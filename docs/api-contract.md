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
| `POST /api/v1/auth/logout` | 当前会话失效 | 已登录 |
| `GET /api/v1/auth/me` | 当前用户、角色、会话到期时间 | 已登录 |
| `POST /api/v1/auth/password` | 修改自己的密码并撤销其他会话 | 已登录 |
| `GET /api/v1/auth/csrf` | 获取 CSRF token（从配置名称的 Cookie 读取） | 已登录 |

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

Gateway 生成 `X-MiQroKey-Request-Id`。若供应商已有 request ID，两个 ID 都进入用量记录；不得覆盖供应商 request ID Header。

## 8. OpenAPI 与兼容性

- Control Plane 必须生成 OpenAPI 3.1，并在 CI 检查未提交的破坏性变更。
- 前端 TypeScript client 从 OpenAPI 生成，禁止复制维护 DTO。
- 同一 major 版本只允许新增可选字段和新端点；删除、改名、改变含义必须进入下一 major。
- 推理入口不进入管理 API 的 DTO 生成流程，以透明代理契约和 fixtures 验证。
