# ADR-0018：单密钥多项目——key×project 多绑定与标签选择（标签路由的完整形态）

- 日期：2026-09-15
- 状态：**Accepted（方向）**——产品负责人（用户）2026-09-15 明确指示"一个人一个虚拟 Key 跨项目使用是肯定要实现的"；本 ADR 修订 CLAUDE.md 第 2 节产品决策条目「一个 Virtual Key 固定绑定一个用户、项目、供应商产品、真实凭证和用途」中的"项目"部分（其余维度不变），落地范围与 UX 细节待 leader 复核。
- 关联：issue #613；**完整叙述版（需求/对标/难点全解）：[docs/single-key-multi-project-design.md](../single-key-multi-project-design.md)**；《MiQroKey-Gateway-虚拟密钥与分级用量统计·架构设计报告》§3（单密钥多项目设计来源）；《详细设计》§3.1（V4 DDL 原文）；`ADR-0008`（文件缺失，V4 迁移与缓存决策均引用之；见 feature-backlog D01）；api-contract §7（密钥）；database-schema §virtual_keys/key_project_binding。

---

## 1. 背景：研讨会原始议题与"文档设计 ↔ 实现"的差距

### 1.1 原始议题（2026-08 技术研讨会，leader 提出）

成本按项目分摊是产品第一诉求（"哪个项目烧了多少 token"）。但若"一项目一把真实/虚拟 Key"，一个人参与 N 个项目就要持有 N 把 Key——**换项目＝换 Key＝改配置，违背人类行为，不可接受**。leader 当时提出**后缀**思路：一把 Key 通过后缀区分项目。团队当时未能确定工程实现；此后由《架构设计报告》（2026-08-13 定稿）给出完整设计。

### 1.2 文档设计（事实）

**《架构设计报告》§3.0（标题即议题）：《核心难点：一个虚拟 Key 如何服务多个项目?》**

> 方案：**标签路由**——一个基础密钥 + 项目标签（`mqk_live_<id>.<projectTag>`），标签是明文路由提示、权威在数据库。
> 用户**申请一次密钥**，在 CC Switch 里为每个项目建一个条目（同一 Key + 各自标签），**切换项目 = 条目切换，永不换 Key**；轮换只换密钥段、不动标签。

**§3.2（流程与关键决策）**

> **关键决策：不做"自动检测"，做"编码解析"。** 由密钥自身携带项目声明，网关执行确定性解析 + 授权校验，零启发式、零歧义。
> 解析标签 → 定位项目 → **校验该密钥是否被授权给该项目** → 通过才注入该项目真实凭证转发；校验失败一律 404。

**《详细设计》§3.1 V4 DDL 原文（建表注释）**

> `-- ② 密钥-项目绑定(一个密钥可绑多个项目;授权权威关系)`
> `CREATE TABLE key_project_binding ( … virtual_key_id …, project_id … )`

**§3.5 软著创新点**：「基于 HMAC 项目绑定与标签路由的多项目虚拟密钥体系：**单密钥多项目**、标签明文路由 + 数据库授权为权威、轮换不影响路由」。

### 1.3 实现收缩（取证）

落地时方案被收缩为"一把 Key 固定一个项目、后缀必须等于该唯一绑定的标签"：

| # | 位置 | 事实 |
|---|---|---|
| 1 | `V4__virtual_key_tag_routing.sql`（表注释与装载） | 注释改为 "A virtual key is bound to **exactly one** project"；表约束本身是 `UNIQUE (virtual_key_id, project_id)`（**结构上支持多行**） |
| 2 | `JdbcRouteSnapshotLoader#loadBindings` | `SELECT DISTINCT ON (b.virtual_key_id) …`（每 Key 只取最早一行）；授权经 `JOIN project_provider_grants g ON g.id = vk.grant_id AND g.project_id = b.project_id`（**用 Key 的单值 grant 反查**，多项目必然落空）；结果装入 `Map<UUID keyId, BindingRecord>`（**多行会互相覆盖**） |
| 3 | `VirtualKeyResolver#resolve` | HMAC 通过后：`if (binding.projectTag() == null \|\| !binding.projectTag().equals(parsed.projectTag())) return invalid();`——后缀**等值校验**而非**选择绑定** |
| 4 | `VirtualKeyService#createKey` | 请求体仅 `projectId`（单值）；只写一行 binding |
| 5 | `ModelsController` / `ProxyController` 模型门控 | 授权模型取 `snapshot.grantModels(ctx.key().grantId())`——**Key 的主 grant**，而非请求所解析绑定的 grant |

**后果（本 ADR 要修的三个真实问题）**：

1. **愿景收益未交付**：单密钥多项目不存在——一人多项目仍需多把 Key（回到研讨会原点）；
2. **管理员被迫发明标签**：项目标签是手填 slug，缺标签项目成员无法建 Key（#503 曾以文案兜底）；
3. **改标签静默废 Key**：绑定解析用项目**当前**标签，而存量密钥后缀冻结于签发时刻——改标签/清空即让该项目全部存量 Key 静默 404（无警告、无计数）。

草台感（产品负责人原话）的根因即此：**愿景的形态（后缀）留下了，愿景的能力没实现**。

---

## 2. 决策

### D1. 正式确立 key×project 多绑定模型

一个 Virtual Key 可授权用于**多个项目**；`key_project_binding` 每行 = 一个 `(Key × 项目)` 授权，**每行携带自己的 `grant_id`**（新增列，V53），请求解析到哪一行，凭证/产品/授权模型就取哪一行——与 §3.2 的"校验该密钥是否被授权给该项目"字面一致。

### D2. 后缀 = 每次使用时的项目选择器（不是签发时的固定属性）

密钥核心段 `mqk_live_<publicKeyId>_<secret>` 与项目标签解耦（HMAC 不含标签，既有不变式不变）。**同一条密钥字符串，用户在不同客户端条目里追加不同标签**（`….proj-a` / `….proj-b`），网关按 `(keyId, 标签) → 绑定行` 查询：

- 命中 ACTIVE 绑定 → 以该绑定解析凭证/产品/授权，转发；
- 未命中（未绑定/其它项目标签/伪造）→ 与现有全部密钥失败路径一致：**统一 404（防枚举）**。

签发时打印的字符串仍带主项目标签（便利/向后兼容），但**它只是第一个合法后缀**，不是唯一合法后缀。

### D3. 创建时多选项目；MVP 的附加项目授权选取规则

创建请求新增 `projectIds: string[]`（首个为主项目；`projectId` 保留兼容=单元素）。校验：每个项目 ACTIVE、标签存在、请求者对每个项目有成员资格（管理员豁免）。**主项目**沿用现状：显式选择 grant（即选择凭证/产品/模型范围）。**附加项目**（MVP 规则，服务器确定性选取、审计留痕）：取该项目的**同 `provider_product_id` 且 ACTIVE 的最早创建的 grant**；找不到 → 409 `PROJECT_GRANT_MISSING`（中文可行动文案）。每绑定独立选择凭证的 UX 列为后续增强（见 §6）。

### D4. 轮换复制全部绑定

`rotate` 为新 Key 复制**全部** `(项目, grant)` 绑定行（现状仅复制一行）。轮换只换密钥段，不影响任何项目路由（与 §3.4 原文一致）。

### D5. 模型门控改用"绑定的 grant"

`/v1/models`（ModelsController）与推理模型检查（ProxyController）从 `key.grantId()` 改为 `binding.grantId()`。有效模型集不变式为：`key 的模型集 ∩ 该绑定 grant 的模型集 ∩ 产品目录 ACTIVE`——跨项目天然互不泄漏。

### D6. 标签生命周期（顺带消灭两个陷阱）

1. **自动生成**：项目创建时未填标签 → 由 `code` 派生 slug（清洗为 `[A-Za-z0-9_-]{1,64}`）；冲突或不可用 → `proj-<uuid前12位>`。管理员不再"必须发明 slug"；
2. **存量回填**：V53 将存量 NULL 标签回填为 `proj-<uuid前12位>`（与《详细设计》V4 原文 `proj-||id` 的意图一致）；
3. **被引用即不可改**：项目标签一旦被任一 `key_project_binding` 引用，修改 → `409 PROJECT_TAG_IN_USE`（含受影响绑定计数；**历史绑定不随轮换解除**，故标签在有引用期内为永久不可改——如需更换须评估迁移方案）。无引用的标签可改。**彻底消除"改标签静默废 Key"**。未来如需改名迁移工具（旧标签宽限/双标签），另立 ADR。

### D7. 成员移除的项目粒度（顺带补上一处从未实现的文档语义）

核查发现：`removeProjectMember` 现状**只删成员行**——文档宣称的"成员移出项目 → 该项目下 Key 即时失效"在代码中从未实现（单绑定时代也如此）。本 ADR 将其落地为项目粒度：成员从项目 B 移除 → 禁用该用户 Key 在 **B** 的绑定行（`key_project_binding.status=DISABLED`，其余绑定项目路由不受影响）；若该 Key **不再有任何 ACTIVE 绑定** → 置 `REVOKED`（兑现"该项目下 Key 失效"的既有承诺，且不误伤其它项目）。

### D8. 明确不做

- **不做内容推断**（"拦截每次问答、按内容判断项目"）：架构报告已否决（§3.0：prompt 常跨项目不可靠；网关不读正文、触碰隐私红线）。本 ADR 追加理由：① 财务分摊/对账必须**确定性、可复算**，概率性归属不可入账；② 逐条过分类模型＝额外成本+延迟+把正文喂给另一模型，与"不保存正文"承诺相悖；③ 跨项目问题/元问题无解。**归属永远靠显式声明（后缀），零猜测。**
- 不做主密钥派生后缀（已否决：轮换全失效）。
- 不改"一把 Key 固定一个供应商产品"（本 ADR 只放开"项目"维度）。

---

## 3. 行业参照（本 ADR 论断的外部校验）

云厂商（阿里云百炼之业务空间+空间级 API-KEY；腾讯云 APPID/子账号+账单标签分摊）与业界工具（LiteLLM/Portkey 等虚拟 Key + 请求级 metadata/tags 计费）的归属模型**全部是显式声明**；未检索到任何"从对话内容自动推断成本归属"的产品化先例。本方案的"标签编入密钥字符串"属于**请求级标签**家族的一种免改造形态（客户端配置天然携带，无需在请求体/头里塞字段）；"单密钥多项目 + 明文标签 + 数据库授权权威 + 轮换不动路由"的组合在业内未见对应实现——这正是其作为软著创新点的依据。

---

## 4. 安全分析

| 威胁 | 论证 |
|---|---|
| 伪造/篡改标签 | 标签不参与 HMAC；篡改标签只会使 `(keyId, tag)` 查询落空 → 404。授权权威始终是绑定行 |
| 枚举探测 | 无效密钥/未绑定标签/其它项目标签 → 全部统一 404，响应路径一致（沿用既有防枚举约定；模型越权 403 的例外维持不变——请求方自知所询模型，不构成额外信息泄露） |
| 跨项目串用 | 每请求固定解析到一个绑定行；模型门控、凭证注入、用量归属全部以该行为准；缓存键本来就含 project 维度（§5.2），隔离不回退 |
| 私用/外泄 | 不变：HMAC 摘要存储、一次性展示、轮换宽限、吊销即时、审计链 |
| 多绑定的授权扩散 | 附加项目要求请求者具备该项目成员资格；绑定行逐条审计（`VIRTUAL_KEY_BIND_PROJECT`）；禁用绑定即时生效（快照刷新） |

---

## 5. 兼容与迁移（V53 + API）

**数据库（V53，追加，不修改既有迁移）**：

1. `ALTER TABLE key_project_binding ADD COLUMN grant_id uuid` → 回填 `= virtual_keys.grant_id`（存量单绑定语义完全不变）→ `SET NOT NULL` + FK；
2. `UPDATE projects SET project_tag = 'proj-' || … WHERE project_tag IS NULL`（回填；唯一性由 uuid 前缀保证，冲突时循环加长）；
3. 注释修正 V4 遗留的 "exactly one" 表述（以表注释 COMMENTS 记录，不改 V4 文件本体）。

**装载器/解析**：`loadBindings` 去除 `DISTINCT ON`，改 `JOIN … g.id = b.grant_id`；快照 `bindings` 改为 `(keyId, tag)` 复合键；`binding(keyId, tag)` 访问器；解析器改"按后缀取绑定"。存量单绑定 Key 的解析结果与今日完全一致。

**API（向后兼容）**：`POST /me/virtual-keys` 增 `projectIds`（缺省时取 `projectId` 单元素）；响应与视图增 `boundProjects:[{projectId, projectTag}]`（列表/详情展示）；审计摘要增项目数。契约同步 api-contract §7 与 generated OpenAPI。

**前端**：建 Key 表单项目单选 → 多选（列出用户可见项目，勾选 ≥1，首个为主项目并沿用其产品/凭证选择）；Key 列表/详情展示"可用于 N 个项目（标签…）"。

**存量行为不变性声明**：现有全部 Key（单绑定）路由结果、模型集、用量归属与迁移前逐字节一致；回填标签使原本缺标签的项目立即可建 Key（纯增益）。

---

## 6. 影响面清单（实现文件级）

- **迁移**：`V53__key_project_multi_binding.sql`（新增）
- **domain**：`RouteSnapshot`（bindings 复合键 + `binding(keyId, tag)` + `BindingRecord.grantId`）、`KeyProjectBinding`（+grantId）
- **route-snapshot**：`JdbcRouteSnapshotLoader#loadBindings`
- **gateway-app**：`VirtualKeyResolver`（按后缀取绑定）、`ModelsController`/`ProxyController`（门控改绑定 grant）、`AuthContext` 注解
- **control-plane-app**：`VirtualKeyService`（createKey 多项目 + rotate 复制全部绑定 + 附加项目 grant 选取）、`CreateVirtualKeyRequest`（+projectIds）、`CreateVirtualKeyResponse`/`VirtualKeyView`（+boundProjects）、`AdminOrgService`（标签自动生成 + 引用守卫）、成员移除钩子（按绑定行禁用）
- **frontend**：`NextKeysView`（建 Key 多选 + 列表展示）、`api/index.ts`
- **测试**：装载器/解析器契约测试（含"同 Key 双后缀 → 各自凭证"）、创建/轮换/守卫 IT、迁移断言、前端 spec
- **文档**：本 ADR、api-contract、database-schema、architecture（绑定小节）、progress.md、decisions/README 索引

---

## 7. 开放问题（不阻塞本 ADR 落地）

1. **附加项目的凭证选择 UX**：MVP 为"同产品最早 ACTIVE grant"；后续可让用户在创建时逐项目选择凭证（多 grant 场景）；
2. **per-binding 模型范围**：MVP 维持 key 级模型集 ∩ 各绑定 grant；若产品要求"同一 Key 在项目 A 可用模型 X、在项目 B 不可用"，需将模型范围下沉到绑定（数据模型已预留——绑定行可扩展列）；
3. **标签改名迁移工具**：当前"被引用即不可改"是安全默认；若确有强需求，另立 ADR 设计"双标签宽限切换"；
4. **审批流的 key×project 归属**：`model_approval` 现挂 key；多绑定后"申请针对哪个项目"需显式化（申请时带标签），列入后续 Goal。

---

## 8. 实施顺序（Accepted 后）

1. V53 迁移 + 领域/装载/解析（网关正确性核心，契约测试先行）；
2. 控制面：创建多项目 + 轮换复制 + 标签自动生成/守卫 + 成员移除粒度；
3. 前端多选与展示；
4. 演示最小闭环：一把 Key 两个后缀（CC Switch 双条目）→ 两项目各自凭证/用量；随后全量回归 + 文档收口。
