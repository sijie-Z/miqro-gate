# ADR-0013：MCP 调用代理接线

- 状态：**Accepted（2026-09-05）**——决策依据：所有者指示「按阿里云/腾讯云怎么做的、参考他们的文档做」；实际实现 #154 已按腾讯 doc 135906（MCP 调用代理形态）/134890（两级 ACL）落地，即下方决策点 1 的推荐选项 **B**（消费者 API Key 进网关快照 + 服务/工具两级 ACL 判定）。决策点 2 按推荐（streamable HTTP 入站最小集、SSE 二期）；决策点 3 挂点（`McpUpstreamClient` 出口）随 F12/F13/F15 实施。
- 日期：2026-09-04（状态化：2026-09-05）
- 关联：[feature-backlog F01](../feature-backlog.md)（接线）、F11（路由规则，配置面已 DONE）、F12-F15（运行时护栏/日志，全部依赖 F01）、[ADR-0002](0002-transparent-proxy.md)（协议透明）、[ADR-0010](0010-consumer-api-key-billing.md)/[0011](0011-consumer-jwt-authentication.md)（消费者凭据）、腾讯 doc 134890/134818（ACL/模型探测）、raw 03（MCP quickstart）

## 现状（配置面已就绪，调用面未接线）

- 管理面：`mcp_services`（注册/上下线/健康，V20）、`mcp_tools`（V21）、两级 ACL（V25）、路由规则（V28）全部落库并有管理 API。
- 判定策略：`McpAccessPolicy`（domain 纯函数）就绪；健康探测已有（`McpHealthChecker`，HTTP GET）。
- **缺口**：网关数据面没有 MCP 入站/转发路径——外部客户端无法经网关调用任何 MCP 服务；F12-F15 与 F44 因此全部不可用。

## 决策点 1：调用者鉴权通道（与 ACL 语义强绑定）

ACL 名单语义（doc 134890）基于 **API 消费者**（`api_consumers`），所以调用面必须能解析出 `consumerId`：

| 选项 | 说明 | 评估 |
|---|---|---|
| A. Virtual Key 通道 | 复用网关已有 Key 快照校验；但 Virtual Key 无 consumer 身份，与 ACL 语义不匹配 | 需要 vkey↔consumer 映射或 ACL 改挂 vkey —— 改动大且与 doc 语义偏移 |
| B. **消费者凭据引入网关**（推荐） | 网关新增对 `mqk_api_` Key（与 JWT 后续）的校验：消费者 Key digest 进**只读快照**（对齐 vkey HMAC 快照模式），解析出 consumer → `McpAccessPolicy` 判定 | 语义一致（doc 134890 消费者即此面）；快照机制复用度高；与 ADR-0010/0011 通道衔接（同一把 Key 可既查 billing 又调 MCP） |
| C. 网关只做内网代理（无鉴权） | 由前置层鉴权 | 违背私有化安全基线；排除 |

推荐 **B**：先在快照体系新增 consumers 层（key_digest 派生同 vkey 风格、无明文），路由面按服务名解析后执行 server/tool 两级判定；tool 判定读取 JSON-RPC 信封的 `method.name`（仅元数据，不解析 args 正文）。

## 决策点 2：入站/转发传输形态

- 入站路由形态候选（backlog 已记）：`/mcpservers/{serviceName}/mcp`（腾讯形态）。备选：`/mcp/{serviceId}`（不暴露名称）。
- 上游：`mcp_services.endpoint` 为 https；传输按 service.transport：`STREAMABLE_HTTP`（POST JSON-RPC + SSE GET 流）与 `SSE`。
- 推荐：入站先支持 **streamable HTTP 客户端视角的最小集**（POST initialize/tools/call 透传 + SSE 响应回流），与会话/流状态（F01 不做分布式会话缓存——raw 09 注明 MCP 分布式会话缓存为独立项）正交。

## 决策点 3：失败/护栏挂点（F12-F15 落地位置）

- F12 重试门禁、F13 熔断、F15 元数据日志均在**网关调用上游 MCP 的出口**挂点（新 `McpUpstreamClient` 内），默认关闭/不读正文。
- usage 记账：MCP tool 调用无 token 语义——建议记**请求级元数据行**（requests-only，`usage_missing=true` 语义或专用列），与 raw 16 `aigw.mcp.*` 日志同源不重复。此点亦需 owner 认可（涉及 usage 口径）。

## 建议实施顺序（Accepted 后）

1. 快照新增 `api_consumers` 层 + 网关 consumer 鉴权（Key digest；JWT 二期）→ 契约测试；
2. `McpUpstreamClient`（streamable HTTP，超时/退避按 health-config 同参）+ 转发 handler（serviceName 解析 + ACL + tool 信封元数据提取）；
3. 元数据日志（F15）与请求级 usage 行；
4. F12/F13 挂点（默认关闭）。

## 未决（需 owner/leader）

- 鉴权选项 A/B/C；入站前缀与是否暴露服务名；usage 口径；SSE transport 是否一期做（推荐二期）。
- 在此之前不写数据面代码（F01 保持 PLANNED）；管理面（已交付部分）不受影响。

## Accepted 裁决记录（2026-09-05）

- 决策点 1 → **B**（#154 已实现：`mqk_api_` 消费者 Key SHA-256 digest 进网关只读快照，路由面按服务名解析 → 服务级 ACL → `tools/call` 工具覆盖与启停判定）。
- 决策点 2 → 入站形态 `/mcpservers/{serviceName}/mcp`（#154）；transport=STREAMABLE_HTTP 最小集，SSE 传输维持二期；会话无状态透传（无分布式会话缓存）。
- 决策点 3 → usage 请求级元数据行口径维持未定，暂以 F15 元数据日志（不存正文）先行；F12/F13 默认关闭挂 `McpUpstreamClient` 出口（Q3 队列）。

