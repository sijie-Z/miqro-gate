# ADR-0015：管理开放 API 的机器凭据（Admin API Keys）

- 日期：2026-09-07
- 状态：Accepted（owner 2026-09-07「都做」拍板；批 1 动工）
- 关联：ADR-0010/0011（api_consumers/JWT 机器通道先例）、F59（backlog）、
  docs/open-admin-api-plan.md（三批拆解）

## 背景

leader 方向（2026-09-07）：像腾讯 AI 网关一样，除 Web 控制台外提供可编程
API 操作（POST/GET 以 API 形式管理）。仓库现状 = `/api/v1/**` 全量 REST +
OpenAPI 3.1 + CI breaking-check，但管理面认证面向“人”（会话 + CSRF + F05
门户白名单）。缺：机器对机器管理凭据 + 对外文档/示例。

## 决策

1. **凭据形态**：管理机器密钥 = 256-bit 随机（展示前缀 `mqk_admin_` + hex）；
   库中仅存 **SHA-256 摘要（bytea）**（与 Virtual Key / api_consumers 同族）；
   明文只显示一次。可设可选过期；支持吊销（即时生效，按请求查库校验）。
2. **传输**：`Authorization: Bearer mqk_admin_…`。机器通道**豁免 CSRF**
   （非 cookie 承载；批 1 全部为 GET，CSRF/Origin 拦截天然跳过）。
3. **Principal 建模（关键决策点）**：机器身份 = 租户级系统主体，无 userId。
   **批 1 只开放只读子集**（/api/v1/admin-api/usage/summary + records）；
   写操作与更多读面在批 1b/批 2 评估 machine-executor 语义后扩展。
4. **治理默认**：批 1 不限频（与 F05 默认一致）；作用域与频控列入批 3 可选。
5. **安全红线**：密钥只存摘要；展示仅前缀；吊销即时；机器调用全量审计
   （`ADMIN_API_KEY_ISSUE/REVOKE` 用既有 AuditService）；正文依旧不落库；
   与 api_consumers（外部平台账单通道）语义分离登记，不混用。

## 影响

- 新表 V32 `admin_api_key`（tenant_id、name、key_digest、key_prefix、
  created_by、expires_at、revoked_at、created_at）；每租户多把、同名唯一。
- 新管理端点（SYSTEM_ADMIN-only）：POST/GET /api/v1/admin/api-keys、
  POST /{id}/revoke，审计两事件。
- 新过滤链 `AdminApiKeyAuthFilter`：/api/v1/admin-api/**，Bearer 摘要校验 +
  过期/吊销即时判定；门户会话放行；无效 401 `ADMIN_API_KEY_INVALID`。
- 文档：configuration-reference 新行、api-contract §新节、OpenAPI 自动随
  controller 生成（breaking-check 覆盖）。

## 测试与验收

- 单测：生成（前缀/摘要长度/明文不落库）、过期 active 翻转、吊销即时与
  重复吊销冲突、未知 404、重名冲突、审计两事件。
- 集成（批 1b 补）：发行→吊销即时 401→过期 401→摘要不可逆→读端点 200、
  写/全量面维持既有鉴权。
- CI 全绿后并入 develop。

## 未决

- 写面与更多读面扩展需本 ADR 修订（machine-executor 的 userId/created_by
  语义）；批 3 作用域/频控待排。
