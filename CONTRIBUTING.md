# Contributing

本项目后续默认由 Claude Code 实施、项目所有者审查与合并；当前规格作者不承担继续编码。开始前必须阅读：

1. [`CLAUDE.md`](CLAUDE.md)：产品和 Agent 硬约束。
2. [`docs/claude-code-execution-contract.md`](docs/claude-code-execution-contract.md)：实施责任、授权和输出格式。
3. [`docs/claude-code-context-strategy.md`](docs/claude-code-context-strategy.md)：压缩失败时的 checkpoint 和新会话续接。
4. [`docs/progress.md`](docs/progress.md)：当前真实进度。
5. [`docs/implementation-plan.md`](docs/implementation-plan.md)：当前 Goal。
6. [`docs/git-workflow.md`](docs/git-workflow.md)：分支、commit、push 和 PR。
7. 当前 Goal 对应的专项契约；前端还要阅读 [`docs/frontend-design.md`](docs/frontend-design.md)。

## 基本规则

- 一次只完成一个 Goal，不混入下一目标。
- 保留工作区中的用户改动，不使用 destructive Git 命令。
- 正常业务代码不直接 push 到 `main`，不 force push，不由 Agent 自动 merge。
- 代码、测试、文档和 `docs/progress.md` 一起完成。
- 禁止提交 Secret、请求/回答正文、客户代码、数据库 dump 和导出文件。
- Gateway 保持协议透明；不得在前端或后端悄悄引入跨供应商路由。

## 提交前

运行当前 Goal 的验证命令，并至少检查：

```powershell
git status --short
git diff --check
git diff
git diff --cached --check
git diff --cached
```

完整 Definition of Done 和构建命令见 `CLAUDE.md`。提交消息示例：

```text
feat(gateway): proxy anthropic message streams transparently
```

## 安全问题

不要在公开 Issue 中粘贴 API Key、Virtual Key、prompt、代码、模型回答或供应商账单原文。通过客户认可的私有渠道报告，并立即轮换可能泄漏的凭证。
