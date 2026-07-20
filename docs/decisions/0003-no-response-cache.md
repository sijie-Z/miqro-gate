# ADR-0003：第一版不做模型响应缓存

- 状态：Accepted
- 日期：2026-07-17

## 决策

第一版不提供内存、Redis 或硬盘模型响应缓存，但保留 `GatewayResponseCache` 扩展接口。

## 原因

Coding Agent 请求包含持续变化的代码上下文、工具状态和副作用。缓存完整回答容易返回过期内容或重复工具调用。系统当前规模也不需要通过缓存降低上游成本。

## 后果

- 必须完整透传供应商 Prompt Cache 字段和缓存 Token。
- 未来缓存必须显式开启，Claude Code、Claude Desktop、Codex 和工具调用默认关闭。

