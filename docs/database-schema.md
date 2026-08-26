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

### `user_sessions` (V2)

| 列 | 类型 | 约束 |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL FK→tenants(id) |
| user_id | uuid | NOT NULL FK→users(tenant_id, id) |
| token_digest | bytea | NOT NULL UNIQUE (SHA-256 of session token) |
| csrf_digest | bytea | NOT NULL (SHA-256 of CSRF secret) |
| created_at | timestamptz | NOT NULL |
| last_seen_at | timestamptz | NOT NULL |
| expires_at | timestamptz | NOT NULL |
| revoked_at | timestamptz | NULLABLE |

保存随机 session token 的 SHA-256 摘要和 CSRF secret 的 SHA-256 摘要，不保存明文 token。索引：`token_digest` (UNIQUE, 热路径查询)、`user_id`、`expires_at`（partial, 清理过期会话）、`(user_id, id)`（partial, 批量撤销）。

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

### `model_catalog` (V7，当前实现)

目录级模型注册表：`provider_product_id`、`model_id`、`display_name`、`context_window`、`max_output_tokens`、`status`（`ACTIVE|DISABLED|DEPRECATED`）、`version`。唯一 `(provider_product_id, model_id)`。V7 已建表，供未来门户目录浏览使用；当前无应用代码消费。

### `model_access` (V7，当前实现)

租户/项目级模型放行规则：`project_id`、`model_id`、`status`（`ACTIVE|DISABLED`）、`created_by`、`version`。唯一 `(tenant_id, project_id, model_id)`。V7 已建表，当前无应用代码消费；Virtual Key 的实际模型权限由 Grant 模型与 Key 快照求交集决定。

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

### `projects.project_tag` / `virtual_keys.cache_policy` (V4)

- `projects.project_tag varchar(64) nullable`：路由标签，唯一 `(tenant_id, project_tag)`（部分索引，非 NULL 才唯一）。格式 `^[A-Za-z0-9_-]{1,64}$`。标签明文嵌入 Key 后缀（`mqk_live_<id>_<secret>.<projectTag>`）用于路由；鉴权权威是 `key_project_binding`。
- `virtual_keys.cache_policy varchar(32) NOT NULL DEFAULT 'DISABLED'`，取值 `DISABLED|ENABLED`：显式开启才可能参与响应缓存（缓存子系统默认关闭，ADR-0008）。

### `key_project_binding` (V4)

Key → 项目绑定（标签路由的鉴权权威），与 `virtual_keys.project_id` 分离，便于绑定状态演化而不重写 Key 行：

- `virtual_key_id`、`project_id`、`status`（`ACTIVE|DISABLED`）、`version`、时间戳
- 复合 FK 到 `virtual_keys(tenant_id, id)` 和 `projects(tenant_id, id)`（防跨租户）
- 唯一 `(virtual_key_id, project_id)`；`project_id`、`tenant_id` 索引

### `model_approval` (V4)

为 Key 追加模型的审批工作流：

- `virtual_key_id`、`model_id`、`requested_by`、`status`（`PENDING|APPROVED|REJECTED`）、`reviewed_by`、`review_note varchar(500)`、`version`
- 复合 FK 到 Key 和 `users(tenant_id, id)`；`virtual_key_id`、`status`、`tenant_id` 索引

## 6. 请求与用量

### `usage_event` (V6，当前实现)

当前分级的追加型用量事实表（`request_usage_records` 的完整分区表为后续增量，见下）。Gateway 批量写（有界队列，默认容量 10000 / 每 5s 或 100 条 flush），`provider_request_id` 在 tenant 内唯一 → `INSERT ... ON CONFLICT DO NOTHING` 幂等，重试 flush 不双计。

关键列：

- `tenant_id`、`virtual_key_id`、`project_id`、`provider_product_id`、`credential_id`、`model_id`
- `provider_request_id`、`gateway_request_id`（必填）
- `cache_level`（`UPSTREAM|COALESCED|L1_HIT|L2_HIT`，默认 `UPSTREAM`）
- 六类 Token 列（`input/output/cache_creation_input/cache_read/prompt/completion/total/reasoning`），**可为 NULL**：缓存命中无 usage
- `latency_ms`、`upstream_status_code`、`cache_key bytea`
- `is_complete boolean`、`usage_missing boolean`（上游未返回 usage 时标记，用量记 0）
- `occurred_at`、`created_at`

部分唯一索引 `(tenant_id, provider_request_id) WHERE provider_request_id IS NOT NULL`；`virtual_key_id`、`project_id`、`cache_level`、`occurred_at` 索引。正文（prompt、代码、工具、回答）永不写入。

### `cache_hit_event` (V6)

缓存命中计数（L1/L2 命中不写 `usage_event`，在此去重计数）：`cache_key`、`virtual_key_id`、`project_id`、`provider_product_id`、`level`（`L1_HIT|L2_HIT`）、`occurred_at`、`gateway_request_id`。唯一 `(tenant_id, cache_key, level, occurred_at)`——同一秒内同一 cache_key 只记一次。

### `request_usage_records` (V8，当前实现子集)

按 `started_at` 月度 range partition（V8 建 DEFAULT partition）。生命周期记录：到达上游的请求在开始时插入为 `IN_FLIGHT`，结束（含客户端取消、上游故障、超时）时**只允许 finalize 一次**——Guard 语义为 `ON CONFLICT (started_at, gateway_request_id) DO UPDATE ... WHERE request_status = 'IN_FLIGHT'`，retried flush 绝不重写已 finalized 记录、绝不双计；start 行丢失时 completion 事件自带完整 start 快照，独立插入终态行。

**G2.4 已实现列**：

- `id uuid`、`gateway_request_id`、`upstream_request_id`
- `tenant_id`（唯一 FK → `tenants`，`ON DELETE RESTRICT`）、`user_id`、`project_id`
- `virtual_key_id`、`provider_id`、`provider_product_id`、`credential_id`
- `model_id`、`wire_protocol`（`ANTHROPIC_MESSAGES|OPENAI_RESPONSES|OPENAI_CHAT_COMPLETIONS|OPENAI_COMPATIBLE`）
- `started_at`、`first_byte_at`、`completed_at`、`duration_ms`、`time_to_first_byte_ms`
- `http_status`、`request_status`（`IN_FLIGHT|SUCCEEDED|UPSTREAM_REJECTED|CLIENT_CANCELLED|UPSTREAM_UNAVAILABLE|TIMEOUT_BEFORE_FIRST_BYTE|STREAM_INTERRUPTED`）
- `streaming`、`client_cancelled`、`partial_response`、`retry_count`
- 六类 Token（`input/output/cache_creation_input/cache_read/prompt/completion/total/reasoning`，可为 NULL）
- `usage_missing`（SUCCEEDED 但上游未返回 usage 时显式标记）
- `finalized_at`

主键含分区键：`primary key (started_at, id)`；幂等键唯一 `(started_at, gateway_request_id)`。常用索引：`(tenant_id, started_at desc)`、`(virtual_key_id, started_at desc)` 等。

**延后列（后续 Goal）**：`team_id`、`subscription_id`、相关名称/指纹快照、`error_category`、每类 token authority、`provider_usage_json jsonb`、`price_catalog_version`、`price_snapshot_json`、成本列、`plan_window_ref`、`usage_integrity`。

正文、完整 Header 和 Secret 不得存在（G2.4 起写入路径不含任何正文内容）。

### `quota_snapshots` (V9，G4.2 实现)

追加式历史表：`subscription_id`、`seat_id nullable`、`credential_id nullable`、`window_type`（`PERIOD|ROLLING_5H|WEEKLY|MONTHLY|UNKNOWN`）、总/已用/剩余（`numeric(24,10)`）、`unit`（`POINTS|TOKENS|REQUESTS|CURRENCY|UNKNOWN`）、`shared_pool`、`source`（`OFFICIAL_API|LOCAL_ESTIMATE|UNAVAILABLE`，对应 provider-adapter-contract §6 权威级别）、`provider_status_json`（脱敏预留，绝不存 Secret）、`synced_at`、`error_message`。读取按 `(tenant_id, subscription_id, synced_at DESC)` 与 `(tenant_id, credential_id, synced_at DESC)` 索引；最新视图用 `DISTINCT ON (seat_id, credential_id)` 每作用域取最新一行。写入路径：`QuotaSnapshotService.refresh`（管理端触发）——按 ACTIVE 凭证逐个经适配器 `fetchPlanStatus`（解密 → 凭证作用域 `ProviderClient` → OFFICIAL_API/UNAVAILABLE 行），订阅带 `quota_total` + `period_start` 时另写 LOCAL_ESTIMATE 行（本地 usage 输入+输出 token 相对周期起点估算）。

### `cost_allocations`

按 Subscription 周期、项目/用户对象记录固定成本、权重 Token、分摊金额、currency、algorithm_version、生成时间。唯一 `(subscription_id, period_start, period_end, target_type, target_id, algorithm_version)`。

### `cache_entry` (V5，当前实现)

PostgreSQL 响应缓存（L2），默认关闭（缓存子系统显式启用且 Key `cache_policy=ENABLED` 才参与）：

- `cache_key bytea`（归一化请求的 SHA-256）、`virtual_key_id`、`project_id`、`provider_product_id`、`model_id`
- `provider_request_id`、`status_code`、`content_type`、`response_headers jsonb`、`body bytea`（原始字节，SSE 按字节重放）
- `meta_json jsonb`、`hit_count_l1`、`hit_count_l2`、`expires_at`、时间戳
- 唯一 `(tenant_id, cache_key)`；`project_id`、`expires_at`、`tenant_id` 索引

### `price_snapshot` (V5，当前实现)

每百万 token 单价快照，**不租户隔离**（价格属于全局产品目录）：`provider_product_id`、`model_id`、`token_type`（`INPUT|OUTPUT|CACHE_READ|CACHE_CREATION`）、`currency`（默认 CNY）、`unit_price numeric(24,10)`、`effective_from`、`source`（`MANUAL|OFFICIAL|ESTIMATED`）、`created_by`。查询索引 `(provider_product_id, model_id, token_type, effective_from DESC)`。控制面用量汇总按此计算成本；无快照的模型成本记 0。

### `budget` / `model_budget` (V7，当前实现)

月度预算（仅告警，永不阻断）：`project_id`、`period_month`（`YYYY-MM`）、`amount numeric(24,10)`、`currency`、`alert_threshold_pct`、`status`（`ACTIVE|PAUSED`）、`version`。`budget` 唯一 `(tenant_id, project_id, period_month)`；`model_budget` 额外含 `model_id`，唯一 `(tenant_id, project_id, model_id, period_month)`。V7 已建表，告警消费为后续 Goal。

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

追加写入：actor、action、target type/id、change summary JSON、gateway/admin request ID、时间、前一事件 hash、当前 hash、chain_position (数据库单调序列)。禁止删除和外键 cascade。

Head selection 使用 `ORDER BY chain_position DESC` —— 数据库单调 identity/sequence 在 INSERT 时分配，反映真实因果提交顺序。JVM 时钟和随机 UUID 不用于 head 排序。

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

