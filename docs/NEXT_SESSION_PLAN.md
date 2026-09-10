# 下一会话执行计划（NEXT SESSION PLAN）

> 给新 Claude Code 会话的启动指令。新会话第一步：通读 `CLAUDE.md` +
> `docs/claude-code-execution-contract.md` + `docs/progress.md`（Current State 与最近的
> "会话交接点"段）+ `docs/git-workflow.md` + 本文件。
>
> **2026-09-10 重写（rc.10 后）**：leader 2026-09-09 方向词「权鉴 / 服务 / 接口」的全部
> **无外部依赖可实现项已交付**（见 §0 清单）。本文件给"现在在哪 + 接下来做啥 + 怎么做"。

## 0. 现状（2026-09-10，develop 最新；以 progress.md Current State 为准）

- 版本锚点：**0.1.0-rc.10**（rc.9 覆盖 #314/#315/#316；rc.10 覆盖 #320/#322/#324；#326 已合待入 rc.11）。
- 盘点交付（rc.9 → 现在）：
  - **#314** 操作记录查询补全 + 合规 CSV 导出（双端点筛选/5 万行截断头）；
  - **#315** 服务族审计（六族 21 事件）→ **#324** 审计二批（告警/Webhook/预算/配置/模型目录 +
    `AuditContext` 机器面归属 via 标记）；
  - **#316** 消费者能力作用域（billing:read/mcp:call 双面 fail-closed）→ **#322** 消费者到期
    （双面静默 401 + CONSUMER_KEY_EXPIRING 提醒）；
  - **#320** MCP 上游后端鉴权注入（Visitor/API Key、写后不可读、fail-closed）；
  - **#326** 服务注册表运行时治理（enable 对称 + ServiceHealthChecker）；
  - #328 运维文档补齐轮（runbook §3c/3d/3e + 配置欠账行 + 本文件）。
- 迁移至 **V40**；审计覆盖面已完整（管理写操作无已知零审计族）。
- 外部等待（保持原口径）：#211 真机凭证、#245 告警接线裁决（leader）、F32/F33 平台接口
  （等平台 OAuth client）、F19 真实账单样本、F29 服务→网关数据面形态。

## 1. 铁律（每次开工必守）

- Git：develop 只收 squash PR；一个 PR 一个 issue；**issue 与 PR 一律用仓库模板逐节详细填**。
- 验证：后端 `bash miqro-local/mvnw21.sh -f backend/pom.xml -Pintegration verify`（本机唯一可靠入口，
  见 memory jdk-location；run_in_background 并核对日志内真实 exit）；前端 typecheck（清缓存脚本）+
  test + 改动文件 eslint（**勿整树 lint**，见 memory frontend-lint-tree-drift）；CI 全绿才合并
  （`gh pr checks` 零非 pass/skip）。
- 网络黑洞（github 偶发）：push/fetch 命令级重试循环（30s×多次）；Docker Desktop 掉线会让
  testcontainers verify 假红——先 `docker info` 探活，必要时启动
  `D:\programming\Docker_4.78.0\Docker Desktop.exe` 并等 daemon。
- 红线不变：不读正文/1:1 绑定/只告警不阻断/单租户私有化；改产品决策需 ADR+owner 同意。
- 前端类型：schema 权威（`types/generated-api.ts` hub）；新增后端端点/字段须跑 `npm run gen:types`
  并提交；后端契约变更须再生 `docs/openapi/openapi-3.1.json` 基线（OpenApiSpecIntegrationTest
  产物拷贝）并跑 breaking-check。
- 迁移号：下一个是 **V41**；审计事件词表/目标类型进 api-contract §5.0；新配置行进 configuration-reference。

## 2. 接下来做啥（按序，外部输入随时插队）

1. **rc.11 攒批**：#326（服务运行时）已合未锚；再攒 1-2 项后打 rc.11（中文 Release Notes）。
2. 无外部依赖候选（按价值排序，均需先开 issue）：
   - F23 导出「可对账等级」标记（usage-accounting §11 承诺；可从 provider_request_id 有无派生
     原始/可对账等级，避开 F19 四态模型的部分先行）；
   - 告警规则/Webhook 机器面读端在审计页的展示增强（数据已具备，读面 UX 小项）；
   - MCP 后端密钥 reEncrypt 批量轮换通道（低频需求，模型面已有 reEncrypt 可复用）。
3. 外部一到即做：平台 OAuth client→OIDC 真机 E2E；#211 凭证→F53 冒烟矩阵；账单样本→F19
   解析器+端点+报告；leader 形态→F29 服务数据面 / #245 告警接线 / F32/F33。

## 3. 环境要点

- 仓库在 `D:\Desktop\My_projects\MiQro-key\miqro-key-gateway`；cwd 常漂移，命令前确认目录。
- CHANGELOG 等 md 是 CRLF：改文档用 Edit 工具或 python `newline=''` 写 CRLF；
  python 脚本含反引号/模板串务必**写文件执行**，勿用 bash heredoc（引号会炸）。
- bash 工具偶发环境级故障（exit 107/0 输出）——重试即可，非代码问题。
- vitest 偶发单测 flake（App.spec 并发时）：复跑即绿属已知，勿掩盖。
- 视觉评审/UI token 决策：#244 遗留，需 owner 在场。

## 4. 会话收尾义务

更新 `docs/progress.md`（Current State + 交接点段）与 CHANGELOG 对应条目；本地分支清理；
工作树干净；给 owner 一屏中文汇报（做到哪/外部等什么/下一步默认）。
