# 下一会话执行计划（NEXT SESSION PLAN）

> 给新 Claude Code 会话的启动指令。新会话第一步：通读 `CLAUDE.md` +
> `docs/claude-code-execution-contract.md` + `docs/progress.md`（Current State 与最近的
> "会话交接点"段）+ `docs/git-workflow.md` + 本文件。
>
> **2026-09-09 重写**：此前按阶段 UI/功能计划已由自主轮完成大部并收口
> （F60 全链、codegen 迁移线、typecheck 真实化、spec 检查、F19 契约+引擎、rc.1–rc.7）。
> 本文件不再复制细节，只给"现在在哪 + 接下来做啥 + 怎么做"的指针。

## 0. 现状（2026-09-09，develop 最新；以 progress.md Current State 为准）

已交付且收口：网关数据面/MCP/治理闭环/F60 开放管理 API（读→写→委托建钥→scope→到期事件）/
平台 OIDC 代码(等 client)/合规留痕/codegen 25 类型收口/typecheck 真+确定性/spec 纳入检查/
F19 契约稿+四级匹配引擎。外部等待：#211 真实凭证、#245 告警接线裁决(leader)、F32/F33 平台
接口、平台 OAuth client、F19 真实账单样本。

## 1. 铁律（每次开工必守）

- Git：develop 只收 squash PR；一个 PR 一个 issue；**issue 与 PR 一律用仓库模板逐节详细填**
  （`.github/ISSUE_TEMPLATE/` 与 `PULL_REQUEST_TEMPLATE.md`；owner 2026-09-09 明确）。
- 验证：后端 `./mvnw.cmd -f backend/pom.xml -Pintegration verify`（JAVA_HOME=
  `D:\programming\jdk-21.0.12.1+1`，PATH 加其 bin）；前端 `npm --prefix frontend run typecheck`
  （**先清 tsbuildinfo 的确定性脚本**，勿绕过）与 `npm --prefix frontend run test`；CI 全绿才合并
  （`gh pr checks` 零非 pass/skip）。
- 网络黑洞(github 偶发)：命令级重试循环；CI 判红以 job 日志为准，不以本地缓存为准。
- 红线不变：不读正文/1:1 绑定/只告警不阻断/单租户私有化；改产品决策需 ADR+owner 同意。
- 前端类型：schema 权威（`types/generated-api.ts` hub）；新增后端端点/字段须同步
  `npm run gen:types` 并提交（drift 检查会抓）。

## 2. 接下来做啥（按序，外部输入随时插队）

1. 无外部依赖的 backlog 收尾/运维文档/契约一致性小项（每项独立 issue+PR）。
2. 攒批后打 rc.x（中文 Release Notes，含阶段锚点）——owner 靠 rc 版本号看进度。
3. 外部一到即做：OAuth client→OIDC 真机 E2E；#211 凭证→F53 冒烟矩阵；账单样本→F19
   解析器+端点+报告；leader 接口→F32/F33/#245。
4. 被否/待拍板项（设计稿已就绪，owner 拍板后转实现）：无新拍板积压；F07 告警接线=跨进程
   管道+租户口径，等 #245。

## 3. 环境要点

- 仓库在 `D:\Desktop\My_projects\MiQro-key\miqro-key-gateway`；cwd 常漂移，命令前确认目录。
- CHANGELOG 是 CRLF：node 脚本 `\n` 匹配会失败，改文档用 Edit 工具。
- vitest 偶发单测 flake：复跑即绿属已知，勿掩盖、勿当修复结果。
- 视觉评审/UI token 决策：#244 遗留，需 owner 在场。

## 4. 会话收尾义务

更新 `docs/progress.md`（Current State + 交接点段）与 CHANGELOG 对应条目；本地分支清理；
工作树干净；给 owner 一屏中文汇报（做到哪/外部等什么/下一步默认）。
