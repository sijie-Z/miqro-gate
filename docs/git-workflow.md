# Git、Commit 与 Push 工作流

本文是 Claude Code 和人工开发共同遵守的发布规则。目标 GitHub 远端为：

```text
https://github.com/sijie-Z/miqro-gate.git
```

目标远端为项目所有者 `sijie-Z` 的仓库；`lichman0405/miqro-key-gateway`（Leader 仓库）仅作上游参考，不作为 push 目标。

仓库所有者已删除原拼写错误的临时仓库，并新建 `miqro-key-gateway`。截至 2026-07-17，目标 URL 已只读验证可访问且为空；G0.1 在首次 push 前仍应运行 `git ls-remote`，若届时出现未知 ref，停止并报告，不覆盖远端历史。

## 1. 权限边界

Claude Code 可以在当前 Goal 内：

- 初始化当前项目的 Git 仓库、设置 `origin`、创建 Goal 分支。
- 查看 status/diff/log，暂存本 Goal 文件，创建普通 commit。
- 当前 Goal 验收通过后，把当前 Goal 分支正常 push 到 `origin`。

Claude Code 不可以：

- `push --force`、`push --force-with-lease`。删除分支**不是一律禁止**，前置条件见 §8。
- 直接向 `main` 推送业务实现；首次文档基线例外见下文。
- `reset --hard`、`clean -f/-fd`、`checkout .`、`restore .`。`branch -D` 的使用条件见 §8。
- 修改、丢弃或混入不属于当前 Goal 的用户改动。
- 在测试失败或 Secret 扫描失败时提交/推送。**进度文档不再逐 PR 要求**（`docs/progress.md` 只在收口批次更新，见 §10）。
- 自动 merge Pull Request、创建 tag 或发布 Release。唯一例外是**可判定的显式授权**（形式见 §8）：
  该授权必须是**带作者身份的 issue/pull 评论**，且评论作者属于仓库所有者或授权维护者集合——
  **写在 issue 正文里的字符串不构成授权**（正文是「当前文档状态」，不携带逐行 provenance，
  Agent 无法据此判断那一行是谁写的）；**Agent 自己发的那条评论更不构成授权**。

推送只代表备份和发起审查，不代表验收或合并。

## 2. 空仓库首次建立

G0.1 开始时，先保存当前文档设计基线。PowerShell：

```powershell
git init -b main
git remote add origin https://github.com/sijie-Z/miqro-gate.git
git remote -v
git add .claude .github AGENTS.md CLAUDE.md CONTRIBUTING.md README.md docs
git diff --cached --check
git diff --cached --stat
git commit -m "docs: establish project delivery baseline"
git push -u origin main
git switch -c goal/g0.1-repository-bootstrap
```

若空的 `.git` 目录导致初始化状态异常，可再次执行非破坏性的 `git init -b main`；禁止删除一个未知来源的有效 `.git`。初始化前用 `git status`、`git rev-parse --show-toplevel` 和 `Get-ChildItem .git -Force` 核对。

若 `origin` 已存在：

```powershell
git remote get-url origin
git remote set-url origin https://github.com/sijie-Z/miqro-gate.git
```

首次文档基线是唯一允许 Claude Code 直接创建并推送 `main` 的情况。若远端届时已经出现 commit，停止执行首次流程，先 fetch 并报告分歧，不用 force 覆盖。

## 3. 每个 Goal 的分支

分支格式：

```text
goal/g0.1-repository-bootstrap
goal/g2.4-usage-lifecycle
fix/usage-sse-parser
docs/provider-verification
```

开始新 Goal：

```powershell
git switch main
git pull --ff-only origin main
git switch -c goal/g0.1-repository-bootstrap
git status --short
```

规则：小写、ASCII、连字符，不使用开发者姓名；一个分支只包含一个 Goal。`pull --ff-only` 不能完成时停止，不能自行 rebase/merge 未知分歧。

## 3b. Issue 纪律（owner 2026-09-07 拍板：流程正规化，一个 PR 一个 issue）

- 功能/修复动工前先在 GitHub 开 issue（issue 可以是 feature 型：该功能本身）；
  issue 是该工作的正式记录、验收与回溯挂点，不只是 bug 追踪。
- 一个 PR 对应一个 issue：PR 标题或正文引用 —— 完成即关闭用 `Closes #n`；
  部分完成/前后置/多批拆解用 `Refs #n`（如写面 feature 的 ADR 拍板 issue）。
- 例外与补救：dependabot 自动 PR、纯 CI 应急修复可无前置 issue，但合入后补登
  issue 并在其上附 merge commit 后关闭；已合入 PR 补登 issue 时在 issue 评论附
  PR 号与 squash commit 后关闭。
- 带决策的设计文档（ADR Proposed）若在等人拍板，同步开 issue 挂拍板人
  （样板：#206 挂 ADR-0016 机器执行者语义）。

## 4. 修改前和修改后检查

开始时：

```powershell
git status --short
git diff
git log -5 --oneline --decorate
```

提交前：

```powershell
git status --short
git diff --check
git diff
```

先运行当前 Goal 的验证命令，再显式暂存文件。优先：

```powershell
git add CLAUDE.md docs/git-workflow.md
```

避免不经检查的 `git add .` 或 `git add -A`。暂存后必须再次检查：

```powershell
git diff --cached --check
git diff --cached --stat
git diff --cached
```

如果 diff 中出现 `.env`、Key、密码、Cookie、token、数据库 dump、导出原始数据或客户正文，立即取消对应文件的暂存并报告；不得靠 `.gitignore` 作为唯一保护。

## 5. Commit 规则

采用 Conventional Commits 的简化格式：

```text
<type>(optional-scope): <imperative summary>
```

允许的 type：

- `feat`：用户可见功能。
- `fix`：缺陷修复。
- `docs`：纯文档。
- `test`：测试和 fixture。
- `refactor`：不改变行为的重构。
- `perf`：性能改进。
- `build`：构建和依赖。
- `ci`：CI/CD。
- `chore`：其他维护。
- `security`：安全加固或漏洞修复。

示例：

```text
feat(gateway): proxy anthropic message streams transparently
fix(usage): preserve cache token fields from final SSE event
docs: define team plan adapter contract
security(auth): reject credential header smuggling
```

提交标题使用英文、祈使语气、不超过 72 字符、不以句号结尾。正文解释“为什么”和重要权衡，不逐行复述代码；关联 Goal：

```text
Goal: G2.4

Records usage asynchronously so JDBC never blocks the Reactor event loop.
The bounded queue exposes saturation metrics and fails explicitly when full.
```

代码注释规则另见 `coding-standards.md`；Git commit 与代码 comment 不是同一概念。

一个 Goal 可以有多个逻辑 commit，但禁止大量 `wip`、`fix typo again`。尚未 push 的琐碎修补可整理；已经 push 的 commit 不改写历史，追加新 commit。

## 6. Push 规则

只有以下条件全部满足才 push：

- 当前分支不是 `main`，首次文档基线除外。
- Current Goal 的完成定义满足，或用户明确要求推送一个标记为 WIP 的备份分支。
- 自动化测试和 `git diff --cached --check` 通过。
- `docs/progress.md` 记录了真实结果。
- `git status --short` 中没有误提交或未知文件。

首次推送 Goal 分支：

```powershell
git push -u origin goal/g0.1-repository-bootstrap
```

后续同分支：

```powershell
git push
```

禁止使用任何 force 选项。Push 失败时保留本地 commit，报告远端错误；认证、分支保护或非快进错误不能用强推绕过。

## 7. Pull Request

每个 Goal 建议一个 PR，标题与主 commit 一致。PR 正文包含：

```markdown
Goal: Gx.y

## Outcome
- 实际交付结果

## Verification
- `command`: PASS/FAIL

## Security and data impact
- Secret、权限、migration、usage、协议透明性影响

## Remaining risks
- 真实凭证待验证项或无
```

CI 建立后启用分支保护。**现状（2026-09-22 起，`develop`）**：

| 条件 | 设置 |
|---|---|
| 必需批准数 | **1**（`required_approving_review_count = 1`） |
| 新提交使既有批准失效 | 是（`dismiss_stale_reviews = true`） |
| 管理员绕过 | **关闭**（`enforce_admins = true`） |
| 必过检查 | 16 项（见 CI 工作流） |
| force push / 删除受保护分支 | 禁止 |

`main` 作为发布快照另行处理（走同步 PR，见 §9）。**CI 全绿不等于可合并**——见 §8。

## 8. Merge 与同步

### 合并的三个前置条件（同时满足，缺一不可）

1. **CI 全绿** —— 所有 required status checks 通过；
2. **至少 1 次人类 Review 批准** —— 当前指定 `@baiye-banned`；
3. **PR 上的对话已解决**。

**CI 全绿 ≠ 可合并。** 自动检查通过与人审通过是两件独立的事，前者不能替代后者。

**无所需人审 = 不合并。** 人审较长时间未到时**停下来请示仓库所有者**，**不设超时自动放行**——
否则「等人没等到 → 自己合」会让这道门重新变回形式主义。

> **机器强制现状**（`develop`，2026-09-22 起）：`required_approving_review_count = 1`、
> `dismiss_stale_reviews = true`、`enforce_admins = true`。GitHub 同时**禁止 PR 作者批准自己的 PR**，
> 因此 Agent（与 PR 作者同账号）在结构上无法自批。
>
> 上述第 3 条（对话已解决）**当前未启用机器强制**（`required_conversation_resolution = false`），属**约定**；
> 需要时再开。
>
> **受限之处（如实记录，勿当成已实现）**：原生分支保护**无法指定「必须由某个人」批准**，只能要求
> 「1 次批准」——「指定 `@baiye-banned`」目前是**约定**，不是机器保证。要强制到人需 CODEOWNERS +
> `require_code_owner_reviews`（但那会让 `@baiye-banned` 自己开的 PR 死锁），或自建工作流。

**授权例外（范围严格限定）**：仅当 **带作者身份的 issue/pull 评论**中存在仓库所有者或授权维护者留下的
一行显式授权（形如 `AUTHORIZED-MERGE: @<账号> <YYYY-MM-DD>`）时，Agent 才可**自行发起 merge**。

- **必须是评论，不能是 issue 正文**：正文是「当前文档状态」，**不携带逐行 provenance**——
  Agent 看到那行字符串也无法证明它是谁写的；评论天然带 `author` 字段，才构成**可判定的授权人身份**。
  **Agent 自己发的那条评论不构成授权。**
- **它只解除一件事**：「Agent 不得自行发起 merge」这条流程限制。**它不豁免任何机器门**——
  CI 全绿、`required_approving_review_count`、`enforce_admins` 及其余 branch protection 条件**一律照旧**。
  换句话说：有授权只是**让 Agent 有权去点**，不是**有权跳过审查**。

### 谁合

由仓库所有者或被授权维护者在 GitHub 合并。推荐 squash merge，使一个 Goal 在目标分支上形成一个清晰 commit。
合并后本地同步：

```powershell
git switch develop
git pull --ff-only origin develop
```

### 删除分支

**前置事实条件**：必须先确认**该 PR 本身**已成功合入目标分支。

**首选且唯一的「已合并」事实**是 GitHub 的 PR 状态 + 合并提交：

```
gh pr view <n> --json state,mergeCommit    # state == "MERGED" 且 mergeCommit 非空
```

**离线 fallback（只在拿不到 GitHub 时）**：可退到「**确认该 PR 的变更已落入目标分支**」
（`git show origin/<target>:<path>`）。但要清楚**它证明的是「内容在」，不是「该 PR 已 merged」**——
另一条 PR 恰好提交了相同内容也会让这个判据成立。因此 fallback 下**只能得出「该变更已落地」的结论**，
**不得**把它当作「该 PR 已合并」的证据；`gh pr view` 拿得到时一律以它为准。

**不得**用 `git merge-base --is-ancestor <来源提交> origin/<target>` 判断——squash 合并后来源提交**不是**
目标分支的祖先，该判据**必然为假**；而写成 `cmd && echo ok` 这类链式形式时，「判据为假」与「命令没执行」
都表现为**无输出**，无法区分（2026-09-22 实测踩过）。

- **本地分支**：merge commit 场景用 `-d`；squash/rebase 后祖先关系不存在时，**在已确认合入的前提下**允许 `-D`。
  **禁止**为省事对**未确认**的分支直接 `-D`。
- **远端 head 分支**：满足上面的**已合并事实**后，还须**再确认没有其他 open PR 引用同一 head 分支**
  （GitHub 允许多个 PR 共用一个 head branch——删掉会让另一个 PR 的 head 消失），才可删除。
  **不得**删除未确认合入的远端分支。

## 9. Tag 与版本

普通 Goal 不创建 tag。发布 tag 由用户明确授权后创建，分两档：

**预发布（rc）**：发布候选通过发布清单后，在 `develop` 的收口 commit 上打 tag，并在
`progress.md` 补一条对应 `0.1.0-rc.N` 的发布记录（`N` 为下一个序号）：

```powershell
git switch develop
git pull --ff-only origin develop
git tag -a 0.1.0-rc.N -m "0.1.0-rc.N"
git push origin 0.1.0-rc.N
```

**正式版本**：`develop` 合入 `main` 之后再打，tag 必须指向已合并的 `main` commit：

```powershell
git switch main
git pull --ff-only origin main
git tag -a 0.1.0 -m "0.1.0"
git push origin 0.1.0
```

`main` 是发布快照、平时落后 `develop`（见 [`decisions/0022-semantic-cache-evaluation.md`](decisions/0022-semantic-cache-evaluation.md) §11.4 与 [`progress.md`](progress.md) 的“§11.4 新教训”条目），因此 rc tag 只指向 `develop` 的收口范围，不代表 `main` 上已有对应代码；「必须指向已合并的 `main` commit」只约束正式版本 tag。版本号遵循 SemVer，tag 名不带 `v` 前缀（与既有 `0.1.0-rc.N` 一致）。禁止移动或覆盖已发布 tag。

## 10. 进度文档（`docs/progress.md`）的更新时机

**`docs/progress.md` 不是每个 PR 的逐条承诺项**，只在明确的**收口 / 回流批次**统一更新。

这条规矩来自一次实际失效：曾有 PR 正文写着「`docs/progress.md` records exact results（随本轮收口文档并入）」，
而 `progress.md` 里**根本没有对应条目** —— 且 **CI 绿、review 也没人发现**，因为模板里那一勾**没有任何校验**。
完整的失效链是：

```text
PR 描述：会同步 progress.md  →  实际：没同步  →  CI：绿色  →  Review：没人发现
```

与其加一个「解析自然语言正文」的脆检查（本仓已有「检查器误报 → 不再被信任 → 门禁形同虚设」的前车之鉴），
不如**从源头去掉这个虚假承诺**。

- **PR 模板**不再要求勾「`docs/progress.md` records exact results」，改为声明
  **「本 PR 是否属于 progress 回流批次：Yes / No」**（见 [`.github/PULL_REQUEST_TEMPLATE.md`](../.github/PULL_REQUEST_TEMPLATE.md)）。
- **只有标了 `Yes` 的 PR**，才要求这一批把结果写进 `progress.md`；标 `No` 的 PR **不因「进度文档未更新」被拦下**
  （§1 的那条已相应收紧）。
- 收口批次把**当批合并的 PR** 结果写进 `progress.md`。
- **历史日志不改写**：新状态以**追加入口**的方式覆盖旧口径（例如 `progress.md` 里旧的交接结论），
  不去改过去的记录。

