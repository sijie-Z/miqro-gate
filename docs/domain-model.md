# 领域模型

## 1. 核心关系

```text
User ──< ProjectMembership >── Project
                                  |
                                  v
                         ProjectProviderGrant
                                  |
                                  v
Provider ──< ProviderProduct ──< UpstreamSubscription
                                  |
                                  v
                         UpstreamCredential
                                  |
                                  v
User + Project + Product + Credential + Purpose
                                  |
                                  v
                             VirtualKey
                                  |
                                  v
                             UsageEvent
```

`tenant_id` 保留在核心表中，但首版固定为一个租户，不实现 SaaS 租户管理。

## 2. 组织模型

### User

- `id`
- `tenant_id`
- `username`
- `display_name`
- `password_hash`
- `role`: `SYSTEM_ADMIN | USER`
- `status`: `ACTIVE | DISABLED | LOCKED`
- `must_change_password`
- `last_login_at`
- `created_at`, `updated_at`

### Team

团队只用于组织人员，不承担授权和成本归集。

- `id`, `tenant_id`
- `name`, `description`
- `status`

### Project

项目是授权、用量和成本归集的核心。

- `id`, `tenant_id`
- `code`, `name`, `description`
- `cost_center`（可选）
- `status`

### ProjectMembership

- `project_id`
- `user_id`
- `created_by`
- `created_at`

首版没有项目管理员，成员关系只能由系统管理员修改。

## 3. 供应商与产品

### Provider

表示供应商或聚合平台，例如腾讯云 TokenHub、阿里云百炼、智谱、MiniMax。

- `id`, `slug`, `display_name`
- `catalog_version`
- `status`
- `official_site_url`, `documentation_url`

### ProviderProduct

表示供应商下一个具有明确协议、鉴权和计费规则的产品，不能只用 Provider 粗略代表。例如：

- 腾讯云 TokenHub Coding Plan；
- 腾讯云 Token Plan 企业版专业套餐；
- 阿里云百炼 Token Plan 团队版；
- MiniMax Token Plan Team Subscription Key；
- DeepSeek 官方按量 API。

字段：

- `id`, `provider_id`, `product_code`, `display_name`
- `billing_mode`: `PAYG | FIXED_SUBSCRIPTION | TOKEN_PACKAGE | CREDIT_POOL | HYBRID`
- `plan_scope`: `NONE | PERSONAL | TEAM | ENTERPRISE`
- `credential_topology`: `SINGLE_SHARED | MULTI_KEY_SHARED_POOL | PER_SEAT_KEY | PER_MEMBER_SUBSCRIPTION_KEY`
- `quota_topology`: `NONE | GLOBAL_SHARED | MEMBER_ISOLATED | DEDICATED_PLUS_SHARED | KEY_CAPPED`
- `supported_wire_protocols`
- `upstream_base_urls`
- `auth_scheme`
- `model_catalog_strategy`
- `plan_status_strategy`
- `balance_authority`: `OFFICIAL_API | LOCAL_ESTIMATE | UNAVAILABLE`
- `status`: `DRAFT | IMPLEMENTED | VERIFIED | DISABLED`

### UpstreamSubscription

表示一次实际购买或开通的套餐/账户资源。按量 API 也可以用一个无周期的 Subscription 表达账户余额容器。

- `id`, `provider_product_id`
- `name`, `external_account_ref`
- `billing_mode`, `plan_scope`
- `subscription_price`
- `currency`
- `period_start`, `period_end`, `renewal_at`
- `quota_total`, `quota_unit`
- `status`
- `last_status_sync_at`
- `status_source`: `OFFICIAL_API | LOCAL_ESTIMATE | MANUAL_UNKNOWN`

管理员不手工维护价格和周期规则；这些数据来自签名目录或供应商状态接口。无法官方获取时，字段可为空并明确标记来源。

### PlanSeat

适用于团队和企业 Plan。

- `id`, `upstream_subscription_id`
- `external_seat_ref`
- `assigned_user_id`（可空）
- `display_name`
- `seat_status`
- `quota_total`, `quota_used`
- `period_start`, `period_end`

PlanSeat 不是所有团队 Plan 都必须存在。腾讯企业 Token Plan 更接近多 Key 共享池；智谱和 MiniMax 团队产品则需要表达成员/席位 Key。

### UpstreamCredential

- `id`, `upstream_subscription_id`
- `credential_name`
- `seat_id`（可空）
- `encrypted_secret`
- `encryption_key_version`
- `secret_fingerprint`
- `status`: `PENDING_VALIDATION | ACTIVE | DRAINING | DISABLED | INVALID`
- `valid_from`, `retired_at`
- `last_validated_at`, `last_validation_error`

一个 Subscription 可以包含多个真实凭证。Virtual Key 始终指向一个明确的 `UpstreamCredential`。

## 4. 项目授权

### ProjectProviderGrant

- `id`, `project_id`
- `provider_product_id`
- `upstream_credential_id`
- `allowed_models`
- `status`
- `created_by`, `created_at`

管理员通过该实体决定项目能使用哪些产品、真实凭证和模型。普通用户不能扩大授权边界。

## 5. Virtual Key

### VirtualKey

- `id`
- `public_key_id`
- `secret_digest`
- `display_prefix`, `last_four`
- `user_id`, `project_id`
- `project_provider_grant_id`
- `upstream_credential_id`
- `purpose`: `CLAUDE_CODE | CLAUDE_DESKTOP | CODEX | CUSTOM`
- `name`
- `allowed_models_snapshot`
- `status`: `ACTIVE | ROTATING | REVOKED | DISABLED`
- `created_at`, `last_used_at`, `revoked_at`
- `replaced_by_key_id`（可空）

建议格式：`mqk_live_<publicKeyId>_<randomSecret>`。`publicKeyId` 用于定位记录，`randomSecret` 至少 256 bit。数据库只保存使用服务端 pepper 计算的 HMAC-SHA-256 摘要。

## 6. 用量与成本

### UsageEvent

每个请求一条，不可原地修改业务事实；流式请求结束后补齐终态可以通过同事务的 finalize 或独立终态事件完成。

- 内部与上游请求 ID；
- 用户、项目、Virtual Key、Subscription、Credential；
- 供应商产品、协议、模型；
- 开始、首包、结束时间；
- HTTP 状态、终态、错误分类；
- 输入、输出、缓存读取、缓存写入、推理和总 Token；
- 供应商原始 usage JSON（仅 usage 字段）；
- 价格目录版本与价格快照；
- 官方按量成本、内部估算成本、币种；
- 客户端断开、是否重试、是否产生部分响应；
- 数据完整性标记。

严禁保存 prompt、代码、工具参数正文或模型回答。

### QuotaSnapshot

保存供应商官方或本地估算的余额/周期状态：

- 归属 Subscription、Seat 或 Credential；
- 窗口类型与起止时间；
- 总量、已用、剩余、单位；
- 数据来源、抓取时间、错误状态；
- 官方原始状态的脱敏 JSON。

### CostAllocation

用于周期结算：

- Subscription 周期固定成本；
- 项目或用户在周期内的 Token 权重；
- 分摊金额；
- 算法版本；
- 生成时间。

## 7. 告警、导出与审计

### AlertRule / AlertEvent

规则由管理员创建，事件记录触发对象、阈值、实际值、Webhook 结果和去重键。

### ExportJob

- 时间范围、过滤条件、格式；
- 创建管理员；
- 状态、文件位置、校验值；
- 过期时间和下载审计。

### AdminAuditEvent

永久保存以下动作：账号、项目、授权、真实凭证、Virtual Key 管理、目录更新、告警配置、导出和数据删除。审计只记录对象标识及变更摘要，不记录完整密钥。

## 8. 数据生命周期

- UsageEvent、QuotaSnapshot、CostAllocation：永久，直到管理员手动删除。
- AdminAuditEvent：永久，不随 UsageEvent 删除。
- 登录会话、临时导出文件、告警投递重试：按配置清理。
- 删除用量时必须指定明确时间范围、二次确认并写入 AdminAuditEvent。
