# ADR-0016：管理开放 API 写面 —— 机器执行者（machine executor）语义（草案）

- 日期：2026-09-07
- 状态：**Accepted（2026-09-08 owner 拍板：A+C）**——批 2 v1 = 无执行者列配置写直接开（C：告警规则、Webhook 端点全生命周期）+ 需执行者列操作用委托（A：导出创建 created_by=发行管理员、审计沿机器密钥）；Virtual Key 创建因需目标用户委托语义另行评估，不进批 2 v1。
- 关联：ADR-0015（机器凭据，批 1/1b 读面已 Accepted）、F59（feature-backlog）、
  docs/open-admin-api-plan.md（批 2 拆解）。先例：ADR-0010/0011（api_consumers 机器通道
  面向"内容数据面"，与管理面语义分离）。

## 背景

ADR-0015 批 1/1b 只开放了只读子集；写面（建 Virtual Key、配额/告警变更、Webhook、审批、
导出任务创建等）尚未开放，因为**所有写操作必须先解决"机器执行者"在业务表
`created_by`/审计链里的表示**：现有 schema 中"执行者"列（如 `export_tasks.created_by`
NOT NULL REFERENCES users、`quota_rules.created_by` 复合 FK、`admin_api_keys.created_by`
REFERENCES users）都指向 users 行，而机器身份 = 租户级系统主体，**没有 userId**。

## 决策（2026-09-08 Accepted：A+C 组合，见下文 v1 范围）

> 选型过程记录保留（原三案）。v1 范围：
> - C 直接开：`/api/v1/admin-api/alert-rules` 与 `/api/v1/admin-api/webhooks` 全生命周期
>   （表无执行者列，tenant 参数即隔离边界）
> - A 委托开：`POST /api/v1/admin-api/export-tasks`——created_by = 密钥发行管理员
>   （过滤器新增 ISSUER_ATTR；导出下载/状态仍走元数据面）
> - 后续待评估：Virtual Key 创建（目标用户委托语义）、配额/模板写、批 3 治理。

## 增补：批 2 v2 Virtual Key 委托创建（2026-09-09 Accepted：案 1，issue #263）

机器密钥开放「代指定用户建 Virtual Key」，但**边界语义与自助建钥完全一致**
（1:1 固定绑定不变：钥归属目标用户，成员/授权校验按目标执行，密钥只存摘要、
secret 一次性）。设计稿与案型记录：docs/f60-v2-virtual-key-delegation-design.md。

- 端点：`POST /api/v1/admin-api/virtual-keys`（body = CreateVirtualKeyRequest 字段 +
  `userId`）→ 201 `{key, secret, shownOnce:true}`；只读配套
  `GET /api/v1/admin-api/virtual-keys?userId=`（视图列表，便于开通流程查询）。
- 委托语义：operator = 密钥的发行管理员（ISSUER_ATTR，同导出委托 A），且**现行角色
  必须仍为 SYSTEM_ADMIN**（`DELEGATION_FORBIDDEN` 403）——代他人发钥是系统管理员权限，
  防止被降权的历史委托人继续借钥扩散凭据。密钥缺发行管理员 → 既有 `EXECUTOR_UNKNOWN`。
- 成员边界（案 1 关键）：项目/授权/模型/cachePolicy 校验与自助一致；成员校验按
  **目标用户**执行，目标为 SYSTEM_ADMIN 时豁免（与管理员自助建钥一致）；
  `key.userId = 目标用户`。目标用户须租户内存在（404 `TARGET_USER_NOT_FOUND`）且
  ACTIVE（409 `TARGET_USER_INACTIVE`）；非成员目标 → 403 `PROJECT_MEMBERSHIP_REQUIRED`。
- 审计双元：action `VIRTUAL_KEY_CREATE`，actor = 委托人（机器永远不冒充用户），
  change_summary 增加 `targetUserId`——沿审计链可同时回到「谁执行」与「钥归谁」。
- 不分用途白名单（默认不限，与管理员自建一致）；不设目标用户白名单。
- 验收（2026-09-09 集成测试 6/6 + 自服务回归 8/8）：目标成员 201 且钥归属目标、
  secret 一次性；非成员 403；SYSTEM_ADMIN 目标豁免成员；停用/跨租户/不存在 404/409；
  创建链不变量（routing tag 缺失 409）仍生效；无凭据 401；审计 actor+targetUserId。

## 决策选项（历史记录，已裁决）

### 候选 A：委托执行（recommended for v1）
机器密钥的"业务执行者" = **发行该密钥的管理员**。写面端点照常写
`created_by = 密钥.created_by`；审计事件在既有 `change_summary`/`admin_request_id` 之外
把机器身份以**结构化目标**记下（扩展审计动作命名空间 `ADMIN_API_*`，target_id = 密钥 id，
target_type = `ADMIN_API_KEY`，并在 admin_request_id 携带调用方 requestId）。
- 优点：零 schema 迁移；可追溯性不降级（任何机器写操作都能沿审计链回到"哪把密钥"→"谁发行"）；
  读面与安全红线（摘要/吊销即时/正文不落库）全部原样复用。
- 代价：密钥转手/共用场景语义是"以管理员名义执行"（与 Web 会话操作一致的口径）；
  发行管理员被吊销/删除时其密钥应一并吊销（运维规则，非 schema 强制）。

### 候选 B：系统主体 + schema 扩展
引入 `principal` 表示：新增 `principal_type (USER | ADMIN_API_KEY)` + 复用 uuid 列，
V33 起逐表放宽/新增"执行者"列，审计链 actor 语义改为 (type,id)。
- 优点：语义最正，符合 ADR-0015「机器身份 = 租户级系统主体」建模。
- 代价：波及 users FK 约束（export_tasks、quota_rules、admin_api_keys、后续写面表）、
  审计表语义变更、现有 SQL/工具链调整；单租户首版收益低。

### 候选 C：写面目录裁剪
批 2 v1 只开放**不存在 users-FK 执行者列**的写操作（纯配置/无 created_by 的表），其余写操作
留到候选 A/B 拍板后。
- 优点：零 schema 变化即可交付一部分写面。
- 代价：开放目录形状由 schema 巧合决定而非产品语义，割裂。

## 范围边界（本 ADR 不覆盖，保持 ADR-0015 原文）

- 批 3 治理（密钥作用域只读/写、过期提醒、频控）仍可选，不阻塞写面。
- api_consumers/JWT 通道与 admin 密钥语义继续分离，不混用。
- 写面端点逐个评估：凡端点语义依赖"本人"（如审批流中的当前用户），机器执行者的
  delegate/委托语义需该端点单独说明。

## 验收（拍板后）

- 选定选项落 ADR；批 2 首批写端点 + 测试矩阵：created_by 落库值、审计两事件
  （`ADMIN_API_*` + 操作审计）、吊销后 401 即时性、跨租户不可见、OpenAPI 快照更新、
  CI 全绿。

## 未决

- 候选 A/B/C 选哪个；密钥是否支持"更换委托人"；写面首批端点清单（建议：Virtual Key 管理
  读-写子集 + Webhook/告警规则）待 leader 圈定。
