# Changelog

MiQroKey Gateway — 内部凭证治理网关。所有改动按 Goal 汇总；版本号语义化（MAJOR.MINOR.PATCH）。

## [Unreleased] — 截至 2026-09-03（发布候选基线）

### 2026-09-12
- **被动健康检查：真实流量失败率入服务健康视图（#397，矩阵 §3 候选落地，阿里「主动+被动并列」）**：新端点
  `GET /api/v1/admin/mcp-services/{id}/traffic?hours=24`——`mcp_access_log` 按服务窗口聚合（分类口径同 #338：
  `failed` = UPSTREAM_FAILURE + CIRCUIT_OPEN）、`failureRate`（failed/(forwarded+failed)，无健康相关流量为 null）、
  `lastCallAt`/`lastFailureAt`、`topFailingTools`（失败工具 top ≤5）；管理面「健康检查」弹窗新增「真实流量」区
  （1h/24h/7d 窗口切换），**主动探测通过但窗口内存在真实上游失败时显式提示**（主动探测盲区）。只读、不阻断；
  无迁移（数据已存在）。
- **修复管理面窗口切换的过期响应竞态（#399）**：MCP 服务「真实流量（被动）」区（#397）与消费者「调用概览」
  （#338）改为**请求序号守卫**——过期响应（含失败）一律丢弃、loading 只由最新请求收尾；服务弹窗窗口切换改
  **选择器事件驱动**（不再用 watch——重开弹窗时窗口复位会经 watch 再触发，此前每次重开发 2 次 `…/traffic`）。
  纯前端显示正确性修复，无接口/数据变更；两处均补乱序响应回归测试（先红灯复现、修复后转绿）。
- **访问日志转发 sink 挂起免疫（#401，网关）**：新增 `TimeBoundedForwarder` 队列级硬截止包装——每个 sink
  专属守护线程 + `Future.get` 墙钟截止（webhook = 配置超时 + 5s；syslog = 15s，覆盖 3s 连接 + 无上界的写）；
  超时计失败并 `cancel(true)`；连续超时 ≥3 次进入 60s 冷却（不重试风暴），冷却后自动探测恢复。修复前：
  TCP syslog 对端「收连接不读取」→ 阻塞写不可中断 → **唯一 flush 线程被挂死 → 后续批次不再落库、队列打满
  持续丢弃**（catch 不到 hang）。附带：webhook 请求超时改用配置值 `webhook-timeout-ms`（此前硬编码 5s）。
  测试：包装器 4 + 队列挂起隔离 1（挂起 sink 不阻断落库与其它健康 sink）。
- **修复 webhook 端点删除的并发窗口（#403，I21 补强）**：依赖检查前先对端点行 `SELECT … FOR UPDATE`
  （与并发建引用规则的 FK `FOR KEY SHARE` 互斥）——并发交错下要么 409 `RESOURCE_IN_USE` 拦截、要么
  插入侧干净 FK 失败，**静默 `ON DELETE SET NULL` 脱钩路径彻底关闭**（修复前该交错让刚建的规则被静默
  脱钩）；并发双击删除干净 404。测试：并发 IT（第二连接持未提交引用插入 → 删除阻塞 → 提交后 409 +
  引用原样），修复前红灯精确复现（200 + 引用置 NULL）、修复后 4/4 绿。
- **修复技能修订并发激活的冲突错误面（#404，I14 补强）**：`activate` 的变更块（deactivateOthers→activate
  →mirror）捕获 `ConcurrencyFailureException | DuplicateKeyException` → **409 `SKILL_REVISION_CONFLICT`**
  （与发布路径同形）——并发激活交错（deactivateOthers 互锁 → 死锁输家 / 激活指针唯一索引竞争）此前冒泡
  **裸 500**；`publishValidated` 捕获同步扩到 `ConcurrencyFailureException`（发布-激活交叉并发同样可死锁）。
  串行路径行为不变；技能修订 IT 全绿。
- **修复管理面 8 处弹窗目标切换的同类竞态（#407，#399 全量收口）**：工具/工具重试/工具修订/服务访问/
  服务路由/服务韧性/技能修订/Webhook 投递记录——按目标加载的弹窗统一加**请求序号守卫**（过期成功/失败
  一律丢弃、loading 由最新请求收尾），快速切换目标时旧响应不再覆盖新显示。代表性回归测试先红灯复现
  （工具列表串服务：`erp-stale-tool` 覆盖 `crm-only-tool` 现场）后修复转绿；全量前端套件绿。
- **PR 自动 AI 审查接入（#410，key 版惰性）**：新增 `AI review` workflow（claude-code-action v1.0.222，
  SHA 固定）——默认未配置 `ANTHROPIC_API_KEY` 时全部步骤跳过、对任何 PR 零影响；配置后每个非草稿 PR
  自动获得一次中文审查（仓库红线核对 + 缺陷/安全/边界视角 + 测试/流程检查 + `track_progress` 进度 +
  行内批注）；同一 PR 并发去重、20 分钟超时上限。停用=删除 secret。
- **修复全局异常语义（#412）**：`GlobalExceptionHandler` 补 6 个兜底 handler——未知路径→404 `NOT_FOUND`、
  错方法→405 `METHOD_NOT_ALLOWED`、缺必填参数→400 `PARAM_INVALID`、不支持 Content-Type→415、
  未局部映射的约束冲突→409 `RESOURCE_CONFLICT`、死锁/锁失败→409 `CONCURRENT_MODIFICATION`
  （此前全部被 `Exception` 兜底吞成 **500 INTERNAL_ERROR + ERROR 日志噪音**；响应体永不携带 SQL）。
  服务内 14 处局部映射保持不变。测试：单元 3（含不泄露 SQL 断言）+ IT 2（认证后未知路径 404、
  错方法 405；**修复前红灯精确复现两处 500 现场**）。
- **修复 MCP 健康巡检与管理员操作的乐观锁竞态 + 全库乐观锁冲突映射（#415）**：巡检改**窄写**
  （`McpServiceRepository.updateHealth`，镜像 #361 对 Internal Service 的实现：健康列专属、无版本校验、
  不推版本）——探测周期不再与管理员编辑竞争（修复前：巡检先提交 → 管理操作 `IllegalStateException` 裸
  500，CI 集成套件现场复现）；全库 **19 处**乐观锁冲突抛点改用 `OptimisticLockingFailureException`
  （Spring 类型，extends `ConcurrencyFailureException` → #412 全局兜底自动 **409 `CONCURRENT_MODIFICATION`**）；
  既有域内映射（SERVICE_STATE_CONFLICT / ALREADY_REVIEWED）对齐保持。测试：巡检版本不动 + 双连接行锁
  交错 409（红→绿）+ 持久层断言类型更新。

### 2026-09-11
- **资源删除前置依赖检查（#393，I21，腾讯模型 API 删除语义）**：删除仍被引用的资源返回 **409 `RESOURCE_IN_USE`**
  + problem 体附 **`dependencies` 清单**（type/id/name/detail）——首个落点=webhook 端点（原 `SET NULL` 会
  让告警规则静默失去投递目标，现改为拒绝 + 清单，先删/改配规则再删）；前端删除被阻时弹依赖清单弹窗；
  通用异常与问题体形状可供后续删除端点沿用（经 FK 梳理，其余现存删除端点无引用面）。
- **官方文档直读吸收（TKE MCP 托管 / 腾讯模型 API / 阿里 AI 网关概述 / 阿里消费者认证）**：验证 #387
  JSON-RPC 探活与 I15 观测维度方向；新增 **I21 资源删除前置依赖检查**（#393，409+依赖清单）；登记 §3
  三项需裁决/对齐（消费者 HMAC、AI 内容安全护栏、F11 匹配模式 CONTAINS/EXISTS）与被动健康检查候选；
  对照详见 `docs/ai-gateway-comparison.md`「官方文档直读补充」。纯研究登记，无产品行为变更。
- **默认配额语义提示 + 矩阵尾项核销（#391）**：默认配额模板面板补常显提示「变更或停用只影响之后新建的用户；
  已存在（含自动分配）的配额规则保持不变，停用也不会删除它们」（doc 22 三条语义收齐）；Skill「编辑」核定=
  重传即发布修订（I14）为编辑路径，直接元数据编辑因与「SKILL.md 为事实源」冲突不做（矩阵 20 行记录）。
- **审计列表资源名称解析（#389，doc 27）**：`GET /admin/audit-events` 每行新增**可空** `targetName`——按页内
  `(targetType, targetId)` 每类型一条 `IN` 查询批量解析（租户内、只读、无 N+1；USER/MCP_SERVICE/MCP_TOOL/SKILL/
  VIRTUAL_KEY/TEAM/SERVICE/PROJECT/CONSUMER/UPSTREAM_CREDENTIAL/SUBSCRIPTION/AGENT/WEBHOOK/ALERT_RULE/TENANT），
  未知类型或引用已不存在为 null。审计页新增「目标」列（名称优先、回退短 ID）；CSV 导出与链上数据不变。
- **MCP 健康探测支持 JSON-RPC initialize 模式（#387，doc 03）**：V50 `mcp_services.check_mode`（`HEALTH_PATH`
  默认，行为不变 | `JSONRPC_INITIALIZE`）——后者 POST `endpoint` 的 JSON-RPC 2.0 `initialize` 信封（标准 MCP
  服务的协议原生探活，兼容 SSE 帧响应），API_KEY 后端自动注入解密 Bearer（fail-closed）；健康配置 API/管理页
  弹窗可选（非法值 `400 MCP_CHECK_MODE_INVALID`）。
- **MCP 服务管理页上游预算字段（#383，I20 前端后续）**：注册表单可填「上游预算（毫秒，可选，默认 60000）」；
  行内「预算」入口 → 弹窗查看/编辑（本地范围校验 1000–600000，PUT `…/upstream-timeout`），完成 I20 的
  用户可见闭环。
- **测试基建：App.spec 并发超时加固（#384）**：登录用例模块作用域预加载登录 chunk，消除 `router.push`
  等待被争用动态 import 的超时耦合（全量 ×3 + 6 路 CPU 压测下 179/179）。
- **修复审计链偶发校验失败（#362）**：`AuditServiceImpl.record()` 现在把 `created_at` 先**截到微秒**再参与哈希与落库——
  此前 `Instant.now()` 携带亚微秒位（Windows 时钟 100ns 步进），pgjdbc 向 PG 微秒列写入时**四舍五入**，个别值
  （如 `.9999996s`）被进位到下一毫秒 → 校验重算的 `toEpochMilli` 与存储值不符（约 1/2000 事件概率，长跑偶发）。
  微秒对齐值可被 PG 精确存储，存储时间戳与哈希输入逐位一致。回归：`AuditChainIntegrityTest`
  新增固定时钟边界用例（`.9999996s`，修复前确定性失败）；修复后该类 7/7，并经 120 轮 × 8 线程并发压测复核。
- **MCP 访问日志可插拔 sink（#379，I19，raw 16 日志投递）**：批次**落库成功后**旁路扇出到 webhook（POST JSON 数组，
  `aigw.mcp.*` 元数据字段集，可选 Bearer token）与 syslog（RFC 5424，UDP/TCP，facility 可配，MSG 为同字段集
  JSON）；`miqrokey.gateway.mcp-log.forward.*` 部署级开关（默认全关）；重入队批次不重复投递、sink 失败仅节流
  WARN——永不阻断数据面、不影响审计行；字段集固定为条目元数据（无任何正文）。
- **Skill 版本历史/回滚（#377，I14，raw 20 版本管理）**：V49 `skill_revisions`（不可变包快照，存量回填 r1；部分唯一
  索引保证每技能至多一激活修订）；**同名重传从「upsert 覆盖」改为「发布下一修订」**——旧包保留、目录/下载镜像激活
  修订；`GET /admin/skills/{id}/revisions`（元数据视图，永不回包体）与 `POST …/revisions/{rev}/activate`
  （回滚=幂等指针移动，不产生新版本号）；审计 `SKILL_REVISION_PUBLISH/ACTIVATE`；管理页「版本」弹窗（历史列表 +
  回滚确认 + 重传提示）。
- **成本报表维度补齐（#375，I15，raw 23）**：`usage/summary` 新增 `groupBy=user`（调用方，label=用户名）/
  `model`/`month`（自然月 `YYYY-MM`）三个维度（缓存命中事件同维度聚合；usage IT 8/8）；成本报表页扩为
  项目/按天/调用方/模型/月五页签、表格统一占比列、新增**最高消费者**与**缓存命中 Tokens**两卡（共 7 卡）、
  CSV 导出跟随当前维度。
- **MCP 服务级上游超时 + 熔断慢阈值基准修正（#373，I20，raw 03「超时时间」/ raw 13）**：V48
  `mcp_services.upstream_timeout_ms`（1000–600000，默认 60000）经路由快照下发；数据面每次上游尝试按服务预算计时，
  超时 → **504 `mcp_upstream_timeout`** 类型化错误（`mcp_access_log` 记 UPSTREAM_FAILURE/504，此前为裸 500）；
  `PUT /admin/mcp-services/{id}/upstream-timeout` + 创建可带（越界 `400 MCP_TIMEOUT_INVALID`）；熔断慢调用阈值校验基准
  由健康探测超时（默认 5s）修正为上游预算（doc 134859 原文基准=后端请求超时），保存策略/下调预算**双向拒绝**
  （`RESILIENCE_SLOW_EXCEEDS_TIMEOUT`）；审计 `MCP_SERVICE_UPSTREAM_TIMEOUT`。
- **修复：快照装配丢弃 backend 鉴权字段（#371）**：`JdbcRouteSnapshotLoader` 最终装配回填
  `backendAuthMode/encryptedBackendSecret`（#321 引入字段时漏改 #154 时代重建行）——API_KEY 模式数据面恢复
  上游 `Authorization` 注入；回归：真实加载器断言密文信封逐字节回环。
- **runbook §14 常见误配与归因（#369，I16，阿里/腾讯运营口径对照）**：Key 形态误配一步定位（三类凭据 × 端点面
  对照表 + 定位四步）；供应商账单 T+1 对账窗口建议（接 #330 reconcile 等级）；429 只来自上游（不限流红线）与
  403 六类归因码 + 归因入口（mcp_access_log/审计链）。纯文档。
- **留痕通道内容上限（#367，I18，doc 26 衍生）**：V47 `retention_config.max_content_bytes`（默认 256 KiB，范围
  1 KiB–4 MiB，租户级）；超限由「整条丢弃」改为 **UTF-8 边界截断 + `truncated` 标记（envelope/Kafka payload）+
  超限计数**（节流 WARN）；PUT `/admin/retention-config` 接受 `maxContentBytes`（越界 400
  `RETENTION_CONFIG_INVALID`，审计含值）。验证：网关捕获测试 4/4（新增截断用例：1 条 envelope、flag、计数、
  UTF-8 安全前缀、零丢弃）、管理 IT 5/5（回读 + 越界 400）、Kafka IT 原样绿。
- **修复：`breakerSkipRetry` 真分支 + SERVER_5XX 收窄（#365，I17，doc 12/13）**：`breakerSkipRetry=false` 时熔断
  只观测不限流（`beforeCall` 仍驱动状态机/探测计数，REJECTED 不再阻断调用；默认 true 行为不变）；
  `SERVER_5XX` 重试触发由 500–599 收窄为 **500/502/503/504**（501/505 等确定性状态直接回传）。
  验证：熔断 IT 3/3（新增 skipRetry-off：开断后第三次调用落上游）、重试门 5/5、契约 30 例、域单测全绿。

### 2026-09-10
- **修复：服务状态切换与健康巡检的乐观锁竞态（#361）**：`updateStatus` 改为**按状态列 compare-and-set**
  （并发切换 → `409 SERVICE_STATE_CONFLICT`，不再 500；不再与巡检的 version 争用）；`ServiceHealthChecker`
  改用窄写 `updateHealth`（仅健康列、不 bump version），管理侧版本守卫路径不再被巡检挤掉；健康配置更新遇并发
  同样 409 化。集成测试：巡检后 version 不变 + 健康列已更新 + 随后禁用成功。
- **Tool 级重试粒度下沉（#360，I13，doc 12）**：V46 `mcp_tool_retry_policy`（每工具一行，覆盖服务级策略的**重试字段**，
  熔断保持服务级）；`GET/PUT /api/v1/admin/mcp-services/{id}/tools/{toolId}/retry-policy`（校验同 F12；
  `TOOL_RETRY_POLICY_INVALID`；审计 `MCP_TOOL_RETRY_UPDATE`；快照即时刷新）；快照 `McpToolRecord.retry` +
  loader LEFT JOIN；网关生效策略 = 服务策略 withRetry(工具覆盖)。前端 Tools 对话框「重试」按钮 + 覆盖表单。
  验证：数据面 ToolLevelRetry 3/3（GET 覆盖无服务策略可重试 / POST 覆盖未确认不重试 / 未覆盖工具沿旧行为）+
  既有 RetryGate 5/5、熔断 2/2、契约 30 例无回归；管理面 IT（默认态/三类校验 400/回读/审计/404）；
  前端 spec 17/17；OpenAPI 再生无破坏 + gen:types。
- **MCP 访问日志补 session_id / ttfb_ms（#358，I12，doc 16）**：V45 两列（纯加列）；网关写入 `session_id`
  （客户端 Session-Id 头；SSE 分发未带头回落入站会话 id）与 `ttfb_ms`（上游首字节，仅 FORWARDED，重试按最终
  成功计）；管理/开放读面与前端日志页两列同步（null 占位 —）。验证：网关 F15 集成 5/5（新增带会话头的转发
  行断言 + 无头行 null 断言）、既有契约 30 例原样通过；OpenAPI 再生无破坏 + gen:types。
- **入站 MCP SSE 双端点（#356，I11，ADR-0013 二期）**：`GET /mcpservers/{name}/sse`（单节点内存会话 +
  endpoint 事件 + 15s 保活 + 容量 256/空闲 5 分钟回收）+ `POST /mcpservers/{name}/message`（传输级检查直答、
  202 后沿与 /mcp 完全同一流水线分发，结果以 `message`/`error` 事件回流；上游响应体逐字节原样，v1 上游流式
  响应整段聚合）；会话绑定单一消费者+服务、F15 日志同记。`McpProxyController` 抽出 `ResponseTarget`
  （直连/SSE 两种目标），流水线代码零分叉。验证：`SseFrames` 4/4、会话注册表 4/4、SSE 契约 7/7
  （401/404、字节原样回流、ACL→error 事件、会话越权 403、断开即失效）；全量 verify 绿。
- **F16 修订字段级 diff + 路由匹配表达式只读展示（#354，I10，doc 11/10）**：`McpToolRevision.changedFieldsVs`
  纯函数——修订列表每项携带只读 `changedFields`（相邻旧版差异：描述/方法/路径；基线为空）；`McpRouteRules.renderExpression`
  纯函数——路由规则响应增只读 `matchExpression`（与引擎执行的规范条件面同一语义）。前端：修订对话框差异 chips
  + 基线「初始版本」标记；路由抽屉只读表达式行。两领域记录均提供旧签名兼容构造器；无迁移。
  验证：域单测 +2（表达式渲染 / 变更字段），路由 IT 6/6、修订 IT 4/4；前端 16/16（本 spec）；OpenAPI 再生无破坏。
- **Skill 搜索 + 标签筛选 + Examples + 创建人（#352，I9，doc 20/28）**：`GET /api/v1/skills?q=&tags=&tags=`
  与 `GET /api/v1/admin/skills?...` 支持关键字（≤60，名称/描述/ID 不区分大小写）与标签多选（**与**语义）；
  上传校验新增 `examples`（≤10×512，V44 `skills.examples text[]`）与 tags 上限（≤5×20，去重）；
  `SkillView` 增 `examples/createdBy/createdByName`。前端技能市场：搜索 + 已有标签筛选 chips + 卡片
  （≤3 标签+余量、首个示例、创建人）；管理页创建人列。验证：Validator 12/12、Skill IT 5/5（含搜索/与语义/
  q 超长 400）；前端 173/173；OpenAPI 再生无破坏 + gen:types。
- **模型目录定期重探（#350，I8，doc 05 建议频率）**：`ModelCatalogReprobeScheduler`——`miqrokey.model-catalog.reprobe.enabled`
  开启（**默认关**）后按 `miqrokey.model-catalog.reprobe.cycle-ms`（默认 6h，fixedDelay）对种子租户 ACTIVE 订阅关联的
  OFFICIAL_API 产品执行与手动探测**同一实现**的抓取（成功才落目录、失败记录 V43 状态面并计数不中断）；
  审计沿用 `MODEL_CATALOG_PROBE_SUCCEEDED/FAILED`（actor=null 系统行为）。无迁移、无端点变化；
  configuration-reference 增两条属性。验证：调度器单测 2/2（逐产品探测 + 系统 actor + 失败隔离 / 无订阅零调用）。
- **审计页收口（#348，I7，doc 27）**：审计日志页新增 **actorId 过滤**（UUID 形态校验，非法内联报错且不发请求；
  列表与 CSV 导出均生效——后端两端点本就支持）、**快捷时间窗**（近 7 天 / 近 30 天一键回填并查询）、
  **targetType 下拉**（28 个实际资源类型 + 全部类型）。纯前端，无契约与会话变更。
  验证：audit spec 5/5、全量 41 文件 172 用例、typecheck/eslint/build 通过。
- **模型探测端点 + 失败可见面（#346，I4）**：`POST /api/v1/admin/models/probe`（适配器 + 首个 ACTIVE 凭证 →
  官方 `/models` 抓取，30s 上限，成功才落目录）+ `GET /api/v1/admin/models/probe-status`（最近结果可见面）；
  V43 在 `provider_products` 记录 probe 状态 / 脱敏错误 / 模型数 / 时间；`refreshProduct` 与探测共用抓取核心
  （成功才落库、失败保留最后成功目录、不覆盖 MANUAL 行）；审计 `MODEL_CATALOG_PROBE_SUCCEEDED/FAILED`。
  前端供应商产品「模型目录」对话框「探测模型」+ 上次探测状态行（成功/失败原因）。验证：目录服务单测 8/8、
  探测集成 5/5（成功落库 / 失败脱敏且目录不动 / 无凭证 / 未知产品 404 / 401）；OpenAPI 基线再生（无破坏）+ gen:types。
- **MCP Tools 自动同步（#344，I3）**：`POST /api/v1/admin/mcp-services/{id}/tools/sync?dryRun=`——上游 `tools/list`
  差量合并：新增工具（占位 `POST /` + 基线修订 1）、描述变化经 F16 发布下一修订（激活并镜像）、上游缺失仅报告
  `absentUpstream`（不自动禁用/删除）；逐项报告 added/updated/unchanged/absent/skipped；`dryRun` 预览零写入零审计；
  `API_KEY` 后端注入解密 Bearer（fail-closed、用后清零）；2MB/1000 工具/30s 守卫；审计 `MCP_TOOLS_SYNCED`。
  前端 MCP 服务 Tools 对话框「同步 Tools」→ 预览 → 确认应用。验证：客户端单测 7、同步服务单测 9、集成 8
  （新增/幂等/修订发布/缺失只报/dryRun/脱敏 502/Bearer 注入/404）；OpenAPI 基线再生（无破坏）+ gen:types。
- **账单对账前端页（#342，I2）**：新增「账单对账」管理页（运营分组）——报告列表（新→旧，状态 / 四态计数 /
  金额差）、canonical JSONL/.gz 上传（16MB 预检、gzip 魔数提示、202 后自动轮询到终态）、报告详情（汇总卡 +
  四态明细按 verdict 过滤 + 游标「加载更多」+ 失败原因展示）。后端配套 `GET /api/v1/admin/reconciliations?limit=`
  （租户内新→旧，limit 1..100 越界 400，空列表 `[]`）；OpenAPI 基线再生（无破坏变更）、`gen:types` 同步。
  验证：对账 IT +1（列表排序 / limit 边界 / 跨租户隔离）；前端 spec +3；全量 vitest 41 文件 167 用例。
- **MCP 数据面接受消费者 JWT（#340，I6）**：`/mcpservers/{name}/mcp` 的 `Authorization: Bearer` 非
  `mqk_api_` 前缀时按消费者 RS256 JWT 处理（`sub`→快照按名映射，公钥随快照 `jwt_public_key_pem` 下发；
  验签失败/未知 sub/未配公钥 401 与未知 Key 同形）；到期与 `mcp:call` 作用域检查对两通道一致；
  `X-API-Key` 保持 Key-only。`ConsumerJwtVerifier` 上移 domain（纯 JDK：自带严格 JSON 扫描，零三方库；claims
  不做类型强转，ArchUnit domain 门禁拦截序列化库依赖）供两进程复用。ADR-0011 增补。验证：网关契约 +6（有效/
  过期/错签/未知 sub/无公钥/scope 与到期/x-api-key 误用）全量 30/30；验签器单测 +4（类型严格/嵌套诱饵/重复键
  last-wins/文档严格解析）。
- **消费者「最近调用概览」（#338，I5）**：`GET /api/v1/admin/api-consumers/{id}/activity?hours=24`——
  `mcp_access_log` 窗口聚合（总数/已转发/被拒/失败、最近调用、Top 工具/Top 服务各 ≤5；hours 1..168）；
  无新表、无网关改动（复用 V29 纯元数据日志）；前端消费者页「调用概览」对话框（24h/7d 切换）。
  验证：IT 2/2（多状态聚合/Top/窗口/租户隔离/空窗口/跨租户 404）+ FE spec。
- **能力覆盖对照表（#336）**：docs/coverage-matrix.md 首版——腾讯 29 篇 + 阿里对照逐能力四态定级
  （对等/部分/未做/不适用+理由），含「可立即实现（I 编号）/需裁决/需外部」清单与每-rc 刷新纪律；
  feature-backlog 与 NEXT_SESSION_PLAN 增加指针（立项登记 vs 能力底账分工）。封堵"缺口等关键词才浮现"流程缺口。
- **F19 对账端点层（#334）**：canonical 账单导入→异步四级匹配→四态报告全链落地（V42 两表，**结果只读、不写 usage_event、不存上传内容**）——`POST /admin/reconciliations`（JSONL/可选 gzip、202）、`GET /{id}` 汇总、`GET /{id}/rows` 四态明细（游标分页）；幂等重传（同 provider+window+currency+sha 返回既有报告）；引擎补行级 UNMATCHED_LOCAL 输出；审计 RECONCILIATION_CREATED/SUCCEEDED/FAILED（不存正文）。供应商私有解析器与指纹级匹配仍 WAITING_FOR_SAMPLE（canonical 路径不依赖样本）。验证：集成测试 2/2（四态/幂等/gzip/校验/审计）。
- **App.spec 并发 flake 修复（#332）**：全量 vitest 并发跑下 `App.spec` 登录视图用例间歇失败
  （懒加载路由组件 chunk 在 CPU 竞争下晚于 flushPromises 就绪，断言竞态）——改用 `vi.waitFor`
  条件等待，全量套件连跑 3 次稳定绿 163/163（未用 timeout/retry 掩盖）。
- **导出「可对账等级」标记（#330）**：V41 `export_tasks.reconcile_level`——任务完成按 provider_request_id
  覆盖度声明 `PROVIDER_ID_BACKED/PARTIAL/LOCAL_ONLY`（空窗口/历史任务 null）；会话与机器元数据面均带
  `reconcileLevel`；产物 `local_caliber_note` 扩展 `;reconcile=…`（前缀向后兼容）；前端导出页等级徽标。
  兑现 usage-accounting §11 文档承诺的先行部分（净额/含调整随 F20）。验证：IT 2/2（四场景等级/文件注记/
  双读面字段）。
- **运维文档补齐轮（#328）**：runbook §3c 消费者密钥运维（scope 最小权限/到期静默失效语义/轮换=重建/
  CONSUMER_KEY_EXPIRING 提醒）、§3d MCP 上游后端密钥运维（写后不可读/上游轮换双活窗口/
  backend_auth_unavailable 排障）、§3e 服务注册表健康运维（上下线对称/探测与阈值语义/调度周期配置）；
  configuration-reference 补 `MIQROKEY_MCP_HEALTH_CYCLE_MS` 欠账行；NEXT_SESSION_PLAN 重写。
- **服务注册表运行时治理（#326）**：V40 扩展 `services`——健康探测列（镜像 mcp_services 先例）+ 上下线补全：
  `POST /{id}/enable`（对称为一等操作，审计 `SERVICE_ENABLE`）、`POST /{id}/health-config`（部分更新，审计
  `SERVICE_HEALTH_UPDATE`）；`ServiceHealthChecker` 按各自间隔探测 ACTIVE 服务（GET baseUrl+checkPath，2xx
  计健康，阈值驱动 UNKNOWN/HEALTHY/UNHEALTHY），DISABLED 永不探测；前端服务页健康徽标/最近检查/启用按钮/
  健康配置对话框。验证：检查器单测 2/2（阈值迁移 + 真实本地 HTTP 探测）+ IT 3/3（往返/配置/探测生命周期）。
- **审计覆盖第二批（#324）**：兑现 #315 follow-up——告警规则/Webhook/预算/全局配置/模型目录人工维护五族
  12 个写操作全量入审计链（此前零审计，含 F60 机器写面对告警规则/Webhook 的改动）：
  `ALERT_RULE_*`/`WEBHOOK_*`（secret 永不入摘要，断言）`BUDGET_PUT/DELETE`/`CONFIG_PUT/DELETE`
  （value 永不入摘要，断言）/`MODEL_CATALOG_ADD/DELETE_MANUAL`；新增 `AuditContext` 统一归属模型——
  机器面 actor=发行管理员 + 摘要 `via: admin-api:<密钥名>`（人机双元可溯），会话面 actor=操作用户。
  验证：综合 IT 2/2（五族逐事件断言 + 机器面 via/actor + secret/value 缺席断言）+ 全量回归。
- **消费者密钥到期治理（#322）**：V39 `api_consumers.expires_at`（NULL=永不过期，存量零行为变化）——创建时可选
  到期（必须为将来，非法 400 CONSUMER_EXPIRES_INVALID）；到期后**双面静默失效**（控制面计费通道仓储查询
  `expires_at > now()` 条件 + 网关 MCP 数据面快照 `expiredAt(clock)` 判定，均 401 与未知 Key 同形）；
  管理列表仍展示到期行；可选 `CONSUMER_KEY_EXPIRING` 告警规则（默认关，≤7 天到期消费者每（消费者×天）
  至多一条事件，镜像管理密钥 V36 先例）；前端消费者页到期列（≤7 天高亮）+ 创建表单到期输入 + 告警规则类型选项。
  验证：控制面 IT 4/4（创建/双仓储静默拒绝/列表可见/校验/提醒去重与关停）+ 网关契约含过期 401 用例。
- **MCP 上游后端鉴权注入（#320）**：对齐腾讯 raw 03「Visitor / API Key」三级鉴权链——`mcp_services` 新增
  `backend_auth_mode`（VISITOR 默认 / API Key，V38）；API Key 模式网关按请求解密注入固定
  `Authorization: Bearer <secret>`（密文随路由快照、AAD 绑定 tenant+service、明文不出网关、用后清零）；
  密钥写后不可读（任何读面/审计不含）；解密失败 fail-closed 502 `backend_auth_unavailable`（上游零请求）；
  `PUT /api/v1/admin/mcp-services/{id}/backend-auth` + 400 `MCP_BACKEND_AUTH_INVALID` + 审计
  `MCP_SERVICE_BACKEND_AUTH`；前端服务页后端鉴权徽标与编辑对话框。验证：控制面 IT 2/2（只写/加密落库/
  轮换/清除/审计/校验）+ 网关契约 3/3（注入/VISITOR 不注入/失败关闭）。
### 2026-09-09
- **API 消费者能力作用域（#316）**：V37 `api_consumers.capabilities`（NULL=全量，值域 billing:read/mcp:call）——
  一把消费者 Key 不再隐式同时拥有「整租户计费读 + MCP 数据面调用」；计费通道缺 billing:read → 403
  CONSUMER_SCOPE_DENIED，网关 MCP 数据面缺 mcp:call → 403 consumer_scope_denied（均 fail-closed、与
  ACL 正交、快照即时刷新）；`PATCH /api/v1/admin/api-consumers/{id}/scope` + 审计 CONSUMER_SCOPE_UPDATE
  （from/to）+ 400 CONSUMER_SCOPE_INVALID；前端消费者页作用域徽标与编辑。验证：控制面 IT 2/2（计费门禁/
  恢复全量/校验/空列表=无通道/审计断言）+ 网关契约 6/6（含 consumer_scope_denied）。
- **服务与集成族审计覆盖（#315）**：消费者/Agent/内部服务注册表/MCP 服务/MCP 工具（含 F16 修订发布与
  回滚激活）/Skill 六族管理写操作全部进审计链（此前零审计）——事件 action/targetType 同词表、actor=
  操作管理员、共享 AuditSummaries 生成 jsonb 安全摘要（控制字符剥离 + JSON 转义，不含明文密钥/PEM/包体）、
  X-Request-Id 关联；控制器统一传参。集成测试 5/5（含"摘要不含明文 Key"断言）。
- **操作记录查询补全 + 合规导出（#314）**：audit-events 读面新增 targetType/actorId/from/to 筛选
  （人类 + 机器双端点共享 AuditEventReadService，cursor 可组合）；新增 CSV 合规导出
  `GET /api/v1/admin/audit-events/export` 与 `/api/v1/admin-api/audit-events/export`
  （RFC 4180 转义 + UTF-8 BOM，不含哈希链/正文；单次上限 5 万行，超限以响应头
  `X-MiQroKey-Truncated` 显式声明，不静默截断；不合法时间参数 400 PARAM_INVALID/
  TIME_RANGE_INVALID）。对齐腾讯 AI 网关「数据观测 > 操作记录」下载能力（raw 27）。
  前端审计页加资源类型/时间窗筛选与导出按钮（截断提示）。集成测试 5/5（双面筛选组合/
  租户隔离/CSV 形状与转义/截断声明/时间参数校验）。
- **spec 纳入真实类型检查(#295,#294)**：tsconfig.spec.json(composite=false 防 TS6307)；
  typecheck 三段链 app→spec→node；20 个 spec 文件 95 积压错误清零(仅类型层)；
  codegen-consistency.spec 潜在 any 掩盖修正。
- **F60 批 3 治理设计稿(#292,#290,待拍板)**：docs/f60-batch3-admin-key-governance-design.md
  (能力组 scope,案 A 推荐;过期提醒;拍板 4 项)。
- **F19 账单对账契约先行稿(#293,#291,待样本)**：docs/bill-reconciliation-contract.md
  (canonical JSONL v0+四级匹配+四态报告+异步端点契约)。
- **codegen 小步 3/4(#285/#287,#284/#286)**：Grant/ToolImport*/UsageDeletionRequest/Usage*
  嵌套族/CreateApiConsumerResponse(后端改具名记录建模)迁 hub；types/api.ts 仅剩两个刻意
  保留 interface——迁移线收口(#246)；typecheck 脚本改确定性(清 tsbuildinfo,CI 抓到本地假绿)。
- **typecheck 空转修复（#281，#280）**：`vue-tsc --noEmit` 对 project-references 壳零检查 →
  脚本改为显式检查 app/node 两个子项目；**被假绿灯掩盖的 360 个积压错误清零**（导入源漂移 +
  hub 可选字段收窄），并暴露 2 个真 bug：toast 自动消失回调引用未定义 `dismiss`（toast 永不
  自动移除）；`clearMcpAccessGrants` 第二参被 `del()` 静默丢弃 → 工具级 ACL 重置一直在重置
  服务级（改走 `?toolId=` query）。验证：tsc 双项目 0 错、vitest 162/162、CI 前端 job 现跑真实
  typecheck 全绿。
- **codegen hub 小步 2（#279，#278）**：McpToolRevisionRow/ModelCatalogRow/UserProjectMembership/
  MemberView/McpHeaderCondition 五个手写 DTO 迁 hub（后端返回类型逐一核实后别名）；
  消费方 6 视图 + 3 spec 换导入源；顺手清 #273 遗留的 NextUsersView.spec 陈旧 AdminUser 导入。
- **admin 用户契约修复（#273，#272）**：/api/v1/admin/users 改返 AdminUserView（domain User 去
  passwordHash）——OpenAPI 契约不再公开哈希（原 schema 含 passwordHash、运行时靠 mixin 隐藏，
  文档违约红线）；spec 测试加防回归断言；FE AdminUser/UserCreatedResponse 迁 hub。
- **依赖审计清零（#275，#274）**：Security gate 因 2026-09-09 新公告失败（vitest ≤4.1.10 /
  js-yaml 4.0.0–4.3.1）→ vitest ^5.0.0（零配置破坏，163/163 绿）+ js-yaml overrides；
  npm audit 0 漏洞。
- **F60 批 2 v2 Virtual Key 委托创建（#268，#263）**：机器密钥代指定用户建钥——案 1 语义
  （钥归属目标、成员校验按目标执行、SYSTEM_ADMIN 目标豁免；委托人须现行 SYSTEM_ADMIN），
  审计 actor=委托人+summary targetUserId；ADR-0016 增补、api-contract §9、示例集补委托段；
  集成 6/6 + 自服务回归 8/8。
- **codegen 第一步（#269，#265，方案 B）**：/auth/* 成功体 @ApiResponse 显式 content schema
  （auth 信封出盲区）；FE LoginResponse/UserResponse 迁 generated-api hub。

### 2026-09-07
- **平台 OIDC + rc.6（2026-09-08）**：平台 OIDC 登录（ADR-0017，#258，授权码 RP+映射/自动建号，等平台 client）；平台申请单（#260）；usage 空态合计行微修（#262）；rc.6 tag/Release。F60 v2 委托建钥设计稿（#264）待圈案 1/案 2（#263）。
- **F60 写面 v1 + 批 2 契约/示例 + rc.4（2026-09-08）**：ADR-0016 Accepted(A+C)——开放面告警规则/Webhook 全生命周期机器写(C)、导出创建委托=发行管理员(A,ISSUER_ATTR)、机器通道 CSRF 豁免语义;curl/Python 示例集与最小权限建议(scripts/open-api-examples/);OpenAPI 基线再生并同步前端类型;tag 0.1.0-rc.4 + 中文 Release。
- **Pre-release 0.1.0-rc.3（2026-09-08）**：rc.2 后合入 #225-#236（导出口径列/Webhook 成功率卡/codegen 修复/F16 工具版本管理+UI/F17 OpenAPI 导入/F18 模型人工兜底+UI）；OpenAPI 基线再生并同步前端生成类型；progress 交接点更新。tag `0.1.0-rc.3` + 中文 Release。
- **登录稿最终打磨（#212）**：对照权威稿收口轮——hero 标题收紧不折行、能力卡改回纵向单列带说明、按钮改紫→蓝渐变呼应 portal、portal 光晕/拱门内光加强、白面板微渐变、底部两卡强化容器感、分隔线淡彩、占位符对比度提升（视觉评审 7.5→8.5，文案与 testid 零改动）。
- **文档收口轮（#216/#218 及本行，2026-09-07 下午）**：issue 纪律入 git-workflow §3b（一个 PR 一个 issue，#209）；backlog 卫生（#216：管理开放 API F59→F60 撞号修正、F34 Kafka 与 F59-留痕按交付事实校正 DONE）；F01 MCP 调用代理经代码核对转 DONE（#218：McpProxyController 数据面+三套测试实锤）；大厂「做了没做」三类对照入 feature-expansion-candidates；OpenAPI 基线刷新至当前契约（落后 #166 起）；pre-release **0.1.0-rc.2** 打标（rc.1 之后合入 #204/#209/#212/#214/#216/#218）。
- **登录页文案中文化（owner 方向：中文产品）**：设计师稿遗留的英文文案（hero 标语/标签/按钮/卡片/页脚）全部改中文,语义保留;品牌与供应商名除外;hero 标语收紧为七字对仗（网关悄然运行。/ 密钥由你掌控。）以适配窄列不折行,行高按 CJK 放宽;spec 文案断言同步。

- **开放管理 API（F60）立项与 ADR-0015 Accepted**：机器凭据（`mqk_admin_`、SHA-256 摘要、过期/吊销）+ 只读开放面批 1（#198/#199/#200）。详细：V32 `admin_api_keys`；`POST/GET /api/v1/admin/api-keys` 与 revoke（审计两事件）；`/api/v1/admin-api/**` Bearer 过滤（门户会话放行、吊销即时）；开放读端点 usage summary/records。批 1b/2 与批 3 见 docs/open-admin-api-plan.md。
- **开放管理 API 批 1b 读面（本批）**：`/api/v1/admin-api/**` 只读子集全开——audit-events（审计链尾,与 `GET /api/v1/admin/audit-events` 共享新 AuditEventReadService）、api-keys（视图列表,digest 永不外泄）、quota-rules（计划+当期水位）、export-tasks（仅元数据,SQL 不读 file_bytes）、mcp-access-logs（同会话端参数/窗口校验）；**会话硬化 + 机器通道修复**：SessionFilter 豁免 `/api/v1/admin-api/**`（批1 机器通道此前被会话过滤器前置 401 拦截、从未端到端生效——首个集成测试暴露）；开放面仅 SYSTEM_ADMIN 放行（403 `ADMIN_API_FORBIDDEN`）且会话租户即开放面租户（修 batch1 会话路径租户属性缺失的隐患）；首个开放面端到端集成测试（机器密钥全端点 200、跨租户隔离、吊销/过期即时 401、审计光标、非管理员会话 403、人类端 audit 端点回归）。写面扩展草案 ADR-0016（机器执行者语义,Proposed,等拍板）。
- **docs 收口**：progress/CHANGELOG/database-schema/api-contract 全量同步至 #200；ADR-0015 状态 Accepted。

- **用户管理筛选器 #189**：搜索（用户名/昵称）+ 角色 + 状态 + 命中/总数,纯前端带测试。
- **用量时间范围 #191**：个人与管理用量页 默认/近7/近30/近93,预设即带 from/to（导出同步）;默认语义零变化。
- **codegen 收尾 #190/#193**：route-rules 三件套与 WebhookDelivery 全部迁 OpenAPI schema（含命名映射备注）。
- **口径提示条 #193**：用量页可关闭提示（本地即时记账 vs 供应商 T+1）。
- **设计师登录稿 #194**：按权威设计图实现（暗色网关传送门 hero + 白色认证面板;登录文案 EN 按稿,注册/错误仍中文;功能与测试不变）。大厂扩展候选清单入库 docs/feature-expansion-candidates.md。
### 2026-09-06
- **UI 视觉母版 v3（#173，Vben console edition）**：owner 指令把母版从 PostHog 切到 Vben Admin 观感。tokens v2.1（冷画布 #f0f2f5、antd 蓝 #1677ff、深海军蓝导航轨 #001529、hover ≥7%、lg 控件 40px）；NewShell 深色轨 + 分组面包屑（普通页不再重复标题）；UiTable 表头 muted 底 13/600 + 正文 14；登录页 Vben 式左右分屏（#2a5ad7 品牌板 + 白表单列、下划线 tab、44px 控件）；用户页（页头汇总/角色徽标/kebab 控件化）与用量报表（统计卡组/筛选行/Request ID 截断/分页右对齐）结构性打磨；总览页（去空洞欢迎语、统计卡图标徽章、成本分布独立卡、账本空态）。frontend-design.md 修订 v3 段。

- **ADR-0012/0014 Accepted + 留痕 R1 配置面（V31）**：Kafka 引入与内容留痕管道获所有者批准（v3 默认值，P1–P8 已裁决）。V31 新增 `retention_config`（租户级默认关开关，快照下发即时生效）与 `user_identity_link`（平台 OAuth 映射骨架）；管理 API `GET/PUT /api/v1/admin/retention-config`（审计 + 即时刷新）。网关密文采集/信封、Kafka producer、消费端参考实现为后续批次。

- **ADR-0014 R2 网关留痕侧信道**：租户开关开启时，代理请求体的用户消息文本被抽取并以密文信封（envelope: 元数据明文 + AES-GCM 密文载荷，正文仅存在于抽取与加密之间）经有界队列交给 publisher（默认 no-op，fail-closed）；系统提示/工具/模型回复永不在范围；上游字节零改动。测试：抽取矩阵 4 + 端到端 2（开关生效/关闭零采集、上游原样）。

- **ADR-0014 R3 Kafka producer（本批）**：标准 Kafka 协议投递信封到 `content-retention` topic（记录键 = SHA-256(tenant/user)，同用户恒同分区保序）；信封 JSON 只带 base64 AES 密文/IV，明文不出网关；`miqrokey.retention.kafka.bootstrap-servers` 配置后经 @Primary 替换 no-op（默认仍 fail-closed）；发送异步、失败节流计数。测试：payload/partition-key 单测 2 + Redpanda 容器端到端 1（真实 broker 收信、分区亲和、密文语义）。

- **ADR-0014 R4 消费端参考实现（本批）**：`docs/retention-consumer.md` 定义消费端契约（topic/键语义/at-least-once + eventId 幂等/信封 JSON schema/解密说明 RETENTION_AAD_ID/本地文件布局 `<tenant>/<user>/<YYYY-MM-DD>.jsonl`）；`scripts/retention/consumer-file-ref.py` 为平台侧可照抄的 kafka-python 参考消费者（批量落盘后 commit、跨重启去重、--dry-run 联调）。产品门禁不执行 Python；逻辑冒烟已过。
- **UI 临摹轮 #179（Vben 精仿）**：登录页白卡场景插画（内联 SVG，无渐变）；面板圆角 12px + 发丝浮起；总览统计四张独立卡 + 四色图标徽章；审计规则升级（radius-panel / --ui-shadow-card 豁免）。owner 规则：学习只动视觉皮，产品文案逐字保留（此前两处误改已还原）。
- **codegen 收尾 #180**：RoiReportView 迁至 OpenAPI schema 类型（手写接口删除，hub 别名统一；旧 spec-缺口记录已过期）。
- **#181 Key ID 复制入口**：keys 行内复制钮（clipboard + 回退，toast 反馈）。

### 2026-09-05
- **F15 MCP 元数据访问日志（V29）**：MCP 代理（F01）每次身份可解析的调用落一条纯元数据审计行（`mcp_access_log`：租户/服务/消费者/方法/工具/终态/HTTP 状态；网关异步有界队列批量写入、`(tenant, gateway_request_id)` 幂等、饱和 drop+计数）；管理端查询 `GET /api/v1/admin/mcp-access-logs`（service/consumer/窗口 ≤31d/limit≤1000 过滤，SYSTEM_ADMIN-only）。工具参数与响应正文永不入表。
- **F12/F13 MCP 韧性（V30）**：MCP 代理出口新增**重试门禁与熔断**（均默认关闭；路由快照承载配置，改后即时生效）。重试=首字节前、条件可选（5xx/连接失败/超时）、1–5 次、POST/PUT/PATCH 工具需显式幂等确认；熔断=三态状态机（滑动窗口+最小请求数+错误比例/慢调用双触发+半开探测恢复），按工具桶隔离，OPEN 期间 503 `circuit_open` 快速失败。管理 API `GET/PUT /api/v1/admin/mcp-services/{id}/resilience`；F15 日志新增 `CIRCUIT_OPEN` 终态。腾讯 doc 134831/134859。

### 2026-09-04

- **管理员「加入项目」快捷入口**：`GET /api/v1/admin/users/{id}/project-memberships`（用户所属项目，按 code 排序）；用户列表菜单新增「项目成员」→ 抽屉展示当前项目（可移除）与可加入的 ACTIVE 项目（下拉+加入），注册用户引导闭环（F-REG）。
- **MCP 路由规则**（F11，腾讯 doc 135482，V28）：每服务 default 兜底路由（不可改删禁，注册自动生成+存量回填）+ 自定义优先级规则；Path/Host/方法/Header 条件 AND 匹配（RE2 正则、全匹配、不读正文）；冲突实时校验（同服务已启用规则匹配面等价 → 409 含冲突名；重新启用再校验）；启停幂等、更新为全量替换、删除/服务级联清理；domain 纯函数 `McpRouteRules` 供数据面复用（匹配器+冲突面）；前端 MCP 服务页「路由规则」抽屉（默认徽标、规则列表、新建/编辑表单、删除门）；依赖新增 re2j 1.7（BSD-3）。
- **UI 专项收官**：tdesign-vue-next 全量移除（main.ts 全局注册退役，产物 -1.18MB / gzip -326KB）；部署信息页 v2 化（唯一遗留 TDesign 时代路由页退役）；e2e 审美审计覆盖 v2 设计层；ui-layer 守卫防回归。


### G7.x — 对照腾讯云 / 阿里云 AI 网关能力

- **G7.1 上游凭证门户**：凭证列表（掩码+指纹）、创建（Secret 可见性切换）、测试 Secret（纯校验）、轮换、禁用、版本历史抽屉；修复 Credentials 导航死链。
- **G7.2 模型单价目录**：`/api/v1/admin/prices`（追加式快照、修改不追溯）；前端定价页（产品/模型/类型/单价/来源）。
- **G7.3 成本报表页**：按项目/按天成本分摊视图、7/30/93 天窗口、缓存节省卡、CSV 导出。
- **G7.4 响应缓存**（ADR-0009，对齐腾讯 L1 方案）：Caffeine L1 + PostgreSQL L2（不引 Redis）、双重 opt-in（Key cachePolicy + X-MiQroKey-Cacheable）、工具调用永不缓存；缓存键升级为 system + 最后一条 user 消息（对齐腾讯/阿里键策略）。
- 前端：Element Plus → TDesign 全量迁移（含 CDN 图标改本地 SVG）；bundle 拆分（入口 1.46MB → 15KB）；UsageView 导出 CSV；部署信息页。
- 修复（自测发现）：登录提交链路失效、t-drawer 标题/默认 footer、DialogPlugin.confirm 非 Promise（危险操作确认前即执行，全站修复）、jsdom 缺 ResizeObserver 等。
- 工程：CI 拆分 6+ job + 路径过滤 + CodeQL + npm audit；CodeRabbit / Dependabot / OSSF Scorecard / Stale；Issue 模板 / SECURITY.md / 标签体系；GitHub 公开 + MIT。

### 治理闭环（2026-09-02：#118–#120）

- **模型申请审批流**（原始设计文档 §8.2/§13 P6.1，V22）：用户给 Virtual Key 申请授权外模型 → 管理员审批中心通过/驳回（keySet 游标队列）；通过即写入 Key + Grant 模型集并立即刷新路由快照；白名单模型（`MIQROKEY_APPROVAL_WHITELIST_MODELS`）自动批准；乐观锁防重复审批；审计三事件。
- **配额规则**（roadmap「配额管理」行，V23）：用量配额（用户/项目 × Token/请求次数 × 日/周/月 UTC 窗口 + 预警阈值），管理面 CRUD 与实时水位（NORMAL/WARNING/EXCEEDED，只预警不阻断）；前端配额规则页（水位条 + 门禁删除）。
- **配额水位告警**（V24）：`QUOTA_THRESHOLD` 告警类型（scope=配额规则，按规则重置窗口去重）→ 事件 + 签名 Webhook；规则停用即停止评估。roadmap 配额管理行至此闭环。
- **缓存 ROI 报表**（原始设计文档 P5.4）：`GET /api/v1/admin/usage/roi` 窗口 + 逐日实付/节省/命中率/等效折扣（共享聚合器派生，按单价快照计价）；前端缓存 ROI 页（统计卡 + 逐日表 + CSV 导出）——G7.4 缓存收益的数据化。
- **MCP 两级访问控制**（腾讯 doc 134890，V25）：服务级 ACL（NONE/ALLOW/DENY × API 消费者名单）+ 工具级覆盖（服务全开放时配置，只能进一步收窄）；前端 MCP 服务页「访问控制」；判定策略 `McpAccessPolicy` 纯函数（调用代理接线后生效）；审计三事件。
- **默认配额模板**（腾讯 doc 135489，V26）：全局模板（每租户一份）+ **创建时快照复制**——启用后每个新建用户自动获得一条 USER 作用域配额规则（warn 80）；改模板不惊动已分配、停用不删已分配、手动规则优先（insert-if-absent）；`AdminOrgService.createUser` 事务内复制并审计 `auto:true`；前端配额规则页「默认配额模板」面板（状态/定义/三条提示文案/配置与启停）。
- **模型审批 Webhook 通知**（F03，V27）：告警框架新增三个**事件驱动**规则类型 `MODEL_APPROVAL_SUBMITTED/APPROVED/REJECTED`——审批提交/通过/驳回瞬间即时触发（非周期评估），复用同一签名投递/指数退避重试；payload 带审批明细（approvalId/申请人/Key/模型/理由/意见/autoApproved，纯元数据）；白名单自动批准一次提交触发双事件；投递原语抽取为共享 `AlertEventDispatcher`（评估器与审批流共用）；前端告警规则页三类型选项（事件型隐藏阈值/去重输入）。
- **用户自助配额可见性**（F04）：`GET /api/v1/me/quota-rules`——调用者名下 USER 作用域配额规则 + 当前窗口实时水位（含模板自动规则，停用仍可见），只读不分页、其他作用域绝不出现；前端用量页「我的配额」面板（维度/限额/本期用量/水位条/状态徽标，空态提示）。
- **过期记录定时 GC**（F06）：定时回收（`MIQROKEY_CLEANUP_EXPIRED_SWEEP_MS` 默认 1h）下载窗口已过的导出产物（`SUCCEEDED` 超 `expires_at` 连同 `file_bytes` 删除，FAILED/PENDING 保留查看）与确认窗口已过的删除请求（PENDING_CONFIRMATION/CONFIRMED/EXPIRED 删除，**EXECUTED 永久保留**——执行审计）。
- **usage 队列饱和应急直写**（F35，architecture §5）：`MIQROKEY_GATEWAY_QUEUE_SATURATION_MODE` 默认 `DROP`（行为不变）；置 `WRITE_THROUGH` 时队列满的事件经专用 writer 执行器单条幂等直写、发布线程有界等待（`MIQROKEY_GATEWAY_QUEUE_WRITE_THROUGH_TIMEOUT` 默认 5s）——审计完整性优先，JDBC 仍只在 writer 执行器，超时/失败照旧计数丢弃、发布线程永不无限阻塞。
- **管理门户 IP 白名单**（F05，security §6）：`MIQROKEY_CONTROL_ADMIN_IP_ALLOWLIST`（CIDR，空 = 不限制）——配置后门户面仅名单内来源可达（403 `IP_NOT_ALLOWED`），billing 外部通道与 bootstrap 引导豁免；`MIQROKEY_CONTROL_ADMIN_TRUSTED_PROXIES` 声明受信反代，只有其 `X-Forwarded-For` 被采纳（直连无法伪造头绕过）；非法 CIDR 启动失败；纯函数 `IpCidrMatcher`（v4/v6）。
- **账号自助注册**（F-REG）：`POST /api/v1/auth/register`（公开端点，注册即登录，USER 角色；重名 409 USERNAME_TAKEN / 弱密码 400 PASSWORD_INVALID / `MIQROKEY_REGISTRATION_ENABLED=false` 时 403 REGISTRATION_DISABLED）；登录页重做为双模式卡片（登录/注册页签 + 账号/昵称文案统一）+ 布局整改（对称双栏、卡片浮起、控件 40px、focus 环、额度条入卡）——按视觉模型评审意见修正。
- **OpenAPI 3.1 生成 + CI 破坏性变更检查**（F09，api-contract §8 契约收尾）：springdoc 接入 Control Plane（无 swagger-ui），`GET /v3/api-docs` 输出 3.1.0（105 paths / 89 schemas）；Info 元数据 + 四类鉴权 scheme 建模（门户 Cookie/CSRF/外部 API Key/JWT，不强制任何操作）；机器可读基线 `docs/openapi/openapi-3.1.json` 入库；CI backend-integration job 对生成结果跑 `deploy/openapi/check-openapi-breaking.py`（删 path/op/response/参数或属性变 required 即红）。前端 TS client 仍手写（codegen 列发布前候选，document-map §3 注明）。
- 全局修复：请求体 JSON 解析失败统一 `400 PARAM_INVALID`（含字段名提示，此前 500）。

### G8.x — 平台中间件 P0/P1（外部系统通道与预算告警）

- **G8.1 消费者 API Key + JWT 双认证通道**（ADR-0010/0011，对齐阿里消费者认证）：`api_consumers` 表（Key 仅存 SHA-256 哈希）；`/api/v1/billing/**` 对外通道（summary/records 全租户用量、仅元数据）；JWT 可选 RS256 验签公钥（平台私钥签发，JDK 原生验签零三方库，exp/nbf/size 校验）；Key 轮换/吊销即时失效；消费者管理 UI（一次性 Key 弹窗/吊销）；配额状态端点 `GET /api/v1/billing/quota`（订阅分组最新快照，外部视图不暴露内部字段）。
- **G8.2 项目月度预算**：`budget` 管理面落地（水位 = 当月分摊成本实时计算，NORMAL/WARNING/EXCEEDED 三态）；成本页「月度预算」面板（汇总水位条 + 每项目编辑/删除）。只告警不阻断。
- **G8.3 预算水位告警**：`BUDGET_THRESHOLD` 规则类型（scope=项目、阈值百分比、同月按（规则×月份）去重一次）→ 事件 + HMAC 签名 Webhook 投递；项目不存在/跨租户 400。
- 测试基建：共享 Testcontainers 连接耗尽修复（测试池降至 10 + 容器 `max_connections=200`）。

### P2 SkillHub — 技能目录（Anthropic Agent Skills 格式）

- P2.1 形态调研（存档于 platform-middleware-roadmap.md）：格式采用 Anthropic Agent Skills 规范（SKILL.md frontmatter），分发对标腾讯 SkillHub 应用商店模式。
- P2.2/P2.3 后端（V16 `skills`/`skill_access`）：上传 zip 校验（单根目录、name 与目录一致、保留词禁令、5MB/200 条目/512KB 上限 zip 炸弹防护，只读 SKILL.md 不解压其余）；目录公开（全部 ACTIVE 可见）+ 下载双层授权（无授权行=公开、TEAM/PROJECT 成员、管理员绕过）；上传 upsert/归档/授权整体替换（修复 upsert 硬编码 ACTIVE 覆盖归档的真实缺陷）。
- P2.4 前端：SkillHub 浏览页（全员：卡片网格 + 下载门禁 403 友好提示）+ 管理上传页（zip + 语义化版本、授权弹窗）。

### P3 内部治理 — Agent / 服务 / 全局配置 / MCP

- P3.1 Agent 管理（V17）：绑定 ACTIVE 凭证（产品由凭证→订阅派生）、重名校验、禁用乐观锁、按凭证聚合用量；凭证轮换/吊销后 Agent 自动失效（级联 RESTRICT）。
- P3.2 服务注册（V18）：内部服务目录（HTTP/MCP/OTHER），base_url 校验镜像上游目标规则（https 必选、无 userinfo/query/fragment）、重名 409。
- P3.3 全局配置中心（V19）：分组键值 + 乐观 version upsert；名称白名单规则；仅非机密配置（机密走 env/加密凭证体系）。
- P3.4 MCP 服务管理（V20，对标腾讯 MCP 管理）：传输/上下线/健康三态；`McpHealthChecker` 定时 15s 探测 ONLINE 服务（GET 2xx 计健康、连续失败/成功达阈值翻转 UNHEALTHY/HEALTHY，手动下线不被健康检查覆盖）；失败恢复计数器。
- P3.5 MCP Tools 管理（V21，对标腾讯 Tools 管理）：工具注册（snake_case 名唯一标识/描述/方法/路径）+ 逐个启停 + 同服务重名 409；绑定服务级联删除。

### 真实联调、研究与发布状态

- **DeepSeek 官方 Key 全链路联调（2026-08-30）**：bootstrap→凭证→Grant→Virtual Key→真实推理（`MQROK-DRILL-OK`）→用量落库（含 cacheCreation）→成本精确断言全通过；修复真实容器缺陷（SessionFilter order 先于 RequestContextFilter 导致带 session 请求 500）+ 新增 `CatalogSeedService`（启动幂等 seed 8 供应商/23 产品，URL 只来自签名目录）。
- **腾讯云 AI 网关 30 篇文档研究**（入库 `docs/tencent-ai-gateway-study/`）：A 类 15 项元数据级设计可直接借鉴（MCP 两级 ACL、消费者默认配额快照复制、Agent 服务分离、Tools 版本/重试/熔断、模型探测、操作记录等）；B 类 4 项（参数改写/流量镜像/脱敏/包体采集）与「不读正文」产品决策冲突仅对照。
- **发布状态**：代码版本 0.1.0-SNAPSHOT / 前端 0.1.0，从未打 tag；23 产品真实凭证契约测试全部 `WAITING_FOR_CREDENTIAL`（禁止标记 VERIFIED）；G6.5 收尾后为发布候选基线，正式版本号与 tag 由发布负责人授权后记录。

## [0.1.0] — 2026-08-26（首个候选版本，未标记 VERIFIED；从未 tag/发布，历史归档）

### Phase 0 — 工程基线（G0.1–G0.4）

- Maven wrapper 校验与 Windows/Linux 可复现构建；配置对齐 `configuration-reference.md`
- ArchUnit 模块依赖规则、Enforcer、Spotless、固定依赖版本
- API 契约、数据库 schema（Flyway V1–V8）、运维 Runbook、发布清单、安全基线文档

### Phase 1 — 领域与安全核心（G1.x）

- Virtual Key：`mqk_live_` 格式、HMAC 摘要存储、一次性显示、租户绑定、恒定时间比较、key 轮换
- 上游凭证：AES-256-GCM（AAD 绑定租户+凭证）、key ring 轮换、掩码视图、验证/轮换/禁用生命周期
- 会话安全：渐进锁定、CSRF（SHA-256 digest 比对）、Origin 校验、SameSite/HttpOnly Cookie
- 审计哈希链（`admin_audit_events`，previous/current hash + chain_position）

### Phase 2 — 网关数据面（G2.x）

- 路由快照 + Virtual Key 鉴权（多凭证头 401、防枚举）
- `/v1/models` 四路交集（目录/上游模型/Grant/Key 快照）
- 请求生命周期记录（IN_FLIGHT → 终态、幂等 flush、`usage_missing` 显式标记）
- 有界 usage 队列（容量/指标/告警，drop 不静默）
- 四层超时 + 首字节前至多重试一次 + 慢客户端内存有界
- SSRF 双重门控、路径白名单、Header/body 上限、错误脱敏

### Phase 3 — 供应商适配器（G3.1–G3.8，23 个产品，全部 IMPLEMENTED / WAITING_FOR_CREDENTIAL）

- DeepSeek PAYG（官方余额 OFFICIAL_API）
- Tencent TokenHub 5 产品（Coding Plan / Token Plan 个人版 / 企业专业 / 企业轻享 / 按量）
- Zhipu GLM 3 产品（个人/团队 Coding Plan / 按量，`PER_SEAT_KEY`）
- MiniMax 3 产品（个人/团队 Token Plan / 按量，`PER_MEMBER_SUBSCRIPTION_KEY` + 共享 Credits）
- Moonshot/Kimi 2 产品（Kimi Code 会员 / 按量，按量官方余额 OFFICIAL_API）
- Baidu Qianfan 3 产品（Coding Plan / Token Plan 个人版 / 按量）
- Volcengine Ark 3 产品（Coding Plan / Agent Plan / 按量）
- Aliyun Bailian 3 产品（Coding Plan / Token Plan 团队版 / 按量）
- 共享基础设施：`TokenUsageParser`（双形状 + `prompt_tokens_details.cached_tokens`）、`TransparentResolve`、`HttpProviderClient`（SSRF 门控/超时/1MB 上限）、编译期注册

### Phase 4 — 控制面服务（G4.x）

- 用量统计/成本分摊（价格快照）、导出（gzip+SHA-256）、双确认删除
- Webhook 签名投递（指数退避、去重）、告警规则（usage 缺失率/上游错误率/余额不可用/用量激增）

### Phase 5 — 门户（G5.0–G5.5）

- Quiet Operations Console：tokens、应用 shell、PageHeader、mk-status、baseline 截图
- 用户门户（登录/改密/Key 全生命周期/个人用量）+ 管理门户（用户/团队/项目/Grant/产品/订阅/席位/导出/删除/Webhook/告警/审计）
- UI 安全与可访问性（管理路由守卫、no-store 防缓存、focus-visible、aria 标签）
- Playwright 生产构建 baseline（12 项）+ vite 冷启动根治

### Phase 6 — 交付（G6.1–G6.4；G6.5 发布收尾见 [Unreleased]）

- Observability：`monitoring`/`json` profiles、Prometheus 指标（低基数标签禁令）、Logstash JSON 日志、Grafana dashboard
- Backup & Restore：加密备份（AES-256-CBC+PBKDF2+manifest）、校验、恢复、真实恢复演练 PASS
- Supply-chain gate：Secret 扫描（修复 23 处文档示例 Key）、CycloneDX SBOM + 许可证门禁、Trivy 镜像扫描（驱动 postgres 镜像 digest 升级）
- Performance & soak：并发流浸泡测试 + 生产 soak 脚本
- 本版本：**未标记 VERIFIED**（无真实供应商凭证契约测试，`WAITING_FOR_CREDENTIAL`）

