# ADR-0020：配额软着陆——规则级 opt-in 超限拒绝

- 状态：**Accepted（2026-09-16）**——owner 2026-09-16 拍板「**软着陆：拒绝请求，不自动禁用 Key**」，并采纳 [ADR-0019](0019-quota-hard-block-proposal.md) 决策点 1 的**方案 B（规则级 opt-in 拒绝）**。本 ADR 取代 ADR-0019（其状态改标 **Superseded by ADR-0020**，保留原文）。本 ADR 同时**修订** CLAUDE.md §2 产品决策「不限流、不因预算阻断，只做 Webhook 告警」（原文见 [CLAUDE.md](../../CLAUDE.md)），修订边界见 §3。
- 落地进度（2026-09-16，#684 块①）：**仅「控制面周期评估落库 + 路由快照装载」已交付**；**网关热路径的实际拒绝（429 信封）与前端配置界面尚未实现**（块②③）。因此**网关目前不会因配额拒绝任何请求**：`RouteSnapshot` 的 blocked-scope 投影**没有任何生产消费点**，本批对运行时行为**零影响**。
- 日期：2026-09-16
- 关联：issue #684（本决策的工作令与验收口径，含 owner 拍板记录）；[ADR-0019](0019-quota-hard-block-proposal.md)（被本 ADR 取代的提案原文）；[ADR-0002](0002-transparent-proxy.md)（透明代理：不触碰请求内容）；[ADR-0005](0005-no-redis-v1.md)（第一版不引 Redis）；#683 / PR #686（COST + YEARLY + NEAR_LIMIT 已交付，迁移 V58）；[api-contract §5.19](../api-contract.md)；[database-schema](../database-schema.md)；[feature-backlog F51](../feature-backlog.md)；腾讯云 AI 网关「配额管理：超限处理=拒绝请求」（owner 2026-09-16 样本）

## 1. 背景

配额规则（V23/V58）此前为 **alerting-only**：水位读时计算、`QUOTA_THRESHOLD` Webhook 告警、`NORMAL/WARNING/NEAR_LIMIT/EXCEEDED` 四档展示，**永不阻断**（api-contract §5.19 明示「硬阻断需 ADR」）。2026-09-15/16 的 Key 被外部持续调用事件暴露了该形态的缺口：**配额系统「只算不管」**——规则、水位、告警齐全，但网关对配额零感知，任何一把 Key 被持续使用都不会被自动刹住。

ADMIN 要求对齐腾讯云 AI 网关「配额管理」：配额使用达到 100% 时自动拒绝新请求，直到配额重置或提额。owner 于 2026-09-16 拍板**软着陆**形态：**拒绝请求，不自动禁用 Key**。

## 2. 决策

### D1. 每条规则可选超限动作：`ALERT`（默认）| `REJECT`

`quota_rules` 增 `enforcement` 列；**数据库默认 `ALERT`**，故**存量规则的语义与行为逐字不变**。`REJECT` 为**逐规则显式 opt-in**——不存在租户级总开关，也不会被继承或隐式打开（对照 ADR-0019 决策点 1 的方案 C：多一层继承语义，50 账号规模下收益不足，不采用）。

### D2. 软着陆语义（对齐腾讯，owner 拍板）

超限作用域的请求被网关拒绝，**Key 本身不失效**：不删除、不停用、不轮换、不告警降级。配额**周期滚动**、管理员**上调限额**或**停用/删除规则**后**自动恢复**，无需人工解封。拒绝的作用域是 **USER / PROJECT**（既有维度）；命中多条 `REJECT` 规则时**任一超限即拒**；共享同一用户/项目的其它 Key **一并生效**（这是「治理作用域」而非「治理单把 Key」的必然结果，也是与「禁用 Key」的关键区别）。

### D3. 实现走既有架构：控制面评估 → 阻断投影 → 路由快照，**网关热路径零查询**

- 控制面按周期重算越线作用域，写入 `quota_enforcement` 投影表；
- 快照刷新沿用既有通道（`RouteRefreshPublisher` → NOTIFY + 轮询），**不引入新的推送机制**；
- 网关只在内存集合中判定，**不新增任何热路径 DB 查询**（ADR-0005 不引 Redis 的约束因此不构成阻塞：判定所需状态极小且可由快照承载）。

### D4. 近似语义如实文档化（不承诺精确阻断）

阻断的水位来自**周期评估**而非实时计数：upstream 口径的误差 = 评估周期内的在途与新增用量（默认周期 60s），即额度可能被超出**一个评估周期内**的量。该近似是本类系统的常态（腾讯口径亦为「配额使用情况每小时更新」），**必须在 api-contract / 前端如实标注**，不得宣称精确。

### D5. 失效开放（fail-open）而非失效阻断

快照装载**只取窗口仍有效**的阻断行（`window_to > now()`）：评估器停摆、控制面不可用或窗口已过时，网关**放行**请求而不是继续阻断。理由：本能力是**额度治理**，不是安全边界；控制面故障不得演变为数据面停摆（与「不因预算阻断可用性」的原始决策精神一致）。

### D6. 明确不做（与 ADR-0019 一致的边界）

- **不做限流**：无令牌桶、无 QPS 语义——这是**额度治理**（quota），不是**速率治理**（rate limit，见 feature-backlog F47）；
- **不做硬熔断**：不自动禁用/吊销 Key、不自动轮换凭证、不自动改动供应商侧配置；
- **不做跨供应商路由或降级**：拒绝就是拒绝，不换凭证、不换供应商、不做负载选择；
- **不触碰请求内容**：准入判定发生在转发之前，不读、不改写、不重排请求 JSON（ADR-0002 不变）。

## 3. 对「不因预算阻断」红线的修订边界

CLAUDE.md §2 原文为「**不限流、不因预算阻断，只做 Webhook 告警**」。本 ADR 将其修订为：

> **默认不限流、不因预算阻断，只做 Webhook 告警；显式置为 `REJECT` 的配额规则可在其作用域超限时拒绝请求（软着陆）。仍不做限流、不做自动故障切换或凭证失效。**

修订的**边界**（超出即需新 ADR）：

| 维度 | 本 ADR 允许 | 仍禁止 |
|---|---|---|
| 默认行为 | `ALERT` 默认，存量零变化 | 任何默认开启的阻断 |
| 阻断对象 | 超限作用域（USER/PROJECT）的**请求** | 凭证生命周期（禁用/吊销/轮换/改供应商配置） |
| 触发依据 | 显式 `REJECT` 规则的当期水位 | 隐式/推断/继承；速率（QPS）|
| 故障姿态 | fail-open（窗口过期即放行） | fail-closed / 永久阻断 |
| 数据面 | 内存集合判定，零热路径查询 | 热路径同步查询控制面、引入 Redis（ADR-0005） |
| 请求内容 | 不触碰（ADR-0002） | 读写/改写/重排请求 JSON |

CLAUDE.md §2 的措辞修订本身**尚未落盘**（该文件属项目级指令，改动需 owner 确认）；本 ADR 作为决策记录先行，修订文本以上表为准。

若 owner 确认修订，需一并复核/更新的措辞落点（本 ADR 不改动它们，以免越过 owner 授权）：

| 落点 | 现值 | 处置 |
|---|---|---|
| `CLAUDE.md` §2 | 「不限流、不因预算阻断，只做 Webhook 告警」 | 待 owner 确认后按上表修订 |
| `README.md` 产品边界 | 「不限流、不因预算阻断请求；通过 Webhook 告警」 | 同上 |
| `docs/session-handover.md` | 「不限流不因预算阻断（硬阻断需 ADR）」 | 同上 |
| `docs/platform-middleware-roadmap.md` F51 段 | 「硬阻断（超限拒绝）需 ADR 反转决策」 | 同上（ADR 已反转，措辞待更新） |
| `api-contract.md` §项目预算 | 「只预警不阻断」 | **不改**：该句描述的是**项目预算**口径，本 ADR 只对**配额规则**开放 `REJECT`，预算阻断仍不在范围内 |
| `docs/progress.md` 历史条目 | 各批次当时的判断 | **不改**：历史记录保持原样，由本条与 §4 记录现状 |

## 4. 已落地形态（#684 块①，2026-09-16）

- **迁移 V59**（追加，不改既有迁移）：`quota_rules.enforcement varchar(16) NOT NULL DEFAULT 'ALERT'` + `CHECK (enforcement IN ('ALERT','REJECT'))`；新表 `quota_enforcement`（`(tenant_id, scope_type, scope_id)` 唯一约束、`rule_id … ON DELETE CASCADE`、`metric`/`period` CHECK、`window_to > window_from` CHECK、`rule_id` 索引与 `window_to` 索引）。
- **领域枚举**：`QuotaRuleEnforcement { ALERT, REJECT }`（`backend/domain`）。
- **控制面评估器**：`QuotaEnforcementService` 按 `REJECT` 规则重算越线作用域并幂等写/删投影（同一投影二次重算不写库；行新增/删除或字段变化时才触发快照刷新）；`QuotaEnforcementScheduler` 周期 `miqrokey.quota.enforcement-interval-ms`（默认 `60000`），**单租户评估失败只跳过该租户、不中断整轮**。
- **管理 API**：`QuotaRuleView` / `UpsertQuotaRuleRequest` 增 `enforcement`（缺省 `ALERT`；省略时**保留原值**；非法值 → `400 PARAM_INVALID`）；OpenAPI 基线已再生。
- **路由快照**：`RouteSnapshot` 增 blocked users/projects 集合（保留旧构造，既有调用点不破）；`JdbcRouteSnapshotLoader.loadBlockedScopes()` 只装载 `window_to > now()` 的行（D5 的 fail-open 落点）。
- **投影表不是第二事实来源**：它是规则状态的投影，随规则/窗口变化被重写，并因限额上调、规则停用/删除（FK 级联）或周期滚动而消失。

## 5. 未落地（块②③，本 ADR 明确记录为「未实现」）

- **块②（网关热路径拒绝）**：429 响应与错误信封、按作用域的准入判定、`QUOTA_REJECTED` 告警、数据面增量计数。**在块②合入前，网关不会拒绝任何请求**——`RouteSnapshot` 的 blocked 集合在生产代码中零消费。
- **块③（前端）**：配额规则页的 enforcement 表单项与列表展示、自服务「我的配额」的同 DTO 可见性。
- **待定细节（块②拍板时对齐 api-contract 既有错误码惯例）**：拒绝信封的**状态码已定 429**（可重试语义：周期滚动后自动恢复）；`code` 字面量在 issue #684 正文写作 `quota_exceeded`、在 ADR-0019 中写作 `QUOTA_EXCEEDED`，**以仓库既有错误码大小写惯例为准**在块②统一，并同步 api-contract 与 OpenAPI。
- **已知前置**：前端 `src/types/generated.ts` 与刷新后的 OpenAPI 基线存在漂移，块③ 必须重跑 `npm run gen:types`（CI 的 codegen drift check 在下一个触碰 `frontend/**` 的 PR 会校验）。

## 6. 后果

- **正面**：滥用可被自动刹住，且**默认行为零回归**（`ALERT` 默认 + 逐规则 opt-in）；不动凭证生命周期，避免「超预算即封号」的可用性事故；复用既有快照链路，数据面零新增查询。
- **代价/风险**：
  - **近似窗口内超额**（D4）：可能超出「一个评估周期」的量，须在文档与前端如实标注；
  - **快照滞后的观感差异**：页面水位是读时计算、网关用快照，可能出现「已拒绝但页面未满」的短时观感差（页面须标注 `synced_at`）；
  - **共享作用域的外溢**：同一项目的其它 Key 会被一并拒绝——这是设计意图（D2），但必须在管理界面给出可行动解释；
  - **控制面调度压力**：评估器与其他 `@Scheduled` 任务共用控制面默认单线程调度池（未设 `spring.task.scheduling.pool.size`），租户/规则量增长时须关注排队（已记入 configuration-reference 容量注记）。

## 7. 未决问题

1. 拒绝事件告警（`QUOTA_REJECTED`）与数据面增量计数：issue #684 明确列入后续对齐项，**未纳入块②③**；
2. CLAUDE.md §2 措辞修订的落盘时机（本 ADR §3 已给出修订文本，等待 owner 确认后写入该文件）；
3. `code` 字面量统一（见 §5）；
4. 多实例部署下的并发重算（当前无分布式锁，与仓库既有调度器同款；单客户私有化单实例部署下无影响）。
