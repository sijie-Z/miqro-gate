# ADR-0010：外部系统 API 认证通道（消费者 API Key）与计费查询 API

- 状态：Accepted
- 日期：2026-08-31
- 关联：[platform-middleware-roadmap.md](../platform-middleware-roadmap.md)（P0 身份地基）、阿里云 AI 网关消费者认证（API Key / JWT）、腾讯云消费者体系

## 决策

为外部系统（平台）新增独立的 API 认证通道，与现有门户会话认证并存：

| 通道 | 使用者 | 认证方式 | 保护范围 |
|---|---|---|---|
| 门户通道（现有） | 人（门户用户） | session + CSRF（Argon2id 登录） | `/api/v1/auth`、`/api/v1/me/**`、`/api/v1/admin/**` |
| **系统通道（新增）** | 外部系统（平台） | **API Key**（`Authorization: Bearer mqk_api_…` 或 `X-API-Key`） | `/api/v1/billing/**` |

**API Key 安全规范**（对齐现有凭证规范）：
- 格式 `mqk_api_<8 hex 前缀>_<32 hex secret>`，**只存 SHA-256 哈希**（对齐 Virtual Key 的 HMAC 摘要策略）
- 明文只显示一次（创建响应），无法找回；吊销立即生效
- 消费者状态 `ACTIVE | DISABLED`

**计费查询 API**（系统通道保护的对外面）：
- `GET /api/v1/billing/summary?from&to&groupBy` —— 全租户用量/成本汇总（复用 `AdminUsageStatsService`）
- `GET /api/v1/billing/records` —— 分页明细（复用既有查询）
- 仅元数据（时间/模型/Token/成本），无正文；租户隔离由系统身份决定
- 管理员 session 也可访问（便于本地调试与审计）

## 原因

- Leader 蓝图：网关作为核心中间件，计费查询数据源给平台。
- 两家云厂商（腾讯/阿里）均为「消费者 + API Key」模型，网关自管外部身份，不接外部 OAuth；用户级映射等平台注册细节明确后追加（届时可在消费者上绑定平台 user_id）。
- 不动现有安全模型：双通道并存，互不影响。

## 后果

- 新增 `api_consumers` 表（V13）、`ApiKeyAuthFilter`（仅拦截 `/api/v1/billing/**`）、管理员消费者管理 API。
- 平台接入方式：管理员创建消费者 → 获取一次性 API Key → 调计费 API。
- 用户级同步、JWT 确权（平台自签）列为后续（等待平台注册/确权细节）。
