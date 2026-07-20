# Claude Code / Goal 开发工作流

本文用于把本仓库交给 Claude Code + DS V4-Pro 持续开发。Claude Code 是默认实施者，对当前 Goal 的代码、测试、文档、commit 和 push 负完整责任；当前规格作者不继续承担实现。详细角色和输出格式见 [`claude-code-execution-contract.md`](claude-code-execution-contract.md)。DS V4-Pro 是开发 Agent 使用的模型，不是本系统运行时依赖。

## 1. 第一原则

一次只执行 [`implementation-plan.md`](implementation-plan.md) 中一个 Goal。不要把整个项目作为一个超大 Goal；这会让验证、回滚和跨会话交接失真。

每次会话都从仓库根目录开始，并要求 Agent 先读 [`CLAUDE.md`](../CLAUDE.md)、[`progress.md`](progress.md) 和 [`claude-code-context-strategy.md`](claude-code-context-strategy.md)。若 Claude Code 的 `/goal` 具体语法随版本变化，以客户端当前帮助为准，但目标正文保持下面的约束。通过 CC Switch 使用第三方模型时，不把自动 `/compact` 视为可靠能力；一个 Goal 可以由多个全新会话续接。

## 2. 首次启动

推荐 Goal 正文：

```text
You are the implementation owner. Read CLAUDE.md,
docs/claude-code-execution-contract.md, docs/progress.md,
docs/document-map.md, docs/git-workflow.md and
docs/claude-code-context-strategy.md completely, then execute Goal G0.1 from
docs/implementation-plan.md. Work on G0.1 only. Implement, test,
document, commit and push the Goal branch. Do not merge or begin G0.2. If
blocked, finish all unblocked work and report concrete evidence plus the
smallest user decision needed. Run for at most 8 evaluated goal turns; if not
DONE by then, persist an IN_PROGRESS checkpoint with the next concrete action
and stop safely.
```

在 CC Switch 中为 Claude Code 选择 DS V4-Pro 时，只需配置该开发会话的 Base URL、Key 和模型映射。本项目 Gateway 的用户界面仍只向最终用户展示 Base URL 与 Virtual Key，不生成 CC Switch 配置。

## 3. 每个 Goal 的循环

1. **读取**：完整读 `CLAUDE.md`、`progress.md`、Goal 和 Goal 指向的专项文档。
2. **核对现状**：查看文件、测试和未提交变更，不假设上个会话已经完成。
3. **登记开始**：把 `progress.md` 的 current goal/status 改为 `IN_PROGRESS`。
4. **实现**：只改当前 Goal 所需内容；先建立失败测试，再实现关键协议行为。
5. **验证**：执行 Goal 指定命令和受影响模块测试，记录真实命令与结果。
6. **复核**：检查安全边界、透明代理、阻塞调用、Secret、许可证和文档差异。
7. **登记完成**：只有 Definition of Done 全部满足才标 `DONE`，设置下一个 Goal。
8. **提交与推送**：按 [`git-workflow.md`](git-workflow.md) 创建规范 commit，并 push 当前 Goal 分支。
9. **停止**：向用户汇报分支、commit、远端 URL、文件、测试和风险；不要自动进入下一个 Goal 或自动 merge。

若当前工作区有用户改动，必须保留并避免覆盖。不要用 destructive Git 命令清理。

## 4. 续接会话

推荐正文：

```text
Read CLAUDE.md and docs/progress.md. Independently verify the recorded state.
Resume only the current Goal. Preserve existing user changes. Complete its
remaining Definition of Done, update progress with exact test results, and stop.
```

如果 `progress.md` 写 `DONE` 但代码/测试不支持，先恢复为 `IN_PROGRESS` 并说明证据。若状态为 `BLOCKED`，先验证阻塞是否仍存在；能通过 Mock、fixture 或合理默认继续时继续，不能凭空伪造真实供应商验证。

如果上一会话因 `/compact` 失败或上下文预算主动结束，使用上下文策略中的续接指令，不使用旧聊天摘要作为唯一依据。先读磁盘状态和 Git diff，再从 `progress.md` 的 Next Action 继续。

`/goal` 不得无限运行。每批默认 8 个 evaluated turns；到点未完成就 checkpoint，在新会话重新启动同一个 Goal。完整条件模板见 [`claude-code-context-strategy.md`](claude-code-context-strategy.md)。

## 5. Review 会话

关键阶段结束可另开只读 review：

```text
Review completed Goal Gx.y against CLAUDE.md, its acceptance criteria, security
boundaries, and tests. Do not implement changes. Report only reproducible issues
with file/line evidence and severity.
```

修复 review 问题时仍归入原 Goal 或建立一个明确的修复 Goal，不混入无关重构。

## 6. 凭证与真实供应商测试

- 日常开发使用 WireMock/MockWebServer 和 fixtures，不需要真实 Key。
- 真实 Key 只通过未提交的 Secret 文件/环境注入；测试 profile 必须显式开启。
- 每次真实测试设定最小输出和费用上限，不输出正文。
- 个人 Plan 的成功不能证明团队 Plan；团队能力按其共享池/席位/成员 Key 形态单独验证。
- 未获得官方 API 的余额/周期能力时标 `UNAVAILABLE` 或 `LOCAL_ESTIMATE`，禁止浏览器抓取控制台。

## 7. 提交粒度

建议一个 Goal 一个可审查提交，较大的 Goal 可拆为测试、实现、文档多个提交，但不能混入下一 Goal。完整的初始化、分支、commit、push 和 PR 规则见 [`git-workflow.md`](git-workflow.md)。提交前执行：

```powershell
./mvnw.cmd verify
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run build
docker compose config
```

命令在 G0.1 建立后生效；不存在时应按 Goal 说明创建，而不是在 progress 中伪报通过。Linux/CI 使用对应的 `./mvnw`。

## 8. 文档同步

- API/DTO 变化：`api-contract.md` 和生成 OpenAPI。
- 表/约束变化：Flyway migration 和 `database-schema.md`。
- Provider/Plan 变化：`provider-catalog.md`、Adapter manifest、fixtures。
- 架构边界变化：新 ADR；不得只在代码注释中改变。
- Goal 结束：永远更新 `progress.md`。

## 9. 阻塞标准

只有缺少必要权限/凭证、外部系统不可用、互斥需求需要用户选择等真正无法推进的情况才标阻塞。报告必须包含：已尝试的命令/证据、影响的验收条目、仍可完成的部分、所需的最小用户决定。实现困难或测试失败本身不是阻塞。

## 10. 推荐执行顺序

严格按 G0 → G1 → G2 → G3 → G4 → G5 → G6。供应商 G3.x 可在核心 SPI 稳定后按客户实际凭证调整先后，但必须先完成 G3.1 的基准 Adapter；门户可使用稳定 OpenAPI 和 Mock 并行开发，但最终验收依赖真实后端契约。
