# ADR-0016：管理开放 API 写面 —— 机器执行者（machine executor）语义（草案）

- 日期：2026-09-07
- 状态：**Proposed**（等 owner/leader 拍板；拍板前不实现任何写面端点）
- 关联：ADR-0015（机器凭据，批 1/1b 读面已 Accepted）、F59（feature-backlog）、
  docs/open-admin-api-plan.md（批 2 拆解）。先例：ADR-0010/0011（api_consumers 机器通道
  面向"内容数据面"，与管理面语义分离）。

## 背景

ADR-0015 批 1/1b 只开放了只读子集；写面（建 Virtual Key、配额/告警变更、Webhook、审批、
导出任务创建等）尚未开放，因为**所有写操作必须先解决"机器执行者"在业务表
`created_by`/审计链里的表示**：现有 schema 中"执行者"列（如 `export_tasks.created_by`
NOT NULL REFERENCES users、`quota_rules.created_by` 复合 FK、`admin_api_keys.created_by`
REFERENCES users）都指向 users 行，而机器身份 = 租户级系统主体，**没有 userId**。

## 决策选项（等拍板，未选定）

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
