# 透明代理与 CC Switch 兼容

> 兼容性基线：CC Switch v3.15.0+；发布前必须使用客户实际版本重新执行端到端测试。

## 1. 集成边界

用户操作链路：

```text
管理员录入真实供应商 Key
          ↓
用户在门户创建 Virtual Key
          ↓
门户显示 Base URL + Virtual Key
          ↓
用户手工填入 CC Switch Provider
          ↓
CC Switch 配置 Claude Code / Claude Desktop / Codex
```

门户不生成 CC Switch 配置，不提供 Deeplink 或一键导入。

## 2. Gateway 与 CC Switch 的职责

| 能力 | Gateway | CC Switch |
|---|---:|---:|
| Virtual Key 鉴权 | 是 | 否 |
| 真实 Key 隐藏与替换 | 是 | 否 |
| 固定供应商/凭证映射 | 是 | Provider 配置选择 |
| Anthropic/OpenAI/Gemini 跨协议转换 | 否 | 是 |
| Claude Desktop 模型角色映射 | 否 | 是 |
| 供应商 usage 解析 | 是 | 可有，但不作为账本 |
| 内部用户/项目归集 | 是 | 否 |
| 原始流水导出 | 是 | 否 |
| 客户端配置切换 | 否 | 是 |

CC Switch v3.15 文档表明，其 Claude Desktop 本地路由支持 Anthropic Messages、OpenAI Chat Completions、OpenAI Responses 与 Gemini Native，并负责非 Claude 模型的 Sonnet/Opus/Haiku 角色映射。参见：[Claude Desktop 指南](https://github.com/farion1231/cc-switch/blob/main/docs/user-manual/zh/2-providers/2.6-claude-desktop.md)。

## 3. Base URL 设计

首版对用户展示同一个公共入口，例如：

```text
https://llm-gateway.example.com
```

Virtual Key 自身解析到唯一供应商产品和真实凭证，因此不需要在 URL 中暴露供应商 ID。Gateway 根据：

1. Virtual Key 固定路由；
2. 入站请求路径；
3. 供应商产品的路径策略；

拼接真实上游 URL。

如果 CC Switch 的某个 Provider 要求完整 URL，Gateway 可同时展示从公共入口派生的协议 URL，但首版门户的主要交付信息仍是 Base URL 和 Key。

## 4. 支持的透明协议族

Gateway 不要求每个供应商支持所有协议。ProviderProduct 只声明该产品真实支持的协议和路径。

首版协议解析器至少覆盖：

### Anthropic Messages

- `POST /v1/messages`
- `POST /v1/messages/count_tokens`
- SSE 流式事件
- `x-api-key`、`Authorization: Bearer`
- `anthropic-version`、`anthropic-beta`
- `cache_control`、thinking、tools、tool results
- cache creation/read Token

### OpenAI Responses

- `POST /v1/responses`
- 流式 Responses 事件
- Responses 子资源仅在上游产品实际支持且 CC Switch 需要时开放
- `Authorization: Bearer`
- input/output/reasoning items、function calls、usage

### OpenAI Chat Completions

Gateway 为真实上游产品保留透明支持，因为部分 CC Switch Provider 会把转换后的请求发送到该协议：

- `POST /v1/chat/completions`
- SSE `data:` 流
- tools、reasoning_content、usage

### Gemini Native

作为供应商适配扩展支持。首版是否进入 P0 由真实 CC Switch 契约测试决定。

## 5. 入站鉴权

为了兼容不同 CC Switch Provider，Gateway 接受以下位置中的 Virtual Key：

- `Authorization: Bearer <virtual-key>`
- `x-api-key: <virtual-key>`
- `api-key: <virtual-key>`
- 供应商预设明确需要的其他头

若同时出现多个且值不同，拒绝请求。Gateway 删除所有入站凭证头，再按供应商产品预设注入真实凭证，防止 Virtual Key 泄漏到上游。

## 6. 请求转发规则

### 6.1 必须保留

- 请求体的字段、数组顺序和未识别扩展字段；
- `anthropic-version`、`anthropic-beta`；
- 内容协商、压缩和 SSE 相关头；
- 供应商 Prompt Cache 与会话字段；
- 客户端取消信号；
- 上游 request ID 和 rate-limit/usage 相关响应头。

### 6.2 必须移除或重建

- 入站 Virtual Key 鉴权头；
- hop-by-hop headers；
- 不可信的 `Host`、`Content-Length`；
- 用户伪造的内部追踪头；
- 供应商产品明确禁止透传的头。

### 6.3 请求体处理

Gateway 可以流式解析请求体以读取 `model`、`stream` 和协议版本，但不得改变推理语义。对于 50 用户规模，可以设置合理的请求体上限并在内存中解析小型 JSON；大请求必须使用有界缓冲或流式 JSON，禁止无界聚合。

## 7. `/v1/models`

`GET /v1/models` 是 Gateway 统一生成的控制接口，使用 OpenAI 兼容列表格式，以便 CC Switch 自动发现模型。

结果是以下集合的交集：

```text
供应商产品目录
∩ 上游实时模型列表（若可用）
∩ 项目授权模型
∩ Virtual Key 创建时的授权快照
```

返回项必须包含真实模型 ID、显示名、供应商和能力标签。不能返回其他供应商或未授权模型。

## 8. 模型校验

推理请求中的 `model` 必须出现在 Virtual Key 的授权模型列表中。Gateway 不进行模糊匹配、版本猜测或自动别名替换。模型映射由 CC Switch Provider 配置处理。

## 9. 流式响应

- 不缓冲完整响应。
- 尽可能保留上游事件边界与顺序。
- 使用旁路解析器观察 usage 和终止事件。
- 旁路解析失败不得修改或吞掉下游响应，但必须将用量事件标记为 `USAGE_PARSE_FAILED` 并告警。
- 客户端断开时取消上游请求并记录 `CLIENT_CANCELLED`。
- 已经发送任何响应字节后禁止重试。

## 10. 缓存命中保护

Virtual Key 到真实凭证的确定映射可以避免同一会话在多个真实 Key 间漂移。Gateway 不增加系统提示词、不重排工具、不标准化 JSON、不更改模型名，尽量保持供应商 Prompt Cache 的输入一致性。

## 11. CC Switch 验收矩阵

| 客户端 | CC Switch 模式 | Gateway 关注点 |
|---|---|---|
| Claude Code | 直接 Provider 或本地路由 | Messages、count_tokens、beta 头、tools、thinking、cache usage |
| Claude Desktop | Direct 或 Model Mapping | `/v1/models`、Messages、角色模型映射后的真实 model |
| Codex | Provider 配置或本地路由 | Responses SSE、function calls、reasoning、取消、usage |

Claude Desktop 第三方 Provider 当前主要支持 Windows 与 macOS；Linux 不作为首版 Claude Desktop 验收平台。Claude Code 与 Codex 需要覆盖 Windows 开发环境和 Linux 部署访问。
