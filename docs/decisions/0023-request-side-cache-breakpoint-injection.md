# ADR-0023：请求侧可选改造①——prompt 缓存断点自动注入（opt-in 按 Key）

- 状态：**Proposed（待所有者拍板；拍板前不实现，选项见 §3）**
- 日期：2026-09-18
- 关联：issue #769；[ADR-0002](0002-transparent-proxy.md)（透明代理）；[ADR-0009](0009-enable-response-cache.md)（缓存双重 opt-in 先例）；[ADR-0005](0005-no-redis-v1.md)（不引外部状态）；[ADR-0020](0020-quota-soft-landing.md)（opt-in 判定的落地形态先例）；姊妹篇 [ADR-0024](0024-request-side-rectification-retry.md)；issue #740（同属「默认关的改写族」决策批次）、#742（封闭客户端接入，本提案的价值场景）、#704/#717（模型路由与回退，未决）
- 触发事件：issue #769——对照 cc-switch 源码（`src-tauri/src/proxy/cache_injector.rs`）逐模块比对后，提出「不带断点的客户端在 Anthropic 系上游拿不到 prompt 缓存收益」；该能力与 CLAUDE.md 的透明代理红线正面冲突，故先行 ADR。

---

## 1. 背景与现状

### 1.1 提案内容（issue #769）

网关在 Anthropic 协议入站时，对 opt-in 的 Virtual Key 自动注入 `cache_control` 断点，语义对齐 cc-switch：**最多 4 个**、**保留调用方已有标记不删不重排**（budget = 4 − existing）、注入顺序 **tools 末尾 → system 末尾 → messages 末尾**。默认关，按 Key 开启；开启后该 Key 的转发不再字节等同，需文档与 UI 明示。OpenAI 系入站不适用（DeepSeek/OpenAI 为自动缓存，无需断点）。

外部实证为 **issue 转述**（本仓未复核 cc-switch/AWS 源码，不作为本 ADR 的论据）：cc-switch `cache_injector.rs` 的注入顺序与 4 断点上限；AWS Bedrock 2026-08 在 GPT-5.6 系提供 explicit cache breakpoints。

### 1.2 现状证据（本仓坐标）

| 事实 | 坐标 |
|---|---|
| 透明代理红线（最强表述） | `CLAUDE.md:55`「透明代理不得重排、标准化或补写推理请求 JSON。」 |
| 红线在文档中的展开 | `docs/proxy-and-cc-switch.md:157`「不增加系统提示词、不重排工具、不标准化 JSON、不更改模型名」；`docs/protocol-agents.md:21`「字节级透传原则：透明代理不重排/标准化/补写请求正文」 |
| 请求体在网关内被完整缓冲后原样转发 | `ProxyController.java:333-334`（`DataBufferUtils.join` → `byte[]`）、`:527-528`（用同一 `byte[]` 构造上游 body） |
| 转发路径的只读承诺 | `ProxyController.java:295-296`「Read-only: the accepted body is forwarded byte-identically.」 |
| 既有只读解析（模型/工具/流式标志） | `ProxyController.java:258` `parseQuietly(body)`，用于模型授权与缓存资格判定 |
| 缓存键归一化永不回写 | `CacheKeyFactory.java:42-43`「The gateway NEVER re-emits the normalized JSON upstream: the raw request bytes are forwarded untouched.」 |
| 体量预检同样只读 | `ContextLimitGuard.java:16-19`「never parses, re-serializes, reorders or truncates the request」 |
| 验收口径目前是**零注入** | `docs/testing-and-acceptance.md:52`「请求体不被格式化、排序或注入内容；」，`:51`「`cache_control` 和缓存相关头不丢失」，`:27`「Anthropic beta、cache_control、thinking、tool use/result 保留」 |
| Prompt Cache 依赖字段必须原样保留 | `docs/architecture.md:166-169`（请求体顺序和内容、`cache_control` 等协议字段、Anthropic beta 头、Responses API 会话字段） |
| 「参数改写」被登记为与红线冲突 | `docs/tencent-ai-gateway-mapping.md:26`、`docs/ai-gateway-comparison.md:70`、`docs/feature-backlog.md:112`（F49，状态 `ADR`） |
| 同族 opt-in 先例：响应缓存双重开关 | `docs/decisions/0009-enable-response-cache.md:18,26`；落地点 `CacheEligibility.java:31`（`"ENABLED".equals(ctx.key().cachePolicy()) && "1".equals(cacheableHeaderValue) && !hasToolFields`） |
| Key 级开关的既有承载形态 | `V4__virtual_key_tag_routing.sql:17-19`（`cache_policy varchar(32) NOT NULL DEFAULT 'DISABLED'` + CHECK）、`VirtualKey.java:25`、`RouteSnapshot.java:234`、`JdbcRouteSnapshotLoader.java:157,164`、`OpenAdminVirtualKeysController.java:68`、`VirtualKeyService.java:191`、`frontend/src/views/next/NextKeysView.vue:117,980-987` |
| 逐请求证据的既有承载形态 | `V8__request_usage_records.sql:55`（`retry_count integer NOT NULL DEFAULT 0`）；响应头先例 `SseReplayEngine.java:22,50`、`ProxyController.java:540`（`X-MiQroKey-Cache`） |
| 指标形态 | `GatewayMetricsFilter.java:30`、`GatewayTtfbMetrics.java:24`、`ContextLimitGuard.java:51`、`LlmCircuitBreakerRegistry.java:49`（统一 `miqrokey*` 前缀，开关关闭时计数恒零） |
| 上游错误/成功判定只看状态码，不读响应体 | `ProxyController.java:779`；错误体在响应发出前不被读取：`:529-540`（先 `setStatusCode` + 复制响应头，再流式写 body） |
| 网关进程**已有**数据库写入通道（用量/生命周期） | `gateway-app/pom.xml:37` 以 compile scope（无 `<scope>`）依赖 `queue-spi`；`GatewayFeatureConfig.java:43` `@Import({…, QueueConfig.class})` 把队列装配进网关上下文；`QueueConfig.java:62` 构造 `PostgresUsageEventWriter`，后者执行 `INSERT INTO request_usage_records`（`PostgresUsageEventWriter.java:181,230`，**显式列名**写法）；另有 `PostgresMcpAccessLogWriter.java:44` 写 `mcp_access_log` |
| 网关进程**没有**管理审计写入通道 | `gateway-app/src/main/java` 下 grep `AuditService|admin_audit_events` 无命中；`admin_audit_events` 的既有写入者在控制面（`AuditServiceImpl.java:86-137`）——逐请求写该表需新增写入器/通道，超出本提案 |

### 1.3 issue #769 提出的问题逐条回答

| # | 问题（issue 原文要点） | 结论 | 依据 |
|---|---|---|---|
| Q1 | 配置面：Key 级开关，或服务级默认 + Key 覆盖 | 可选；**推荐 Key 级**（与 `cache_policy` 同形），不推荐服务级默认开启——默认开启会改变存量 Key 的转发语义 | `V4:17-19`、`VirtualKeyService.java:191`、`0009:18` |
| Q2 | 网关：Anthropic 协议入站按 cc-switch 顺序注入；OpenAI 系不适用 | 可行且**必须按协议分流**——断点是 Anthropic Messages 协议字段，OpenAI 系（`/v1/chat/completions`、`/v1/responses`）无对应语义，不得注入 | 入站路径 `ProxyController.java:107,194-207`；协议语义 `docs/proxy-and-cc-switch.md:70` |
| Q3 | 观测：注入次数指标 + 审计 | **拆成两条通道**：逐请求事实用「计数器指标 + 生命周期记录字段 + 响应头」；**审计只能记配置变更**（Key 开关本身）：网关**已有** `request_usage_records` 写入通道（§1.2 表末三行），逐请求事实落该表是成本最低的选择；而 `admin_audit_events` 的既有写入者在控制面，逐请求写该表需新增写入器/通道，超出本提案 | 见 §1.2 表末三行；`V8:55`、`SseReplayEngine.java:22` |
| Q4 | 对比开启前后该 Key 的 cacheRead 占比 | 数据已具备：`request_usage_records.cache_read_input_tokens` / `cache_creation_input_tokens` | `V8__request_usage_records.sql:58-59` |
| Q5 | 代价与风险 | 工程量小（注入点小），**风险在「改体」行为本身**；另有两项本提案新增的风险：字符串型 `system`/`content` 的结构差异、缓存写入溢价（见 §4.5/§6） | §4 |
| Q6 | 折中：默认关、只添加、≤4、已有标记保留、审计记注入发生、开启后不再字节等同 | **建议全部采纳（待所有者拍板）**，并按 §2 的例外边界收紧（「审计记注入发生」按 Q3 修正为指标 + 生命周期字段） | §2、§4 |
| Q7 | 若裁定维持「零改体」 | 则本 ADR 记为 `DECLINED` 并登记 `feature-backlog`（F49 现有条目即为该落点），不发生产品行为 | `docs/feature-backlog.md:112` |

---

## 2. 与透明代理红线的关系（本提案的核心争议）

**结论（技术判断，不含产品取舍）**：本提案在**字面上就是红线所禁止的行为**。`CLAUDE.md:55` 的「补写推理请求 JSON」正是「注入字段」的同义表述；`docs/testing-and-acceptance.md:52` 更把「请求体不被注入内容」写成了验收断言。因此这不是「红线之外的相邻能力」，而是**需要显式例外 + 修订红线文本**的决定：

- 若所有者维持零改体 → 本提案 `DECLINED`，`CLAUDE.md:55`、`testing-and-acceptance.md:52` 不动；这是**完全自洽**的现状，不需要任何工程改动。
- 若所有者批准 opt-in 例外 → 必须同时修订三处文本，缺一即为「文档与代码不一致」：`CLAUDE.md:55` 加例外句、`docs/testing-and-acceptance.md:52` 改为「开关关闭时字节等同；开关开启时仅允许新增 `cache_control` 标记」，`docs/architecture.md:166-169` 的「原样保留」段落补一句范围限定。

**建议的例外边界（若批准，逐条可测）**：

| # | 边界 |
|---|---|
| E1 | **默认关**；Key 未显式开启时，转发字节与今天完全一致（可用字节级契约测试锁定） |
| E2 | **只增不删不改**：不删除、不重排、不修改调用方已有的任何字节/标记；断点总数（既有 + 注入）≤ 4 |
| E3 | 只作用于**转发拷贝**：鉴权、模型授权、体量预检、缓存键派生一律仍用原始字节，注入结果不得回流到上述任何路径 |
| E4 | 仅 **Anthropic Messages** 协议入站（`/v1/messages`）；OpenAI 系与其他入站路径不注入 |
| E5 | 结构不可识别时**跳过注入并原样转发**（fail-safe：宁可无缓存收益，不做结构改造） |
| E6 | 不记正文：只记「注入了 / 跳过 / 失败」与数量，错误与日志不得包含请求内容 |
| E7 | 一旦开启，该 Key 的转发不再字节等同——**控制面创建/编辑 Key 时必须明示**，且 UI 可回显当前状态 |

---

## 3. 选项与代价（含推荐，最终取舍属所有者）

| 选项 | 改动面 | 风险 | 代价 | 默认行为 |
|---|---|---|---|---|
| **A. 维持零改体（不做）** | 无 | 无 | 无；代价是「不带断点的客户端拿不到 prompt 缓存收益」在网关侧无解，只能靠客户端/CC Switch 侧解决 | 不变 |
| **B. Key 级 opt-in 注入（推荐）** | 网关新增一个注入器（仅转发拷贝）+ Key 配置字段（同 `cache_policy` 形态）+ 计数器/生命周期字段 + 契约测试；控制面表单/回显 | 改体行为本身；注入错误可能损坏请求（用 E5 兜底） | 中低；不影响鉴权/计量路径 | 零行为变化（默认关） |
| **C. 服务级默认开 + Key 可覆盖** | 同 B，另加默认值语义与存量 Key 的批量影响面 | 存量 Key 的转发语义被**静默改变**；与 `0009:18`「默认全关」原则冲突 | 同 B + 迁移说明与回归面 | 变化（不可接受，除非所有者明确要求） |
| **D. 只做客户端指引（不做网关注入）** | 仅文档（对接指引加一节「自带 `cache_control` 断点」） | 无 | 极低 | 不变；但 #742 的目标场景（封闭客户端直连、无本地代理层）恰恰无法自行加断点——本选项对该场景无效 |

**推荐：B**，理由：与 ADR-0009 的「双重 opt-in」同形（开关默认关、显式开启、可随时退出），改动面最小且不触碰鉴权/计量/缓存键派生；A 与 D 都不解决 #769 指出的「封闭客户端」缺口，而 C 违反既有的「默认关」原则。

**触发条件（何时值得做）**：同时满足——① 存在真实、可复现的收益场景（某把 Key 的客户端不带断点，且该 Key 在 Anthropic 系上游有稳定的长前缀复用）；② 上游确实支持 `cache_control` 断点（见 §6 未决项 1）；③ 所有者接受红线例外与文本修订。任一不满足则本提案应保持 `Proposed`/转 `DECLINED`，不进入实现。

---

## 4. 若采纳：落地形态（供拍板后细化）

### 4.1 注入语义

顺序 tools 末尾 → system 末尾 → messages 末尾（对齐 issue 转述的 cc-switch 实现）；budget = 4 − 既有断点数，budget ≤ 0 时不注入；已有标记一律保留。

### 4.2 结构边界（本 ADR 新增，须与 owner 一并拍板）

- `system` 为**字符串**、或 `messages[].content` 为**字符串**时：**不注入、不转换结构**（把字符串改写成 block 数组属于「重排/标准化」，超出本提案授权范围）；
- `tools` 为空数组、`messages` 为空时不注入；
- 目标位置已有 `cache_control` 时跳过该位置，顺延到下一个位置。

### 4.3 字节策略（子决策点，两种成本）

| 子选项 | 语义 | 代价 |
|---|---|---|
| B1. **定点插入（推荐）** | 保留原始字节，仅在目标对象闭合处插入 `,"cache_control":{"type":"ephemeral"}` | 需要带偏移的 JSON 扫描/切片，代码量大于 B2 |
| B2. 解析后整体重序列化 | 实现最简（cc-switch 的 Rust 侧即此形态） | **改变全文字节**（空白、转义、数字格式）——例外面从「新增字段」扩大到「重新格式化整个请求体」，与 §2 的 E2 冲突 |

若 owner 只想批准最小例外面，应选 B1；选择 B2 等于把例外边界放宽到「网关可重写请求体字节布局」，本 ADR 不建议。

### 4.4 开关粒度与配置面

Key 级字段（建议名 `cacheInjectionPolicy ∈ {OFF, BREAKPOINTS}`，默认 `OFF`），承载与 `cache_policy` 同形：迁移（新版本号，追加不回改）→ `VirtualKey` 记录 → `RouteSnapshot.KeyRecord` → 网关快照读取。控制面创建/编辑 Key 时暴露该字段并回显；前端 Key 列表增列（同 `NextKeysView.vue:117,980-987` 的既有形态）。

**项目级粒度（本 ADR 新增的待议点，不预设答案）**：ADR-0018 已确立 key×project 多绑定——一把 Key 可授权用于多个项目（`docs/decisions/0018-single-key-multi-project.md` D1），而 prompt 前缀与缓存复用率是**项目相关**的，同一把 Key 在不同项目下的最优策略可能不同。因此粒度问题实际有三档：纯 Key 级（本 ADR 推荐的最小面）、Key 级 + 项目绑定级覆盖（`key_project_binding` 行上再加一列/BREAKPOINTS 白名单）、服务级默认 + Key 覆盖（选项 C，已不推荐）。本 ADR 不替所有者选档，列入 §6；若选项目级覆盖，改动面需在 `RouteSnapshot` 的绑定行上扩展（`JdbcRouteSnapshotLoader.loadBindings()`，`:171-194`，其 SQL 直接查 `key_project_binding`），成本高于纯 Key 级。

### 4.5 失败与回退

- 解析失败 / 结构不符 / budget 用尽 → **不注入，原样转发**（E5），计 `skipped`；
- 注入本身不得使请求变成上游不可接受的形态：注入后仅新增字段，不改变原有类型与顺序；
- 若上游对断点报错（例如不支持该字段的兼容端点）→ 请求按上游错误原样返回，**不因注入失败而重试**（重试语义见 ADR-0024，两者不叠加）；
- 注入发生在 `bufferBody` 之后、`callUpstreamOnce` 之前，**不引入新阻塞点**（纯 CPU、无 IO、无锁）。

### 4.6 审计与可观测

| 需求 | 落点 |
|---|---|
| 逐请求「是否注入、注入几个」 | ① 计数器（形态同 `ContextLimitGuard.java:51`/`LlmCircuitBreakerRegistry.java:49`，开关关闭时恒零）；② `request_usage_records` 增列（追加迁移；该表已有 `retry_count` 先例 `V8:55`）；③ 响应头（同 `X-MiQroKey-Cache` 先例 `SseReplayEngine.java:22`），建议 `X-MiQroKey-Cache-Injection: <n>` |
| 「这次请求被注入过」的可举证 | 上述 ②+③ 均为**逐请求**证据，且不含正文 |
| 配置变更留痕 | 控制面 `AuditService.record(...)`（Key 开关变更走既有审计链路 `AuditService.java:36-37`）；**逐请求审计不在本提案范围**（`admin_audit_events` 的写入者在控制面，网关侧无该通道，见 §1.2 表末三行） |
| 收益评估 | 按 Key 对比开启前后 `cache_read_input_tokens` / `cache_creation_input_tokens`（`V8:58-59`） |
| 不记录 | 请求/响应正文、断点所在的具体内容（只记数量与位置类别） |

### 4.7 与既有决策的关系

- **ADR-0002（透明代理）**：见 §2——需要显式例外与文本修订，不存在「不违反」的解读空间。
- **ADR-0009（缓存）**：注入与响应缓存是**两条独立通道**，都不改正文内容；同时开启时，缓存键仍按**原始**字节派生（E3），注入只影响发往上游的那一次请求。缓存命中重放时不会经过注入路径，属预期。
- **ADR-0005（不引 Redis）**：本提案无需任何外部状态，不变。
- **#740（内容过滤拦截）**：同属「默认关的改写族」，两个决策应使用同一套例外模板（默认关 + 边界枚举 + 逐请求可举证），便于一次性拍板。
- **#704/#717（模型路由与回退）**：本提案**不涉及**路由或凭证选择——注入在路由确定之后、对已选定的上游执行；若 #717 引入多凭证切换，断点随请求体一并作用于**最终选中的那一次调用**，不改变切换语义。

### 4.8 测试与验收（若采纳）

1. 开关关闭：与今天的字节级契约测试**完全一致**（`testing-and-acceptance.md:52` 维持原样，作为默认路径断言）；
2. 开关开启：断言「转发体与请求体的差异仅为新增的 `cache_control` 字段」，并断言既有标记未被删除或重排；
3. 失败路径：畸形 JSON / 字符串型 `system` / budget 用尽 → 原样转发 + 计数为 skipped；
4. 权限路径：未开启的 Key 与其他租户的 Key 不注入（隔离断言）。

---

## 5. 后果

**数据面**：默认零行为变化；开启的 Key 多一次纯 CPU 注入（无 IO、无阻塞）。上游侧行为变化：断点使上游写入 prompt 缓存，后续同前缀请求可命中。

**控制面**：Key 新增一个可配置字段 + 审计留痕；无新表。若逐请求字段落在 `request_usage_records`，改动面不止「一次性追加迁移」：该表的写入是**显式列名 + 命名参数**写法（`PostgresUsageEventWriter.java:181,230`，同一语句出现两次），新增列必须同步改这两处的列清单与参数映射，并改域事件（`RequestStartedEvent` / `RequestCompletedEvent`）与 `ProxyController` 的发射点；迁移本身只是其中一步。若不做逐请求列而只用响应头 + 计数器，则控制面零改动。

**成本影响（需所有者评估）**：缓存写入通常按高于普通输入计价，读取按折扣计价；同一上游的计价规则各异，本 ADR 不据此外推（列入 §6）。若某 Key 前缀复用率低，开启注入可能**增加**成本——这正是 §4.6 的收益对比要求存在的原因，也建议把「开启后 N 天内 cacheRead 占比未改善即可回退」写入运行手册（若采纳）。

---

## 6. 未决项（需要所有者/证据补齐，本 ADR 不替其决定）

1. **目标上游是否支持 `cache_control` 断点**：GLM / Kimi / DeepSeek 的 `/v1/messages` 对断点的支持与计费口径需逐产品实测（真实凭证，属 `WAITING_FOR_CREDENTIAL` 类）；不支持时本提案无可兑现收益。
2. **是否批准红线例外**：若不批准，本 ADR 转为 `DECLINED` 并登记 `feature-backlog`（`F49` 条目即落点），三处红线文本保持不变。
3. **例外边界选择**：B1（定点插入）还是 B2（重序列化）——决定例外面大小。
4. **开关粒度**：仅 Key 级，还是允许「服务级默认 + Key 覆盖」（选项 C 的默认值部分）。
5. **是否接受「逐请求注入」只以指标 + 生命周期字段 + 响应头举证**（而非独立审计事件）。
6. **成本回退阈值**：开启后收益未达预期时的自动/人工回退口径。
7. **开关粒度是否只到 Key 级**：是否需要在 ADR-0018 的 key×project 多绑定上增加项目级覆盖（§4.4 新增待议点）；答案是「只到 Key 级」还是「允许项目级覆盖」，直接决定迁移与快照的改动面。
8. **cc-switch/AWS 的转述证据是否需要独立复核**（本 ADR 未复核其源码）。
