# 下一会话执行计划（NEXT SESSION PLAN）

> 给新 Claude Code 会话的启动指令。新会话第一步：通读 `CLAUDE.md` +
> `docs/claude-code-execution-contract.md` + `docs/progress.md`（Current State 与最近的
> "会话交接点"段）+ `docs/git-workflow.md` + 本文件。
>
> ⚠️ **当前状态以 `docs/progress.md` 的 Current State 为准**（本文件只给"怎么开工"，
> 不承载版本/迁移号/待办快照——那些每轮都在变，写在这里必然过期）。

## 0. 先读哪里

| 想知道 | 去看 |
|---|---|
| **现在做到哪了、下一 Goal、已知阻塞** | `docs/progress.md` Current State + 最近的"会话交接点"段 |
| 文档职责与事实来源优先级 | `docs/document-map.md` |
| 未做/候选功能总登记（F01–F67 + D01–D04） | `docs/feature-backlog.md` |
| 分支、commit、push、发布权限 | `docs/git-workflow.md` |
| **从零理解这个系统**（架构 / 接口 / 运维） | `docs/handover/`（交接文档集，见该目录 README） |

**不要**从本文件里找版本号或迁移号——见下方 §1 最后一条。

## 1. 铁律（每次开工必守）

- Git：develop 只收 squash PR；一个 PR 一个 issue；**issue 与 PR 一律用仓库模板逐节详细填**。
- 验证：后端 `bash miqro-local/mvnw21.sh -f backend/pom.xml -Pintegration verify`（本机唯一可靠入口，
  见 memory jdk-location；run_in_background 并核对日志内真实 exit）；前端 typecheck（清缓存脚本）+
  test + 改动文件 eslint（**勿整树 lint**，见 memory frontend-lint-tree-drift）；CI 全绿才合并
  （`gh pr checks` 零非 pass/skip）。
- 网络黑洞（github 偶发）：push/fetch 命令级重试循环（30s×多次）；Docker Desktop 掉线会让
  testcontainers verify 假红——先 `docker info` 探活，必要时启动 Docker Desktop 并等 daemon。
- **红线**：以 `CLAUDE.md` §2「不可改变的产品决策」清单为准（该清单已含 ADR-0018 与 ADR-0020 的修订）。
  改任何一条产品决策需**新开 ADR + owner 明确同意**。
- 前端类型：schema 权威（`types/generated-api.ts` hub）；新增后端端点/字段须跑 `npm run gen:types`
  并提交；后端契约变更须再生 `docs/openapi/openapi-3.1.json` 基线（OpenApiSpecIntegrationTest
  产物拷贝）并跑 breaking-check。
- **迁移号**：Flyway 已发布迁移**永不修改、只追加**。下一个号 = 现最高号 + 1，
  用 `ls backend/persistence-postgres/src/main/resources/db/migration/ | sort -V | tail -1` 现查，
  **不要相信任何文档里写死的号**。审计事件词表/目标类型进 api-contract §5.0；新配置行进 configuration-reference。

## 2. 环境要点

- CHANGELOG 等 md 是 CRLF：改文档用 Edit 工具或 python `newline=''` 写 CRLF；
  python 脚本含反引号/模板串务必**写文件执行**，勿用 bash heredoc（引号会炸）。
- bash 工具偶发环境级故障（exit 107/0 输出）——重试即可，非代码问题。
- vitest 偶发单测 flake（App.spec 并发时）：复跑即绿属已知，勿掩盖。

## 3. 会话收尾义务

更新 `docs/progress.md`（Current State + 交接点段）与 CHANGELOG 对应条目；本地分支清理；
工作树干净；给 owner 一屏中文汇报（做到哪/外部等什么/下一步默认）。
