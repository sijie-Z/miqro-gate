# 下一会话执行计划（NEXT SESSION PLAN）

> 给新 Claude Code 会话的启动指令与分阶段计划。新会话第一步：通读本文件 + `CLAUDE.md` + `docs/claude-code-execution-contract.md` + `docs/progress.md`（顶部 Current State 与"会话交接点"段）+ `docs/git-workflow.md`。
>
> 创建时间：2026-09-03。创建人：上一会话（用户授权连续执行；用户明确"token 不敏感、不太在意工作量、会让我一直做"，故计划按可连续执行的分阶段 Goal 组织，每阶段独立验收、独立 checkpoint、可随时停）。

## 0. 用户交给下一会话的总体命令（原话要点）

1. UI 太差：以 **PostHog（github.com/PostHog/posthog，MIT）为视觉母版（约 95%）**，**Vben Admin 5（github.com/vben-group/vue-vben-admin）做布局/组件组织参考（约 5%）**，认真参考、照着改。最终目标是用户可感知的"有品味、像大厂成品"（视觉评审目标 ≥9/10，评审方法见 §6）。
2. 工程路线已与用户对齐：**不再 TDesign 换肤**（天花板 8.5 分）；走 **B 路线：在保留 Vue3/路由/store/API 层不动的前提下，自绘设计系统组件库**（shadcn-vue 风格：设计 token 驱动、源码入库、可自由定制），分阶段把 30+ 页面重制。每阶段 3-5 页 + 视觉评审，验收后再放量。
3. 功能 backlog 照常推进（推荐顺序见 §5），MCP/Forge 公司集成仍暂缓。
4. 用户"会让我一直做"：每阶段必须写 checkpoint（progress.md + 本文件状态段），下轮从 checkpoint 继续。

## 1. 第一优先：收尾当前分支 PR #131（新会话第一件事）

- 仓库根 `git fetch origin develop`；`gh pr checks 131`（分支 goal/self-registration，含：F-REG 注册全栈、登录页四轮、onboarding、confirm 弹窗/usage 路由修复、中文化、PostHog 表格语言首版、aesthetic 规则现代化）。
- CI 全绿 → `gh pr merge 131 --squash --delete-branch` → `git switch develop && git pull --ff-only` → 删本地旧分支（`git branch -d goal/self-registration`）。
- 若 CI 红：修复后重推；若队列卡住（GitHub Actions 偶发）：`gh run cancel <id>` → `gh run rerun <id>`。
- 合并后：本地跑一遍试跑环境验证（见 §7）确认 develop 级可用，再进入 §2。

## 2. UI 专项（主任务，多阶段；本文件状态段每阶段更新）

### 阶段 U0 — 设计语言与试点（先做，验收通过才继续 U1）
产出：
1. 抽取 PostHog 设计语言 → 写入 `frontend/src/styles/` 新体系：色板（近白 canvas、极细边框、单一品牌蓝、语义色）、字号/字距阶、间距 4 基、圆角（控件 6、卡片 8、弹窗 12）、阴影禁令（仅 popper/modal/焦点环）、表格规则（细线、无表头填充、行 hover、数字 tabular-nums）。
2. 建 `frontend/src/ui/` 自绘组件首批（源码入库，设计 token 驱动，参考 shadcn-vue 结构，**不要引入运行时 UI 框架依赖**，可引 radix-vue 原语或纯自绘——取舍记录）：Button / Input / Table（含排序分页空态）/ Dialog / Tag/Status / Sidebar 布局 / EmptyState / Toast。
3. 试点重制 4 页：Login（已有较好底子，纳入新体系）、Keys（列表+创建流）、Usage（汇总+明细+我的配额）、AdminUsers（表格+操作列）。试点页**同时保留 TDesign 版对照**（路由/环境开关或独立路由前缀 /app-new/* 先并行，避免半途不可用）。
4. 视觉验收：每页截图（Playwright 1440x900）→ DeepSeek 视觉模型评分（key 见 §7）→ **目标 ≥9/10**（对照标准：PostHog 后台观感）→ 把评分与截图存 `miqro-local/ui-reviews/`（不入库，路径记录到本文件）。
5. 前端既有测试适配：新组件/新页面 spec 覆盖（沿用 vitest + Playwright 基线）；TDesign 试点的旧测试保留到切换完成。
6. 用户验收：把 preview URL 交给用户点验；用户点头才进 U1。**如果用户否定方向**：停下来问，不改设计母版。

### 阶段 U1 — 用户面全量（试点批准后）
总览 / 我的 Key / 用量 / 资料 / 技能库 / 模型申请 / Profile → 全部切到新组件体系；删除试点并行开关；AppShell 布局落地 PostHog 侧栏+头部（参考 Vben 布局但视觉 PostHog）；路由守卫/权限逻辑零改动（只动表现）。

### 阶段 U2 — 管理面全量（用户面批准后）
组织组（用户/团队/项目/授权/审批中心）、平台组（供应商/订阅/定价/上游凭证/用量/成本/配额/缓存收益/导出/删除/API 消费者/技能库管理）、运营组（Webhook/告警/审计/智能体/服务/配置/MCP 服务/技能库）——每组 3-5 页一批，逐批视觉验收。

### 阶段 U3 — 收尾
视觉基线全量刷新、无 TDesign 残留（删除依赖或降到仅全局 alert 类）、旧 aesthetic 审计规则迁移到新体系、e2e 全量、性能（chunk 体积对比记录）。

## 3. UI 专项的硬约束（不可破）

- 不动业务逻辑/API/路由 name/后端；表现层替换。
- 遵循既有审美审计精神：无渐变（品牌 chip 与既定 donut 除外）、无紫色、常规容器 radius≤8（弹窗/状态 pill 例外）、阴影仅 popper/dropdown/modal/焦点环。
- 全部文案中文、与动作一致（按钮"删除"→ 提示"已删除"）；空态=行动邀请。
- 可访问性：focus-visible 可见、对比度达标、移动端可用。
- 每阶段都要 vitest+typecheck+build+e2e 绿才 checkpoint。
- 禁止引入禁许可依赖（宽松许可 only）；新依赖版本固定并过 check-sbom。

## 4. 视觉评审方法（贯穿 U0-U3）

- 本会话内图片不可直接看：用 Playwright 截图 → base64 → DeepSeek `deepseek-v4-flash-vision-exp`（key 在 miqro-local/.deepseek-key.tmp）评 1-10 并给 3 条 nit（写好的调用脚本模板可存 miqro-local/vision_review.py）。
- 对照标准：PostHog/Vercel 级（9-10）；当前基线 8/10（TDesign 覆写后）；目标 ≥9。
- 评分与截图每阶段存 miqro-local/ui-reviews/<stage>-<page>.png + score.txt；把结论行记录到本文件"视觉评审记录"段。

## 5. 功能 backlog（UI 之外，穿插或 UI 后）

按优先级（无外部依赖在前）：
1. F11 MCP 路由规则（配置面先行：default 兜底不可删改 + 自定义优先级、Path/Host/Method/Header 匹配纯函数 + 冲突校验；表 V28 + 管理 API + 前端页面）。
2. 管理员「加入项目」快捷入口（用户列表对无项目用户一行直达项目成员抽屉；配合 F-REG 新用户引导闭环）。
3. 注册用户闭环演示脚本（miqro-local，不提交）：demo2_user → 加 LIVE 项目 → 建 Key → 真推理 → usage/quota 核对（DeepSeek key 在本地）。
4. 前端 OpenAPI codegen 迁移（发布前候选：删手写 api/types，改为 openapi-typescript 从 docs/openapi/openapi-3.1.json 生成 + CI 校验无漂移）。
5. 供应商真实凭证矩阵（逐个 VERIFIED；本地仅 DeepSeek key）。
6. B 组 F12-F15 / C 组 F19-F21 维持 backlog 状态；BLOCKED 组等 leader；MCP/Forge 公司集成**暂缓（保密，仓库内不写链接/细节）**。

## 6. 每阶段完成与继续规则

- 每个阶段结束时：更新 `docs/progress.md`（Current State 指到最新）+ 本文件状态段（把完成的勾掉、写下一步）；跑全量验证；若分支有业务代码按 gitflow 开 goal/ 分支→push→PR（合并模式沿用：CI 全绿 → squash merge → 删分支；用户已授权该模式）。
- **用户说"会让我一直做"**：完成一个阶段后，如果还有预算且工作区干净，继续下一阶段；每 2-3 个阶段或上下文到 ~60% 就写 checkpoint 结束一轮，让用户开新 session 说"继续执行 docs/NEXT_SESSION_PLAN.md 的下一个未完成阶段"。
- 遇到需要用户拍板（设计方向被否、合并授权缺失、新 ADR 级决策）：停下写成最小问题，不自行改方向。

## 7. 本地试跑环境与密钥（非仓库内容）

- 服务：control-plane http://localhost:8080、gateway http://localhost:8081、前端 dev http://localhost:5173（若不在跑：参考 miqro-local/restart.bat 环境变量；启动顺序=先 `./mvnw.cmd -f backend/pom.xml install -Dmaven.test.skip=true` 再 `-pl <app> spring-boot:run`；gateway 额外 GATEWAY_DB_PASSWORD/crypto 文件 env）。
- 账号：root / DrillPass2026!（管理员）；demo2_user / DemoPass2026!（普通用户，已注册）。
- DeepSeek key：miqro-local/.deepseek-key.tmp（已建议用户轮换；未经用户新授权勿外发）。
- PostgreSQL：docker 容器 miqrokey-postgres（库 miqrokey，用户 miqrokey/change-me-in-production）。
- Windows 坑：中文 curl 体用 UTF-8 文件；python 路径用 D:/ 盘符；命令前显式 cd 仓库根；maven 前 export JAVA_HOME="D:\programming\jdk-21.0.12.1+1"。

## 状态段（每阶段更新）

- [x] U0 前置：PR #131 收尾（2026-09-03 已完成：CI 曾红一次——KeysView onboarding spec 缺 listVirtualKeys 默认 stub，修复重推后全绿 → squash merge → develop 80dddad，见 progress.md 顶部）
- [~] U0 设计语言抽取 + ui/ 组件首批 + 试点 4 页（Login/Keys/Usage/AdminUsers）+ 视觉评审 —— **代码与测试完成，等待用户点验（见文末视觉评审记录；评分中位 ~7-7.5 未达 9，因评审器噪声大已停止逐轮追分；分支 goal/ui-posthog-u0 @ 803f552 已 push，PR 待开/已开）**
- [x] U1 用户面全量切换 —— **2026-09-03 完成并入 develop**（PR #133 squash @ bc34874；/login 与 /app 六用户路由正式指向 v2；试点前缀退役；-4294 行清理）
- [x] U2 管理面三组逐批切换 —— **全部并入 develop**（组织 #134 / 平台 #135 / 运营 #136：A 批导出·用量删除·审计·全局配置·API 消费者；B 批技能库管理·智能体·服务管理·Webhook·告警规则·MCP 服务）**C 步视觉统一轮并入 develop（PR #137 squash @ 55c1e13；两轮评审 16 页，采纳：壳层激活态强化/顶栏去品牌重复/e2e 真实密度 fixtures；raw 存档 miqro-local/ui-reviews/ops-c/；下一阶段=U3 收尾，待用户继续指令）**
- [ ] U2 管理面三组逐批切换
- [x] U3 UI 专项收尾 —— **2026-09-04 并入 develop**（PR #139 squash；tdesign-vue-next 1.18 MB chunk 移除、settings 页 v2 化（唯一遗留路由页退役）、e2e 审美审计扩到 --ui/.ui- 层 + ui-layer 防回归守卫、chunk 对比记档于 progress.md；残余=零 legacy 路由页，--miqrokey CSS 审计兼容保留，tdesign-icons 图标集保留）
- [x] 功能 1：F11 MCP 路由规则 —— **2026-09-04 配置面完成并入 develop**（V28 mcp_route_rule + McpRouteRules 纯函数（RE2/冲突等价面）+ §5.23 管理 API + MCP 页路由规则抽屉；default 随服务自动生成/存量回填；数据面匹配待 F01 接线）
- [~] 功能 2：管理员快捷加入项目 —— **2026-09-04 实现完成待合并**（后端用户所属项目查询 + 用户页「项目成员」抽屉（移除/加入 ACTIVE 项目））
- [ ] 功能 3：注册用户闭环演示
- [ ] 功能 4：OpenAPI codegen 迁移（发布前）

## 视觉评审记录（U0，2026-09-03；raw 存档 miqro-local/ui-reviews/，见 U0-VISION-SCORES.md）

- 方法：Playwright mock 截图（1440x900，fullPage）→ deepseek-v4-flash-vision-exp 评审（SCORE + 中文问题 + NIT）。
- 历程分（login/keys/usage/users）：R1 6.5/7.8/6.8/— → R2 6.5/6.5/7.5/6.8 → R3 7.6/—/5.7/6.5 → R4 7.8/—/7.1/8.4 → R5 8.2/7.5/5.6/7.5 → R6 6.8/7.0/6.8/6.5 → 终审双票中位 ≈7.2/7.5/6.2/7.2。
- 结论：目标 ≥9 未达成；评审器噪声大（相邻轮 ±1.5、同一元素意见自相矛盾、静态截图误判 hover/focus 缺失），逐轮追分进入破坏性循环，已停止并以多轮中位数为准；跨轮一致且可落地的意见全部已实施（语义色分离、摘要彩色分段、缓存列纯文本、动态列、紧凑日期、分段控件、顶栏用户区、激活色条、真实数据密度 fixture 等）。
- 拒绝项（违反审美审计/母版纪律）：渐变条形、卡片投影、紫色调。
- 残余差距：侧栏试点期仅 2-3 项（U1 填满）；品牌 Logo 待用户拍板资产；usage fullPage 纵向观感。
- 下一步：用户以真实浏览器点验（preview URL：http://localhost:5173/login-new、/app-new/keys、/app-new/usage、/app-new/users；旧版对照 /login、/app/keys 等）。**用户点头 → U1；否定 → 停下问方向。**
