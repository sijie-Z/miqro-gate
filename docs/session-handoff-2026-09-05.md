# 会话交接总纲（2026-09-05，读我即可接手）

> 给下一个 Claude Code 会话的**极详细**交接。新会话第一步：通读本文件 +
> `CLAUDE.md` + `docs/claude-code-execution-contract.md` + `docs/progress.md`（顶部
> Current State）+ `docs/NEXT_SESSION_PLAN.md`（状态段）+ `docs/git-workflow.md`；
> 仓库根 `git fetch origin` 并确认 develop 已同步；再执行下方「第一优先」。
>
> 事实基线：分支 `develop` @ `b8b354d`（#154 F01 v1 已并入），工作区干净。
> 远端 `https://github.com/sijie-Z/miqro-gate.git`（public/MIT，分支名
> `goal/<kebab>` 或 `docs/<kebab>`）。本文件不入 NEXT_SESSION_PLAN 主流程之外，
> 作为 2026-09-04→09-05 交接的补充详单。

---

## 0. 环境与凭据（本地，不入库）

- 试跑服务（**均运行最新代码**）：control-plane `http://localhost:8080`、gateway
  `http://localhost:8081`、前端 dev `5173`；重启方法：先 kill 占用 8080/8081 的
  java（`netstat -ano | grep LISTENING | grep -E ":808[01]"` 取 PID →
  `taskkill //PID <pid> //F`），再按 `miqro-local/restart.bat` 的环境变量手工前台起
  （注意 restart.bat 的 `start` 窗口方式可能静默失败；手动验证方式见下）。
- 账号：`root / DrillPass2026!`（管理员）、`demo2_user / DemoPass2026!`（普通用户，
  已加入 LIVE 项目）。Postgres 容器 `miqrokey-postgres`（库 miqrokey /
  miqrokey / change-me-in-production）。DeepSeek key：
  `miqro-local/.deepseek-key.tmp`（仅供本地视觉评审/演示，勿外发）。
- 网络教训（重要，反复出现）：
  1. `github.com:443` 会**周期性黑洞**（数十分钟级），但 `api.github.com` 与
     `ssh.github.com:443` 通常可达；`git push/pull` 失败就等 1-10 分钟重试（后台
     循环 `until git push; do sleep 45; done` 可靠）。
  2. 本地代理 `127.0.0.1:7897` 状态不稳（会失效导致 schannel 报错），不要依赖；
     npm 需绕代理安装时用 `npm install --proxy=null --https-proxy=null`。
  3. **GitHub Actions 的 `pull_request` 事件曾在 develop-base PR 上静默失效约
     4 小时**（push/CodeQL 正常；平台级问题，status 页 operational）。永久绕行已
     落地：ci.yml 支持 `workflow_dispatch`，验证流程 = 推送分支后
     `gh workflow run CI --ref <branch>` → 轮询 run → 9 jobs 全绿 → 建 PR →
     `gh pr merge --squash --delete-branch`。后续 PR 一律走此通道，不再等 PR 自动
     CI（若自动 CI 恢复也可直接用，但 dispatch 更稳）。
  4. 会话 cwd 极易漂移：每条命令显式 `cd /d/Desktop/My_projects/MiQro-key/miqro-key-gateway`
     （python 路径用 `D:/` 盘符；maven 前 `export JAVA_HOME="D:\programming\jdk-21.0.12.1+1"`
     并加 PATH）。
  5. `npm run lint --prefix frontend`（eslint --fix）会整批改写 EOL：lint 后
     `git status` 大量 M 属噪声，`git diff --numstat` 为 0/0 即纯 EOL；
     用 `git restore <列表>` 清掉（排除真实改动文件）。
  6. 提交前检查分支：本会话多次把 commit 误落 develop——commit 前先
     `git branch --show-current`；修正方法 `git switch -c <goal> && git switch develop
     && git branch -f develop <原sha>`（先切走再 -f）。

---

## 1. 第一优先（新会话 30 分钟内）

1. `git fetch origin && git pull --ff-only origin develop`（网络黑洞则重试）。
2. 通读 `docs/progress.md` Current State 与 `docs/NEXT_SESSION_PLAN.md` 状态段。
3. **处理本 handoff 分支**：本文件所在分支 `docs/handoff-2026-09-05`（含 progress/
   NEXT_SESSION_PLAN 状态更新）→ 推送 → `gh workflow run CI --ref <branch>` → 全绿
   → PR → squash merge → 同步 develop → 删分支。
4. **ADR-0013 状态化**：`docs/decisions/0013-mcp-proxy-wiring-proposal.md` 状态
   Proposal → **Accepted**（决策依据 = 用户 2026-09-05 指令「按阿里云/腾讯云怎么做的、
   参考他们的文档做」；实际实现 #154 已按腾讯 doc 135906/134890 落地，即原文档
   决策点 1 的推荐 B：消费者 API Key 进网关 + 服务/工具两级 ACL）。改动后并入同一个
   handoff 分支或新建小分支，流程同 3。
5. 若 4 小时内 GitHub 仍黑洞：直接更新 docs 提交到本地分支并留在远端，报告用户，
   不阻塞（一切验证以手动 dispatch 通道进行）。

---

## 2. 已做完（2026-09-04 → 09-05 连续轮，全部并入 develop）

| PR | 内容 | develop |
|---|---|---|
| #144 | UI 壳层 Vben 轮：顶栏面包屑、用户头像下拉菜单（退出项）、页面描述 14px、表头近白底 | 0e75f8b |
| #146 | 功能 3 演示证据 + usage 记账缺陷登记 | 68f3d8a |
| #147 | usage writer 失败日志带完整 throwable（原只记 message） | 2f9498c |
| #148 | 协议全景 `docs/protocol-agents.md` + Kafka ADR-0012 草案 | 9f30744 |
| #149 | MCP 代理接线 ADR-0013 草案 | cb9623e |
| #150 | 功能 4 stage 1：openapi-typescript 生成 + CI drift check + 一致性守卫 | ceb0959 |
| #151 | checkpoint 文档 | 169d96e |
| #152 | codegen 守卫全量化（自动推导 40 对 + ProductView 例外） | d146da7 |
| #153 | F55 盘点修正 DONE + runbook 冒烟清单 | 0813a7b |
| #154 | **F01 MCP 调用代理 v1**（腾讯 doc 135906 形态） | b8b354d |

另有更早合并：#141（管理员快捷加入项目，用户项目成员查询端点）、#138/#139（docs/
TDesign 移除收尾）、#135-#137（平台组/运营组 B 批/视觉轮）等；`/mcpservers` 配置面
（服务注册/工具/ACL/路由）与 UI 全量此前已完成。

### F01 v1 已实现细节（#154，新会话据此续作）

- 网关新入口 `POST /mcpservers/{serviceName}/mcp`
  （`backend/gateway-app/src/main/java/com/miqroera/miqrokey/gateway/proxy/McpProxyController.java`）：
  消费者鉴权（Bearer / x-api-key → SHA-256 → 快照 `consumerByDigest`）→ 服务按名
  解析（快照仅含 ONLINE）→ 服务级 ACL（`McpAccessPolicy`）→ `tools/call` 再做工具
  覆盖与启停判定（读 JSON-RPC 信封 method/params.name，不读参数正文）→ 60s 预算
  透传上游（会话无状态透传；响应流式回写；错误信封
  `{"error":{"type","message"}}`）。
- 快照扩展（`backend/domain/.../route/RouteSnapshot.java` +
  `backend/route-snapshot/.../JdbcRouteSnapshotLoader.java`）：新增
  `ConsumerRecord`（SHA-256 digest byte[]）按 digest 线性比对、`McpServerRecord`
  （endpoint/transport/status/aclMode/serverConsumerIds/tools[]）按服务名索引；
  loader 三趟只读查询（services+mode / server-list grants / tools left join
  override grants）。
- 即时刷新：control-plane 四个 service（`AdminMcpService`（create/setStatus）、
  `AdminMcpToolService`（create/setStatus）、`AdminMcpAccessService`
  （setMode/replaceGrants/clear）、`ApiConsumerService`（create/disable））注入
  `RouteRefreshPublisher` 并 `publishChanged()`。
- 测试侧：`GatewayTestKeys.snapshot` 已适配 12 参快照构造（test-support）。

---

## 3. 下一步队列（按顺序，每项含验收；做完一批 = commit + push + dispatch CI + merge）

### Q1（紧接 F01 v1，建议 09-05 当天）
**网关 MCP 契约/集成测试**（尚无自动化覆盖新入口）：
- 仿照 `UsageLifecycleIntegrationTest` 脚手架（testcontainers PG + WebTestClient +
  内嵌/HTTP mock 上游）新建 `McpProxyContractTest`，用例至少：
  1) 无/错 Key → 401 `invalid_api_key`；未知服务名 → 404 `mcp_service_not_found`；
  2) NONE 服务放行 `tools/list`（正例透传返回）；
  3) ALLOW 名单外消费者调 `tools/list` → 403 `mcp_access_denied`；
  4) 工具级：ENABLED+ALLOW 覆盖（名单内放行/外拒绝）、DISABLED 工具 →
     403 `mcp_tool_unavailable`；
  5) 非 `tools/call` 方法不受工具表影响；
  6) Session-Id 头透传、响应体逐字节一致。
- 注意：测试里 mcp_services.endpoint 的 https 校验只存在于控制面创建路径；测试可
  直接 JDBC 插行指向本地 mock（参考现有 mock provider 起法），或沿用
  `GatewayAuthTestConfig` fixture 快照注入（把 consumer/mcp 记录加进
  `GatewayTestKeys.snapshot` 后再断言，最省事——推荐后者，先加 fixture 数据）。
- 验收：新测试全绿 + 本地 `-pl gateway-app test`（175+）不回归 + CI 9/9。

### Q2（F15 元数据访问日志落地）
现状=McpProxyController 仅一行 `log.info("aigw.mcp.call ...")`。目标=结构化审计面
（不含工具参数与响应正文）：建议新表（V29）`mcp_access_log`（tenant/service/
consumer/method/tool/status/http_status/gateway_request_id/occurred_at），写入口放
gateway（异步批量，参考 usage writer 模式：有界队列/批量 insert/失败降级日志），
管理 API `GET /api/v1/admin/mcp-access-logs?service=&consumer=&from=&to=&limit=`
+ 审计页/管理页展示（可先只做 API + 表 + 后端测试，前端页放后续）。
验收：表/写路径/查询测试绿；网关调用后行可见；CI 全绿。

### Q3（F12 重试门禁 + F13 熔断；语义见 feature-backlog）
两者都**默认关闭**、挂 McpProxyController 上游出口。建议纯 domain 状态机：
- `McpRetryPolicy`（开关 + 幂等方法集合（GET/initialize/tools/list 之外默认重试？
  按 doc 135482 raw 12：仅首字节前、默认关、非幂等显式确认才重试）；
- `McpCircuitBreaker`（三态 CLOSED/OPEN/HALF_OPEN，最小请求数防误判，慢调用阈值<
  后端超时校验，429 可触发；配置存 mcp_services 扩展列或新表+管理 API）。
验收：domain 单测（状态机矩阵）+ gateway 集成测试 + 管理配置面可开关 + CI 全绿。
**注意**：F13 的「慢调用阈值 < 后端超时」指上游 health/config 的超时字段，别自造。

### Q4（本地真机冒烟，把 F01 从"能测"变"能演示"）
1) 起本地 mock MCP server（streamable HTTP JSON-RPC，python/node 均可，监听 8082，
   实现 tools/list + tools/call + initialize 最小集）；
2) control-plane 建/指向 https？——**https 限制**：mcp_services.endpoint 校验只认
   https，本地 mock 需自签 https 或临时改校验（不建议改生产校验）；更简单=在测试/
   演示用 https 反向（如给 python 起 TLS 自签并在 gateway 侧允许? 网关 WebClient
   TLS 校验是全局信任库）→ 建议先用 Q1 的测试路径演示，真机冒烟若必须则用
   `miqro-local/demo-registration-loop.sh` 同法 + mcp mock 起 https 自签并让 gateway
   JVM trust（`-Djavax.net.ssl.trustStore` 指向自签）——耗时，排最后。
3) 验收：curl `POST /mcpservers/<name>/mcp` 用消费者 Key 拿到 tools/list 结果；
   名单外消费者 403；流程写回 runbook 或 demo 脚本。

### Q5（UI）
用户标准未最终拍板：等你真机点验（preview `http://localhost:4174` 需先
`cd frontend && npm run build-only` 再起 preview；或另一会话 5173 dev 直连 8080）后
按页定向改；或回复「你自己看着办」由新会话继续 Vben 观感轮（2-3 页/批，评审存档
`miqro-local/ui-reviews/vben-*`，≤2 轮不追噪声）。已知评分（评审器 ±1.5 噪声）：
users 7.5-7.8 / keys 7.5 / plans 7.5 / credentials 8.0 / admin-usage 6.5 /
overview 6.5-7.5 / skillhub 7.4 / login 7.2（U0）。已实现而评审常误判的"已达标项"
：dot 状态徽标、ghost 行操作、48px 行高、进度条圆角/轨道、统计卡分层、数字列右对齐
——不要再因评审重复诉求而 churn。

### Q6（功能 4 stage 2：前端手写类型切换生成类型）
现状：`frontend/src/types/generated.ts`（openapi-typescript 产物，已提交）+ CI
drift check + 一致性守卫（自动 40 对）。stage 2 = 让 api/index.ts 与视图改为引用
生成类型，删除手写同名重复。**高成本全仓改名**（生成 schema 名与手写 View/Request
命名差），建议等用户知情后启动；若启动：拆批（每批 3-5 个 DTO 迁移 + typecheck +
vitest + 截图比对），守卫白名单同步移除已迁移项。

### Q7（Kafka / F32 / 发布收尾 —— 等拍板，勿擅动）
- Kafka ADR-0012（Proposal）：场景/拓扑待用户或 leader 选择（候选：usage 导出流 /
  F32 同步事件）。**不要在没有 Accepted ADR 的情况下引入 Kafka**。
- F32 平台用户同步：BLOCKED 等 leader 平台字段形态（JWT sub→平台 user_id 映射已在
  ADR-0011 铺垫，F32 本体再等）。
- 发布收尾：版本号/tag（等授权）、真实供应商凭证实测矩阵（H 组 F53，等真实 Key）。

---

## 3b. 新增：内容留痕/Kafka/OAuth 需求（2026-09-05 用户转述 leader×平台沟通）

- 需求整理与方案见 [ADR-0014（草案，v3）](decisions/0014-content-retention-and-kafka-events.md)（§0b 覆盖矩阵 M1-M14：口述每条→主流做法或口述方案；本地缓冲 WAL 与消费端本地文件输出为一等设计；带宽估算见 P8）：
  内容留痕（用户请求加密冷存、按用户追溯、默认关闭）+ Kafka 事件管道（平台多进程消费端）+ OAuth 用户无感同步（映射表骨架，本系统登录思路不变）。
- 待拍板：P1 内容范围 / P2 默认关 / P3 存储目标与保留期 / P5 加密密钥管理 / P7 OAuth 细节（平台后续改）。
- 红线冲突：与 CLAUDE.md「不保存 prompt/正文」冲突，ADR Accepted 前不做实现。

## 4. 过程性记忆（防重踩）

- 视觉评审纪律：DeepSeek 评审器噪声大（同元素相邻轮意见相反、误判已实现项），
  只采纳"跨轮一致且代码确实缺失"项；≤2 轮；打分只记录不做硬目标。
  调用：`python D:/Desktop/My_projects/MiQro-key/miqro-local/vision_review.py <png> "上下文"`。
- codegen 守卫教训：生成器版本差异会改输出格式；守卫一律读 **JSON 源**
  （`docs/openapi/openapi-3.1.json`）而非 generated.ts 文本，避免格式敏感。
- MCP loader 教训：快照 SQL 引用表 `api_consumers`（key_digest **bytea**）、
  `mcp_services/mcp_service_access/mcp_access_grants/mcp_tools`（V13/V20/V21/V25）；
  consumer 摘要=SHA-256（vkey 是 HMAC，**不要混用**）。
- 网关快照=**只读内存**（JdbcRouteSnapshotLoader 查库构建 + NOTIFY/30s 刷新），
  网关热路径绝不查库；新增数据面读取一律进快照。
- `npm run lint` 的 EOL 噪声与 `git status` 的判定差异：以 `git diff --numstat`
  为准（0/0=纯 EOL）。
