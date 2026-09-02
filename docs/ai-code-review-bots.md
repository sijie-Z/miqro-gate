# AI 代码审查机器人安装指南

> 更新：2026-09-01。目标：用免费机器人对每个 PR 做尽可能多的自动代码审查。
> 除 CodeRabbit 已配置外，其余需在 GitHub Marketplace 安装 App 或在对应平台生成 token——**必须由仓库所有者（sijie-Z）在 GitHub 账号下操作**，Claude Code 无法代替授权。

## 已配置（开箱即用）

| 机器人 | 状态 | 说明 |
|---|---|---|
| **CodeRabbit** | ✅ 已接入 | `.coderabbit.yaml`（ASSERTIVE profile，base_branches 含 main/develop/goal/*/feat/*/fix/*）。OSS 仓库首次 review 需在 [CodeRabbit 仪表盘](https://coderabbit.ai) 批准一次，或对 PR 评论 `@coderabbitai review` 触发单次审查 |
| **CodeQL** | ✅ 已有 | GitHub 内置（java-kotlin + javascript-typescript），push/PR 全触发 |
| **Trivy / Scorecard / Dependabot** | ✅ 已有 | 安全扫描、供应链评分、依赖升级 PR |
| **SonarCloud** | 🔧 workflow 已备好 | 见下，需两个 secret |

## 推荐安装（免费，可叠加）

### 1. SonarCloud（深度静态分析 + 安全门禁）—— 推荐先装

1. 登录 [sonarcloud.io](https://sonarcloud.io)（GitHub 账号 OAuth）。
2. Import 组织/仓库 `sijie-Z/miqro-gate` → 创建项目（project key 默认 `sijie-Z_miqro-gate`）。
3. 在 SonarCloud 项目 → Administration → Analysis Method → 复制 **SONAR_TOKEN**。
4. GitHub 仓库 → Settings → Secrets and variables → Actions → 新建两个 secret：
   - `SONAR_TOKEN` = 上面的 token
   - `GITHUB_TOKEN` 不需要手动建（`secrets.GITHUB_TOKEN` 内置）
5. 提交 `.github/workflows/sonarqube.yml`（已写好）→ 下一次 push/PR 自动分析，PR 上显示 Quality Gate 与行内评论。

> 若 organization key 不是 `sijie-z`，改 workflow 里 `-Dsonar.organization` 与 `-Dsonar.projectKey`。

### 2. Qodo（原 Codium）PR Agent —— AI 第二视角

1. 打开 [Qodo PR Agent](https://github.com/apps/qodo-ai-pr-agent)（GitHub Marketplace）。
2. Install → 选择 `sijie-Z/miqro-gate`。
3. 每个 PR 自动生成：PR 描述、变更总结、代码审查、质疑审查（focus/possible issues）。
4. 可与 CodeRabbit 并存（两者视角不同：CodeRabbit 逐文件注释，Qodo 侧重总结与质疑）。

### 3. Ellipsis（AI 审查 + 自动修复）—— 可选

1. [Ellipsis](https://github.com/apps/ellipsis-ai) → Install → 选仓库。
2. 免费计划每月有额度；可自动修简单问题并提交建议。

### 4. Bito AI Code Review —— 可选

1. [Bito AI Code Review](https://github.com/apps/bito-ai-code-review) → Install。
2. 免费计划；审查 + 安全扫描（找泄露的 key/token）。

## 建议的最终组合

```
CodeRabbit（AI 语义审查）        ← 已配置
Qodo PR Agent（AI 第二视角）     ← Marketplace 安装
SonarCloud（静态分析 + 质量门禁） ← workflow 已备好，等你提供 token
CodeQL + Trivy + Scorecard       ← 已有
```

安装完成任一机器人后，在 PR 上验证：新 PR 或对已有 PR 重新 push 会触发对应检查出现在 checks 列表。
