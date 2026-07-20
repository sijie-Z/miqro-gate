# 数据库 Schema 规格

## 1. 约定

- PostgreSQL 16+。
- UUID 由应用生成。
- 时间使用 `timestamptz` 和 UTC。
- 金额使用 `numeric(24, 10)`。
- Token/请求计数使用 `bigint`，未知为 NULL。
- 枚举首版使用受 CHECK 约束的 `varchar`，便于迁移；不使用 PostgreSQL enum。
- 所有可变聚合根包含 `version bigint not null default 0`。
- 所有租户表包含 `tenant_id uuid not null`。
- Secret 密文、nonce、摘要使用 `bytea`。

V1 migration 可以创建首批核心表；后续 Goal 只能追加 migration。

## 2. 租户、账号和组织

### `tenants`

| 列 | 类型 | 约束 |
|---|---|---|
| id | uuid | PK |
| code | varchar(64) | UNIQUE NOT NULL |
| name | varchar(200) | NOT NULL |
| status | varchar(32) | ACTIVE/DISABLED |
| created_at/updated_at | timestamptz | NOT NULL |

首版 seed 一个固定租户。

### `users`

关键列：`id`、`tenant_id`、`username`、`display_name`、`password_hash`、`role`、`status`、`must_change_password`、`failed_login_count`、`locked_until`、`last_login_at`、`version`、时间列。

约束/索引：

- `unique (tenant_id, lower(username))` 通过函数唯一索引。
- role 仅 `SYSTEM_ADMIN|USER`。
- status 仅 `ACTIVE|DISABLED|LOCKED`。
- password_hash 永不返回 API。

### `user_sessions`

保存随机 session token 的摘要，不保存明文。包含 `user_id`、`token_digest`、`csrf_secret_digest`、`created_at`、`last_seen_at`、`expires_at`、`revoked_at`、安全 user-agent hash 和 IP 摘要（可选）。

### `teams` / `team_memberships`

团队仅组织人员。`team_memberships` 唯一 `(team_id, user_id)`，删除团队不得级联删除用量快照中的团队名称。

### `projects` / `project_memberships`

`projects` 包含 `code`、`name`、`description`、`cost_center`、`status`、`version`。

唯一：`(tenant_id, code)`。`project_memberships` 唯一 `(project_id, user_id)`。

## 3. 供应商目录

### `providers`

`slug`、`display_name`、`official_site_url`、`documentation_url`、`catalog_version`、`status`、`version`。`slug` 全局唯一。

### `provider_products`

关键列：

- `provider_id`, `product_code`, `display_name`
- `billing_mode`, `plan_scope`
- `credential_topology`, `quota_topology`
- `supported_wire_protocols jsonb`
- `base_url_templates jsonb`
- `auth_scheme jsonb`
- `model_catalog_strategy`, `plan_status_strategy`, `balance_authority`
- `implementation_status`: DRAFT/DOCUMENTED/IMPLEMENTED/VERIFIED/DEGRADED/DISABLED
- `catalog_version`, `version`

唯一 `(provider_id, product_code)`。JSONB 由版本化 JSON Schema 校验后入库。

### `catalog_releases`

`version`、`payload_sha256`、`signature`、`signing_key_id`、`source`、`status`、`imported_at`。只保存已验证的纯数据目录。

### `model_catalog_entries`

`provider_product_id`、`model_id`、`display_name`、`capabilities jsonb`、`context_tokens`、`max_output_tokens`、`active_from/to`、`catalog_version`。

唯一 `(provider_product_id, model_id, catalog_version)`；查询当前模型有组合索引。

### `model_price_entries`

`provider_product_id`、`model_id`、`currency`、各 Token 单价、`unit_tokens`、`effective_from/to`、`catalog_version`。价格不可覆盖历史版本。

## 4. Subscription、席位和凭证

### `upstream_subscriptions`

关键列：

- `provider_product_id`, `name`, `external_account_ref`
- `billing_mode`, `plan_scope`, `status`
- `subscription_price`, `currency`
- `period_start`, `period_end`, `renewal_at`
- `quota_total`, `quota_unit`
- `last_status_sync_at`, `status_source`
- `version`, 时间列

### `plan_seats`

`upstream_subscription_id`、`external_seat_ref`、`assigned_user_id nullable`、`display_name`、`seat_status`、额度和周期字段、`version`。

唯一 `(upstream_subscription_id, external_seat_ref)`，但无外部 ID 的 Seat 使用应用 UUID。

### `upstream_credentials`

逻辑凭证槽位：`subscription_id`、`seat_id nullable`、`credential_name`、`secret_fingerprint`、`status`、`active_version_id nullable`、验证时间/错误、`version`。

不保存明文或当前密文本体。

### `upstream_credential_versions`

不可变凭证版本：

- `credential_id`
- `encrypted_secret`, `nonce`, `encryption_key_version`
- `secret_fingerprint`
- `status`: PENDING_VALIDATION/ACTIVE/DRAINING/RETIRED/INVALID
- `valid_from`, `retired_at`, `created_at`

同一 credential 最多一个 ACTIVE，由事务和部分唯一索引保证。

## 5. 项目授权与 Virtual Key

### `project_provider_grants`

`project_id`、`provider_product_id`、`upstream_credential_id`、`status`、`version`、创建信息。

唯一 `(project_id, provider_product_id, upstream_credential_id)`。

### `project_provider_grant_models`

`grant_id`、`model_id`，主键 `(grant_id, model_id)`。精确、区分大小写。

### `virtual_keys`

关键列：

- `public_key_id varchar(64)` UNIQUE
- `secret_digest bytea`
- `display_prefix`, `last_four`
- `user_id`, `project_id`, `grant_id`, `upstream_credential_id`
- `purpose`, `name`, `status`
- `created_at`, `last_used_at`, `revoked_at`
- `replaced_by_key_id nullable`
- `version`

不包含明文 Key。`secret_digest` 不得进入审计 diff。

### `virtual_key_models`

Key 创建时的授权快照，主键 `(virtual_key_id, model_id)`。实际可用模型仍需与当前 Grant 求交集。

## 6. 请求与用量

### `request_usage_records`

按 `started_at` 月度 range partition。记录在开始时创建为 `IN_FLIGHT`，结束后只允许一次 finalize；finalized 后业务字段不可修改。

关键列：

- `id uuid`, `gateway_request_id`, `upstream_request_id`
- `tenant_id`, `user_id`, `team_id nullable`, `project_id`
- `virtual_key_id`, `subscription_id`, `credential_id`
- 相关名称/指纹快照
- `provider_id`, `provider_product_id`, `wire_protocol`, `model_id`
- `started_at`, `first_byte_at`, `completed_at`
- `duration_ms`, `time_to_first_byte_ms`
- `http_status`, `request_status`, `error_category`
- `streaming`, `client_cancelled`, `retry_count`, `partial_response`
- 六类 Token、每类 authority、provider total
- `provider_usage_json jsonb`（仅 usage 白名单）
- `price_catalog_version`, `price_snapshot_json`
- `official_estimated_cost`, `internal_estimated_cost`, `currency`
- `plan_window_ref`, `usage_integrity`
- `finalized_at`

主键包含分区键：`primary key (started_at, id)`；唯一 `(started_at, gateway_request_id)`。常用索引：

- `(tenant_id, started_at desc)`
- `(user_id, started_at desc)`
- `(project_id, started_at desc)`
- `(virtual_key_id, started_at desc)`
- `(credential_id, started_at desc)`
- `(provider_product_id, model_id, started_at desc)`
- 部分索引 `request_status <> 'SUCCEEDED'`

正文、完整 Header 和 Secret 不得存在。

### `quota_snapshots`

`subscription_id`、`seat_id nullable`、`credential_id nullable`、窗口类型/时间、总/已用/剩余、单位、来源、`provider_status_json` 脱敏、同步时间和错误。

### `cost_allocations`

按 Subscription 周期、项目/用户对象记录固定成本、权重 Token、分摊金额、currency、algorithm_version、生成时间。唯一 `(subscription_id, period_start, period_end, target_type, target_id, algorithm_version)`。

## 7. 告警、导出和审计

### `webhook_endpoints`

URL、加密签名 Secret、启停、超时、version。URL 必须通过 SSRF 校验。

### `alert_rules` / `alert_events` / `webhook_delivery_attempts`

规则保存 type、scope、threshold JSON Schema、去重窗口。事件保存实际值、对象、dedupe key 和状态；投递表保存 HTTP 状态、次数、下次重试和脱敏错误。

### `export_jobs`

过滤条件 JSON、格式、状态、文件路径、SHA-256、创建/完成/过期时间、创建管理员。路径是内部相对标识，不接受用户路径。

### `usage_deletion_jobs`

过滤条件、预估行数、确认 token 摘要、实际行数、状态和管理员。

### `admin_audit_events`

追加写入：actor、action、target type/id、change summary JSON、gateway/admin request ID、时间、前一事件 hash、当前 hash。禁止删除和外键 cascade。

## 8. 调度与配置

### `scheduled_task_locks`

可采用 ShedLock 表结构或等价 advisory lock 方案；只能有一个方案，Phase 0/1 记录实现决定。

### `application_settings`

只保存非敏感动态设置、schema version、updated_by。Secret 只能保存外部引用或加密密文专表。

## 9. 删除规则

- 用户/项目默认软禁用，不物理删除历史引用。
- Credential 可退休，版本不可立即物理删，直到安全保留期和备份策略满足。
- Usage 只能通过 `usage_deletion_jobs` 按明确范围删除。
- Audit 永久保存。
- Subscription/Seat/Grant 有用量引用时禁止物理删除。

## 10. Migration 测试

- 空库升级到最新。
- 从上一个发布版本升级。
- 所有 FK/CHECK/unique/partial index 验证。
- V1 schema 与本文核心表一致。
- Testcontainers 每次测试使用真实 PostgreSQL，不用 H2 模拟。

