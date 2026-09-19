# 配置参考

所有应用配置使用 `MIQROKEY_` 前缀。生产环境优先通过只读 Secret 文件注入敏感值；环境变量适合非敏感配置和 Secret 文件路径。不得把真实凭证、master key 或 Webhook Secret 写入 Compose、Git、镜像层或命令行参数。

## 1. 配置优先级

从高到低：启动参数（仅开发）、环境变量、外部 `application.yaml`、镜像默认值。生产禁止通过门户修改进程级安全配置。

布尔值只接受 `true/false`，时长使用 ISO-8601（如 `PT30S`），容量使用明确单位。未知 `MIQROKEY_` 配置在生产 profile 下应使启动失败，防止拼写错误静默失效。

## 2. 基础配置

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_ENVIRONMENT` | `development` | `development/test/production`（**预留：当前版本未读取**，#733） |
| `MIQROKEY_PUBLIC_BASE_URL` | 无 | 门户公开 URL（**预留：当前版本未读取**；#733 更正——生产启动校验现仅含 Cookie Secure 与 originAllowlist，§10） |
| `MIQROKEY_GATEWAY_BASE_URL` | 无 | 展示给用户的 Gateway Base URL，生产必填 |

> **预留配置（文档登记、当前版本未读取，#733）**：`MIQROKEY_TIME_ZONE`、`MIQROKEY_INSTANCE_ID`、`MIQROKEY_CATALOG_PATH`、`MIQROKEY_DATA_PATH`、`MIQROKEY_TEMP_PATH`、`MIQROKEY_DB_CONNECT_TIMEOUT`、`MIQROKEY_DB_STATEMENT_TIMEOUT`、`MIQROKEY_MAX_CONTROL_BODY_BYTES`、`MIQROKEY_METRICS_ENABLED`、`MIQROKEY_METRICS_PATH`、`MIQROKEY_BACKUP_SCHEDULE`、`MIQROKEY_VK_ROTATION_GRACE`。这些键当前不改变任何行为；实现它们或从文档移除，随对应功能的变更一并处理。
| `MIQROKEY_TIME_ZONE` | `UTC` | 后台调度时区；存储仍为 UTC |
| `MIQROKEY_INSTANCE_ID` | 自动 | 审计和任务锁实例标识 |
| `MIQROKEY_CATALOG_PATH` | `/etc/miqrokey/catalog` | 只读供应商目录目录 |
| `MIQROKEY_DATA_PATH` | `/var/lib/miqrokey` | 导出、任务和本地运行数据 |
| `MIQROKEY_TEMP_PATH` | 系统临时目录 | 临时文件，必须同磁盘容量监控 |

## 3. 数据库

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_DB_URL` | `jdbc:postgresql://localhost:5432/miqrokey` | PostgreSQL JDBC URL |
| `MIQROKEY_DB_USERNAME` | `miqrokey` | 数据库账号 |
| `MIQROKEY_DB_PASSWORD_FILE` | 无 | 密码文件，生产必填 |
| `MIQROKEY_DB_POOL_MAX_SIZE` | `20` | Control Plane/usage 写入共享上限按部署校准 |
| `MIQROKEY_DB_CONNECT_TIMEOUT` | `PT5S` | 建连超时 |
| `MIQROKEY_DB_STATEMENT_TIMEOUT` | `PT30S` | 管理查询默认超时 |
| `MIQROKEY_DB_FLYWAY_ENABLED` | `true` | 生产允许迁移，但发布前必须演练 |

Gateway 不在事件循环中执行 JDBC；usage 写入进入有界队列和专用执行器。

## 4. 密钥与会话

### 4.1 Crypto 密钥配置

生产环境必须通过文件注入加密密钥；禁止将密钥写入环境变量、命令行参数或 Spring 属性。

```yaml
miqrokey.crypto.enabled: true
miqrokey.crypto.encryption.active-version: v1
miqrokey.crypto.encryption.versions[v1]: /etc/miqrokey/keys/master-key-v1.key
miqrokey.crypto.encryption.versions[v2]: /etc/miqrokey/keys/master-key-v2.key
miqrokey.crypto.hmac.active-version: v1
miqrokey.crypto.hmac.versions[v1]: /etc/miqrokey/keys/vk-hmac-v1.key
miqrokey.crypto.hmac.versions[v2]: /etc/miqrokey/keys/vk-hmac-v2.key
```

| Spring 属性 | 说明 |
|---|---:|
| `miqrokey.crypto.enabled` | 启用 crypto 自动配置；生产必为 `true` |
| `miqrokey.crypto.encryption.active-version` | 新加密使用的活跃密钥版本 ID |
| `miqrokey.crypto.encryption.versions[v1]` | 版本 → 密钥文件绝对路径；支持多版本用于轮换 |
| `miqrokey.crypto.hmac.active-version` | 新 Virtual Key 摘要使用的活跃 HMAC 版本 ID |
| `miqrokey.crypto.hmac.versions[v1]` | 版本 → HMAC 密钥文件绝对路径；支持多版本验证 |

### 4.2 密钥文件要求

- 必须为普通文件（拒绝符号链接、管道、目录）。
- POSIX 环境下必须为 `0400`（仅 owner 可读）；Windows 下至少需要进程可读。
- AES 主密钥：恰好 32 字节原始二进制或 base64 编码文本。
- HMAC 密钥：至少 32 字节原始二进制或 base64 编码文本。
- 拒绝全零、全相同字节（示例/弱密钥）。
- 主密钥和 HMAC 密钥必须为不同文件，且字节内容不同。

### 4.3 多版本轮换

1. 添加 `encryption.versions[v2]=/path/to/new-key.key`，设置 `active-version=v2`，重启。
2. 重启后新加密使用 v2；旧版本 v1 保留用于解密。
3. 以 SYSTEM_ADMIN 会话调用 `POST /api/v1/admin/crypto/reencrypt`（api-contract §5.1b）把旧密文批量
   重加密到 v2；幂等，可重复调用，直到响应 `remaining = 0`（`failed` 行按 id 排查后重跑）。
4. 确认 `remaining = 0` 后再从配置移除 v1，重启。**remaining > 0 时移除旧版本会使对应密文解密失败
   （fail-closed）**。
5. HMAC 密钥环不可批量迁移：Virtual Key 摘要单向、原值不落库——退役任一 HMAC 版本会使其签发的全部
   Virtual Key 立即失效且无法重算，必须先让这些 Key 完成重发（详见 operations-runbook §11）。

### 4.4 会话

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_BOOTSTRAP_SECRET_FILE` | 无 | 仅首个管理员创建时使用，完成后移除 |
| `MIQROKEY_REGISTRATION_ENABLED` | `true` | 自助注册开关（F-REG，api-contract §3.1b）：`false` 时 `/api/v1/auth/register` 返回 403 REGISTRATION_DISABLED（邀请制部署）；公网部署建议另配网络层速率限制 |
| `MIQROKEY_PLATFORM_OIDC_ENABLED` | `false` | 平台 OIDC 登录总开关（P0a，ADR-0017）：`true` 后登录页出现「平台账号登录」 |
| `MIQROKEY_PLATFORM_OIDC_IDP_CODE` | `forge` | 身份源标识；作为 `user_identity_link.idp` 的写入值与绑定查询条件（`PlatformOidcAuthService#insertLink/findLinkedUser`）。**已投入使用后修改会使既有绑定的查询落空**：`AUTO_PROVISION=true` 时按新 idp 再建一次账号，`false` 时原用户登录被拒（ACCOUNT_UNLINKED）；改值需同步迁移 `user_identity_link.idp` |
| `MIQROKEY_PLATFORM_OIDC_NAME` | `平台账号登录` | 登录页平台登录入口的显示名（`PlatformOidcAuthService` 构造的 `ProviderInfo`），仅影响展示 |
| `MIQROKEY_PLATFORM_OIDC_CLIENT_ID` / `_SECRET` | 空 | 平台侧注册的 OAuth2 client（test.forge 环境向平台申请） |
| `MIQROKEY_PLATFORM_OIDC_AUTHORIZE_URI` / `_TOKEN_URI` / `_USERINFO_URI` | 空 | 平台 OAuth2 端点；test 环境形如 `https://test.forge.miqroera.com/api/oauth2/authorize`（token/userinfo 同基址） |
| `MIQROKEY_PLATFORM_OIDC_REDIRECT_URI` | 空 | 本系统回调地址（需在平台 client 白名单登记） |
| `MIQROKEY_PLATFORM_OIDC_SCOPE` | `openid profile` | 授权请求携带的 OAuth2 `scope` 查询参数（`PlatformOidcAuthService` 拼 authorize URL）；取值应与平台侧 client 登记的 scope 一致 |
| `MIQROKEY_PLATFORM_OIDC_AUTO_PROVISION` | `true` | 首登自动建号并写 `user_identity_link`；`false` 时未绑定平台账号的登录被拒（ACCOUNT_UNLINKED） |
| `MIQROKEY_SESSION_COOKIE_NAME` | `MIQROKEY_SESSION` | Secure/HttpOnly/SameSite cookie |
| `MIQROKEY_CSRF_COOKIE_NAME` | `MIQROKEY_CSRF` | non-HttpOnly/SameSite cookie（JavaScript 可读） |
| `MIQROKEY_SESSION_IDLE_TIMEOUT` | `PT30M` | 空闲失效 |
| `MIQROKEY_SESSION_ABSOLUTE_TIMEOUT` | `PT12H` | 绝对失效 |
| `MIQROKEY_LOGIN_MAX_FAILURES` | `5` | 渐进锁定阈值 |
| `MIQROKEY_LOGIN_LOCK_BASE` | `PT1M` | 首次锁定时长 |
| `MIQROKEY_VK_ROTATION_GRACE` | `PT5M` | 规格默认旧 Key 宽限；管理员可立即失效 |
| `MIQROKEY_GATEWAY_BASE_URL` | `http://localhost:8081` | （当前实现）展示给用户的 Key Base URL（`miqrokey.gateway-base-url`） |
| `MIQROKEY_VIRTUAL_KEY_ROTATE_GRACE` | `PT0S` | （当前实现）轮换宽限期（`miqrokey.virtual-key-rotate-grace`）：`PT0S` = 快照刷新后旧 Key 立即失效；控制面在此窗口内对轮换 Key 的旋转状态提示 |
| `MIQROKEY_CREDENTIAL_DRAIN_GRACE` | `PT0S` | （当前实现）上游凭证轮换/禁用宽限期（`miqrokey.credential-drain-grace`）：旧凭证版本在 `retiredAt = now + grace` 前保持可解密，请求启动时已解密旧 Secret 的请求可完成；`PT0S` = 快照刷新后旧版本立即退役 |
| `MIQROKEY_PRODUCTION` | `false` | 生产模式：启用严格 Origin 验证、强制 cookie Secure 标志、拒绝 localhost 来源 |
| `MIQROKEY_ORIGIN_ALLOWLIST` | `localhost:5173,localhost:8080` | 生产模式下至少需要一个非 localhost 条目 |
| `MIQROKEY_COOKIE_SECURE` | `false` | Cookie Secure flag；生产模式（`miqrokey.production=true`）下必须显式设为 `true`，否则 `ProductionStartupValidator` 拒绝启动（不会自动启用） |

主密钥和 HMAC 密钥不能复用。生产启动时若文件权限过宽、长度错误或使用示例值，必须失败。

### 4.5 生产模式约束

当 Spring `production` profile 激活或 `miqrokey.production=true` 时，启动前执行以下验证（`ProductionStartupValidator`，任一不满足即拒绝启动）：

1. **Cookie Secure**：`cookieSecure` 必须为 `true`（显式设置 `MIQROKEY_COOKIE_SECURE=true`；不会自动启用）。
2. **Origin Allowlist**：必须包含至少一个非 localhost 条目，且每个条目均为带 scheme 的 https 裸 origin（如 `https://your-domain.com`，不得含路径/尾斜杠/query/userinfo）。
3. **启动失败**：allowlist 为空、仅含 localhost、或任一条目非法时，启动直接失败。

生产模式下，所有缺少/无效/未允许的 Origin 返回 `403 ORIGIN_REJECTED`；Cookie 自动设置 `Secure` flag；开发模式的 localhost 隐式放行被禁用。

## 5. Gateway 网络与流式

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_GATEWAY_PORT` | `8081` | 数据面端口 |
| `MIQROKEY_CONTROL_PORT` | `8080` | 管理面端口 |
| `MIQROKEY_UPSTREAM_URL` | 空 | 仅 Phase 0 固定路由 PoC 使用；后续由 Virtual Key 路由快照提供 |
| `MIQROKEY_UPSTREAM_CONNECT_TIMEOUT` | `PT10S` | 建立上游连接超时 |
| `MIQROKEY_UPSTREAM_FIRST_BYTE_TIMEOUT` | `PT120S` | 等待首个响应字节（含头）超时；超时永不重试 |
| `MIQROKEY_UPSTREAM_STREAM_IDLE_TIMEOUT` | `PT5M` | SSE 无数据超时（每个 chunk 重置）；已出首字节后超时 → `STREAM_INTERRUPTED` |
| `MIQROKEY_UPSTREAM_RESPONSE_TIMEOUT` | `PT10M` | 整体硬截止（自第一次尝试起计时，不重置）；流式空闲另算 |
| `MIQROKEY_MAX_INBOUND_HEADER_BYTES` | `32KB` | 入站 Header 上限（G2.6）；Netty 在路由前拒绝超限请求 → `431` |
| `MIQROKEY_MAX_CONTROL_BODY_BYTES` | `1MB` | 管理 API body 上限 |
| `MIQROKEY_MAX_PROXY_BUFFER_BYTES` | `256KB` | 只限制必要解析缓冲，不聚合完整响应 |
| `MIQROKEY_GATEWAY_CONTEXT_LIMIT_ENABLED` | `true` | 请求前置预检开关（#553，`miqrokey.gateway.context-limit.enabled`）：关闭后热路径行为与引入该预检前完全一致 |
| `MIQROKEY_GATEWAY_CONTEXT_LIMIT_THRESHOLD_CHARS` | `200000` | 请求前置预检阈值（#553，`miqrokey.gateway.context-limit.threshold-chars`，非正值回落默认）：按 UTF-8 码点统计**整个已缓冲 body**（含 JSON 结构、工具 schema、base64），超限 → `413 context_limit_exceeded`，不连接上游。字符数不是 token 数：合法 UTF-8 下是整个 body 的字符上界，**非法 UTF-8 按字节长度计**（严格 UTF-8 校验不通过即整段回退字节数），计数整体不低估（仍受缓冲上限约束）；它不是余额/配额，且会把 200001–262144 字符的请求从「缓冲上限放行」改为 413（刻意收紧，可用 `enabled` / `threshold-chars` 调整）：默认 200000 字符约合 5 万 token 量级，**可能拒绝上游本可接受的请求**，规模更大的工作负载请提高阈值或设 `MIQROKEY_GATEWAY_CONTEXT_LIMIT_ENABLED=false`。只读不重写（转发字节不变）。阈值高于 `MIQROKEY_MAX_PROXY_BUFFER_BYTES` 时由缓冲上限先拒绝（`payload_too_large`）。每 Key 可配置为 #553 的后续项，本版本只支持全局配置 |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_ENABLED` | `false` | 模型侧熔断开关（#741，`miqrokey.gateway.circuit-breaker.enabled`）：按**（供应商产品 × 上游凭证）**隔离的滑窗熔断；**默认关**——关闭时不查、不建桶、拒绝计数恒零，行为与引入前逐字节一致。打开后连败超阈值 → 请求**不触上游**直接 `503 circuit_open`（协议兼容信封），窗口到期半开探活、连续成功自动封闭。被拒请求不写用量与生命周期记录。与 MCP 侧 F13 同一状态机（口径对齐） |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_WINDOW_SECONDS` | `10` | 熔断统计滑窗（秒；F13 腾讯口径） |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_MIN_REQUESTS` | `10` | 窗口内最小样本数（防误判；不足不触发） |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_ERROR_RATIO` | `50` | 错误率阈值（%）；与最小样本数同时满足才触发 |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_ERROR_STATUS_CODES` | `500,502,503,504` | 计入错误的上游状态码集合（传输错误恒计入；**客户端取消不计**） |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_OPEN_SECONDS` | `30` | 熔断打开时长；到期进入半开 |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_PROBE_COUNT` | `3` | 半开窗口放行的探活请求数 |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_PROBE_SUCCESS` | `2` | 连续探活成功数（达到即封闭；任一探活失败立即重开） |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_SLOW_ENABLED` | `false` | 慢调用触发面开关（默认关） |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_SLOW_CALL_MS` | `3000` | 慢调用判定阈值（毫秒） |
| `MIQROKEY_GATEWAY_CIRCUIT_BREAKER_SLOW_RATIO` | `80` | 慢调用率阈值（%） |
| `MIQROKEY_MAX_CONCURRENT_STREAMS` | `50` | 首版容量目标（**预留：当前版本未实现**——无并发闸与对应 503 语义，#733）；不是用户限流策略 |
| `MIQROKEY_TRUSTED_PROXY_CIDRS` | 空（compose.prod 默认 `172.28.0.0/24`） | 数据面可信反向代理 CIDR（#605，`miqrokey.trusted-proxy.cidrs`）：仅当连接对端命中名单时才消费 `X-Forwarded-For` 记录调用方 IP（从右往左取第一个非可信地址）；空 = 只记录对端地址，请求头永不采信。compose 部署默认信任编排内网段（portal nginx 反代），control-plane 对等配置见 `MIQROKEY_CONTROL_ADMIN_TRUSTED_PROXIES` |
| `MIQROKEY_UPSTREAM_ALLOWED_CIDRS` | 空 | SSRF 门控 allowlist（G2.6）：命中这些 CIDR 的目标豁免「非公网地址」与「明文 http」两道拒绝（`127.0.0.0/8, ::1/128` 用于本地自建模型）；空 = 仅接受 https + 公网地址；`userinfo` URL 永不豁免 |
| `MIQROKEY_UPSTREAM_FOLLOW_REDIRECTS` | `false` | 重定向跟随硬编码禁用（G2.6：防止 30x 把已通过 SSRF 校验的目标重定向到任意地址）；当前版本不可配置 |
| `MIQROKEY_CONTROL_PROVIDER_CLIENT_CONNECT_TIMEOUT` | `10s` | 控制面 → 供应商调用的 TCP 连接超时（G3.1，`ProviderClient`） |
| `MIQROKEY_CONTROL_PROVIDER_CLIENT_REQUEST_TIMEOUT` | `30s` | 控制面 → 供应商单次调用整体截止（G3.1） |
| `MIQROKEY_CONTROL_PROVIDER_CLIENT_MAX_RESPONSE_BYTES` | `1048576` | 控制面 → 供应商单次响应体上限（G3.1）；超限中止交换 |
| `MIQROKEY_PRICE_SYNC_URL` | `https://openrouter.ai/api/v1/models` | 定价目录同步的公开价格源（#585，`miqrokey.price-sync.url`）：编译期适配器默认 OpenRouter 模型索引；运维可覆盖，不接受请求参数传入；大陆服务器实测 jsDelivr/GitHub raw 不可用（18KB/s/超时），勿改回 |
| `MIQROKEY_PRICE_SYNC_USD_CNY_RATE` | `7.2` | 价格源 USD→CNY 换算率（#585）：同步时 `USD/token × 1e6 × 汇率` 写入 CNY/1M 快照；汇率变化只影响此后同步写入的数值 |
| `MIQROKEY_PRICE_SYNC_CONNECT_TIMEOUT` | `10s` | 价格源连接超时（#585） |
| `MIQROKEY_PRICE_SYNC_REQUEST_TIMEOUT` | `30s` | 价格源单次请求整体截止（#585）；源文件约 700KB，正常 <3s |
| `MIQROKEY_PRICE_SYNC_MAX_BYTES` | `10485760` | 价格源响应体上限（#585）；超限按失败处理（零写入） |
| `MIQROKEY_PRICE_SYNC_AUTO_ENABLED` | `false` | 官方价格 24h 自动同步开关（#708，F08，`miqrokey.price-sync.auto.enabled`）：true 时 `PriceSyncScheduler` 按周期拉取同一价源并按 24h 增量写入 `source=OFFICIAL` 快照；默认关（无人值守改价影响成本口径，先由运维显式开启）。可观测：`monitoring` profile 下 `miqrokey_control_price_sync_auto_total{result=success|failure}` 计数 + 失败 `PRICE_SYNC_FAILED` 审计 + ERROR 日志 |
| `MIQROKEY_PRICE_SYNC_AUTO_CYCLE_MS` | `86400000` | 自动同步周期（#708，`miqrokey.price-sync.auto.cycle-ms`，fixedDelay——上一轮结束后计时，慢价源不叠加）；默认 24 小时 |
| `MIQROKEY_PRICE_SYNC_AUTO_INITIAL_DELAY_MS` | `60000` | 自动同步首轮延迟（#708，`miqrokey.price-sync.auto.initial-delay-ms`）：避开启动期的迁移/种子竞争 |
| `MIQROKEY_QUOTA_ENFORCEMENT_INTERVAL_MS` | `60000` | 配额软着陆判定周期（#684，`miqrokey.quota.enforcement-interval-ms`，控制面 `@Scheduled` 固定延迟）：重算 ACTIVE REJECT 规则的超限判定并整体替换 `quota_enforcement`，判定集变化才发布路由刷新；周期即"额外放行量"的上界（ADR-0020 D5） |
| `MIQROKEY_QUOTA_ENFORCEMENT_INITIAL_DELAY_MS` | `45000` | 软着陆评估器首轮延迟（#684，`miqrokey.quota.enforcement-initial-delay-ms`）：避开启动期的迁移/种子竞争 |
| `MIQROKEY_ALERTS_EVALUATION_INTERVAL_MS` | `300000` | 告警规则评估固定延迟（G4.5，`@Scheduled`）；也控制投递重试扫描节奏 |
| `MIQROKEY_ALERTS_ADMIN_KEY_EXPIRY_INTERVAL_MS` | `21600000` | 管理密钥到期扫描间隔（`miqrokey.alerts.admin-key-expiry-interval-ms`，6 小时）：有启用的 ADMIN_API_KEY_EXPIRING 规则时检查 ≤7 天到期密钥并产生事件（规则 opt-in，默认关） |
| `MIQROKEY_ALERTS_CONSUMER_KEY_EXPIRY_INTERVAL_MS` | `21600000` | 消费者密钥到期扫描间隔（`miqrokey.alerts.consumer-key-expiry-interval-ms`，6 小时，镜像管理密钥先例）：有启用的 CONSUMER_KEY_EXPIRING 规则时检查 ≤7 天到期消费者并产生事件（规则 opt-in，默认关） |
| `MIQROKEY_SERVICES_HEALTH_CYCLE_MS` | `15000` | 服务注册表健康探测周期（`miqrokey.services.health-cycle-ms`，#326）：按各服务自身间隔探测 ACTIVE 服务（GET baseUrl+checkPath，2xx 计健康）；DISABLED 不探测 |
| `MIQROKEY_MCP_HEALTH_CYCLE_MS` | `15000` | MCP 服务健康探测周期（`miqrokey.mcp.health-cycle-ms`）：按各 MCP 服务自身间隔探测 ONLINE 服务（GET endpoint+checkPath，2xx 计健康）；OFFLINE 不探测 |
| `MIQROKEY_MODEL_CATALOG_REPROBE_ENABLED` | `false` | 模型目录定期重探开关（`miqrokey.model-catalog.reprobe.enabled`，#350）：true 时按周期对种子租户 ACTIVE 订阅关联的 OFFICIAL_API 产品执行与手动探测同一实现的抓取（成功才落目录；失败记录在 V43 探测状态面）；默认关（doc 05「不要过于频繁」） |
| `MIQROKEY_MODEL_CATALOG_REPROBE_CYCLE_MS` | `21600000` | 定期重探周期（`miqrokey.model-catalog.reprobe.cycle-ms`，fixedDelay——上一轮结束后计时，慢上游不叠加）；默认 6 小时 |
| `MIQROKEY_USAGE_PRICE_RECONCILE_ENABLED` | `false` | 派生列调和定时通道（#777，`miqrokey.usage.price-reconcile.enabled`）：true 时按周期把最近 48h 内已盖章行的价格标签重算、补写缺失的冻结金额——**不重查价目、不改价格列、不动既有金额**；默认关（人工通道 `POST /admin/usage-price-backfill` 与它并存，关掉只是回到"只有人工跑"）。`compose.prod.yaml` 已接线：生产在 `.env` 设值即可（**不要**改运行树的 compose 文件） |
| `MIQROKEY_USAGE_PRICE_RECONCILE_CYCLE_MS` | `900000` | 调和周期（`miqrokey.usage.price-reconcile.cycle-ms`，fixedDelay 15 分钟——上一轮结束后计时）；首轮延迟 `MIQROKEY_USAGE_PRICE_RECONCILE_INITIAL_DELAY_MS` 默认 `120000`（2 分钟，等应用就绪再开跑） |
| `MIQROKEY_MCP_SSE_REAP_CYCLE_MS` | `30000` | 入站 MCP SSE 会话空闲回收扫描周期（`miqrokey.mcp.sse.reap-cycle-ms`，#356）：空闲 5 分钟的会话由网关切流；容量上限 256 |
| `MIQROKEY_CLEANUP_EXPIRED_SWEEP_MS` | `3600000` | 过期记录 GC 固定延迟（F06，`@Scheduled`）：回收下载窗口已过的导出产物、确认窗口已过的删除请求，以及已过 `expires_at` 的登录会话（含已吊销行）；EXECUTED 删除记录与审计链永久保留，不入 GC |
| `MIQROKEY_CONTROL_PROVIDER_CLIENT_ALLOWED_CIDRS` | 空 | 控制面 → 供应商调用的 SSRF 门控 allowlist（G4.2）：命中这些 CIDR 的目标豁免「非公网地址」与「明文 http」两道拒绝（配额刷新对接本地/内网供应商网关时配置，如 `127.0.0.0/8`）；空 = 仅接受 https + 公网地址 |
| `MIQROKEY_APPROVAL_WHITELIST_MODELS` | 空 | 模型审批白名单（逗号分隔的精确模型 ID）：用户申请命中白名单即自动批准并立即生效（写入授权 + 快照刷新），免管理员审批；空 = 全部模型走人工审批 |
| `MIQROKEY_CONTROL_ADMIN_IP_ALLOWLIST` | 空 | 管理门户来源 IP 白名单（F05，security §6，CIDR 逗号分隔如 `10.0.0.0/8,203.0.113.0/24`）：空 = 不限制（历史行为）；配置后门户面仅名单内来源可达（403 IP_NOT_ALLOWED），billing 通道与 bootstrap 豁免；非法 CIDR 启动失败 |
| `MIQROKEY_CONTROL_ADMIN_TRUSTED_PROXIES` | 空 | 受信反向代理 CIDR（F05）：只有来自这些代理的 `X-Forwarded-For` 被采纳为真实客户端地址——直连来源无法伪造头绕过白名单 |

`MIQROKEY_MAX_CONCURRENT_STREAMS` 是保护实例稳定性的容量目标，不是按用户/团队配额。（#733 更正：**当前版本未实现该并发闸与 `503 CAPACITY_EXHAUSTED`**；容量过载的现行为是上游超时/缓冲上限的自然背压。）

### 5.1 Gateway 数据库模式（当前实现）

Gateway 使用版本化只读路由快照 + 有界用量写入队列（G2.2/G2.4 当前实现）：

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_GATEWAY_PERSISTENCE_ENABLED` | `true` | 数据库模式总开关（路由快照、L2 缓存、用量写入） |
| `MIQROKEY_GATEWAY_DB_URL` | `jdbc:postgresql://localhost:5432/miqrokey` | 数据面连接串 |
| `MIQROKEY_GATEWAY_DB_USERNAME` | `miqrokey` | 数据面用户名 |
| `MIQROKEY_GATEWAY_DB_PASSWORD` | 空 | 数据面密码（生产用 `_FILE` 约定或 Secret 挂载） |
| `MIQROKEY_GATEWAY_DB_POOL_SIZE` | `5` | 数据面连接池；热路径不执行阻塞查询，快照刷新在专用调度器 |
| `MIQROKEY_GATEWAY_ROUTE_REFRESH_INTERVAL` | `30s` | 路由快照刷新周期——兜底机制；正常路径由 `pg_notify` 事件即时刷新，通知丢失时按此周期自愈（宽限期配置见 4.5） |
| `MIQROKEY_GATEWAY_ROUTE_RETRY_CHECK_INTERVAL` | `2s` | 快照「schema 尚未就绪」快速重试的滴答周期（#846）：全新部署时网关可能先于控制面 Flyway 起跑，此时刷新失败若为 PostgreSQL `42P01`（表不存在=还没建到）按 2→4→8→16→30s 退避快速重试并打 WARN（区别于其它 SQL 错误的 ERROR）；就绪后该滴答只做一次原子检查。编排侧另已让 gateway 等 control-plane `service_healthy`（compose.prod.yaml），此旋钮兜底"网关指向未迁移库"的旁路 |
| `MIQROKEY_GATEWAY_ROUTE_NOTIFY_CHANNEL` | `miqrokey_route_refresh` | PostgreSQL `LISTEN/NOTIFY` 通道名；控制面在变更事务提交后（AFTER_COMMIT）向该通道发布通知，Gateway 专用连接监听并立即重载快照 |
| `MIQROKEY_GATEWAY_QUEUE_CAPACITY` | `50000` | 用量写入有界队列容量（#424：吸收负载下写入端多秒级停顿的红线突发） |
| `MIQROKEY_GATEWAY_QUEUE_FLUSH_THRESHOLD` | `100` | 单次批量写入条数（#417：每次 flush **全量排空**队列、按此值分块调用 writer；此前误作「每次 flush 排空上限」，把稳态吞吐钉死在 threshold/interval = 20 事件/秒） |
| `MIQROKEY_GATEWAY_QUEUE_FLUSH_INTERVAL` | `1s` | 批量 flush 周期（#424：5s 使红线档突发在一个周期内超容量触发 DROP；1s 下单周期突发 ≈2400 ≪ 容量 10000）。**同一周期也驱动丢弃事实上报**（F07/#245）：每个周期把「自上次上报以来新增的丢弃数」写一行 `gateway_queue_signal`（0 条则不写），无独立配置项 |
| `MIQROKEY_GATEWAY_QUEUE_WRITER_THREADS` | `4` | 专用有界 writer 执行器线程数（G2.4） |
| `MIQROKEY_GATEWAY_QUEUE_SATURATION_MODE` | `DROP` | 队列饱和策略（F35）：`DROP` = 保持热路径不阻塞、事件计数丢弃（默认）；`WRITE_THROUGH` = 应急直写——单事件经专用 writer 执行器幂等写入并**有界等待**（见下），审计完整性优先、发布线程短暂停滞可接受。只有 `DROP` 造成的丢失会写 `gateway_queue_signal` 事实行（写穿失败回退为计数丢弃时同样记行） |
| `MIQROKEY_GATEWAY_QUEUE_WRITE_THROUGH_TIMEOUT` | `5s` | WRITE_THROUGH 单事件直写的等待上限；超时/失败仍按 drop 计数兜底，发布线程永不无限阻塞 |
| `MIQROKEY_GATEWAY_COALESCER_ENABLED` | `false` | 请求合并（single-flight）：默认关闭（ADR-0008） |
| `MIQROKEY_GATEWAY_COALESCER_WAIT_TIMEOUT` | `2s` | 合并等待窗口 |
| `MIQROKEY_CACHE_ENABLED` | `false` | 响应缓存总开关（默认关闭，见 §9） |
| `MIQROKEY_CACHE_L1_ENABLED` | `true` | L1 内存缓存（总开关开启后生效） |
| `MIQROKEY_CACHE_L1_TTL` | `300s` | L1 TTL |
| `MIQROKEY_CACHE_L2_ENABLED` | `true` | L2 PostgreSQL 缓存 |
| `MIQROKEY_CACHE_L2_TTL` | `300s` | L2 TTL |

队列达到高水位必须告警；队列满不能静默丢弃——写失败保留在队列并重试，幂等键防止双计。

## 6. Usage、成本与后台任务

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_USAGE_RETENTION_MODE` | `MANUAL_ONLY` | 首版永久保留直到人工删除（**预留：当前版本未读取**，#733） |
| `MIQROKEY_PLAN_SYNC_INTERVAL` | `PT15M` | 余额/周期同步（**预留：当前版本未读取**——真实旋钮为 `miqrokey.quota.refresh-interval-ms`，默认 900000ms，见下） |
| `MIQROKEY_MODEL_SYNC_INTERVAL` | `PT6H` | 模型目录同步（**预留：当前版本未读取**——真实旋钮为 `miqrokey.model-catalog.reprobe.*`） |
| `miqrokey.quota.refresh-interval-ms` | `900000` | **实际生效**：配额/余额快照定时刷新周期（`QuotaSnapshotService` @Scheduled，毫秒） |
| `miqrokey.model-catalog.reprobe.*` | 默认关 | **实际生效**：模型目录定期重探（#350 交付；enabled/interval 等子键） |
| `MIQROKEY_PRICE_CATALOG_PATH` | `/etc/miqrokey/prices` | 版本化价格目录 |
| `MIQROKEY_EXPORT_MAX_RANGE` | `P93D` | 单次导出最大时间窗（**预留：当前版本未读取**——实现硬编码 93 天，与 api-contract 一致；#733 更正，原文档误写 `P366D` 且不可配） |
| `MIQROKEY_EXPORT_LINK_TTL` | `PT24H` | 下载链接到期（**预留：当前版本未读取**——实现硬编码 24 小时；#733 更正，原文档误写 `PT1H` 且不可配） |

队列达到高水位必须告警；队列满不能静默丢弃。G2.4 实现语义：写失败把整批**按序重入队**并记 `warn`（幂等写入保证重试不双计），饱和 drop 按高优先级 `warn` 计数——均不静默；`miqrokey.usage.queue.*` 无标签 gauge（深度/发布/持久化/drop/flush）供告警。

F15 MCP 访问日志队列（网关数据面）：`miqrokey.gateway.mcp-log.capacity`（默认 4096，`MIQROKEY_GATEWAY_MCP_LOG_CAPACITY`）、`miqrokey.gateway.mcp-log.flush-interval-ms`（默认 1000，`MIQROKEY_GATEWAY_MCP_LOG_FLUSH_INTERVAL_MS`）。语义同 usage 队列：饱和 drop+WARN 计数、批量写失败整批重入队（`(tenant_id, gateway_request_id)` 幂等保证重试不双写）；`MIQROKEY_GATEWAY_PERSISTENCE_ENABLED`；`false` 时日志为 no-op（不产行），与 usage 持久化同一开关（**注意：网关 persistence 默认开启（`true`）**，#733 更正——原文误写"（默认）"。另见 §5.1）。

**I19 外部投递**（同开关组 `miqrokey.gateway.mcp-log.forward.*`，默认全关）：批次**落库成功后**扇出到已配置 sink
（重入队批次不重复投递；sink 失败仅节流 WARN，不重试、不阻断数据面）。webhook：`…forward.webhook-url`
（`MIQROKEY_GATEWAY_MCP_LOG_FORWARD_WEBHOOK_URL`；POST JSON 数组 + 可选 `…forward.webhook-token`
`MIQROKEY_GATEWAY_MCP_LOG_FORWARD_WEBHOOK_TOKEN` 的 `Authorization: Bearer` 头；`…forward.webhook-timeout-ms`
默认 5000）。syslog：`…forward.syslog-host`/`…forward.syslog-port`（默认 514）/`…forward.syslog-protocol`
（`UDP|TCP`，默认 UDP）/`…forward.syslog-facility`（默认 `LOCAL0`）；RFC 5424 帧
（`<PRI>1 <ts> <host> miqrokey-gateway - - <json>`），MSG 为 `aigw.mcp.*` JSON（OTel 字段风格，纯元数据，
永不包含工具参数/应答正文）。投递有**硬截止**（#401）：每个 sink 由 `TimeBoundedForwarder` 包装——专属守护
线程 + 墙钟截止（webhook=配置超时+5s，syslog=15s）；超时按失败计入节流 WARN，**连续超时 3 次进入 60s 冷却**
（期间跳过、不重试），冷却后自动探测恢复——挂起的 sink（对端接受连接但停止读取等）不会阻塞落库
（flush 管线与队列不受影响）。

合规留痕侧信道（ADR-0014，默认全关——除 retention_config 开关外无任何采集）：`miqrokey.retention.capacity`（默认 512，`MIQROKEY_RETENTION_CAPACITY`）、`miqrokey.retention.flush-interval-ms`（默认 1000，`MIQROKEY_RETENTION_FLUSH_INTERVAL_MS`）、`miqrokey.retention.max-text-chars`（默认 100000，`MIQROKEY_RETENTION_MAX_TEXT_CHARS`，单请求用户文本上限，超限跳过+计数）。采集面由控制面 `retention_config`（管理 API §5.26）逐租户开关并经路由快照下发；无 crypto 或 publisher 时 fail-closed。

**R3 Kafka 出口（ADR-0014，默认关）**：`miqrokey.retention.kafka.bootstrap-servers`（默认空=不启用，`MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS`；配置后替换 no-op publisher 为真实投递，topic 默认 `content-retention`）、`miqrokey.retention.kafka.topic`（`MIQROKEY_RETENTION_KAFKA_TOPIC`）、`miqrokey.retention.kafka.client-id`（默认 `miqrokey-gateway-retention`，`MIQROKEY_RETENTION_KAFKA_CLIENT_ID`）。记录键 = SHA-256(tenant/user)，同用户恒落同分区；信封 JSON 携带 AES 密文（base64），明文永不出网关；发送异步、失败节流计数（消费者按 eventId 幂等容忍重放）。

**R4 内置留痕消费端（控制面，ADR-0014 §8，默认关）**：`miqrokey.retention.consumer.enabled`（`MIQROKEY_RETENTION_CONSUMER_ENABLED`）、`miqrokey.retention.consumer.bootstrap-servers`（`MIQROKEY_RETENTION_CONSUMER_BOOTSTRAP_SERVERS`；enabled=true 且非空才启动）、`…consumer.topic`（默认 `content-retention`）、`…consumer.group-id`（默认 `miqrokey-retention-console`）、`…consumer.poll-millis`（默认 500）。消费端把信封**密文原样**写入 `retention_log`（`event_id` 幂等，at-least-once + 手动提交），供管理台「内容留痕」查看与 CSV 导出；broker 断连仅节流告警，不影响请求路径。

## 7. Webhook 与告警

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_WEBHOOK_ENABLED` | `true` | 全局开关（**预留：当前版本未读取**——开关为每端点 `enabled`；#733） |
| `MIQROKEY_WEBHOOK_CONNECT_TIMEOUT` | `PT5S` | 连接超时（**预留：当前版本未读取**——超时为每端点 `timeout_ms` 列） |
| `MIQROKEY_WEBHOOK_REQUEST_TIMEOUT` | `PT10S` | 请求超时（同上：按每端点 `timeout_ms`） |
| `MIQROKEY_WEBHOOK_MAX_ATTEMPTS` | `3` | 指数退避次数（**与实现一致**：#733 更正——`AlertEventDispatcher` 上限 3 次、退避 2^attempt×60s；原文档误写 6） |
| `MIQROKEY_WEBHOOK_MAX_AGE` | `P1D` | 最长重试窗口（**预留：当前版本未读取**——实现无重试年龄上限） |
| `MIQROKEY_WEBHOOK_SIGNATURE_HEADER` | `X-MiQroKey-Signature` | HMAC-SHA256 签名 Header（**与实现一致**：#733 更正——实际头名为 `X-MiQroKey-Signature`，载荷 `sha256=<hex>`；原文档误写 `-256` 后缀） |

目标 URL 和 Secret 由管理员在数据库配置；Secret 加密保存。发送器必须实施 SSRF 校验，并禁止重定向逃逸。

## 8. 备份与可观测性

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_BACKUP_SCHEDULE` | `0 0 2 * * *` | 每日 02:00（按 `MIQROKEY_TIME_ZONE`） |
| `MIQROKEY_BACKUP_DAILY_KEEP` | `7` | 每日备份数量 |
| `MIQROKEY_BACKUP_WEEKLY_KEEP` | `4` | 每周备份数量 |
| `MIQROKEY_BACKUP_PATH` | `/var/backups/miqrokey` | 应映射到独立存储 |
| `MIQROKEY_BACKUP_KEY_FILE` | 无 | 备份加密密钥，必须与在线 master key 分离 |
| `MIQROKEY_METRICS_ENABLED` | `false` | Prometheus 指标（G6.1）：`monitoring` profile 激活时暴露 `/actuator/prometheus`；默认关闭（G0.1 安全边界） |
| `MIQROKEY_METRICS_PATH` | `/actuator/prometheus` | 仅管理网络暴露 |
| `SPRING_PROFILES_ACTIVE` | 空 | 附加 `monitoring`（Prometheus 抓取端点）与 `json`（Logstash JSON 日志，G6.1） |
| `MIQROKEY_LOG_LEVEL` | `INFO` | 生产禁止默认 DEBUG |

管理面 OpenAPI（F09）：Control Plane 固定暴露 `GET /v3/api-docs`（OpenAPI 3.1，springdoc，无 swagger-ui）。该端点只读、无需鉴权（文档消费）；基线 `docs/openapi/openapi-3.1.json` 与 CI 破坏性 diff 见 api-contract §8。如生产不希望暴露可后续加 `springdoc.api-docs.enabled=false` 环境开关（本版本未暴露为 `MIQROKEY_` 变量）。

指标标签不得使用用户 ID、完整模型输入、Key、request body 或供应商错误正文等高基数/敏感值。

请求前置预检（#553）在拒绝时计数 `miqrokey_gateway_context_limit_rejected_total`（零标签 counter，与 `miqrokey_gateway_requests_total` 同规矩）；命中日志只含 requestId、路径、实测字符数与阈值，不含 body 内容。

上游错误体分类（ADR-0024 选项 B / #770，**只观测**）：网关对**已经缓冲**的上游非 2xx 响应体做**有界**（前 8KB）子串分类，命中即计数 `miqrokey_gateway_upstream_error_class_total{class=…}`——`class` 是**有界枚举**（`SIGNATURE_INVALID` / `THINKING_BLOCK_MISMATCH` / `MISSING_SIGNATURE` / `BUDGET_INVALID` / `UNCLASSIFIED`），符合上一段「标签不得高基数」的规矩；HTTP 状态码只进日志、**不作标签**（上游可能返回任意整数码）。同时打一行 `status=… class=…` 日志。**不重试、不改写请求、不改变响应**——客户端收到的仍是上游原字节；错误体**只读不存**（不进日志正文、不落库、不进事件），被缓冲上限截断的响应体一律记 `UNCLASSIFIED`（不从不完整片段下结论）。该计数是「签名类错误是否为稳定模式」这一判定的证据来源；升档（选项 C 的整流重试）需另行拍板。

## 9. Cache（ADR-0009 已启用）

**实现（2026-08-29，ADR-0009 放行）**：L1 内存（Caffeine）+ L2 PostgreSQL（`cache_entry` 表）双级缓存。总开关 `MIQROKEY_CACHE_ENABLED` 默认 `false`（生产默认零行为变化）。只有同时满足以下条件才可能命中缓存：

- `MIQROKEY_CACHE_ENABLED=true` 且 L1/L2 各自开关开启；
- Virtual Key `cache_policy=ENABLED`（创建时显式开启，默认 `DISABLED`；前端 KeysView 可选择）；
- 客户端显式声明 `X-MiQroKey-Cacheable: 1`；
- 请求满足缓存资格（无工具字段、非空 body，由 `CacheEligibility` 判定；工具调用永不缓存）。

缓存响应按字节重放（SSE 支持）；命中计数与节省成本在成本报表页展示（`savedByGatewayCache`）。缓存内容不解读、不进日志与审计。语义缓存（向量召回；本文旧称「L2 向量」，与代码的 L2=精确缓存撞名，见 ADR-0022 §10-8）不启用。

Gateway 必须透明保留供应商自己的 Prompt Cache Header/字段，并单独统计 cache token；这与本系统响应缓存无关。

## 10. 生产启动校验

生产 profile **当前实现**的拒绝启动项（`ProductionStartupValidator`）：**Cookie 非 Secure**、**originAllowlist 未配置或不合规**。

（#733 更正：本节此前列出的其余九项——缺少公开 URL、缺失密钥文件、默认/弱密钥、数据库版本、目录签名、导出或备份目录不可写、上游 scheme、开启响应缓存、Flyway 校验——**均未实现为启动门禁**；其中密钥文件缺失与 Flyway 失败会在使用点自然失败而非启动预检，其余为预留设计。）
