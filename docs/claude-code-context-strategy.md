# Claude Code 上下文与无压缩续接策略

## 1. 目的

Claude Code 通过 CC Switch 使用 DeepSeek/第三方 Plan 时，`/compact` 和自动压缩可能因上下文窗口认知、Anthropic 扩展字段、thinking/tool 状态或上游请求限制而失败。项目开发不得把“压缩一定可用”作为前提。

本项目采用 **disk-first continuity**：会话只是临时执行环境，Goal、状态、决定和验证证据必须落到 Git 工作区。压缩可用时使用；不可用时换新会话，不丢开发状态。

## 2. 默认运行方式

- 一个 Goal 使用一个新 Claude Code 会话；Goal 完成后直接 `/clear` 或退出，不跨 Goal 延续长对话。
- Goal 过大时允许多个新会话续接同一个 Goal，但不创建隐含的新范围。
- 无人值守 `/goal` 必须设置回合上限，默认每批最多 8 个 evaluated turns；完成不了就 checkpoint，下一批继续同一个 Goal。
- 每次启动都从 `CLAUDE.md`、执行契约、`progress.md` 和当前 Goal 重建上下文。
- 不依赖聊天中“你还记得”的信息；重要决定写入规格/ADR，执行状态写入 `progress.md`。
- 不把完整构建日志、供应商响应或大文件粘贴进对话；保存到被 Git 忽略的本地日志，再让 Claude Code读取相关片段。
- 只启用当前 Goal 需要的 MCP/skills；用 `/context` 检查启动成本和主要占用。

项目 `.claude/settings.json` 显式设置 `autoCompactEnabled: true`，用于排除配置误关；即使开启，工作流仍不得依赖它一定触发。

## 3. `/goal` 的特殊约束

`/goal` 会在每一轮结束后调用同一 provider 上配置的小型 evaluator，判断完成条件是否成立，然后自动开始下一轮。它适合可验证的任务，但也会让会话在无人干预下持续增长。因此本项目不使用无界 Goal。

标准条件：

```text
/goal Complete Goal Gx.y exactly as specified and prove every acceptance check.
Do not start another Goal and do not merge. If completion is not achieved by
the end of 8 evaluated turns, stop expanding scope, update docs/progress.md
with exact completed work, changed files, commands/results, blockers and the
next concrete action, verify git diff --check, report CHECKPOINTED, and stop.
The condition is satisfied only when Gx.y is fully DONE with required commit
and push evidence, or the 8-turn CHECKPOINT has been safely persisted.
```

Goal evaluator 只根据对话中 Claude 已经展示的证据判断，不会自行读文件或运行测试。因此每轮输出必须简短写明已执行命令和结果；最终 DONE/CHECKPOINT 必须明确展示证据。

8 回合是默认安全批次，不是产品限制。若单轮包含大量构建输出或大文件读取，改为 5–6 回合；经过实际验证仍有充足上下文时可以调整到 10–12，但禁止取消边界。

## 4. 会话预算

Claude Code 开始修改前运行 `/context`。以下任一情况触发 checkpoint：

- 上下文使用达到约 60%，且后面还有构建、测试或大文件分析。
- 即将运行可能产生大量输出的命令。
- 连续出现工具输出清理、响应明显退化或重复读取文件。
- `/compact` 已经失败一次。
- 当前会话包含重要但尚未落盘的决定。

60% 是保守运行值，不是 Claude Code 内部阈值；目的是在第三方上游真正拒绝长请求之前留下恢复空间。不要等到 `Prompt is too long` 才处理。

## 5. Checkpoint 协议

Claude Code 在结束当前会话前必须：

1. 停止扩大修改范围。
2. 运行能够快速完成的最小测试；不能运行的明确记录。
3. 更新 `docs/progress.md`：Goal 仍为 `IN_PROGRESS`，记录当前分支、已完成项、修改文件、测试结果、失败和下一条具体动作。
4. 检查 `git status --short` 和 `git diff --check`。
5. 默认保留工作区改动，不为“干净”而丢弃或隐藏。
6. 如果项目所有者明确允许 WIP 远端备份，创建 `chore(checkpoint): save Gx.y progress` 并 push 当前 Goal 分支；否则不创建虚假完成 commit。
7. 输出下面的续接指令，然后退出或 `/clear`。

续接指令：

```text
You are resuming the current Goal in a fresh context because compaction is not
trusted. Read CLAUDE.md, docs/claude-code-execution-contract.md,
docs/claude-code-context-strategy.md, docs/progress.md, docs/git-workflow.md,
and the current Goal completely. Inspect git status/diff and independently
verify the recorded state. Preserve all existing work. Resume only the current
Goal from the exact Next Action in progress.md. Do not start another Goal.
```

## 6. `/compact` 可用时

在空间充足时主动运行带重点的命令，而不是等待自动触发：

```text
/compact Preserve the current Goal, branch, changed files, implementation
decisions, exact tests and results, unresolved errors, user-owned changes,
security constraints, and the next concrete action. Drop verbose tool output.
```

压缩完成后：

1. 运行 `/context` 确认空间已释放。
2. 重新读取 `docs/progress.md` 和当前 Goal。
3. 核对 `git status --short`，避免摘要遗漏未提交文件。
4. 如果摘要与磁盘事实冲突，以 Git/文件/测试证据为准。

根目录 `CLAUDE.md` 会在压缩后重新注入，因此长期硬约束放在根文件；只有局部生效的规则不能作为唯一安全控制。

## 7. Auto-compact 显示 100% 但不触发

这是和手动 `/compact` 失败不同的故障：客户端已计算出上下文用满，但没有派发自动压缩。Claude Code 官方 issue 已记录即使使用官方 OAuth、没有第三方网关也可能出现同一 core regression，因此不能仅凭该症状认定 CC Switch/DeepSeek 有错。

处理顺序：

1. `claude --version`，优先使用当前 stable/latest 中已验证的版本。
2. `/config` 确认 Auto-compact 开启；检查 user/project/local/managed scopes 和 `DISABLE_AUTO_COMPACT`。
3. 在短上下文手动 `/compact smoke test`。手动成功而自动不触发，基本把协议转换排到次要位置。
4. 检查 CC Switch DeepSeek 本地路由的模型/上下文配置，避免错误的 1M 后缀或把实际窗口夸大。
5. 在问题解决前只运行有 5–8 回合边界的 `/goal`，到点 checkpoint 后新开会话。

不要依赖未进入官方设置参考的 `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` 作为生产修复；某些版本存在覆盖值不生效的报告。

## 8. `/compact` 失败时的分流

### `Conversation too long` / `Prompt is too long`

说明压缩启动得太晚，摘要请求自身也放不进窗口。不要反复重试。按官方建议退回最近几条消息后再试一次；仍失败则 checkpoint 并用 `/clear` 开新会话。

### `Extra inputs are not permitted`、`context_management` 或 beta header 错误

大概率是网关转发了请求字段但丢失/不支持对应 `anthropic-beta` Header。优先升级或修正 CC Switch/上游透传。临时诊断可在启动 Claude Code 前设置：

```powershell
$env:CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS="1"
claude
```

只有错误明确匹配时才使用；这会关闭依赖实验 beta 的功能，不是通用性能开关。

### `content[].thinking must be passed back` / `reasoning_content` 错误

这是多轮 thinking 状态在 Claude/CC Switch/DeepSeek 之间没有原样往返，和摘要内容本身无关。升级 Claude Code、CC Switch 和供应商适配；用关闭 extended thinking 或不产生 thinking 状态的模型做单变量对照。问题未修复时不要依赖长会话，使用 checkpoint + fresh session。

### 上游 400/413、超时或无响应

用早期、短上下文手动 `/compact` 对照：早期成功而接近满窗失败，优先怀疑实际上游 context/body/timeout 小于 Claude Code 的认知；任何长度都失败，优先检查协议字段和 Header。不要盲目把 Claude Code 的 context 配置写得比 Plan 实际能力更大。

## 9. 最小复现与取证

要确定根因，至少收集：

- Claude Code 版本、CC Switch 版本、Windows 版本。
- CC Switch 是仅改配置直连，还是启用了 Claude 本地路由/协议转换。
- 供应商和 Plan 名称、上游协议、实际模型 ID；不提供 Key。
- `/context` 在失败前的摘要。
- 手动 `/compact test` 的准确错误文本和 HTTP status。
- `/debug` 中对应请求的脱敏错误，以及 CC Switch 同一时刻的脱敏代理日志。
- thinking 开/关各一次，短上下文/长上下文各一次。
- 若可用，换一个已知支持 Anthropic Messages 的上游执行同一测试。

四个最小对照：

| 对照 | 结果解释 |
|---|---|
| 短上下文 + `/compact` | 验证功能/协议是否基本支持 |
| 长上下文 + `/compact` | 验证真实窗口和 body 限制 |
| thinking off/on | 验证 thinking 状态往返 |
| 同 Claude Code 版本换上游 | 区分客户端问题和 CC Switch/供应商问题 |

日志必须脱敏，不记录 prompt、代码、模型回答、API Key 或 Cookie。只有上述反馈回路可复现后，才把某一假设认定为根因。

## 10. Goal 设计约束

- 每个 Goal 必须能在无对话历史的情况下，仅靠仓库文件继续。
- 每个 `/goal` 条件必须包含回合/时间边界和 CHECKPOINT 成功条件。
- Goal 内超过一个上下文预算时，在 `progress.md` 写具体 Next Action，再开新会话。
- 测试输出只在报告中保留命令、PASS/FAIL 和关键错误，不复制数千行日志。
- Phase review 使用独立新会话，不在实现会话末尾追加大规模 review。
- 真实供应商验证单独会话执行，避免把凭证诊断和大量协议输出塞进实现上下文。

因此，即使整个开发周期内 `/compact` 完全不可用，G0.1–G6.5 仍应能够逐 Goal 完成。
