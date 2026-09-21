# 平台级事件模型与 dispatcher 语义（#245 设计稿）

> **本稿只定语义，不含实现。**本轮不写 Java、不写 SQL、不建表、不写迁移、不改任何生产代码；文末 §7 的 schema 内容在本轮一律标注「未定稿，等 owner 拍板」（2026-09-20 已拍板，见下一行）。
> **状态：已拍板（2026-09-20，Q1–Q4 全部按推荐 A）**；本稿口径自此生效。后续新信号按 §7.1 路线甲实现（当前无待实现项——既有队列饱和链已全链落地）。
>
> 事实依据：2026-09-20 对 develop（`8d345ffd`）的逐文件阅读，所有「现状」都给出 `文件:行号`；凡无法核实的写「未核实」，凡属本稿主张的写「设计立场」或「建议」，两者不混。附录 A 是坐标索引。2026-09-21：随 #1168/#1173 同步 §2.3/§5.2 评估失败语义（per-rule 兜底 + retryDue 保活），`AlertEvaluator` 全部行坐标回流至 develop `7b1672c9`。

---

## 0. 一句话结论

MiQroGate 现有告警链只认识一种事件：「某个租户的事实行，被该租户的规则评估命中，投递到该租户的 webhook」。而队列饱和这类**系统级、无租户语义**的事实放不进这个模型，目前的落地方式是「事实行挂到默认 seed 租户下，让 seed 租户的规则来评估」——它已经跑通，但只是三个特例拼出来的形状，没有成文语义。本稿把这条形状固化成两类事件的模型（§1），定义它们的生命周期（§2）、扇出与去重（§3）、规则所有权与匹配语义（§4）、失败语义与「不递归」原则（§5），并把必须由 owner 拍板的四个口径集中在 §8。**先语义、后 schema**：本轮的边界到此为止；事实表形态已由 §8 的四条拍板（2026-09-20，全部按推荐 A）确定。

一个必须先把话说破的澄清：任务书把「队列饱和 / 解析失败 / 供应商错误」并称平台级事件，但代码事实是——**三者里只有队列饱和是无租户语义的平台事件**；解析失败与供应商错误的现有事实都是行级、自带 `tenant_id` 的租户事件，且早就通了告警（详见 §1.3）。这不是抠字眼，它决定了两类事件的分界线画在哪里。

---

## 1. 两类事件的分界（问题 a）

### 1.1 判据

一个事实如果同时满足以下三条，本稿称它为 **Platform Event（平台事件）**：

1. **主体是进程/部署级资源**——队列容量、磁盘、计划任务、网关实例本身，而不是某个租户的某次请求。队列饱和的样本：整个网关进程只有一条用量队列（`PostgresUsageEventBus.java:102`），它被哪个租户的请求填满，事前不可知。
2. **产生点在租户上下文之外**——事实是热路径上一个纯内存计数，拿不到、也不应该去拿租户身份。样本：`PostgresUsageEventBus.java:133-144` 的 `offer()` 只自增内存计数（丢失计数 `AtomicLong totalDropped` :93，及丢弃时刻的高水位采样 :142/:151-153），不调度后台工作、不碰 JDBC；同文件 :55-62 的注释把这条设计写死为「hot path 只加计数器，从不调度工作、从不碰 JDBC」。
3. **归因给单个租户会破坏不变量**——要么该租户收到它无法控制、也无法处置的事故，要么评估方必须去读别的租户的行；后者正是 `AlertEvaluator.java:186-190` 明确拒绝的事：「a tenant-owned rule alerting on the platform aggregate would fire on data its operator cannot see, and cannot silence」。

反过来，**Tenant Alert Event（租户告警事件）** 是：事实行携带 `tenant_id`、由该租户自己的请求产生，规则属于同一租户，评估只读自己的行。这是现有告警链的原生形态（`AlertEvaluator.java:193-241` 全部按规则自身 `tenantId` 过滤）。

### 1.2 对照表

| 维度 | Platform Event | Tenant Alert Event |
|---|---|---|
| 主体 | 进程/部署级资源（队列、任务、磁盘，未来还有更多） | 单个租户的请求/密钥/预算/配额 |
| 产生点的租户上下文 | 无（热路径只做内存计数，`PostgresUsageEventBus.java:133-144`） | 有（`ProxyController.java:681` 用 `ctx.tenantId()` 构造事件） |
| 现有样本 | 用量队列饱和（唯一） | `usage_missing` 行（:590-591）、`upstream_status_code` 行（:590）、预算/配额水位、审批/密钥到期通知 |
| 事实承载 | `gateway_queue_signal` 挂默认 seed 租户（`PostgresUsageEventBus.java:79`、`V60:19-30`） | `usage_event` 等事实表行自带 `tenant_id`（V1 约定） |
| 规则归属 | 形式上仍是 tenant-owned 规则，但必须建在 seed 租户下（`AlertRuleService.java:146-149` 无平台类别） | 规则属于事实所属租户（`V12:26`，CRUD 全部按租户过滤 `AlertRuleService.java:72-78`） |
| 评估过滤 | 同一个 `WHERE tenant_id = :tenantId` 谓词；因为事实固定挂 seed，所以只有 seed 租户的规则能命中（`AlertEvaluator.java:218-221`） | 同一个谓词，天然按事实归属命中（`AlertEvaluator.java:196-206`） |
| 投递 | 完全相同：`AlertEventDispatcher`（信封 :167-173、退避 :213-215、attempt 表 :299-315） | 相同 |
| 去重键 | 相同：`(tenant_id, rule_id, dedupe_key)`（`V12:52`），平台信号落在 seed 租户名下 | 相同 |
| 多租户可见性 | 仅 seed 租户可见（设计立场见 §3） | 每租户各自可见 |

这张表的关键信息是：**两类事件在「存储→派发→投递→去重」四段上共用同一条链路**，分界只发生在最上游两段——事实的归属和产生点的租户上下文。所以本稿的模型不需要新机制，只需要把「平台事实如何归属、如何成为规则输入」这两件事写成规矩。

### 1.3 对三类信号的逐一归类（必要的澄清）

- **队列饱和 → Platform Event。** 唯一无租户语义的样本：队列容量是整个进程的（`QueueConfig.QueueProperties @DefaultValue("50000")`、`backend/gateway-app/src/main/resources/application.yml:62`），丢弃发生在没有租户上下文的 `offer()`（`PostgresUsageEventBus.java:141-143`）。V60 迁移的注释里对这三类做过同样的核对：「Queue saturation is the only one with NO queryable fact」（`V60:4-9`）。
- **解析失败 → Tenant Alert Event。** 判定点 `ProxyController.java:590-591`：`usageMissing = successful && tokens.isEmpty()`——上游 2xx 但解析不出 usage 块。这条事实落在该请求自己的 `usage_event` 行上（`usage_missing` 列，`PostgresUsageEventWriter.java:132,139`），行带 `tenant_id`。解析器本身是纯函数、失败只返回空 Optional、绝不影响被代理请求（`TokenUsageParser.java:27-31,113-116`）。告警早就有：`USAGE_MISSING_RATE`（`AlertEvaluator.java:196-200`）。
- **供应商错误 → 拆成两层，都不是平台事件。** 第一层是行级事实：上游对某租户请求返回的非 2xx/429 写进该租户 `usage_event` 行的 `upstream_status_code`（`ProxyController.java:590` → `PostgresUsageEventWriter.java:132`），由 `UPSTREAM_ERROR_RATE`（`AlertEvaluator.java:201-206`）与 `UPSTREAM_RATE_LIMITED`（:225-229，ADR-0026 选项 D/#706）消费。第二层是网关进程内的错误体分类器 `UpstreamErrorClassifier.java:43-103`（ADR-0024 选项 B/#770）：它只做 Counter + 日志（:65-74），指标**无租户标签**（:67-69），今天既没有投递通道也不是告警输入。本稿的立场：**它保持观测工具身份，不建议升格为告警**；如果未来要告警「上游供应商整体在批量拒绝」（对全部租户），那是另一条新的平台事件候选，不在本轮范围。
- **顺带记录的候选清单（都不是本轮内容）**：F07 剩余的无数据源类目标（Plan 同步、磁盘等，`docs/feature-backlog.md:27`）以及各类系统计划任务失败（如 `PriceSyncScheduler.java:52`、`UsagePriceReconcileScheduler.java:56` 等带 seed 租户的系统任务）——它们形状上都符合平台事件判据，但数据源尚未存在；本模型的意义之一就是让它们以后接入时不再需要发明第二套形状。

---

## 2. 生命周期（问题 b）

统一以已落地的队列饱和样本叙述，五段各自的「谁负责 / 失败会怎样 / 重试 / 幂等键」都写全；每段末尾附上对模型其他成员的普适规矩。

### 2.1 产生（Produce）

- **谁负责**：网关热路径自己。`PostgresUsageEventBus.offer()`（`:133-144`）在队列满且模式为 `DROP`（默认，`QueueConfig` 的 `SaturationMode @DefaultValue("DROP")`）时丢弃事件、自增进程内 `totalDropped` 并采样丢弃时刻的高水位（`:141-142`），再打一条高优先级 WARN（`:143`）。同文件 :42-48 的注释承诺：`publish()` 无锁（lock-free offer），JDBC 永不在发布线程上执行。
- **失败会怎样**：这一段不可失败（纯内存自增），但它有明确的边界——**计数只在进程内存里，网关进程崩溃会丢掉尚未上报的丢弃量**。这个边界是设计接受的：热路径的可用性优先于丢弃量的绝对精确（`CLAUDE.md:54` 要求「队列必须有容量、指标和告警，不能无界增长」——容量与告警都在，精确到字节不是）。
- **重试**：无（热路径不重试，也不允许重试）。
- **幂等键**：不适用（本段只产生计数器增量）。

### 2.2 存储（Persist）

- **谁负责**：同一进程里的调度上报任务 `scheduledSignalReport()`（`@Scheduled` 1 秒周期，`PostgresUsageEventBus.java:236-254`）claim 自上次上报以来的 delta（原子 CAS，:241），交给专用 writer 执行器（:244-245），由 `PostgresQueueSignalWriter.java:38-54` 单行、单事务地 INSERT 进 `gateway_queue_signal`（表定义 `V60:52-61`，`CHECK (dropped > 0)` :56）。零丢弃的周期直接 return，不写任何行（:241-243）——健康的网关在表里不留痕。
- **失败会怎样**：写失败或调度被拒时，claim 过的 delta 被原子地「还回去」（`:264-268`、`:246-253`），下个周期连同新的丢弃量一起重报；日志 WARN 留证。因为「还回去」发生在同一进程内且是唯一的 claim 方，不存在两个上报任务同时认领同一段 delta 的窗口。
- **重试**：下个周期自动重试（1 秒粒度），无次数上限（丢弃量一直挂在内存计数上直到写出）。
- **幂等键**：**「整行要么落地、要么不落地」+ 每次写入新随机 UUID**（`PostgresQueueSignalWriter.java:14-18` 的注释把理由写死了：回滚的事务不留残余，重试不会与谁撞键）。语义结果是：每个丢弃增量恰好被上报一次（exactly-once counting），前提是进程活着撑到写出。
- **普适规矩（建议）**：后续任何平台事实的产生段都遵守同样的分工——热路径只加计数器；上报由独立周期任务做；写失败必须可归还、可重试；一行的落地与否即幂等单元。

### 2.3 派发（Dispatch / 评估）

术语对齐：在这条链路里「派发」不是把事件推进队列，而是**控制面周期性地把事实评估成规则命中**，命中才生成事件实体并交给投递器。

- **谁负责**：控制面 `AlertEvaluator.evaluateAll()`（`@Scheduled` 默认 5 分钟，`AlertEvaluator.java:78`；默认值来自注解，control-plane 的 application.yml 里没有覆盖项——本轮已核实）。流程：拉取全部 enabled 规则并逐条评估（:81-93，含 per-rule 兜底 :87-92）→ 逐规则算指标 `metric(type, tenantId, scopeJson)`（:111，平台信号分支 :218-221）→ 阈值比较 `value >= threshold`（:112）→ 计算去重键并 `INSERT INTO alert_events … ON CONFLICT DO NOTHING`（:132-142）→ 插入成功才 `dispatcher.deliverEvent(…)`（:143-146）。平台信号与租户信号在这里走的是同一个循环、同一套谓词。
- **失败会怎样**（2026-09-21 随 #1168/#1173 更新——此前「单规则异常中止本周期其余规则并连带跳过 `retryDue()`」的中断面已消除）：单条规则的求值异常在循环内由 **per-rule try/catch** 就地兜底（:87-92），只落一条带 `ruleId` 的 WARN（`Alert rule evaluation failed (ruleId=…, type=…)`），**其余规则照常评估**；周期级 catch（:94-98）现在只剩「规则查询本身失败」一个落点（该情形什么都没评估、仍落 WARN）；`retryDue()` 在其后的**独立 try**（:103-107）中执行——**评估阶段的任何异常都不再阻止到期投递重试**，sweep 自身异常也单独 WARN、不炸周期。任何异常都只落 WARN 日志，下周期（5 分钟后）从头再来。
- **重试**：靠下一个评估周期，无补偿、无追赶（由此产生的语义缺口见 §5）。
- **幂等键**：`(tenant_id, rule_id, dedupe_key)` 唯一约束（`V12:52`）+ 去重键构造 `type:小时桶`（`AlertEvaluator.java:123`，truncated to hours）→ 一条规则每个小时窗口最多产生一个事件；平台信号沿用同一构造，落在 seed 租户名下。

### 2.4 投递（Deliver）

- **谁负责**：`AlertEventDispatcher`。首次投递是同步的一次 HTTP 尝试（`deliver` :163-180 → `attempt` :182-207），地址来自规则的 `webhook_endpoint_id`（:155-161，端点是租户自有的 `webhook_endpoints` 行，`V12:9-22`）；重试不靠调度器，而是每次评估周期末尾由 `retryDue()`（:231-285，evaluator 在 :104 调用）扫描「已失败且退避到点」的投递。
- **失败会怎样**（现状语义，逐条核对过）：
  - 非 2xx 一律算投递失败（:192-197，注释明确「响应码」是运维可见的失败面）；
  - 5xx（及网络异常/超时）arm 重试；4xx 只记录、不重试（重试不可能成功）；
  - 退避 `2^attempt × 60s`、封顶 3 次尝试（:34 `MAX_ATTEMPTS`、:213-215）；
  - **耗尽后 `next_retry_at = NULL`，静默终止**：没有 dead-letter 状态、没有门户提示、没有重放接口（生产主代码 `src/main` 零命中：`UPDATE alert_events` / `DELETE FROM alert_events` / `DELETE FROM webhook_delivery_attempts`——测试夹具用拼接 SQL 清表，如 `UsageQueueSaturationAlertIntegrationTest.java:402`，不计入清理语义；投递状态不写回 `alert_events.status`，那张表的 status 只有 FIRED/DEDUPED 两种值，`V12:48-49`）；
  - 规则或端点被禁用会同时停掉首投和已 arm 的重试（:240-241、:260 双重门），重新启用后按既有退避继续（:223-228 注释）。
  - 注意一处**文档与代码的漂移**：`docs/operations-runbook.md:137` 与 `docs/release-checklist.md:94` 提到「超过窗口转 dead-letter 并在门户告警」「dead-letter 和人工重放测试通过」，但代码里找不到 dead-letter 状态转换、门户告警或重放接口。本稿如实记录代码事实；措辞修正已随 2026-09-20 拍板后的文档轮落地（见 `operations-runbook.md` §8 与 `release-checklist.md`）。
- **重试**：见上，最多 3 次尝试、指数退避、开关可随时打断。
- **幂等键**：投递尝试表 `(event_id, endpoint_id, attempt)` 唯一（`V12:65`）；webhook 信封带 `eventId`（:168）与签名（`WebhookEndpointService.java:37,279`，HMAC-SHA256，`X-MiQroKey-Signature: sha256=…`）。**投递语义是 at-least-once**：接收方若在回 5xx 前实际已收到（例如回执丢失），会再收到同 `eventId` 的重试——**建议把「按 eventId 幂等」写成 webhook 接入方的契约**（这是建议，现状没有强制）。

### 2.5 保留与清理（Retain / Purge）

- **谁负责**：**现状没有任何一方负责**——这是本轮核查过的事实：`gateway_queue_signal`、`alert_events`、`webhook_delivery_attempts` 三张表都没有清理代码（生产代码 `src/main` 的 grep 无对应 DELETE/周期清理；测试夹具的拼接 SQL 清表不计）。唯一的删除都是级联：删规则级联删事件（`V12:44`，经 `AlertRuleService.java:132-140`）、删端点级联删尝试（`V12:59`）。
- **失败会怎样**：不适用（无动作可失败）；风险反过来——表只增不减。
- **重试**：不适用。
- **幂等键**：不适用。
- **设计立场**：保留策略（保留多久、谁清、清理是否要审计）列为 §8 的拍板点之一（Q4）。在拍板前，本模型不默认任何清理行为。

### 2.6 生命周期总表

| 段 | 谁负责 | 失败会怎样 | 重试 | 幂等键 |
|---|---|---|---|---|
| 产生 | 网关热路径（内存计数） | 不可失败；进程崩溃丢未上报增量（边界） | 无 | 不适用 |
| 存储 | 网关 1s 上报任务 + 专用 writer | 写失败/调度被拒 → delta 归还，下周期重报 | 下周期自动，无上限 | 随机行 id + 单行单事务（整行落地即一次计数） |
| 派发 | 控制面 AlertEvaluator（5min 周期） | per-rule 兜底：单规则异常只落 WARN（带 ruleId）、其余规则照评（#1168/#1173）；周期级 catch 只包规则查询；retryDue 独立 catch 不再被跳过 | 下周期重来，无追赶 | `(tenant_id, rule_id, dedupe_key)` + `type:小时桶` |
| 投递 | AlertEventDispatcher | 非 2xx/异常 → arm 重试；4xx 终态；3 次耗尽后静默 | 指数退避最多 3 次 | `(event_id, endpoint_id, attempt)`；接收方建议按 eventId 幂等 |
| 保留 | （无人负责，现状） | — | — | — |

---

## 3. 扇出与去重（问题 c）

先把问题翻译准确：在本模型里没有「一个事件实体投递给 N 个租户」这种东西——平台事实是**共享的一批事实行**，各租户的规则各自评估它。所以「能否向多个租户派发」的实际含义是：**非 seed 租户的规则，能不能评估到平台事实行**。

- **现状答案：不能。** 两处共同保证：事实行固定挂在 seed 租户（`PostgresUsageEventBus.java:79`、`V60:19-30`），评估 SQL 又固定按 `WHERE tenant_id = :tenantId` 过滤（`AlertEvaluator.java:218-221`）。非 seed 租户的同名规则聚合到空窗口，`COALESCE(SUM(dropped), 0)` 恒为 0，正阈值下恒不触发（有测试固定：`UsageQueueSaturationAlertIntegrationTest.java:202-249`，含 `threshold = 0` 的退化行为）。
- **设计立场（推荐维持单承载点）**：本期不扇出。理由：产品形态是单客户私有化部署（`CLAUDE.md:31`），seed 租户就是部署方自己，V60 的注释也说这「在单租户部署下严格等价于一个全局信号」（`V60:29-30`）；一旦放开让所有租户的规则都能评估共享事实，就等于要推翻 `AlertEvaluator.java:186-190` 明文拒绝过的不变量（规则不得读他租户的行），需要新造「平台规则豁免」这一类别，复杂度和风险都不划算。
- **未来若要扇出，怎么去重**：**不需要 `(event_id, tenant_id)` 这类唯一键**。因为 `alert_events` 记录的是「某条规则在某窗口命中了」，天然每租户、每规则、每窗口一条，现有唯一约束 `(tenant_id, rule_id, dedupe_key)`（`V12:52`）就是扇出形态下的正确去重键——每个租户各生成自己的事件。只有当模型改成「平台事件先实体化为一条记录、再为每个租户维护投递状态」时，才需要 `(event_id, tenant_id)`；本稿不建议实体化（事实行 + 规则评估已经能表达一切，且复用全部现有机制）。
- **候选（拍板用，见 §8 Q1）**：A 单承载点维持（推荐）/ B 全租户可见 / C 订阅表。三者代价列在 Q1。

---

## 4. 告警规则的所有权与匹配语义（问题 d）

**`alert_rules` 是否仍 tenant-owned？——是，且本轮不变。**`tenant_id` NOT NULL（`V12:26`），全部 CRUD、读取与 CAS 更新都按租户过滤（`AlertRuleService.java:72-78、:106-111、:132-135`）。当前**没有**「平台规则」的一等公民表示：平台信号的规则就是一条恰好建在 seed 租户下的普通规则。

**平台事件如何成为某条 tenant rule 的输入（现状唯一路径）**：

1. 网关把平台事实写成挂 seed 租户的名字的行（§2.2）；
2. seed 租户的管理员建一条对应类型的规则（如 `USAGE_QUEUE_SATURATION`，阈值 = 近 1 小时可接受的丢弃条数——运营口径见 `docs/operations-runbook.md:121`，示例：填 1 表示丢 1 条即告警，不要按比例理解）；
3. `AlertEvaluator` 用与租户信号完全相同的循环评估它（§2.3），命中即按下述信封投递。

**匹配语义：按类型，不按 scope；评估在控制面。**
- 每个平台信号对应一个规则 `type`（`USAGE_QUEUE_SATURATION` 是第一个样本，`AlertRuleService.java:146-149`），`scope_json` 不参与匹配（目前只为 `BUDGET_THRESHOLD`/`QUOTA_THRESHOLD` 而存在：`AlertRuleService.java:165-173`、`AlertEvaluator.java:237-238`）。
- 评估归控制面，现在唯一可行：网关没有投递通道（`QueueSignal.java:9-11` 原文「The gateway cannot alert on this by itself — it has no delivery channel」）；网关也不写 `alert_events`（本轮 grep 核实：`backend/gateway-app/src/main`、`backend/queue-spi/src/main` 零命中），且 V60 注释已论证直写事件行不可能被投递（`retryDue()` 只扫已有失败尝试的行，`V60:14-17` 对应 `AlertEventDispatcher.java:231-247`）。

**能复用什么（不需要新机制）**：规则 CRUD/审计/乐观锁（`AlertRuleService.java:46-140`）、评估调度与去重（§2.3）、`alert_events`、dispatcher 的签名/退避/attempt/retryDue（§2.4）、seed 承载模式（本仓已有 8 处同类先例，坐标见附录 A）。

**必须新加什么（每增加一个平台信号类）**：
1. 网关侧事实产生与上报（§2.1–2.2 形状）；
2. 新事实表迁移 + `(tenant_id, occurred_at DESC)` 索引（V60:63-66 形状）；
3. `AlertEvaluator` 的评估分支（返回计数或占比要显式区分，:213-221 的注释与分支是范本）；
4. **类型注册面——至少 8 处**（本轮逐一定位）：`AlertRuleService.java:146-149`（服务层校验，连同 open-admin 面一起覆盖）、`AlertEvaluator.java:195-240`、迁移 CHECK（模式样本 V60:39-46；现行最新一次为 V71:33-41）、`frontend/src/types/api.ts:75`、`NextAdminAlertRulesView.vue:45` 与 `:77`、`frontend/src/i18n/dict.ts:973`、`docs/api-contract.md:750` 与 `docs/database-schema.md:330,338`。这是目前接一个新信号的真实成本，也是 §8 Q2 的决策背景。
5. 测试：至少覆盖「命中并签名投递」「阈值是计数不是比例」「非 seed 租户不触发」「跨窗去重」四类（样本：`UsageQueueSaturationAlertIntegrationTest.java:138-290`）。

**与 `AlertEvaluator` 的对齐要求**：新分支必须保持 (i) 按 `:tenantId` 过滤（不变量，:186-190）；(ii) 阈值语义在注释和文档里说清「计数还是占比」（:213-221 是正面样本）；(iii) 去重沿用默认小时桶即可，不需要自定义。

---

## 5. 失败与可观测（问题 e）

### 5.1 投递失败（webhook 5xx / 超时）

语义已由 dispatcher 定义（§2.4）：可重试、有界、退避、开关可打断；失败证据 = `webhook_delivery_attempts` 行（`http_status`/`error_message`/`next_retry_at`），门户可用 `GET /admin/webhooks/{id}/deliveries` 看最近 20 条（`AdminWebhookController.java:85-89`）。**耗尽后静默**是现状（§2.4），语义上是「投递失败不再升级」——升级手段只有运维看 attempt 表，这是 §8 Q4 的拍板点。

### 5.2 评估失败

周期级 catch + WARN（`AlertEvaluator.java:94-98`；`retryDue()` 另有独立 catch :103-107，不再被评估异常跳过），下周期重来。两个必须写进文档的性质：
- **单规则异常已被隔离（#1168/#1173）**：per-rule try/catch（:87-92）只让出错规则自己落带 `ruleId` 的 WARN——其余规则照常评估、`retryDue()` 照常执行（此前是「一错全停、投递重试随之推迟一个周期」；带病运行期间其他规则的告警时效与投递重试都不再受影响）；
- **无追赶**：指标是「滚动 1 小时」查询（:199 等），若控制面连续宕机超过 1 小时，窗口里的平台事实就滚出去了——**警报永远不会补发**。评估周期 5 分钟，所以短于 1 小时的罢工恢复后仍会在下个周期命中。这是本模型最需要 owner 知情的一个语义缺口，列为 §8 Q3。

### 5.3 平台告警链自身的故障，谁来告警？

**明确定义：这一层不递归。** 本层不为自己的故障生成 `alert_event`，理由是自引用告警会产生循环（评估器坏了就报不出「评估器坏了」）与噪声；用一个可能坏掉的通道去报它自己坏了，在语义上是空的。分层如下：

| 本层故障 | 事实留在哪 | 谁来看 |
|---|---|---|
| 评估周期异常（规则查询失败 / 单规则求值失败） | 控制面 WARN 日志（:90-97；per-rule 与周期级各带锚点） | 运维日志/巡检（runbook） |
| 投递失败/耗尽 | attempt 表行（`V12:55-66`）+ WARN 日志（:201-205） | deliveries 接口 / 运维 |
| 网关丢弃计数丢失（进程崩溃） | 无（内存态，边界见 §2.1） | 网关重启后的 WARN/低分证据链（归因成本高，属已知边界） |
| 网关上报写失败 | delta 归还 + WARN（:264-268） | 同上，最终要么写出要么随进程消失 |

若未来需要「告警链健康度」的可观测性，**建议走独立通道**（外部进程健康检查/运维巡检基线），而不是让本链给自己发事件；把它做成同一链路的规则会直接违反本节的不递归原则。现状盘点：本层目前**没有**自监控（除了日志），也没有对「投递耗尽」的任何提示——这两条是 §8 Q4 的背景。

---

## 6. 不做什么（问题 f）

本轮（#245 r1）明确不做：

- 不写 Java、不写 SQL、不建表、不写迁移、不改任何生产代码；唯一新增文件就是本文档。（红线，见任务书 §0。）
- 不做 schema、不做网关侧 writer、不做控制面 dispatcher/评估改动——它们留待**新信号接入**的实现轮（本稿拍板后当前无待实现项：既有队列饱和链已全链落地）。
- 不做扇出（§3）、不做 dead-letter/重放（§2.4）、不做保留清理（§2.5）、不做告警链自监控（§5.3）——前三项已随 §8 拍板（2026-09-20，全部按推荐 A）；末一项按 §5.3 的不递归原则另行设计。
- 不引入新中间件。平台事实的通道就是「网关写事实行、控制面读事实行」的共享 PostgreSQL 模式（V60 已证明可行）；本设计不依赖 Kafka 或任何新组件。
- 不修改 `docs/operations-runbook.md` 等的既有措辞（含 §2.4 记录的 dead-letter 漂移）——该等文档级修正已于 2026-09-20 落地（见 `operations-runbook.md` §8 与 `release-checklist.md`）。

**交接句（新信号接入时）**：本稿是语义基线；§8 已于 2026-09-20 拍板（全部按推荐 A）。下一个新平台信号按 §7.1 形状实现「事实表迁移 + 网关 writer + evaluator 分支 + 类型注册 8 处 + 测试」，并在实现时同步回填 `docs/api-contract.md`、`docs/database-schema.md`、`docs/operations-runbook.md` 与 `docs/feature-backlog.md` 的 F07 行。

---

## 7. 给下一轮的 schema 建议（问题 g —— 2026-09-20 已随 Q2=A 选定**路线甲**（§7.1））

> 本节只列字段名与用途，不写 DDL。**2026-09-20 已随 Q2=A 选定路线甲（§7.1）**；字段名仍为示意，落库前随实现轮评审。

### 7.1 路线甲（推荐给一期）：延续「一信号一事实表」

沿用 V60 的形状：每个平台信号一张窄表。字段（按用途分组）：

| 字段 | 用途 |
|---|---|
| `id` | 行主键；每次写入新随机 UUID，整行落地即一次计数（幂等单元） |
| `tenant_id` | 承载租户（默认 = seed 租户，`00000000-…-0001`）；保持评估侧「按租户过滤」不变量不变 |
| `occurred_at` | 事实窗口时刻；评估按 `occurred_at >= now() - 1h` 取滚动窗口 |
| （信号数值列，如 `dropped`） | 该信号的本窗数值；NOT NULL + 领域 CHECK（样本：`dropped > 0`） |
| （诊断列 2–4 个，如 `queued_high_water`/`capacity`/`saturation_mode`，或未来任务的 `job_name`/`error_class`） | 给读告警的人看的上下文，**不参与阈值**（V60:48-51 的定性照搬） |
| `created_at` | 入库时刻（默认 now()，与 occurred_at 区分重放/迟到） |

索引：`(tenant_id, occurred_at DESC)`（样本 V60:63-66，注释写明它服务「每租户滚动窗口 SUM」的扫描形状）。

### 7.2 路线乙（备选；Q2 已于 2026-09-20 选定甲）：通用 `platform_event_signal` 表

一张表承载所有平台信号：`id` / `event_type`（字符串枚举）/ `tenant_id`（同甲）/ `occurred_at` / `value`（数值）/ `severity`（可选）/ `payload_json`（诊断上下文，参照 `alert_events.payload_json` 的用法）/ `created_at`；评估侧对应一个通用 `PLATFORM_EVENT` 规则类型 + `scope_json = {"eventType": "…"}` 过滤器。**收益**：类型注册面从 8 处压缩到 1–2 处（迁移 CHECK 只加一项）。**代价**：失去每信号 CHECK 的类型安全；各信号诊断字段被迫塞进 `payload_json`，查询与排障不如窄表直白；评估 SQL 需要一层 event_type → 数值列的映射，复杂性是转移而非消失。

### 7.3 规则侧（两条路线共用）

- 路线甲：`alert_rules` 无需结构变化，每信号照旧扩展 CHECK（模式样本 V60:39-46；最新一次 V71:33-41）；
- 路线乙：CHECK 增加 `PLATFORM_EVENT`，`AlertRuleService.validateScope` 增加 `eventType` 必填校验（对齐 :165-173 现有的 scope 校验模式）。
- 以上字段名仍为**示意**；落库实现前随实现轮评审。

---

## 8. 拍板记录（2026-09-20 完成；一次一问 —— 每条：选项 / 代价 / 推荐）

> 本节四条口径已于 2026-09-20 由 owner 拍板（全部按推荐 A），记录见下；下一轮据此开工。

### Q1（来自 §3）：平台事件对非 seed 租户可见吗？

- **A. 单承载点维持（推荐）**：事实挂 seed，只有 seed 租户的规则能评估。代价：多租户部署（今天没有，`CLAUDE.md:31` 是单客户私有化）里其他租户看不到平台事故；好处：零新机制、保住「规则不得读他租户行」的不变量、行为与已上线的 V60 完全一致。
- **B. 全租户可见**：评估 SQL 去掉事实行的租户过滤，每个租户的同名规则各自触发。代价：要显式新造「平台规则豁免」类别，推翻 `AlertEvaluator.java:186-190` 的书面立场；当事租户收到它无法处置、也看不到原始数据的事故；告警量随租户数放大。
- **C. 订阅表**：新增平台事件订阅关系，只有订阅的租户能建平台规则。代价：新 schema + 管理面 + 权限语义，收益要在多租户形态下才兑现；可延后。

### Q2（来自 §4）：平台信号接入规则引擎的方式？

- **A. 一信号一类型（推荐）**：沿用 `USAGE_QUEUE_SATURATION` 的既有形状。代价：每个新信号要动至少 8 处注册面（§4 清单）——这是已知、可核对、有测试样本的成本；好处：类型安全、与运营文档/前端/契约的耦合点全部显式。
- **B. 通用 `PLATFORM_EVENT` 类型 + `scope.eventType`**（§7.2 路线乙）：注册面压到 1–2 处。代价：类型安全弱化、诊断字段进 payload、评估多一层映射；适合信号类超过大约 5 个之后再上（届时可另开一轮设计）。
- **C. 网关直写 `alert_events`**：**不推荐**。V60:14-17 已论证直插的事件行永远不会被投递（`retryDue()` 只扫有失败尝试的行）；网关也没有端点上下文与签名 secret（secret 是控制面的 AES-GCM 资产，`V12:6-7`）；还会把「评估归控制面」的现状拆成两处。

### Q3（来自 §5.2）：评估中断超过 1 小时的窗口遗漏，怎么处理？

- **A. 接受并文档化（推荐一期）**：把「控制面宕机 > 1h ⇒ 期间平台告警不补发」写进 runbook 的告警章节。代价：有一个已知的告警盲区；好处：零新机制，且 5 分钟周期的正常形态下几乎不可见。
- **B. 加长事实保留期 + 评估窗放宽**：事实行本身不过期（现状本来就不过期），把评估窗从 1h 放宽或在恢复后追踪未评估区间。代价：阈值语义变成非线性（窗内混入中断前的事故），要重新定义「1 小时」在文档里的含义。
- **C. 未消费标记**：事实行加「已评估」水位，恢复后补评估。代价：事实表写入侧与评估侧都要改（写侧加状态位、评估侧改增量扫描），是三个选项里实现成本最高的，收益只在中长期宕机场景兑现。

### Q4（来自 §2.4/§2.5/§5）：投递耗尽与数据保留的语义？

- **A. 现状 + 文档修正（推荐一期）**：语义定为「3 次尝试后静默终止」；修正 `operations-runbook.md:137` 与 `release-checklist.md:94` 的 dead-letter/重放措辞（或注明其指「耗尽后的人工查看」），把「无自动 dead-letter、无重放 API」写成明文。代价：投递失败仍然只能在 attempt 表里被人工发现。
- **B. 实现 dead-letter 状态 + 门户提示**：把耗尽转成一个显式终态并在门户可见（不做自动重发）。代价：需要 `alert_events`/attempt 表的状态机扩展 + 前端展示 + 迁移，属实现轮增量。
- **C. 在 B（或 A）之上再加保留期清理**：为 `gateway_queue_signal`/`alert_events`/`webhook_delivery_attempts` 定义保留时长与清理任务。代价：新迁移 + 调度 + 审计口径；收益是长期表体量可控。清理会触到「告警证据可追溯多久」的合规口径，建议单独一拍。

**拍板记录**（owner 于 2026-09-20 拍板，全部按推荐 A）：

- Q1：**A**（单承载点维持——事实挂 seed，只有 seed 租户的规则能评估）
- Q2：**A**（一信号一类型，沿用 USAGE_QUEUE_SATURATION 的既有形状）
- Q3：**A**（接受并文档化——控制面宕机 > 1h 期间平台告警不补发，写进 runbook）
- Q4：**A**（现状 + 文档修正——「3 次尝试后静默终止」定为明文；runbook/release-checklist 的措辞修正随后续轮执行）

---

## 附录 A. 关键坐标索引（供核对，全部为 develop `8d345ffd` 实测；评估器行已随 #1168/#1173 回流至 `7b1672c9`）

| 主题 | 坐标 |
|---|---|
| 丢弃产生点（无租户上下文） | `backend/queue-spi/src/main/java/com/miqroera/miqrokey/queue/PostgresUsageEventBus.java:133-144`（计数 :141-143；线程承诺 :42-48；drop 上报 javadoc :55-62） |
| 平台承载租户硬编码 | 同上 `:79`（`SIGNAL_TENANT_ID`）、`:261`（写信号） |
| 上报任务 / writer | 同上 `:236-254`、`:259-268`；`PostgresQueueSignalWriter.java:38-54`（幂等单元 javadoc :14-18） |
| 事实表 | `backend/persistence-postgres/src/main/resources/db/migration/V60__usage_queue_saturation_alert.sql:52-61`（CHECK :56；索引 :65-66；承载口径论证 :19-30；三类核对 :4-9） |
| 队列配置 | `backend/gateway-app/src/main/resources/application.yml:61-65`（capacity 50000 :62）；`QueueConfig.java`（`QueueProperties` 默认值） |
| 进程内指标 | `backend/gateway-app/src/main/java/com/miqroera/miqrokey/gateway/config/QueueMetricsBinder.java:19-30`；暴露面 `application.yml:113-117` 仅 health,info |
| 解析失败判定 | `backend/gateway-app/src/main/java/com/miqroera/miqrokey/gateway/proxy/ProxyController.java:590-591`；非流式解析 :573-578；SSE 观测 :562 |
| 解析器 | `backend/provider-adapters/src/main/java/com/miqroera/miqrokey/adapters/common/TokenUsageParser.java:27-31,56-124,113-116` |
| 供应商错误（行级） | `ProxyController.java:541-542,590`；`PostgresUsageEventWriter.java:132,139` |
| 供应商错误（进程级观测） | `backend/gateway-app/src/main/java/com/miqroera/miqrokey/gateway/proxy/UpstreamErrorClassifier.java:43-103`（枚举 :51-53；无标签指标 :67-69） |
| 告警表 | `V12__webhook_alerts.sql:24-53`（唯一约束 :52）、`:55-66`（:65）、`:44`/`:59`（级联） |
| 评估器 | `backend/control-plane-app/src/main/java/com/miqroera/miqrokey/controlplane/service/AlertEvaluator.java:78,81-107,110-147`（平台分支 :218-221；租户不变量 :186-190；占比/计数分支 :196-229） |
| 投递器 | `backend/control-plane-app/src/main/java/com/miqroera/miqrokey/controlplane/service/AlertEventDispatcher.java:34,54-93,163-180,182-215,231-285,299-315` |
| 规则服务 | `backend/control-plane-app/src/main/java/com/miqroera/miqrokey/controlplane/service/AlertRuleService.java:72-78,106-111,132-140,146-149,165-173` |
| 签名/测试/投递列表 | `WebhookEndpointService.java:37,225,279`；`AdminWebhookController.java:79-81,85-89` |
| 运营口径 | `docs/operations-runbook.md:121`（队列饱和）、`:137`（dead-letter 漂移）；`docs/api-contract.md:750`；`docs/database-schema.md:330,338`；`docs/feature-backlog.md:27`（F07） |
| 平台模式先例（seed 租户 × 8 处服务） | `AuthenticationService.java:104`、`McpHealthChecker.java:213`、`ModelCatalogReprobeScheduler.java:31`、`PlatformOidcAuthService.java:49`、`PriceSyncScheduler.java:52`、`QuotaSnapshotService.java:163`、`ServiceHealthChecker.java:103`、`UsagePriceReconcileScheduler.java:56`；种子租户 `V1__core_tables.sql:35` |
| 测试样本 | `UsageQueueSaturationAlertIntegrationTest.java:138-290`（跨租户 :202-249、零阈值 :229-249） |
| 任务书框架澄清依据 | 网关不写 alert_events（grep `gateway-app/src/main`、`queue-spi/src/main` 零命中）；网关不跑 Flyway `backend/gateway-app/src/main/resources/application.yml:21-24`；无 dead-letter/重放/清理（生产代码 `src/main` 的 grep 零命中；测试夹具拼接 SQL 清表不计） |

> 附录核查提示：上表行号可能随后续提交漂移；核对时以 `git show origin/develop:<path>` 为准。
