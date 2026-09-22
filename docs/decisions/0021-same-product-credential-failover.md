# ADR-0021：同产品凭证回退——多凭证切换与「每笔唯一归属」的兼容设计

- 日期：2026-09-18
- 状态：**Accepted（有条件）——2026-09-22 owner 拍板「有条件采纳」**，条件见 §0。**本文其余章节保留提出时的提案原文与论证，不代表已被逐条实现、更不代表其中的选项取舍已全部生效**；实现跟踪见 issue #704（ADR 先行）。
- 效力（经 §0 条件限定后生效）：将修订 [CLAUDE.md](../../CLAUDE.md) §2「不自动故障切换」与 [architecture.md](../architecture.md) 的「禁止跨供应商或跨真实凭证故障切换」。**修订范围严格限定为：同一供应商产品内、首字节前、凭证级的显式回退**。跨供应商/跨产品的自动路由与故障切换维持红线不变（feature-backlog F46 维持 DECLINED）。**该修订尚未执行**，见 §0 末尾的待决项。
- 关联：issue #717（本 ADR 的提出）、issue #704（实现跟踪，**ADR 先行**）；[ADR-0002](0002-transparent-proxy.md)（透明代理——本议题不改写请求内容）；[ADR-0018](0018-single-key-multi-project.md)（key×project 多绑定，`grant_id` 的来源）；[ADR-0020](0020-quota-soft-landing.md)（opt-in + 默认关闭的取舍风格、配额判定集）；feature-backlog F46（跨供应商切换，DECLINED）/ F50（多服务绑定，ADR）；[ai-gateway-comparison](../ai-gateway-comparison.md) §「多 Key 均衡/Key 池轮询」；[operations-runbook](../operations-runbook.md) §5（供应商故障处置）；[bill-reconciliation-contract](../bill-reconciliation-contract.md)（F19 对账）；V1/V6/V8/V57（见 §1.3）、V9/V64/V67（见 §2-Q4、§2-Q2 与 §4）。

---

## 0. 采纳条件（owner 2026-09-22 拍板）

方向**采纳**，但**范围与不变量先钉死**，满足后才进入实现：

- **首期只允许「同供应商 + 同 product/model 兼容池内」的显式回退。**
  **不**放开跨供应商语义切换（`DeepSeek → Kimi`、`DeepSeek → GLM`）—— **跨供应商 fallback 另开 ADR。**
  > **「兼容池」的判定谓词尚未定义**（同 product 不同 model / model alias 不同 / capability 不同 /
  > 同 `provider_product` 下多个 model —— 哪些算同一个池？）。它在**本 ADR 里只是自然语言约束**；
  > 具体 predicate 属 **#704 实现设计**，必须在实现时形成**确定的判定函数并写入测试**，
  > 否则这条硬条件无法被机器检验（review #1365 的 P3）。
- `virtual_key_id`、`project_id`、`gateway_request_id` **在整条 fallback 链中不变**。
- **每一次 credential attempt 都有自己的可追踪记录** —— 不能最后只留下「这次请求用了 credential C」。
  否则出现「第一次 credential A 已发出请求 → 上游有无计费不确定 → 第二次换 credential B 成功」时，
  **根本无法做可信对账**。
- 不改变 tenant / project / key 归属；**不能绕过 quota**。
- 成功 / 失败的**计量规则明确**；全部 credential 失败时的行为**固定**。
- **默认关闭。**

### 待决（本 ADR 未覆盖，需 owner 另行拍板）

上一条「效力」要求修订 `CLAUDE.md` §2「不自动故障切换」与 `architecture.md` 的相应表述。
**该修订尚未执行** —— 是否按本 ADR 的限定范围落地，待 owner 明确（未拍板前，`CLAUDE.md` 的红线原样有效）。

---

## 1. 背景

### 1.1 原决策与它的语境

[CLAUDE.md](../../CLAUDE.md) §2 的两条不可变决策构成了今天的形态：

- `CLAUDE.md:35`：「一个 Virtual Key 固定绑定一个用户、项目、供应商产品、**真实凭证**和用途；不跨供应商，不负载均衡。」
- `CLAUDE.md:36`：「**不自动故障切换**；首字节前最多安全重试一次，流开始后不重试。」

配套的落地表述散落在四处：[architecture.md:161](../architecture.md)「禁止跨供应商或跨真实凭证故障切换」、[product-requirements.md:27](../product-requirements.md)「不做供应商之间的自动路由、负载均衡或故障切换」、[provider-adapter-contract.md:126](../provider-adapter-contract.md)「上游业务错误原样返回，不跨凭证/产品自动重试」与 [provider-adapter-contract.md:128](../provider-adapter-contract.md)「供应商故障不自动切换」、[ai-gateway-comparison.md:33](../ai-gateway-comparison.md)「**不自动故障切换**（首字节前最多安全重试一次）| 刻意差异」。

这个决定的语境是首版：单租户私有化、50 个账号、单人运营，且**一个产品通常只有一把凭证**——「切换」在当时不是一个可执行的动作。当时的替代方案被明确记录为运维流程而非数据面能力：[operations-runbook.md:99](../operations-runbook.md)「系统不自动跨供应商切换」+ 用户自行在 CC Switch 换一个已配置项；[provider-adapter-contract.md:128](../provider-adapter-contract.md) 同义。

### 1.2 发生了什么变化

1. **同一产品下已天然存在多把凭证**，且这不是例外而是常态：轮换期双活（旧凭证 `DRAINING` + 新凭证 `ACTIVE`）、同一供应商的多账号/多 Plan 混用（预留 + 按量）、团队 Plan 的成员 key。凭证的 `status` 枚举已包含 `PENDING_VALIDATION / ACTIVE / DRAINING / DISABLED / INVALID`（`V1__core_tables.sql:288-291`），轮换生命周期早已存在。
2. **单凭证的限流/失效直接等于整条路断**。今天的后果不是「降级」而是「失败」：上游 429/401/连续 5xx 时，网关原样把错误返回给客户端，客户端只能自己重试或人工干预。
3. **#704 提出了需求**：同一供应商产品内，按配置顺序自动切换到下一把 ACTIVE 凭证，且「需保持每笔调用可归因，不得破坏现有审计模型」——即需求方自己就把**审计唯一性**列为一等约束。

### 1.3 现状证据（带坐标）

「一次调用 = 一把虚拟 Key × 一份绑定 × 一把上游凭证」这个唯一性**不是在注释里，而是被三层结构分别钉死的**：

| 层 | 证据坐标 | 事实 |
|---|---|---|
| 授权 | `V1__core_tables.sql:359` | `project_provider_grants.upstream_credential_id uuid NOT NULL`——授权（grant）携带**恰好一把**凭证 |
| 授权 | `V1__core_tables.sql:373-374` | `uq_grants_project_product_credential UNIQUE (project_id, provider_product_id, upstream_credential_id)`——唯一性是**三元组**，**允许**同一 project+product 存在**多行、各带不同凭证**的 grant |
| 快照 | `RouteSnapshot.java:251` | `record BindingRecord(UUID keyId, UUID projectId, String projectTag, UUID credentialId, UUID productId, UUID grantId)`——绑定里**只有一个** `credentialId` |
| 快照 | `AuthContext.java`（record 定义） | 请求上下文持有**一个** `binding`；`credentialId()` 经 `binding` 取得，全链路无第二个候选 |
| 路由 | `ProxyController.java:454` | `// the same credential (no cross-credential failover).`——凭证在任何尝试之前解析一次 |
| 路由 | `ProxyController.java:632` | `* resolved once for all attempts (no cross-credential failover).`——重试规则显式声明复用同一凭证 |
| 路由 | `ProxyController.java:471` | `Retry.max(1)`，filter 为 `retryableConnectionFailure`；后者要求 `attempt.ttfb.firstByteMillisRaw() == 0`（判据在 `ProxyController.java:634-635`；`:632` 是它的 javadoc，两处坐标勿混用） |
| 熔断 | `ProxyController.java:445` / `:501` | 熔断按 `(productId, credentialId)` 键控——**凭证已经是熔断的最小单位** |
| 账本 | `V8__request_usage_records.sql:39` | `credential_id uuid NOT NULL`（单数、非空） |
| 账本 | `V8__request_usage_records.sql:55` | `retry_count integer NOT NULL DEFAULT 0`——**重试已在同一行内计数，从未拆成多行** |
| 账本 | `V8__request_usage_records.sql:69` | `UNIQUE (started_at, gateway_request_id)`——idempotency 目标，即「一请求一行」的 DDL 表达 |
| 用量 | `V6__usage_events.sql:24` / `:47` | `usage_event.credential_id` **可空**；唯一索引在 `(tenant_id, provider_request_id)` |
| 未归属 | `V57__unattributed_policy.sql:8` | 「不变量：**归属未知永不借用具体项目的 grant/凭证**」——唯一性以散文形式写在 migration 里 |

**两个此前未被写下来的事实（本次核查发现）**：

- `upstream_credentials` 上**没有**任何限制「一产品一凭证」的约束——唯一的唯一约束是 `UNIQUE (tenant_id, id)`（`V1__core_tables.sql:309`）。凭证挂在 `upstream_subscriptions`（`V1__core_tables.sql:207`，`provider_product_id` 在 `:210`），而一个产品可以有**多个 subscription、每个 subscription 可以有多个 credential**。**「凭证池」在数据模型上不需要新建，已经存在。**
- 真正禁止切换的**不是凭证表，而是 grant 的单数 `upstream_credential_id` 加上请求期的单 binding 解析**。换句话说：要放开的是**选择规则**，不是**数据模型容量**。

**反向证据（同样是发现）**：`docs/*.md` 全文检索 `唯一归因|唯一身份|一笔一|每笔绑定|可归因` **零命中**。这条被 #704 当作一等约束的不变式，目前**只存在于代码与 DDL 中，没有任何散文表述**——本 ADR 是它第一次被写成文字。

> 检索口径：**除本段自身与本 ADR 落盘后的 `docs/progress.md` 记录之外**，全仓无第二处来源。两处命中都源于本 ADR，属自指，不构成「既有表述」。

### 1.4 与既有决策/红线的关系（逐条）

| 既有决策 | 坐标 | 本议题与之的关系 |
|---|---|---|
| 「不自动故障切换」 | `CLAUDE.md:36` | **直接冲突**，是全篇唯一需要 owner 明确同意才可动的红线 |
| 「禁止跨供应商或跨真实凭证故障切换」 | `architecture.md:161` | **直接冲突**：该句显式点名「跨真实凭证」，无法靠解释绕开，必须修订 |
| 「首字节前最多重试一次……**真实凭证只在第一次尝试前解析一次，重试复用同一凭证**」 | `architecture.md:159` | **直接冲突（第二条被遗漏的红线）**：该句字面规定了「重试复用同一凭证」，正是本 ADR 要改的语义。它与 `:161` 是**并列的两条**，owner 只看 `:161` 不足以覆盖本条 |
| 「不做供应商之间的自动路由、负载均衡或故障切换」 | `product-requirements.md:27` | **不冲突**：限定词是「供应商**之间**」。同产品内凭证回退不在其字面范围内 |
| 「上游业务错误原样返回，不跨凭证/产品自动重试」 | `provider-adapter-contract.md:126` | **冲突**，需修订为「不跨产品；是否跨凭证由本 ADR 决定」 |
| 「网络连接建立前且请求体尚未发送时，可按统一策略进行一次安全重试；流式开始或非幂等请求发送后禁止重试」 | `provider-adapter-contract.md:127` | **支持本 ADR 的一条授权基础**：回退正是「请求体已发送前」的安全重试在同一产品内的有序扩展。**本 ADR 明确不动这一行**——它与 `architecture.md:159` 的幂等性论证是同一来源 |
| 「供应商故障不自动切换」 | `provider-adapter-contract.md:128`、`operations-runbook.md:99` | **不冲突**：两者都指**跨供应商**；同产品回退是另一层 |
| 「Adapter 的后台失败只标记能力陈旧，**不自动吊销凭证**」 | `provider-adapter-contract.md:125` | **支持本 ADR 的一条约束**：切换不得伴随自动吊销/自动禁用凭证 |
| 「Higress 多 Key 均衡/Key 池轮询——**刻意不采纳**（1:1 固定绑定；**Key 池轮询破坏审计映射**）」 | `ai-gateway-comparison.md:93` | **必须正面回答的反对理由之一**。注意其否决对象是**轮询/均衡**（无差别的选择），而回退是**有明确触发条件与固定顺序**的选择——§3 的「方案 C 的正面复用说明」正是对它的正面回答 |
| 「Higress 模型 Fallback/降级链——**刻意不采纳**（不自动故障切换）」 | `ai-gateway-comparison.md:94` | **必须正面回答的反对理由之二，且是字面上最接近本议题的一条**（该行不含「轮询/均衡」限定，字面可读作否决一切 fallback）。§3 末尾「与 `ai-gateway-comparison.md:94` 的分界」逐字回应它 |
| 「**降级/Fallback**：触发条件多选 + 备用服务按序 / Fallback 按序兜底 / **不自动故障切换**（首字节前最多安全重试一次）/ 刻意差异」 | `ai-gateway-comparison.md:33` | **冲突**：这是把「不自动故障切换」写成对标结论的一行，与 `CLAUDE.md:36` 同级。若 Accepted，本行需补注「同产品凭证回退除外」 |
| 「模型多服务路由/负载均衡/灰度：红线」 | `ai-gateway-comparison.md:123` | **不冲突**：红线对象是负载均衡与灰度，不含失败回退。若 Accepted 仅需补注，不改变其红线地位 |
| 腾讯「降级策略（服务异常/超时/429 触发，按序 Fallback）」 | `tencent-ai-gateway-mapping.md:22`（第 13 行） | 状态记为「**冲突**」。本 ADR 处理该冲突；注意腾讯的「按序 Fallback」本就是**跨服务/跨产品**，本 ADR 只采纳其「按序」形态并**收窄到同产品内** |
| F46「智能路由/自动选模型；降级 Fallback；跨供应商故障切换」= DECLINED | `feature-backlog.md:109` | **不推翻**。F46 的红线是「不负载均衡/不跨供应商/不读正文」；本 ADR 同产品内有序回退三条都不触碰。F46 保持 DECLINED |
| F50「Virtual Key 多服务绑定/Header 分流路由」 | `feature-backlog.md:113` | **不冲突**：F50 动的是 **Virtual Key → 服务** 的绑定（本 ADR 不动），本 ADR 动的是 **grant → 凭证** 的选择 |
| ADR-0020 opt-in + 默认关闭 | `0020-quota-soft-landing.md` §D1 | **风格沿用**：默认关闭、按规则开启、未开启时行为与现状**完全一致** |
| ADR-0009 双重 opt-in | `0009-enable-response-cache.md` | **风格沿用**：能力默认关，且需显式声明才生效 |
| ADR-0002 透明代理 | `0002-transparent-proxy.md` | **不冲突**：本议题不改写、不重排、不补写请求 JSON |
| 账单不做自动导入/对账 | `product-requirements.md:33` | **约束了 §2-Q2 的答案**：不能承诺「失败尝试不计费」，只能承诺「不计入本系统账本」；上游差额归 F19 对账范围 |
| 「不自动禁用 Key」「不加限流」 | `CLAUDE.md` §2 | **不触碰**：本 ADR 不引入限流、不改 Key 生命周期 |

---

## 2. 决策点（逐条回答 #717 的六问）

> 本节每条给出**问题 → 现状 → 建议结论 → 理由 → 备选**，是**提出时的论证记录**。
> 其中的「建议结论」若与 §0 的 owner 拍板条件冲突，**一律以 §0 为准**；写成「待 owner 拍板」的地方
> 不表示今天仍未决——**当前未决项只有 §0 末尾那一处**（`CLAUDE.md` / `architecture.md` 的修订）。

### Q1 审计锚点：`credential_id` 记首次还是最终？多次尝试要不要各记一行？

**现状**：一请求一行（`V8:69` 唯一约束），`credential_id` 非空单值（`V8:39`），重试在同行的 `retry_count` 计数（`V8:55`）而非新起一行。

**建议结论**：**保持「一请求一行」不变**；`credential_id` 记**实际产生该行终态的那把凭证**——即成功那次尝试所用的凭证；若全部尝试失败，记**最后一次尝试**的凭证。**尝试明细另立一张只追加的表**（例如 `request_credential_attempts`），一行一次尝试，只存 id/序号/结果/耗时/上游 request id，**不存正文**。

**理由**：
- `request_usage_records` 的语义是**生命周期与计费行**，且它的写入契约是「guarded upsert、finalize 一次、已 finalize 的业务字段永不重写」（`V8` 文件头注释）。把一次请求摊成多行会直接破坏这个契约。
- 若 `credential_id` 记「首次」，则成功产生的 token/成本会被挂到一把**什么都没产出**的凭证上——成本报表、凭证维度的用量、熔断统计（`ProxyController:445` 本来就按 `(productId, credentialId)` 键控）会同时失真。记「最终成功者」让既有的所有凭证维度视图**语义不变**。
- 尝试轨迹是**审计诉求**，不是账本诉求。它与账本的行数、保留期、写入频率都不同，混在一张表里会让两个诉求互相绑架。

**备选**：(a) 不记尝试明细，只留 `retry_count` —— 信息不足以回答「换了哪把、为什么换」，审计上等于没有；(b) 把尝试明细塞进 `request_usage_records` 的 jsonb 列 —— 省一张表，但破坏该表的定长字段契约与分区裁剪；(c) 只记 `switch_count` 与首/末凭证 id —— 折中，成本最低，但无法逐次定位失败原因。

### Q2 计费语义：失败的那次调用是否产生费用？

**现状（必须分成两种失败分别陈述，二者今天的行为并不相同）**：`usage_event`（`V6`）是计费事实表，金额来自响应中解析出的 token 与价目快照（`V64__usage_event_price_snapshot.sql` 存在）。

- **连接阶段失败**：没有响应体，解析不出 token，且这类失败走的是 `Retry` 的错误通道，从不进入响应管线。今天的**唯一发布点在响应管线内**（`ProxyController.java:571`，由 `:559` 的 `writeWith(...).then(...)` 触发），错误路径不调用它——因此连接阶段的失败尝试今天确实不产生 `usage_event`，**在本系统内零计费**。
- **响应阶段的失败（429 / 401 / 403 / 5xx）**：这些是 `exchangeToMono` 的**正常返回**（`ProxyController.java:526-530` 对所有状态码统一进入该分支，无 `onStatus` 过滤），**今天会照常走到 `:571` 并写出一行 `usage_event`**（`upstream_status_code` = 429 等、token 为空）。`request_usage_records` 同样落行（写入器 `PostgresUsageEventWriter.java:253,302` 的 guarded upsert（两处 `INSERT INTO request_usage_records`，显式列名）；测试 `PostgresUsageEventWriterTest.java:137-147` 用 `UPSTREAM_REJECTED / 429` 明确覆盖这一形态）。**发布点唯一的门槛是 `modelName != null`（`ProxyController.java:649-661`），与状态码无关。**

**这两类失败恰好都出现在 Q7 的回退触发表里**——所以「失败尝试的计费语义」**不是沿用现状就能满足的**。

**建议结论（规范性要求，含明确改动点）**：**只有产生终态的**那次尝试进入 `usage_event`；**非终态尝试**（被回退跳过的那些）**不生成** `usage_event`、**不生成**成本行。落到实现上，这是一处**必须的改动**，而不是现状：

1. `ProxyController.java:571` 的发布须按「**仅终态尝试**」门控——否则第一次尝试的 429 会先落一行，回退成功后再落一行；
2. `ProxyController.java:663` 的凭证来源须由 `ctx.binding().credentialId()`（**绑定的凭证**）改为**实际产生终态的尝试所用凭证**——这是 INV-2 的直接要求，不改则账本会记在「被跳过的那把」上；
3. `request_usage_records` 同理：其 `credential_id` 是 NOT NULL 单值（`V8__request_usage_records.sql:39`），`retry_count`（`:55`）已经承担了「重试了几次」的表达（`:69` 的 `UNIQUE (started_at, gateway_request_id)` 钉死「一请求一行」），因此**不新增行**，只改行的取值来源。

**明确不承诺**「上游也没计费」：上游对本系统不可观测；确需核对的差额走既有的 F19 对账契约（`docs/bill-reconciliation-contract.md`，按 `provider_request_id` 等四级匹配），**本 ADR 不新造对账机制**。

**理由**：
- #717 的原话是「上游可能已计费但未返回」——这个可能性**无法在网关侧消除**，只能被**观测**。任何在网关内「为失败尝试也记一笔」的做法都会凭空创造一笔未经证实的成本，反而污染账本。
- 好消息是**纳入回退的失败类型大多是「未到达推理」的错误**：连接失败（请求根本没发出）、429（被上游拒绝）、401（鉴权拒绝）。这三类发生在推理之前，供应商通常不计费。真正模糊的是「推理中途 5xx」——而**首字节后的失败根本不重试**（见 Q6/`architecture.md:160`），所以它不会进入回退路径。**回退的触发条件本身就把它限制在了「大概率未计费」的区间内**。
- 与 `product-requirements.md:33`（不自动导入供应商账单）一致：本系统的账本是**自记账本**，不是供应商账单的镜像。

**待 owner 确认的事实**：若 owner 要求「失败尝试也留痕计费」，则本条的结论需重写——那不是本 ADR 的建议。

### Q3 归属不变式（#717 要求明文写进 ADR）

**建议结论**：写成明文不变式，作为实现与测试的强制约束：

> **INV-1**：一次客户端请求（`gateway_request_id`）自始至终对应**同一** `tenant_id`、**同一** `virtual_key_id`、**同一** `project_id`、**同一** `provider_product_id`。凭证回退**只允许**改变「使用哪把上游凭证」，**不允许**改变上述四个字段中的任何一个。
>
> **INV-2**：`request_usage_records` 对该请求**至多一行**，其 `credential_id` 是**实际产生该行终态的唯一一把凭证**。回退使「一把虚拟 Key × 一份绑定 × **一组按序尝试的凭证**」，但账本上**恒有且仅有一把凭证**与该行的用量关联。
>
> **INV-3**：回退候选集**必须**限定在该 `(project, provider_product)` **已持有 ACTIVE grant** 的凭证之内。**永不借用未授权给该项目的凭证**（沿用 `V57__unattributed_policy.sql:8` 的既有不变量）。候选集外无凭证可用时，按 Q6 失败，**不得放宽授权**。

**理由**：INV-1/INV-2 是 #717 的原始要求（「切换不得改变 virtual_key_id / project_id / gateway_request_id」）。**INV-3 是本 ADR 补的、且是本议题真正的安全边界**——因为 grant 携带单数凭证（`V1:359`），"换一把凭证"字面上等于"换一个授权"。若不写死 INV-3，回退机制会变成一个**权限提升通道**：任何能让某产品的一把凭证被限流的人，就能把流量引到该产品下**任意**另一把凭证上——包括别的项目/别的团队专属的凭证。注意 `V1:373-374` 的唯一约束是三元组，**同 project+product 持有多把不同凭证的 grant 是 schema 已允许的形态**，所以 INV-3 不需要任何 schema 变更即可满足。

**备选**：若 owner 希望跨越「项目未授权的凭证」做回退（例如管理员配一把全局兜底凭证），则必须同时定义**显式的策略对象与管理员审计**，且本 ADR 的 INV-3 需改写——这会显著扩大安全评审面，**不建议**。

### Q4 与配额执行（ADR-0020）的关系

**现状**：ADR-0020 的判定集按 **scope**（USER / PROJECT）而非按 Key 或凭证（`0020` §D3）；网关在**准入处**（`ProxyController` 入口、Key 解析后、读 body 前）查内存集合，命中即 429（`api-contract.md:1014`，§5.19「超限动作（#684，ADR-0020）」）。

**建议结论**：**回退发生在配额门之后，与配额判定无交集**——配额判定按 `(user, project)` 作用域，与**用哪把凭证**无关；回退既不改变作用域，也**不能**绕过判定（判定在任何上游尝试之前完成）。因此：**切换后用量仍记在原本的 user/project 作用域下，配额语义零变化。**

**理由**：这是纯粹的顺序论证——配额门在 `Key 解析后、读 body 前`，回退在**上游调用时**，二者不同阶段，回退没有机会影响配额。ADR-0020 的判定集也不含凭证维度（其 `quota_enforcement` 表按 `scope_type/scope_id`），所以「换了凭证」在配额侧**不可观测**。这条同时也是对 #717 第 4 问的答案：**切换不能、也不会成为绕过配额的手段。**

**一处需要 owner 知情的细节**：`quota_snapshots`（`V9__quota_snapshots.sql:16`）确实带 `credential_id`，但那是**上游 Plan/余额快照**（来源 `OFFICIAL_API / LOCAL_ESTIMATE / UNAVAILABLE`），与 ADR-0020 的**我方配额规则**是两套东西。回退会改变「哪把凭证的上游余额被消耗」，因此**凭证维度的上游余额视图会在切换后归因到实际使用的那把**——这是期望行为，但报表口径需在实现时明确（记为 §5 未决项）。

### Q5 开关与默认

**建议结论**：**默认关闭，且是双重 opt-in**（沿用 ADR-0009/ADR-0020 的风格）：

1. **产品级配置**：回退候选集与顺序在 `provider_product` 维度显式配置（默认空 = 不启用）；
2. **生效前提**：候选集非空 **且** 该 `(project, product)` 对候选集内的凭证持有 ACTIVE grant。

两者缺一，行为与今天**逐字节一致**——这是 #704 验收标准「不启用该能力时行为与现状完全一致」的直接落地。

**理由**：ADR-0020 已经用「默认 ALERT、规则级 opt-in」证明了这条路径在本项目可行且低风险（`0020` §D1）。默认关闭使得：`CLAUDE.md:36` 的修订在**未被主动开启时没有任何实际效果**，反转范围最小；也为 owner「先接受 ADR、暂不实现」留出空间。

**不推荐**租户级全局开关 + 产品继承：多一层继承语义，50 账号规模下收益不足（同 ADR-0019 对选项 C 的否决理由）。

### Q6 失败打开还是失败关闭

**建议结论**：**快速失败（fail-closed）**。全部候选凭证都不可用时：**返回最后一次尝试的上游错误语义**（状态码与错误体语义原样，符合 `provider-adapter-contract.md:126`「上游业务错误原样返回」），**不伪装成其他模型或其他供应商**（`operations-runbook.md` §5 第 3 条）。**不**做「乐观放行」，**不**做错误聚合后的自造 status。

**理由**：「失败打开」在本场景没有可定义的语义——不存在「忽略错误继续转发」的选项，唯一真实的岔路是「返回哪个错误」。返回**最后一次**尝试的错误，语义最接近「我们试过了，最后是这个结果」，且与既有单次重试的终态行为一致（现有实现 retry 耗尽后也返回最后一次失败）。

**关于向客户端暴露多少**：建议**只暴露尝试次数，不暴露凭证标识**。具体地，可复用既有的 `X-MiQroKey-*` 响应头命名空间（`SseReplayEngine.java:21-22`）。**伪造头剥离不需要任何同步动作**：入站剥离是**命名空间前缀**规则而非清单——`HeaderFilters.java:77` 的 `!lower.startsWith("x-miqrokey-") && !lower.startsWith("x-miqro-")` 让任何新增的 `X-MiQroKey-*` 头**自动**被剥离（`ProxyController.java:95-97` 只是描述该事实的 javadoc，不是剥离清单；实际实现全部在 `HeaderFilters.filterInboundHeaders`）。实现时应**用一条测试锁定该前缀判定**，而不是去登记一个不存在的列表。凭证级的尝试轨迹只进账本/审计，**永不进响应**。

**理由（安全）**：向调用方暴露「你的供应商产品下有几把凭证、分别是谁」是**不必要的信息泄露**，且会诱导客户端针对凭证做规避性重试。审计诉求由服务端账本满足。

### Q7 切换边界（首字节前/后、幂等性、成本差异、限流/封号）

**建议结论**：回退**只允许发生在首字节之前**，沿用既有单次重试的判据；触发与不触发条件逐条如下：

| 触发条件 | 是否回退 | 依据 |
|---|---|---|
| 连接阶段失败（`WebClientRequestException`，未出首字节） | **是** | 现有 `retryableConnectionFailure`（判据 `ProxyController.java:634-635`）已定义该判据，回退只是把「复用同一凭证」换成「取下一把候选」 |
| 上游 429（限流） | **是** | #704 明确列为触发条件；请求在推理前被拒，通常不计费 |
| 上游 401/403（凭证鉴权失败） | **建议是**，但**只回退、不自动吊销凭证** | `provider-adapter-contract.md:125`「不自动吊销凭证」；凭证可能只是过期/被临时封，自动吊销会造成不可逆误伤 |
| 上游 5xx（服务端错误） | **建议是** | #704 的「连续 5xx」；「连续」的计数语义见 §5 未决项 |
| 任何超时（含首包等待 120s） | **否** | `architecture.md:155-157`：超时按 deadline 语义处理，**永不重试**——这条是既有决策，本 ADR 不动 |
| 任意首字节之后的失败（含流中断、流式空闲超时） | **否** | `architecture.md:160`「流式响应一旦开始，禁止重试」；客户端已收到部分内容，重放会产生重复内容 |
| 客户端已断开 | **否** | 已有取消语义，重试无意义 |

**实现层的一条既有事实（决定「触发信号」怎么定义，不可跳过）**：429 / 401 / 403 / 5xx **今天不走 Reactor 的错误通道**——`ProxyController.java:526-530` 的 `exchangeToMono` 对**所有**状态码统一进入正常回调，**没有任何 `onStatus` 过滤**。因此现有 `Retry.max(1)`（`:471`）的 filter（`retryableConnectionFailure`，判据 `:634-635`）**永远看不到这些状态码**，「复用既有判据」这句话只对**连接阶段失败**成立。要实现本表的「上游 429/401/403/5xx → 回退」，必须**新造一个可重试信号**（把可回退的响应码提升为错误通道，或引入独立的尝试推进逻辑），**不是复用现有 filter 就能得到**。这属于阶段 2 的实现细节，但必须在此写明，否则「沿用既有判据」会被误读为「此处零改动」。

**两种 429 必须区分**：**我方配额拒绝的 429**（ADR-0020 的 `quota_enforcement`）发生在**准入处、请求转发之前**，**根本不产生上游尝试**，因此**不进入回退路径**；只有**上游返回的 429** 才可能触发回退。两者状态码相同、语义完全不同，实现与测试都不得混淆（测试用例也必须分别覆盖）。

**幂等性**：不新增论证负担。今天已经允许「首字节前失败 → 重试一次」（`architecture.md:159`），其安全性论证就是「**首字节未到 ⇒ 上游没有产出可观测结果 ⇒ 重放不产生副作用**」。本 ADR 的边界与它**逐条相同**——只是把「同一把凭证再试一次」扩为「按序取下一把凭证试一次」。**不引入任何今天不存在的重放窗口。**

**成本差异（易被忽略）**：候选凭证可能属于**不同 subscription**（PAYG / 个人 Plan / 团队 Plan），同一请求在不同凭证上的**有效单价可能不同**。建议：账本按**实际使用的那把凭证**计价（`V64__usage_event_price_snapshot.sql` 已记录价目快照），并在实现时确认 `price_snapshot` 的选取与凭证/subscription 对齐。**不做**跨凭证的价格择优（那是 F46 的「智能路由」，红线）。

**限流/封号风险（本 ADR 最重要的一条实操警告）**：**429 可能作用在「账号」而不是「Key」上**。若候选凭证属于**同一个 subscription/同一个上游账号**，切换**不会缓解**账号级限流，反而会**加倍请求**、加速触发账号级封禁。因此建议：

> **候选集的有效性以「跨 subscription（账号）」为前提。** 同账号内的多把凭证回退**收益有限且可能有害**。若 owner 只打算在同账号内配置多把凭证，本 ADR 的收益论证相应减弱，此时**选项 A（维持现状）是更诚实的答案**。

**建议同时不做的**：回退**不伴随**自动禁用/吊销被跳过凭证、**不伴随**熔断状态改写（熔断仍按 `(productId, credentialId)` 独立键控，`ProxyController.java:445`）、**不引入**任何后台自动探测。这些都属于 F46 的自动化范畴，保持红线。

### Q8 与 `ProxyController.java:454` / `:632` 现有注释的关系

**结论**：**修订注释**——但注释是**受影响的产物，不是被修订的决策**。

- 这两处注释是当前规则的**实现说明**（分别说明「凭证在任何尝试前解析一次」与「所有尝试复用同一凭证」）。它们**描述**了 `CLAUDE.md:36`，不构成独立决策，因此不产生「是否推翻既有 ADR」的问题。
- 若本 ADR 被接受并实现，这两处注释将变成**错误陈述**，必须同步改写为「未启用回退时复用同一凭证；启用时按候选顺序取下一把」。
- 真正的红线是 `architecture.md:159`、`architecture.md:161` 与 `CLAUDE.md:36`——**这三处需要 owner 显式同意才能改**。其中 `:159`（「重试复用同一凭证」）与 `:161`（「禁止跨真实凭证故障切换」）是**并列的两句**：只改 `:161` 会让 `architecture.md` 内部自相矛盾（一句允许、一句禁止）。注释随实现走。

### Q9 与 #704 的推荐推进顺序

**建议**：**先 ADR（本文件），后实现**；实现拆成三块，质量阈值路由（#704 的 B 部分）**不并入**。

| 阶段 | 内容 | 前置 |
|---|---|---|
| 0 | 本 ADR 由 owner 裁决 | — |
| 1 | **数据模型与授权**：回退候选集与顺序的配置载体（产品级）、与 grant 的校验规则（INV-3）、管理面审计事件（沿用 `auditService.record(... "CREDENTIAL_*", "UPSTREAM_CREDENTIAL", ...)` 命名，`AdminCredentialService.java:151/284/315`）、快照扩展 | ADR Accepted |
| 2 | **数据面**：`ProxyController` 的尝试序列（扩展 `:471` 的 `Retry.max(1)` 与判据 `:634-635`；注意 Q7 的「需新造可重试信号」）、账本锚点（Q1）、不变式 INV-1/2 的测试 | 阶段 1 |
| 3 | **审计与视图**：尝试明细的查询/展示、凭证维度报表口径确认（Q4 末段） | 阶段 2 |
| B | **质量阈值路由**（按响应质量选择/重试模型） | 单独评估，#704 备注已建议「先做 A」 |

**理由**：阶段 1 独立可交付且**不改变任何运行时行为**（配置存在但无人消费），是最小风险的第一步；阶段 2 是唯一触碰热路径的部分，应在数据模型冻结后再动。B 需要「质量」的可计算定义与额外判据，与 A 的失败回退是两种不同机制，合并会让两者的验收标准互相污染。

**触发条件（什么条件下应该做）**：① owner 同意修订 `CLAUDE.md:36` / `architecture.md:161`；② 至少有一个产品在**不同 subscription** 上配有两把及以上 ACTIVE 凭证（否则候选集为空，能力无意义）；③ 已能复现「单凭证限流/失效导致整条路断」的实际损失。三条同时成立时立项；缺 ② 则本能力**不可用**，应先解决凭证供给问题。

---

## 3. 候选方案与被否的替代方案

| 方案 | 改动面 / 代价 | 风险 | 判定 |
|---|---|---|---|
| **A. 维持现状（不做）** | 零改动 | 单凭证限流/失效仍等于整条路断；缓解仍是人工（`operations-runbook.md` §5：告警 + 用户在 CC Switch 换配置项） | **有效产出**（#717 明示）。若凭证供给不足以支撑候选集（Q7），这是**更诚实**的选择 |
| **B. 同产品内、首字节前、按显式顺序的凭证回退**（本 ADR 提案） | 快照增候选列表；`ProxyController` 尝试序列扩展；候选集配置 + 审计事件；账本语义明确（Q1）；尝试明细表 | 热路径新增一次凭证选择（纯内存查表）；误伤面 = 配置错顺序；需 owner 同意改红线 | **推荐（若 owner 决定立项）** |
| **C. 同产品内凭证池轮询 / 负载均衡** | 同 B 再加选择策略 | **否决**：`ai-gateway-comparison.md:93` 已判定「Key 池轮询破坏审计映射」；且轮询把「哪把凭证」变成不可从请求推断的状态，Q1 的账本锚点将失去确定性；同时是 `ai-gateway-comparison.md:123` 与 F46 明确点名的红线 | **否决** |
| **D. 跨供应商回退** | 需跨产品语义 | **否决**：F46 DECLINED（`feature-backlog.md:109`）；`CLAUDE.md:35` 不跨供应商；`product-requirements.md:27` | **否决** |
| **E. 只做运维编排**（管理员在告警后手工把 grant 换到备用凭证） | 控制面小改 + 运维流程 | 切换期间请求仍然失败（需人工）；但**零热路径风险、零红线变更**，且完全复用既有 grant 机制 | **次选**：若 owner 不愿改红线，E 能覆盖大部分收益。**建议与 A 一并作为「不改红线」时的答案** |

**方案 C 的正面复用说明**：`ai-gateway-comparison.md:93` 否决的是**轮询/均衡**（无触发条件、无固定顺序、纯粹为了分散负载），其理由是「破坏审计映射」。方案 B 与它的区别是**可判定性**：B 的选择完全由「第几次尝试」与「一份显式有序配置」决定，**不依赖运行期负载状态**，因此给定请求与配置即可重建「用了哪把凭证、为什么」——审计映射保持可重建。**这是 B 与 C 的分界，也是本 ADR 认为 B 不违反该先例的理由**；若 owner 不接受这个区分，则 B 与 C 同被否决，答案回到 A/E。

**与 `ai-gateway-comparison.md:94` 的分界（字面上最接近本议题的反对先例）**：该行原文是「Higress 模型 Fallback/降级链 | **刻意不采纳**（不自动故障切换）」。它与 `:93` 不同——**没有「轮询/均衡」这个限定词**，字面上可读作「否决一切 fallback」，因此本 ADR 必须正面回应，而不能靠坐标错位绕过：

- **该行的对象是「模型 Fallback/降级链」**——即请求失败后换一个**模型**（或换一个**服务/供应商**）重试，属于 `ai-gateway-comparison.md:33` 同组的「备用服务按序兜底」。本 ADR 的方案 B **不换模型、不换产品、不换供应商**（INV-1 明文钉死 `provider_product_id` 不变），只换同一产品内的一把凭证。二者不是同一个动作。
- **该行的理由只有四个字「不自动故障切换」**，其完整语境是 `CLAUDE.md:36` 与 `architecture.md:161`。本 ADR 的效力条**显式修订的正是这两条的这一句**，并把修订范围限定在「同产品、首字节前、凭证级」。换句话说：B 不是绕过 `:94`，而是**先取得对 `:94` 母句的修订授权，再据此收窄实施**；若 owner 不批准该修订，B 同样被否决——`:94` 与 A/E 之外的选项**同生共死**。
- **可行的边界情况**：若 owner 认为 `:94` 的「不自动」是对**任何**自动切换的否决（即只接受 E 的人工编排），则本 ADR 的答案收敛到 §3 的 A/E，`:94` 无需任何修改。这是 owner 可选的、代价最低的裁决路径，本 ADR 不预设它错误。

**一处会削弱上述分界的未决点**：若启用回退后，**熔断 OPEN 的凭证会被跳过候选序**（或反之），则「B 的选择不依赖运行期状态」这一论据会被削弱——因为熔断状态是运行期的。本 ADR 未对此作出规定，已列入 §5 未决项第 15 条，**在 owner 决定前不应把该论据当作已成立**。

---

## 4. 后果（若被 Accepted 并实现）

**数据面**：路由快照中 `BindingRecord` 之外新增「候选凭证有序列表」（或按 `(project, product)` 索引的映射）；`ProxyController` 的尝试序列由「同一凭证最多两次」变为「按候选顺序、首字节前推进」；熔断键 `(productId, credentialId)` 不变；配额门不变（Q4）。

**控制面**：产品级回退候选集的配置接口与校验（含 INV-3 的 grant 校验）；审计事件（沿用 `CREDENTIAL_*` / `UPSTREAM_CREDENTIAL` 命名，`AdminCredentialService.java:151`）；配置变更时的快照刷新沿用既有 `RouteRefreshPublisher` 通道（ADR-0020 §D4 同一形态）。

**数据库**：候选集需要新的存储（落盘时 develop 最高为 `V66__usage_event_base_cost.sql`；其后 #783 已占用 `V67__export_adjustment_level.sql`，故实现批次按**开工时 develop 树的最高号 +1** 取定，不在本 ADR 中预占号）；若采纳 Q1 的尝试明细表，同批次新增。**不修改任何已进入共享环境的 migration**（`CLAUDE.md` §7）。

**API/前端**：凭证详情/产品配置页增回退组与顺序编辑；用量明细页若展示尝试轨迹，需与阶段 3 一起交付；自助侧**默认不可见**（默认关闭，无人受影响）。

**文档（若 Accepted）——按「必须改写」与「仅需补注」分开，避免误改授权基础**：

- **必须改写（红线/冲突句，需 owner 逐字同意）**：`CLAUDE.md:36`、`architecture.md:159`、`architecture.md:161`（**三处并列，缺一处即内部自相矛盾**）、`provider-adapter-contract.md:126`（「不跨凭证/产品自动重试」）、`ai-gateway-comparison.md:33`（对标表结论行）。
- **仅需补注（不改结论）**：`ai-gateway-comparison.md:93/94/123`（补「已由 ADR-0021 收窄到同产品凭证级」）、`tencent-ai-gateway-mapping.md:22`（第 13 行状态由「冲突」改为记录本 ADR 结论）、`feature-backlog.md:109`（F46 维持 DECLINED，补注「同产品凭证回退已由 ADR-0021 单独裁决」）、`operations-runbook.md` §5、`api-contract.md`、`configuration-reference.md`。
- **明确不改**：`provider-adapter-contract.md:127`（首字节前安全重试——本 ADR 的**授权基础**）、`:128`、`:125`、`product-requirements.md:27`、`product-requirements.md:33`、`CLAUDE.md:35`。**把 `:127` 列入「必须改写」是错误**：它约束的正是本 ADR 想扩展的那条安全重试，改写它反而会削弱授权基础。

**风险与缓解**：

- *误伤面扩大*：一次失败尝试可能触发多把凭证的错误——缓解：默认关闭、候选集上限（见 §6）、单次请求总尝试次数有界（不得退化为无界重试）。
- *账号级限流加倍*：见 Q7；缓解：候选集有效性以跨 subscription 为前提，并在配置页给出警告。
- *审计映射被稀释*：缓解：INV-2（账本恒一把凭证）+ 尝试明细表独立保存。
- *权限提升*：缓解：INV-3，候选集必须在 `(project, product)` 的 ACTIVE grant 之内。
- *红线的滑坡*：本 ADR 一旦开了「同产品回退」的口子，后续「跨产品/跨供应商」的请求会援引为先例——缓解：效力条明确限定范围，F46 维持 DECLINED，且跨供应商需**另行 ADR**。

---

## 5. 未决项（需 owner 补充的事实与裁决）

**需要 owner 拍板（决策权在 owner，本 ADR 不代为决定）**：

1. **是否接受修订 `CLAUDE.md:36` 与 `architecture.md:161`**——这是唯一的前置红线问题。若否，答案收敛到 §3 的 A/E。
2. **ACCEPT / REJECT / 修改后再议**——REJECT 同样是有效产出（#717 明示），若 REJECT，建议同时记录「本次由谁、基于什么证据否决」，以便未来复评。
3. 若 ACCEPT：**Q1 的账本锚点**（最终成功者 vs 首次 vs 各记一行）与**尝试明细表的取舍**。
4. 若 ACCEPT：**候选集是否限定为「跨 subscription」**（Q7）。若限定，需 owner 确认实际存在这样的凭证供给。
5. **触发条件集合的最终形态**：401/403 是否纳入（语义上是「凭证失效」还是「账号被禁」，处置不同）；「连续 5xx」的「连续」如何定义（次数/时间窗口/是否含熔断状态）。
6. **单请求尝试次数上限**（建议 2–3 把，且必须有界）。

**需要 owner 补充的事实（本 ADR 无法从代码或文档得到）**：

7. **实际凭证供给**：当前生产是否存在「同一产品、不同 subscription、两把以上 ACTIVE 凭证」的真实配置？若不存在，本能力**没有可用的候选集**，应优先解决凭证供给而非实现回退。
8. **成本口径**：不同 subscription 单价不同时，用量报表是否需要**同时展示**「按实际凭证计价」与「按单一价目估算」两个口径？这属于产品口径而非工程口径。
9. **上游 429 的实际作用域**：供应商的限流是按 Key、按账号还是按产品？（需要真实供应商契约或实测证据，手册未覆盖。）
10. **是否接受**「回退的失败尝试不进 `usage_event`」这一自记账本口径（Q2），及是否需要配套的对账提示。

**本 ADR 明确未回答（留待各自议题）**：

11. **质量阈值路由**（#704 的 B 部分，含 AWS Bedrock Intelligent Prompt Routing 一类做法）——需要独立的「质量」可计算定义、判据来源与延迟预算，本 ADR 不涉及。
12. **F50 多服务绑定 / Header 分流路由**——动的是 Virtual Key → 服务的绑定，与本 ADR 的 grant → 凭证选择不是同一层。
13. **跨供应商故障切换（F46）**——维持 DECLINED；若未来重启，需**独立 ADR**，不得援引本 ADR 为先例（本 ADR 的效力条已为此设界）。
14. Azure API Management「后端池 + 优先级」的**预留/按量混合**计费形态——#717 列为参考；本 ADR 只采纳其「有序优先级」形态，未评估其计费建模。
15. **熔断（`McpCircuitBreaker`，键 `(productId, credentialId)`，`ProxyController.java:445`）与候选序的交互**——三个子问题本 ADR 均未规定：① 处于 `OPEN` 的候选凭证是**跳过**还是**仍然尝试**？② 一次回退失败是否**计入该凭证的熔断计数**？③ 回退本身是否算「一次调用」，从而更快把整个产品推入 `OPEN`？**若选择「跳过 OPEN 凭证」，则 §3 中「方案 B 的选择不依赖运行期状态」这一论据会被削弱**（熔断状态是运行期的），该论据在 owner 决定前不应被当作已成立。需要 owner 在实现前给出裁决。
