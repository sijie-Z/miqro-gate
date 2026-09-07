# ADR-0015：管理开放 API 的机器凭据（Admin API Keys）

- 日期：2026-09-07
- 状态：Proposal（owner Accepted 后动工批 1）
- 关联：ADR-0010/0011（api_consumers/JWT 机器通道先例）、F59（backlog）、
  docs/open-admin-api-plan.md（三批拆解）

## 背景

leader 方向（2026-09-07）：像腾讯 AI 网关一样，除 Web 控制台外提供可编程
API 操作（POST/GET 以 API 形式管理）。仓库现状 = `/api/v1/**` 全量 REST +
OpenAPI 3.1 + CI breaking-check，但管理面认证面向“人”（会话 + CSRF + F05
门户白名单）。缺：机器对机器管理凭据 + 对外文档/示例。

## 决策

1. **凭据形态**：管理机器密钥 = 256-bit 随机（展示前缀 `mqk_` + 16 hex），
   库中仅存 **SHA-256 摘要（bytea）**（与 Virtual Key / api_consumers 同族）；
   只展示一次明文。可设可选过期；支持吊销（即时生效，直读库校验）。
2. **传输**：`Authorization: Bearer mqk_…`（择一，不另设 X-API-Key）。
   机器通道**豁免 CSRF**（非 cookie 承载）。
3. **Principal 建模（关键决策点）**：机器身份 = 租户级系统主体，无 userId。
   **批 1 范围采用最小风险口径：先开放只读子集**（用量汇总/明细、审计、Key
   列表、配额规则、导出任务查询、MCP 访问日志），写操作与全量面在批 1b/批 2
   评估 userId 语义后扩（所有写操作须解决 created_by/审计的“机器执行者”表示）。
4. **治理默认**：批 1 不限频（与 F05 默认不限制一致），仅登记日志；作用域
   （只读/写/端点白名单）列入批 3 可选。
5. **安全红线**：密钥只存摘要；展示仅 prefix；吊销即时；机器调用全部进审计
   （`ADMIN_API_KEY_*` + 操作审计沿用既有链）；正文依旧不落库；与
   api_consumers（外部平台账单通道）语义分离登记，不混用。

## 影响

- 新表 V32 `admin_api_key`（tenant_id、name、digest、prefix、created_by、
  expires_at、revoked_at、created_at）；每租户多把。
- 新管理端点（SYSTEM_ADMIN-only）：发行/列表/吊销 + 审计事件
  `ADMIN_API_KEY_ISSUE/REVOKE`。
- 新过滤链：`AdminApiKeyFilter` 与 SessionFilter/F05 白名单并存（机器通道
  不走门户白名单路径）。
- 文档：configuration-reference 新行、api-contract §新节、OpenAPI 自动随
  controller 生成（breaking-check 覆盖）。

## 测试与验收

- 单测：摘要/前缀/过期/吊销判定矩阵。
- 集成：发行（只示一次）→ 吊销即时生效 → 过期 401 → 摘要不可逆（库中无明文）
  → 读子集端点 200、写/全量端点维持既有鉴权（403/401）→ 审计两事件。
- CI 全绿后并入 develop。

## 未决（等 owner/leader）

- 版本口径：本 ADR Accepted 即动工批 1（read-only 子集）；写面扩展需本 ADR
  修订（补 machine-executor 的 userId/created_by 语义）。
