# API 契约

本文定义 Control Plane 管理接口和 Gateway 推理入口的稳定边界。实现后以生成的 OpenAPI 为机器可读事实，但 OpenAPI 不得改变本文的业务语义。

## 1. 通用约定

- 管理 API 前缀：`/api/v1`；推理 API 保持上游原生路径，例如 `/v1/messages`。
- 管理 API 使用门户会话 Cookie；Gateway 使用 `Authorization: Bearer <virtual-key>` 或上游协议要求的等价 Header。
- JSON 字段使用 `camelCase`，数据库字段使用 `snake_case`，时间为 UTC RFC 3339。
- 资源 ID 使用不可枚举的 UUIDv7；金额以最小货币单位或 `decimal string + currency` 表示，不使用浮点数。
- 列表默认按 `createdAt DESC, id DESC`，使用不透明 cursor，禁止 offset 深分页。
- 写请求支持 `Idempotency-Key`；重复键和不同请求体返回 `409 IDEMPOTENCY_CONFLICT`。
- 可更新资源返回 `version`，更新时提交 `If-Match`；版本冲突返回 `412 VERSION_CONFLICT`。
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

登录失败返回通用的 `401 UNAUTHORIZED`，无论用户不存在、密码错误、账号禁用或锁定均使用相同消息 `"Invalid username or password."`。

## 3. 身份与会话

### 3.1 认证端点

| 方法与路径 | 用途 | 访问者 |
|---|---|---|
| `POST /api/v1/auth/bootstrap` | 一次性创建首个 SYSTEM_ADMIN 管理员 | 匿名（需 bootstrap secret） |
| `POST /api/v1/auth/login` | 用户名/密码登录，创建会话 | 匿名 |
| `POST /api/v1/auth/register` | 自助注册（F-REG）：创建普通用户并直接登录 | 匿名（开关 `miqrokey.registration-enabled`，默认开） |
| `POST /api/v1/auth/logout` | 当前会话失效 | 已登录 |
| `GET /api/v1/auth/me` | 当前用户、角色、会话到期时间 | 已登录 |
| `POST /api/v1/auth/password` | 修改自己的密码并撤销其他会话 | 已登录 |
| `GET /api/v1/auth/csrf` | 获取 CSRF token（从配置名称的 Cookie 读取） | 已登录 |

### 3.1b 自助注册（F-REG）

`POST /api/v1/auth/register`：`{ "username", "displayName"?, "password" }` → `201`（响应体与 `/login` 相同，并下发同一套会话 Cookie，注册即登录）。语义：

- 只创建 `USER` 角色账号（管理员仍走 `/admin/users` 邀请制流程）；`mustChangePassword=false`（密码为本人所设）。
- 校验：用户名空白/超长 → `400 USERNAME_INVALID`；重复 → `409 USERNAME_TAKEN`（租户行锁序列化并发注册）；密码不满足策略（长度/字符类别/常见密码）→ `400 PASSWORD_INVALID`。
- 开关 `miqrokey.registration-enabled`（`MIQROKEY_REGISTRATION_ENABLED`，默认 `true`）为 `false` 时 → `403 REGISTRATION_DISABLED`；登录、bootstrap 不受影响。私有化部署需要"仅邀请"时可关闭。
- 公开端点：与 login/bootstrap 一样无会话、无 CSRF 要求；审计事件 `REGISTER`。
- 防滥用注记：单租户内部/试用规模未加频率限制；对外公网部署建议在网络层加速率限制（记录于配置参考）。

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
| `GET /api/v1/me/usage/summary` | 自己的聚合用量和成本 |
| `GET /api/v1/me/usage/records` | 自己的明细，受分页和最大时间窗限制 |

创建请求：

```json
{
  "name": "claude-code-main",
  "projectId": "0190...",
  "providerProductId": "0190...",
  "credentialGrantId": "0190...",
  "purpose": "CLAUDE_CODE",
  "allowedModels": ["provider-model-id"]
}
```

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

### 4.4 用量汇总 `GET /api/v1/me/usage/summary`

参数：`groupBy`（`project | virtual_key | cache_level | day`，默认 `project`）、`from`、`to`（ISO-8601，默认最近 93 天窗口；`from` 必须在 `to` 之前，窗口超过 93 天拒绝）。

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
        "savedByGatewayCache": 0.0
      }
    }
  ],
  "totals": { "requests": { "upstream": 12, "coalesced": 0, "l1Hit": 0, "l2Hit": 0 }, "tokens": { "input": 1200, "output": 800, "cacheRead": 0, "cacheCreation": 0 }, "cost": { "upstreamPaid": 0.0128, "gatewayObserved": 0.0128, "projectAllocated": 0.0128, "savedByGatewayCache": 0.0 } }
}
```

- 用量明细只包含自己的 Key 产生的记录；他人的 Key 不出现也不可区分（统一 404）。
- `upstreamPaid` 按 `price_snapshot`（每百万 token 单价，来源 `MANUAL|OFFICIAL|ESTIMATED`）计算；无价格快照的模型按 `0` 计。
- 缓存命中产生的成本节省记入 `savedByGatewayCache`，不计入 `projectAllocated`。

### 4.5 用量明细 `GET /api/v1/me/usage/records`

参数：`from`、`to`（ISO-8601）、`page`（默认 1，≥1）、`size`（默认 50，1–200）。按时间倒序。

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
      "virtualKeyId": "0190..."
    }
  ],
  "page": 1,
  "size": 50,
  "total": 12
}
```

- `cacheLevel` ∈ `UPSTREAM | COALESCED | L1_HIT | L2_HIT`。缓存命中行没有 token 数（NULL → 0）且 `isComplete=false` 时不作为上游用量计入。
- `usageMissing=true` 表示上游未返回 usage（如异常中断）；该行仍入账但用量为 0，便于排查。
- `providerRequestId` 在 tenant 内唯一（幂等写，重复 flush 不双计）。

### 4.6 模型申请（审批流）`POST/GET /api/v1/me/model-approvals`

用户在 Virtual Key 上申请授权范围外的模型；管理员在 `5.18` 审批队列处理。

- `POST /api/v1/me/model-approvals`：`{ "virtualKeyId", "modelId", "reason"? }` → 201 `ModelApprovalView`。
  - `modelId` 精确匹配（trim、≤ 128、禁控制字符）；理由 ≤ 500。
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

- 响应 = `QuotaRuleView[]`（同 `5.19` 管理端视图字段：metric/period/limitValue/warnPercent/status/used/usedPct/level/windowFrom/windowTo 等）——仅含 `scopeId == 当前用户` 的行，其他人/项目规则绝不出现。
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
- `/api/v1/admin/audit-events`：不可修改的管理审计事件。
- `/api/v1/admin/usage-deletions`：双确认后人工删除用量范围。

真实凭证写接口只接受明文输入，响应只返回掩码、指纹、版本和验证状态。凭证测试不得自动把未保存值写入数据库。

### 5.0 组织（G5.2）

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/users` | 用户列表（**永不返回 passwordHash**，Jackson mixin 全局排除） |
| `POST /api/v1/admin/users` | 创建用户（`username`/`displayName`/`role`）；返回一次性临时密码（仅本次出现） |
| `PATCH /api/v1/admin/users/{id}` | 更新状态（`status`：ACTIVE/DISABLED；禁用即撤销全部会话；SYSTEM_ADMIN 不可禁用 → 409 `ADMIN_NOT_DISABLEABLE`） |
| `POST /api/v1/admin/users/{id}/reset-password` | 重置密码 + 撤销全部会话；返回新临时密码（仅本次） |
| `POST /api/v1/admin/users/{id}/revoke-sessions` | 撤销该用户全部会话 |
| `GET /api/v1/admin/users/{id}/project-memberships` | 用户所属项目列表（`[{projectId, projectCode, projectName, projectStatus, joinedAt}]`，按 code 排序）——管理员「加入项目」快捷入口数据面（F-REG 闭环）；用户不存在 `404 USER_NOT_FOUND` |
| `GET/POST /api/v1/admin/teams`、`PATCH /{id}` | 团队列表/创建/更新 |
| `GET/POST /api/v1/admin/teams/{id}/members`、`DELETE /members/{userId}` | 团队成员管理 |
| `GET/POST /api/v1/admin/projects`、`PATCH /{id}` | 项目列表/创建（`code` 唯一，冲突 → 409 `PROJECT_CODE_TAKEN`）/更新 |
| `GET/POST /api/v1/admin/projects/{id}/members`、`DELETE /members/{userId}` | 项目成员管理 |
| `GET/POST /api/v1/admin/grants` | Grant 列表/创建（`projectId`×`providerProductId`×`credentialId` + `models[]`；重复 → 409 `GRANT_EXISTS`） |
| `GET/POST /api/v1/admin/grants/{id}/models`、`DELETE /{id}` | 模型范围查询/替换；禁用 Grant |

错误码：`USER_NOT_FOUND`/`TEAM_NOT_FOUND`/`PROJECT_NOT_FOUND`/`GRANT_NOT_FOUND`（404）、`USERNAME_TAKEN`/`PROJECT_CODE_TAKEN`/`GRANT_EXISTS`（409）、`USERNAME_INVALID`（400）、`ADMIN_NOT_DISABLEABLE`（409）。所有写操作写审计事件（`USER_CREATE`/`USER_STATUS`/`USER_PASSWORD_RESET`/`USER_SESSIONS_REVOKED`/`TEAM_*`/`PROJECT_*`/`GRANT_*`）。

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

管理员录入真实供应商凭证并管理其生命周期（G1.6）。真实凭证属于供应商产品订阅，不绑定用户；只有 SYSTEM_ADMIN 可操作。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/credentials` | 租户内全部凭证（掩码视图） |
| `GET /api/v1/admin/credentials/{id}` | 凭证元数据 + 完整版本历史（新版本在前） |
| `POST /api/v1/admin/credentials` | 创建：`{ "name", "subscriptionId", "secret" }`，返回 `201` 掩码视图 |
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
- 审计事件 `CREDENTIAL_CREATE` / `CREDENTIAL_ROTATE` / `CREDENTIAL_DISABLE` 只记变更摘要，永不包含明文或完整指纹。

错误码：

| code | HTTP | 场景 |
|---|---|---|
| `SUBSCRIPTION_NOT_FOUND` | 404 | 订阅不存在或不属于本租户 |
| `CREDENTIAL_NOT_FOUND` | 404 | 凭证不存在或不属于本租户（统一 404，防枚举） |
| `CREDENTIAL_INVALID` | 400 | Secret 格式非法（过短/过长/含控制字符） |
| `CREDENTIAL_NOT_ROTATABLE` | 409 | 仅 ACTIVE 可轮换 |
| `CREDENTIAL_NOT_DISABLEABLE` | 409 | 已 DISABLED/INVALID 的凭证不可再禁用 |

### 5.2 全局用量查询（G4.1）

管理员全局汇总与明细，返回形状与个人端（§4.4/§4.5）一致，但作用域为整个租户，并支持可选维度过滤：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/usage/summary` | 全租户聚合汇总 + 成本 |
| `GET /api/v1/admin/usage/records` | 全租户分页明细，时间倒序 |

`summary` 参数：`groupBy`（`project` | `virtual_key` | `cache_level` | `day`，默认 `project`）、`from`、`to`（同个人端 93 天窗口规则）、可选过滤 `userId`、`projectId`、`virtualKeyId`、`credentialId`、`subscriptionId`（Plan）、`providerProductId`（供应商产品）、`modelId`。

`records` 参数：`from`、`to`、`page`（默认 1）、`size`（默认 50，1–200）及与 `summary` 相同的可选过滤。

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
- 价格取分配时刻最新快照（逐事件价格快照为延后列）；`currency` 取订阅币种（缺省 USD）。
- 错误码：`SUBSCRIPTION_NOT_FOUND`（404）、`TIME_RANGE_INVALID` / `TIME_RANGE_TOO_WIDE`（400，窗口 ≤ 93 天）。

### 5.5 原始记录导出（G4.4）

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/exports?format=CSV\|JSONL&from&to` | 创建导出任务，返回 `202` + 任务（异步执行） |
| `GET /api/v1/admin/exports/{id}` | 任务状态（不含产物字节） |
| `GET /api/v1/admin/exports/{id}/download` | 下载 gzip 产物（`Content-Type: application/gzip`、`X-MiQroKey-SHA256` 校验头） |
| `GET /api/v1/admin/exports?limit` | 最近任务列表 |

- 窗口 ≤ 93 天；产物只含计数与元数据列（见 database-schema `export_tasks`），绝不包含 prompt、代码、Secret 或 Virtual Key 明文。
- 产物保存 24 小时后 `EXPIRED`，下载返回 `410 EXPORT_EXPIRED`；未完成/不存在 → `404 EXPORT_NOT_FOUND`。
- **GC（F06）**：定时回收过窗产物（`miqrokey.cleanup.expired-sweep-ms`，默认 1h）——`SUCCEEDED` 且超过 `expires_at` 的行连同 `file_bytes` 物理删除；清理后下载返回 `404 EXPORT_NOT_FOUND`（410 语义仅在清理前可观测）。`FAILED`/`PENDING` 行保留供运维查看。
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

### 5.7 Webhook 端点（G4.5）

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/webhooks` | 创建（`name`/`url`/`secret`/`timeoutMs`）；URL 经 SSRF 门控，Secret 加密存储且永不返回 |
| `GET /api/v1/admin/webhooks` / `/{id}` | 列表/详情（无 Secret） |
| `PATCH /api/v1/admin/webhooks/{id}` | 更新（name/enabled/timeoutMs） |
| `DELETE /api/v1/admin/webhooks/{id}` | 删除 |
| `POST /api/v1/admin/webhooks/{id}/test` | 发送 HMAC 签名测试载荷，返回上游 HTTP 状态或脱敏错误 |
| `GET /api/v1/admin/webhooks/{id}/deliveries` | 投递历史 |

投递签名：`X-MiQroKey-Signature: sha256=<HMAC-SHA256(secret, payload) hex>`，payload 为事件 JSON（eventId/ruleId/type/value/occurredAt）。错误码：`WEBHOOK_URL_REJECTED`（400，SSRF 门控）、`WEBHOOK_NOT_FOUND`（404）。

### 5.8 告警规则（G4.5/G8.3）

| 方法与路径 | 用途 |
|---|---|
| `POST /api/v1/admin/alert-rules` | 创建（`name`/`type`/`threshold`/`dedupeMinutes`/`webhookEndpointId`/`scopeJson`） |
| `GET /api/v1/admin/alert-rules` / `/{id}` | 列表/详情 |
| `PATCH /api/v1/admin/alert-rules/{id}` | 更新（含 enabled、scopeJson） |
| `DELETE /api/v1/admin/alert-rules/{id}` | 删除 |

规则类型：`USAGE_MISSING_RATE`（1h 内 usage_missing 占比）、`UPSTREAM_ERROR_RATE`（1h 内非 2xx 占比）、`BALANCE_UNAVAILABLE`（1h 内 UNAVAILABLE 配额快照数）、`USAGE_SURGE`（当前 1h 事件数 / 前一 1h 比率）、**`BUDGET_THRESHOLD`**（项目当月预算水位 %，`scopeJson: {"projectId": "…"}` 必填且项目需存在，否则 `400 SCOPE_INVALID`）、**`QUOTA_THRESHOLD`**（配额规则当前窗口水位 %，`scopeJson: {"quotaRuleId": "…"}` 必填且配额规则需存在，否则 `400 SCOPE_INVALID`；规则停用即不评估）。评估周期 `miqrokey.alerts.evaluation-interval-ms`（默认 5min）；`BUDGET_THRESHOLD` 按（规则 × 月份）、`QUOTA_THRESHOLD` 按（规则 × 配额重置窗口，日/周/月随规则周期）去重（同窗口仅告警一次），其余按（规则 × 小时桶）去重；仅首个事件触发投递；投递失败指数退避重试最多 3 次。错误码：`ALERT_RULE_NOT_FOUND`（404）、`ALERT_TYPE_INVALID`（400）、`SCOPE_INVALID`（400）。

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

快照字段：`id`、`providerProductId`、`modelId`、`tokenType`（`INPUT`/`OUTPUT`/`CACHE_READ`/`CACHE_CREATION`）、`currency`、`unitPrice`（BigDecimal，每 1M Tokens）、`effectiveFrom`、`source`（`MANUAL`/`OFFICIAL`）、`createdBy`、`createdAt`。

错误码：

| code | HTTP | 场景 |
|---|---|---|
| `PRODUCT_NOT_FOUND` | 404 | 供应商产品不存在 |
| `PARAM_INVALID` | 400 | tokenType 非法或参数校验失败 |

### 5.10 外部系统计费通道与 API 消费者（G8.1，ADR-0010）

平台等外部系统通过独立 API 通道查询计费数据，与门户会话认证并存。

**API 消费者**（管理员管理）：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/api-consumers` | 消费者列表（掩码视图 + JWT 公钥指纹） |
| `POST /api/v1/admin/api-consumers` | 创建：`{ "name" }` → `201`，返回一次性 API Key（明文仅此一次） |
| `POST /api/v1/admin/api-consumers/{id}/disable` | 立即吊销（禁用的 Key 即刻失效） |
| `PUT /api/v1/admin/api-consumers/{id}/jwt-key` | 设置/轮换 JWT 验签公钥：`{ "publicKeyPem" }`（RSA PEM SubjectPublicKeyInfo）→ 返回带 `jwtKeyFingerprint` 的视图；非法 PEM → `400 JWT_KEY_INVALID` |
| `DELETE /api/v1/admin/api-consumers/{id}/jwt-key` | 移除公钥（JWT 认证立即失效） |

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
- 响应仅元数据（时间/模型/Token/成本/配额），无正文
- 错误码：`CONSUMER_NAME_TAKEN`（409）、`CONSUMER_NOT_FOUND`（404）、`CONSUMER_ALREADY_DISABLED`（409）、`JWT_KEY_INVALID`（400）、匿名 401

### 5.11 项目月度预算（G8.2，配额管理）

项目级月度预算：**只预警不阻断**（符合「不因预算阻断」产品决策），水位按当月分摊成本实时计算。对标腾讯消费者配额管理的预警状态（正常/预警/超限）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/budgets?month` | 全部项目当月预算 + 水位（`month` 缺省为当月） |
| `GET /api/v1/admin/projects/{projectId}/budget?month` | 单项目预算 + 水位 |
| `PUT /api/v1/admin/projects/{projectId}/budget` | 创建/更新（按 `(project, month)` upsert）：`{ "month", "amount", "currency"?, "alertThresholdPct"? }` |
| `DELETE /api/v1/admin/projects/{projectId}/budget?month` | 删除（`204`） |

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
| `GET /api/v1/skills` | 目录（登录用户可见全部 ACTIVE） |
| `GET /api/v1/skills/{id}` | 详情（元数据，无包体） |
| `GET /api/v1/skills/{id}/download` | 下载 zip（授权门禁；公开 = 全员可下） |
| `POST /api/v1/admin/skills?version=1.0.0` | 上传（raw zip body，`Content-Type: application/zip`）；重传同名 = upsert 替换并恢复 ACTIVE |
| `GET /api/v1/admin/skills` | 管理目录 |
| `POST /api/v1/admin/skills/{id}/archive` | 归档（目录隐藏、数据保留、授权保留） |
| `PUT /api/v1/admin/skills/{id}/access` | 整体替换下载授权：`[{"scopeType":"TEAM\|PROJECT","scopeId":"…"}]`；空数组 = 公开 |

**格式校验（上传时）**：zip 必须只含一个技能目录（`skill-name/`），含 `SKILL.md`（YAML frontmatter：`name` 必填且为小写 kebab-case、与目录名一致、不含 claude/anthropic 保留词；`description` 必填 ≤ 1024 字符；可选 `author`/`license`/`tags`）。包上限 5MB、条目上限 200、SKILL.md 上限 512KB（防 zip 炸弹——只读 SKILL.md，不解压）。`version` 必填语义化（`\d+\.\d+\.\d+`）。

**下载授权语义**：无 `skill_access` 行 = 公开；有行 = 仅授权 TEAM/PROJECT 成员（及管理员）可下载；非成员 `403 SKILL_DOWNLOAD_FORBIDDEN`；归档技能对目录/详情/下载一律 `404 SKILL_NOT_FOUND`。

**错误码**：`SKILL_NOT_FOUND`（404）、`SKILL_DOWNLOAD_FORBIDDEN`（403）、`VERSION_INVALID`（400）、`SKILL_EMPTY`/`SKILL_TOO_LARGE`/`SKILL_TOO_MANY_ENTRIES`/`SKILL_ZIP_INVALID`/`SKILL_MD_MISSING`/`SKILL_MD_TOO_LARGE`/`SKILL_FRONTMATTER_INVALID`/`SKILL_NAME_INVALID`/`SKILL_NAME_MISMATCH`/`SKILL_DESCRIPTION_INVALID`（400）、`SCOPE_INVALID`（400）。

### 5.13 Agent 管理（P3.1，对标阿里 AI 网关 Agent 拓扑）

管理智能体资源：Agent 的**出口**绑定一个 ACTIVE 上游凭证（供应商产品由凭证 → 订阅派生，前端无需联动选择）；用量按绑定凭证聚合，形成按 Agent 维度的观测。入口路由（外部访问 Agent）为后续扩展。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/agents` / `/{id}` | 列表/详情（含派生的凭证名与产品名） |
| `POST /api/v1/admin/agents` | 创建：`{ "name", "description"?, "credentialId" }`；凭证必须存在且 ACTIVE（`400 CREDENTIAL_NOT_FOUND`）、重名 `409 AGENT_NAME_TAKEN`、**凭证已被其他 Agent 绑定 `409 AGENT_CREDENTIAL_TAKEN`**（1:1 规则：一个凭证只支持一个 Agent，保证按 Agent 用量可区分） |
| `POST /api/v1/admin/agents/{id}/disable` | 禁用（`409 AGENT_ALREADY_DISABLED` 重复禁用） |
| `GET /api/v1/admin/agents/{id}/usage?from&to` | 按绑定凭证的用量汇总（请求/Token/成本，默认近 93 天） |

**响应 `AgentView`**：`name`/`description`/`credentialId`/`credentialName`/`providerProductId`/`providerProductName`（派生）/`status`/`createdAt`。

**错误码**：`AGENT_NOT_FOUND`（404）、`AGENT_NAME_TAKEN`（409）、`AGENT_CREDENTIAL_TAKEN`（409）、`AGENT_ALREADY_DISABLED`（409）、`CREDENTIAL_NOT_FOUND`（400）。

### 5.14 内部服务注册表（P3.2）

平台组件、MCP 端点等内部服务经网关集成前的注册目录（对标腾讯服务来源）。服务地址必须为 https、不含用户信息/查询参数/片段（镜像上游目标规则）。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/services` / `/{id}` | 列表/详情 |
| `POST /api/v1/admin/services` | 注册：`{ "name", "kind"?, "description"?, "baseUrl" }`（`kind` ∈ `HTTP\|MCP\|OTHER`，缺省 `HTTP`） |
| `POST /api/v1/admin/services/{id}/disable` | 禁用（`409 SERVICE_ALREADY_DISABLED` 重复禁用） |

**错误码**：`SERVICE_NOT_FOUND`（404）、`SERVICE_NAME_TAKEN`（409）、`SERVICE_ALREADY_DISABLED`（409）、`BASE_URL_INVALID`（400）。

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
| `POST /api/v1/admin/mcp-services` | 注册：`{ "name", "description"?, "endpoint", "transport"?, "checkIntervalSeconds"?, "checkTimeoutSeconds"?, "failThreshold"?, "recoverThreshold"?, "checkPath"? }`（默认 STREAMABLE_HTTP / 30s / 5s / 3 / 1 / `/health`；注册即自动生成 default 路由，见 5.23） |
| `POST /api/v1/admin/mcp-services/{id}/status?status=ONLINE\|OFFLINE` | 手动上下线（重复切换 `409 MCP_STATUS_UNCHANGED`） |
| `POST /api/v1/admin/mcp-services/{id}/health-config` | 更新健康检查配置 |

- 接入地址：https、无 userinfo/query/fragment（`MCP_ENDPOINT_INVALID` 400）；重名 `409 MCP_SERVICE_NAME_TAKEN`
- **健康检查**：`McpHealthChecker` 定时（`miqrokey.mcp.health-cycle-ms` 默认 15s）遍历 ONLINE 服务，按各自间隔探测 `endpoint + checkPath`（GET，2xx 计健康）；连续失败达 `failThreshold` → `UNHEALTHY`，连续成功达 `recoverThreshold` → `HEALTHY`；OFFLINE 服务不被探测
- **错误码**：`MCP_SERVICE_NOT_FOUND`（404）、`MCP_SERVICE_NAME_TAKEN`（409）、`MCP_STATUS_UNCHANGED`（409）、`MCP_STATUS_INVALID`（400）、`MCP_ENDPOINT_INVALID`（400）

### 5.17 MCP Tools 管理（P3.5，对标腾讯 AI 网关 Tools 管理）

工具注册在 MCP 服务下，逐个启用/禁用（腾讯 Tools 管理语义）；工具名是 AI Agent 调用该工具的唯一标识。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/mcp-services/{id}/tools` | 服务下工具列表 |
| `POST /api/v1/admin/mcp-services/{id}/tools` | 手动创建：`{ "toolName", "description"?, "method"?, "path" }`（方法默认 GET） |
| `POST /api/v1/admin/mcp-services/{id}/tools/{toolId}/status?status=ENABLED\|DISABLED` | 单个工具启用/禁用（重复切换 `409 TOOL_STATUS_UNCHANGED`） |

- `toolName` 规则：小写字母开头 snake_case（`TOOL_NAME_INVALID` 400）；`path` 必须以 `/` 开头（`TOOL_PATH_INVALID` 400）；同服务重名 `409 TOOL_NAME_TAKEN`；服务不存在 `404 MCP_SERVICE_NOT_FOUND`
- **错误码**：`TOOL_NOT_FOUND`（404）、`TOOL_NAME_TAKEN`（409）、`TOOL_STATUS_UNCHANGED`（409）、`TOOL_STATUS_INVALID`（400）、`TOOL_NAME_INVALID`（400）、`TOOL_PATH_INVALID`（400）

### 5.18 模型审批队列（原始设计文档 §8.2，SYSTEM_ADMIN-only）

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/model-approvals?status=&size=&before=` | 审批队列（keySet 游标分页） |
| `POST /api/v1/admin/model-approvals/{id}/approve` | 通过（`{ "reviewNote"? }`）→ 生效 |
| `POST /api/v1/admin/model-approvals/{id}/reject` | 驳回（`{ "reviewNote"? }`） |

- `status` ∈ `PENDING\|APPROVED\|REJECTED`，缺省返回全部；`size` 默认 20、上限 100；`before` 为上一页 `nextCursor`（不透明，编码 `(created_at, id)`；非法游标 `400 PARAM_INVALID`）。倒序返回 `{ "items": [ModelApprovalView], "nextCursor" }`。
- **通过语义**：写入 `virtual_key_models`（申请 Key）+ 若模型不在 Grant 中先写入 `project_provider_grant_models`（网关按 `key.models ∩ grant.models` 放行，两处缺一不可），随后**立即**触发路由快照刷新（不等 30s 定时）。同 Grant 其它 Key 不受影响（各自 Key 快照独立）。
- 仅 PENDING 可审批：重复审批 `409 ALREADY_REVIEWED`（乐观锁，并发评审只有一个成功）；Key 已吊销/停用 → `409 KEY_NOT_ACTIVE`；Grant 已停用 → `409 GRANT_INACTIVE`；不存在 → `404 APPROVAL_NOT_FOUND`。
- 审批/驳回写 `MODEL_APPROVAL_APPROVED` / `MODEL_APPROVAL_REJECTED` 审计（含 reviewNote 长度 ≤ 500 校验）。

### 5.19 配额规则（用量配额，platform-middleware roadmap「配额管理」步骤）

只预警不阻断的用量配额（对齐腾讯消费者配额 / 阿里消费者配额，alerting-only）：

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/admin/quota-rules` | 全部规则 + 当前窗口水位（读时计算） |
| `PUT /api/v1/admin/quota-rules` | 新增/更新规则（`(scopeType, scopeId, metric, period)` 为自然键，重复 PUT 原地编辑） |
| `DELETE /api/v1/admin/quota-rules/{id}` | 删除规则（`404 QUOTA_RULE_NOT_FOUND`） |

- 请求体 `{ "scopeType": USER\|PROJECT, "scopeId", "metric": TOKENS\|REQUESTS, "period": DAILY\|WEEKLY\|MONTHLY, "limitValue"（正整数）, "warnPercent"?（1–99，默认 80）, "status"?（默认 ACTIVE）}`；scope 不存在 → `404 SCOPE_NOT_FOUND`（防枚举）。
- **水位口径（读时计算，非预聚合）**：TOKENS = 当期窗口 usage 事件全部 token（input+output+cacheRead+cacheCreation，与个人用量 TotalTokens 同口径）；REQUESTS = 当期到达上游的请求数（缓存命中不计）。窗口为 UTC 切片：DAILY=当日 / WEEKLY=周一起 / MONTHLY=当月（与月度预算同约定）。
- `level`：`NORMAL` → `WARNING`（≥ warnPercent）→ `EXCEEDED`（≥ 100%）。**规则永不阻断流量**；硬阻断需 ADR。
- DISABLED 规则保留计划并展示水位，页面按停用渲染。
- 审计：`QUOTA_RULE_CREATE` / `QUOTA_RULE_UPDATE` / `QUOTA_RULE_DELETE`。
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
| `GET /api/v1/admin/mcp-services/{serviceId}/route-rules` | 规则列表（优先级降序，default 在末尾） |
| `POST /api/v1/admin/mcp-services/{serviceId}/route-rules` | 新建（默认启用）：`{ "name", "description"?, "priority"?, "pathMode"?, "pathValue"?, "hostMode"?, "hostValue"?, "methods"?, "headers"? }` |
| `PATCH /api/v1/admin/mcp-services/{serviceId}/route-rules/{ruleId}` | 全量替换可编辑字段（见上） |
| `POST /api/v1/admin/mcp-services/{serviceId}/route-rules/{ruleId}/status?status=ENABLED\|DISABLED` | 启用/禁用（幂等） |
| `DELETE /api/v1/admin/mcp-services/{serviceId}/route-rules/{ruleId}` | 删除自定义路由 |

- 名称：1–64 字符、同一服务唯一（`409 ROUTE_NAME_TAKEN`）、`default` 保留（`400 ROUTE_NAME_RESERVED`）；描述 ≤200。
- 错误码：`ROUTE_NOT_FOUND`（404）、`ROUTE_NAME_TAKEN`（409）、`ROUTE_NAME_RESERVED`（400）、`ROUTE_NAME_INVALID`（400）、`ROUTE_DESCRIPTION_INVALID`（400）、`ROUTE_PRIORITY_INVALID`（400）、`ROUTE_PATH_INVALID`（400）、`ROUTE_MATCHER_INVALID`（400）、`ROUTE_PATTERN_INVALID`（400）、`ROUTE_METHOD_INVALID`（400）、`ROUTE_HEADERS_TOO_MANY`（400）、`ROUTE_HEADER_INVALID`（400）、`ROUTE_MATCH_CONFLICT`（409）、`ROUTE_DEFAULT_IMMUTABLE`（409）、`ROUTE_STATUS_INVALID`（400）；服务不存在 `404 MCP_SERVICE_NOT_FOUND`。

### 5.24 MCP 访问日志查询 `GET /api/v1/admin/mcp-access-logs`（F15，V29）

MCP 代理调用（F01 入口 `/mcpservers/{serviceName}/mcp`）的**纯元数据审计行**：每次身份可解析的调用（消费者认证通过且服务名解析成功）由网关异步批量写一行；**不存工具参数、请求正文或响应正文**（信封 method/`params.name` 是唯一被读取的正文元数据，raw 16 `aigw.mcp.*` 语义）。`status` 为网关侧终态：`FORWARDED`（上游已应答，`httpStatus`=上游 HTTP 状态）/ `SERVICE_DENIED`、`TOOL_DENIED`、`TOOL_UNAVAILABLE`（ACL，doc 134890，`httpStatus`=403）/ `INVALID_ENVELOPE`（400）/ `UPSTREAM_FAILURE`（60s 预算内无上游应答，`httpStatus` 空）。

- **写入口**：网关 `McpAccessLogSink`（有界队列 4096 + 1s 周期 flush；饱和 drop+计数 WARN；批量失败整批重入队重试）。写入幂等：`(tenant_id, gateway_request_id)` 唯一，重试 flush 不双写。参数 `miqrokey.gateway.mcp-log.capacity` / `.flush-interval-ms`。
- **不落行**：预解析失败（401 未知 Key、404 未知服务）无可信身份，仅留在请求日志——与 usage_event 同口径。
- **查询语义**：新→旧排序（`occurred_at DESC, id DESC`）；`service`/`consumer` 按名称精确过滤；`from`/`to`（ISO-8601 instant，含 `Z`）默认近 24h，窗口 ≤ 31 天（`TIME_RANGE_TOO_WIDE`）；`from > to` → `TIME_RANGE_INVALID`；`limit` 默认 200、上限 1000（`SIZE_INVALID`）；`from/to` 非法格式 → `PARAM_INVALID`。
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

**F12 重试（doc 134831 语义本土化）**：`retryEnabled` + `retryMax`（1–5）+ `retryConditions`（`SERVER_5XX|CONNECTION_FAILURE|TIMEOUT`，启用时至少一项）+ `idempotencyConfirmed`。重试只发生在**网关把上游首字节回给调用方之前**；5xx 重试在响应尚未写出时进行。**非幂等门**：`tools/call` 命中的工具行 method 为 POST/PUT/PATCH 时，未勾选 `idempotencyConfirmed` 一律不重试（防重复写）；GET/HEAD/OPTIONS/DELETE 与无工具行的方法（initialize/tools/list 等）不受限。

**F13 熔断（doc 134859）**：三态 CLOSED/OPEN/HALF_OPEN。滑动窗口 `breakerWindowSeconds`（1–60，默认 10）+ 最小请求数 `breakerMinRequests`（1–100，默认 10）防低流量误判；错误比例触发 `breakerErrorEnabled`/`breakerErrorRatio`（1–100，默认 50）+ `breakerErrorStatusCodes`（400–599、≤32、默认 500/502/503/504，**429 需显式加入**）；慢调用触发 `breakerSlowEnabled`/`breakerSlowCallMs`（100–60000）/`breakerSlowRatio`——两触发至少启用其一。**`breakerSlowCallMs` 必须小于服务自身 `check_timeout_seconds`×1000**（超时字段即健康检查配置的 `checkTimeoutSeconds`；否则慢调用永远观察不到），越界 → `400 RESILIENCE_SLOW_EXCEEDS_TIMEOUT`。OPEN 持续 `breakerOpenSeconds`（5–600，默认 30）后进入 HALF_OPEN，放行 `breakerProbeCount`（1–10，默认 3）个探测，成功 `breakerProbeSuccess`（≤probeCount，默认 2）个即恢复 CLOSED，任一失败重新 OPEN。`breakerSkipRetry`（默认 true）语义：OPEN 期间请求快速失败（503 `circuit_open` 错误信封），天然不进入重试。熔断桶= `tools/call` 按工具名、其余信封方法按方法名，桶间互不影响。

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

内容留痕通道的**租户级总开关**：默认**全关**（无行=任何请求内容不被采集；CLAUDE.md「不保存正文」红线仅在此行 enabled 时按 ADR-0014 §1 例外放行）。GET 返回生效配置（无行时=disabled 默认）；PUT 体 `{"enabled": bool}` 切换并审计 `RETENTION_CONFIG_UPDATE`、经路由快照即时下发网关（运行中生效，无需重启）。v1 固定 `contentScope=USER_TEXT_ONLY`（P1：仅用户消息文本起步，模型回复/工具正文不在范围）与 `keyVersion=v1`（P5：部署密钥集；KMS/轮换随 P5 落地扩展）。启用本身只开通道——网关侧采集/密文信封/Kafka 投递为后续批次（见 ADR-0014 §6）。SYSTEM_ADMIN-only（deny-by-default）；body 非法 → 400。

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
- Key 格式 `mqk_live_<publicKeyId>_<secret>[.<projectTag>]`：点号后缀是**路由标签**（明文，仅用于把请求路由到 Key 绑定的项目），鉴权权威是数据库中的 `key_project_binding`，标签本身不决定授权。HMAC 摘要不包含标签。
- Gateway 使用版本化只读路由快照（定时刷新，默认 30s）做校验与路由；热路径不查询数据库。吊销/轮换按快照刷新传播，宽限期由控制面配置。
- 校验通过后 Gateway 注入该 Key 固定绑定的上游凭证（AES-256-GCM 解密，内存中用完即清零），并把请求转发到该授权对应项目的目标；请求头和体按透明代理规则原样转发。
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
- Header 走私：凭证 Header（`Authorization`/`x-api-key`/`api-key`）出现多个 → `401`，任何凭证都不会转发；`Connection` 提名的 hop-by-hop Header 与 `X-MiQroKey-*` 内部 Header 在转发前剥离；上游只携带 Gateway 注入的真实凭证，客户端 Virtual Key 永不泄漏到上游。

Gateway 生成 `X-MiQroKey-Request-Id`。若供应商已有 request ID，两个 ID 都进入用量记录；不得覆盖供应商 request ID Header。

## 8. OpenAPI 与兼容性

- Control Plane 生成 **OpenAPI 3.1**（F09 已实现）：`GET /v3/api-docs`（springdoc，无 swagger-ui；`springdoc.api-docs.version=OPENAPI_3_1`）。机器可读基线提交于 `docs/openapi/openapi-3.1.json`；CI（backend-integration job）对每次生成结果跑破坏性 diff（`deploy/openapi/check-openapi-breaking.py`：删除 path/operation/response code/参数、属性变 required 即失败）。本文仍是业务语义事实源；生成物是机器可读镜像，OpenAPI 不得改变本文语义。
- 前端 TypeScript client **目前由手写 `frontend/src/api` + `types/api` 维护**（未从 OpenAPI 生成——规格愿景；codegen 迁移列为发布前候选，届时删除手写 DTO）。
- 同一 major 版本只允许新增可选字段和新端点；删除、改名、改变含义必须进入下一 major。
- 推理入口不进入管理 API 的 DTO 生成流程，以透明代理契约和 fixtures 验证。
