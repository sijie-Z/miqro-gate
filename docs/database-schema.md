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

### `model_approval` (V4 + V22)

为 Key 追加模型的审批工作流（接线于模型申请审批 Goal；`reviewed_by IS NULL` = 白名单自动批准）：

- `virtual_key_id`、`model_id`、`requested_by`、`status`（`PENDING|APPROVED|REJECTED`）、`reviewed_by`、`reason varchar(500)`（V22 新增，申请理由）、`review_note varchar(500)`（审核意见）、`version`（乐观锁，PENDING → 终态唯一一次）
- 复合 FK 到 Key 和 `users(tenant_id, id)`；`virtual_key_id`、`status`、`tenant_id` 索引
- **无重复申请的数据库约束**：同 Key 同模型重复 PENDING 由服务层检查（`409 DUPLICATE_PENDING`）
- **审批生效**：`APPROVED` 行的 `model_id` 写入 `virtual_key_models`（申请 Key）+ `project_provider_grant_models`（如缺失）并触发路由快照即时刷新——两表分别对应网关放行的 Key 层与 Grant 层

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

### `webhook_endpoints` (V12，G4.5 实现)

URL（创建时经控制面 SSRF 门控：默认仅公网 https，`MIQROKEY_CONTROL_PROVIDER_CLIENT_ALLOWED_CIDRS` 可扩展）、HMAC 签名 Secret（AES-GCM 加密，AAD 绑定 tenant + endpoint）、启停、超时、version。Secret 明文永不返回。

### `alert_rules` / `alert_events` / `webhook_delivery_attempts` (V12/V15/V24，G4.5/G8.3/配额告警实现)

规则：`type`（`USAGE_MISSING_RATE|UPSTREAM_ERROR_RATE|BALANCE_UNAVAILABLE|USAGE_SURGE|BUDGET_THRESHOLD|QUOTA_THRESHOLD|MODEL_APPROVAL_SUBMITTED|MODEL_APPROVAL_APPROVED|MODEL_APPROVAL_REJECTED`，V15/V24/V27 扩展 CHECK 约束）、`threshold`、`dedupe_minutes`、`enabled`、可选 `webhook_endpoint_id`（null = 仅记录事件）、`scope_json jsonb`（`BUDGET_THRESHOLD` 必填：`{"projectId": "…"}`；`QUOTA_THRESHOLD` 必填：`{"quotaRuleId": "…"}`）。事件：`dedupe_key`（type + 小时桶；`BUDGET_THRESHOLD` 为 type + 月份；`QUOTA_THRESHOLD` 为 type + 配额重置窗口起点 epoch；审批通知型为 type + approvalId）唯一约束 `(tenant_id, rule_id, dedupe_key)` 实现去重；`value` 为指标实际值（审批通知型恒为 1 = 一次发生）；`payload_json` 存事件明细（审批通知型 = 通知字段原样，重试投递时随信封带出），不含正文/密钥。投递表：事件 × 端点 × 尝试次数唯一；`next_retry_at` 指数退避（2^attempt × 1min，最多 3 次）、`http_status`、脱敏错误。评估调度：`@Scheduled` 固定延迟（`miqrokey.alerts.evaluation-interval-ms` 默认 5min）；指标基于滚动 1 小时、租户级（单租户部署语义）；`BUDGET_THRESHOLD` 由 `AlertEvaluator` 复用 `AdminBudgetService` 水位（当月分摊成本/预算 × 100），`QUOTA_THRESHOLD` 复用 `AdminQuotaRuleService` 水位（当前窗口用量/限额 × 100；规则 DISABLED 不评估）。**投递/重试/退避原语抽取为 `AlertEventDispatcher`**（G4.5 机制），周期型由 `AlertEvaluator` 经它投递；`MODEL_APPROVAL_*` 事件型不评估、由审批工作流（`ModelApprovalService` 迁移瞬间）直接触发。

### `export_tasks` (V11，G4.4 实现)

异步导出任务：`format`（`CSV|JSONL`）、窗口、`status`（`PENDING|RUNNING|SUCCEEDED|FAILED|EXPIRED`）、`sha256`（gzip 产物哈希）、`row_count`/`byte_count`、`file_bytes`（gzip 产物本体，24h 过期）、`error_message`（脱敏）。产物只含计数与元数据列（时间/模型/缓存层级/token/延迟/状态码/request ID/Key/项目/产品/凭证 ID），绝不包含 prompt、代码、Secret 或 Virtual Key 明文。

### `usage_deletions` (V11，G4.4 实现)

双确认删除请求：窗口、`preview_count`、`confirm_token_hash`（一次性确认 token 的 SHA-256，明文只在创建响应出现一次）、`status`（`PENDING_CONFIRMATION|CONFIRMED|EXECUTED|EXPIRED`）、`deleted_count`、`expires_at`（确认窗口 1h）。执行时物理删除 `usage_event` 窗口行并写 `USAGE_DELETE` 审计事件（审计链本身永不删除）。

### `cost_allocations` (V10，G4.3 实现)

按 Subscription 周期、项目对象记录：`fixed_cost`（Plan 订阅价按窗口/订阅周期天数比例折算）、`usage_cost`（本地 usage × 最新价格快照，每百万 token 单价）、`weight_tokens`（权重 Token = 输入+输出）、`allocated_amount`（= usage + fixed 份额）、`currency`、`algorithm_version`（当前 `1`；唯一键含版本，重跑同版本幂等覆盖、新算法另起历史行）、`generated_at`。唯一 `(subscription_id, period_start, period_end, target_type, target_id, algorithm_version)`。写入路径：`CostAllocationService.allocate`（管理端触发）——固定成本按 Token 权重在项目间分摊（无用量不产出行）；PAYG 订阅无固定成本。价格取分配时刻最新快照（usage 行上的逐事件价格快照为延后列，见 §6 延后列清单）。

### `cache_entry` (V5，当前实现)

PostgreSQL 响应缓存（L2），默认关闭（缓存子系统显式启用且 Key `cache_policy=ENABLED` 才参与）：

- `cache_key bytea`（归一化请求的 SHA-256）、`virtual_key_id`、`project_id`、`provider_product_id`、`model_id`
- `provider_request_id`、`status_code`、`content_type`、`response_headers jsonb`、`body bytea`（原始字节，SSE 按字节重放）
- `meta_json jsonb`、`hit_count_l1`、`hit_count_l2`、`expires_at`、时间戳
- 唯一 `(tenant_id, cache_key)`；`project_id`、`expires_at`、`tenant_id` 索引

### `api_consumers` (V13/V14，ADR-0010/0011)

外部系统 API 消费者：`name`、`key_digest bytea`（SHA-256，仅哈希）、`key_prefix varchar(8)`、`status`（`ACTIVE|DISABLED`）。唯一 `(tenant_id, name)`；`key_digest` 索引用于认证查找。

V14（ADR-0011）新增 JWT 验签公钥：`jwt_public_key_pem text`（RSA SubjectPublicKeyInfo，公钥非机密）、`jwt_key_fingerprint varchar(16)`（SHA-256 前 8 字节 hex，展示）、`jwt_key_set_at timestamptz`。三列同设同清（`ApiConsumer` 构造校验同 null）。

### `price_snapshot` (V5，当前实现)

每百万 token 单价快照，**不租户隔离**（价格属于全局产品目录）：`provider_product_id`、`model_id`、`token_type`（`INPUT|OUTPUT|CACHE_READ|CACHE_CREATION`）、`currency`（默认 CNY）、`unit_price numeric(24,10)`、`effective_from`、`source`（`MANUAL|OFFICIAL|ESTIMATED`）、`created_by`。查询索引 `(provider_product_id, model_id, token_type, effective_from DESC)`。控制面用量汇总按此计算成本；无快照的模型成本记 0。

### `budget` / `model_budget` (V7，当前实现)

月度预算（仅告警，永不阻断）：`project_id`、`period_month`（`YYYY-MM`）、`amount numeric(24,10)`、`currency`、`alert_threshold_pct`、`status`（`ACTIVE|PAUSED`）、`version`。`budget` 唯一 `(tenant_id, project_id, period_month)`；`model_budget` 额外含 `model_id`，唯一 `(tenant_id, project_id, model_id, period_month)`。V7 已建表，告警消费为后续 Goal。

### `quota_rules` (V23，用量配额)

用量配额计划（仅预警永不阻断，roadmap「配额管理」）：`scope_type`（`USER|PROJECT`）、`scope_id`、`metric`（`TOKENS|REQUESTS`）、`period`（`DAILY|WEEKLY|MONTHLY`）、`limit_value bigint`（>0）、`warn_percent`（1–99，默认 80）、`status`（`ACTIVE|DISABLED`）、`created_by`、`version`。唯一 `(tenant_id, scope_type, scope_id, metric, period)`（同 scope 同维同周期仅一条，重复 PUT 原地编辑）。表只存计划；**当前窗口水位在读取时由 usage 事件计算**（UTC 窗口；TOKENS=全部 token 口径，REQUESTS=上游请求数），`(tenant_id, scope_type, scope_id, status)` 索引。规则永不阻断流量——硬阻断需 ADR。

### `quota_default_template` (V26，默认配额模板)

全局默认配额策略（腾讯 doc 135489，`AdminQuotaDefaultTemplateService`）：**每租户一行**（`tenant_id` PK）——`enabled`、`metric`（`TOKENS|REQUESTS`）、`period`（`DAILY|WEEKLY|MONTHLY`）、`limit_value bigint`（>0）、`updated_by`（`(tenant_id, updated_by)` 引用 users）、`version`。行仅在管理员首次配置定义后存在（GET 未配置 = 空态视图）。**创建时快照复制**：启用状态下 `AdminOrgService.createUser` 同事务内按模板复制一条 `quota_rules` 行（USER 作用域、warn 80、ACTIVE、insert-if-absent）——改模板不惊动存量、停用不删已分配、手动规则优先。启用开关独立于定义（enable/disable 端点只翻 `enabled`）。

### `skills` / `skill_access` (V16，P2.2 SkillHub)

`skills`：技能目录条目——`name varchar(64)`（kebab-case，= SKILL.md frontmatter name）、`description`、`version`（语义化）、`author`、`license`、`tags text[]`、`content_zip bytea`（校验后的技能包）、`content_sha256`、`content_bytes`、`status`（`ACTIVE|ARCHIVED`）、`created_by`。唯一 `(tenant_id, name)`。

`skill_access`：下载授权——`skill_id`（ON DELETE CASCADE）、`scope_type`（`TEAM|PROJECT`）、`scope_id`；唯一 `(skill_id, scope_type, scope_id)`。无行 = 公开技能。

### `agents` (V17，P3.1)

管理智能体：`name`、`description`、`upstream_credential_id`（出口绑定凭证，产品由凭证派生）、`status`（`ACTIVE|DISABLED`）、`created_by`。唯一 `(tenant_id, name)`；`(tenant_id, upstream_credential_id)` 索引（用量聚合路径）。

### `services` (V18，P3.2)

内部服务注册表：`name`、`kind`（`HTTP|MCP|OTHER`）、`description`、`base_url`（https、无 userinfo/query/fragment）、`status`（`ACTIVE|DISABLED`）、`created_by`。唯一 `(tenant_id, name)`。

### `config_entries` (V19，P3.3)

全局配置中心：`group_name`、`key`、`value text`、`description`、`updated_by`（乐观 `version`）。唯一 `(tenant_id, group_name, key)`。仅非机密配置（机密走 env/加密凭证）。

### `mcp_services` (V20，P3.4)

MCP Server 管理：`name`、`description`、`endpoint`（https）、`transport`（`STREAMABLE_HTTP|SSE`）、`status`（`ONLINE|OFFLINE`，手动切换，健康检查不覆盖）、`health_status`（`UNKNOWN|HEALTHY|UNHEALTHY`）、`health_checked_at`、`consecutive_failures/successes`、检查配置（`check_interval_seconds`/`check_timeout_seconds`/`fail_threshold`/`recover_threshold`/`check_path`）。唯一 `(tenant_id, name)`；`(tenant_id, health_status)` 索引（探活列表）。

### `mcp_tools` (V21，P3.5)

MCP Tools 管理：`tool_name`（AI Agent 调用唯一标识，snake_case）、`description`、`method`（`GET|POST|PUT|DELETE|PATCH`）、`path`（以 `/` 开头）、`status`（`ENABLED|DISABLED`）、绑定 `mcp_service_id`（ON DELETE CASCADE）。唯一 `(tenant_id, mcp_service_id, tool_name)`；`mcp_service_id` 索引。

### `mcp_service_access` / `mcp_access_grants` (V25，MCP 两级访问控制)

腾讯 doc 134890 语义：`mcp_service_access` 每服务一行——`mode`（`NONE|ALLOW|DENY`，缺行 = NONE）、唯一 `mcp_service_id`（ON DELETE CASCADE）；`mcp_access_grants` 名单行——`service_access_id`（CASCADE）、`tool_id`（可空：NULL=服务级名单，非 NULL=该工具覆盖）、`consumer_id`（引用 `api_consumers`，CASCADE）、`mode`（`ALLOW|DENY`）。唯一 `(service_access_id, tool_id, consumer_id)`。模式约束由 API 层保证：服务名单仅 ALLOW/DENY 模式存在（NONE 时清空）；工具覆盖仅服务 NONE 时可配置；服务模式切 NONE 自动清服务名单。判定在调用侧用 `McpAccessPolicy`（domain 纯函数：服务层判定 + 工具层收窄，工具只能进一步限制）。

### `mcp_route_rule` (V28，F11 MCP 路由规则)

腾讯 doc 135482 语义：每 MCP 服务一组路由规则（`mcp_service_id` ON DELETE CASCADE），决定哪些入站请求可达服务（上游恒为服务本身，配置面先行）。`name`（≤64）服务内唯一 `(tenant_id, mcp_service_id, name)`；`priority`（0–65535，默认 1000）大者优先，0 为系统 default 保留；匹配条件列：`path_mode/path_value`（EXACT|PREFIX|REGEX + 值，RE2 全匹配，REGEX 豁免 `/` 前缀，DB CHECK 同步）、`host_mode/host_value`（同上，域名大小写不敏感）、`methods`（逗号 CSV 白名单，NULL=不限）、`header_conditions jsonb`（`[{name, mode, value}]` AND 语义，≤8，default `[]`）；`status`（ENABLED|DISABLED，仅启用参与匹配）；`version` 乐观锁。**default 行**（name=default/priority=0/无条件/ENABLED/created_by NULL）随服务创建生成、V28 对存量服务以确定性 md5 uuid 回填，API 层禁改禁删禁停；自定义行 `created_by` 必填。匹配/冲突/正则校验逻辑集中在 domain 纯函数 `McpRouteRules`（可被后续数据面复用，不读正文）。



### `mcp_access_log` (V29，F15 MCP 元数据访问日志)

网关数据面 F01 代理的审计行（写方=网关异步批量 writer，读方=管理 API §5.24）。列：`id uuid`（网关侧生成）、`tenant_id`（FK tenants ON DELETE RESTRICT）、`service_id/service_name`、`consumer_id/consumer_name`（快照身份快照值，非 FK——服务/消费者删除不毁审计）、`rpc_method`（信封 method，可空=不可解析）、`tool_name`（tools/call 的 `params.name`，其余方法空）、`status`（CHECK：`FORWARDED|SERVICE_DENIED|TOOL_DENIED|TOOL_UNAVAILABLE|INVALID_ENVELOPE|UPSTREAM_FAILURE`）、`http_status`（FORWARDED=上游状态；拒绝类=客户端可见 403/400；UPSTREAM_FAILURE=空）、`gateway_request_id`、`occurred_at timestamptz`。**幂等**：唯一索引 `(tenant_id, gateway_request_id)`（重试 flush `ON CONFLICT DO NOTHING`）。查询索引：`(tenant_id, occurred_at DESC)`、`(tenant_id, service_name, occurred_at DESC)`、`(tenant_id, consumer_name, occurred_at DESC)`。正文永不入表。

### `mcp_resilience_policy` (V30，F12/F13 韧性配置)

每 MCP 服务一行（`mcp_service_id` PK，FK mcp_services ON DELETE CASCADE）：重试门禁与熔断配置，**默认全关**（无行=行为不变）。F12 列：`retry_enabled`、`retry_max`（1–5）、`retry_conditions`（CSV `SERVER_5XX|CONNECTION_FAILURE|TIMEOUT`）、`retry_idempotency_confirmed`（POST/PUT/PATCH 工具调用重试需显式确认）。F13 列：`breaker_enabled`、`breaker_window_seconds`（1–60）、`breaker_min_requests`（1–100）、`breaker_error_enabled`/`breaker_error_ratio`（1–100）/`breaker_error_status_codes`（CSV 400–599，≤32，默认 500,502,503,504）、`breaker_slow_enabled`/`breaker_slow_call_ms`（100–60000）/`breaker_slow_ratio`、`breaker_open_seconds`（5–600）、`breaker_probe_count`（1–10）、`breaker_probe_success`（1–10 且 ≤probe_count）、`breaker_skip_retry`（默认 true）。`version`（每次 upsert +1）、`created_by`/`updated_by`、时间戳。慢阈值与服务 `check_timeout_seconds` 的跨字段校验在管理 API 层（`RESILIENCE_SLOW_EXCEEDS_TIMEOUT`）。数据面经路由快照读取本表（loader LEFT JOIN，无行→null=全关）。

### `retention_config` / `user_identity_link` (V31，ADR-0014 v3 Accepted)

- `retention_config`：每租户一行（PK tenant_id FK tenants），`enabled`（默认 false）、`content_scope`（CHECK `USER_TEXT_ONLY`）、`key_version`（默认 'v1'，P5 信封密钥版本）、`version`（每次 upsert+1）、`updated_by`、`updated_at`。网关经路由快照读取（loader 全表入 `retentionByTenant`），控制面 PUT 后即时生效。
- `user_identity_link`：OAuth 平台映射骨架（R4/P7，等平台 claims 后接线）：`internal_user_id`（FK users）↔ `platform_user_id` + `idp`，唯一 `(tenant_id, idp, platform_user_id)`，索引按 internal_user_id。数据面暂不读取。
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

