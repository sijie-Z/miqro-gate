# Agent Instructions

本仓库后续默认实施者是 Claude Code；当前规格作者不负责继续编码。所有编码 Agent 必须先完整阅读 [`CLAUDE.md`](CLAUDE.md) 和 [`docs/claude-code-execution-contract.md`](docs/claude-code-execution-contract.md)，再读取 [`docs/progress.md`](docs/progress.md) 和当前 Goal 的规格。

核心约束：Java 21、WebFlux 透明代理、Vue 3、PostgreSQL；协议转换交给 CC Switch；Virtual Key 固定映射；不自动路由、不限流、不记录正文、不做首版响应缓存、不使用 LiteLLM/Bifrost 运行时。

按 [`docs/implementation-plan.md`](docs/implementation-plan.md) 一次只执行一个 Goal。每个 Goal 必须包含测试、文档更新和 `docs/progress.md` 状态更新。不得自动进入下一个 Goal。

默认验证命令和完整禁止事项以 [`CLAUDE.md`](CLAUDE.md) 为准。

实现者对当前 Goal 的代码、测试、文档、commit 和 push 负完整责任；完成后报告证据并停止，不等待规格作者善后。
