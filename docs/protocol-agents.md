# Agent / 模型协议全景（Protocol Landscape）

> 范围：MiQroGate 网关对外暴露的「模型与 Agent 协议」面——哪些协议可入、如何判定、
> 上游侧协议如何声明、哪些客户端矩阵已验证。本文是**盘点与索引**；协议转换不在范围
> （CLAUDE.md 红线：CC Switch 负责客户端配置/协议转换与模型映射）。

## 1. 入站协议（Gateway 暴露的透明代理入口）

`ProxyController` 只放行三条路径，其余一律 `unsupported_path` 错误：

| 入口路径 | 协议 | 判定/用途 |
|---|---|---|
| `POST /v1/messages` | **Anthropic Messages** | `ProtocolFamily.ANTHROPIC_MESSAGES`（CC Switch 直连 Claude 系客户端） |
| `POST /v1/responses` | **OpenAI Responses** | `ProtocolFamily.OPENAI_RESPONSES`（Codex 等） |
| `POST /v1/chat/completions` | **OpenAI Chat Completions** | `ProtocolFamily.OPENAI_CHAT_COMPLETIONS`（广谱 OpenAI 风格客户端/上游） |

- 单一 Virtual Key 同时可用任一路径；`/v1/models` 按 Key 权限返回允许模型（跨协议一致）。
- 上游产品在 provider catalog 里按 `ProtocolFamily` 声明协议（seed 见
  `CatalogSeedService`，例如 DeepSeek 类产品声明 OpenAI Chat 系）；不支持的组合在
  请求路径被上游适配层判定。
- **字节级透传原则**：透明代理不重排/标准化/补写请求正文；L1 缓存只读不写正文、
  缓存键派生只解析必要元数据（见 G7.4/ADR-0009）。
- 错误信封按客户端协议方言返回（Anthropic SSE `event: error` 形 vs OpenAI JSON
  error 形）；上游错误保留 requestId 上下文，不泄漏内部路径。

## 2. 客户端 × 协议矩阵（已人工/自动化验证的通道）

完整索引在 `cc-switch-compatibility/README.md`：

| 客户端 | 协议 | 通道 |
|---|---|---|
| Claude Code | Anthropic Messages（直连） | `matrix-claude-code-anthropic.md` |
| Claude Code + CC Switch 本地路由 | OpenAI Chat Completions | `matrix-claude-code-openai-routing.md` |
| Codex | OpenAI Responses | `matrix-codex-responses.md` |
| Claude Desktop | 直连 / 模型映射 | `matrix-claude-desktop.md` |

契约测试锚点（自动化，CI 执行）：

- `VirtualKeyAuthContractTest`：鉴权/协议路径/权限与 usage 语义（含 L1 缓存用例）。
- gateway 模块 `*ContractTest` 家族：字节一致、流式透传、usage 提取。
- e2e/前端 mock 均走同一协议面。

## 3. 上游协议声明（provider-catalog）

- 每个 `provider_product` 的 `protocols` 数组声明其上游协议族（如
  `["messages"]`、`["chat_completions"]`），seed 常量为准；实现状态
  VERIFIED/IMPLEMENTED 记录在 `docs/provider-catalog.md`（真实凭证矩阵见
  feature-backlog H 组，逐产品登记）。
- 上游侧协议能力（如 Anthropic 兼容端点 vs 原生 OpenAI 端点）以适配器实现为准，
  禁止下载执行远程插件式适配代码（provider-catalog §304）。

## 4. 缺口与红线

- 红线：**不做跨协议转换**（`/v1/messages` → 上游 OpenAI Chat 之类）——这是 CC
  Switch 的职责；网关按 Key 所属产品直接寻址。
- 已知开放项（记录待评估，不承诺实现）：
  - Google Gemini `generateContent` 家族：当前非入站协议；如需接入按产品协议
    声明扩展 + 客户端矩阵补齐（决策点：是否值得，等使用场景）。
  - OpenAI Assistants/Realtime：与「工具正文/会话」边界待产品决策，默认不做。
  - MCP 作为协议面：MCP Server 管理/工具注册/ACL 已配置面化（F11/V25/V28）；
    MCP 经网关的**代理接线（F01）**后才成为完整协议面。

## 5. 变更入口

- 新增入站协议：改 `ProxyController.ALLOWED_PATHS` + `ProtocolFamily` +
  usage 解析/错误信封分支 + 契约测试 + 本文矩阵行。必须 ADR 级评审（触碰红线面）。
- 仅新增上游产品协议声明：provider catalog + seed（无代码面）。
