# ADR-0022：语义缓存启用评估——正文向量化出网关的合规边界

- 状态：**Accepted（2026-09-19 所有者拍板，记录见 §11）**——决策信息与选项见 §2–§7。**本文件不授权启用语义缓存**：运行时行为维持不变（语义缓存继续不启用，同 ADR-0009:38）。
- 日期：2026-09-18（Proposed）／2026-09-19（Accepted，§11 拍板记录）
- 效力：D1–D5（§11）与 §11.1 的落地结论；**不产生任何启用语义缓存的实现义务**（启用须按 §11.3 另行审议）
- 关联：[#718](https://github.com/sijie-Z/miqro-gate/issues/718)（提案 issue，六问出处）；[ADR-0009](0009-enable-response-cache.md)（L1 精确缓存启用；语义缓存不启用及其重启条件）；[ADR-0005](0005-no-redis-v1.md)（v1 不引入 Redis）；[ADR-0003](0003-no-response-cache.md)（已被 0009 取代）；[ADR-0002](0002-transparent-proxy.md)（透明代理）；[ADR-0014](0014-content-retention-and-kafka-events.md)（唯一的正文出网关先例，含红线放宽授权点）；[#706](https://github.com/sijie-Z/miqro-gate/issues/706)（限流评估，同属待拍板类）；[feature-backlog F41](../feature-backlog.md)（语义缓存登记项）；[CLAUDE.md](../../CLAUDE.md) §2

## 1. 背景与现状

### 1.1 术语校准：`L2` 在库内指两件事（先纠这一处，否则本议题无法讨论）

| 出处 | `L1` | `L2` |
|---|---|---|
| 代码 | Caffeine 进程内缓存（`backend/cache-spi/.../cache/CacheConfig.java:37-50`；`cache-spi/.../CaffeineCacheProvider.java:28`，maximumSize 1000） | **PostgreSQL `cache_entry` 精确缓存**（`cache-spi/.../PostgresCacheProvider.java:19-21`「Stores raw response bytes and replays them byte-identically」；`:69` 返回 `LookupLevel.L2_HIT`） |
| 文档 | ADR-0009:13 把 PostgreSQL 表记作「L1」 | `docs/configuration-reference.md:287`「语义缓存（L2 向量）不启用」 |

- `X-MiQroKey-Cache` 响应头的 `L2` 取值 = **PostgreSQL 精确缓存命中**，不是语义命中（`ProxyController.java:696-711`：`L1_HIT -> "L1"`，其余 -> `"L2"`）。
- 结论：本 ADR 一律用「**语义缓存 / 向量召回**」指 #718 讨论的对象，不使用 `L2` 一词。既有文档的命名不一致记录在 §10，本文件不改写既有 ADR 结论。

### 1.2 现状（可核实）

- **语义缓存无任何实现**：`backend/` 主源码中不存在 embedding 调用、向量库客户端或 ANN 索引（`grep -rniE "pgvector|embedding|vector store|faiss|milvus|qdrant" backend/*/src/main` 零命中；`semantic` 在 `*/src/main` 的命中除英文单词 semantics 的普通注释外，只剩下述语义 scope 键构造）。网关同时**不代理 embeddings 端点**：`/v1/embeddings` 返回 404 并有测试固化（`GatewaySecurityHardeningTest.java:188,223`）。语义缓存只以「接口预留」登记在 `docs/decisions/0009-enable-response-cache.md:13,38`、`docs/feature-backlog.md:99`（F41）、`docs/configuration-reference.md:287`。
- **精确缓存已启用且默认关**：总开关 `miqrokey.cache.enabled` 默认 `false`（`backend/gateway-app/src/main/resources/application.yml:88-96`）；L1/L2 子开关默认 `true`、TTL 默认 300s。启用后仍须**双重 opt-in**：Key `cachePolicy=ENABLED` **且** 请求头 `X-MiQroKey-Cacheable: 1`，且无工具字段、body 非空（`CacheEligibility.java:24,29-33`）。工具调用永不缓存。
- **缓存键已经含「语义」成分，且完全在网关内完成**：`CacheKeyFactory.compute()`（`:68-76`）对 chat 形态请求使用 `semanticScope(body)`（`:102-142`）——取 system 消息 + **最后一条 user 消息**拼接后参与 SHA-256；无 user 消息时回落到全 body 归一化哈希。键还含 tenant/project/keyId/product/model/purpose/stream 维度。这正是 `docs/ai-gateway-comparison.md:92` 记录的「我们语义键=末条 user 消息哈希」。
- **正文当前不出网关**：缓存条目只存响应字节、按字节重放；「缓存内容不解读、不进日志与审计」（`docs/configuration-reference.md:287`、`docs/decisions/0009-enable-response-cache.md:23`）。响应在 SSE 场景下同样字节重放（`SseReplayEngine.java:12-17,51-52`：类注释「Replays a cached response byte-identically … including SSE streams, whose byte sequence must be preserved」，实现为 `writeWith(Flux.just(bufferFactory().wrap(cached.body())))`）。
- **唯一既有的正文出网关通道是 ADR-0014 的合规留痕**：默认关闭、租户 opt-in、AES-256-GCM 密文信封、Kafka topic 上永无明文（`RetentionSidecar.java`、`KafkaRetentionPublisher.java`，实现与测试在库）。

### 1.3 核心矛盾

语义缓存的行业形态 = 「精确键未命中时，把请求正文送去 embedding 服务 / 向量库做近似召回」。这条链路要求**请求正文（或其中一段）离开网关进程**，与产品红线「不保存 prompt、代码、工具正文和模型回答」（`CLAUDE.md:39`）以及 `docs/security.md:78-84`「默认不保存 prompt、代码片段、图片、文件」的对外承诺直接冲突。客户可见层面，登录页文案写着「本地化部署，数据不出环境」（`frontend/src/views/next/NextLoginView.vue:122`，文案源 `frontend/src/i18n/dict.ts:848`）。

因此本议题的性质不是「加一个功能」，而是：**要不要为一类性能优化放宽一条合规红线**——与 ADR-0014 同类，但 ADR-0014 是「密文、默认关、可审计」，而语义缓存的典型形态是「正文送第三方 embedding 服务」或「正文落向量库」，性质更重。

### 1.4 对外口径需要 owner 补事实

#718 转述「对外口径是语义缓存『接口预留、默认不开；要开需拍板 + 新 ADR』」。**该措辞在仓库内无逐字来源**；等价转述见 `docs/decisions/0009-enable-response-cache.md:13,38`、`docs/feature-backlog.md:99`、`docs/configuration-reference.md:287`、`docs/progress.md:1309,1314`。若确已向客户/领导报告过该口径，需 owner 提供原话或材料——因为「维持禁用」与「要开需拍板」是两种不同强度的承诺，决定了本次结论能否直接对外复用。

## 2. Q1 收益：预计命中率与成本节省

- **本系统无实测数据，且现在也拿不到。** 精确缓存已有命中率观测（`cache_hit_event` 表 + 用量汇总 `savedByGatewayCache`，成本报表页展示），但那是**精确缓存**的收益；语义增量收益 = 「精确键未命中、但语义上等价」的请求占比，而这一部分**恰恰需要看到正文才能判定**——本系统不保存正文（`CLAUDE.md:39`），因此无法离线回放估算。这是本议题的鸡生蛋问题，必须如实记录，不能用行业数字代替。
- 可用的**下界**（现有数据即可读出，零新增合规面）：`cache_hit_event` 的命中率，与 `usage_event.cache_key` 的重复度分布。前者可按 `virtual_key_id` / `project_id` / `provider_product_id` / `level` 切分（列见 `V6__usage_events.sql:61-73`，`level ∈ {L1_HIT, L2_HIT}`）；若需按 model / purpose 切分，**不需要新增列**——`cache_key` 是含 `model`、`purpose`、`stream` 维度的 SHA-256 摘要（`CacheKeyFactory.compute():71-74`），按 key 分组即可间接切分。`purpose` 的真实取值只有四个：`CLAUDE_CODE / CLAUDE_DESKTOP / CODEX / CUSTOM`（`backend/domain/src/main/java/com/miqroera/miqrokey/domain/model/VirtualKeyPurpose.java:5`）。但它只给出「精确缓存已覆盖多少」，不给出「语义还能多覆盖多少」。
- 行业侧只有厂商标称，无独立验证：Higress 语义缓存宣称省 40-60%（转引见 `docs/ai-gateway-comparison.md:92`）；腾讯侧文档只有 L1 精确缓存口径（`docs/tencent-ai-gateway-mapping.md:21`「缓存策略 (134822)」行、`:68`「腾讯 L1 方案本土化」）。**这些数字不构成本系统收益的估计。**
- 关键反证：本系统主要场景是编码 Agent（Claude Code / CC Switch 形态，`docs/tencent-ai-gateway-mapping.md:58`），上下文高度多变；ADR-0003 记录的理由是「Coding Agent 请求包含持续变化的代码上下文、工具状态和副作用。缓存完整回答容易返回过期内容或重复工具调用」（`docs/decisions/0003-no-response-cache.md:12`），G7.4 复盘同样标记该风险（`docs/progress.md:1314`）。这与 §5 的流式约束叠加，使预期收益进一步收窄。

**结论**：收益当前**不可估**。任何要求「先证明收益」的拍板，都必须先选一条测量路径（见 §4 与 §10-3）。

## 3. Q2 代价：合规边界怎么重新描述、需要什么补偿控制

若允许正文出网关，合规对外表述必须从「正文不出网关进程」改写为类似「正文在租户内网范围内、以向量化形式短暂离开网关进程，不落库、不落日志、不进第三方」，并承担：

1. **承诺文本变更**：`CLAUDE.md:39`、`docs/security.md:78-84`、登录页文案（`NextLoginView.vue:122`/`dict.ts:848`）需同步修订，且属于**对客户可见承诺**的变更——按 `CLAUDE.md` §2，改变产品决策需新 ADR + 用户明确同意。
2. **第二个信任边界**：embedding 服务（无论自建还是云服务）成为新的正文接收方，纳入威胁模型；云 embedding = 正文出客户环境，与「本地化部署/数据不出环境」直接矛盾。
3. **可选择的补偿措施（全部为已有机制的复用）**：租户级 opt-in 且默认关（同 ADR-0014 纪律）；仅对 `cachePolicy=ENABLED` 的 Key 生效；embedding 请求体不落日志、进审计但只记元数据；向量库与 embedding 服务部署在租户内网；不缓存工具调用（`CacheEligibility` 既有规则）；TTL 上限保持 300s 量级；响应头标注命中来源（复用 `X-MiQroKey-Cache`）。

补充事实（决定代价的关键分叉）：**自建 embedding 服务（内网）与调用云 embedding API，合规代价相差一个量级**——前者正文不出客户环境，后者正文出客户环境。ADR-0014 的先例属前者（密文、自建 topic、默认关），因此**不能把 ADR-0014 当作「正文出网关已获授权」的通例**：0014 的授权是**针对密文信封这一具体形态**给出的三个放宽点，不自动延伸到明文的第三方 embedding 调用。

## 4. Q3 替代方案：不出网关能拿到多少收益

**已经拿到了相当一部分。** 语义缓存通常被用来解决两个问题，而第一个问题本系统已用不出网关的方式解决了：

| 问题 | 行业解法 | 本系统现状 |
|---|---|---|
| 多轮对话中前文变化导致精确键失配 | 语义键（取末条 user 消息） | **已实现**：`CacheKeyFactory.semanticScope`（`:102-142`），且是当前生效路径（`compute()` 在 `:69` 调用它，仅当为空才回落全 body） |
| 措辞不同但意图相同（「改 A 文件的 bug」vs「修复 A 文件里的缺陷」） | embedding + ANN 近似召回 + 阈值 | 未实现——**且这正是唯一需要正文出网关的部分** |

可评估的不出网关路径：

- **路径 B（网关内向量化）**：在网关进程内跑本地小模型生成向量 + 本进程内/PostgreSQL 扩展内建索引（如 pgvector），正文不出进程、不出网。技术上可行，但代价：新增运行时依赖与模型分发物（需逐个核查许可证与体积）、CPU 预算与热路径零阻塞约束的冲突（须另有界线程池）、**以及对 token 级差异（`A 文件` vs `B 文件`）的区分力弱于云端大模型 → 误命中风险高于 §5 的行业阈值实况**。
- **路径 影子测量（本 ADR 建议的零风险前置步）**：只对既有 `semanticScope` 做归一化指纹统计（不缓存、不外发、不落正文），得到「同 scope 重复度」的真实分布，用来估计上界。不改缓存行为，合规面为零——因为记录的就是既有 `cache_key`（已入 `usage_event` / `cache_hit_event`，见 `V6__usage_events.sql:38,61-73`），不新增任何正文留存。**采样口径注意（否则会得到一个有偏甚至为空的样本）**：`semanticScope` 只在 `CacheEligibility` 通过后才被调用——`ProxyController.java:303-306` 先算 `cacheable`，再 `cacheable ? cacheKeyFactory.compute(...) : null`，而 `miqrokey.cache.enabled` 默认 `false`（`application.yml:88-96`）。因此纯影子模式必须在**资格判定之前**独立计算，并明确它是「全部流量」还是「仅 `cachePolicy=ENABLED` 的 Key」口径；两种口径给出的上界含义不同，需在报告里写明。

## 5. Q4 行业事实核对

本次直接核对官方文档（2026-09-18）：

- **Azure API Management `llm-semantic-cache-lookup`**（learn.microsoft.com，页内 `ms.date` 2026-08-18）：语义缓存是**显式 policy**，须与 `llm-semantic-cache-store` 配对；参数 `score-threshold`（0.0–1.0，值越小要求相似度越高，示例 0.05）、`embeddings-backend-id`、`embeddings-backend-auth`（必须 `system-assigned`）、`ignore-system-messages`、`max-message-count`；`<vary-by>` 元素支持按 `context.Subscription.Id` 做跨用户分区。**官方 Usage notes 明示「Score threshold above 0.2 may lead to cache mismatch」**；官方免责声明要求评估者自行承担「返回不正确、过时或对本请求不安全内容」的风险；文档要求在 lookup 之后配限流以免绕过后端限流。**Supported model APIs** 列明支持 **OpenAI Chat Completions / Responses、Anthropic Messages（v2 tier）、Google Vertex AI**——即**多协议**，而非仅 Chat Completions。
- **Azure enable 步骤页**（azure-openai-enable-semantic-caching，`ms.date` 2026-08-24）：前置 = 独立 Embeddings API 部署 + **Azure Managed Redis 且须启用 RediSearch 模块**（该模块只能在新建 cache 时开启）+ managed identity；步骤「Create a backend for embeddings API」要求填 **Runtime URL**，形如 `https://<instance>.openai.azure.com/openai/deployments/<deployment>/embeddings` → **正文被送到另一个服务**，与 §3 的判断一致。语义缓存按 API 施加 policy，非默认行为。（坐标说明：该 URL 出自**本 enable 页**；lookup 页只给 `embeddings-backend-id` 参数，不给 URL 形态。）
- **流式兼容性（来源强弱必须标注）**：issue 转述「Azure 文档明确与流式响应不兼容」。本次实拉的三个现行 Learn 页面（lookup / store / enable）正文中**均未见**该限制段落；命中该说法的是 Microsoft 发布的 `azure-skills` 仓库 AI Gateway policies 参考文档（**次要来源**），原文为「Semantic caching is NOT compatible with streaming responses」。**请 owner 以原始出处复核**——该事实直接决定本议题的收益上限（见 §7）。
- **阿里云 AI 网关「缓存」**（`https://help.aliyun.com/zh/api-gateway/ai-gateway/user-guide/ai-cache-1`，2026-09-18 拉取）：页面把两条路径**分开配置**——**精确缓存**用 Redis（服务地址/端口/访问方式/账号密码/缓存时长默认 1800s）；**语义缓存**用「缓存键策略 + 文本向量化（AI 服务/模型/超时默认 5000ms）+ 向量数据库（DashVector/Collection/API Key/相似度阈值/超时默认 3000ms）+ 距离度量（文档指导选 Cosine）」。即**语义缓存路径的必需件是向量化服务 + 向量库，而不是 Redis**。控制台把缓存键策略记作**「缓存键生成策略」**（可选控制台默认的「只取最新提问」或「整合历史提问」）；同一概念在 API 参考页（`api-apig-2024-03-27-aicacheconfig`，2026-09-18 拉取）暴露为 `cacheKeyStrategy`（描述仅「缓存键生成策略」、示例值为 `-`）与 `cacheMode`（示例 `exact`），并把 Redis 描述为「Redis 配置（精确缓存计数存储）」。**两份阿里云页面均未列出策略枚举值**——`lastQuestion/allQuestions/disabled` 只在 Higress OSS README 逐字核到（Higress 官网插件页 `https://higress.cn/en/docs/latest/user/plugins/ai/api-provider/ai-cache/` 与阿里云 MSE 文档页 `https://help.aliyun.com/zh/mse/user-guide/ai-cache`，两者 2026-09-18 拉取，均无 `cacheKeyStrategy`）；GJSON 取键表达式 `messages.@reverse.0.content` 则另见前句两页，后者原文释义是「把 messages 数组反转后取第一项的 content」（见下条）。**相似度阈值建议 0.8–0.9，文档明示不建议低于 0.8（否则可能把无关查询误判为命中）**；启用需在控制台**手动打开缓存开关**。文档中的 `text-embedding-v4`（1024 维）与示例阈值 0.85 是**示例配置**，不是该产品的固定属性。
- **Higress OSS `ai-cache` 插件（自建形态）**（`https://github.com/alibaba/higress/blob/main/plugins/wasm-go/extensions/ai-cache/README.md`，2026-09-18 拉取）：插件要求「向量数据库(vector) 和 缓存数据库(cache) **不能同时为空**」——至少配置其一；语义模式需要 vector（DashVector 只是可选 provider 之一）；`cacheKeyStrategy ∈ {lastQuestion(默认), allQuestions, disabled}`（`disabled` 的原文释义是**禁用缓存**）；缓存键默认用 GJSON PATH 从请求体提取（`cacheKeyFrom` 默认 `messages.@reverse.0.content`，即反转 messages 数组后取第一项的 content）；配置了 vector provider 后语义缓存自动开启（未配置则不提供缓存服务）；README 明示其缓存**支持流式与非流式**响应；命中后可用 `x-higress-skip-ai-cache` 跳过（**opt-out 形态**：配好后默认参与缓存、显式跳过，与本系统的双重 opt-in 方向相反）。**以上两处具名来源均未出现「仅支持 Chat Completions」或「其他协议绕过」的表述**——本 ADR 不作该项断言。

两家共同点：**都不是默认行为**（Azure 需显式 policy，阿里云需手动打开缓存开关，Higress OSS 需装插件且至少配置 vector 或 cache 之一）；**都要引入本系统按 ADR-0005 刻意不引入的额外存储**（Azure 必配 Azure Managed Redis + RediSearch 模块；阿里云语义路径必配向量库 DashVector 或等价物）；**都需要一个独立的 embedding 服务**（Azure 是独立的 embeddings 部署，阿里云语义路径必配向量化服务，正文都会到达网关之外的组件）。**结论：行业事实支持「按需插件、默认关」，但不支持「零成本接入」。**

本系统的主要流量形态是流式：`CacheKeyFactory` 特地为 `stream` 维度分键（`docs/decisions/0009` 记录 `#444`）；而 `CLAUDE.md:31` 的「最多 50 条并发流」是**并发上限**，不能当作流量形态的证据。若流式不兼容属实，语义缓存将在主场景上失效，仅对非流式请求有价值——这会把 Q1 的收益预期压到很低。**反向证据（削弱该说法，非推翻）**：Higress OSS README 明示其缓存支持流式与非流式响应；Azure 三个官方页面则对兼容性完全未置可否。即「语义缓存与流式天然不兼容」**不是行业普遍事实**，需 owner 以原始出处确认后，才能用作收益上限的依据。

## 6. Q5 误命中风险与兜底

- 风险性质：「相似 ≠ 相同」。#718 的例子（「改 A 文件的 bug」vs「改 B 文件的 bug」）在向量空间上距离极近，而正确响应完全不同——缓存的错误答案是**看起来合理但指向错误对象**的答案，比明确的报错更有害，且用户无法从响应本身察觉命中了缓存。
- 行业阈值实况印证该风险：Azure 官方说阈值 >0.2 就可能误命中（建议从 0.05 起调）；阿里官方说 <0.8 就可能假阳性——两家在相反方向上都承认阈值调不好就会错配。
- 本系统已有的兜底（若未来启用，均需保留）：双重 opt-in（`CacheEligibility.java:29-33`）；工具调用永不缓存；TTL 300s 量级（`application.yml:88-96`）；响应头 `X-MiQroKey-Cache` 标注命中；键含 tenant/project/keyId/product/model，天然按用户隔离；命中计数与节省入 `cache_hit_event` + `savedByGatewayCache` 可观测。
- **既有键的「故意忽略中间轮次」是一个已存在的误命中面（登记，非新增风险）**：`CacheKeyFactory` 类注释原文「Earlier conversation turns do not change the key, so a repeated question inside different histories still hits the cache」（`:31-32`）——设计如此（对齐末条 user 消息策略）。它不等于语义近似召回，但说明本系统**已经**在用「相似即同一」的判据换取命中率；若未来引入向量近似，误命中面是在这个既有面上继续放大，而不是从零开始。评估时应把这一条算进基线，避免把「当前命中都是安全的」当作前提。
- **行业兜底参数（可对标的现成旋钮）**：Azure 官方给了 `ignore-system-messages`（默认 `false`，官方**建议置 `true`**）、`max-message-count`、`<vary-by>`（按 `context.Subscription.Id` 做跨用户分区）；阿里云控制台给了「向量相似度阈值」下限建议（不建议低于 0.8）；Higress OSS README 给了 `cacheKeyStrategy=disabled`（原文释义是**禁用缓存**，并非「保留精确、只关语义」——只做精确匹配（README 用词「字符串匹配」）对应的是「仅配 cache 服务、不配 vector」的组合，见该 README 的 `enableSemanticCache` 说明）与逐请求跳过 `x-higress-skip-ai-cache: on`。**若启用，这些维度在我们的方案里都要有对应物**，否则等于把行业已经踩过的坑重新踩一遍。
- **若语义缓存启用，必须追加的兜底（当前不存在）**：命中时在响应头区分「精确命中」与「近似命中」（现有 `L1`/`L2` 值不足以表达，且语义命中不得复用 `L2` 值以免与 PostgreSQL 命中混淆）；近似命中的审计与开关粒度；以及**当相似度落在阈值附近时的处置策略**（放行还是回退上游）——这些都要在启用前定，不能留到实现时。

## 7. Q6 结论与选项

本 ADR **不替 owner 拍板**，列出四条路径与推荐。四条路径的共同前提：无论选哪条，**当前行为不变**（语义缓存继续不启用），直到 owner 明确回复。

| 选项 | 改动面 | 风险 / 代价 | 什么条件下应该做 |
|---|---|---|---|
| **A. 维持不启用（推荐）** | 零代码改动；本 ADR 落 Accepted（2026-09-19，§11）；运行时行为不变 | 无新增风险；收益机会成本不可估（§2） | 默认选项。适用于：收益无法测量、主场景为流式、以及不愿为性能优化动合规承诺的情形 |
| **B. 启用但向量化在网关内**（正文不出进程） | 新依赖（本地 embedding 模型 + 索引）、新线程池与内存预算、`CacheKeyFactory`/`CacheEligibility` 扩展、许可证与镜像体积核查 | 不破正文红线，但破「热路径零阻塞」的整洁性；中小模型对 token 级差异区分力弱 → **误命中风险高于云端方案**（§6）；模型分发与版本管理成本 | 仅当 §2 的测量显示上界显著（例如同 scope 重复度仍高）、且 owner 接受本地模型质量与资源开销时，才值得做可行性验证 |
| **B2. 启用但在客户私有环境向量化**（网关 → 客户内网 embedding 服务；2026-09-19 拍板 D3 定为优先路径） | 网关侧仅新增 HTTP 客户端与索引存储；embedding 服务由客户自建自管（可用私有化部署的强模型） | 正文**出网关进程、不出客户环境**；对外承诺不改写（须在部署文档声明该内网服务）；网关资源开销接近零 | 与 B 同条件（P1 显示高置信候选密度显著），且客户具备或愿意提供内网 embedding 服务 |
| **C. 启用且允许正文出网关** | 新 embedding 服务 + 向量库 + 新 ADR + 承诺文本修订（`CLAUDE.md:39`、`docs/security.md:78-84`、登录页文案）+ 新增审计面 | **放宽一条合规红线**（同 ADR-0014 性质，但更重：ADR-0014 是密文信封且已获三个放宽点授权，本项是明文送 embedding）；若用云服务则「数据不出环境」承诺不成立；§3 补偿措施必须全部落地 | 仅当 owner 明确愿意改写对外承诺，且**自建内网 embedding**（而非云 API）时。**不建议**以云 embedding 形态做 |
| **D. 折中（按范围开放）** | 依赖既有 `cachePolicy` 维度或按端点/协议开关 | 需注意：本产品为**单客户私有化部署**（`CLAUDE.md:31`），「仅对特定租户开放」在此形态下等于整个客户开放；真正可用的粒度是 **per-Key**（已有）或 **per-端点/协议**——本系统只有三条代理路由，天然可作粒度：`/v1/messages`、`/v1/responses`、`/v1/chat/completions`（`ProxyController.java:194,199,204`；白名单同见 `:107`） | 仅当 B 或 C 已确定要做、需要一个更小的首版范围时，作为其范围裁剪方式，不单独构成一条路径 |

**推荐：A（维持不启用）+ 补一个零合规面的测量前置。** 理由：本系统已经把语义缓存最常被引用的收益来源（多轮对话的键稳定性）用 `semanticScope` 在网关内拿到（§4）；剩余增量收益（措辞等价的近似召回）正是唯一需要正文出网关的部分，而它在流式主场景上可能整体失效（§5），且当前**无法测量**（§2）。在这种「代价明确、收益未知且可能为零」的组合下，放宽红线的证据不足。

**重新评估触发条件（若 owner 采纳 A，建议写死以下三条之一触发再议）**：

1. **测量触发**：影子测量或 `cache_hit_event` 分析显示，在 `cachePolicy=ENABLED` 的 Key 上，精确键未命中但同 scope 重复度仍显著（阈值由 owner 定），即存在明确的语义增量空间；
2. **事实触发**：确认语义缓存与流式响应兼容（推翻或修正 §5 的次要来源），且本系统非流式流量占比达到值得优化的比例；
3. **需求触发**：出现明确的成本压力或客户要求，且 owner 愿意为此启动一次与 ADR-0014 同级别的红线放宽审议。

任一触发时，**重新开 issue 并按本 ADR 的 §3/§5/§6 重新取证**，不直接沿用本文件的结论。

## 8. 与既有 ADR 的关系（修订边界）

| ADR | 是否修订 | 边界 |
|---|---|---|
| [ADR-0009](0009-enable-response-cache.md) | **不修订**（本 ADR 是其履约件） | 0009:38 原文「语义缓存（L2）维持禁用；未来若启用需新 ADR（向量库依赖）」——**本文件即那个「新 ADR」的评估件**。0009 的 L1 精确缓存结论、双重 opt-in、工具不缓存、租户隔离全部不变。若 owner 选 A，本条无需任何改动；若选 B/C/D，只需新增决策节，**不回溯改写 0009 的结论**。 |
| [ADR-0005](0005-no-redis-v1.md) | **待 owner 判定一处解释边界** | 0005 原文 = 「第一版仅使用 PostgreSQL，不部署 Redis」（`:8`）+ 「未来…可通过 SPI 引入 Redis」（`:18`）。注意：**「零中间件」不是 ADR-0005 的原文措辞**（该措辞出自 `docs/ai-gateway-comparison.md:23,50`、`docs/feature-expansion-candidates.md:6,25`），引用时不要归给 0005。0009:13 以「依赖向量库，违反 ADR-0005 约束」否决语义缓存——但若走选项 B 且索引用 **pgvector（PostgreSQL 扩展，非新中间件）**，该否决理由是否仍成立，属解释边界，**需 owner 判定**（见 §10-2）。本 ADR 不改 0005。 |
| [ADR-0002](0002-transparent-proxy.md) | 不触及 | 语义缓存不改写、不补写请求 JSON；仅换用不同的缓存键。透明性边界不变。 |
| [ADR-0003](0003-no-response-cache.md) | 不修订 | 已由 ADR-0009 取代，本议题不影响其历史结论。 |
| [ADR-0014](0014-content-retention-and-kafka-events.md) | 不修订 | 0014 是**密文信封、默认关、三个放宽点已授权**的先例，**不构成**「正文出网关已被普遍授权」。本 ADR 引用它只作为「同类审议的定标参照」与 §3「补偿措施可复用的机制来源」。 |
| [ADR-0020](0020-quota-soft-landing.md) | 不修订 | 仅沿用其「Proposed → owner 拍板 → 改写状态」的流程形态。 |
| `CLAUDE.md` §2 | 若选 C 才需修订 | 「不保存 prompt、代码、工具正文和模型回答」（`:39`）在 C 路径下需 owner 同意后修订；A/B 路径不需要。 |

## 9. 后果（若 owner 转为 Accepted 并实现）

- A：仅本文件与 `docs/decisions/README.md` 索引行、`docs/progress.md` 记录；零运行时影响。
- B：新增本地模型依赖与索引存储；`CacheKeyFactory` 增加相似度维度；需评估网关 P95 首包与 CPU 预算；需核查模型与运行时许可证（生产依赖只允许宽松许可证）。
- C：新增 embedding 服务与向量库部署单元；承诺文本与登录页文案需修订；需新增命中来源审计与告警；ADR-0014 的补偿机制可复用。
- D：以上之一的范围子集。

## 10. 未决问题（留给 owner 拍板）

1. **收益口径**：是否接受「收益当前不可测」这一事实，并在无测量的情况下拍板？若不接受，是否批准先做 §4 的**影子测量**（零合规面、不改缓存行为）？
2. **ADR-0005 的解释边界**：ADR-0009:13 以「违反 ADR-0005」否决向量库；若索引改用 PostgreSQL 扩展（pgvector）而非新中间件，该否决理由是否仍成立？（此问题的答案会影响 B 路径是否需要 ADR-0005 的修订。）
3. **测量路径**：允许用哪种数据估收益？候选：(a) 仅现有 `cache_hit_event` 聚合（零新增面）；(b) 新增语义 scope 影子计数；(c) 若 ADR-0014 留痕在某租户已启用，经 owner 授权对该租户密文样本做受权离线评估（涉正文，须审计）。
4. **对外口径的事实补充**：§1.4 所述「接口预留、默认不开；要开需拍板」是否已对客户/领导承诺？原话或材料是什么？决定了本次结论能否直接对外复用。
5. **流式兼容性复核**：§5 的流式不兼容说法来自次要来源，请 owner 确认原始出处；若确认不兼容，A 的推荐强度进一步提高。
6. **是否与 #706（限流评估）合并一次决策会**：#718 备注建议合并；两条都是「反转既有裁决」类提案。
7. **范围粒度**：若将来选 D，「按租户」在本产品单客户私有化形态下无实际粒度（§7），是否改为 per-Key 或 per-端点/协议？
8. **命名不一致（非本议题，仅登记）**：ADR-0009:13 把 PostgreSQL `cache_entry` 记作「L1」、而代码记为 L2；`docs/decisions/0009` 写的配置键 `miqrokey.cache.l1-ttl` 与实际键 `miqrokey.cache.l1.ttl` 不一致（`application.yml:88-96`）；`docs/decisions/README.md` 的 ADR-0014 索引行仍标「（草案）」而文件内为 Accepted。**本 ADR 不改写这些既有文件**，登记待一次独立的文档订正批处理。

## 11. 所有者拍板记录（2026-09-19，状态由 Proposed 转为 Accepted）

- **答复形式**：所有者对评估线的推荐整体回复「语义缓存按你推荐的来」。下列 D1–D5 即评估线推荐（本文件 §7 结论 + 经外部技术评审后修订的 v2 版 §5 优先级 / §6 口径 / §7 建议）的原样落地，逐项可核。
- **前置事实**：评估线在拍板前经一轮外部技术评审并做 v2 修订，评审引出的两处真实缺口已修复并合入 develop（生成参数指纹进键、Anthropic 顶层 `system`／Responses `instructions` 进 scope，issue #859 / PR #860）；评审中「`stream` 未进键」的主张经代码复核不成立（评审基线 `main` 落后 `develop` 400+ 提交，见 §11.4）。
- **本记录不授权任何运行时行为变更**：语义缓存继续不启用（承 ADR-0009:38）。

| # | 决策问题 | 拍板结论 | 落地面 |
|---|---|---|---|
| D1 | 维持不启用，还是批准测量前置？ | **维持默认不启用**；同时**批准 P0**（provider-native prompt caching 透传验证）与 **P1**（两阶段影子测量：离线 probe 集 + 生产抽样）。两者均为零合规面——不出网关、不落正文、不改缓存行为 | P0 与 P1 各自立项为独立 issue/PR（见 §11.3） |
| D2 | probe 集评测档位 | **本地 base 级 + 现代本地模型 + 云 `text-embedding-v4`** 三档，同一 probe 集对比；small 档（`bge-small-zh-v1.5` 类）只作下限参照，不作生产候选 | P1 阶段一的评测矩阵 |
| D3 | P1 显示高置信候选密度显著时，走 B 还是 B2？ | **B2 优先**（客户私有环境 embedding 服务：正文不出客户环境、几乎不占网关资源）；**B 作为无内网条件下的退化档**（网关内本地模型）。进入 P2 前必须先完成：ADR 修订（含 B2 形态与部署声明）、误命中止蚀设计、模型与许可证核查 | P2 的路径选择；不改变 D1 的默认不启用 |
| D4 | 三层隐私口径（§6） | **采纳**：①默认不持久化正文；②启用派生数据能力（指纹、向量等）时，其处理位置、是否持久化、保留期限与隔离范围在对应功能的安全说明中单独披露；③默认不将正文发往任何第三方 | `docs/security.md` 落地（影子测量按第 2 层登记）；登录页一行文案在 P2 实际引入向量化能力时随设计一并修订——P1 形态（进程内、不落盘、重启即清）下现有承诺仍成立 |
| D5 | 是否与 #706 合并决策会？ | **合并**：本议题与 #706（速率限流评估）合并一次决策；两条同属「需拍板」类 | 流程约定，无代码面 |

### 11.1 §10 未决项逐条落地

| §10 项 | 落地 |
|---|---|
| 1 收益口径 | **已决**：接受「收益当前不可测」的现状，批准 P1 影子测量作为测量手段（D1）；测量结论出来前不进入 B/B2 |
| 2 ADR-0005 解释边界 | **已决（评估线结论）**：pgvector 属 PostgreSQL 扩展，本身不构成 ADR-0005 的「新中间件」违约；但 B 路径的**本地模型运行时**是新增生产依赖，仍须在 P2 修订时按 ADR-0005 口径补一次解释。B2 不在网关内新增依赖，但需在部署文档声明客户内网服务 |
| 3 测量路径 | **已决**：走「离线 probe 集 + 生产抽样影子计数」；§10-3(c)（对已启用留痕的密文样本做离线评估）**不启用**——不涉正文 |
| 4 对外口径事实补充 | **未决**：所有者未补原话；在默认不开的前提下，「接口预留、默认不开；要开需拍板」的对外表述不变，本拍板不改变其强度 |
| 5 流式兼容性复核 | **保留待复核**：P1 不依赖该结论（影子测量只做相似度统计，不做命中回放）；若进入 P2，须先复核原始出处 |
| 6 与 #706 合并决策会 | **已决**（D5） |
| 7 范围粒度 | **未决**：随 P2 一并定（候选：per-Key / per-端点） |
| 8 命名不一致（登记项） | **仍不改写既有文件**，待独立文档订正批处理 |

### 11.2 重新评估触发条件（承接 §7）

§7 三条触发条件不变，其中**第 1 条（测量触发）自本记录起即为已激活状态**——P1 影子测量即该触发的执行体；第 2、3 条维持原状。P1 判读口径：若高置信候选密度不显著（判据见 P1 issue 的验收标准），维持 A 并关闭本议题；若显著，按 D3 启动 P2。**阶段一（离线 probe 集）结果见 §11.5。**

### 11.3 效力

- 自本记录起本文件状态为 **Accepted**，效力范围：D1–D5 与 §11.1 的落地结论，以及随之产生的文档改动（`docs/security.md` 三层口径）与工具资产（probe 集与评测跑具、影子测量的回归固化）。
- **不授权**启用语义缓存（B/B2）。启用须按 P2 流程另行修订本文件并经所有者批准（同 ADR-0019→0020 的 Proposed→Accepted 先例）。

### 11.4 一条协作教训（外部评审的基线）

本轮外部评审按仓库默认分支 `main` 取代码事实，而该分支是发布快照、评审时落后 `develop` 400+ 提交，导致其头号技术主张（`stream` 未进缓存键）对真正的主干不成立——该维度自 #444 起就在键里并有专测。**后续任何外部评审一律指明 `develop`**，并对其代码类主张先做主干复核再采纳。

### 11.5 P1 阶段一（离线 probe 集）结果与判读（2026-09-19）

§11.2 的「测量触发」已执行完毕，完整报告见 [`docs/semantic-cache-probe-phase1-2026-09-19.md`](../semantic-cache-probe-phase1-2026-09-19.md)（原始产物 `docs/semantic-cache-probe/runs-2026-09-19/`，跑具 `scripts/semantic-probe/`）。

结果要点（148 对全合成 probe 集，三档本地模型）：

- **零/近零误命中下召回全为 0**：误命中率 ≤0.5%/1%/2%/5% 的任一工作点上，三档模型的正例召回都是 0——全表最高分是一对 hard negative（1.000）。
- **整体 AUC 0.34–0.48（低于随机）**：余弦排序主要跟随字面重叠（负例字面相似度中位 0.778，正例 0.488）。
- **按住字面重叠后（区间内 AUC）近重复区仅 0.60–0.77**：即缓存真正会命中的区域有 23–40% 的对照对被排错序；最小编辑对照 7 组中 4 组被三档模型全部排错序（如「超时调大/调小」得分高于「改成/调成」同义替换）。
- 云档 `text-embedding-v4` **未跑**（无密钥；跑具已支持，一条命令可补）。
- **用法敏感性（对照实验，方法论上必须记住）**：按模型卡给 `Qwen3-Embedding-0.6B` 加**指令前缀**后，区间内 AUC 由 0.60–0.75 升至 **0.80–0.87**、最小编辑对照的排错由 4/7 降到 **1/7**——但误命中 ≤1% 时召回仍为 0（≤5% 时 6.2%）。即：**「默认用法」不能当作模型能力上界**，评估任何更强档位都必须用其文档用法。

**判读：不支持按当前本地档位进入 P2，维持 A（语义缓存继续不启用）。** 重新开题的最小条件（须在同一 probe 集上验证）：

1. 云 `text-embedding-v4` 或 B2 客户内网强模型**按其文档用法**跑出**非零的「零误命中召回」**；
2. probe 集扩到规格目标 300–500 对后上述结论仍成立（或反之）；
3. P1 第二阶段（生产抽样影子测量）显示同 scope 重复度高到值得容忍误命中风险。

第 1、2 条建议作为一组一起做（近重复区内样本量只有 40 对，足以定方向、不足以定阈值）。任一触发时按 §11 D3 走 **B2 优先**，并先完成误命中止蚀设计。
