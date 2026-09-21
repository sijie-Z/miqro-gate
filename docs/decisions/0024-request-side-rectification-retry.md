# ADR-0024：请求侧可选改造②——错误驱动的整流重试（thinking 签名/预算，默认关）

- 状态：**Accepted（部分）——选项 B（观察档）已采纳为一期**（2026-09-19 所有者拍板「按推荐」；推荐原文为「先 B → 数据支持再上 C」，见 §3）；**C / D / E 仍待二期拍板**。B = 有界错误体分类 + 计数 + 日志，**零改体、零重试**，实现见 issue #770。
- 日期：2026-09-18
- 关联：issue #770；[ADR-0002](0002-transparent-proxy.md)（透明代理）；[ADR-0009](0009-enable-response-cache.md)（缓存：整流请求不得写缓存）；[ADR-0020](0020-quota-soft-landing.md)（网关自产 429，不得被误判为上游错误）；[ADR-0005](0005-no-redis-v1.md)；姊妹篇 [ADR-0023](0023-request-side-cache-breakpoint-injection.md)（同属「opt-in 改体例外族」）；issue #704/#717（路由与回退，未决）、#742（封闭客户端接入）、#740（同族改写）、#544（已关闭，见 §1.3 的边界澄清）
- 触发事件：issue #770——对照 cc-switch 源码（`src-tauri/src/proxy/thinking_rectifier.rs`、`thinking_budget_rectifier.rs`、`thinking_optimizer.rs`）比对后，提出「换供应商后旧会话的 thinking 签名必然失效，网关能否在上游报错后自动整流并重试一次」。
- **独立复核补充（2026-09-19，对 develop `8e35fddb`）**：坐标复验，**1 处漂移已修**——`PostgresUsageEventWriter.java:181,230`（两处 `INSERT INTO request_usage_records` 的行号）→ `:253,302`（§5「改动面不止一次性迁移」的论证正落在这两处）；`ProxyController` / `RequestStatus` / `LlmCircuitBreakerRegistry` / `ContextLimitGuard` / 迁移文件 / `architecture.md` / `CLAUDE.md` 等其余引用均落在所引区间内。另：为 §2 的「改体」不变量提供锁定的字节级契约测试**现已存在**（`AnthropicProxyContractTest$PromptCachePassthrough`，PR #933，断言关闭态下请求体字节级原样转发）——若采纳本提案，它同样是必须同步更新的第一处。

---

## 1. 背景与现状

### 1.1 提案内容（issue #770）

上游因 `thinking` 块签名失效（或 thinking/预算字段不自洽）报错时，网关在**首字节之前**对请求做**白名单内**的整流（删除 thinking/redacted_thinking 块，或调整预算字段），并用**同一凭证**重试一次。默认关、按 Key opt-in；只在上游报错后触发；整流动作与错误模式类别记入审计（不记正文）。issue 明确列为未决：**「重试次数沿用现有 ≤1 语义还是独立预算，需拍板」**。

外部实证为 **issue 转述**（本仓未复核 cc-switch 源码，不作为本 ADR 的论据）：`thinking_rectifier.rs` 的错误模式清单（`Invalid 'signature' in 'thinking' block`、`Thought signature is not valid`、`must start with a thinking block`、`Expected thinking/redacted_thinking, but found tool_use`、缺少必需签名），`thinking_budget_rectifier.rs` 的边界（budget ≤ 32000、max_tokens ≤ 64000、max > budget），以及 `thinking_optimizer.rs`。

### 1.2 现状证据（本仓坐标）

| 事实 | 坐标 |
|---|---|
| 红线：不自动切换、首字节前最多一次安全重试 | `CLAUDE.md:36`「不自动故障切换；首字节前最多安全重试一次，流开始后不重试。」；`CLAUDE.md:55`「透明代理不得重排、标准化或补写推理请求 JSON。」；`docs/architecture.md:34`「不改写推理语义」；`docs/protocol-agents.md:21`「字节级透传原则」 |
| 现有重试的**唯一**触发条件：连接阶段失败 | `ProxyController.java:634-642`（`retryableConnectionFailure` 要求未收到任何首字节）、`:627-632`（javadoc：仅连接阶段、无跨凭证切换）、`:471-472`（`Retry.max(1)`） |
| 重试复用同一凭证、不跨供应商 | `ProxyController.java:451-454`、`:464-470`（`Mono.defer` 用**同一** `byte[]` 重发） |
| 重试次数已持久化 | `ProxyController.java:754`；`V8__request_usage_records.sql:55`（`retry_count integer NOT NULL DEFAULT 0`） |
| 上游业务错误**原样返回**，正是枚举注释里的承诺 | `RequestStatus.java:24`「Upstream answered with a non-2xx status (body forwarded untouched)」；`ProxyController.java:779`（仅按 HTTP 状态归类 `SUCCEEDED` / `UPSTREAM_REJECTED`） |
| 响应状态/头立即提交，响应体直接流式转发 | `ProxyController.java:529-540`（先 `setStatusCode` + `addAll(outHeaders)` + 注入 `X-MiQroKey-Request-Id`），`:548-559`（`writeWith(observed)` 流式写回） |
| 网关**逐块缓冲**上游响应体，但**不按内容分类错误** | `ProxyController.java:548` 用 `bodyToFlux(DataBuffer.class)` + `doOnNext(attempt.collector::append)` 缓冲**每一个**上游响应体（**无状态码分支**，错误体同样被缓冲），随后只交给三个消费者：`:566` `SseUsageObserver.parseUsageJson`（用量解析，非 SSE 也含错误体）、`:578` `retentionSidecar.captureOutput`、`:850` `UpstreamRequestIdExtractor.fromBodyPrefix`（取上游请求 id）；`:585` 的缓存写入仅 2xx。即：错误字节已在内存里，但**没有任何错误模式分类**——`.../proxy` 下 grep `bodyToMono` / `BodyExtractors` 无命中，`gateway-app/src/main/java` 下 grep `thinking` / `cache_control` 无命中（下一行） |
| **网关当前完全没有 thinking / cache_control 相关处理** | `backend/gateway-app/src/main/java` 下 grep `thinking` / `cache_control` 无命中 |
| 契约层面禁止跨凭证/产品重试 | `docs/provider-adapter-contract.md:126-128`；`docs/architecture.md:159-161`；`docs/testing-and-acceptance.md:41-42` |
| 「参数改写」已是登记的冲突项 | `docs/feature-backlog.md:112`（F49，状态 `ADR`）；`:109`（F46 智能路由/跨供应商故障切换，已 DECLINED） |
| 网关自产响应的种类（不得被误判为上游错误） | 配额 429 `ADR-0020`；熔断 503 `LlmCircuitBreakerRegistry.java:14-33`；鉴权/模型授权/体量预检在转发前短路（`ProxyController.java:287-289`、`ContextLimitGuard.java:16-19`） |
| 逐请求证据的既有承载形态 | `V8:43-47`（`request_status` 列与其 CHECK 枚举）、`V8:55`（`retry_count`）、`V8:58-59`（cache token 两列）；响应头先例 `SseReplayEngine.java:22,50`、`ProxyController.java:540` |
| 指标形态 | `ContextLimitGuard.java:51`、`LlmCircuitBreakerRegistry.java:49`（统一 `miqrokey*` 前缀，开关关闭时恒零） |
| 网关进程**已有**数据库写入通道（用量/生命周期） | `gateway-app/pom.xml:37` 以 compile scope（无 `<scope>`）依赖 `queue-spi`；`GatewayFeatureConfig.java:43` `@Import({…, QueueConfig.class})`；`QueueConfig.java:62` 构造 `PostgresUsageEventWriter`，后者执行 `INSERT INTO request_usage_records`（`PostgresUsageEventWriter.java:253,302`，**显式列名**写法）；另 `PostgresMcpAccessLogWriter.java:44` 写 `mcp_access_log` |
| 网关进程**没有**管理审计写入通道 | grep `AuditService` / `admin_audit_events` 在 `gateway-app/src/main/java` 无命中；`admin_audit_events` 的既有写入者在控制面（`AuditServiceImpl.java:86-137`） |

### 1.3 与 issue #544 的边界澄清（重要，避免过度承诺）

#544 的形态是 **HTTP 200 且 `content` 为空**（`finish_reason=length`、`reasoning_content` 非空），见 `docs/live-integration-guide.md:74` 的处置行。**错误驱动的整流失效对其无效**——没有非 2xx，就没有触发点。issue #770 把「#544 只能靠文档」列为痛点之一，本 ADR 必须明确：**#544 场景不在本提案射程内**，它要么由客户端侧指引解决（现状），要么属于「请求前优化器」——那是 [ADR-0023](0023-request-side-cache-breakpoint-injection.md) 的**主动改体族**要单独拍板的另一件事，不能借用本 ADR 的「错误后整流」授权。

---

## 2. 与透明代理红线的关系（本提案的核心争议）

**结论（技术判断，不含产品取舍）**：整流重试需要**删除或修改**请求字段，这与透明代理的产品立场直接冲突，且比 ADR-0023 的「只追加」更深一层：

- ADR-0023 的注入是「补写」，`CLAUDE.md:55` 的字面直接覆盖；
- 本提案是「**删除/改写**」。现有红线文本的枚举词是「重排、标准化、补写」（`CLAUDE.md:55`）、「格式化、排序或注入内容」（`docs/testing-and-acceptance.md:52`）——**都是增改导向的措辞，没有一项字面覆盖「删除」**。但 `docs/architecture.md:34` 的「不改写推理语义」与 `docs/protocol-agents.md:21` 的「字节级透传原则」显然覆盖它。

也就是说：本议题暴露的是**红线文本的可执行性缺口**——即使所有者只打算批准最小例外，也应把红线从「不改体」的口号改写成可判定的断言（哪些字节可动、哪些绝不可动、开关关闭时的等价性如何断言），否则「例外」与「违例」没有共同的可援引条款。

**建议的例外边界（若批准，逐条可测）**：

| # | 边界 |
|---|---|
| E1 | **默认关**；Key 未显式开启时零行为变化，转发字节与今天完全一致 |
| E2 | 只在**收到上游非 2xx 响应之后、向客户端提交任何响应字节之前**触发 |
| E3 | 整流是**白名单操作**：只允许删除 `thinking` / `redacted_thinking` 块、或调整 thinking 预算字段；**绝不触碰**用户消息文本、`system`、`tools`、`tool_use` / `tool_result` 的结构与配对、模型名 |
| E4 | 结构不可识别 / 模式不匹配 / 白名单无适用动作 → **原样返回首个上游响应** |
| E5 | 整流后重试**复用同一凭证、同一 ProviderProduct**，不跨供应商、不跨凭证（沿用 `CLAUDE.md:36` 与 `architecture.md:161` 既有口径） |
| E6 | 整流最多一次；整流后的那次调用**不再叠加连接重试** |
| E7 | 只对**来自上游**的响应判定；网关自产响应（配额 429、熔断 503、鉴权/授权/体量预检）永不触发整流 |
| E8 | 不记正文：只记「是否整流 + 错误模式类别（枚举）+ 整流动作类别」 |
| E9 | 触发整流的那次请求**不写入响应缓存**（见 §4.6） |

---

## 3. 选项与代价（含推荐，最终取舍属所有者）

| 选项 | 改动面 | 风险 | 代价 | 默认行为 |
|---|---|---|---|---|
| **A. 不做（记 DECLINED）** | 无 | 无 | 无；痛点在网关侧无解，切换供应商后旧会话只能靠客户端重开/清上下文或走 #742 的接入方案 | 不变 |
| **B. 只检测不重试（观察档，推荐的第 0 阶段）** | 新增错误体模式分类（有界缓冲）+ 计数器 + 日志分类；**不修改、不重试** | 极低（请求字节零改动；只新增一个受限的读路径） | 小；需要错误体缓冲实现 | 请求路径无变化（只多一次有界读取与分类） |
| **C. Key 级 opt-in：签名整流 + 共享既有 ≤1 重试预算（推荐的目标档）** | B + 整流器 + Key 配置字段 + 生命周期列/响应头 + 契约测试 | 改体（删除块）；整流错误可能产生新的上游错误；多一次上游调用 | 中；需明确授权与文本修订 | 零行为变化（默认关） |
| **D. 独立重试预算（连接重试 ≤1 + 整流重试 ≤1 → 最多 3 次上游尝试）** | 同 C，另需改重试语义与 `retry_count` 口径 | 与 `CLAUDE.md:36`「最多安全重试一次」的**字面**冲突；上游成本与幂等论证×1.5 | 同 C + 红线文本第二处修订 | 零行为变化（默认关） |
| **E. 预算类整流（`max_tokens` / `thinking.budget_tokens`）** | 同 C 的另一白名单分支 | 改的是**计费与输出长度**相关字段，比删除 thinking 更接近「替用户做产品决定」 | 中高 | 零行为变化（默认关）；建议单列二期 |

**推荐：先 B（观察档）→ 数据支持再上 C**；D 反对（除非所有者明确要改「一次重试」红线，见 §3.1）；E 列为二期且需单独拍板。

**触发条件（何时值得从 B 升到 C）**：同时满足——① 观察档在真实流量中确认**该 Key 的签名类错误是重复出现的稳定模式**（而非偶发）；② #742 场景（封闭客户端直连、无本地代理层）确实无法通过客户端侧修复；③ 所有者批准 §2 的例外边界与红线文本修订；④ 接受「整流后重试仍可能失败，且整流过的请求不写缓存」。任一不满足则维持 B 或退到 A。

### 3.1 重试预算（issue 明确留待拍板的那一项）

| 子选项 | 语义 | 结论 |
|---|---|---|
| R1. **共享既有 ≤1 预算（推荐）** | 一次请求最多 2 次上游尝试；连接重试用掉预算则不再整流，整流用掉则不再连接重试；`retry_count` 语义不变（≤1，`V8:55` 无需改注释口径） | 与 `CLAUDE.md:36` 完全一致，无需第二处红线修订 |
| R2. 独立预算 | 最多 3 次尝试 | 需修订 `CLAUDE.md:36` 与 `testing-and-acceptance.md:41`；幂等与成本论证都要重做 |

**推荐 R1**：整流重试与连接重试在语义上是同一件事的两种触发（「首字节前的一次安全重试」），共享预算能保持既有承诺不变；把预算拆成两条，等于在无人注意的地方把「最多一次」变成了「最多两次」。

---

## 4. 若采纳：落地形态（供拍板后细化）

### 4.1 触发与判定流程

```
上游非 2xx（且不是流中失败）
  → 有界缓冲错误体（上限几十 KB；超出即放弃判定）
  → 错误模式分类（枚举：SIGNATURE_INVALID / THINKING_BLOCK_MISMATCH / MISSING_SIGNATURE / BUDGET_INVALID）
  → 白名单动作选择（删除 thinking 块 / 调整预算字段）
  → 共享预算尚未用完 and 尚未整流过 → 整流 + 用同一凭证重试一次
  → 否则：把首个上游响应按原字节返回
```

关键实现约束：今天的实现**先提交状态码与响应头**（`ProxyController.java:529-540`）再流式转发正文，因此「是否整流」的决策必须发生在提交之前——这是本提案在数据面上唯一的**结构性新增**（错误路径上的有界预判缓冲），必须与成功路径的零拷贝保持隔离，并确保开关关闭时不走该缓冲。

### 4.2 整流语义（白名单）

- 允许：删除 assistant 消息中的 `thinking` / `redacted_thinking` 块；保留其余块顺序；`tool_use` / `tool_result` 的配对与结构**不得改变**（`Expected thinking/redacted_thinking, but found tool_use` 这类错误恰恰源自结构不匹配，错误地删除会制造新的孤儿块）；
- 允许（二期，E）：调整 `max_tokens` / `thinking.budget_tokens` 到自洽区间；
- 禁止：任何用户可见文本、模型名、tools 定义、system 内容；字符串型 `content` 不转换结构（同 ADR-0023 §4.2）；
- 整流结果必须仍是结构合法的请求；不确定即放弃（E4）。

### 4.3 字节策略

整流按定义改变了正文字节（删除块），无法做到 ADR-0023 §4.3 的「定点插入」式最小扰动；可能的折中是「**只删除、不重排、不重序列化其余部分**」（保留原始字节片段，仅挖掉目标块并修正分隔符）。若做不到字节级最小扰动，需明确记录为「整流请求体允许被重新序列化」——这是比 ADR-0023-B2 更大的例外，必须由所有者单独确认。

### 4.4 开关粒度

Key 级字段（建议名 `rectificationPolicy ∈ {OFF, SIGNATURE, SIGNATURE_AND_BUDGET}`，默认 `OFF`），承载与 `cache_policy` 同形：追加迁移 → `VirtualKey` → `RouteSnapshot.KeyRecord` → 网关快照读取（`V4:17-19`、`VirtualKey.java:25`、`RouteSnapshot.java:234`、`JdbcRouteSnapshotLoader.java:157,164`）。控制面表单暴露并回显；前端 Key 列表增列。开启时 UI 必须明示「该 Key 的请求可能被网关改写后重试」。

**项目级粒度（本 ADR 新增的待议点，不预设答案）**：ADR-0018 已确立 key×project 多绑定——一把 Key 可授权用于多个项目（`docs/decisions/0018-single-key-multi-project.md` D1），而「thinking 签名是否失效」取决于**该项目的会话历史与上游组合**，不是 Key 的固有属性；同一把 Key 服务两个项目时，项目 A 需要的整流可能是项目 B 的错误来源。因此粒度至少有三档：纯 Key 级（本 ADR 推荐的最小面）、Key 级 + 项目绑定级覆盖（`key_project_binding` 行上再带一列或白名单）、Key 级 + 会话/上游维度（不在首版讨论）。本 ADR 不替所有者选档，列入 §6；若选项目级覆盖，改动面从「Key 记录加一列」扩到「绑定行加一列 + 快照按绑定行下发」，成本与测试面都高于纯 Key 级。

### 4.5 失败与回退

- 开关关闭 → 字节等同、零行为变化（E1，用现有契约测试锁定）；
- 模式不匹配 / 结构不可识别 / 白名单无动作 / 错误体超出缓冲上限 → 原样返回首个上游响应（E4）；
- 整流后重试仍失败 → **返回第一次（原始）上游响应**（原样字节与状态），并在响应头/记录中声明曾尝试整流；不把整流后那次的错误正文返回给客户端——客户端的请求确实产生了第一个错误，如实返回；
- 整流后不再叠加连接重试（E6）；连接重试已用掉预算则不再整流（§3.1-R1）；
- 流式响应一旦开始，任何整流都不再可能（结构上：错误状态先于首字节，见 §4.1）；
- 网关自产响应（配额/熔断/鉴权/授权/预检）永不进入整流判定（E7）；
- 不引入新的阻塞点：整流是纯 CPU 转换，无 IO、无外部状态（ADR-0005 不变）。

### 4.6 与既有决策的关系

- **ADR-0002（透明代理）**：见 §2，需要显式例外与文本修订。
- **ADR-0009（缓存）**：缓存只存 2xx（`0009:17`）。整流后重试若成功即为 2xx，理论上可写入缓存；**但缓存键是按原始请求派生的**，写入会让后续「同样的、本会失败的请求」从缓存拿到 200——等于把整流结果永久化并掩盖真实错误。因此**建议的规则是**：**发生过整流的请求不写缓存**（E9，待所有者拍板），响应头（§4.7）已说明原因。
- **ADR-0020（配额软着陆）**：配额 REJECT 的 429 由网关在转发前产生，**不得**被整流逻辑当作上游错误（E7）——这是最容易写错的一处，需专门测试。
- **#704/#717（路由与回退）**：见 §4.8。
- **#740（内容过滤拦截）**：同族，共用例外模板。
- **ADR-0005**：不需要外部状态，不变。

### 4.7 审计与可观测

| 需求 | 落点 |
|---|---|
| 逐请求「是否整流、哪一类错误、做了什么动作」 | ① `request_usage_records` 追加列（枚举，如 `rectification_class`；同 `retry_count` 先例 `V8:55`）；② 响应头（同 `X-MiQroKey-Cache` 先例 `SseReplayEngine.java:22`），建议 `X-MiQroKey-Rectify: <class>`；③ 计数器（形态同 `ContextLimitGuard.java:51`） |
| 错误模式分类 | 只存**枚举类别**，不存错误正文、不存请求内容（E8） |
| 配置变更留痕 | 控制面 `AuditService.record(...)`（`AuditService.java:36-37`） |
| 逐请求审计事件 | **不在本提案范围**：`admin_audit_events` 的既有写入者在控制面，网关侧没有该通道（§1.2 末两行）——若所有者要求逐请求审计，需要单独决策一个跨进程通道（新 ADR）。注意与①的区别：① 走的是网关**已有**的 `request_usage_records` 写入器（`PostgresUsageEventWriter.java:253,302`），不新建通道 |
| 观察档（选项 B） | 同①③但只计数不整流，用于 §3 的触发条件判定 |

### 4.8 与 #704/#717 的关系（本 ADR 的边界）

两条不同的轴，**必须分开拍板**：

| | 本 ADR（整流重试） | #704/#717（路由/回退） |
|---|---|---|
| 变的对象 | 请求体（白名单内） | 选中的凭证/上游 |
| 凭证 | **同一凭证**（E5） | 切换到另一个凭证 |
| 触发 | 上游错误模式匹配 | 质量阈值/可用性 |
| 既有红线 | 透明代理（§2） | `CLAUDE.md:36` 故障切换条款、F46 已 DECLINED |

若两者都落地，不变量必须保持：`gateway_request_id`、`virtual_key_id`、`project_id` 不因任何一次整流或切换而改变，审计锚点仍指向**最初**的请求。**交叉依赖（未决）**：#717 若引入自己的尝试预算，需与 §3.1 的 R1/R2 联合定义，避免出现「连接重试 + 整流重试 + 跨凭证切换」三次上游调用的叠加，那将实质推翻 `CLAUDE.md:36`。

### 4.9 测试与验收（若采纳）

1. 开关关闭：现有字节级契约测试全绿（默认路径断言）；
2. 开启 + 上游返回签名类错误 + 整流成功：断言上游收到两次请求、第二次不含 thinking 块、客户端只看到一次正常响应、响应头声明整流、`retry_count` 仍 ≤1；
3. 整流后仍失败：客户端拿到**第一次**响应（状态码与正文逐字节一致）；
4. 白名单外错误（如 400 参数错、401、429 配额）：不整流、不重试、原样返回（含 ADR-0020 的 429 专项断言）；
5. 网关自产响应不被误判（E7）；
6. 整流过的请求不写缓存（E9）；
7. 权限路径：未开启的 Key 不整流（隔离断言）。

---

## 5. 后果

**数据面**：默认零行为变化；开启的 Key 在上游报错时可能多一次上游调用（共享 ≤1 预算内），并可能发送一个被改写的请求体。

**控制面**：Key 新增一个可配置字段 + 审计留痕；若采纳选项 B/C 还要在 `request_usage_records` 追加列——改动面不止「一次性迁移」：该表写入是**显式列名 + 命名参数**写法且同一语句出现两处（`PostgresUsageEventWriter.java:253,302`），新增列必须同步改两处列清单与参数映射，并改域事件（`RequestStartedEvent` / `RequestCompletedEvent`）与 `ProxyController` 的发射点；迁移只是其中一步。

**成本影响**：整流重试成功 = 一次额外计费调用（上游对成功调用计费）；整流失败 = 一次额外调用且客户端仍看到错误。这正是「只在确认存在稳定重复错误模式时才开启」的原因（§3 触发条件）。

**风险与缓解**：*错误整流制造新错误* → 白名单 + 结构校验 + 最多一次；*掩盖真实错误* → 首次响应作为兜底返回 + 响应头声明；*成本放大* → 共享预算 + 观察档先行；*红线漂移* → 三处文本修订 + 开关关闭路径的字节等同断言常驻测试。

---

## 6. 未决项（需要所有者/证据补齐，本 ADR 不替其决定）

1. **是否批准红线例外**；若不批准，本 ADR 转 `DECLINED` 并登记 `feature-backlog`（`F49` 落点），现有关闭口径不变。
2. **重试预算**：R1（共享 ≤1，推荐）还是 R2（独立预算，需修订 `CLAUDE.md:36`）。
3. **整流是否允许重新序列化请求体**（§4.3）——决定例外面大小。
4. **首版白名单范围**：仅签名整流，还是含预算类整流（选项 E）；issue 建议前者。
5. **逐请求审计**：接受「生命周期列 + 响应头 + 指标」举证，还是必须新建跨进程审计通道（另立 ADR）。
6. **真实的上游错误模式样本**：本仓目前**不按错误内容分类**（错误体虽被逐块缓冲，却只被用量解析、保留策略与请求 id 提取消费——`ProxyController.java:566`、`:578`、`:850`，从不分类，见 §1.2），**没有任何一条真实签名错误的样本或计数**——这是选项 B（观察档）存在的直接理由；在拿到样本前，白名单的模式清单只有 issue 转述，不可作为实现依据。
7. **与 #717 的预算叠加口径**（§4.8）。
8. **开关粒度是否只到 Key 级**：是否需要在 ADR-0018 的 key×project 多绑定上增加项目级覆盖（§4.4 新增待议点）——决定迁移与快照是「Key 加一列」还是「绑定行加一列」。
9. **是否复核 cc-switch 源码**（本 ADR 未复核其实现）。

---

## 7. 拍板记录（2026-09-19 部分采纳）

- **采纳：选项 B（观察档）为一期**。所有者答复「可以，做吧」（对评估线推荐的整体确认），推荐即 §3 的「先 B（观察档）→ 数据支持再上 C」。
- **B 的边界（实现即契约）**：对**已缓冲**的上游非 2xx 响应体做**有界**（前 8KB）子串分类，产出**有界枚举**类别
  （`SIGNATURE_INVALID` / `THINKING_BLOCK_MISMATCH` / `MISSING_SIGNATURE` / `BUDGET_INVALID` / `UNCLASSIFIED`）的计数与一行日志；
  **不重试、不改写请求、不改变响应**；错误正文只读不存；被截断的缓冲不分类。
- **未采纳（仍为 Proposed 状态、不得据此实现）**：C（Key 级 opt-in 整流重试）、D（独立重试预算）、E（预算类整流）。
  它们各自需要 §2 的例外边界与红线文本修订，按 §3 的触发条件在 B 的数据支持下再议。
- **§4 中仅与 C 相关的设计**（整流语义、字节策略、整流后不写缓存、响应头声明等）**保持为提案**，本批不落实现。
