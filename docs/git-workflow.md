# Git、Commit 与 Push 工作流

本文是 Claude Code 和人工开发共同遵守的发布规则。目标 GitHub 远端为：

```text
https://github.com/lichman0405/miqro-key-gateway.git
```

仓库所有者已删除原拼写错误的临时仓库，并新建 `miqro-key-gateway`。截至 2026-07-17，目标 URL 已只读验证可访问且为空；G0.1 在首次 push 前仍应运行 `git ls-remote`，若届时出现未知 ref，停止并报告，不覆盖远端历史。

## 1. 权限边界

Claude Code 可以在当前 Goal 内：

- 初始化当前项目的 Git 仓库、设置 `origin`、创建 Goal 分支。
- 查看 status/diff/log，暂存本 Goal 文件，创建普通 commit。
- 当前 Goal 验收通过后，把当前 Goal 分支正常 push 到 `origin`。

Claude Code 不可以：

- `push --force`、`push --force-with-lease` 或删除远端分支。
- 直接向 `main` 推送业务实现；首次文档基线例外见下文。
- `reset --hard`、`clean -f/-fd`、`checkout .`、`restore .`、`branch -D`。
- 修改、丢弃或混入不属于当前 Goal 的用户改动。
- 在测试失败、Secret 扫描失败或进度文档未更新时提交/推送。
- 自动 merge Pull Request、创建 tag 或发布 Release，除非当前 Goal 明确要求。

推送只代表备份和发起审查，不代表验收或合并。

## 2. 空仓库首次建立

G0.1 开始时，先保存当前文档设计基线。PowerShell：

```powershell
git init -b main
git remote add origin https://github.com/lichman0405/miqro-key-gateway.git
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
git remote set-url origin https://github.com/lichman0405/miqro-key-gateway.git
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

CI 建立后，`main` 建议启用分支保护：禁止 force push 和删除，要求 PR、required status checks、所有对话解决。当前只有一个开发者时可以保留管理员紧急 bypass，但每次 bypass 都要有原因和后补 PR；交付客户前收紧权限。

## 8. Merge 与同步

由仓库所有者或被授权维护者在 GitHub 合并。推荐 squash merge，使一个 Goal 在 `main` 上形成一个清晰 commit。合并后本地同步：

```powershell
git switch main
git pull --ff-only origin main
git branch -d goal/g0.1-repository-bootstrap
```

只删除已经确认合并的本地分支，使用 `-d`，不用 `-D`。远端分支由 GitHub 的“合并后自动删除”设置处理，不由 Agent 命令删除。

## 9. Tag 与版本

普通 Goal 不创建 tag。G6.5 发布候选通过发布清单后，由用户明确授权：

```powershell
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

Tag 必须指向已合并的 `main` commit，版本遵循 SemVer。禁止移动或覆盖已发布 tag。
