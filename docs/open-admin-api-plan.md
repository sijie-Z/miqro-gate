# 管理开放 API 专项设计稿（Open Admin API）

> 状态：PLANNED（2026-09-07 立项，owner/leader 方向确认：像腾讯 AI 网关那样
> “既有 Web 也提供 API 操作”）。本文档做设计骨架；实施时每批独立验收。
> 相关既有资产：`/api/v1/**` 全量 REST + OpenAPI 3.1（docs/openapi/ + 运行时
> /v3/api-docs，CI breaking-check）；机器通道先例 = api_consumers + JWT
> （ADR-0010/0011，外部平台记账/查询）。

## 目标与边界
- 目标：让**程序**（脚本/平台/别的系统）不登录网页，也能用安全凭据调用管理类
  操作（建 Virtual Key、查用量、配额/告警、Webhook、审计等），与网页操作一样
  进审计，且互不混淆。
- 非目标：不做全量资源镜像/不做 SDK 发行/不做云市场；第三方“内容数据面调用”
  走既有 Virtual Key（消费者）体系，本专项只管**管理面开放**。

## 现状差距（一句话）
管理 API 面向“人”（会话 Cookie + CSRF + 管理门户白名单 F05）；缺**机器对机器
的管理凭据 + 对外文档/示例 + 第三方治理**。

## 批次拆解（2-4 批）
### 批 1 — 管理机器凭据（核心，后端）
- V32 `admin_api_key`：tenant_id、name、digest（SHA-256 摘要,与 vkey 同族）、
  prefix（展示用）、created_by、expires_at（可选）、revoked_at、created_at；
  每租户多把，吊销/重建。
- 发行/吊销/列表管理端点（SYSTEM_ADMIN-only）+ 审计事件
  `ADMIN_API_KEY_ISSUE/REVOKE`（沿用审计链）。
- 鉴权：新 `AdminApiKeyFilter`（order 先于 SessionFilter？与 F05 白名单并存：
  机器通道豁免 CSRF；**Principal 建模是关键决策点**——候选 A：机器身份=租户级
  系统主体，userContext 里 userId=null 需全控制器兼容审查；候选 B：仅开放
  **只读+指定操作子集**（用量/审计/Key 列表/导出任务查询）先行，写操作后续批次
  扩——建议先 B 后扩，风险最小。
- 触发方式：`Authorization: Bearer mqk_…` 或 `X-API-Key`（择一，建议 Bearer）。
### 批 2 — 对外契约与示例
- OpenAPI 对外发布说明（版本化快照 + runbook 放公网反代路径说明）；
- `scripts/open-api-examples/`：curl + Python 示例集（鉴权→列 Key→查用量→
  建告警→吊销）,README 含最小权限建议。
### 批 3 — 第三方治理（可选）
- 管理 Key 作用域（只读/写）、过期提醒、频控（复用 F05 思路,默认不限制）。

## 安全/审计红线（不可破）
- 密钥只存摘要；展示仅 prefix；吊销即时生效（读库或快照——管理面直读库即可，
  无需进网关热路径）。
- 管理 Key 操作全部进审计；正文依旧不落库。
- 机器凭据与 api_consumers（外部平台账单通道）语义分开登记，不混用。

## 状态追踪
- [x] 批 1 后端（V32 + 过滤 + 端点 + 集成测试；ADR-0015 Accepted；#200 合入 2026-09-07）
- [x] 批 1b 读面扩展（#204 合入 2026-09-07）：/api/v1/admin-api 读面全开——
      audit-events（共享 AdminAuditController 查询服务）、api-keys、quota-rules、
      export-tasks 元数据（不读 file_bytes）、mcp-access-logs；会话仅 SYSTEM_ADMIN
      放行（403 ADMIN_API_FORBIDDEN）；首个开放面端到端集成测试（跨租户隔离/吊销
      即时/过期/审计光标）
- [x] 批 2 v1 写面（2026-09-08 ADR-0016 Accepted A+C）：告警规则/Webhook 全生命周期直接开（C）+
      导出创建委托（A）；Virtual Key 创建与批量治理留待 v2
- [x] 批 2 契约/示例（2026-09-08）：scripts/open-api-examples/（curl+Python+最小权限 README）+ OpenAPI 基线刷新
- [ ] 批 3 治理可选
