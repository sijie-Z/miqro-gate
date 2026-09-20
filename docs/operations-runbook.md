# 运维 Runbook

适用于单客户 Docker Compose 私有化部署。操作前确认目标环境、备份状态和审计身份；命令以实际 Compose 文件和 Secret 路径为准，不在本文写入真实值。

## 1. 日常检查

- Control Plane 和 Gateway readiness/health 正常。
- PostgreSQL 可连接，Flyway 版本一致，无长事务和容量告警。
- usage 队列深度、解析失败率、请求错误率、SSE 活跃数在正常范围。
- Provider credential/Plan 最近同步时间，余额/周期告警，无 `DEGRADED` 产品。
- Webhook 投递积压、导出任务和备份最近成功时间。
- 磁盘空间覆盖 PostgreSQL、导出、临时文件和备份增长。

## 2. 启动与停止

启动前验证 Secret 文件权限、备份目录、配置和目录签名，运行 `docker compose config`。先启动 PostgreSQL并等待健康，再启动 Control Plane/Gateway/Portal。发布后依次检查 migration、readiness、登录、`/v1/models` 和一个 Mock/低成本烟雾请求。

停止时先从入口摘除新流量，等待活动流在维护窗口内结束，再停止应用，最后停止数据库。除紧急事件外不要直接中断所有流。


### 发布后闭环冒烟（注册 → 加入 → Key → 真实推理 → 用量核对）

本地开发环境可运行 `miqro-local/demo-registration-loop.sh`（不入库）自动执行六步；私有化环境按同步骤人工核对（替换账号/项目名）：

1. 管理员登录 → 定位目标用户（无则创建）。
2. `GET /admin/users/{id}/project-memberships` 为空 → 管理员把用户加入 ACTIVE 项目 → 列表出现该项目（快捷加入闭环）。
3. 用户登录 → `me/grants` 含该项目授权与模型集。
4. 用授权模型集创建 Virtual Key（`allowedModels` 必须取自 grant 模型，否则网关 403 `model_not_allowed`）。
5. 经网关发一次最小推理（建议 max_tokens≤8）：期望 200 且返回 model 字段；400 多为请求体编码/JSON 问题，403 多为模型未授权。
6. 数秒后 `me/usage/summary` 应有 upstream=1 与 tokens>0；usage_event 表新增行（`is_complete=true`）。若推理 200 但 usage 持续为空：查网关日志 `Usage batch write failed`（旧 jar 部署常见；以最新代码重建）。

## 3. 新增/轮换真实凭证

1. 管理员创建或选择供应商产品/订阅。
2. 输入新 Key，系统加密保存为非激活版本并执行官方最小验证。
3. 核对协议、模型和 Plan/团队形态；验证失败不得激活。
4. 激活新版本。新请求立即使用新版本，既有流保持旧版本到结束。
5. 观察错误率和 usage；必要时在旧版本尚可用时回退。
6. 在供应商侧撤销旧 Key，并在系统中关闭旧版本；全程写审计。

若供应商强制立即吊销，跳过宽限但通知受影响用户。不得通过群聊发送真实 Key。

## 3b. 管理密钥运维（scope / 轮换 / 到期）

开放管理面机器密钥（`mqk_admin_…`，`/api/v1/admin-api/**`）生命周期在
[api-contract §9](api-contract.md) 与示例集 `scripts/open-api-examples/`：

1. **最小权限**：发行后立即配 scope（`PATCH /api/v1/admin/api-keys/{id}/scope`，
   能力组 `usage:read / alerts:write / exports:create / vkeys:delegate`；不配=全量）。
   越权调用返回 `403 ADMIN_API_SCOPE_DENIED` 并进审计——先配好再交付给自动化。
2. **例行轮换**：发新钥（新名称）→ 迁移调用方 → 吊销旧钥；**scope 不隐式继承**——
   重建时显式重配（或保留旧密钥视图里的 capabilities 照抄）。
3. **到期提醒**：需告警时建一条 `ADMIN_API_KEY_EXPIRING` 规则（类型选择器
   「管理密钥 · 即将到期」），此后 ≤7 天到期密钥每天至多一条事件（默认关：
   不建规则即无通知）。到期当天密钥静默失效——务必在到期前完成轮换。

## 3c. 消费者密钥运维（scope / 到期 / 轮换）

平台等外部系统的 `mqk_api_…` 凭据（管理 API `/api/v1/admin/api-consumers`，#316/#322）：

1. **最小权限**：发行后按用途配 scope（`PATCH …/{id}/scope`，`billing:read`/`mcp:call`；不配=全量）。
   只调 MCP 的 Agent 务必去掉 `billing:read`——越权调用计费面返回 `403 CONSUMER_SCOPE_DENIED`。
2. **到期**：创建时给 `expiresAt`（90 天等）成为常态；**到期即静默失效**（计费与网关 MCP 双面 401，
   与未知 Key 同形）——列表仍可见到期行。到期前完成轮换，勿依赖当天操作。
3. **轮换 = 重建**：发新消费者（新名）→ 迁移调用方 → 在旧消费者上 disable。**scope/到期不继承**，
   重建时显式重配。`CONSUMER_CREATE/DISABLE/SCOPE_UPDATE` 均进审计。
4. **到期提醒**：需要时建 `CONSUMER_KEY_EXPIRING` 告警规则（默认关）：≤7 天到期者每（消费者×天）
   至多一条事件。

## 3d. MCP 上游后端密钥运维（backend-auth）

需要 Key 的内网 MCP 服务（`/api/v1/admin/mcp-services/{id}/backend-auth`，#320）：

1. **设定/轮换**：`PUT` body `{"mode":"API_KEY","secret":"…"}`——**密钥写后不可读**（任何读面/审计不含），
   轮换即重设；上游轮换窗口内先在 MCP 服务侧保留旧 Key 双活，再重设网关侧，最后撤销旧 Key。
2. **清除**：`{"mode":"VISITOR"}` 回到不注入模式（上游必须已放开鉴权，否则调用将 401）。
3. **排障**：消费者调用返回 `502 backend_auth_unavailable` = 网关未能解密/密钥缺失（fail-closed，
   上游零请求）——检查 crypto 配置与 backend-auth 是否已设；正常注入时上游看到固定
   `Authorization: Bearer <secret>`，且**消费者凭据从不下传**。

## 3e. 服务注册表健康运维

内部服务目录（`/api/v1/admin/services`，#326）的运行时状态：

1. **上下线**：`disable`/`enable` 对称为一等操作（审计 `SERVICE_DISABLE/ENABLE`）；禁用后不再探测，
   健康状态冻结显示原值。
2. **健康探测**：仅 ACTIVE 服务按各自间隔探测 `baseUrl + checkPath`（GET，2xx 计健康；
   周期 `MIQROKEY_SERVICES_HEALTH_CYCLE_MS` 默认 15s，单服务间隔/超时/阈值可配）；连续失败达
   `failThreshold` → `UNHEALTHY`，连续成功达 `recoverThreshold` → `HEALTHY`。
3. **语义**：健康状态是**运营信号**，当前不驱动任何流量行为（数据面接线 F29 待形态确认）——
   `UNHEALTHY` 时人工核实服务与网络，处理后在页面「健康检查」对话框调整阈值或路径。

## 4. 吊销 Virtual Key

确认 Key 掩码、所属用户/项目/产品和最近使用，执行立即吊销。新请求立刻拒绝；是否取消既有流按安全事件等级决定并记录。怀疑泄漏时同时撤销相关会话、检查 IP/模型/用量异常，并建议用户轮换 CC Switch 配置。

## 5. 供应商故障

系统不自动跨供应商切换：

1. 用官方状态页/RSS、网络检查和供应商 request ID 确认范围。
2. 把产品标记为 `DEGRADED`，触发 Webhook，向内部用户群发通知。
3. 保留上游状态/错误体语义，不伪装为其他模型。
4. 用户自行在 CC Switch 选择另一个已配置供应商/Key。
5. 恢复后执行烟雾测试，再解除状态并发布恢复消息。

## 6. 额度或周期告警

确认数据来源和最后同步时间。官方数据可用时与供应商控制台/账单核对；只有本地估算时明确告知误差。管理员负责充值、续期或增加团队席位。系统首版不因为软预算自动拒绝请求，但物理上游额度耗尽仍会产生上游错误。

## 7. Usage 异常

### 解析失败

用户响应不受影响。按 adapter/product/protocol 聚合失败，使用脱敏 fixture 复现；修复解析器后执行幂等补偿。未经官方数据支持不得把空值改成本地“官方 token”。

### 队列高水位或满

检查数据库延迟、锁、连接池和磁盘。先恢复写入能力，不无限扩大内存。队列满导致的请求失败/背压必须告警并统计；根据本地 request ID 和供应商明细补偿，不允许静默丢记录。

**告警规则**（F07/#245）：在默认（seed）租户下建 `USAGE_QUEUE_SATURATION` 规则，阈值 = 近 1 小时可接受的丢弃条数——填 `1` 表示「丢 1 条即告警」，不要按比例理解。网关每个 flush 周期（`MIQROKEY_GATEWAY_QUEUE_FLUSH_INTERVAL`，默认 1s）把新增丢弃数写一行 `gateway_queue_signal`；零丢弃的网关不写任何行，规则安静。该事实是**平台级**的（队列全进程唯一），只有 seed 租户的规则能评估到；其他租户的规则读到的是空窗口（`SUM = 0`），在正阈值下恒不触发、也看不到任何别的租户的数字。阈值填 `0` 或负数会让这类规则每个去重窗口都以 `value = 0` 触发一次（服务端不校验，属误配），排查时先看规则的 `threshold`。

**评估盲区（已知并接受，2026-09-20 #245 拍板 Q3A）**：平台事实按滚动 1 小时窗口评估、评估周期 5 分钟；控制面**连续宕机超过 1 小时**时，窗口内事实滚出，**期间的平台告警不会补发**（宕机不足 1 小时则恢复后下个周期照常命中）。怀疑漏告警时先核对控制面在该时段的可用性。

**定位与补偿**：

1. 查事实行：`SELECT occurred_at, dropped, queued_high_water, capacity, saturation_mode FROM gateway_queue_signal WHERE tenant_id = '<seed 租户 id>' AND occurred_at >= now() - interval '1 hour' ORDER BY occurred_at DESC;`——`queued_high_water` 是**进程生命周期内**的单调高水位（只在丢弃发生的那一刻采样，不随窗口重置），因此要按进程启动以来的**最大值**解读：贴近 `capacity` 说明该进程确实打满过队列，远低于容量则优先怀疑写端停顿（数据库锁/慢查询/连接池耗尽）；同一进程后续行的该值不会回落，不要拿相邻两行的差值当「本窗口峰值」。
2. 查事件本身的丢失范围：同一时间窗内 `usage_event` 是否断档；`saturation_mode = WRITE_THROUGH` 的行对应「应急直写也失败」的兜底丢弃，通常同时伴随数据库不可用。
3. 定位写入端：网关日志中 `Queue saturation signal write failed` / `... could not be scheduled` 表示事实行本身也没写进去（delta 会保留并下轮重试，不丢），结合 `UsageEventBus` 指标（`totalDropped`/`queuedCount`/`flushCount`）确认。
4. 补偿：按运行窗口内本地 request ID + 供应商明细补录，不覆盖原始事实（追加 adjustment），不允许静默丢记录。
5. 恢复后确认新事实行归零、规则不再触发；若容量确实不足，先评估 `MIQROKEY_GATEWAY_QUEUE_CAPACITY` 与 flush 周期的关系，再决定是否调整，不要无限扩大内存。

### 本地与官方账单不一致

按供应商 request ID 匹配，再检查时区、计费周期、模型别名、cache token、失败请求收费、价格版本和供应商延迟。导出本地原始 JSONL/CSV 与官方文件，保留 manifest 和 SHA-256。差异修正使用追加 adjustment，不覆盖原始事实。

## 8. Webhook 故障

检查 DNS/TLS、SSRF 拒绝原因、响应码和签名时钟偏差。系统按指数退避重试（2^attempt × 60s），最多 3 次尝试；**耗尽后静默终止**——没有自动 dead-letter、没有门户告警、也没有重放 API。失败证据是 `webhook_delivery_attempts` 行（`GET /admin/webhooks/{id}/deliveries` 可查最近 20 条），需要人工巡检发现。测试投递使用独立事件，不重放真实 Secret 或用量明细。投递语义为 at-least-once：同 `eventId` 可能重复到达，接收方应按 `eventId` 幂等去重（信封带 `eventId` 与 HMAC 签名）。

## 9. 数据库故障

- Gateway 无法验证未缓存路由时 fail closed，不绕过 Virtual Key 鉴权。
- 已发布的不可变路由快照可在短暂数据库故障期间按架构定义继续服务，但新吊销传播存在明确上限并告警。
- 禁止手工修改业务表“修好状态”；使用受审查 SQL/迁移并备份。
- 恢复后检查 usage 队列、任务锁、会话、目录版本和审计连续性。

## 10. 备份与恢复

每日 02:00 备份，保留 7 个每日和 4 个每周副本；备份使用与在线 master key 分离的密钥，并存放于独立介质。备份包含 PostgreSQL、目录/价格版本、必要配置和加密凭证密文，不包含运行日志正文。

恢复演练至少每季度一次：在隔离环境恢复数据库和文件，加载正确 master/backup key，验证 Flyway、管理员登录、凭证可解密、Key 鉴权、usage 总数/校验和和导出。未完成恢复验证的备份不能算成功。

## 11. Master/HMAC Key

正常轮换采用 key version：新写入使用新 key，后台分批重加密（`POST /api/v1/admin/crypto/reencrypt`，幂等，重复调用至 `remaining = 0`；步骤见 configuration-reference §4.3），旧 key 保留到所有密文迁移（`remaining = 0`）和备份策略确认后再退出。HMAC key 轮换会影响 Virtual Key 验证且摘要不可重算：多版本校验在场，退役旧 HMAC 版本前必须先让该版本签发的所有 Virtual Key 完成重发。

Master key 丢失无法从数据库恢复真实凭证；使用受保护备份恢复，或重新录入全部上游 Key。不得设计后门或把明文写日志。HMAC key 丢失时现有 Virtual Key 无法验证，必须批量轮换并通知用户。

## 12. 人工删除用量

原始记录默认永久保留。删除只由管理员发起：先生成受影响范围和行数预览，要求二次确认，创建不可变删除任务和审计事件，按分区/批次执行，最后保存删除范围、行数和校验信息。删除后无法从在线库恢复，只能从仍在保留期的备份恢复；操作前必须明确告知。

## 13. 事件取证

保留 request ID、时间、账号、Virtual Key ID、凭证版本、产品、模型、状态、token/费用、来源 IP（按客户策略）和审计事件。不得为了排错临时开启 prompt、代码或 response body 日志。需要协议样本时使用合成请求或经批准的完全脱敏 capture。


## 14. 常见误配与归因（阿里/腾讯运营口径对照，I16）

### 14.1 Key 形态误配（401 一步定位）

本系统有三类凭据，**端点各认各的**——混用一律 401（不区分原因，防枚举）：

| 凭据 | 形态 | 适用面 | 错误用法示例 |
|---|---|---|---|
| Virtual Key | `mqk_live_…` | 推理数据面 `/v1/**`（`Authorization: Bearer` 或 `x-api-key`） | 拿去调 `/mcpservers/{name}/mcp` → 401（MCP 面只认消费者凭据） |
| 消费者 Key | `mqk_api_…` | 计费 API `/api/v1/billing/**` 与 MCP 数据面（`Authorization: Bearer`；MCP 面 `x-api-key` 也认） | 拿去调 `/v1/messages` → 401 |
| 消费者 JWT | 三段式 RS256 | 同上（`Authorization: Bearer`，非 `mqk_` 前缀） | 平台私钥未配公钥/轮换后旧 token → 401（检查消费者 `jwt_public_key_pem` 指纹） |

定位步骤：① 看 401 的 `WWW-Authenticate`/请求路径确定「哪一面”；② 看凭据前缀确定「哪一类」；③ MCP 面查
`GET /api/v1/admin/mcp-access-logs`（401 未知 Key 不入日志——日志无行 + 上游无请求 = 凭据层失败）；④ 消费者
凭据还要核对 `expires_at`（到期静默 401）与 `capabilities`（缺失 `mcp:call` 是 403 而非 401）。

### 14.2 供应商账单 T+1（对账窗口建议）

绝大多数供应商账单**T+1 才稳定**（当日增量仍在滚动）。对账时：窗口取 **T-1 及更早**、避开当日；F19 上传
窗口 ≤31 天；「本地与官方账单不一致」（§7）里若差异集中在最新一天，先等 T+1 再定论——先用导出的
`reconcile=provider-id`（#330）等级判断该批数据是否具备逐请求对账资格。

### 14.3 429 / 403 归因（谁拒绝的？）

- **429 有两类来源，先分清是哪一类**（#684 / ADR-0020 之后网关会自产 429）：
  - **网关自产**：配额规则的 `action=REJECT` 超限时，网关在准入处（Key 解析后、读 body 前）直接返回
    `429` + 标准错误信封（`type=quota_exceeded`，文案含恢复路径）+ **`Retry-After`**（秒，指向该作用域
    最早可自愈的窗口结束时刻）。**这类请求不触达上游**；判别依据是错误体类型与 `Retry-After`，不是状态码本身。
  - **上游透传**：供应商限流，网关只透传状态与错误体（查上游配额/控制台）；错误体是上游原样，不含网关的
    `quota_exceeded` 信封。
  - 两类共用同一个状态码，**不要再按「429 只可能来自上游」归因**（该表述在 ADR-0020 落地后已过时）；
    `Retry-After` 的量级也完全不同——周期配额指向「窗口结束」（分钟到月），上游限流通常是「若干秒」。
- **403 全部来自本系统的授权层**，按错误码归因（MCP 面）：
  - `consumer_scope_denied` → 消费者 `capabilities` 缺 `mcp:call`（`PATCH /admin/api-consumers/{id}/scope`）；
  - `mcp_access_denied` → 服务级或工具级 ACL 未放行（doc 134890 语义，`GET /admin/mcp-services/{id}/access`）；
  - `mcp_tool_unavailable` → 工具未登记或已禁用（Tools 管理页）；
  - `session_credential_mismatch` → SSE 会话被另一消费者凭据使用（#356）；
  - `SERVICE_STATE_CONFLICT`（409）→ 并发状态变更，刷新重试即可（#361）。
- 归因入口：`mcp_access_log` 的 `status` 列（SERVICE_DENIED/TOOL_DENIED/TOOL_UNAVAILABLE 精确到桶）+
  审计链事件（含操作人）。

## 15. 诊断陷阱速查

> **先问"这个观察到底证明了什么"，再拿它做结论。** 本节条目都是这条原则的实例，两类形状：
> **症状指向错误的「层」**（15.1–15.3）、**证据的「强度」被高估**（15.4–15.6）。
> 工程/CI 侧的同类条目（Spotless JDT 下载抖动、Maven 静默卡死、测试隔离与空基线、读已合并
> revision）另见 [`debugging-traps.md`](debugging-traps.md)。
>
> <!-- verified-against: develop@0ef478fd -->
> Verified against: develop @ `0ef478fd` ｜ Last verified: 2026-09-18 ｜
> Re-check when changing: 错误处理分支 / 冒烟与部署脚本 / nginx 部署模板 / 本节引用的测试

### 15.1 同一状态码 ≠ 同一语义：先看 `code`，别看状态码

- **409 双义**：`RESOURCE_CONFLICT` 是**翻译层兜底桶**（类型转换如 `SUM(bigint)`→numeric 再取 `Long`、
  约束冲突、部分翻译失败的语法错都会进来；detail 为「请求与现有数据约束冲突（重复或引用不允许）」）
  ——**不是并发信号**；只有 `CONCURRENT_MODIFICATION` 才是并发（死锁/锁获取失败）。
- **404 三义**：数据面未知/失效虚拟密钥 → `virtual_key_invalid`（统一反枚举，各场景逐字一致）；
  `/v1/**` 白名单外路径 → `unsupported_path`；管理面未知路由 → `NOT_FOUND`。
  **跨面提醒**：MCP 数据面对未知消费者密钥是 **401 `invalid_api_key`**（与 `/v1` 的 404 口径不同，
  均为既定设计，见 api-contract §7.1）。
- **同一个根因也会落进不同的桶**：拼接型 SQL 语法错落 `500 INTERNAL_ERROR`，
  而类型转换错（`SUM(bigint)`→numeric 再取 `Long`）落 `409 RESOURCE_CONFLICT`——`code` 不仅区分
  语义，**更不能靠状态码反推根因**。

### 15.2 泛化兜底会藏起真实类别：去"症状所指的那层之外"找信号

| 症状 | 看起来像 | 真实原因 | 去哪里找信号 |
|---|---|---|---|
| 请求 500 / 测试报错 | 服务器内部错误、系统故障 | SQL 拼接型语法错（如前段不以换行结尾粘出 `ue.tenant_idleft`）→ `BadSqlGrammarException` 落通用 500 `INTERNAL_ERROR` 分支 | **服务端日志**：`Unhandled exception [requestId=…]` 带完整堆栈——误导只在响应层 |

> CI 速挂（JDT）与构建静默卡死（Maven）两例如出一辙，同表见 [`debugging-traps.md`](debugging-traps.md) §1。

### 15.3 别把"没验证的输入"当事实（两机制、两修法）

- **二手结论过期**（例：按旧 ADR/旧记忆判断"某名字应为 X""某表零引用"，未查后续变更记录）→
  **修法：用前回源**——ADR 会被后续决策覆盖，老结论使用前先查更新记录；
- **不可读输入补全**（例：终端渲染乱码时照乱码形状"猜"出中文原文并当成原文写下）→
  **修法：别拿渲染结果做判断题**——存在性/内容判断一律用**编码安全锚点**（commit hash、PR 号、
  错误码、ASCII 标识符）；中文内容先写进文件再读取查看。

### 15.4 「命令返回了」≠「服务就绪了」（本部署模板的两次实测，2026-09-18）

同一族，都是"把『进程在』读成『服务可用』"：

| 现象 | 看起来像 | 真实原因 | 行动点 |
|---|---|---|---|
| 部署脚本冒烟在**正常**部署上报 502 | 上游挂了 / 配置错了 | **在本部署模板中**，nginx 的上游地址在启动阶段确定：后端容器刚换了地址而 portal 未重启，`/api` 全部 502 直到重启 | 替换任何后端容器后必须 `restart`/`reload` portal；冒烟要放在重启**之后** |
| 冒烟挪到重启之后，**第一次真机跑仍然红** | 站点起不来 | `restart` 返回的是"容器起来了"，**nginx 还没开始监听**：curl 立刻打就是 `000` | 对"还没起来"的应答（`000`/`5xx`）做**有界重试**；`403` 这类**判定**立即上报，不重试 |

共用行动点：**返回码、`Up healthy`、镜像 ID 相等，都只说明"进程在"**；"启动完成"与"服务就绪"
必须分开断言（健康检查 + 一条真实请求各一）。镜像 ID 相等尤其不能当配置正确的证据——挂载与
环境变量都可以错着而镜像完全正确。

### 15.5 不可见字符：两个"一模一样"的值不相等

- **现象**：断言报 `ASSERT FAILED … MIQROKEY_REGISTRATION_ENABLED='false' but …/.env says 'false'`——
  两侧打印逐字相同。
- **真实原因**：`.env` 是**混合行尾**（CRLF+LF），比较取的是**文件原始字节**（`false
`），而容器里是
  compose 解析后的值（`false`）。**这条断言比它所检查的系统更严格**，于是它查的是"文件的行尾是否干净"，
  而不是它存在的理由——"配置有没有送达"。
- **行动点**：
  1. 比较要**按被检查系统的语义归一化**（compose 的 dotenv 容忍 CR、trim 空白、剥一层引号）；
  2. **失败信息必须自证差异**：两侧长度相同就直接 `od -c` 打印字节，否则下一个人看到的就是
     "两个一样的值不相等"。

### 15.6 宽容的失败语会把检查变成摆设

| 现象 | 看起来像 | 真实原因 | 行动点 |
|---|---|---|---|
| 脚本 `exit 0` 并打印"部署完成"，而站点其实起不来 | 部署成功 | 证书挂载错位 → portal 崩溃重启 → 冒烟拿到 `000`，而 `000` 被分类成"天气"（warning，不判死） | 把"**我们自己的 bug**"与"**外部天气**"分开：URL 由脚本自己派生时，`000` 应当**判失败**；只有用户显式指定目标时才按"这台机器绕不出去"处理 |
| 冒烟"通过"，但 origin 层从未被检查 | 配置正确 | `GET /` 不触发 origin 校验（`403 ORIGIN_REJECTED` 只在写路径/登录出现） | 让冒烟打**能观测目标层**的请求：空体 `POST /api/v1/auth/login` + 允许的 Origin → `400` 正常 / `403` 白名单没进容器 / `502` 够不到上游 |
| 冒烟"通过"，但冒烟的 URL 被自己的解析 bug 拼坏了 | 检查过了 | 派生 URL 时没做上面 15.5 的归一化，CR 进了 URL → 够不到 → 判成天气 | 派生输入先归一化；并保证"派生路径的失败不打折" |

共用行动点：**通过语要精确到"这次到底证明了什么"**。在收尾语上多加一个词（`verified`）比在
断言里多加一条检查便宜得多，而前者正是把无配置容器放行的那条路。

## 备份与恢复（G6.2）

脚本位于 `deploy/backup/`：

| 脚本 | 用途 |
|---|---|
| `miqrokey-backup.sh` | pg_dump(custom) → gzip → AES-256-CBC(PBKDF2 200k) → `<BACKUP_PATH>/miqrokey-<UTC 时间戳>.sql.gz.enc` + SHA-256 manifest；保留（#438）：最新 `DAILY_KEEP` 个日备份（默认 7）+ 其余中每 ISO 周（周一起）最新 1 个、至多 `WEEKLY_KEEP` 周（默认 4）——同一次备份只计一次，其余连 manifest 剪除；失败不残留半成品。成功/失败经 Webhook（可选 HMAC-SHA256 签名 `X-MiQroKey-Signature: sha256=...`）通知 |
| `miqrokey-verify.sh <file>` | 校验 manifest + 解密干跑（`pg_restore --list`），不触碰任何库 |
| `miqrokey-restore.sh <file> [target-db]` | 校验 manifest → 解密 → `pg_restore --exit-on-error --single-transaction`（**单事务原子恢复**：#438，中途失败整体回滚、目标库不留半程状态）；恢复前目标库必须存在 |
| `test-restore.sh` | 真实恢复演练：双 Postgres 容器 → 播种 1000 行 → 真备份 → 校验 → 恢复 → 行数一致断言（已验证 PASS） |
| `test-retention.sh` | 保留语义夹具测试（无 Docker）：日历周精确保留集（20 → 10：最新 7 日 + W36/W35/W34 各 1）+ 幂等 + 失败零残留断言（已验证 PASS） |
| `test-retention-webhook.sh` | 保留上限与 Webhook 签名通知测试（已验证 PASS） |

### 每日备份（cron）

```bash
# 02:00 每日；密钥文件 32 字节 base64、权限 0400，与在线主密钥分离存储
0 2 * * * /opt/miqrokey/deploy/backup/miqrokey-backup.sh >> /var/log/miqrokey-backup.log 2>&1
```

环境变量：`MIQROKEY_BACKUP_PATH`、`MIQROKEY_BACKUP_DAILY_KEEP`、`MIQROKEY_BACKUP_WEEKLY_KEEP`、`MIQROKEY_BACKUP_KEY_FILE`（必需）、`MIQROKEY_BACKUP_WEBHOOK_URL`/`_SECRET`（可选）。退出码：0=成功，1=转储/加密失败，2=保留失败，3=备份成功但通知失败。

### 恢复演练要求

- 每季度至少一次 `test-restore.sh` 或对最新备份执行 `verify + restore` 到隔离实例。
- 上面这三个 `test-*.sh` 是**人工演练脚本，未接入 CI**（它们要起真实 Postgres 容器，属于季度演练而非每次提交的闸门）。**别把「已验证 PASS」读成「每次提交都会跑」**——这里的 PASS 是人工执行、记录在案的结果。
- 备份加密密钥离线/分离保管；`restore` 与 `verify` 均强校验 SHA-256 manifest。

## 性能与浸泡（G6.4）

| 工具 | 用途 |
|---|---|
| `backend/gateway-app` `SoakIntegrationTest`（`@Tag("soak")`） | 真实 gateway + mock 上游 + PostgreSQL 的 10 秒并发流浸泡（50 并发，`WINDOW_SECONDS`；长时档见下行 `soak.sh`）：断言 0 上游错误、全部请求落库（队列 drop 会表现为缺行）；CI 全量套件内运行 |
| `deploy/loadtest/soak.sh` | 生产类环境的长时间浸泡：对运行中的 stack 并发流式请求，报告吞吐/延迟分位/错误率 + usage 队列 drop 计数（须为 0） |

浸泡验收基线（首版）：并发 20 流持续 30 分钟无错误、usage 队列 drop 恒为 0、p99 延迟 ≤ 2× 基线。指标经 `monitoring` profile 的 `/actuator/prometheus` 观察。

## 导出与截断语义（#1096）

- 合规导出（审计 / 内容留痕 / 对账）是**流式** CSV：`X-MiQroKey-Rows` 给出精确行数，达到 5 万行时以 `X-MiQroKey-Truncated: true` 声明截断。
- **5 万行是行数上限，不是响应字节上限**。响应一开始就边查边写，HTTP 层没有"先算大小再决定"的机会；单次导出的传输量由**行宽**决定——内容留痕行是密文信封，单条可达数十 KB。
- 所以不要把导出当成"受大小保护的下载"：大时间窗 + 宽行会让连接长时间占用、产生大流量（这是容量问题，不是安全边界）。要**硬字节预算**，应改用异步导出任务那一族（生成文件 → 算大小 → 完成后下载），而不是中途截断 HTTP 流。
- 观察点：响应头 `X-MiQroKey-Rows` / `X-MiQroKey-Truncated` **与** nginx 访问日志里的 `$body_bytes_sent` 一起看，才能区分"被行数截断"与"本来就很小"。
