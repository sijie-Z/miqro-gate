# ADR-0002：Gateway 采用协议透明代理

- 状态：Accepted
- 日期：2026-07-17

## 决策

Gateway 不做 Anthropic、OpenAI、Gemini 之间的跨协议转换。它只完成 Virtual Key 鉴权、真实凭证替换、固定上游转发、模型授权、usage 解析与审计。协议转换和模型映射交给 CC Switch。

## 原因

CC Switch 已针对 Claude Code、Claude Desktop、Codex 维护本地路由和格式转换。重复实现会扩大风险并可能破坏工具调用、推理和 Prompt Cache。

## 后果

- ProviderProduct 必须声明真实协议和路径。
- Gateway 仍需理解多种 usage/SSE 格式，但不改变请求语义。
- 用户在 CC Switch 中选择匹配的 Provider API Format。

