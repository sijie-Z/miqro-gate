# API 契约

本文定义 Control Plane 管理接口和 Gateway 推理入口的稳定边界。实现后以生成的 OpenAPI 为机器可读事实，但 OpenAPI 不得改变本文的业务语义。

## 1. 通用约定

- 管理 API 前缀：`/api/v1`；推理 API 保持上游原生路径，例如 `/v1/messages`。
- 管理 API 使用门户会话 Cookie；Gateway 使用 `Authorization: Bearer <virtual-key>` 或上游协议要求的等价 Header。
- JSON 字段使用 `camelCase`，数据库字段使用 `snake_case`，时间为 UTC RFC 3339。
- 资源 ID 使用不可枚举的 UUIDv7；金额以最小货币单位或 `decimal string + currency` 表示，不使用浮点数。
- 列表默认按 `createdAt DESC, id DESC`，使用不透明 cursor，禁止 offset 深分页。
- 写请求支持 `Idempotency-Key`；重复键和不同请求体返回 `409 IDEMPOTENCY_CONFLICT`。（**预留：当前版本未实现**，#734——重复提交目前会重复创建；幂等语义的实现/移除随对应功能变更另行立项。）
- 可更新资源返回 `version`，更新时提交 `If-Match`；版本冲突返回 `412 VERSION_CONFLICT`。（**#734 更正为实现现状**：`version` 为**请求体字段**、随写请求提交；并发冲突由服务端乐观锁检出并映射为 `409` + 端点级错误码（如 `SERVICE_STATE_CONFLICT`「并发状态变更，请刷新后重试」）。`If-Match` 请求头与 `412 VERSION_CONFLICT` 为**预留，当前版本未实现**。）
- 管理写接口校验 `Origin` 和 CSRF token。推理入口不使用浏览器 Cookie，不做 CSRF。
- `/api/v1/**` 不接受供应商 API Key 或 Virtual Key 作为门户身份。

## 2. 错误格式

采用 RFC 9457 Problem Details：

```json
{
  "type": "about:blank",
  "title": "Virtual key not found",
  "status": 404,
  "code": "VIRTUAL_KEY_NOT_FOUND",
  "detail": "The requested virtual key does not exist or is not visible.",
  "requestId": "0190...",
  "fieldErrors": [{"field": "name", "code": "REQUIRED"}]
}
```

所有错误响应均包含 `type`（通常为 `about:blank`）、`title`、`status`、稳定 `code` token 和唯一 `requestId`。`application/problem+json` 为所有管理 API 错误的标准 Content-Type。filter、interceptor、controller、全局 exception handler 均使用此格式。

普通用户访问他人资源统一返回 `404`，避免资源枚举。错误响应、应用日志和审计记录不得出现真实 Key、Virtual Key 明文或请求正文。

登录失败返回通用的 `401 UNAUTHORIZED`，无论用户不存在、密码错误、账号禁用或锁定均使用相同消息 `"账号或密码不正确。"`（用户可见 detail 一律简体中文，与控制台语言一致；机器可判的 `code` 不变）。

## 3. 身份与会话

### 3.1 认证端点

| 方法与路径 | 用途 | 访问者 |
|---|---|---|
| `POST /api/v1/auth/bootstrap` | 一次性创建首个 SYSTEM_ADMIN 管理员 | 匿名（需 bootstrap secret） |
| `POST /api/v1/auth/login` | 用户名/密码登录，创建会话 | 匿名 |
| `POST /api/v1/auth/register` | 自助注册（F-REG）：创建普通用户并直接登录 | 匿名（开关 `miqrokey.registration-enabled`，默认开） |
| `GET /api/v1/auth/registration-status` | 自助注册开关的公开只读状态（#550，登录页入口闸门） | 匿名 |
| `POST /api/v1/auth/logout` | 当前会话失效 | 已登录 |
| `GET /api/v1/auth/me` | 当前用户、角色、状态、最近登录与会话到期时间 | 已登录 |
| `POST /api/v1/auth/password` | 修改自己的密码并撤销其他会话 | 已登录 |
| `POST /api/v1/auth/logout-others` | 退出其他会话：撤销除当前会话外的全部会话（自助版 `revoke-sessions`；审计 `LOGOUT_OTHERS`；强制改密会话被 `PASSWORD_CHANGE_REQUIRED` 门槛拦截） | 已登录 |
| `GET /api/v1/auth/csrf` | 获取 CSRF token（从配置名称的 Cookie 读取） | 已登录 |

### 3.1b 自助注册（F-REG）

`POST /api/v1/auth/register`：`{ "username", "displayName"?, "password" }` → `201`（响应体与 `/login` 相同，并下发同一套会话 Cookie，注册即登录）。语义：

- 只创建 `USER` 角色账号（管理员仍走 `/admin/users` 邀请制流程）；`mustChangePassword=false`（密码为本人所设）。
- 校验：用户名空白/超长 → `400 USERNAME_INVALID`；重复 → `409 USERNAME_TAKEN`（租户行锁序列化并发注册）；密码不满足策略（长度/字符类别/常见密码）→ `400 PASSWORD_INVALID`。
- 开关 `miqrokey.registration-enabled`（`MIQROKEY_REGISTRATION_ENABLED`，默认 `true`）为 `false` 时 → `403 REGISTRATION_DISABLED`；登录、bootstrap 不受影响。私有化部署需要"仅邀请"时可关闭。
- 公开端点：与 login/bootstrap 一样无会话、无 CSRF 要求；审计事件 `REGISTER`。
- 防滥用注记：单租户内部/试用规模未加频率限制；对外公网部署建议在网络层加速率限制（记录于配置参考）。

**`GET /api/v1/auth/registration-status`（#550）**：同一开关的公开只读视图，供登录页在渲染注册入口**之前**查询，避免"填完表单提交才拿到 `403`"。

- 访问：匿名（`SessionFilter.PUBLIC_PATHS` 精确匹配白名单）；无会话要求。CSRF 拦截器虽注册在 `/api/**` 全方法上（`SecurityConfig#addInterceptors`），但 `CsrfInterceptor#preHandle` 对非状态变更方法直接短路放行，故 `GET` 不需要 CSRF token，也无需加入 `CSRF_EXEMPT`。
- 响应 `200 application/json`：`{ "enabled": true | false }`——**仅此一个布尔字段**，不回显配置来源、开关名称或任何部署信息（集成测试断言响应体恰好 1 个字段）。
- 判定同源：与 `/register` 的 `403` 分支读同一个已绑定属性（`AuthProperties.registrationEnabled`，`@ConfigurationProperties` 启动期绑定、无 `@RefreshScope`），故同一进程内两者不可能给出不同答案。
- 错误：正常路径无业务错误码；`5xx` 仅来自通用异常处理器。前端对此端点**失败即放行**（默认按"开"渲染，仍由 `/register` 的 `403 REGISTRATION_DISABLED` 强制），因此该端点是 UX 前置提示而**非**权限判定点。
- 审计：本端点为纯只读查询，**不写审计事件**。注意与 `/register` 不同：成功注册会写 `REGISTER` 事件，两者在审计面上不等价。

### 3.1c 平台 OIDC 登录（P0a，ADR-0017，2026-09-08）

授权码 RP：登录页出现「平台账号登录」（开关关闭时不出现）→ 平台 `authorize` → 回调。

- `GET /api/v1/auth/oauth/providers`（公开）→ `[{code,name}]`（空数组=未启用）。
- `GET /api/v1/auth/oauth/start`（公开）→ 302 至平台 `authorize`（带 state Cookie）。
- `GET /api/v1/auth/oauth/callback?code&state`（公开）→ 换 token → `/oauth2/userinfo`
  → `sub` 经 `user_identity_link`（idp=forge）映射/自动建号（可配关）→ 建立普通门户会话 → `/app/keys`。
  失败重定向 `/login-new?oauth_error=<ASCII 码>`：`STATE_MISMATCH` / `ACCOUNT_UNLINKED` /
  `CONFIG_INCOMPLETE` / `AUTH_ERROR` / `USERINFO_INVALID` / `USERNAME_CONFLICT` / `PROVIDER_UNKNOWN`。
- 审计：`OAUTH_LOGIN` / `OAUTH_PROVISION`（首次建号）；自动建号用户无口令登录通道（随机口令）。

### 3.2 Bootstrap 流程

首个管理员通过 `POST /api/v1/auth/bootstrap` 创建，需提供一次性 bootstrap secret（来自 `MIQROKEY_BOOTSTRAP_SECRET_FILE` 配置的文件）。bootstrap 在数据库层通过 `SELECT ... FOR UPDATE` 锁租户行序列化并发请求：即使两个请求使用不同用户名，也只有恰好一个能成功创建管理员。

响应返回一次性临时密码 `temporaryPassword`（之后不可再次获取）、`shownOnce: true` 和会话 Cookie。首次登录时 `mustChangePassword` 为 `true`，强制改密。

### 3.3 CSRF 保护

所有 `POST/PUT/PATCH/DELETE` 写请求需要 CSRF 保护（`/api/v1/auth/login` 和 `/api/v1/auth/bootstrap` 除外）。CSRF token 通过以下机制传递：

1. 登录/bootstrap 响应设置 CSRF Cookie（名称由 `miqrokey.csrf-cookie-name` 配置，默认 `MIQROKEY_CSRF`）；Cookie 为 non-HttpOnly（JavaScript 可读），SameSite=Strict。
2. 客户端从 Cookie 读取 CSRF token，在写请求中以 `X-CSRF-Token` Header 发送。
3. 服务端通过 SHA-256 digest 比对验证 token。

`GET /api/v1/auth/csrf` 端点返回当前会话的 CSRF token 值和过期时间。

### 3.4 Origin 验证

生产模式（`miqrokey.production=true` 或 Spring `production` profile 激活）下，所有对 `/api/` 的状态变更请求（POST/PUT/PATCH/DELETE）必须包含有效的 `Origin` Header。Origin 通过严格的 `java.net.URI` 解析进行验证（scheme、host、port 完全匹配），不使用子字符串匹配。

生产模式不允许 localhost 或开发 Origin；缺少/无效/未列入 allowlist 的 Origin 返回 `403 ORIGIN_REJECTED` 并包含 `requestId`。

开发模式下，缺少 Origin 或 localhost 来源的请求被放行。

### 3.5 会话 Cookie

会话 Cookie 使用 `miqrokey.session-cookie-name` 配置名称（默认 `MIQROKEY_SESSION`），属性为 HttpOnly、SameSite=Strict。生产模式下自动启用 `Secure` flag（若未显式设置，启动时自动覆盖为 `true`）。clear 操作也保持相同的安全属性。

### 3.6 登录安全

连续登录失败触发渐进锁定：延迟从 250ms 逐步增加到最大 3s，达到 `miqrokey.login-max-failures`（默认 5）后账户锁定，锁定时长指数退避（1 min → 2 min → 4 min → ... 最大 ~17 小时）。失败计数在数据库行锁（`SELECT ... FOR UPDATE`）下原子递增，并发请求不会丢失更新。登录失败和账户锁定均持久记录审计事件 `LOGIN_FAILED` 和 `ACCOUNT_LOCKED`。

密码要求至少 8 个字符、包含大小写字母和数字、最多 128 字符、拒绝常见/已泄露密码。首次登录强制修改临时密码。

## 4. 普通用户 API

普通用户只能看到自己创建的 Virtual Key，以及这些 Key 产生的用量。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/me/grants` | 可选项目、产品、凭证授权、模型和用途 |
| `GET /api/v1/me/virtual-keys` | 自己的 Key 列表；只返回前缀和末四位 |
| `POST /api/v1/me/virtual-keys` | 自助创建 Key；明文只在本次响应出现 |
| `GET /api/v1/me/virtual-keys/{id}` | 自己的 Key 元数据和 Base URL |
| `POST /api/v1/me/virtual-keys/{id}/rotate` | 原子轮换；旧 Key 按配置宽限后失效 |
| `POST /api/v1/me/virtual-keys/{id}/revoke` | 立即吊销 |
| `PATCH /api/v1/me/virtual-keys/{id}` | 重命名（#582；绑定/模型/密钥不变，审计 from/to） |
| `POST /api/v1/me/virtual-keys/{id}/disable` | 临时停用（#582；网关按未知密钥 404，可恢复） |
| `POST /api/v1/me/virtual-keys/{id}/enable` | 恢复已停用密钥的路由（#582） |
| `GET /api/v1/me/usage/summary` | 自己的聚合用量和成本 |
| `GET /api/v1/me/usage/records` | 自己的明细，受分页和最大时间窗限制 |

创建请求（ADR-0018：一把 Key 可绑定多个项目——`projectIds` 首个为主项目，其授权即 `credentialGrantId`；附加项目由服务端匹配该项目下同产品的最早 ACTIVE 授权，匹配不到则 409 `PROJECT_GRANT_MISSING`。旧字段 `projectId` 仍兼容=单元素）：

```json
{
  "name": "claude-code-main",
  "projectIds": ["0190...", "0191..."],
  "providerProductId": "0190...",
  "credentialGrantId": "0190...",
  "purpose": "CLAUDE_CODE",
  "allowedModels": ["provider-model-id"]
}
```

响应新增 `boundProjects: [{ projectId, projectTag }]`：打印字符串携带首个项目的标签；对其余已绑定项目，把同一密钥核心段追加各自标签（`mqk_live_<id>_<secret>.<tag>`）即可路由。标签不参与 HMAC、不承载权限；它是**路由选择器**而非授权边界：单绑定 Key 上任意合法标签都路由到该唯一绑定；多绑定 Key 的请求归属按 §7.1 的上下文解析阶梯裁决——无法解析时 `400 CONTEXT_REQUIRED`（失败关闭，不猜不 404）。项目标签在项目创建时若未填写会自动生成（code slug），且**被绑定引用后不可修改**（409 `PROJECT_TAG_IN_USE`；历史绑定不随轮换解除）。

前置条件：`projectId` 所属项目必须已设置路由标签（`project_tag`，Key 明文后缀嵌入该标签用于路由）；未设置时返回 `409 ROUTING_TAG_MISSING`——普通用户请联系管理员在项目设置中补充后重试（管理员建项目时请勿留空）。

创建响应：

```json
{
  "id": "0190...",
  "secret": "mqk_live_once_only",
  "baseUrl": "https://gateway.example.internal",
  "display": "mqk_live_...8f2a",
  "shownOnce": true,
  "createdAt": "2026-07-17T05:00:00Z",
  "version": 1
}
```

服务端不允许再次读取 `secret`。遗失后只能轮换或新建。

### 4.1 我的授权 `GET /api/v1/me/grants`

返回当前用户可用的项目、供应商授权、模型和用途选项。普通用户只能看到自己是成员的项目；他人资源一律不出现。

```json
{
  "projects": [
    { "id": "0190...", "code": "CORE", "name": "Core AI", "projectTag": "core-ai" }
  ],
  "grants": [
    {
      "id": "0190...",
      "projectId": "0190...",
      "providerProductId": "0190...",
      "models": ["claude-3-7-sonnet", "claude-3-5-haiku"]
    }
  ],
  "purposes": ["CLAUDE_CODE", "CLAUDE_DESKTOP", "CODEX", "CUSTOM"]
}
```

### 4.2 Key 列表与详情

`GET /api/v1/me/virtual-keys` 返回 `VirtualKeyView` 数组，`GET /api/v1/me/virtual-keys/{id}` 返回单个。只包含前缀和末四位，永远不包含完整 Secret：

```json
{
  "id": "0190...",
  "name": "claude-code-main",
  "purpose": "CLAUDE_CODE",
  "status": "ACTIVE",
  "displayPrefix": "mqk_live_abcdefghijklmnopqrstuv",
  "lastFour": "8f2a",
  "display": "mqk_live_…8f2a",
  "modelIds": ["claude-3-7-sonnet"],
  "projectId": "0190...",
  "projectTag": "core-ai",
  "cachePolicy": "DISABLED",
  "baseUrl": "https://gateway.example.internal",
  "createdAt": "2026-07-17T05:00:00Z",
  "lastUsedAt": null,
  "revokedAt": null
}
```

`status` ∈ `ACTIVE | ROTATING | REVOKED | DISABLED`。`cachePolicy` 默认 `DISABLED`（显式开启才可参与响应缓存）。

### 4.3 轮换与吊销

`POST /api/v1/me/virtual-keys/{id}/rotate` 原子轮换：旧 Key 立即停止接受新请求，在配置宽限期（`miqrokey.virtual-key-rotate-grace`，默认 `PT0S`）内仍可路由，宽限结束后失效。响应与创建响应相同（`CreateVirtualKeyResponse`，新 Secret 仅本次出现一次）。

`POST /api/v1/me/virtual-keys/{id}/revoke` 立即吊销，响应：

```json
{ "message": "Virtual key revoked" }
```

轮换/吊销只允许 `ACTIVE`（吊销额外允许 `ROTATING`）；冲突返回 `409 KEY_NOT_ROTATABLE` / `409 KEY_NOT_REVOCABLE`。操作写审计事件，审计日志不含 Secret 明文。

`PATCH /api/v1/me/virtual-keys/{id}`（#582）重命名：body `{ "name": "..." }`（必填，≤200 字符）；仅改展示名，绑定、模型与密钥本身不变，审计记录 from/to；已吊销（`REVOKED`）的密钥不可重命名（`409 KEY_NOT_RENAMEABLE`），且不触发路由快照刷新（路由不依赖名称）。

`POST /api/v1/me/virtual-keys/{id}/disable` 与 `.../enable`（#582）为可逆软停用：停用后该 Key 在下一次路由快照刷新时被移除，请求得到与未知密钥一致的 404（反枚举口径不变）；启用后恢复路由，绑定与授权原样保留。停用仅允许 `ACTIVE`（`409 KEY_NOT_DISABLEABLE`，含 `ROTATING` 拒绝），启用仅允许 `DISABLED`（`409 KEY_NOT_ENABLEABLE`）。两者写审计（`VIRTUAL_KEY_DISABLE` / `VIRTUAL_KEY_ENABLE`）并发布路由快照刷新。

### 4.4 用量汇总 `GET /api/v1/me/usage/summary`

参数：`groupBy`（`project | virtual_key | cache_level | day | user | team | model | month | product`，默认 `project`；**I15**：`user`=调用方（label=用户名）、`model`=模型、`month`=自然月 `YYYY-MM`；**#1050**：`day`/`month` 按**调用方给定的固定偏移**分桶（label `YYYY-MM-DD` / `YYYY-MM`），偏移由 `tzOffsetMinutes` 传入（分钟，范围 [-1080, 1080]，缺省 0=UTC；与小时报表同一参数、同一校验，越界返回 400 `TZ_OFFSET_INVALID`）。分桶**与会话/服务器 TimeZone 无关**：同一批数据在任何服务器、任何会话下结果一致；此偏移是「固定偏移」而非 IANA 时区，跨 DST 的历史窗口如需精确本地日应等待后续按 ZoneId 的增强）；**2026-09-15**：`team`=团队成员归属（label=团队名，经成员的 Virtual Key 归集；同一用户属多团队时在各团队分别计入——归属视图非分割口径）；**#758**：`product`=供应商产品（label=产品显示名））、`from`、`to`（ISO-8601，默认最近 93 天窗口；`from` 必须在 `to` 之前，窗口超过 93 天拒绝）。

```json
{
  "groupBy": "project",
  "groups": [
    {
      "groupKey": "core-ai",
      "label": "core-ai",
      "requests": { "upstream": 12, "coalesced": 0, "l1Hit": 0, "l2Hit": 0 },
      "tokens": { "input": 1200, "output": 800, "cacheRead": 0, "cacheCreation": 0 },
      "cost": {
        "upstreamPaid": 0.0128,
        "gatewayObserved": 0.0128,
        "projectAllocated": 0.0128,
        "savedByGatewayCache": 0.0,
        "upstreamPaidParts": { "input": 0.0064, "output": 0.0064, "cacheRead": 0.0, "cacheCreation": 0.0 },
        "gatewayObservedParts": { "input": 0.0064, "output": 0.0064, "cacheRead": 0.0, "cacheCreation": 0.0 }
      },
      "outcomes": { "succeeded": 11, "failed": 1, "cancelled": 0, "avgDurationMs": 4200, "avgTtfbMs": 1800 }
    }
  ],
  "totals": { "requests": { "upstream": 12, "coalesced": 0, "l1Hit": 0, "l2Hit": 0 }, "tokens": { "input": 1200, "output": 800, "cacheRead": 0, "cacheCreation": 0 }, "cost": { "upstreamPaid": 0.0128, "gatewayObserved": 0.0128, "projectAllocated": 0.0128, "savedByGatewayCache": 0.0, "upstreamPaidParts": { "input": 0.0064, "output": 0.0064, "cacheRead": 0.0, "cacheCreation": 0.0 }, "gatewayObservedParts": { "input": 0.0064, "output": 0.0064, "cacheRead": 0.0, "cacheCreation": 0.0 } }, "outcomes": { "succeeded": 11, "failed": 1, "cancelled": 0, "avgDurationMs": 4200, "avgTtfbMs": 1800 } }
}
```

- 用量明细只包含自己的 Key 产生的记录；他人的 Key 不出现也不可区分（统一 404）。
- `upstreamPaid` 按 `price_snapshot`（每百万 token 单价，来源 `MANUAL|OFFICIAL|ESTIMATED`）计算；无价格快照的模型按 `0` 计。
- 缓存命中产生的成本节省记入 `savedByGatewayCache`，不计入 `projectAllocated`。
- **成本构成（#1097）**：`upstreamPaidParts` / `gatewayObservedParts` 把相应合计按 token 维度拆开——`input` / `output` / `cacheRead` / `cacheCreation`，**四者之和精确等于对应的合计**（分项与合计累加的是同一批已除过 100 万的逐行金额，不是各算一遍）。**为什么是两组**：两个合计覆盖的行集不同——合并请求（`COALESCED`）进 `gatewayObserved` 而不进 `upstreamPaid`，缓存命中的节省则两边都不进（只记 `savedByGatewayCache`）；拿一组分项去配另一个合计会对不上账。未定价用量对分项与合计同样计 `0`，缺口由 `pricingStatus` / `unpriced` 表达（同 #943）。管理与自助两个 summary 端点同口径。
- `outcomes`（#758）：生命周期终态来自 `request_usage_records`（按 gateway request id 一对一对齐）；`succeeded = 转发+合并 − failed − cancelled`——**客户端取消不计入成功率两侧**（`CLIENT_CANCELLED` 既不算成功也不算失败），无生命周期行的合并请求计成功侧；`avgDurationMs` / `avgTtfbMs` 仅在实际观测到取值的行上平均，无观测为 `null`。缓存命中（`cache_hit_event`）不参与成功率。

### 4.5 用量明细 `GET /api/v1/me/usage/records`

参数：`from`、`to`（ISO-8601）、`page`（默认 1，≥1）、`size`（默认 50，1–200）。按时间倒序。

**归属（#1128，CAA V54）**：每行带三个**可空**字段——

- `resolutionStatus`——服务端裁定。**现产四个值**：`RESOLVED_HEADER`（按请求头声明的项目）/ `RESOLVED_SUFFIX`（按密钥后缀）/ `SOLE_BINDING`（该密钥只有一个绑定，无需上下文）/ `POLICY_ROUTED`（多绑定且无法解析，走租户的未归属策略）。词表另保留 `UNATTRIBUTED` / `AMBIGUOUS`（它们是**客户端**可声明的 claim-status 取值，当前 resolver 不产出），消费方见到未知取值应按原样展示而非丢弃。
- `claimSource`——客户端**声明**的来源，7 个允许值：`prompt_url` / `tool_path` / `bash_cwd` / `system_cwd` / `git_remote` / `suffix` / `none`（与 §7.1 同一份清单）。
- `claimConfidence`——`HIGH` / `MEDIUM` / `LOW` / `NONE`。

三者**刻意分开**：声明是未验证输入，裁定才是结论。控制台并排展示**裁定与声明来源**（但不展示声明的项目 id，见下），因此「声明存在、却被别的方式裁定」这类行是可辨认的；「声明指向哪个项目」不在此契约内。

`claimSource` / `claimConfidence` 为 `null` 有两种情形且**无法区分**：客户端没发对应请求头，或发了但未通过校验（超 64 字符、去空白后为空、或不在允许集合内）——所以它们不承载「客户端一定没声明」的语义。`resolutionStatus` 为 `null` 只出现在 **V54 之前写入的行**（或不经代理路径写入的行）：每个已认证的代理请求都会走归属阶梯。单绑定密钥的行**既不是 null，也不是 `SOLE_BINDING`**——密钥铸造时以后缀携带项目标签，阶梯的第 2 步（后缀）先于第 3 步（唯一绑定兜底）命中，所以普通单绑定密钥的行记的是 `RESOLVED_SUFFIX`；`SOLE_BINDING` 出现在**后缀与绑定不匹配**时（后缀允许携带任意标签，不匹配即视为装饰并下坠到兜底步）。

**刻意不外露**：`session_id` / `activity_id`（CAA Spec 的 session_id 隐私口径未决，且这两列的语义是「纯观测、不参与路由授权」）与 `claimed_project_id`（未验证输入，且只有 id 没有名字）以及 V55 的 `request_context_evidence` 证据表。管理端点（§5）与对外只读通道同口径。

```json
{
  "items": [
    {
      "occurredAt": "2026-07-17T05:00:00Z",
      "modelId": "claude-3-7-sonnet",
      "cacheLevel": "UPSTREAM",
      "inputTokens": 600,
      "outputTokens": 400,
      "cacheReadInputTokens": 0,
      "cacheCreationInputTokens": 0,
      "totalTokens": 1000,
      "latencyMs": 1842,
      "upstreamStatusCode": 200,
      "providerRequestId": "msg_01...",
      "gatewayRequestId": "req-abc123",
      "isComplete": true,
      "usageMissing": false,
      "virtualKeyId": "0190...",
      "clientIp": "203.0.113.7",
      "providerProductName": "DeepSeek 官方按量 API",
      "ttfbMs": 2100,
      "wireProtocol": "ANTHROPIC_MESSAGES",
      "requestStatus": "SUCCEEDED",
      "cost": 0.0128,
      "priced": true
    }
  ],
  "page": 1,
  "size": 50,
  "total": 12
}
```

- `cacheLevel` ∈ `UPSTREAM | COALESCED | L1_HIT | L2_HIT`。缓存命中行没有 token 数（NULL → 0）且 `isComplete=false` 时不作为上游用量计入。
- `usageMissing=true` 表示上游未返回 usage（如异常中断）；该行仍入账但用量为 0，便于排查。
- `clientIp`（#605）：调用方网络地址——传输层对端；仅当对端命中 `MIQROKEY_TRUSTED_PROXY_CIDRS` 可信代理时才消费 `X-Forwarded-For`（**从右往左**取第一个非可信地址，杜绝最左伪造），非 IP 字面量（主机名/带端口）一律不记录、不解析；无法确定时为 `null`。历史行与直连未配置代理时的对端地址照记。
- `providerProductName` / `ttfbMs` / `wireProtocol` / `requestStatus`（#758）：供应商产品显示名与生命周期富集列，来自 `request_usage_records` 按 gateway request id 的左连接；合并请求无生命周期行时三者均为 `null`（首字对无首字节的失败请求同样为 `null`）。
- `cost` / `priced`（#758）：单行成本估计，用与汇总相同的价目快照与算法（`tokens × 单价 / 1e6` 逐 token 类型求和）；`priced=false` 表示**非零的输入/输出 token 缺少价目快照**（前端显示「未定价」，此时 `cost` 不可信）；缓存读/写缺价与聚合口径一致按 0 计，不触发该标记（真实供应商常不单列缓存写费率）。
- `providerRequestId` 在 tenant 内唯一（幂等写，重复 flush 不双计）。

### 4.6 模型申请（审批流）`POST/GET /api/v1/me/model-approvals`

用户在 Virtual Key 上申请授权范围外的模型；管理员在 `5.18` 审批队列处理。

- `POST /api/v1/me/model-approvals`：`{ "virtualKeyId", "modelId", "reason"? }` → 201 `ModelApprovalView`。
  - `modelId` 精确匹配（trim、≤ 128、禁控制字符）；理由 ≤ 500。
  - **目录前置（#506）**：模型必须在 Key 所属产品的 `model_catalog` 中有 ACTIVE 行（即 `/v1/models` 的第三层闸门），否则 `409 MODEL_NOT_IN_CATALOG`——未目录化的模型"批准了也不会在网关生效"，因此在源头拦截并提示管理员先录入/探测。
  - 模型已在 Key 上 → `400 MODEL_ALREADY_AVAILABLE`；同 Key 同模型已有 PENDING → `409 DUPLICATE_PENDING`；Key 非本人/不存在 → 通用 `404 KEY_NOT_FOUND`（防枚举）；Key 非 ACTIVE → `409 KEY_NOT_ACTIVE`。
  - 白名单模型（`miqrokey.approval.whitelist-models`）提交即自动 `APPROVED` 并立即生效，`reviewNote="Auto-approved: model on the approval whitelist"`、`reviewedBy=null`；仍写入 SUBMITTED + APPROVED 两条审计。
- `GET /api/v1/me/model-approvals`：本人全部申请（时间倒序）。

`ModelApprovalView`（安全视图，仅掩码/显示名，无 Key 明文）：

```json
{
  "id": "0190...", "virtualKeyId": "0190...", "keyName": "claude-code-main",
  "keyDisplay": "mqk_live_…8f2a", "projectTag": "core-ai",
  "modelId": "deepseek-v4-flash", "reason": "编码需要", "status": "PENDING",
  "requesterId": "0190...", "requesterName": "张三",
  "reviewNote": null, "reviewedByName": null,
  "createdAt": "2026-09-02T00:00:00Z", "updatedAt": "2026-09-02T00:00:00Z"
}
```

审计事件：`MODEL_APPROVAL_SUBMITTED` / `MODEL_APPROVAL_APPROVED` / `MODEL_APPROVAL_REJECTED`（target=MODEL_APPROVAL，summary 含 virtualKeyId/modelId，自动批准含 `"autoApproved":true`）。

### 4.7 我的配额 `GET /api/v1/me/quota-rules`（F04）

用户自助配额可见性：调用者名下的 **USER 作用域**配额规则 + 当前窗口实时水位（只读）。管理员设置的规则（含默认配额模板自动复制）对用户透明展示；停用规则仍可见。

- 响应 = `QuotaRuleView[]`（同 `5.19` 管理端视图字段：metric/period/limitValue/warnPercent/status/used/usedPct/level/windowFrom/windowTo 等；COST 规则同样带 `pricingStatus`/`unpriced`——见 `5.19` 的定价口径）——仅含 `scopeId == 当前用户` 的行，其他人/项目规则绝不出现。
- 口径与审计同 `5.19`（水位读时计算、NORMAL/WARNING/EXCEEDED）；本端点不触发审计（只读）。
- 会话鉴权（任意角色，含普通用户）；匿名 `401`。无规则时返回空数组。

### 4.8 错误码

| code | HTTP | 场景 |
|---|---|---|
| `IP_NOT_ALLOWED` | 403 | 来源 IP 不在管理门户白名单（F05：`miqrokey.control.admin-access.ip-allowlist`；billing 通道与 bootstrap 豁免） |
| `PROJECT_NOT_FOUND` | 404 | 项目不存在 |
| `PROJECT_MEMBERSHIP_REQUIRED` | 403 | 当前用户不是项目成员 |
| `PROJECT_INACTIVE` | 409 | 项目已停用 |
| `ROUTING_TAG_MISSING` | 409 | 项目没有配置路由标签（projectTag） |
| `GRANT_INVALID` | 400 | 授权不属于该项目或产品 |
| `GRANT_INACTIVE` | 409 | 授权已停用 |
| `MODEL_NOT_GRANTED` | 400 | 请求的模型超出授权范围 |
| `MODEL_ALREADY_AVAILABLE` | 400 | 模型已在该 Key 上（无需申请） |
| `MODEL_INVALID` | 400 | 模型 ID 格式非法（空白/控制字符/超长） |
| `DUPLICATE_PENDING` | 409 | 同 Key 同模型已有待审批申请 |
| `KEY_NOT_ACTIVE` | 409 | Key 已停用/吊销，不能申请或审批生效 |
| `ALREADY_REVIEWED` | 409 | 申请已被审批（乐观锁，重复审批被拒） |
| `KEY_NOT_FOUND` | 404 | Key 不存在或不属于当前用户 |
| `KEY_NOT_ROTATABLE` | 409 | 仅 ACTIVE 可轮换 |
| `KEY_NOT_REVOCABLE` | 409 | 该状态不可吊销 |
| `PAGE_INVALID` / `SIZE_INVALID` | 400 | 分页参数越界 |
| `TIME_RANGE_INVALID` / `TIME_RANGE_TOO_WIDE` | 400 | 时间窗参数错误 |
| `GROUP_BY_INVALID` | 400 | groupBy 取值非法 |

所有错误都是 RFC 9457 `application/problem+json`，含 `type`、`status`、`code`、`detail`、`requestId`。

## 5. 管理员 API

管理员拥有单租户内全部管理权限：

- `/api/v1/admin/users`：用户创建、禁用、密码重置、会话撤销。
- `/api/v1/admin/teams`、`/projects`：组织与项目。
- `/api/v1/admin/provider-products`：供应商产品实例、Base URL、协议族、目录版本。
- `/api/v1/admin/subscriptions`：PAYG、个人 Plan、团队 Plan、企业 Plan。
- `/api/v1/admin/subscriptions/{id}/members`：席位、成员 Key 或共享池成员关系。
- `/api/v1/admin/credentials`：创建、测试、轮换、禁用真实凭证。
- `/api/v1/admin/grants`：向用户授予项目、产品、凭证和模型范围。
- `/api/v1/admin/virtual-keys`：全局查询、吊销；仍不返回明文。
- `/api/v1/admin/usage/**`：全局汇总、差异视图、解析失败队列。
- `/api/v1/admin/exports`：创建和下载原始记录导出任务。
- `/api/v1/admin/reconciliation/**`：导入官方账单并生成匹配结果。
- `/api/v1/admin/webhooks`：目标、签名 Secret、测试和投递记录。
- `/api/v1/admin/audit-events`：不可修改的管理审计事件（读面可选 `action`/`targetType`/`actorId`/
  `from`/`to` 精确筛选 + `beforePosition` cursor）；每行带**只读** `targetName`（#389，doc 27）：按页内
  `(targetType, targetId)` 批量子查询解析的资源名（租户内、未知类型或引用已不存在为 null——前端回退短 ID；
  链上数据与导出**不变**）；`GET /api/v1/admin/audit-events/export`：
  CSV 合规导出（对齐腾讯 AI 网关操作记录下载；上限 5 万行、截断以 `X-MiQroKey-Truncated` 声明，
  参数/形状同 §9 机器端点）。**5 万行是行数上限，不是响应字节上限**：导出是流式的，响应一旦开始吐字节，
  `X-MiQroKey-Truncated` 就只是应用层声明，HTTP 层没有"先算大小再决定"的机会——单次导出的传输量由行宽决定
  （留痕行的密文尤其大）。要硬字节预算须走异步导出任务（生成文件、算完大小再下载），而不是中途截断流。
- `/api/v1/admin/usage-deletions`：双确认后人工删除用量范围。
- `/api/v1/admin/retention-logs`：内容留痕日志（ADR-0014 §8）——分页解密查看（`userId`/`direction`/
  `protocol`/`from`/`to` 筛选、`page`/`size`；返回信封元数据 + 解密文本 + `dataMd5`）与
  `GET /api/v1/admin/retention-logs/export`（CSV 合规导出，形状同审计导出：5 万行上限、截断以
  `X-MiQroKey-Truncated` 声明）；仅 SYSTEM_ADMIN，每次查看/导出自身进审计
  （`RETENTION_LOG_VIEW`/`RETENTION_LOG_EXPORT`）。数据由**可选内置消费端**
  （`miqrokey.retention.consumer.*`，默认关）从 `content-retention` topic 幂等落库（`retention_log`
  行内保持密文；明文只在控制面解密路径出现，且仅出现在受审计的管理员响应中）。

真实凭证写接口只接受明文输入，响应只返回掩码、指纹、版本和验证状态。凭证测试不得自动把未保存值写入数据库。

### 5.0 组织（G5.2）

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/users` | 用户列表（**永不返回 passwordHash**，Jackson mixin 全局排除） |
| `POST /api/v1/admin/users` | 创建用户（`username`/`displayName`/`role`）；返回一次性临时密码（仅本次出现） |
| `PATCH /api/v1/admin/users/{id}` | 更新显示名与/或状态（`displayName` 非空白 ≤200；`status`：ACTIVE/DISABLED/LOCKED；至少一项，空请求 → 400 `USER_UPDATE_EMPTY`，显示名非法 → 400 `DISPLAY_NAME_INVALID`；禁用/锁定即撤销全部会话；SYSTEM_ADMIN 不可禁用 → 409 `ADMIN_NOT_DISABLEABLE`；#614） |
| `POST /api/v1/admin/users/{id}/reset-password` | 重置密码 + 撤销全部会话；返回新临时密码（仅本次） |
| `POST /api/v1/admin/users/{id}/revoke-sessions` | 撤销该用户全部会话 |
| `GET /api/v1/admin/users/{id}/project-memberships` | 用户所属项目列表（`[{projectId, projectCode, projectName, projectStatus, joinedAt}]`，按 code 排序）——管理员「加入项目」快捷入口数据面（F-REG 闭环）；用户不存在 `404 USER_NOT_FOUND` |
| `GET/POST /api/v1/admin/teams`、`PATCH /{id}` | 团队列表/创建/更新 |
| `GET/POST /api/v1/admin/teams/{id}/members`、`DELETE /members/{userId}` | 团队成员管理 |
| `GET/POST /api/v1/admin/projects`、`PATCH /{id}` | 项目列表/创建（`code` 唯一，冲突 → 409 `PROJECT_CODE_TAKEN`）/更新 |
| `GET/POST /api/v1/admin/projects/{id}/members`、`DELETE /members/{userId}` | 项目成员管理 |
| `GET/PUT/DELETE /api/v1/admin/unattributed-policy` | **未归属策略（V57，#647，Spec §7.3）**：`PUT {credentialId, providerProductId?, models?}`（产品缺省从凭证订阅推导；凭证须 ACTIVE，不匹配 → `400 UNAUTH_CREDENTIAL_PRODUCT_MISMATCH`；模型按 #498 目录语义校验 → `400 MODEL_NOT_IN_CATALOG`；凭证不存在/停用 → `404 CREDENTIAL_NOT_FOUND`）。首次配置懒建「未归属」系统项目（`projects.system=true`，不可被建 Key 选择 → `400 PROJECT_NOT_SELECTABLE`）。凭证被项目授权引用时响应带 `warning`（建议专用凭证，不阻断）。`DELETE` 仅删策略（桶项目保留供历史用量引用）。GET 未配置返回 `{configured:false}`。审计 `UNATTRIBUTED_POLICY_SET/CLEARED`，变更即刷快照。 |
| `GET/POST /api/v1/admin/projects/{id}/repositories`、`DELETE /{mappingId}` | **CAA Project Registry（V56，#639）**：仓库→项目映射。`repoKey` 接受 `github.com/acme/rocket` / `https://github.com/acme/rocket(.git)` / `git@github.com:acme/rocket.git` / 裸 `acme/rocket`（默认 github.com），统一规范化为小写 `host/owner/repo`；租户内唯一，重复 → `409 REPO_KEY_TAKEN`；格式非法 → `400 REPO_KEY_INVALID`；项目不存在 → `404 PROJECT_NOT_FOUND`；删除不存在 → `404 REPOSITORY_NOT_FOUND`。写操作审计 `REPOSITORY_ADD`/`REPOSITORY_REMOVE`。Agent 经 §7 的 `/v1/context-registry` 消费 |
| `GET/POST /api/v1/admin/grants` | Grant 列表/创建（`projectId`×`providerProductId`×`credentialId` + `models[]`；重复 → 409 `GRANT_EXISTS`；凭证订阅产品与声明产品不一致 → 400 `GRANT_CREDENTIAL_PRODUCT_MISMATCH`（数据库触发器同约束兜底）；`models[]` 必须存在于该产品 `model_catalog` → 否则 400 `MODEL_NOT_IN_CATALOG`） |
| `GET/POST /api/v1/admin/grants/{id}/models`、`DELETE /{id}` | 模型范围查询/替换（替换同样校验目录，400 `MODEL_NOT_IN_CATALOG`）；禁用 Grant |

错误码：`USER_NOT_FOUND`/`TEAM_NOT_FOUND`/`PROJECT_NOT_FOUND`/`GRANT_NOT_FOUND`（404）、`USERNAME_TAKEN`/`PROJECT_CODE_TAKEN`/`GRANT_EXISTS`（409）、`USERNAME_INVALID`/`USER_UPDATE_EMPTY`/`DISPLAY_NAME_INVALID`（400）、`ADMIN_NOT_DISABLEABLE`（409）。所有写操作写审计事件（`USER_CREATE`/`USER_UPDATE`/`USER_PASSWORD_RESET`/`USER_SESSIONS_REVOKED`/`TEAM_*`/`PROJECT_*`/`GRANT_*`）。

服务与集成族写操作（#315，对齐腾讯操作记录资源类型）：`CONSUMER_CREATE/DISABLE/JWT_KEY_SET/JWT_KEY_REMOVED`、
`AGENT_CREATE/DISABLE`、`SERVICE_CREATE/DISABLE`、`MCP_SERVICE_CREATE/STATUS/HEALTH_UPDATE`、
`MCP_TOOL_CREATE/IMPORT/STATUS/REVISION_PUBLISH/REVISION_ACTIVATE`、`SKILL_UPLOAD/ARCHIVE/ACCESS`
（targetType 与 action 前缀同名；摘要只含名称/状态/计数类元数据，永不含明文密钥、PEM 或包体）。

审计覆盖第二批（#324）：`ALERT_RULE_CREATE/UPDATE/DELETE`、`WEBHOOK_CREATE/UPDATE/DELETE`（摘要含
name 与 url host，**secret 永不入摘要**）、`BUDGET_PUT/DELETE`（projectId/month/amount）、`CONFIG_PUT/DELETE`
（group/key，**value 永不入摘要**）、`MODEL_CATALOG_ADD/DELETE_MANUAL`（productId/modelId）。
**机器面归属（#324）**：开放管理 API 调用同一服务时，actor=发行管理员，摘要附 `via: admin-api:<密钥名>`
（F60 先例的 `adminApiKeyIssuerId`/`adminApiKeyName` 请求属性）；人类会话 actor=操作用户。

### 5.0b 供应商产品与 Plan（G5.3）

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/provider-products` | 产品实例列表（供应商名、productCode、协议、Base URL host、实现状态、余额权威级别） |
| `GET /api/v1/admin/provider-products/{id}` | 产品详情 |
| `GET /api/v1/admin/provider-products/providers` | 供应商列表 |
| `GET /api/v1/admin/subscriptions` / `/{id}` | 订阅列表/详情（含产品名） |
| `POST /api/v1/admin/subscriptions` | 创建（`providerProductId`/`name`/`billingMode`/`planScope`/价格/配额） |
| `PATCH /api/v1/admin/subscriptions/{id}` | 更新（价格/币种/配额/状态） |
| `GET /api/v1/admin/subscriptions/{id}/seats` | 席位列表（含分配用户） |
| `POST /api/v1/admin/subscriptions/{id}/seats` | 创建席位（`externalSeatRef`/`displayName`/`assignedUserId`） |
| `PATCH /api/v1/admin/subscriptions/{id}/seats/{seatId}` | 分配/释放/禁用席位 |

错误码：`PRODUCT_NOT_FOUND`（404）、`SUBSCRIPTION_NOT_FOUND`（404）、`SEAT_NOT_FOUND`（404）。写操作审计 `SUBSCRIPTION_CREATE/UPDATE`、`SEAT_CREATE/UPDATE`。成员 Key（席位凭证）继续由 `/api/v1/admin/credentials` 管理（`seat_id` 关联）。

### 5.1 上游凭证

管理员录入真实供应商凭证并管理其生命周期（G1.6）。真实凭证属于供应商产品订阅，不绑定用户；只有 SYSTEM_ADMIN 可操作。凭证可选绑定订阅下的一个**席位**（`seatId`，团队 Plan 的"每席位独立 Key"拓扑，见 §4 席位）；席位必须属于同一订阅与租户，否则 `404 SEAT_NOT_FOUND`；绑定后掩码视图的 `seatId` 回显归属。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/credentials` | 租户内全部凭证（掩码视图） |
| `GET /api/v1/admin/credentials/{id}` | 凭证元数据 + 完整版本历史（新版本在前） |
| `POST /api/v1/admin/credentials` | 创建：`{ "name", "subscriptionId", "secret", "seatId"? }`，返回 `201` 掩码视图 |
| `POST /api/v1/admin/credentials/{id}/validate` | 测试候选 Secret；不写入数据库 |
| `POST /api/v1/admin/credentials/{id}/rotate` | 原子轮换：新 Secret 成为 ACTIVE，旧版本进入 DRAINING |
| `POST /api/v1/admin/credentials/{id}/disable` | 立即禁用；凭证从路由快照消失 |

创建/轮换响应（掩码视图；`secret` 明文永不出现）：

```json
{
  "id": "0190...",
  "name": "anthropic-main",
  "subscriptionId": "0190...",
  "status": "ACTIVE",
  "activeVersionId": "0190...",
  "fingerprintPrefix": "a1b2c3d4e5f6a7b8",
  "lastValidatedAt": null,
  "lastValidationError": null,
  "version": 1,
  "createdAt": "2026-07-17T05:00:00Z",
  "updatedAt": "2026-07-17T05:00:00Z"
}
```

验证响应：

```json
{ "matchesActive": true, "message": null, "providerStatus": "VALID", "providerMessage": null, "checkedAt": "2026-08-31T00:00:00Z" }
```

`providerStatus`（候选与生效版本一致时执行真实供应商探活；不一致或不适用时为 `NOT_CHECKED`）：

| 值 | 含义 |
|---|---|
| `VALID` | 供应商接受了该 Key（如 2xx 探活） |
| `REJECTED` | 供应商拒绝（401/403） |
| `UNREACHABLE` | 供应商调用失败或超时（10s） |
| `NOT_CHECKED` | 无适配器/Base URL，或候选与生效版本不一致 |

探活使用候选 Secret 直连供应商（适配器 `validateCredential`），失败不阻塞校验；供应商响应不在日志与审计中保留正文。

安全规则：

- Secret 只接受明文输入；持久化前以 AES-256-GCM 加密（AAD 绑定 tenant + credential），数据库、响应与审计只保留 SHA-256 指纹和 `fingerprintPrefix`（前 8 字节 hex）。明文与完整指纹永不回显。
- `validate` 是纯检查：格式非法返回 `400 CREDENTIAL_INVALID`；格式合法时按 SHA-256 指纹与当前 ACTIVE 版本比对（不解密、不暴露明文），返回 `matchesActive`。任何情况下不写数据库。供应商侧校验接缝（适配器 `validateCredential` + `ProviderClient`）已随 G3.1 落地，管理端点接线到真实供应商 API 属 G4.x（需解密 + 出网，标注 `WAITING_FOR_CREDENTIAL` 联调）。
- 轮换是单事务原子操作：持有凭证行锁（`SELECT ... FOR UPDATE` 串行化并发生命周期变更），先把当前 ACTIVE 版本降级为 DRAINING（`retiredAt = now + miqrokey.credential-drain-grace`，默认 `PT0S`），再插入新 ACTIVE 版本——部分唯一索引 `uq_credential_versions_one_active` 保证任意时刻每个凭证至多一个 ACTIVE 版本。新 Secret 校验失败时整个操作回滚，当前版本不受影响。
- 已降级版本在 `retiredAt` 前保持可解密：请求启动时已解密旧 Secret 的请求可完成（“旧请求可完成”）；路由快照刷新后新请求使用新版本。`PT0S` = 快照刷新后旧版本立即退役。
- `disable` 把凭证置为 `DISABLED` 并降级当前 ACTIVE 版本；网关路由快照只加载 `status = 'ACTIVE'` 的凭证，刷新后该凭证不可路由，新请求干净失败。
- **被 Agent 引用即不可变（#714）**：只要存在 `status = 'ACTIVE'` 的 Agent 绑定该凭证，`rotate` 与 `disable`（本产品没有凭证 DELETE 端点，`disable` 即生命周期终止操作）都被拒绝为 `409 CREDENTIAL_REFERENCED_BY_AGENT`，文案含阻塞的 Agent 名。解除引用的唯一路径是 `POST /api/v1/admin/agents/{id}/disable`；已禁用的 Agent 保留历史绑定（用量归属不变）但不再钉住凭证。检查在行锁与既有状态守卫之后、任何写入之前执行，被拒时数据库与审计均无写入。
- 审计事件 `CREDENTIAL_CREATE` / `CREDENTIAL_ROTATE` / `CREDENTIAL_DISABLE` 只记变更摘要，永不包含明文或完整指纹。

错误码：

| code | HTTP | 场景 |
|---|---|---|
| `SUBSCRIPTION_NOT_FOUND` | 404 | 订阅不存在或不属于本租户 |
| `CREDENTIAL_NOT_FOUND` | 404 | 凭证不存在或不属于本租户（统一 404，防枚举） |
| `CREDENTIAL_INVALID` | 400 | Secret 格式非法（过短/过长/含控制字符） |
| `CREDENTIAL_NOT_ROTATABLE` | 409 | 仅 ACTIVE 可轮换 |
| `CREDENTIAL_NOT_DISABLEABLE` | 409 | 已 DISABLED/INVALID 的凭证不可再禁用 |
| `CREDENTIAL_REFERENCED_BY_AGENT` | 409 | 凭证被 ACTIVE Agent 引用：不可轮换、不可停用（#714）；文案为 `凭证已被 Agent「<name>」引用，不能轮换\|停用；请先停用该 Agent。` |

### 5.1b 加密密钥轮换（主密钥批量重加密，#432）

主密钥轮换的迁移步骤（security.md「后台分批重新加密旧密文」）：把存量密文从旧版本批量重加密到当前
`active-version`。覆盖三处落库密文：上游凭证版本、Webhook 签名密钥、MCP 后端密钥（留痕载体经 Kafka
出站、不落库，不在迁移面）。

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/crypto/reencrypt` | 执行一次批量重加密并返回计数报告；幂等，可重复调用 |

响应（计数与行 id only，永不含密文/明文）：

```json
{
  "activeKeyVersion": "v2",
  "scanned": 7,
  "reencrypted": 6,
  "skipped": 0,
  "failed": 1,
  "remaining": 1,
  "failures": [ { "table": "upstream_credential_versions", "id": "0190..." } ]
}
```

语义：

- 逐行解密（按行内存储的密钥版本 + 原 AAD：tenant + 凭证/端点/服务 id）后以当前 active 版本重加密；
  写回用 CAS（`WHERE id AND key_version = 旧版本`）——并发生命周期写入（凭证轮换、backend-auth 变更）
  不会被覆盖，该行计为 `skipped`。
- 单行失败隔离：解密失败的行保持原样、计入 `failed` 并在 `failures` 中带行 id（上限 50），批量继续。
- `remaining` = 本次执行后仍处于非 active 版本的行数；**`remaining = 0` 是从配置退役旧密钥版本的前置
  条件**——remaining > 0 时移除旧版本会使对应密文解密失败（fail-closed）。
- HMAC 密钥环不可批量迁移：Virtual Key 摘要单向、原值不落库，退役任一 HMAC 版本即让其签发的全部 Key
  失效，只能先重发（见 operations-runbook §11）。
- 审计事件 `CRYPTO_REENCRYPT`（摘要：activeVersion/scanned/reencrypted/skipped/failed/remaining）。

### 5.2 全局用量查询（G4.1）

管理员全局汇总与明细，返回形状与个人端（§4.4/§4.5）一致，但作用域为整个租户，并支持可选维度过滤：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/usage/summary` | 全租户聚合汇总 + 成本 |
| `GET /api/v1/admin/usage/records` | 全租户分页明细，时间倒序 |
| `GET /api/v1/admin/usage/hourly` | 逐小时 Token 表（#634）：小时 × 项目 ×（用户/团队） |

`summary` 参数：`groupBy`（`project` | `virtual_key` | `cache_level` | `day` | `user` | `team` | `model` | `month` | `product`，默认 `project`；I15 新增后三者；2026-09-15 增 `team`，同用户多团队按团队分别计入；#758 增 `product`=供应商产品，label=产品显示名；**#1050**：`day`/`month` 按调用方给定的固定偏移分桶——`tzOffsetMinutes`（分钟，[-1080, 1080]，缺省 0=UTC；与小时报表同一参数与校验，越界 400 `TZ_OFFSET_INVALID`），与会话/服务器 TimeZone 无关）、`from`、`to`（同个人端 93 天窗口规则）、可选过滤 `userId`、`projectId`、`virtualKeyId`、`credentialId`、`subscriptionId`（Plan）、`providerProductId`（供应商产品）、`modelId`。明细与汇总的响应结构、`outcomes`（成功率/平均延迟/平均首字）与富集列口径同 §4.4/§4.5（#758）。

`records` 参数：`from`、`to`、`page`（默认 1）、`size`（默认 50，1–200）及与 `summary` 相同的可选过滤，另支持 `clientIp`（#605，精确匹配调用方地址，用于盗用排查「这个来源都调了什么」）。

`hourly` 参数（#634）：`date`（`YYYY-MM-DD`，默认 `tzOffsetMinutes` 时区下的今天）、`days`（1–7，默认 1，自 `date` 向前连排）、`dimension`（`NONE` | `USER` | `TEAM`，默认 `NONE`；每行 = 小时 × 项目，`USER`/`TEAM` 再乘以所选维度——多团队用户按团队分别计入，口径与 `summary` 的 `team` 维度一致）、`tzOffsetMinutes`（默认 0=UTC；前端传本地偏移，上海=480）、可选过滤 `userId`、`projectId`。返回 `{ date, days, dimension, tzOffsetMinutes, rows: [{ hourStart, projectId, projectLabel, dimensionId, dimensionLabel, requests, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, totalTokens }] }`：`hourStart` 是小时桶边界的 UTC 瞬时（UTC+8 下 14:00 桶 = `06:00Z`，由客户端按本地时区格式化），仅返回有用量的桶，`totalTokens` = 四类 Token 之和（与汇总口径一致）。错误码 `DAYS_INVALID` / `DIMENSION_INVALID` / `DATE_INVALID` / `TZ_OFFSET_INVALID`（400），访问控制与租户隔离同 `summary`/`records`。

过滤语义：

- 过滤维度全部可选、可组合；`virtualKeyId` 等价于把 key 集合收窄到单个 Key。
- 无过滤 = 整个租户；租户隔离由已认证管理员身份决定，不存在跨租户查询形状。
- 管理员可见所有用户/Key 的用量（与个人端严格自见形成对照，是刻意行为）。
- 访问控制：`/api/v1/admin/**` 由拦截器 deny-by-default，仅 `SYSTEM_ADMIN` 可访问；普通用户与匿名请求分别得到 `403` / `401`。
- 明细永不包含 prompt、代码或模型正文。

错误码沿用个人端：`PAGE_INVALID` / `SIZE_INVALID` / `TIME_RANGE_INVALID` / `TIME_RANGE_TOO_WIDE` / `GROUP_BY_INVALID`；非法 UUID 过滤参数返回 `400 PARAM_INVALID`（类型不匹配统一处理，不视为内部错误）。

### 5.3 配额快照（G4.2）

订阅的 Plan/额度状态快照（追加式历史，读取取每作用域最新）：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/subscriptions/{subscriptionId}/quota` | 最新快照（按订阅/席位/凭证作用域各一行） |
| `POST /api/v1/admin/subscriptions/{subscriptionId}/quota/refresh` | 管理端触发刷新：按 ACTIVE 凭证经适配器 `fetchPlanStatus`（官方 API）或本地估算，返回刷新后视图 |

快照字段：`subscriptionId`、`seatId`、`credentialId`、`windowType`（`PERIOD|ROLLING_5H|WEEKLY|MONTHLY|UNKNOWN`）、`total`/`used`/`remaining`、`unit`（`POINTS|TOKENS|REQUESTS|CURRENCY|UNKNOWN`）、`sharedPool`、`source`（`OFFICIAL_API|LOCAL_ESTIMATE|UNAVAILABLE`，对应权威级别，页面必须按此标注）、`syncedAt`、`errorMessage`。

语义：

- `OFFICIAL_API`：适配器官方余额接口返回（当前 DeepSeek / Moonshot 按量）。
- `LOCAL_ESTIMATE`：订阅配置了 `quota_total` + `period_start` 时，用本地 usage（输入+输出 token）相对周期起点估算；与官方值严格区分。
- `UNAVAILABLE`：无官方 API 或刷新失败；`errorMessage` 为脱敏提示（不含 URL/Secret/正文）。
- 刷新为同步管理操作；每次刷新追加新行，历史保留。解密后的 Secret 只存在于调用内（凭证作用域 `ProviderClient`），用后清零。
- 错误码：`SUBSCRIPTION_NOT_FOUND`（404，统一防枚举）。

### 5.4 成本分摊（G4.3）

按订阅周期把用量成本与 Plan 固定成本分摊到项目：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/subscriptions/{subscriptionId}/cost-allocation?from&to` | 已持久化的分摊行（不重算） |
| `POST /api/v1/admin/subscriptions/{subscriptionId}/cost-allocation/allocate?from&to` | 计算并持久化分摊，返回行 |

行字段：`targetType`（当前 `PROJECT`）、`targetId`、`fixedCost`（订阅价按窗口/周期天数比例折算）、`usageCost`（本地 usage × 最新价格快照，每百万 token 单价）、`weightTokens`、`allocatedAmount`、`currency`、`algorithmVersion`（当前 `1`）、`generatedAt`。

语义：

- 固定成本仅 Plan 订阅（非 PAYG）有值，按各项目 Token 权重分摊；无用量时不产出任何行。
- 重复分配同一周期 = 幂等覆盖（唯一键含算法版本）；算法升级另起版本历史。
- 价格取**分配时刻**的最新快照；`currency` 取订阅币种（缺省 USD）。**注意**：按量成本（§5.2 汇总等）自 #710 F21-A 起改读行内冻结价格（`usage_event.price_*`），与本端点的分摊口径不同——分摊切换会牵动"同版本重跑覆盖历史"，属独立决策。
- 错误码：`SUBSCRIPTION_NOT_FOUND`（404）、`TIME_RANGE_INVALID` / `TIME_RANGE_TOO_WIDE`（400，窗口 ≤ 93 天）。

### 5.5 原始记录导出（G4.4）

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/exports?format=CSV\|JSONL&from&to` | 创建导出任务，返回 `202` + 任务（异步执行） |
| `GET /api/v1/admin/exports/{id}` | 任务状态（不含产物字节） |
| `GET /api/v1/admin/exports/{id}/download` | 下载 gzip 产物（`Content-Type: application/gzip`、`X-MiQroKey-SHA256` 校验头） |
| `GET /api/v1/admin/exports?limit` | 最近任务列表 |

- 窗口 ≤ 93 天；产物只含计数与元数据列（见 database-schema `export_tasks`），绝不包含 prompt、代码、Secret 或 Virtual Key 明文。
- **口径标注（2026-09-07）**：CSV 末列 `local_caliber_note` / JSONL 同名字段 = `local-instant`（本地即时记账口径；供应商官方账单通常 T+1 滞后，对账勿以官方值直接核对本地明细），其后按已知的声明追加 token。
- **可对账等级（#330，V41，usage-accounting §11）**：任务完成时按 `provider_request_id` 覆盖度声明
  `reconcileLevel`——`PROVIDER_ID_BACKED`（全行可按 request ID 对账）/ `PARTIAL`（混合）/ `LOCAL_ONLY`
  （全无）；空窗口/历史任务为 null。任务元数据（§5.5 列表与详情、§9 机器面 export-tasks）均带该字段；
  文件内同步：`local-instant;reconcile=provider-id|mixed|local-only`（前缀向后兼容）。
- **含调整等级（#716，V67）**：另一条轴的声明——**这份文件的数字里是否含修正**。`PRESENT`（至少一行被修正过，
  故 `net*` 列才是应对账的那一套）/ `NONE`（没有任何行被改过，`net*` 只是重复观察值）；空窗口/历史任务为 null。
  文件内同步：`;adjustments=present|none`。
  **为什么与 `reconcileLevel` 分开**：两者是互相独立的问题——"能不能按请求 ID 对上账单"与"数字里含不含修正"。
  合进一个枚举就得为每种组合造一个值（`PROVIDER_ID_BACKED_AND_ADJUSTED`…），读起来两边都不是。
  它**按任务声明**而非只在行上标注，是因为消费者希望在读文件**之前**（或只看任务列表时）就知道 `net*` 列要不要看。
- **调整标记（#709）**：CSV 与 JSONL 每行新增 `netInputTokens` / `netOutputTokens` /
  `netCacheReadInputTokens` / `netCacheCreationInputTokens` 与 `adjusted`。既有观察值列**保持原样**，
  净额另列给出；`adjusted` 表示该行**是否存在过修正**——按行数判定，故一笔修正被冲销后
  仍为真（此时净额等于观察值，`adjusted` 是该行唯一还能说明"被改过"的痕迹，#774）。
  净额口径与明细、汇总**共用同一段 SQL 定义**（`UsageAdjustmentSql`），避免三处算法漂移。
- **表头对齐修复（#754）**：CSV 表头此前漏了 `clientIp` 一列——数据行 19 个值而表头只有 18 个名，
  导致**自 `isComplete` 起每一列错位一格**：按列名解析该文件的消费者会拿到错误的值，且不会报错。
  现表头与数据行均由同一份声明的列顺序派生，双份真相已消除；补了**按列名取值**的回归测试
  （旧测试只断言某字符串存在，故长期未发现）。
- 产物保存 24 小时后 `EXPIRED`，下载返回 `410 EXPORT_EXPIRED`；未完成/不存在 → `404 EXPORT_NOT_FOUND`。
- **GC（F06）**：定时回收过窗产物（`miqrokey.cleanup.expired-sweep-ms`，默认 1h）——`SUCCEEDED` 且超过 `expires_at` 的行连同 `file_bytes` 物理删除；清理后下载返回 `404 EXPORT_NOT_FOUND`（410 语义仅在清理前可观测）。`FAILED`/`PENDING` 行保留供运维查看。
- **审计（#1051）**：创建与下载各写一个事件——`EXPORT_CREATE`（摘要含 `format`/`from`/`to`）与
  `EXPORT_DOWNLOAD`（摘要含 `format`/`rows`/`bytes`/`sha256`，即"谁取走了哪一份明细、多少行、校验和多少"）。
  会话面 actor=当前用户，开放管理 API 面 actor=发行管理员并附 `via: admin-api:<密钥名>`（机器面归属规则见 §5.0 的 **#324** 条目）。
  **被拒绝的下载（未完成/过期 → 410）不记事件**：产物字节没有离开控制面，该 action 的语义是"产物已交付"。
  `GET /{id}` 与列表为只读，不写审计。
- 错误码：`TIME_RANGE_INVALID` / `TIME_RANGE_TOO_WIDE`（400）。

### 5.6 用量删除（G4.4）

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/usage-deletions/preview?from&to` | 干跑计数 |
| `POST /api/v1/admin/usage-deletions?from&to` | 创建删除请求；一次性确认 token 只在本次响应出现 |
| `POST /api/v1/admin/usage-deletions/{id}/confirm` | 携带 token 确认并执行永久删除 |
| `GET /api/v1/admin/usage-deletions?limit` | 最近请求列表（永不返回 token） |

- 删除是物理且永久的（无软删除）；执行后写 `USAGE_DELETE` 审计事件，审计链本身永不删除。
- token 仅存 SHA-256 哈希；错误 token → `403 DELETION_TOKEN_INVALID`；确认窗口 1 小时 → `410 DELETION_EXPIRED`；重复确认 → `409 DELETION_NOT_CONFIRMABLE`。
- **GC（F06）**：定时物理清理过期删除请求（同调度属性）——`PENDING_CONFIRMATION`/`CONFIRMED`/`EXPIRED` 且超过 `expires_at` 的行被删除；`EXECUTED` 行**永久保留**（执行审计，与 G4.4「请求本身与审计链保留」一致）。
- 窗口 ≤ 93 天；`TIME_RANGE_INVALID` / `TIME_RANGE_TOO_WIDE`（400）。

### 5.6b 用量调整（#709 / F20）

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/usage-adjustments` | 追加一笔调整（修正或反向行）；`201` + 记录 |
| `GET /api/v1/admin/usage-adjustments?gatewayRequestId` | 某笔用量记录的调整台账，按录入时间正序 |

- **追加型，绝不覆盖原始事实**：`usage_event` 永不被改写；修正以新行追加，纠错以**反向行**（`reversalOfId`）追加，且不允许"反向的反向"。同一笔修正**至多被反向一次**——净额是 `观测值 + Σ全部增减量`，第二次反向减掉的是一笔已经不存在的修正，会把净额推到观测值**之上**（500 观测 → 反向一次回到 500 → 再反向变成 700），因此被拒绝而不是接受。
- 请求体：`gatewayRequestId`（目标用量记录的请求 ID）+ 至少一个非 0 的 `inputTokensDelta` / `outputTokensDelta` / `cacheReadTokensDelta` / `cacheCreationTokensDelta`（可负）+ `reason`（必填）。带 `reversalOfId` 时按被撤销行取反并**忽略**请求里的增减量，使撤销不可能与被撤销内容不一致。
- **幂等**：请求体可选 `idempotencyKey`，落在 `(tenant_id, idempotency_key)` 部分唯一索引上——重试返回已记录的行，不重复入账。**这与 §1 中"预留、当前未实现"的 `Idempotency-Key` 请求头是两套东西**：该请求头仍未实现，本端点用的是请求体内的自然键。
- 错误码：`ADJUSTMENT_EMPTY`（400，未给或全零）、`ADJUSTMENT_WOULD_GO_NEGATIVE`（400，调整后某维度为负）、`ADJUSTMENT_TARGET_HAS_NO_USAGE`（400，缓存命中行不承载用量）、`REVERSAL_TARGET_MISMATCH` / `REVERSAL_OF_REVERSAL` / `ADJUSTMENT_ALREADY_REVERSED`（400，该修正已被反向过，请改为登记一笔新的调整）、`USAGE_EVENT_NOT_FOUND` / `ADJUSTMENT_NOT_FOUND`（404，租户内不可区分他租户）。
- **口径**：调整计入**财务/报告口径**（明细、汇总、计费、导出）；**配额判定仍只读 `usage_event`**——财务更正不得追溯改写运行时控制的历史结果。四层语义见 database-schema §6。
- **当前范围**：读取路径**已接入**——明细净额列与汇总口径（#753）、导出净额列与行级 `adjusted`（#755）、以净额为准的控制台记录表（#773）；
  本节的"含调整"在导出侧另由**任务级`adjustmentLevel`**声明（#716）。`amount_delta` 金额维度表结构已备但**未开放写入**——目前可调整的只有 token 维度。
- 写 `USAGE_ADJUSTMENT_CREATED` / `USAGE_ADJUSTMENT_REVERSED` 审计（操作人填写的原因文本按 JSON 转义）。

### 5.6c 用量价格基座回填（#710 / F21-A）

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/usage-price-backfill?from&to` | 按各行**自己的 occurred_at** 的价目盖章；返回各结果计数 |

- **动机**：成本原先按**查询时刻**的最新价目现算，所以改一次价目，历史报表金额跟着变。本端点把「这笔 token 当时依据什么价格计算」冻结到行上。
- 取值：`price_snapshot` 中 `effective_from <= 该行 occurred_at` 的最新一行（同 `effective_from` 由 `id DESC` 做确定性 tie-break）。**不是按回填时刻**——否则会造出「看起来是历史快照、实际是延迟快照」的假象。
- 返回 `{scanned, complete, partial, unavailable, baseCostFilled, reclassified}`：`COMPLETE`=四维齐全、`PARTIAL`=部分维度有价、`UNAVAILABLE`=已评估但事件发生时无可查价格。
- **`UNAVAILABLE` 的行价格列保持 NULL，不写 0**——「价格未知」与「免费」是不同的审计事实；静默写 0 会低估历史支出。
- **盖章幂等**：盖章只处理 `price_status IS NULL`（尚未评估）的行。**价格列与金额永不改写**——对已评估的行，重跑不会重新定价。窗口 ≤ 93 天，大范围可分次覆盖。
- **同时补写 `base_cost_amount`（#771）**：`baseCostFilled` = 本次为「已盖章但缺冻结金额」的行补上的条数（V66 之前完成回填的库，其历史行金额全为 NULL，而盖章通道不会重选它们）。
  补写**只从该行已冻结的 `price_*` 列派生**——不查价目、不改 `price_status`、不动金额，因此只能**补全**、不能**修订**。
- **同时重算派生标签（#777）**：`reclassified` = 本次把「按现行判据已过时」的 `price_status` 纠正过来的条数。
  判据会演进（#765 把「按价格是否可得」改成「按该行是否**用到**该维度」），而旧章会一直留在行上——那批行既不是"另一种口径"，也不带判据版本，读者无从分辨，**就是错数据**。
  本趟从该行**已冻结的 `price_*` 列**重算，**不改价格列、不动金额**：**标签是派生、金额是事实**，这是它与上一条只做"补全"的分界。
  > 本条**取代**此前那句无条件的「已定状态的行永不重评」。被保护的不变式是**不重估价格、不移动金额**，不是「标签不可纠正」。
- **定时收敛**：`miqrokey.usage-price-reconcile.enabled`（默认关）开启后每 `cycle-ms` 扫最近 48 小时，让上述两条无需人工记得跑端点。
- 写 `USAGE_PRICE_BACKFILL` 审计（含各项计数）；定时通道以**无操作人**（系统发起）记同一条目。
- 错误码：`TIME_RANGE_INVALID` / `TIME_RANGE_TOO_WIDE`（400）。
- **不改变任何上报数字**：读取路径走自己的 as-of 判定，这三趟只补齐/纠正**存储列**，不重估价格、不动金额。

### 5.7 Webhook 端点（G4.5）

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/webhooks` | 创建（`name`/`url`/`secret`/`timeoutMs`）；URL 经 SSRF 门控，Secret 加密存储且永不返回 |
| `GET /api/v1/admin/webhooks` / `/{id}` | 列表/详情（无 Secret） |
| `PATCH /api/v1/admin/webhooks/{id}` | 更新（name/enabled/timeoutMs） |
| `DELETE /api/v1/admin/webhooks/{id}` | 删除。**I21 删除前置依赖检查**（腾讯模型 API 删除语义）：仍被告警规则引用的端点**不再静默脱钩**（原 SET NULL 会让规则悄悄失去投递目标），返回 `409 RESOURCE_IN_USE` + problem 体附 `dependencies: [{type:"ALERT_RULE", id, name, detail:"已启用\|已停用"}]`；先删除或改配这些规则后再删端点。机器面（`/admin-api/webhooks/{id}`）同语义 |
| `POST /api/v1/admin/webhooks/{id}/test` | 发送 HMAC 签名测试载荷，返回上游 HTTP 状态或脱敏错误 |
| `GET /api/v1/admin/webhooks/{id}/deliveries` | 投递历史 |

投递签名：`X-MiQroKey-Signature: sha256=<HMAC-SHA256(secret, payload) hex>`，payload 为事件 JSON（eventId/ruleId/type/value/occurredAt）。错误码：`WEBHOOK_URL_REJECTED`（400，SSRF 门控）、`WEBHOOK_NOT_FOUND`（404）。

**字段约束（PH23）**：创建时 `name` 必填且非空白、`≤200`（列宽 `varchar(200)`）；`url` 必填且非空白、`≤500`（列宽 `varchar(500)`）；`secret` 必填且非空白；`timeoutMs` 缺省 `5000`，给定时须落在 `1000..600000` ms（与 MCP 上游超时同域，§5.11）。PATCH 为部分更新：缺省字段保持原值，`name` 出现即不得为空白、`≤200`，`timeoutMs` 出现即须落在同一区间。违反者一律 `400 VALIDATION_FAILED`（含 `fieldErrors`），不再以 `409 RESOURCE_CONFLICT`（NOT NULL/长度违约）或 `500` 的形式漏出。

### 5.8 告警规则（G4.5/G8.3）

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/alert-rules` | 创建（`name`/`type`/`threshold`/`dedupeMinutes`/`webhookEndpointId`/`scopeJson`） |
| `GET /api/v1/admin/alert-rules` / `/{id}` | 列表/详情 |
| `PATCH /api/v1/admin/alert-rules/{id}` | 更新（含 enabled、scopeJson） |
| `DELETE /api/v1/admin/alert-rules/{id}` | 删除 |

规则类型：`USAGE_MISSING_RATE`（1h 内 usage_missing 占比）、`UPSTREAM_ERROR_RATE`（1h 内非 2xx 占比）、`BALANCE_UNAVAILABLE`（1h 内 UNAVAILABLE 配额快照数）、`USAGE_SURGE`（当前 1h 事件数 / 前一 1h 比率）、**`USAGE_QUEUE_SATURATION`**（F07/#245，V60：网关用量队列近 1h **丢失的事件条数**——绝对值计数，不是比例。事实由网关在队列饱和丢弃时按窗口写 `gateway_queue_signal`（仅 `dropped > 0` 才写行，零丢弃不写行也不触发）；队列是全进程唯一的，故事实固定承载于默认 seed 租户，**只有该租户的规则能评估到**：其他租户的规则聚合到零行，`COALESCE(SUM(dropped), 0)` 恒为 `0`，而评估为「`value >= threshold` 才触发」，故其他租户的规则在**正阈值**下恒不触发，也读不到任何其他租户的数字；阈值 `<= 0`（服务端目前不校验）会在每个去重窗口以 `value = 0` 触发一次，属退化配置，与其余计数型指标行为一致。阈值示例：`1` = 1 小时内丢 1 条即告警）、**`UPSTREAM_RATE_LIMITED`**（ADR-0026 选项 D/#706，V71：近 1h **上游返回 429 的条数**，计数而非比例——比例已在 `UPSTREAM_ERROR_RATE` 里，而「上游在限流」与「上游在故障」是两类事故、处置不同；只统计上游真的答了 429 的请求，**网关自身因配额拒绝的请求不触达上游、无上游状态码，不计入**。阈值示例：`20` = 1 小时内被上游限流 20 次即告警）、**`KEY_REQUEST_RATE`**（ADR-0026 选项 D/#706，V71：近 1h **单把密钥的最高请求条数**（`GROUP BY virtual_key_id` 取最大，平手按键 id 定序以保证确定性）——租户级 `USAGE_SURGE` 说不出「是谁在猛打」，而这条信号只有在**可归因**时才可行动：触发事件在 `payload_json` 里带该密钥的 `keyId`/`keyName`/`requests`（随事件持久化，重试投递时按存储的 payload 重放）。阈值示例：`1000` = 任意单把密钥 1 小时内超 1000 次即告警。两者的 per-key 维度都在 **SQL 聚合**里，不做指标标签（高基数红线，`GatewayMetricsFilter`），评估仍在控制面、热路径零改动（ADR-0026 §6 的约束）、**`BUDGET_THRESHOLD`**（项目当月预算水位 %，`scopeJson: {"projectId": "…"}` 必填且项目需存在，否则 `400 SCOPE_INVALID`）、**`QUOTA_THRESHOLD`**（配额规则当前窗口水位 %，`scopeJson: {"quotaRuleId": "…"}` 必填且配额规则需存在，否则 `400 SCOPE_INVALID`；规则停用即不评估）。评估周期 `miqrokey.alerts.evaluation-interval-ms`（默认 5min）；密钥到期事件型 `ADMIN_API_KEY_EXPIRING`（默认关——需管理员建规则；按 key + 日期去重；检查周期 `miqrokey.alerts.admin-key-expiry-interval-ms` 默认 6h）；`BUDGET_THRESHOLD` 按（规则 × 月份）、`QUOTA_THRESHOLD` 按（规则 × 配额重置窗口，日/周/月随规则周期）去重（同窗口仅告警一次），其余按（规则 × 小时桶）去重；仅首个事件触发投递；投递失败指数退避重试最多 3 次。错误码：`ALERT_RULE_NOT_FOUND`（404）、`ALERT_TYPE_INVALID`（400）、`SCOPE_INVALID`（400）。

**字段约束（PH23）**：创建时 `name` 必填且非空白、`≤200`（列宽 `varchar(200)`）；`threshold` 必填，整数位 `≤6`、小数位 `≤6`（列宽 `numeric(12,6)`）——**故意不设下界**，`threshold <= 0` 仍是文档允许的退化配置（见上）；`dedupeMinutes` 缺省 `60`，给定时须 `≥1`（0 与负数被拒；管理页本身已把 0 归一为默认值）。PATCH 为部分更新：缺省字段保持原值，`name` 出现即不得为空白、`≤200`，`threshold` 出现即须符合同一精度域，`dedupeMinutes` 出现即须 `≥1`。违反者一律 `400 VALIDATION_FAILED`（含 `fieldErrors`），不再以 `409 RESOURCE_CONFLICT` 的形式漏出。`type` 仍按规则类型目录在服务端校验。

**事件驱动类型（F03，V27）**：`MODEL_APPROVAL_SUBMITTED` / `MODEL_APPROVAL_APPROVED` / `MODEL_APPROVAL_REJECTED` ——模型审批流的即时通知（提交→订阅方、通过/驳回→申请人侧），**不参与周期评估**：审批工作流在状态迁移瞬间直接触发（`AlertEventDispatcher` 复用同一投递/签名/退避重试机制）。语义：
- 阈值/scope 不适用（创建阈值恒发送 `1`，服务端事件 value 固定为 1 = 一次发生；无需 scopeJson）。
- 事件去重 = 规则 × `type:approvalId`——同一申请的同一次迁移只通知一次（申请本身只能迁移一次，天然唯一）。
- **Webhook payload**（HMAC 签名同既有告警，`X-MiQroKey-Signature: sha256=…`）：信封（eventId/ruleId/type/value/occurredAt）+ 明细字段：`approvalId`、`modelId`、`status`（PENDING/APPROVED/REJECTED）、`username`/`requesterName`（申请人）、`keyName`/`keyDisplay`（申请 Key 展示）、`reason`（提交理由，提交事件）、`reviewNote`（评审意见，结果事件）、`autoApproved`（白名单自动批准时 true）。纯元数据，无正文/密钥。明细随事件存 `alert_events.payload_json`，重试投递时原样带上。
- 无端点的规则仅记录事件；规则停用即不通知；白名单自动批准在同一次提交里触发 SUBMITTED + APPROVED 两个事件。

### 5.9 模型单价（G7.2）

按（供应商产品、模型、Token 类型）三元组维护每百万 Token 单价，驱动成本计算。单价是不可变快照：修改即追加新快照，历史成本不重算（与官方控制台「修改不追溯」语义一致）。价格是全局目录数据，不租户隔离；端点仍 SYSTEM_ADMIN-only。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/prices` | 每个三元组的最新生效单价列表 |
| `POST /api/v1/admin/prices` | 追加单价快照：`{ "providerProductId", "modelId", "tokenType", "currency", "unitPrice", "source" }`，返回 `201` |
| `POST /api/v1/admin/prices/sync` | 从公开价格源同步（#585）：拉取 OpenRouter 模型索引（USD/token），按 `MIQROKEY_PRICE_SYNC_USD_CNY_RATE` 换算为 CNY/1M 写入快照 |

快照字段：`id`、`providerProductId`、`modelId`、`tokenType`（`INPUT`/`OUTPUT`/`CACHE_READ`/`CACHE_CREATION`）、`currency`、`unitPrice`（BigDecimal，每 1M Tokens）、`effectiveFrom`、`source`（`MANUAL`/`OFFICIAL`）、`createdBy`、`createdAt`。

**同步语义（#585）**：只处理 PAYG 计费且 product_code 命中编译期映射表的产品（deepseek-payg-api→deepseek、moonshot-payg-api→moonshotai、zhipu-payg-api→z-ai、minimax-payg-api→minimax、aliyun-payg-api→qwen、baidu-payg-api→baidu、volcengine-payg-api→volcengine）；模型只在该产品 `model_catalog` ACTIVE 行内匹配（后缀精确匹配，另有少量别名表如 deepseek-flash→deepseek-v4.1-flash）；价格源的 `:batch`/`:free` 变体不参与；与最新快照一致的写入被跳过（未变化计数）。写入快照 `source=OFFICIAL`、`currency=CNY`，**会覆盖同键人工价的最新值**（快照 append-only，历史仍在）。成功才写（含 `PRICE_SYNC` 审计；失败 `502 PRICE_SYNC_FAILED` + `PRICE_SYNC_FAILED` 审计，零写入）。响应报告：`{ source, trigger, usdCnyRate, written, unchanged, conflicts:[{productCode, modelId, tokenType, manualPrice, officialPrice}], unmatched:[{productCode, modelId}], skippedProducts, syncedAt }`（`trigger` = `manual`）。分时价（如 DeepSeek 峰谷）暂取标准/高峰价，闲时折扣为已知缺口。

**自动同步（#708，F08）**：`MIQROKEY_PRICE_SYNC_AUTO_ENABLED=true` 时 `PriceSyncScheduler` 按 `MIQROKEY_PRICE_SYNC_AUTO_CYCLE_MS`（默认 24h，fixedDelay）跑同一条管道（`trigger=scheduled`，审计以种子租户 + 空 actor 记录，写入快照 `createdBy=null`）。与手动端点唯一的语义差异：**同键最新快照为 `MANUAL` 且价格不同时保留人工价**，该报价不写入、计入 `conflicts` 并在审计摘要里报数——无人值守任务不覆盖人工录入；人工触发仍以显式点击为准（覆盖）。拉取失败零写入，且不静默：`PRICE_SYNC_FAILED` 审计事件 + 调度器 `ERROR` 日志 + `miqrokey_control_price_sync_auto_total{result=failure}` 计数，本轮异常被吞掉以保证下一轮照常触发。

错误码：

| code | HTTP | 场景 |
|---|---|---|
| `PRODUCT_NOT_FOUND` | 404 | 供应商产品不存在 |
| `PARAM_INVALID` | 400 | tokenType 非法或参数校验失败 |
| `PRICE_SYNC_FAILED` | 502 | 价格源不可达/非 200/解析失败/超限（已脱敏，零写入） |

### 5.10 外部系统计费通道与 API 消费者（G8.1，ADR-0010）

平台等外部系统通过独立 API 通道查询计费数据，与门户会话认证并存。

**到期语义（#322，V39）**：`expiresAt` 缺省 = 永不过期（存量兼容）。到期（`now >= expires_at`）后凭据在**所有通道**
静默失效（401，与未知 Key 同形——不留「曾有效」信息）；管理列表仍展示该行与到期时间供审计；更新期限=重建消费者。
可选告警规则类型 `CONSUMER_KEY_EXPIRING`（默认关，规则级 opt-in）：≤7 天到期消费者每（消费者×天）至多一条事件。

**能力作用域（#316，V37）**：消费者可裁剪到预设通道；`capabilities` NULL = 全量（存量兼容），强制层按通道
校验（fail-closed）：计费通道缺 `billing:read` → `403 CONSUMER_SCOPE_DENIED`；网关 MCP 数据面缺 `mcp:call`
→ `403 consumer_scope_denied`（error 信封）；与 ACL/白名单判断正交。通道变更即时生效（路由快照刷新）。

**API 消费者**（管理员管理）：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/api-consumers` | 消费者列表（掩码视图 + JWT 公钥指纹） |
| `POST /api/v1/admin/api-consumers` | 创建：`{ "name", "expiresAt"? }`（ISO-8601 时刻，必须为将来；#322） → `201`，返回一次性 API Key（明文仅此一次）；非法到期 `400 CONSUMER_EXPIRES_INVALID` |
| `POST /api/v1/admin/api-consumers/{id}/disable` | 立即吊销（禁用的 Key 即刻失效） |
| `PUT /api/v1/admin/api-consumers/{id}/jwt-key` | 设置/轮换 JWT 验签公钥：`{ "publicKeyPem" }`（RSA PEM SubjectPublicKeyInfo）→ 返回带 `jwtKeyFingerprint` 的视图；非法 PEM → `400 JWT_KEY_INVALID` |
| `DELETE /api/v1/admin/api-consumers/{id}/jwt-key` | 移除公钥（JWT 认证立即失效） |
| `PATCH /api/v1/admin/api-consumers/{id}/scope` | 替换能力作用域（#316）：body `{"capabilities":[…]}`，`null`（缺省）= 全量、`[]` = 无通道；
| `GET /api/v1/admin/api-consumers/{id}/activity?hours=24` | 最近调用概览（#338，I5）：`mcp_access_log` 窗口聚合——`totalCalls/forwarded/denied（被拒：ACL/工具不可用/信封非法）/failed（上游失败/熔断）`、`lastCallAt`、`topTools`/`topServices`（各 ≤5）；`hours` ∈ [1,168]（越界 400 `PARAM_INVALID`）；消费者不存在/跨租户 404 `CONSUMER_NOT_FOUND`；空窗口返回零值视图；401/404 无可信身份的请求按 V29 约定不入日志、不计入 |
  取值限 `billing:read`/`mcp:call` 且不得重复，未知码 → `400 CONSUMER_SCOPE_INVALID`；审计 `CONSUMER_SCOPE_UPDATE`（from/to） |

**计费查询**（API Key 或管理员 session 认证）：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/billing/summary?from&to&groupBy` | 全租户用量/成本汇总 |
| `GET /api/v1/billing/records?from&to&page&size` | 全租户分页明细 |
| `GET /api/v1/billing/quota` | 全租户配额状态：按订阅分组的最近快照 |

**`GET /api/v1/billing/quota` 响应**（按订阅名排序；无快照的订阅以空列表出现）：

```json
[
  {
    "subscriptionId": "…",
    "subscriptionName": "DeepSeek PAYG",
    "snapshots": [
      {
        "seatId": null, "credentialId": null,
        "windowType": "PERIOD",
        "total": 1000000, "used": 250000, "remaining": 750000,
        "unit": "TOKENS", "sharedPool": false,
        "source": "LOCAL_ESTIMATE", "syncedAt": "2026-09-01T00:00:00Z"
      }
    ]
  }
]
```

- `source` 为权威级别：`OFFICIAL_API`（适配器官方余额/用量接口）、`LOCAL_ESTIMATE`（按本地用量估算）、`UNAVAILABLE`（产品无官方接口，明确标注未知）
- 外部通道只暴露配额数字与权威级别，不含内部错误消息与 provider 状态载荷（`errorMessage`/`providerStatusJson` 仅管理员面可见）
- API Key 格式 `mqk_api_<8 hex>_<32 hex>`，仅存 SHA-256 哈希；提交方式 `X-API-Key` 或 `Authorization: Bearer mqk_api_…`
- **JWT 凭据（ADR-0011）**：`Authorization: Bearer <jwt>`（非 `mqk_api_` 前缀即按 JWT 处理）——RS256 签名，`sub` = 消费者名称，`exp` 必填且未过期（`nbf` 可选）；网关用消费者配置的 RSA 公钥验签，`X-API-Key` 头只接受 API Key。平台自持私钥签发，公钥经管理 API 一次性配置。
- **同一凭据亦用于 MCP 数据面（#340）**：`/mcpservers/{name}/mcp` 接受消费者 Key（快照摘要）或消费者 JWT（快照携带 PEM，`sub`→消费者名验签）；验签失败/未知 sub/未配公钥 → `401 invalid_api_key`（与未知 Key 同形），随后到期（#322）与 `mcp:call` 作用域（#316）检查与 Key 通道完全一致；`X-API-Key` 头只接受 API Key。
- **入站 SSE 双端点（#356，I11）**：`GET /mcpservers/{name}/sse` 建立**单节点内存会话**（首帧 `endpoint` 事件给出 `POST /mcpservers/{name}/message?sessionId=…`；15s 注释帧保活；容量 256 → `503 session_capacity_exceeded`；空闲 5 分钟由网关切流）。`POST /mcpservers/{name}/message` 的传输级检查直接应答（`401 invalid_api_key`、`403 consumer_scope_denied`、`404 mcp_service_not_found`/`unknown_session`、`403 session_credential_mismatch`），读完 body 即 `202 Accepted`；JSON-RPC 调用随后沿与 `/mcp` **完全同一**的流水线执行（信封→两级 ACL→后端凭据注入→重试/熔断→上游转发），结果写入会话流：上游成功 → `message` 事件（上游响应体逐字节原样；v1 对上游流式 SSE 响应整段聚合为单条事件）；网关拒绝/失败 → `error` 事件（与直连形态同一 problem JSON）。会话仅进程内存（重启即失效）、绑定单一消费者+服务；F15 元数据日志逐行同记。
- 响应仅元数据（时间/模型/Token/成本/配额），无正文
- 错误码：`CONSUMER_NAME_TAKEN`（409）、`CONSUMER_NOT_FOUND`（404）、`CONSUMER_ALREADY_DISABLED`（409）、`JWT_KEY_INVALID`（400）、匿名 401

### 5.11 项目月度预算（G8.2，配额管理）

项目级月度预算：**只预警不阻断**（符合「不因预算阻断」产品决策），水位按当月分摊成本实时计算。对标腾讯消费者配额管理的预警状态（正常/预警/超限）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/budgets?month` | 全部项目当月预算 + 水位（`month` 缺省为当月） |
| `GET /api/v1/admin/projects/{projectId}/budget?month` | 单项目预算 + 水位 |
| `PUT /api/v1/admin/projects/{projectId}/budget` | 创建/更新（按 `(project, month)` upsert）：`{ "month", "amount", "currency"?, "alertThresholdPct"? }` |
| `DELETE /api/v1/admin/projects/{projectId}/budget?month` | 删除（`204`）。**I21 删除前置依赖检查（#1046）**：删除**当月**预算时，若仍有 `BUDGET_THRESHOLD` 告警规则指向该项目，返回 `409 RESOURCE_IN_USE` + `dependencies: [{type:"ALERT_RULE", id, name, detail:"已启用\|已停用"}]`。**依赖是月度的**——`AlertEvaluator.budgetWatermark` 只解析 `YearMonth.now()` 那一个月，所以删过去/将来月份不受影响、照常 `204`（否则历史将永远无法清理）。引用藏在 `alert_rules.scope_json->>'projectId'`（jsonb，无外键），且匹配时对存储值取 `LOWER(...)`：写入侧经 `UUID.fromString` 接受非规范拼写并原样入库，等值匹配会漏 |

**响应 `BudgetView`**：

```json
{
  "projectId": "…", "projectCode": "CORE", "projectName": "Core AI",
  "month": "2026-09", "amount": 5000, "currency": "CNY",
  "alertThresholdPct": 80, "status": "ACTIVE",
  "spent": 123.45, "spentPct": 2.47, "level": "NORMAL"
}
```

- `spent` = 当月分摊成本（usage × 最新单价快照，复用全局用量聚合）；`spentPct` = `spent / amount × 100`
- `level`：`NORMAL`（< 阈值）/ `WARNING`（≥ 阈值且 < 100%）/ `EXCEEDED`（≥ 100%）
- 校验：`month` 格式 `YYYY-MM`（`MONTH_INVALID` 400）；`amount` > 0；`alertThresholdPct` 0–100；项目不存在/跨租户 `PROJECT_NOT_FOUND` 404；无预算 `BUDGET_NOT_FOUND` 404
- 预算表（`budget`，V7）在 V7 已建表，本 Goal 零迁移

### 5.12 SkillHub 技能目录（P2.2/P2.3，Anthropic Agent Skills 格式）

公司内部技能目录：管理员上传 zip 技能包（SKILL.md + 可选 scripts/references/assets），服务端校验后解析 frontmatter 入库；**全部 ACTIVE 技能对登录用户可见，下载按授权**（leader：能看到所有 skill、只下载对应 skill）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/skills?q=&tags=&tags=` | 目录（登录用户可见全部 ACTIVE；`q` ≤60 字符，匹配名称/描述/ID 不区分大小写；`tags` 多选为**与**语义） |
| `GET /api/v1/skills/{id}` | 详情（元数据，无包体） |
| `GET /api/v1/skills/{id}/download` | 下载 zip（授权门禁；公开 = 全员可下） |
| `POST /api/v1/admin/skills?version=1.0.0` | 上传（raw zip body，`Content-Type: application/zip`）；**I14**：重传同名 = 发布下一修订（历史/旧包保留、恢复 ACTIVE，可回滚），新名 = 建技能 + 基线 r1 |
| `GET /api/v1/admin/skills?q=&tags=&tags=` | 管理目录（过滤语义同上） |
| `POST /api/v1/admin/skills/{id}/archive` | 归档（目录隐藏、数据保留、授权保留） |
| `PUT /api/v1/admin/skills/{id}/access` | 整体替换下载授权：`[{"scopeType":"TEAM\|PROJECT","scopeId":"…"}]`；空数组 = 公开 |
| `GET /api/v1/admin/skills/{id}/revisions?limit=` | **I14 版本历史**（新→旧，默认 20/上限 50）：`{revision, version, description, author, license, tags, examples, contentSha256, contentBytes, createdBy, createdAt, activatedAt}`——**只回元数据，不回包体**；`activatedAt` 非空即当前版本 |
| `POST /api/v1/admin/skills/{id}/revisions/{revision}/activate` | **I14 回滚/切换**：激活指定修订（幂等，不产生新版本号），并把该修订的元数据+包体镜像回 `skills`（目录/下载即时生效）；审计 `SKILL_REVISION_ACTIVATE`（发布审计 `SKILL_REVISION_PUBLISH`） |

**格式校验（上传时）**：zip 必须只含一个技能目录（`skill-name/`），含 `SKILL.md`（YAML frontmatter：`name` 必填且为小写 kebab-case、与目录名一致、不含 claude/anthropic 保留词；`description` 必填 ≤ 1024 字符；可选 `author`/`license`/`tags`/`examples`；`tags` ≤5 个 × ≤20 字符（重复去重）；`examples` ≤10 条 × ≤512 字符）。包上限 5MB、条目上限 200、SKILL.md 上限 512KB（防 zip 炸弹——只读 SKILL.md，不解压）。`version` 必填语义化（`\d+\.\d+\.\d+`）。

**下载授权语义**：无 `skill_access` 行 = 公开；有行 = 仅授权 TEAM/PROJECT 成员（及管理员）可下载；非成员 `403 SKILL_DOWNLOAD_FORBIDDEN`；归档技能对目录/详情/下载一律 `404 SKILL_NOT_FOUND`。

**错误码**：`SKILL_NOT_FOUND`（404）、`SKILL_DOWNLOAD_FORBIDDEN`（403）、`VERSION_INVALID`（400）、`SKILL_EMPTY`/`SKILL_TOO_LARGE`/`SKILL_TOO_MANY_ENTRIES`/`SKILL_ZIP_INVALID`/`SKILL_MD_MISSING`/`SKILL_MD_TOO_LARGE`/`SKILL_FRONTMATTER_INVALID`/`SKILL_NAME_INVALID`/`SKILL_NAME_MISMATCH`/`SKILL_DESCRIPTION_INVALID`/`SKILL_TAGS_INVALID`/`SKILL_EXAMPLES_INVALID`/`SKILL_QUERY_INVALID`（400）、`SCOPE_INVALID`（400）。视图（列表/详情）含 `examples` 与 `createdBy`/`createdByName`（创建人姓名，服务端解析）。

### 5.13 Agent 管理（P3.1，对标阿里 AI 网关 Agent 拓扑）

管理智能体资源：Agent 的**出口**绑定一个 ACTIVE 上游凭证（供应商产品由凭证 → 订阅派生，前端无需联动选择）；用量按绑定凭证聚合，形成按 Agent 维度的观测。入口路由（外部访问 Agent）为后续扩展。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/agents` / `/{id}` | 列表/详情（含派生的凭证名与产品名） |
| `POST /api/v1/admin/agents` | 创建：`{ "name", "description"?, "credentialId" }`；凭证必须存在且 ACTIVE（`400 CREDENTIAL_NOT_FOUND`）、重名 `409 AGENT_NAME_TAKEN`、**凭证已被其他 Agent 绑定 `409 AGENT_CREDENTIAL_TAKEN`**（1:1 规则：一个凭证只支持一个 Agent，保证按 Agent 用量可区分） |
| `POST /api/v1/admin/agents/{id}/disable` | 禁用（`409 AGENT_ALREADY_DISABLED` 重复禁用） |
| `POST /api/v1/admin/agents/{id}/enable` | 重新启用（#824；`409 AGENT_ALREADY_ENABLED` 重复启用；审计 `AGENT_ENABLE`）。**不是 `disable` 的镜像**：停用期间凭证可能已被停用，此时拒绝并返回 `409 CREDENTIAL_NOT_ACTIVE`，要求先启用凭证（凭证被**轮换**无害——Agent 绑定的是凭证行而非密文，可直接启用） |
| `PATCH /api/v1/admin/agents/{id}` | 改名/改描述（#824）：`{ "name", "description", "version" }`——**整份可编辑状态 + 乐观锁版本**（非稀疏 patch），`version` 来自上次读取；重名 `409 AGENT_NAME_TAKEN`、版本过期 `409 CONCURRENT_MODIFICATION`；审计 `AGENT_UPDATE` 记 before→after（改名后按 id 反查不到旧名） |
| `DELETE /api/v1/admin/agents/{id}` | **硬删除**（#824，不可恢复）：204。删除同时释放租户内名称与「该凭证 → 唯一 Agent」的名额（`uq_agents_tenant_credential` 不分状态，停用的 Agent 仍占位）。用量与对账**不受影响**——没有任何表引用 `agents`，用量按凭证聚合；审计 `AGENT_DELETE` 的 detail **带名称快照**（行已不存在，仅凭 id 无法还原） |
| `GET /api/v1/admin/agents/{id}/usage?from&to` | 按绑定凭证的用量汇总（请求/Token/成本，默认近 93 天） |

**响应 `AgentView`**：`name`/`description`/`credentialId`/`credentialName`/`providerProductId`/`providerProductName`（派生）/`status`/`createdAt`。

**反向绑定约束（#714）**：创建时对凭证行加锁（`SELECT ... FOR UPDATE`），与 `rotate`/`disable` 的凭证行锁互斥，避免「校验 ACTIVE 通过 → 并发停用」竞态留下绑定到不可路由凭证的 Agent。绑定期间该凭证不可轮换、不可停用（`409 CREDENTIAL_REFERENCED_BY_AGENT`，见 §5）；因此 `disable` 同时是**解除引用**操作，禁用后该凭证恢复可改写。1:1 唯一索引 `uq_agents_tenant_credential` 在任意状态下都生效。

**错误码**：`AGENT_NOT_FOUND`（404）、`AGENT_NAME_TAKEN`（409）、`AGENT_CREDENTIAL_TAKEN`（409）、`AGENT_ALREADY_DISABLED`（409）、`AGENT_ALREADY_ENABLED`（409）、`CONCURRENT_MODIFICATION`（409，改名时版本过期）、`CREDENTIAL_NOT_FOUND`（400）、`CREDENTIAL_NOT_ACTIVE`（409，启用时凭证不可用）。

**生命周期决策（#824 / ADR-0025）**：四条路径选项 A–D 由所有者选定 **D**（`enable` + 改名 + 硬删除）。`list` 仍不做状态过滤（停用与启用同列，前端以状态徽章区分）；「改绑凭证」不在本批范围内（单独议题）。

### 5.14 内部服务注册表（P3.2）

平台组件、MCP 端点等内部服务经网关集成前的注册目录（对标腾讯服务来源）。服务地址必须为 https、不含用户信息/查询参数/片段（镜像上游目标规则）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/services` / `/{id}` | 列表/详情 |
| `POST /api/v1/admin/services` | 注册：`{ "name", "kind"?, "description"?, "baseUrl" }`（`kind` ∈ `HTTP\|MCP\|OTHER`，缺省 `HTTP`） |
| `POST /api/v1/admin/services/{id}/disable` | 禁用（`409 SERVICE_ALREADY_DISABLED` 重复禁用） |
| `POST /api/v1/admin/services/{id}/enable` | 重新启用（#326；`409 SERVICE_ALREADY_ENABLED` 重复启用；审计 `SERVICE_ENABLE`） |
| `POST /api/v1/admin/services/{id}/health-config` | 健康探测配置部分更新（#326，镜像 MCP 端点）：`{ "checkIntervalSeconds"?, "checkTimeoutSeconds"?, "failThreshold"?, "recoverThreshold"?, "checkPath"? }`；审计 `SERVICE_HEALTH_UPDATE` |

**错误码**：`SERVICE_NOT_FOUND`（404）、`SERVICE_NAME_TAKEN`（409）、`SERVICE_ALREADY_DISABLED`（409）、`BASE_URL_INVALID`（400）。

**运行时状态（#326）**：`status`（`ACTIVE|DISABLED`，手动启停，上下线对称为一等操作并经审计）与
`healthStatus`（`UNKNOWN|HEALTHY|UNHEALTHY`，仅探测 ACTIVE 服务：`GET baseUrl + checkPath`，2xx 计健康，
连续失败/成功达 `failThreshold`/`recoverThreshold` 迁移；DISABLED 永不探测，手动停用不被覆盖）正交；
列表/详情返回探测配置与 `healthCheckedAt`。

### 5.15 全局配置中心（P3.3）

网关侧配置中心：分组键值条目，管理员维护，乐观 upsert。**仅限非机密配置**——机密走环境变量/加密凭证体系，不得写入此目录。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/configs?group` | 全部或按分组列出 |
| `PUT /api/v1/admin/configs` | 创建/更新：`{ "group", "key", "value", "description"? }`（按 `(group, key)` upsert） |
| `DELETE /api/v1/admin/configs/{group}/{key}` | 删除（`204`） |

- 名称规则：`[a-zA-Z][a-zA-Z0-9._-]{0,127}`（`CONFIG_NAME_INVALID` 400）；值必填（`CONFIG_VALUE_REQUIRED` 400）
- 错误码：`CONFIG_NOT_FOUND`（404）、`CONFIG_NAME_INVALID`（400）、`CONFIG_VALUE_REQUIRED`（400）

### 5.16 MCP 服务管理（P3.4，对标腾讯 AI 网关 MCP 管理）

MCP Server 注册、手动上下线与健康检查（对齐腾讯「MCP 上下线与健康检查」：下线后健康检查不会自动恢复，需手动上线；健康状态由失败/恢复阈值驱动）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/mcp-services` / `/{id}` | 列表/详情（含健康状态与检查配置） |
| `POST /api/v1/admin/mcp-services` | 注册：`{ "name", "description"?, "endpoint", "transport"?, "checkIntervalSeconds"?, "checkTimeoutSeconds"?, "failThreshold"?, "recoverThreshold"?, "checkPath"?, "upstreamTimeoutMs"? }`（默认 STREAMABLE_HTTP / 30s / 5s / 3 / 1 / `/health` / 60000ms；注册即自动生成 default 路由，见 5.23） |
| `POST /api/v1/admin/mcp-services/{id}/status?status=ONLINE\|OFFLINE` | 手动上下线（重复切换 `409 MCP_STATUS_UNCHANGED`；并发编辑乐观锁竞争 → `409 CONCURRENT_MODIFICATION`，#415） |
| `POST /api/v1/admin/mcp-services/{id}/health-config` | 健康探测配置部分更新：`{ "checkIntervalSeconds"?, "checkTimeoutSeconds"?, "failThreshold"?, "recoverThreshold"?, "checkPath"?, "checkMode"? }`。**#387 探测方式**：`HEALTH_PATH`（默认，GET `endpoint + checkPath` 2xx 健康）| `JSONRPC_INITIALIZE`（标准 MCP 服务无 HTTP 健康路径时使用：POST `endpoint` JSON-RPC 2.0 `initialize` 信封，2xx 且响应体含 `"jsonrpc"` 健康——兼容 SSE 帧包裹；API_KEY 后端自动携带解密 Bearer，凭证不可用 fail-closed）。非法值 `400 MCP_CHECK_MODE_INVALID` |
| `PUT /api/v1/admin/mcp-services/{id}/backend-auth` | 上游后端鉴权（#320，腾讯 raw 03）：body `{"mode":"VISITOR\|API_KEY","secret"?}`——
  `VISITOR` 清除已存密钥；`API_KEY` 必填 `secret`（≤4096）。密钥**只写不读**：任何读面（列表/详情/审计）永不返回；
  存储 AES-GCM 加密（AAD 绑定 tenant+service）；网关向上游注入固定 `Authorization: Bearer <secret>`；变更即时生效（快照刷新）。
  `400 MCP_BACKEND_AUTH_INVALID`；审计 `MCP_SERVICE_BACKEND_AUTH`（摘要含 name+mode，永不含 secret） |
| `PUT /api/v1/admin/mcp-services/{id}/upstream-timeout` | **数据面上游预算（I20，腾讯 raw 03「超时时间」）**：body `{"upstreamTimeoutMs"}`（1000..600000，默认 60000ms）——数据面每次上游尝试的超时；预算耗尽 → **504 `mcp_upstream_timeout`** 错误信封（`mcp_access_log` 记 UPSTREAM_FAILURE/504）。越界 `400 MCP_TIMEOUT_INVALID`；启用慢调用熔断时不得 ≤ 已配置慢阈值（`400 RESILIENCE_SLOW_EXCEEDS_TIMEOUT`）。审计 `MCP_SERVICE_UPSTREAM_TIMEOUT`，即时快照刷新。 |
| `GET /api/v1/admin/mcp-services/{id}/traffic?hours=24` | **被动健康（真实流量视图，#397，矩阵 §3 候选落地）**：`mcp_access_log` 按服务窗口聚合——`totalCalls/forwarded/denied（被拒：ACL/工具不可用/信封非法）/failed（UPSTREAM_FAILURE+CIRCUIT_OPEN，口径同 #338）`、`failureRate`（failed/(forwarded+failed)；无健康相关流量为 null）、`lastCallAt`/`lastFailureAt`（可空）、`topFailingTools`（失败工具 top ≤5）；`hours` ∈ [1,168]（越界 400 `PARAM_INVALID`）；服务不存在/跨租户 404 `MCP_SERVICE_NOT_FOUND`；空窗口零值视图（不 404）；只读、不阻断。 |
| `GET /api/v1/admin/mcp-services/{id}/connection` | **接入信息（#685，腾讯 MCP 接入指引「接入地址」）**：返回 `{ serviceId, name, mcpUrl, sseUrl, authHint }`——由网关 base URL + `/mcpservers/{name}/mcp`（及 `/sse`）生成，与数据面路由逐字一致；`authHint` 仅描述凭据形态（消费者 API Key/JWT + `mcp:call` 作用域），不含任何密钥。注意：ACL 面占用 `/{id}/access`，本端点位于 `/{id}/connection`。 |
| `POST /api/v1/admin/mcp-services/{id}/verify` | **调用验证（#685，腾讯指引第 3 步）**：按服务现有 `checkMode`（HEALTH_PATH / JSONRPC_INITIALIZE）**立即探测一次上游**，返回 `{ serviceId, reachable, checkMode, latencyMs, detail, checkedAt }`——`detail` 为脱敏中文结论（HTTP 码 / 超时 / 连接失败 / 凭证不可用 / JSON-RPC 通过）；**只读**：不写健康遥测（`health_checked_at` 归周期巡检），超时受服务 `checkTimeoutSeconds` 约束；任意状态（含 OFFLINE）可验证。 |

- 接入地址：https、无 userinfo/query/fragment（`MCP_ENDPOINT_INVALID` 400）；重名 `409 MCP_SERVICE_NAME_TAKEN`
- **健康检查**：`McpHealthChecker` 定时（`miqrokey.mcp.health-cycle-ms` 默认 15s）遍历 ONLINE 服务，按各自间隔探测 `endpoint + checkPath`（GET，2xx 计健康）；连续失败达 `failThreshold` → `UNHEALTHY`，连续成功达 `recoverThreshold` → `HEALTHY`；OFFLINE 服务不被探测
- **错误码**：`MCP_SERVICE_NOT_FOUND`（404）、`MCP_SERVICE_NAME_TAKEN`（409）、`MCP_STATUS_UNCHANGED`（409）、`MCP_STATUS_INVALID`（400）、`MCP_ENDPOINT_INVALID`（400）
- **上游后端鉴权语义（#320）**：`VISITOR`（默认）不向上游携带任何凭据；`API_KEY` 由网关按请求解密注入
  `Authorization: Bearer <secret>`（密文随路由快照下发，**明文永不出网关进程**，用后清零）；解密不可用/失败 → 上游零请求、
  `502 backend_auth_unavailable`（fail-closed）。消费者凭据任何情况下不透传给上游。

### 5.17 MCP Tools 管理（P3.5，对标腾讯 AI 网关 Tools 管理）

工具注册在 MCP 服务下，逐个启用/禁用（腾讯 Tools 管理语义）；工具名是 AI Agent 调用该工具的唯一标识。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/mcp-services/{id}/tools` | 服务下工具列表 |
| `POST /api/v1/admin/mcp-services/{id}/tools` | 手动创建：`{ "toolName", "description"?, "method"?, "path" }`（方法默认 GET） |
| `POST /api/v1/admin/mcp-services/{id}/tools/{toolId}/status?status=ENABLED\|DISABLED` | 单个工具启用/禁用（重复切换 `409 TOOL_STATUS_UNCHANGED`） |
| `GET /api/v1/admin/mcp-services/{id}/tools/{toolId}/revisions?limit` | 定义修订历史，新→旧（默认 20、上限 50；**永不裁剪**）；每项含只读 `changedFields`（相邻旧版的字段级差异，`description`/`method`/`path`；基线为空） |
| `POST /api/v1/admin/mcp-services/{id}/tools/{toolId}/revisions` | 发布编辑为新修订（部分编辑：缺省字段沿用当前激活修订值；自动成为生效版并镜像到工具行） |
| `POST /api/v1/admin/mcp-services/{id}/tools/import` | **F17 OpenAPI 批量导入**：body `{"spec": <OpenAPI JSON>}` → `{created, skipped, parseSkips}`（逐项容错：不可派生/重名/不支持方法各自报告，不整体失败；上限 100） |
| `POST /api/v1/admin/mcp-services/{id}/tools/sync?dryRun=` | **tools/list 自动同步（#344，doc 03）**：上游 `POST {endpoint}` `{"jsonrpc":"2.0","id":1,"method":"tools/list"}` → 差量合并 + 逐项报告 `{dryRun, upstreamToolCount, added[], updated[], unchanged, absentUpstream[], skipped[{toolName,reason}]}`；`dryRun=true` 只算不写不审计 |
| `POST /api/v1/admin/mcp-services/{id}/tools/{toolId}/revisions/{revision}/activate` | 激活指定修订 = 回滚/切换生效版（幂等；不产生新版本号） |

- `toolName` 规则：小写字母开头 snake_case（`TOOL_NAME_INVALID` 400）；`path` 必须以 `/` 开头（`TOOL_PATH_INVALID` 400）；同服务重名 `409 TOOL_NAME_TAKEN`；服务不存在 `404 MCP_SERVICE_NOT_FOUND`
- **同步语义（#344）**：新增工具用占位映射 `POST /`（MCP 原生工具无 HTTP 映射）并播种基线修订 1；上游描述变化经 F16 发布下一修订（自动激活并镜像回工具行）；上游未返回的本地工具仅列入 `absentUpstream`（**不自动禁用/删除**）；上游名不合规/重复/缺 name 逐项 `skipped`；有变更时触发一次路由快照刷新；应用阶段记 `MCP_TOOLS_SYNCED` 审计（计数摘要，无正文）
- **同步上游调用（#344）**：`API_KEY` 后端模式注入 `Authorization: Bearer <后端密钥>`（密文服务端读取、明文用后清零、fail-closed，密钥不落日志）；守卫：响应体 ≤2MB、工具数 ≤1000、超时 30s；上游非 2xx / JSON 非法 / JSON-RPC error → `502 TOOLS_SYNC_UPSTREAM_FAILED`（原因脱敏，不含 URL 与密钥）；并发冲突 `409 TOOLS_SYNC_CONFLICT`；需要 initialize 会话握手的上游不在本版范围（直接调用失败会明确报出）
- **错误码**：`TOOL_NOT_FOUND`（404）、`TOOL_NAME_TAKEN`（409）、`TOOL_STATUS_UNCHANGED`（409）、`TOOL_STATUS_INVALID`（400）、`TOOL_NAME_INVALID`（400）、`TOOL_PATH_INVALID`（400）；修订面另增：`TOOL_REVISION_NOT_FOUND`（404）、`TOOL_REVISION_CONFLICT`（409，并发发布）、`TOOL_METHOD_INVALID`（400，发布时 method 校验）；导入面另增：`SPEC_INVALID`（400，缺 paths）、`TOO_MANY_TOOLS`（400，>100 项）

### 5.18 模型审批队列（原始设计文档 §8.2，SYSTEM_ADMIN-only）

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/model-approvals?status=&size=&before=` | 审批队列（keySet 游标分页） |
| `POST /api/v1/admin/model-approvals/{id}/approve` | 通过（`{ "reviewNote"? }`）→ 生效 |
| `POST /api/v1/admin/model-approvals/{id}/reject` | 驳回（`{ "reviewNote"? }`） |

- `status` ∈ `PENDING\|APPROVED\|REJECTED`，缺省返回全部；`size` 默认 20、上限 100；`before` 为上一页 `nextCursor`（不透明，编码 `(created_at, id)`；非法游标 `400 PARAM_INVALID`）。倒序返回 `{ "items": [ModelApprovalView], "nextCursor" }`。
- **通过语义**：写入 `virtual_key_models`（申请 Key）+ 若模型不在 Grant 中先写入 `project_provider_grant_models`（网关按 `key.models ∩ grant.models ∩ model_catalog(ACTIVE)` 三层放行，缺一不可），随后**立即**触发路由快照刷新（不等 30s 定时）。同 Grant 其它 Key 不受影响（各自 Key 快照独立）。
- **批准前复核目录（#506）**：提交与批准两个时点都校验模型在该产品的 `model_catalog` 中有 ACTIVE 行——提交后模型被移出/停用目录时，批准返回 `409 MODEL_NOT_IN_CATALOG`（否则将"批准成功但网关不可见"）。
- 仅 PENDING 可审批：重复审批 `409 ALREADY_REVIEWED`（乐观锁，并发评审只有一个成功）；Key 已吊销/停用 → `409 KEY_NOT_ACTIVE`（含轮换后的旧 Key：申请永远无法生效，提示会指引管理员改为「驳回」）；Grant 已停用 → `409 GRANT_INACTIVE`；不存在 → `404 APPROVAL_NOT_FOUND`。
- 审批/驳回写 `MODEL_APPROVAL_APPROVED` / `MODEL_APPROVAL_REJECTED` 审计（含 reviewNote 长度 ≤ 500 校验）。

### 5.18b 模型目录人工维护（F18，腾讯 raw 5 探测兜底）

官方目录抓取「只写成功」——探测失败时若无人工入口，管理员将无法补录模型 ID。本组端点提供人工兜底：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/models?providerProductId&source` | 目录行列表（source 过滤，如 `MANUAL`） |
| `POST /api/v1/admin/models` | 人工录入：`{providerProductId, modelId, displayName?, contextWindow?, maxOutputTokens?}` → 201（`source=MANUAL`；同产品重名 409） |
| `DELETE /api/v1/admin/models/{rowId}` | 删除 MANUAL 行（OFFICIAL 行拒绝 409） |

- 语义：MANUAL 行是官方探测失败的回退入口，官方刷新永不覆盖/删除（`ON CONFLICT DO NOTHING`）；`status` 默认 ACTIVE，照常参与 `/v1/models` 交集。
- 错误码：`MODEL_ID_INVALID`（400）、`PRODUCT_NOT_FOUND`（404）、`MODEL_ALREADY_IN_CATALOG`（409）、`MODEL_NOT_FOUND`（404）、`MODEL_NOT_MANUAL`（409）。

### 5.18c 模型探测（#346，I4，腾讯 raw 5）

管理面触发官方 `/models` 抓取（与 G2.3 定时管道共用「成功才落库」核心），并把**最近一次探测结果持久化**为可见面：

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/models/probe` | body `{"providerProductId"}` → 解析适配器与首个 ACTIVE 凭证 → 30s 内抓取官方目录：成功落 `model_catalog`（OFFICIAL）并返回 `{providerProductId, productCode, modelCount, probedAt, models[]}`；失败 `502 MODEL_PROBE_FAILED`（原因脱敏，目录不被触碰） |
| `GET /api/v1/admin/models/probe-status?providerProductId=` | 最近探测状态 `{status(SUCCEEDED/FAILED/null), error, modelCount, probedAt}`（未探测全空；V43 列） |
| `POST /api/v1/admin/models/test-run` | 在线调试（#552）：`{providerProductId, modelId, prompt?}`（prompt ≤2000，空=「请回复OK」）→ 以该产品**首个 ACTIVE 凭证**向上游发一条 OpenAI 兼容 chat 调用（`POST {baseUrl}/chat/completions`，max_tokens 256）→ 返回 `{providerProductId, productCode, modelId, httpStatus, latencyMs, content, promptTokens, completionTokens, totalTokens}`。**正文（prompt 与回复）不落库、不入日志**；审计 `MODEL_TEST_RUN`（仅元数据：产品/模型/状态/耗时/token）；失败 502 `MODEL_TEST_RUN_FAILED`（脱敏、含上游状态与截断后的上游错误消息）；无可用凭证 400 `MODEL_TEST_RUN_CREDENTIAL_UNAVAILABLE`；未知产品 404 `MODEL_TEST_RUN_PRODUCT_NOT_FOUND`。SYSTEM_ADMIN-only |

- 语义：探测只是**触发器**；失败沿用「保留最后成功目录」，不覆盖 MANUAL 行、不影响人工配置（doc 05 口径）。
- 凭证：取该产品订阅下**首个 ACTIVE 凭证**（确定性顺序）；无 ACTIVE 凭证 → `400 MODEL_PROBE_CREDENTIAL_UNAVAILABLE`；无适配器 → `400 MODEL_PROBE_ADAPTER_UNAVAILABLE`；产品缺 base URL → `400 MODEL_PROBE_BASE_URL_MISSING`；产品不存在 → `404 MODEL_PROBE_PRODUCT_NOT_FOUND`。
- 审计：`MODEL_CATALOG_PROBE_SUCCEEDED` / `MODEL_CATALOG_PROBE_FAILED`（计数 / 脱敏原因摘要；无 URL 与密钥）。

### 5.19 配额规则（用量配额，platform-middleware roadmap「配额管理」步骤）

用量配额（对齐腾讯消费者配额 / 阿里消费者配额）：**默认只预警**；`action=REJECT` 的规则超限后由网关拒绝请求（#684，ADR-0020）：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/quota-rules` | 全部规则 + 当前窗口水位（读时计算） |
| `PUT /api/v1/admin/quota-rules` | 新增/更新规则（`(scopeType, scopeId, metric, period)` 为自然键，重复 PUT 原地编辑） |
| `DELETE /api/v1/admin/quota-rules/{id}` | 删除规则（`404 QUOTA_RULE_NOT_FOUND`）。**I21 删除前置依赖检查**：仍被 `QUOTA_THRESHOLD` 告警规则引用的规则返回 `409 RESOURCE_IN_USE` + problem 体附 `dependencies: [{type:"ALERT_RULE", id, name, detail:"已启用\|已停用"}]`；先删除或改配这些规则后再删。该引用藏在 `alert_rules.scope_json->>'quotaRuleId'`（jsonb，无外键），原有「经 FK 梳理无引用面」的结论对它不适用 |

- 请求体 `{ "scopeType": USER\|PROJECT, "scopeId", "metric": TOKENS\|REQUESTS\|COST, "period": DAILY\|WEEKLY\|MONTHLY\|YEARLY, "limitValue"（正整数；COST 口径为整数 CNY）, "warnPercent"?（1–99，默认 80）, "status"?（默认 ACTIVE）, "action"?（ALERT\|REJECT，默认 ALERT）}`；scope 不存在 → `404 SCOPE_NOT_FOUND`（防枚举）。COST 指标与 YEARLY 周期为 #683 增（对标腾讯配额管理）；`action` 为 #684 增（ADR-0020）。
- **水位口径（读时计算，非预聚合）**：TOKENS = 当期窗口 usage 事件全部 token（input+output+cacheRead+cacheCreation，与个人用量 TotalTokens 同口径）；REQUESTS = 当期到达上游的请求数（缓存命中不达上游、不计入，与腾讯「不计入缓存命中」档语义一致）；COST = 当期窗口按价格快照估算的上游实付（与成本报表同口径，缺价记 0）。窗口为 UTC 切片：DAILY=当日 / WEEKLY=周一起 / MONTHLY=当月（与月度预算同约定）/ YEARLY=自然年（1 月 1 日起）。水位计算走内部无上限窗口路径，不受公开查询 93 天窗口约束。
- `level`：`NORMAL` → `WARNING`（≥ warnPercent）→ `NEAR_LIMIT`（≥ 90%，固定提示档，对标腾讯「即将超限」）→ `EXCEEDED`（≥ 100%），按严重度判定。
- **COST 水位的定价口径（#943）**：`used` 是**已定价部分之和**——窗口内若有用量在发生时没有生效价目，它对 `used` 的贡献是 0。为免「未定价的窗口」与「确实没花钱」读成同一个数，**COST 规则**的响应额外带 `pricingStatus`（`COMPLETE`/`PARTIAL`/`UNAVAILABLE`）与 `unpriced`（同 `usage/summary` 的 `PricingGap`：`unpricedEvents`/`unavailableEvents`/`unpricedHitEvents` 及各维度 token 数）——字段名与口径都取自用量 API（#766），两页对同一个窗口的说法因此一致；`pricingStatus != COMPLETE` 时 `used` 是**下界**，管理端配额页与「我的配额」都在数字旁标「未定价」（hover 说明缺口）。TOKENS/REQUESTS 规则的这两个字段为 `null`：token 与请求数与定价无关。**执法语义不变**——`level`/`EXCEEDED` 仍只按 `used` 判定，「未定价窗口该不该拒绝 `REJECT` 规则」属 #943 待决问题②，本版未动。
- **超限动作（#684，ADR-0020）**：`ALERT`（默认）只体现水位、永不阻断；`REJECT` 由控制面评估器（`QuotaEnforcementService`，默认 60s 固定延迟）把超限作用域写入 `quota_enforcement` → 随路由快照下发 → 网关在准入处（Key 解析后、读 body 前）查内存集合，命中即 `429` + 标准错误信封（`type=quota_exceeded`，文案含恢复路径）+ **`Retry-After`**（秒：该作用域最早可自愈的窗口结束时刻；多规则拦同一作用域取最早），`/v1/models` 同受此门。**软着陆语义**：Key 不失效、不自动禁用；**跨入新窗口**或**提高限额/停用规则**后判定自然消失、流量自动恢复。
- **近似语义（必须知道）**：判定按周期刷新，不含评估间隔内新产生的用量——额度可能被超出一个评估周期内的量；页面水位与网关判定在一个周期内可能不一致。不承诺"恰好卡在 100%"，不做限流（速率语义另议）。
- DISABLED 规则保留计划并展示水位，页面按停用渲染。
- 审计：`QUOTA_RULE_CREATE` / `QUOTA_RULE_UPDATE` / `QUOTA_RULE_DELETE`（摘要含 `action`）。
- 视图含 `scopeName`（用户显示名/项目名）与 `scopeTag`（用户名/项目 code）。
- 错误码补充：body JSON 解析失败（未知枚举/类型错误）统一 `400 PARAM_INVALID`（GlobalExceptionHandler 对 `HttpMessageNotReadableException` 的映射，含字段名提示）。

### 5.20 缓存 ROI 报表 `GET /api/v1/admin/usage/roi`（P5.4）

窗口 + 逐日序列的缓存收益视图：什么仍付了上游、缓存省了多少。

- 参数：`from`/`to`（ISO-8601，缺省近 30 天）；复用用量查询共享校验（93 天窗口上限，`400 TIME_RANGE_TOO_WIDE` 等）；非法时间格式 → `400 PARAM_INVALID`。
- 口径（读取时由共享聚合器计算，`groupBy=day`）：
  - `paidCost` = 上游实付（`cost.upstreamPaid`）；`savedCost` = 缓存命中省下的上游费（`cost.savedByGatewayCache`，按当前单价快照对命中 token 计价）
  - `hitRatePct` = (L1+L2 命中) / (上游 + coalesced + 命中) × 100——缓存命中占全部已服务请求比例
  - `savedPct` = savedCost / (paidCost + savedCost) × 100——缓存不存在时的等效折扣
- 响应：`{ from, to, totals { upstreamRequests, coalescedRequests, l1Hits, l2Hits, hitRatePct, paidCost, savedCost, savedPct }, byDay [ { date, upstreamRequests, hitRequests, hitRatePct, paidCost, savedCost } ] }`。
- 零缓存事件也产出完整报表（全部为实付）；金额为十进制数。

### 5.21 MCP 两级访问控制（腾讯 AI 网关 doc 134890）

Server 级（谁能调用整个服务）+ Tool 级（谁可调用某工具）ACL，Tool 规则在 Server 规则上进一步收窄。调用方 = API 消费者（G8.1）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/mcp-services/{id}/access` | 全貌：服务模式 + 服务名单 + 每个工具的模式（null=继承）与名单 |
| `PUT /access/mode` | `{ "mode": NONE\|ALLOW\|DENY }`；切回 NONE 会清空服务名单 |
| `PUT /access/grants` | `{ "toolId"?, "mode": ALLOW\|DENY, "consumerIds"[] }` 整体替换一层名单（服务名单或某工具覆盖） |
| `DELETE /access/grants?toolId=` | 重置一层：无 toolId=服务回全开放（NONE）；带 toolId=该工具回继承 |

- **模式语义**：`NONE` 全部开放（此时才能配置工具级覆盖，腾讯约束）；`ALLOW` 白名单（仅名单内消费者可调用）；`DENY` 黑名单（名单内禁止、其余放行）。
- **判定**（调用侧使用，domain `McpAccessPolicy` 纯函数）：服务层先判（ALLOW=必须在名单、DENY=不在黑名单、NONE=放行）；工具无覆盖 → 继承服务判定；有覆盖 → 在服务放行基础上按工具名单再收窄（**只能收窄不能放宽**）。
- 校验错误：`MCP_SERVICE_NOT_FOUND`（404）、`TOOL_NOT_FOUND`（404，tool 不属于服务）、`SERVER_LIST_UNSUPPORTED`（409，NONE 模式配服务名单）、`TOOL_ACL_UNSUPPORTED`（409，非 NONE 模式配工具覆盖）、`CONSUMER_NOT_FOUND`/`CONSUMER_NOT_ACTIVE`（400，仅 ACTIVE 消费者可入名单）；consumerIds 非空由 bean 校验（400）。
- 审计：`MCP_ACCESS_MODE` / `MCP_ACCESS_GRANTS` / `MCP_ACCESS_RESET`。
- 配置变更即时生效（判定在调用入口读取配置）；真实 MCP 调用代理接线后由判定策略把关（P3.4/P3.5 后续集成）。

### 5.22 默认配额模板（腾讯 AI 网关 doc 135489）

全局默认配额策略：配置一个（每租户一份的）快照源，启用后**每个新创建的用户自动获得一条 USER 作用域配额规则**（复制模板定义；防止新用户"裸奔"）。模板编辑/停用不影响已自动分配的规则；手动规则永远优先（复制为 insert-if-absent）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/quota-default-template` | 当前模板状态（从未配置 = `enabled:false` 且定义字段为 null） |
| `PUT /api/v1/admin/quota-default-template` | 保存模板定义 `{ "metric": TOKENS\|REQUESTS, "period": DAILY\|WEEKLY\|MONTHLY, "limitValue"（正整数）}`；保留当前启用状态（重新配置不会重新启用） |
| `POST /api/v1/admin/quota-default-template/enable` | 启用自动分配 |
| `POST /api/v1/admin/quota-default-template/disable` | 停用自动分配（已分配规则保留） |

- **响应视图**：`{ enabled, metric?, period?, limitValue?, version, updatedBy?, updatedAt? }`——首次配置前 `enabled=false` 且 `metric/period/limitValue/updatedBy/updatedAt` 为 null（页面显示"未配置"空态）。
- **快照复制语义（在 `AdminOrgService.createUser` 事务内执行）**：
  - 自动复制 = 新建 `quota_rules` 行：`scope=USER`（新用户）、模板的 metric/period/limitValue、`warn_percent=80`（与手动创建缺省一致）、`ACTIVE`、`created_by`=建用户的执行者；变更即时生效。
  - **改模板不惊动存量**：编辑定义只改快照源，已自动分配的规则保持创建时副本不变。
  - **关闭不删已分配**：disable 后已分配规则全部保留，仅新用户不再自动获得。
  - **手动规则覆盖默认**：复制用 `ON CONFLICT (tenant, scope, metric, period) DO NOTHING`——已存在的（手动）规则永不被模板覆盖；自动规则本身是普通规则，可随时编辑/删除。
- 冲突错误：`QUOTA_TEMPLATE_NOT_CONFIGURED`（409，未配置即 enable/disable）、`QUOTA_TEMPLATE_ALREADY_ENABLED` / `QUOTA_TEMPLATE_ALREADY_DISABLED`（409，重复切换）；定义字段校验沿用 `400 PARAM_INVALID`。
- 审计：`QUOTA_DEFAULT_TEMPLATE_CREATE` / `QUOTA_DEFAULT_TEMPLATE_UPDATE` / `QUOTA_DEFAULT_TEMPLATE_ENABLE` / `QUOTA_DEFAULT_TEMPLATE_DISABLE`（target = tenant）；自动复制产生的规则记 `QUOTA_RULE_CREATE` 且摘要含 `"auto":true`。
- **映射取舍**：腾讯模板面向"消费者"（配额规则的挂靠对象）；本系统配额规则挂靠 USER/PROJECT 双作用域，其中"消费者"语义最近似**用户**（拥有 Virtual Key 的消费主体），故模板复制只落在新建用户上；PROJECT 作用域不参与模板化（腾讯无此概念，不发明）。预算模板化（roadmap 提及）另行立项。

### 5.23 MCP 路由规则（F11，腾讯 AI 网关 doc 135482，V28）

路由规则决定哪些入站请求能到达某个 MCP 服务；**所有规则共用同一上游（服务本身）**——本能力只控制"谁能进来"，不控制转发去向。配置面先行：规则落库并经管理 API 维护；数据面按优先级匹配在 MCP 代理接线（F01）后生效。**匹配与校验全程不读请求正文。**

- **default 兜底路由**：注册 MCP 服务时自动创建（`name=default`、`priority=0`、无条件匹配、`ENABLED`）；**不可修改/禁用/删除**（`409 ROUTE_DEFAULT_IMMUTABLE`），保证服务始终可达。存量服务由 V28 迁移回填（确定性 id）。
- **自定义路由**：`priority` 默认 1000（1–65535；0 为系统保留），数值越大优先；可编辑、启停、删除。仅 `ENABLED` 规则参与匹配；未命中所有自定义规则时回落到 default。
- **单条规则内 AND 语义**：路径、Host、方法白名单、每条 Header 条件全部满足才命中；多规则间按优先级（大者先）。
- **匹配方式**：`EXACT` / `PREFIX` / `REGEX`（RE2）。正则按**全匹配**语义执行（上游示例自带 `^…$` 锚点）；非法 RE2（含回溯引用）提交即拦截（`400 ROUTE_PATTERN_INVALID`）。路径值必须以 `/` 开头（REGEX 豁免）；Host 值大小写不敏感（EXACT/PREFIX 归一后比较，REGEX 原文执行）；Header 名大小写不敏感、值与模式敏感。Header 条件最多 8 条。
- **方法白名单**：`GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS`，不选/全选 = 不限。
- **冲突实时校验**（创建/更新/启用时执行）：与同服务**已启用**规则（排除自身）的**匹配面完全等价**（path+host+方法+header 条件的规范化集合相同）即 `409 ROUTE_MATCH_CONFLICT`，detail 含冲突路由名。正则包含关系不可判定，等价是所执行的上界（如实记录）；停用规则不参与校验，但**重新启用会再次校验**，防休眠重复被武装。无条件自定义规则与 default 等价 → 创建即冲突。
- **更新语义**：`PATCH` 为可编辑字段**全量替换**（除 status；缺省匹配字段 = 清空/不限；priority 缺省保留现值）。启停**幂等**（同状态重复调用 200 不报错，对齐上游 doc；与 Tools 的 409 惯例不同，如实记录）。
- 路由删除后配置即失；删除 MCP 服务级联清理其路由（DB CASCADE）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/mcp-services/{serviceId}/route-rules` | 规则列表（优先级降序，default 在末尾）；每项含只读 `matchExpression`（规范条件面渲染，与引擎匹配语义同源） |
| `POST /api/v1/admin/mcp-services/{serviceId}/route-rules` | 新建（默认启用）：`{ "name", "description"?, "priority"?, "pathMode"?, "pathValue"?, "hostMode"?, "hostValue"?, "methods"?, "headers"? }` |
| `PATCH /api/v1/admin/mcp-services/{serviceId}/route-rules/{ruleId}` | 全量替换可编辑字段（见上） |
| `POST /api/v1/admin/mcp-services/{serviceId}/route-rules/{ruleId}/status?status=ENABLED\|DISABLED` | 启用/禁用（幂等） |
| `DELETE /api/v1/admin/mcp-services/{serviceId}/route-rules/{ruleId}` | 删除自定义路由 |

- 名称：1–64 字符、同一服务唯一（`409 ROUTE_NAME_TAKEN`）、`default` 保留（`400 ROUTE_NAME_RESERVED`）；描述 ≤200。
- 错误码：`ROUTE_NOT_FOUND`（404）、`ROUTE_NAME_TAKEN`（409）、`ROUTE_NAME_RESERVED`（400）、`ROUTE_NAME_INVALID`（400）、`ROUTE_DESCRIPTION_INVALID`（400）、`ROUTE_PRIORITY_INVALID`（400）、`ROUTE_PATH_INVALID`（400）、`ROUTE_MATCHER_INVALID`（400）、`ROUTE_PATTERN_INVALID`（400）、`ROUTE_METHOD_INVALID`（400）、`ROUTE_HEADERS_TOO_MANY`（400）、`ROUTE_HEADER_INVALID`（400）、`ROUTE_MATCH_CONFLICT`（409）、`ROUTE_DEFAULT_IMMUTABLE`（409）、`ROUTE_STATUS_INVALID`（400）；服务不存在 `404 MCP_SERVICE_NOT_FOUND`。
- **审计（#1052）**：创建/更新/启停/删除分别写 `MCP_ROUTE_RULE_CREATE` / `_UPDATE` / `_STATUS` / `_DELETE`
  （`targetType=MCP_ROUTE_RULE`，`targetId=`规则 id；摘要含 `serviceId`、`name`、`priority`，更新与启停另带
  `previousName` / `previousPriority` / `previousStatus` 作为改前值）。启停的**幂等 no-op**（状态未变）不记事件；
  随服务创建落地的系统 `default` 路由不单独记事件（由 `MCP_SERVICE_CREATE` 覆盖，避免重复行）。actor 取会话用户。

### 5.24 MCP 访问日志查询 `GET /api/v1/admin/mcp-access-logs`（F15，V29）

MCP 代理调用（F01 入口 `/mcpservers/{serviceName}/mcp`）的**纯元数据审计行**：每次身份可解析的调用（消费者认证通过且服务名解析成功）由网关异步批量写一行；**不存工具参数、请求正文或响应正文**（信封 method/`params.name` 是唯一被读取的正文元数据，raw 16 `aigw.mcp.*` 语义）。`status` 为网关侧终态：`FORWARDED`（上游已应答，`httpStatus`=上游 HTTP 状态）/ `SERVICE_DENIED`、`TOOL_DENIED`、`TOOL_UNAVAILABLE`（ACL，doc 134890，`httpStatus`=403）/ `INVALID_ENVELOPE`（400）/ `UPSTREAM_FAILURE`（60s 预算内无上游应答，`httpStatus` 空）。

- **写入口**：网关 `McpAccessLogSink`（有界队列 4096 + 1s 周期 flush；饱和 drop+计数 WARN；批量失败整批重入队重试）。写入幂等：`(tenant_id, gateway_request_id)` 唯一，重试 flush 不双写。参数 `miqrokey.gateway.mcp-log.capacity` / `.flush-interval-ms`。
- **不落行**：预解析失败（401 未知 Key、404 未知服务）无可信身份，仅留在请求日志——与 usage_event 同口径。
- **查询语义**：新→旧排序（`occurred_at DESC, id DESC`）；`service`/`consumer` 按名称精确过滤；`from`/`to`（ISO-8601 instant，含 `Z`）默认近 24h，窗口 ≤ 31 天（`TIME_RANGE_TOO_WIDE`）；`from > to` → `TIME_RANGE_INVALID`；`limit` 默认 200、上限 1000（`SIZE_INVALID`）；`from/to` 非法格式 → `PARAM_INVALID`。
- **会话与耗时（#358，I12，raw 16）**：行含 `sessionId`（客户端 `Session-Id` 头；SSE 双端点分发未带头时回落为入站会话 id；皆无为空）与 `ttfbMs`（上游首字节毫秒，**仅 FORWARDED 行**；重试按最终成功那次计）——会话过滤参数为后续增强。
- 权限：SYSTEM_ADMIN-only（deny-by-default）；只读端点无审计事件。

| 参数 | 类型 | 缺省 |
|---|---|---|
| `service` | string（服务名精确） | 全部 |
| `consumer` | string（消费者名精确） | 全部 |
| `from` / `to` | ISO-8601 instant | now-24h / now |
| `limit` | int 1–1000 | 200 |

响应：`McpAccessLogEntry[]`（`id/serviceId/serviceName/consumerId/consumerName/rpcMethod/toolName/status/httpStatus/gatewayRequestId/occurredAt`；`rpcMethod`/`toolName` 可空）。

### 5.25 MCP 韧性配置 `GET/PUT /api/v1/admin/mcp-services/{serviceId}/resilience`（F12/F13，V30）

每个 MCP 服务一份韧性策略（`mcp_resilience_policy`）：**重试门禁与熔断均默认关闭**——无策略行（或全 false）时数据面行为与之前完全一致。GET 返回生效策略（无行时返回 disabled 默认视图）；PUT 整份替换（省略字段=disabled 默认值），审计 `MCP_RESILIENCE_UPDATE`，并即时触发路由快照刷新（~1 刷新周期内生效）。

**F12 重试（doc 134831 语义本土化）**：`retryEnabled` + `retryMax`（1–5）+ `retryConditions`（`SERVER_5XX|CONNECTION_FAILURE|TIMEOUT`，启用时至少一项）+ `idempotencyConfirmed`。重试只发生在**网关把上游首字节回给调用方之前**；`SERVER_5XX` 仅含 **500/502/503/504**（#365 收窄——501/505 等确定性状态直接回传，不重试）。**非幂等门**：`tools/call` 命中的工具行 method 为 POST/PUT/PATCH 时，未勾选 `idempotencyConfirmed` 一律不重试（防重复写）；GET/HEAD/OPTIONS/DELETE 与无工具行的方法（initialize/tools/list 等）不受限。

**Tool 级重试覆盖（#360，I13，raw 12，V46）**：`GET/PUT /api/v1/admin/mcp-services/{serviceId}/tools/{toolId}/retry-policy`——每工具可配覆盖（`retryEnabled/retryMax/retryConditions/idempotencyConfirmed`，校验同 F12；越界/空条件/未知条件 → `400 TOOL_RETRY_POLICY_INVALID`）。**覆盖语义**：`tools/call` 命中带覆盖的工具时，重试字段以工具覆盖为准、**熔断字段保持服务级**；无覆盖行 → 完全跟随服务策略（GET 无行返回 disabled 默认视图）。审计 `MCP_TOOL_RETRY_UPDATE`，即时触发快照刷新；工具/服务不存在 → `404 TOOL_NOT_FOUND`/`MCP_SERVICE_NOT_FOUND`。

**F13 熔断（doc 134859）**：三态 CLOSED/OPEN/HALF_OPEN。滑动窗口 `breakerWindowSeconds`（1–60，默认 10）+ 最小请求数 `breakerMinRequests`（1–100，默认 10）防低流量误判；错误比例触发 `breakerErrorEnabled`/`breakerErrorRatio`（1–100，默认 50）+ `breakerErrorStatusCodes`（400–599、≤32、默认 500/502/503/504，**429 需显式加入**）；慢调用触发 `breakerSlowEnabled`/`breakerSlowCallMs`（100–60000）/`breakerSlowRatio`——两触发至少启用其一。**`breakerSlowCallMs` 必须小于服务上游超时 `upstreamTimeoutMs`**（I20 基准修正：doc 134859 的基准是后端请求超时而非健康探测超时；默认 60000ms；否则慢调用永远观察不到），越界 → `400 RESILIENCE_SLOW_EXCEEDS_TIMEOUT`；反向：`PUT …/upstream-timeout` 把预算下调到 ≤ 已启用慢阈值同样拒绝。OPEN 持续 `breakerOpenSeconds`（5–600，默认 30）后进入 HALF_OPEN，放行 `breakerProbeCount`（1–10，默认 3）个探测，成功 `breakerProbeSuccess`（≤probeCount，默认 2）个即恢复 CLOSED，任一失败重新 OPEN。`breakerSkipRetry`（默认 true）语义：OPEN / 半开探测槽耗尽期间该桶快速失败（503 `circuit_open` 错误信封）；置 false 时熔断**只观测不限流**（`beforeCall` 仍驱动状态机与探测计数，但不再阻断调用——不经建议的显式模式，#365）。熔断桶= `tools/call` 按工具名、其余信封方法按方法名，桶间互不影响。

其余校验失败 → `400 RESILIENCE_INVALID`（范围/条件/触发组合/状态码集合）；服务不存在 → `404 MCP_SERVICE_NOT_FOUND`；SYSTEM_ADMIN-only（deny-by-default）。

| 参数 | 类型 | 说明 |
|---|---|---|
| `retryEnabled` | bool | 重试总开关（默认 false） |
| `retryMax` | int 1–5 | 重试次数（启用时必填） |
| `retryConditions` | enum[] | `SERVER_5XX` / `CONNECTION_FAILURE` / `TIMEOUT` |
| `idempotencyConfirmed` | bool | 确认后端幂等（POST/PUT/PATCH 工具调用可重试） |
| `breakerEnabled` | bool | 熔断总开关（默认 false） |
| `breakerWindowSeconds` | int | 滑动统计窗口 |
| `breakerMinRequests` | int | 最小请求数防误判 |
| `breakerErrorEnabled` / `breakerErrorRatio` / `breakerErrorStatusCodes` | bool / int / int[] | 错误比例触发 |
| `breakerSlowEnabled` / `breakerSlowCallMs` / `breakerSlowRatio` | bool / int / int | 慢调用触发（slowMs < checkTimeout×1000） |
| `breakerOpenSeconds` | int | OPEN 持续时长 |
| `breakerProbeCount` / `breakerProbeSuccess` | int / int | 半开探测 |
| `breakerSkipRetry` | bool | OPEN 期间跳过重试（默认 true） |

### 5.26 合规留痕开关 `GET/PUT /api/v1/admin/retention-config`（ADR-0014 v3 Accepted，V31）

内容留痕通道的**租户级总开关**：默认**全关**（无行=任何请求内容不被采集；CLAUDE.md「不保存正文」红线仅在此行 enabled 时按 ADR-0014 §1 例外放行）。GET 返回生效配置（无行时=disabled 默认）；PUT 体 `{"enabled": bool, "maxContentBytes"?}` 切换并审计 `RETENTION_CONFIG_UPDATE`、经路由快照即时下发网关（运行中生效，无需重启）；`maxContentBytes`（#367，I18；默认 262144，范围 1024–4194304）为单条捕获的**租户内容上限**——超限按 UTF-8 边界**截断**并在 envelope/Kafka payload 置 `truncated=true`（网关侧计数），越界 400 `RETENTION_CONFIG_INVALID`。v1 固定 `contentScope=USER_TEXT_ONLY`（P1：仅用户消息文本起步，模型回复/工具正文不在范围）与 `keyVersion=v1`（P5：部署密钥集；KMS/轮换随 P5 落地扩展）。启用本身只开通道——网关侧采集/密文信封/Kafka 投递为后续批次（见 ADR-0014 §6）。SYSTEM_ADMIN-only（deny-by-default）；body 非法 → 400。

### 5.27 账单对账 `POST/GET /api/v1/admin/reconciliations`（F19，V42）

canonical 账单导入与四态对账报告（契约稿 docs/bill-reconciliation-contract.md v0）：**只读结果**——不写 usage_event、
不存上传内容（仅 SHA-256 与大小）。供应商私有格式解析器与指纹级匹配仍 WAITING_FOR_SAMPLE；canonical 路径不依赖样本。

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/reconciliations?providerCode&currency&windowFrom&windowTo` | body = canonical JSONL（UTF-8，`.gz` 可选——按 gzip 魔数自动识别）；→ `202` + 报告（PENDING）；异步解析→四级匹配→报告落库 |
| `GET /api/v1/admin/reconciliations?limit=` | 租户报告列表（新→旧，`created_at DESC`，limit 1..100 默认 20；越界 `400 RECONCILIATION_PARAM_INVALID`；空列表 `[]`） |
| `GET /api/v1/admin/reconciliations/{id}` | 元数据 + 汇总：`totalRows/matched/partialBuckets/unmatchedProvider/unmatchedLocal/lineErrorCount/amountDiff` + `uploadSha256/uploadBytes` + `status(PENDING/RUNNING/SUCCEEDED/FAILED)` |
| `GET /api/v1/admin/reconciliations/{id}/rows?state=&cursor=&limit=` | 四态明细行（`state` ∈ MATCHED/PARTIAL/UNMATCHED_PROVIDER/UNMATCHED_LOCAL；`row_no` 游标，limit ≤500，`nextCursor`） |
| `GET /api/v1/admin/reconciliations/{id}/export?state=` | 该报告四态明细行的 CSV 合规导出（#715）：形状同审计/留痕导出（§5.0 前段）——UTF-8 BOM、RFC 4180 + 公式注入防护、5 万行上限、截断以 `X-MiQroKey-Truncated: true` 声明、`Content-Disposition: attachment`。`state` 语义与 `/rows` 完全一致（同一校验、同一 400），空结果返回仅表头的 CSV（不是错误）。裸十进制字面量（如 `detail_amount` 的 `-12.34`）原样输出、不加防护单引号——它是数字不是公式，加前缀既使导出与页面不一致、又让金额列在表格中退化为文本；仅「形似数字」的串（`-1+1`、`+cmd\|' /C calc'!A0`）仍按公式防护 |

CSV 导出列（**声明式单列表**，表头与每一行同源，避免错位；`detail_*` 为按判决展开的 detail JSON，未携带的键留空不挪列）：
`report_id, provider_code, row_no, verdict, matched_by, provider_row_ref, local_ref, detail_model_id, detail_amount,
detail_currency, detail_occurred_at, detail_status, detail_bucket_key, detail_provider_count, detail_local_count`。
行数经 `X-MiQroKey-Rows` 精确返回（明细正文本可含换行，逐行计数不可靠）。

- **幂等**：同 (providerCode, window, currency, uploadSha256) 重复导入返回既有报告（不重复执行）；`FAILED` 除外（可重试）。
- 上传上限：16MB（解压 64MB / 100,000 行）；超限或 gzip 损坏 `400 RECONCILIATION_UPLOAD_INVALID`。
- 校验：窗口 ≤31 天且 from<to（`RECONCILIATION_WINDOW_INVALID`）；**窗口是半开区间 `[windowFrom, windowTo)`
  ——`windowFrom` 含、`windowTo` 不含**（#1045 的实现口径，本行补记）：对账与用量统计/导出共享同一条全局窗口约定，
  边界行因此只归一份报告；按闭区间切分账单文件的调用方会让边界行从 MATCHED 翻成 UNMATCHED，而报告不会解释原因。
  **`providerCode` 取的是
  `provider_products.product_code`（供应商*产品*码，如 `tencent-coding-plan`），不是 `providers.slug`**
  ——传成 slug 会得到 `RECONCILIATION_PROVIDER_UNKNOWN`，而报错正文说的是 `product_code`；
  currency ISO-4217（`RECONCILIATION_PARAM_INVALID`）；报告不存在 `RECONCILIATION_NOT_FOUND`（404）。
- 审计：`RECONCILIATION_CREATED/SUCCEEDED/FAILED`（摘要含上传 sha 与计数，**不存正文**；RUNNING 为瞬时态不入审计）；
  导出记 `RECONCILIATION_EXPORT`（`targetType=RECONCILIATION`，摘要 `{rows, truncated}`，其中 `rows` 为**截断后**实际导出的行数，与 `X-MiQroKey-Rows` 同值）；参数非法或报告不存在时在 `record` 前失败，不产生审计行。
- 语义口径：无 ID 账单行若未匹配计入 `UNMATCHED_PROVIDER` 行、同时按（productCode, 5 分钟桶）计入
  `PARTIAL` 桶差；`UNMATCHED_LOCAL` 为行级（本地有 provider_request_id 且未被账单消费）。
- 前端页随 coverage-matrix I2 交付（报告列表 / 上传 / 四态明细，2026-09-10）；导出链路接 #330 reconcile-level。

## 6. 导出与对账任务

导出和账单对账均为异步任务：

1. `POST` 创建任务，返回 `202` 和任务 ID。
2. `GET /{id}` 查询 `PENDING/RUNNING/SUCCEEDED/FAILED/EXPIRED`。
3. 成功后下载只在短期签名 URL 或已鉴权流式接口提供。
4. 导出包含 schema/version manifest、查询范围、时区、生成时间和文件 SHA-256。
5. CSV/JSONL 均不得包含提示词、回答正文、真实凭证明文或 Virtual Key 明文。

官方账单明细优先按供应商 request ID 匹配；其次按模型、时间窗、token 和金额组合匹配。结果必须区分 `MATCHED`、`PARTIAL`、`UNMATCHED_LOCAL`、`UNMATCHED_PROVIDER`。

## 7. Gateway 推理入口

- Gateway 接受产品已声明的任意上游路径和方法，不把所有请求强制转换为 OpenAI 或 Anthropic 格式。
- 首版重点验证 Anthropic Messages、OpenAI Responses、OpenAI Chat Completions，包括 SSE 流。
- 除鉴权 Header、目标 Host 和明确配置的安全 Header 外，请求体、查询串、未知 Header 和响应体按字节/流透明传递。
- 供应商返回的 HTTP 状态、错误体和 SSE 事件顺序保持不变；本系统错误使用本系统 Problem Details。
- `GET /v1/models` 是本系统提供的受控端点，只返回该 Virtual Key 允许的明确模型 ID。
- Virtual Key 无效、吊销、过期或模型越权时，Gateway 不连接上游。
- 客户断开时取消上游订阅；不得继续消耗 token。

### 7.1 Virtual Key 鉴权与路由

- 客户端必须且只能提供**一个**凭证 Header：`Authorization: Bearer <key>`（或裸值）、`x-api-key`、`api-key`。零个或多个凭证 Header → `401`（错误体不区分具体原因，防枚举）。
- **凭据值错误的统一语义**：未知 / 畸形（含缺失后缀、后缀含点）的 Virtual Key → `404 virtual_key_invalid`——各场景响应逐字一致、与"未知 Key"不可区分（防枚举；见 `VirtualKeyAuthContractTest`）。注意与 MCP 数据面（消费者 Key/JWT）同场景的 `401 invalid_api_key` 口径不同：`/v1` 用 404、MCP 用 401，均为各通道既定设计。
- Key 格式 `mqk_live_<publicKeyId>_<secret>.<projectTag>`（后缀在解析级必填）：点号后缀是**路由选择器**（明文，用于在 Key 的多个项目绑定间选择），鉴权权威是数据库中的 `key_project_binding`，标签本身不承载权限。HMAC 摘要不包含标签。
- `GET /v1/context-registry`（CAA，#639）：本地 Agent 的 repo → 项目映射来源。虚拟 Key 认证（**identity-only**，#641：只做凭证抽取/解析/HMAC，不走归属阶梯——多绑定 Key 带任意（含不匹配）后缀都可读取；统一 404/401 失败语义）；**只返回该 Key ACTIVE 绑定项目**下的 `project_repositories` 行——`{ entries: [{ repoKey, projectId, projectTag }] }`；无持久化时返回空表。注册表读取发生在 Agent 同步（非热路径），直接查库、不占快照。
- **请求上下文解析阶梯（CAA，#633）**：身份（Key/HMAC）与归属（本请求计入哪个项目）分离，归属按固定阶梯裁决，首个命中生效：
  1. `X-Miqro-Project-Id` 声明（**不可信输入**，仅当目标项目确为该 Key 的绑定时生效）→ `RESOLVED_HEADER`；
  2. 点号后缀标签命中该 Key 的某个绑定 → `RESOLVED_SUFFIX`；
  3. Key 恰有一个绑定 → `SOLE_BINDING`（任意合法标签视为装饰）；
  4. 其余（多绑定且上下文无法解析）→ 租户配置了未归属策略（§5 admin）时以策略的凭证/产品/模型范围路由，usage 记「未归属」桶项目（`resolution_status=POLICY_ROUTED`，claimed_* 照常留档）；未配置策略 → `400 CONTEXT_REQUIRED`——不猜测、不静默回落到默认项目。
  - 声明项目不是该 Key 的绑定 → `403 CONTEXT_NOT_ALLOWED`（仅当存在有效声明时）；声明不是合法 UUID → `400 CONTEXT_INVALID`。
  - 审计头（降级为纯审计、绝不参与授权；畸形即丢弃；永不转发上游）：`X-Miqro-Claim-Source`（`prompt_url`/`tool_path`/`bash_cwd`/`system_cwd`/`git_remote`/`suffix`/`none`）、`X-Miqro-Claim-Confidence`（`HIGH`/`MEDIUM`/`LOW`/`NONE`）、`X-Miqro-Claim-Status`（`RESOLVED`/`AMBIGUOUS`/`UNATTRIBUTED`）、`X-Claude-Code-Session-Id`（≤64 字符）。Agent 声明（`claimed_*`）与服务端裁决（`project_id` + `resolution_status`）分开落库，声明永不构成授权。
  - 归属随用量落库：`usage_event` 的 `session_id`/`activity_id`/`claimed_project_id`/`resolution_status`/`claim_source`/`claim_confidence`；逐请求证据审计于 `request_context_evidence`（V55，#629）——**只对使用了外部选择器的裁定写行**：`RESOLVED_HEADER` → `source='header'`、`value=` 声明项目 id；`RESOLVED_SUFFIX` → `source='suffix'`、`value=` Key 中呈现的 tag。`SOLE_BINDING`/`POLICY_ROUTED` 没有线索来源，其解释即 `resolution_status` 本身，故不写证据行。行 `id` = 该笔 `usage_event` 的 `id`，随用量同批同事务写入、`ON CONFLICT (id) DO NOTHING`（就本表而言重放不产生重复证据行，与用量行可按 `id` join；`usage_event` 侧对 `provider_request_id` 为空的事件无冲突保护，属既有边界）。**读取该证据的查询 API 尚未交付。**
  - 规格：`docs/context-attribution-implementation-spec.md` v1.1 §4。
- Gateway 使用版本化只读路由快照（定时刷新，默认 30s）做校验与路由；热路径不查询数据库。吊销/轮换按快照刷新传播，宽限期由控制面配置。
- 校验通过后 Gateway 注入本次解析出的绑定（binding）对应的上游凭证（AES-256-GCM 解密，内存中用完即清零），并把请求转发到该授权对应项目的目标；请求头和体按透明代理规则原样转发。
- 模型预校验：请求体中的模型不在 Key 授权集合内时，不连接上游，直接返回错误（Anthropic/OpenAI 协议兼容的错误体）。代理热路径的预校验只按 **Key 快照**（`virtual_key_models`）判断，与 `GET /v1/models` 的四路交集是两回事——模型目录为空时代理不会拒绝所有流量。
- `/v1/models` 返回该 Virtual Key 的目录、上游模型、Grant 与 Key 快照的交集；未授权模型不泄漏。四路输入均来自同一版本的路由快照：
  - **目录**：已签名 provider catalog（classpath，Ed25519 校验）。Key 绑定产品的 `product_code` 不在目录中 → 返回空列表（目录是外层授权边界）。
  - **上游模型**：`model_catalog` 中该产品 ACTIVE 行。该表只由**成功的**官方 API 抓取写入（`ModelCatalogService` 成功才写、失败保留上次成功目录），因此该集合就是“最后成功目录”。
  - **Grant**：该 Key 所属 ACTIVE grant 的 `project_provider_grant_models`。
  - **Key 快照**：该 Key 的 `virtual_key_models`。
  - 在官方 API 适配器实现（G3.x）之前 `model_catalog` 为空，严格交集的结果是空列表——不泄漏未授权模型是刻意的，不是缺陷。
- 用量记录：每个请求写入 `usage_event`（幂等，`provider_request_id` 在 tenant 内唯一）；usage 缺失时标记 `usage_missing=true`；正文（prompt、代码、工具、回答）永不进入持久化。
- 生命周期记录（G2.4）：每个**到达上游**的请求在 `request_usage_records` 打开 `IN_FLIGHT` 行并恰好 finalize 一次——包括客户端取消、上游错误与超时（状态见 usage-accounting §2）；鉴权失败与缓存命中不打开记录。usage 从 SSE 事件或非流式 JSON 正文解析（仅计数）；SUCCEEDED 但无 usage 时 `usage_missing=true`，绝不静默记零。
- 上游目标门控（G2.6 SSRF）：仅转发路由快照提供的 Base URL；`https` 是硬要求（除非目标命中 `MIQROKEY_UPSTREAM_ALLOWED_CIDRS`），URL 携带 `userinfo` 一律拒绝，DNS 解析后的每个地址必须是公网地址（环回、链路本地、RFC1918、CGNAT `100.64/10`、组播、any-local、IPv6 ULA `fc00::/7` 均拒绝，除非命中 allowlist）。被拒绝时返回 `502 route_unavailable`，错误体、日志与审计**不包含目标 URL 或主机名**（`UpstreamTargetValidator` 的拒绝原因只有稳定类别 token）。
- 路径白名单：数据面只暴露 `POST /v1/messages`、`POST /v1/responses`、`POST /v1/chat/completions`。正确方法之外的请求 → `405 method_not_allowed`；其他 `/v1/**` 路径 → `404 unsupported_path`；两者都不连接上游。嵌入式 `..` 段按字面处理（`/v1/**` 之外不匹配）；`//` 由服务器归一化为规范路径后按正常请求处理，不构成走私。
- 输入上限：入站 Header 超过 `MIQROKEY_MAX_INBOUND_HEADER_BYTES`（默认 `32KB`）由 Netty 在路由前拒绝 → `431`；请求体超过 `MIQROKEY_MAX_PROXY_BUFFER_BYTES`（默认 `256KB`）→ `413 payload_too_large`。超限请求不连接上游。
- 请求前置预检（#553）：鉴权与模型授权通过后、缓存查询与上游调用之前，按 **UTF-8 码点**统计整个已缓冲 body（含 JSON 结构、工具 schema、base64）的字符数；超过 `MIQROKEY_GATEWAY_CONTEXT_LIMIT_THRESHOLD_CHARS`（默认 `200000`）→ `413`，错误码 `context_limit_exceeded`（Anthropic/OpenAI 各自协议兼容的错误体，`message` 只回报实测字符数与阈值，**不含请求内容**）。该预检**只读**：通过时转发字节与无预检时完全一致，不 tokenize、不重排、不补写；拒绝时不连接上游、不查缓存、不产生用量与生命周期记录。`MIQROKEY_GATEWAY_CONTEXT_LIMIT_ENABLED=false` 时完全关闭（行为与引入前一致）。裁决顺序为 鉴权 → 模型授权 → 体量预检，因此超限 body 不构成绕过或探测手段。阈值是**字符数**而非 token 数：对合法 UTF-8，整个序列化 body（含 JSON 结构与 base64 膨胀）都计入，是该 body 的字符上界；**非法 UTF-8 字节序列按字节长度计**（严格 UTF-8 校验不通过即整段回退为字节数），字符数不会超过字节数，因此计数**整体不低估**——不会低于任何宽松解码器解出的字符数（已有 1–2 字节穷举与定种子模糊测试固定）。这类 body 本身不是合法 JSON，且仍受缓冲上限约束。由此引入本预检后，**200001–262144 字符的请求由「缓冲上限放行」变为 413**（256KB 缓冲上限可容纳约 262144 字节）——这是刻意收紧，会同时挡掉同尺寸但上游本可接受的合法请求，运维可用 `enabled` / `threshold-chars` 调整。阈值高于缓冲上限时后者先拒绝；每 Key 阈值不在本版本范围内。覆盖范围限于 LLM 数据面三个 `/v1/**` 路径；MCP 数据面（`/mcpservers/{service}/mcp`、`/mcpservers/{service}/message`）本版本仍只有既有缓冲上限（`payload_too_large`），套用同一预检为后续项。合规留存旁路（ADR-0014，默认关闭）在预检**之前**捕获入站 body，因此开启留存时被 413 拒绝的请求仍可能已按留存策略入库；预检自身不写任何持久化。
- 模型侧熔断（#741，**默认关**）：`MIQROKEY_GATEWAY_CIRCUIT_BREAKER_ENABLED=true` 时，按 **（供应商产品 × 上游凭证）** 滑窗统计错误（上游状态 ∈ 配置集合，默认 500/502/503/504；传输错误恒计入；客户端取消不计）；达到最小样本数与错误率阈值后打开熔断，期间的请求**不连接上游**、直接返回 `503`，错误码 `circuit_open`（Anthropic/OpenAI 各自协议兼容信封），并计入零标签指标 `miqrokey_gateway_circuit_rejected_total`。打开窗口到期放行探活（数量与连续成功数可配），探活成功自动封闭、任一失败立即重开。被拒请求**不写用量与生命周期记录**（与拒绝类一致）；关闭时零行为变化。阈值口径与 MCP 侧 F13 对齐。
- Header 走私：凭证 Header（`Authorization`/`x-api-key`/`api-key`）出现多个 → `401`，任何凭证都不会转发；`Connection` 提名的 hop-by-hop Header 与 `X-MiQroKey-*`、`x-miqro-*` 内部 Header 在转发前剥离（上下文声明因此永不到达上游）；上游只携带 Gateway 注入的真实凭证，客户端 Virtual Key 永不泄漏到上游。

Gateway 生成 `X-MiQroKey-Request-Id`。若供应商已有 request ID，两个 ID 都进入用量记录；不得覆盖供应商 request ID Header。

响应缓存（ADR-0009，默认关）：命中转发时响应头 `X-MiQroKey-Cache` 标注命中级别（`L1`/`L2`）；缓存开启但未命中时为 `miss`。客户端以请求头 `X-MiQroKey-Cacheable: 1` 显式参与缓存（双重 opt-in，见 ADR-0009）。

## 8. OpenAPI 与兼容性

- Control Plane 生成 **OpenAPI 3.1**（F09 已实现）：`GET /v3/api-docs`（springdoc，无 swagger-ui；`springdoc.api-docs.version=OPENAPI_3_1`）。机器可读基线提交于 `docs/openapi/openapi-3.1.json`；CI（backend-integration job）对每次生成结果跑破坏性 diff（`deploy/openapi/check-openapi-breaking.py`：删除 path/operation/response code/参数、属性变 required 即失败）。本文仍是业务语义事实源；生成物是机器可读镜像，OpenAPI 不得改变本文语义。
- 前端 TypeScript client **目前由手写 `frontend/src/api` + `types/api` 维护**（未从 OpenAPI 生成——规格愿景；codegen 迁移列为发布前候选，届时删除手写 DTO）。
- 同一 major 版本只允许新增可选字段和新端点；删除、改名、改变含义必须进入下一 major。
- 推理入口不进入管理 API 的 DTO 生成流程，以透明代理契约和 fixtures 验证。


## 9. 管理开放 API（ADR-0015，2026-09-07 Accepted，批 1 + 批 1b 读面）

**凭据生命周期（SYSTEM_ADMIN-only，网页会话）**
- `POST /api/v1/admin/api-keys?expiresAt=` body `{"name"}` → 201 `{key, secret, shownOnce:true}`；secret 仅此一次。
- `GET /api/v1/admin/api-keys` → 视图列表（无 digest；含 `capabilities`，null=全量）。
- `PATCH /api/v1/admin/api-keys/{id}/scope` body `{"capabilities":["usage:read",…]}` → 视图；
  `capabilities` 缺省（null）= 恢复全量；空数组 = 全拒；未知/重复码 → 400
  `ADMIN_API_KEY_SCOPE_INVALID`；审计 `ADMIN_API_KEY_SCOPE_UPDATE`（摘要 from/to）。
- `POST /api/v1/admin/api-keys/{id}/revoke` → 视图；冲突码 `ADMIN_API_KEY_NAME_TAKEN`（409）、
  `ADMIN_API_KEY_NOT_FOUND`（404）、`ADMIN_API_KEY_ALREADY_REVOKED`（409）；审计
  `ADMIN_API_KEY_ISSUE/REVOKE`。

**能力组 scope（批 3，ADR-0015 增补 2026-09-09，V35）**：机器密钥可裁剪到预设能力组；
NULL scope = 全量（存量兼容）。强制层按「开放面路径 → 能力组」映射校验，能力不足 →
403 `ADMIN_API_SCOPE_DENIED`（problem+json；有发行管理员的越权尝试进审计）。

| 能力组 | 覆盖开放面端点 |
|---|---|
| `usage:read` | usage summary/records、audit-events、api-keys 视图、quota-rules 读、mcp-access-logs |
| `alerts:write` | alert-rules、webhooks 全生命周期（含读） |
| `exports:create` | export-tasks 创建与元数据 |
| `vkeys:delegate` | virtual-keys 委托创建与列表 |

**只读开放面（机器凭据 `Authorization: Bearer mqk_admin_…`，租户级）**
- `GET /api/v1/admin-api/usage/summary?groupBy&from&to` — 租户级汇总（与 §5 管理员用量口径一致）。
- `GET /api/v1/admin-api/usage/records?from&to&page&size` — 租户级明细（窗口/分页校验同管理端点）。
- `GET /api/v1/admin-api/audit-events?size&action&targetType&actorId&from&to&beforePosition` — 审计
  链尾（因果序倒排；可选精确筛选：资源类型 `targetType`、操作人 `actorId`、时间窗 `from`/`to`
  ISO-8601 UTC，非法值 400 `PARAM_INVALID`、from>to 400 `TIME_RANGE_INVALID`；cursor 语义同
  `GET /api/v1/admin/audit-events`；哈希永不序列化）。
- `GET /api/v1/admin-api/audit-events/export?action&targetType&actorId&from&to` → `text/csv`（附件下载，
  usage:read；与人类端 `GET /api/v1/admin/audit-events/export` 同筛选同形状）——合规导出：
  RFC 4180 转义 + UTF-8 BOM，列=时间/action/targetType/targetId/actorId/changeSummary/chainPosition，
  不含哈希链与正文；单次上限 50000 行，超出以响应头 `X-MiQroKey-Truncated: true` 显式截断声明
  （调用方应收窄窗口，不静默丢行）。
- `GET /api/v1/admin-api/api-keys` — 本租户管理密钥视图（无 digest/secret）。
- `GET /api/v1/admin-api/quota-rules` — 配额计划 + 当期水位（与 `GET /api/v1/admin/quota-rules` 同口径）。
- `GET /api/v1/admin-api/export-tasks?limit` / `GET /api/v1/admin-api/export-tasks/{id}` — 导出任务元数据
  （**不读/不返回 file_bytes**；创建与下载仍在会话面）。
- `GET /api/v1/admin-api/mcp-access-logs?service&consumer&from&to&limit` — MCP 访问日志
  （参数/窗口/上限同 `GET /api/v1/admin/mcp-access-logs`）。

**写面（批 2 v1，ADR-0016 Accepted A+C，2026-09-08）**
- `POST/PATCH/DELETE /api/v1/admin-api/alert-rules[/{id}]` + `GET` 列表/单个 — 与
  `GET/POST/PATCH/DELETE /api/v1/admin/alert-rules` 同语义（C：表无执行者列，租户即边界）。
- `POST/PATCH/DELETE /api/v1/admin-api/webhooks[/{endpointId}]` + `GET`/`{id}/deliveries` 与
  `POST /{endpointId}/test` — 与人类端点同语义同 SSRF 校验。
- `POST /api/v1/admin-api/export-tasks?format&from&to` → 202（A 委托：任务的
  `created_by` = 该机器密钥的发行管理员；响应与下载面不含文件字节）。
- 错误码沿用人类端点；新增 `EXECUTOR_UNKNOWN`（403，密钥缺发行管理员时写面拒绝）。
- 可运行示例与最小权限建议：`scripts/open-api-examples/`（curl.sh / example.py / README.md）。

**Virtual Key 委托创建（批 2 v2，ADR-0016 增补 2026-09-09 案 1，issue #263）**
- `POST /api/v1/admin-api/virtual-keys` body `{userId, name?, projectId, providerProductId,
  credentialGrantId, purpose, allowedModels?, cachePolicy?}`（= §4 建钥字段 + 目标 `userId`）
  → 201 `{id, secret, shownOnce:true, baseUrl, display, createdAt, version}`；secret 仅此一次，
  **钥归属 = 目标用户**（`user_id` 落目标），成员/授权校验按目标执行。
- `GET /api/v1/admin-api/virtual-keys?userId=` → 该租户用户拥有的钥视图列表（无 secret，
  便于开通流程查询/校验）。
- 委托边界（案 1）：委托人 = 密钥发行管理员且现行角色须为 SYSTEM_ADMIN，否则 403
  `DELEGATION_FORBIDDEN`；目标用户不存在 → 404 `TARGET_USER_NOT_FOUND`、停用 → 409
  `TARGET_USER_INACTIVE`；目标非项目成员 → 403 `PROJECT_MEMBERSHIP_REQUIRED`
  （SYSTEM_ADMIN 目标豁免，同自助）；创建链不变量全部沿用（tag/授权/模型/缓存）。
- 审计：`VIRTUAL_KEY_CREATE`，actor = 委托人，change_summary 含 `targetUserId`。

**鉴权规则（批 1b 硬化）**
- 机器密钥：无效/吊销/过期 → 401 `ADMIN_API_KEY_INVALID`；密钥身份租户化，跨租户不可见。
- 门户会话：仅 SYSTEM_ADMIN 可访问开放面（403 `ADMIN_API_FORBIDDEN`，其他角色）；会话租户即开放面租户。
- 安全红线不变：密钥只存摘要、吊销即时、机器调用走审计（操作审计沿用既有链）、正文不落库、导出文件字节
  不上机器面。批 3 作用域/频控可选。
