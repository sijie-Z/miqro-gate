# Claude Code 实施交接契约

## 1. 身份与责任边界

本项目的设计阶段由当前规划会话完成；**后续默认实施者是 Claude Code + 用户在 CC Switch 中选择的 DS V4-Pro**。

角色定义：

- **项目所有者（用户）**：决定产品范围、提供必要凭证/环境、审查 PR、合并、发布和交付客户。
- **Claude Code（实施者）**：读取仓库规格，逐 Goal 编码、测试、更新文档、commit 并 push Goal 分支。
- **当前 Codex 规划会话（规格作者）**：只负责已完成的需求澄清、研究和文档基线；不被假定会继续编写代码、补测试、操作 GitHub 或完成后续 Goal。
- **客户运维人员**：交付后根据 Runbook 部署、配置供应商凭证、监控和备份。

Claude Code 读到本文时，应把“你”理解为自己。不得等待规格作者继续实现，也不得把未完成代码留给规格作者善后。需要产品决策时直接向项目所有者提出最小问题。

## 2. 交付物是什么

本仓库不是一份供 Claude Code 自由发挥的需求草稿，而是一套实施合同：

- [`CLAUDE.md`](../CLAUDE.md)：强制执行规则。
- [`progress.md`](progress.md)：当前状态和唯一下一 Goal。
- [`implementation-plan.md`](implementation-plan.md)：Goal 范围和验收。
- [`document-map.md`](document-map.md)：发生冲突时的事实来源顺序。
- 产品、架构、API、数据库、Provider、测试、安全、UI、运维文档：各自领域契约。
- [`git-workflow.md`](git-workflow.md)：Claude Code 的 commit/push 权限与禁止事项。
- [`claude-code-context-strategy.md`](claude-code-context-strategy.md)：`/compact` 不可靠时的 checkpoint 和新会话续接。

文档中的“应、必须、禁止”是验收要求，不是建议。若代码实现需要改变已接受的产品/架构决策，Claude Code 必须停下并请求项目所有者决定；不能通过“更方便实现”自行改需求。

## 3. Claude Code 的默认授权

在当前 Goal 范围内，Claude Code无需每一步请求许可即可：

- 读取和修改本仓库文件。
- 创建测试、fixtures、migration 和必要文档。
- 运行 Maven、npm、Docker Compose 配置检查和本地测试。
- 下载 Goal 明确需要、许可证允许且版本固定的构建依赖。
- 按 `git-workflow.md` 初始化仓库、创建 Goal 分支、commit，并正常 push 当前 Goal 分支。
- 使用 Mock/WireMock 完成不依赖真实供应商凭证的实现。

这些授权不包括：

- force push、自动 merge、删除远端分支、覆盖远端历史或发布正式 Release。
- 使用真实客户环境/凭证，除非项目所有者为该测试显式提供并授权。
- 创建外部付费资源、充值、购买 Plan、联系客户人员或发送群消息。
- 改变产品范围、跨供应商路由原则、安全边界或数据保留策略。

## 4. 每次 Goal 的输入

一个可执行 Goal 必须包含：

- Goal ID，例如 `G0.1`。
- `implementation-plan.md` 中的目标、范围、验收和禁止项。
- `progress.md` 中当前真实状态。
- 相关专项契约和已有代码/测试。
- 若需要真实凭证：由项目所有者通过非 Git Secret 注入方式提供。

Claude Code 不得把“完成整个项目”当成单次 Goal。收到超大目标时，仍按 `progress.md` 的 Next Goal 执行并在完成后停止。

Claude Code 不得依赖聊天历史维持项目状态。上下文达到保守阈值时必须按上下文策略落盘并换新会话；换会话不改变 Goal ID、分支或验收责任。

使用 `/goal` 时一次最多运行执行策略规定的 evaluated turns。达到边界而未完成不是 `DONE`，必须形成 `CHECKPOINTED/IN_PROGRESS` 状态；项目所有者随后在新会话重新发出同一 Goal。

## 5. 启动时必须输出

Claude Code 在修改文件前向项目所有者简短报告：

```text
Executor: Claude Code
Goal: Gx.y — <name>
Branch: <current/new branch>
Scope: <本次做什么>
Out of scope: <明确不做什么>
Verification planned: <命令/测试>
Known blockers: <无或具体事项>
```

然后把 `progress.md` 更新为 `IN_PROGRESS`。发现工作区脏、当前分支错误、文档/代码矛盾时，先报告证据并保护已有改动。

## 6. 实施过程责任

Claude Code 对当前 Goal 的完整垂直切片负责：

1. 先理解事实来源，不只读 Goal 标题。
2. 检查当前代码和测试，不能假设仓库状态。
3. 实现正常、失败、权限、安全和恢复路径。
4. 运行实际验证，不能把“理论上能通过”写成 PASS。
5. 同步 API、migration、fixtures、配置和运维文档。
6. 检查 Secret、许可证、日志正文和协议透明性。
7. 按 Git 契约 commit、push；不自动 merge。
8. 更新 `progress.md`，然后停止。

Claude Code 可以在实现中采用合理的低风险细节默认值，但必须记录重要取舍。只有会改变产品行为、安全、数据、外部成本或交付方式的问题才向项目所有者询问。

## 7. 完成时必须交付

最终报告必须包含：

```text
Goal: Gx.y — DONE | IN_PROGRESS | BLOCKED
Outcome: <用户/系统现在能够做什么>
Branch: <branch>
Commit: <SHA and subject>
Push: <remote branch/URL or reason not pushed>
Changed: <核心模块/文件>
Verification: <每条命令及真实 PASS/FAIL>
Docs/ADRs: <更新内容>
Security/data impact: <明确说明>
Remaining risks: <无或具体事项>
Next Goal: <只报告，不启动>
```

没有 commit SHA、push 结果、验证证据和 `progress.md` 更新，不算完成。真实凭证验收未进行时必须写 `WAITING_FOR_CREDENTIAL`，不能把 Mock 通过描述为供应商已验证。

## 8. 状态与失败恢复

- `NOT_STARTED`：尚未开始。
- `IN_PROGRESS`：正在实现，或仍有可在本地推进的事项。
- `DONE`：该 Goal 的全部 Definition of Done 满足。
- `BLOCKED`：确实需要项目所有者权限/选择或外部状态变化，且已完成其他可推进工作。
- `WAITING_FOR_CREDENTIAL`：能力已实现并通过 Mock，但真实供应商验证待凭证；它是验证子状态，不自动阻止后续不依赖该验证的 Goal。

新会话必须独立核验 `progress.md`。上次报告与仓库事实冲突时，以可复现的 Git/测试证据为准，修正 progress 后继续，不复制虚假状态。

## 9. 项目所有者的最小操作

项目所有者只需要：

1. 在 Claude Code/CC Switch 中选择开发模型和可用连接。
2. 发出一个 Goal 目标。
3. 必要时批准依赖下载、Docker、真实凭证测试或外部动作。
4. 查看 Claude Code 的 commit、测试证据和 PR。
5. 决定 merge、发布和下一个 Goal。

项目所有者不需要在每个会话重新解释产品背景；Claude Code 有责任从仓库读取。

## 10. G0.1 首次接管指令

建议直接作为 `/goal` 或等价目标正文：

```text
You are the implementation owner for this repository. Read CLAUDE.md,
docs/claude-code-execution-contract.md, docs/progress.md, docs/document-map.md,
docs/git-workflow.md, docs/claude-code-context-strategy.md, and Goal G0.1 in
docs/implementation-plan.md completely.

Execute G0.1 only, for at most 8 evaluated goal turns. Initialize and connect the repository as documented,
implement the complete Goal, add tests, run every acceptance command, update
all affected docs and docs/progress.md, create compliant commits, and push the
G0.1 branch. Do not merge and do not begin G0.2.

Before editing, report Goal/branch/scope/out-of-scope/planned verification.
At completion, report outcome, branch, commit SHA, push URL, exact test results,
security/data impact, remaining risks, and the next Goal. If blocked, complete
all unblocked work and provide concrete evidence plus the smallest decision needed.
If G0.1 cannot be completed within 8 evaluated turns, persist an IN_PROGRESS
checkpoint in docs/progress.md with the next concrete action, report
CHECKPOINTED, and stop safely instead of exhausting the context.
```
