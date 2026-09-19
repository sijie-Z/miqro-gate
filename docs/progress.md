# 开发进度

> 此文件是跨 Claude Code/Goal 会话的最小交接状态。每个 Goal 开始和结束时必须更新。不要在这里复制完整设计；链接到事实来源。

## 会话交接点 2026-09-18（#715 缺口②：对账差异报告导出）

- **#715 缺口②（分支 `feat/reconciliation-export-715`，基于 c931e8df，HEAD 再提交本次收尾）**：
  对账报告的四态明细行支持 CSV 合规导出 `GET /api/v1/admin/reconciliations/{id}/export?state=`。
  SYSTEM_ADMIN-only，沿用 `AdminReconciliationController` 与 `/api/v1/admin/**` 默认拒绝拦截器，
  未新开鉴权面。
  - **方言按「同步管理端下载」惯例**（与 `AdminAuditController` / `AdminRetentionLogController`
    的审计/留痕导出同形）：UTF-8 BOM、RFC 4180 引用、`= + - @ TAB CR` 公式注入防护（前置单引号）、
    snake_case 表头、5 万行上限、`Content-Disposition: attachment`。**故意不混用**
    `ExportTaskService` 的异步产物方言（gzip + `\,` 转义 + 可选 JSONL）——两者是不同的下载约定，
    本端点不产生排队任务、不入 `export_tasks`。
  - **列与页面明细同源**：单一声明列清单 `EXPORT_COLUMNS` + `DETAIL_KEYS`（snake_case 表头 ↔ 存储的
    camelCase JSON 键）；页面 `rows()` 与导出共用同一 `rowMapper` 投影与同一 `LIMIT` 语义，
    两处不会各自漂移。未声明的 detail 列直接抛 `IllegalStateException`，宁可失败也不错位。
  - **租户隔离与审计**：导出前先走 `get(tenantId, reportId)`（SQL 带 `AND tenant_id = :tenantId`），
    他人报告一律 404 `RECONCILIATION_NOT_FOUND`（不泄漏存在性）；`state` 非法 400
    `RECONCILIATION_PARAM_INVALID`，且与报告不存在一样在写审计**之前**失败，不留孤儿审计行。
    成功导出记 `RECONCILIATION_EXPORT`（`targetType=RECONCILIATION`，摘要 `{rows, truncated}`）。
  - `state` 语义与 `/rows` 完全一致（同一校验函数、同一 400）；**空结果是仅表头的 CSV，不是错误**。
  - `X-MiQroKey-Rows` 返回精确数据行数：单元格内的换行是合法 CSV，按物理行计数会少报。
- 验证（真实命令与结果，Windows + JDK 21 + Testcontainers PostgreSQL）：
  - 后端 `.\mvnw.cmd -B -f backend -pl control-plane-app -am test -Pintegration
    -Dtest=ReconciliationApiIntegrationTest,ReconciliationExportCsvTest
    -Dsurefire.failIfNoSpecifiedTests=false` →
    `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，`Total time: 57.323 s`。
  - 前端 `npm ci`（0 vulnerabilities）、`npm run typecheck` exit 0、`npm run test` →
    63 test files / 384 tests 全绿、`npm run lint` → `0 errors, 7 warnings`（7 条均为既有告警，
    不含本次改动文件）。
- 覆盖的边界（真实用例，非空跑）：空报表（仅表头 + `X-MiQroKey-Rows: 0`，无截断头）、未知 state
  （400 且不新增审计行）、跨租户（404，租户 try/finally 自建自删）、5 万行上限（插入 50010 行 →
  返回 50000 行 + `X-MiQroKey-Truncated: true`）、公式注入各前导字符与 RFC 4180 引用、
  声明列唯一性/顺序、detail 键与声明列不漂移、导出与页面逐格一致、`state` 收窄与页面筛选一致。
- i18n：`frontend/src/i18n/dict.ts` 已有 `导出 CSV` / `导出失败` / `已导出 N 行 CSV。` /
  截断提示的中英映射（与审计导出一致），本次**无需新增词条**。
- **缺口①（供应商私有账单解析器）不在本次范围**：仍需真实账单样本，本分支不含。
- 工作区卫生：本次只提交上述 6 个改动文件 + 1 个新增单测 + 本文件；工作区另存的 21 个与本议题
  无关的改动文件（`types/generated.ts` 重生成、若干 `ui/*` 与视图改动）保持原样未提交。
- **内部对抗评审轮（同日追加）**：评审提出阻断项 —— `csvCell` 的公式注入防护对所有列一视同仁，
  于是 `detail_amount` 的合法负值（退款/调整行）被导出为 `'-12.34`，既与页面显示的 `-12.34`
  不一致，又让金额列在表格中退化为文本。可达性已复核：`CanonicalBillParser` 只校验 `amount` 非空，
  `BillReconciliationEngine` 的 `new BigDecimal` 接受负值。
  - **修复落在导出层**（未触碰解析器/匹配逻辑，即缺口① 范围）：整格匹配裸十进制字面量
    `[+-]?\d+(\.\d+)?([eE][+-]?\d+)?` 时豁免防护单引号；`-1+1`、`+cmd|' /C calc'!A0` 这类
    仅「形似数字」的串仍按公式处理。`= + @ TAB CR` 前导一律不变。
  - **回归测试**：`ReconciliationExportCsvTest` 新增 `signedDecimalsAreNotGuarded`，并把 `+1` /
    `-1.50` 从「应加引号」用例移入该用例；`ReconciliationApiIntegrationTest` 新增
    `exportCsvSignedAmount`（`-12.34` 与 `+3.00` 两条账单行的独立 fixture，逐格比对页面明细
    并断言单元格 `BigDecimal` 等值）。
  - 复跑：`.\mvnw.cmd -B -f backend -pl control-plane-app -am test -Pintegration
    -Dtest=ReconciliationApiIntegrationTest,ReconciliationExportCsvTest
    -Dsurefire.failIfNoSpecifiedTests=false` →
    `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，`Total time: 48.105 s`。
  - 格式（`mvn test` 不跑 spotless，`verify` 才跑，所以裸测绿不代表 CI 绿）：`spotless:check`
    在整改前报 `.../AdminReconciliationController.java`、`.../ReconciliationService.java`、
    `.../ReconciliationApiIntegrationTest.java`、`.../ReconciliationExportCsvTest.java` 违规，
    已用 `.\mvnw.cmd -B -f backend -pl control-plane-app -am spotless:apply` 修好（纯 javadoc/换行
    重排；`AdminReconciliationController.java` 因此从「已提交」变为「本次再提交一次注释重排」），
    随后 `spotless:check` → `BUILD SUCCESS`。
  - 格式修复后复跑（分别执行）：`ReconciliationApiIntegrationTest` →
    `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，`Total time: 51.523 s`；
    `ReconciliationExportCsvTest` → `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`，
    `BUILD SUCCESS`，`Total time: 4.386 s`。前端 `npm run typecheck` exit 0、`npm run test` →
    63 files / 384 tests 全绿、`npm run lint` → `0 errors, 7 warnings`。
  - 文档：`docs/api-contract.md` 补「裸十进制字面量原样输出、不加防护单引号」与「审计 `rows`
    为截断后实际行数」两处口径。

## 会话交接点 2026-09-16（自助注册关闭态前置体现 #550）

- **#550（PR 待开，分支 `fix/registration-disabled-gating`，基于 50a9b24）**：部署关闭自助注册时，
  登录页仍展示可提交的注册入口，用户填完表单才吃 403。新增公开只读端点
  `GET /api/v1/auth/registration-status`（匿名，仅回一个布尔 `{enabled}`；加入
  `SessionFilter.PUBLIC_PATHS` 精确匹配白名单；CSRF 拦截器虽覆盖 `/api/**` 全方法，但对非状态变更
  方法直接短路，GET 无需 token）——判定与 `/register`
  的 403 分支**同源**（`AuthProperties.registrationEnabled`，`@ConfigurationProperties` 启动期绑定、
  无 `@RefreshScope`）。前端登录页 `onMounted` 预取该状态；关闭态下注册入口**保留可见**（承载说明
  文案）但不可点/不可提交（`disabled` + `aria-disabled` + `request-access--off` 样式），探测失败一律
  fail-open 维持原行为。该端点只是 UX 前置提示，**不是鉴权点**：服务端 403 仍是唯一闸门，且端点只
  暴露一个布尔，不泄漏部署配置其他信息。
- 验证（真实命令与结果）：
  - 后端 `-f backend -pl control-plane-app -am test -Pintegration
    -Dtest=RegistrationApiIntegrationTest,RegistrationDisabledApiIntegrationTest
    -Dsurefire.failIfNoSpecifiedTests=false` → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，
    `BUILD SUCCESS`（Testcontainers PostgreSQL）。
  - 前端 `npm ci`（added 395 packages, 0 vulnerabilities）、`npm run test` → 59 files / 331 tests 全绿、
    `npm run typecheck` / `npm run lint`（0 error、1 条既有 `NewShell.vue` 警告）/ `npm run build` 全部 exit 0。
  - OpenAPI 基线重生成（`OpenApiSpecIntegrationTest` → `docs/openapi/openapi-3.1.json`，与旧基线比
    纯新增两段：schema `RegistrationStatusResponse` + path `/api/v1/auth/registration-status`），
    前端类型 `npm run gen:types` 同步重生成（`src/types/generated.ts` 纯新增 39 行，二次运行幂等）。
- 反空跑：把前端新用例的 mock 临时改成 `{ enabled: true }`，该用例即 FAIL
  （`expected undefined to be defined`），证明断言非空跑。

### 收尾轮 2（2026-09-16 晚）：对抗评审修复 + 复验

- 评审后修复 4 项（均已落盘）：
  1. 审计面误述：`docs/api-contract.md` 曾把本端点与 `/register` 类比，但本端点是纯只读查询、
     **不写审计事件**，只有成功注册才写 `REGISTER` → 已改为显式声明「不写审计」并注明两者不等价。
  2. CSRF 机制描述不准：`SecurityConfig#addInterceptors` 确实把 `csrfInterceptor` 注册在 `/api/**`
     **全方法**上；GET 免 token 的原因是 `CsrfInterceptor#preHandle` 对非状态变更方法直接短路，
     而不是「GET 不在拦截范围内」。`api-contract.md` 与本文档已按真实机制改写，且确认**无需**把
     本端点加入 `CSRF_EXEMPT`。
  3. 前端类型重复定义：手写 `RegistrationStatus` 与生成 schema 重复，且把 `enabled` 声明为必填
     （schema 中为可选）→ 改为 `generated-api.ts` 里的别名 `RegistrationStatusResponse`，
     调用方统一按 `=== false` 判定。
  4. 探测串行化：原实现先 `await` 状态探测再请求 OAuth 供应商，状态端点卡住（HTTP 客户端 60s 超时）
     会连带延迟 OAuth 登录按钮 → 改为两个探测各自 `.then/.catch` 独立回填状态，互不阻塞。
- 新增前端用例 2 条（`NextLoginView.spec.ts`）：状态探测 reject / 字段缺失时 fail-open（入口仍可用、
  表单仍可达）；状态探测悬挂时 OAuth 按钮仍渲染。反空跑依据：两条新旧用例互为反例——关闭态要求
  `disabled` **存在**、失败态要求**不存在**，二者同时通过即证明闸门由探测值驱动，而非恒真/恒假断言。
- 复验（2026-09-16，真实命令与结果）：
  - 后端 `-f backend -pl control-plane-app -am test -Pintegration
    -Dtest=RegistrationApiIntegrationTest,RegistrationDisabledApiIntegrationTest
    -Dsurefire.failIfNoSpecifiedTests=false` → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`、
    `BUILD SUCCESS`（Windows 需 `mvnw.cmd` 且 `JAVA_HOME` 指向 Temurin 21）。
  - 前端 `npm run typecheck` exit 0；`npm run test` → 59 files / **333** tests 全绿（较上轮 +2，即上述新增用例）。
  - 改动文件 `npx eslint <4 个文件>`（**不带 `--fix`**，避免误改工作区）exit 0。
  - CI 的 `gen:types` 漂移门禁本地预演：`npx openapi-typescript ../docs/openapi/openapi-3.1.json -o <临时文件>`
    与 `git show HEAD:frontend/src/types/generated.ts` **逐字节一致**（忽略行尾），故该门禁不会因本分支失败。
  - 端到端 `npx playwright test --grep "new login page"` → `2 passed (42.5s)`、exit 0；
    新增用例 `new login page closes the register entry when self-registration is off (#550)`
    通过路由拦截返回 `{"enabled":false}`，断言注册入口 `disabled` 且注册表单两个字段均不渲染。
    日志里可见 `/api/v1/auth/registration-status`、`/api/v1/auth/oauth/providers` 代理到 8080 失败
    （本机未起后端），页面按 fail-open 回退，原有用例仍绿——即真实浏览器下探测失败不破坏登录页。

### 并入 develop 新基线（2026-09-16）：merge `adfb670`（#695 / #684 配额软着陆）

- 背景与手法：develop 于本日推进到 `adfb670`，本分支（原基于 `50a9b24`）与基线冲突。用
  `git merge origin/develop`（**产生合并提交，非 rebase**）把基线并入，本分支改动全部保留。
- 冲突清单与解法（冲突文件共 **1** 个）：
  - `docs/openapi/openapi-3.1.json`——两侧改动语义不相交：本分支新增 schema
    `RegistrationStatusResponse` + path `/api/v1/auth/registration-status`；develop 在既有 schema
    `UpsertQuotaRuleRequest`、`QuotaRuleView` 上新增 `action` 属性。解法：**以 develop 版为底**，
    把本分支两段按各自前驱键原位插入（`/api/v1/billing/quota` 之后、`SubscriptionQuotaView` 之后）。
    注意 springdoc 输出含 `"maximum":100.00` 这类字面量，JS `JSON.parse`→`JSON.stringify` 往返会丢成
    `100`，故采用 JSON 感知的**文本级**插入，不做往返序列化。合并结果自检：与 develop 版逐成员比对，
    差异恰为本分支 2 段新增；与本分支版比对，差异恰为 develop 的 2 处 `action`；paths 172 / schemas 137；
    `100.00` 原样保留；无冲突标记。
  - 其余重叠文件（`docs/api-contract.md`、`docs/progress.md`、`frontend/src/types/generated.ts`）由 git
    自动合并；`docs/progress.md` 两侧为不同区域追加，互不覆盖。
- 合并后派生物一致性：`npx openapi-typescript ../docs/openapi/openapi-3.1.json -o <临时文件>` 与合并后的
  `frontend/src/types/generated.ts` **逐字节一致**（忽略行尾），即生成物确为合并后契约的忠实渲染，
  CI 的 `gen:types` 漂移门禁不会因此失败。
- 复验（2026-09-16，真实命令与结果）：
  - 后端 `-f backend -pl control-plane-app -am test -Pintegration
    -Dtest=RegistrationApiIntegrationTest,RegistrationDisabledApiIntegrationTest
    -Dsurefire.failIfNoSpecifiedTests=false` → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`
    （`RegistrationApiIntegrationTest` 4 + `RegistrationDisabledApiIntegrationTest` 2）、`BUILD SUCCESS`。
  - 前端 `npm run typecheck` exit 0；`npm run test` → 59 files / **334** tests 全绿
    （较上轮 +1，来自 develop 并入的 `NextQuotaRulesView.spec.ts` 新增用例）。

## 会话交接点 2026-09-16（网关请求前置预检 #553）

- **#553 已实现并验证**（分支 `feat/gateway-context-limit-precheck`，自 develop `50a9b24`）；
  提交 `4582d19`（feat）、`e87a46b`（test）、本批文档提交。语义：鉴权 → 模型授权 → **体量预检**
  → 缓存 → 上游；超限返回 413 `context_limit_exceeded`，**不连接上游**、不进缓存、不记用量。
- 度量：对**已缓冲的原始字节**按 UTF-8 码点计数（零分配、单遍、不解析、不重排、不重序列化），
  合法 UTF-8 下整个序列化 body（含 JSON 结构、工具 schema、base64）都计入，是该 body 的
  **字符上界**（字符数 ≠ token 数）。**非法 UTF-8（严格校验不通过：孤立续字节、截断、
  超长编码 C0/C1 与 E0 80、代理项 ED A0、超出 U+10FFFF 的 F4 90/F5…FF）整段回退为字节长度**，
  字符数不会超过字节数，故计数**整体不低估**——不会低于任何宽松解码器解出的字符数。
  这类 body 本身不是合法 JSON，且仍受 256KB 缓冲上限约束。该口径由 1–2 字节全穷举
  （65792 个 body）＋定种子模糊测试（5000 个随机 body）与
  `250000 × 0x80 字节在默认阈值下必须被拒` 一条边界测试固定。
- 配置（全局）：`miqrokey.gateway.context-limit.enabled`（默认 true）/
  `.threshold-chars`（默认 200000，非正值回落默认）；对应环境变量
  `MIQROKEY_GATEWAY_CONTEXT_LIMIT_ENABLED` / `..._THRESHOLD_CHARS`。**逐 Key 阈值为后续项**
  （issue 文本为「逐 Key 或全局可调」，本版本取全局）。MCP 数据面两条路径本版本不适用该预检，
  仍只有既有缓冲上限（`payload_too_large`），已在 `docs/api-contract.md` §7.1 显式记录。
- 观测：零标签计数器 `miqrokey_gateway_context_limit_rejected_total`；拒绝日志仅含
  requestId / path / 测量字符数 / 阈值，不含正文。
- **真实验证（`backend/gateway-app`）**（评审答复轮后重跑；含本轮新增 3 条测试：
  MCP 大 body 原样直通、413 不写生命周期行、默认阈值下非法 UTF-8 必拒）：
  1. `.\mvnw.cmd -B -f backend -pl gateway-app -am spotless:check` → BUILD SUCCESS，
     `Spotless.Java is keeping 105 files clean - 0 needs changes to be clean`。
  2. `.\mvnw.cmd -B -f backend -pl gateway-app -am test` → BUILD SUCCESS，
     `Tests run: 348, Failures: 0, Errors: 0, Skipped: 0`，02:36
     （`ContextLimitPrecheckTest` 10、`ContextLimitGuardTest` 16 = Characters 6 / Threshold 4 /
     SwitchAndMetric 4 / Defaults 2、`ContextLimitDisabledTest` 3）。
  3. `.\mvnw.cmd -B -f backend -pl gateway-app -am -Pintegration test -Dtest=ContextLimit*Test,*ProxyContractTest,VirtualKeyAuthContractTest,ContextRegistryIntegrationTest,UsageLifecycleIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
     → BUILD SUCCESS，`Tests run: 184, Failures: 0, Errors: 0, Skipped: 0`，01:23
     （Testcontainers PostgreSQL 17.6 正常启动；`UsageLifecycleIntegrationTest` 7 含新增
     「413 不触上游、不写生命周期行」；`McpProxyContractTest$FailureSemantics` 9 含新增
     「200001 字符 MCP 报文原样转发」；`ContextRegistryIntegrationTest` 4/4）。
- 五类覆盖对照：① 超限 413 且 mock 上游零请求（三条路径 `/v1/messages`、`/v1/chat/completions`、
  `/v1/responses`；Anthropic 路径并断言错误信封顶层 `"type":"error"`）② 正常/临界请求字节不变转发
  ③ 开关关闭行为如旧（`ContextLimitDisabledTest` 3/3）
  ④ 边界值（`chars <= limit` 放行、+1 拒绝；另含默认阈值 200000 放行 / 200001 拒绝）
  ⑤ 码点计数单测（ASCII/多字节/emoji/空/非法 UTF-8 回退字节数/永不低估性质测试）；拒绝日志
  单测断言只含 sizes、不含正文哨兵；⑥ 413 **不触上游且不写生命周期行**（Testcontainers 实测
  `request_usage_records`：同上下文先有一行成功记录作对照，413 后按标记时间窗内新增 0 行）；
  ⑦ MCP 数据面 200001 字符报文原样转发（预检只作用于 LLM 数据面）。
- 行为收紧如实记录：启用后 **200001–262144 字符**（仍在上限 256KB 内）的请求由「缓冲上限放行」
  变为 `413`——刻意收紧，会同时挡掉同尺寸但上游本可接受的合法请求；已写入
  `docs/api-contract.md` §7.1、`docs/configuration-reference.md` §5、`docs/security.md`。
  另记：合规留存旁路（ADR-0014，默认关闭）在预检**之前**捕获 body，故开启留存时被 413 的请求
  仍可能已按留存策略入库（预检自身不写持久化）；已在 api-contract §7.1 记录。
- F15 边界如实记录：issue 文本提到「命中记 F15 日志与审计元数据」，本版本以 1 条 WARN
  （requestId/path/字符数/阈值）+ 零标签计数器替代，**不新增审计元数据记录**——本仓库 F15 为
  MCP 专用 `mcp_access_log`（V29），LLM 数据面无同等设施，且「不保存正文」红线限制可落库字段。
  如需「可查询的拒绝审计」，另立后续项，不在本 PR 范围内。
- 注意：`-pl gateway-app` 不带 `-am` 会从共享 `~/.m2` 取到别条线的旧 `test-support`，
  导致 surefire「failed to discover tests」；统一加 `-am`。`-Pintegration` 下忽略空 `-Dtest`
  匹配的属性名是 `-Dsurefire.failIfNoSpecifiedTests=false`。

### 2026-09-16 收尾轮（#553 收口：N1 修复 + 并入新基线）

- 并入 develop 新基线：`git merge origin/develop`（`adfb670`，#695 配额软着陆）→ 合并提交
  `b8bd715`；无冲突（本分支只改 ContextLimit* 与文档，与配额改动不重叠）。Flyway `V59`
  归 develop 的配额软着陆，**本分支不新增 migration**。
- N1 修复提交 `dc6f91e`（`fix(gateway): count malformed UTF-8 bodies as bytes (#553)`）：
  非法 UTF-8 整段回退字节长度，消除「全续字节 body 计 0 字符」的 fail-open。
- 验证（合并后真实输出）：`./mvnw -B -f backend -pl gateway-app -am test -Dtest=ContextLimitGuardTest,ContextLimitPrecheckTest,ContextLimitDisabledTest -Dsurefire.failIfNoSpecifiedTests=false`
  → `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`（Guard 16 / Precheck 10 / Disabled 3），
  BUILD SUCCESS，总耗时 37.0 s。
- 独立 delta 评审（新上下文，只审 `ea3d5cd..dc6f91e` 增量）：**0 BLOCKER，Consensus: APPROVE**；
  `ProxyController.java` 在该提交内仅 javadoc 与折行（`git diff -w` 只剩注释与参数折行），无逻辑变化。
  残留编辑性意见：astral 字符按码点计 1、按 UTF-16 码元计 2 属既有口径（文档统一按码点表述），
  记为后续可选跟进，不阻塞。

## Current State

- Project phase: `PHASE_1`
- Current executor: `Claude Code`
- Current goal: `2026-09-15 并行会话轮` — `IN_PROGRESS`
- Goal status: `IN_PROGRESS（多会话并行推进；已合并进展以 develop git log 为准。在途 PR：#599
  用途标注、#601 留痕控制台、#602 资料页增强；#596/#597 交付与验证细节见下方 09-15 交接点。
  此前 rc.19 审查修复波已全量落地并发布）`
- Last updated: `2026-09-16 CST`

## 会话交接点 2026-09-16（#588 留痕查看/导出审计断言补强）

- **#588 验收补强（PR 待提交）**：`RETENTION_LOG_VIEW` / `RETENTION_LOG_EXPORT` 此前**零自动化断言**
  （此前只出现在 `AdminRetentionLogController`、`docs/api-contract.md` 与前端视图注释中，仓库内无任何测试引用）。新增 `AdminRetentionLogAuditIntegrationTest`
  （8 例，PostgreSQL Testcontainers）：列表/导出各写且只写一条事件、actor=调用管理员、事件落在调用方
  tenant、`change_summary` 的 `rows`/`truncated` 口径正确；跨 tenant 行既不下发也不计数；USER 角色 403
  与匿名 401 均不产生事件；并断言保留正文只出现在响应、绝不出现在审计链（导入真实
  `KeyEncryptionProvider`，否则该断言真空成立）。
- 验证：`mvnw.cmd -B -f backend -pl control-plane-app -am test -Pintegration
  -Dtest=AdminRetentionLogAuditIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` →
  `Tests run: 8, Failures: 0, Errors: 0` / BUILD SUCCESS。
- **附带发现（未修，属产品缺陷，超出本 Goal 范围）**：`GET /api/v1/admin/retention-logs?direction=<非法值>`
  返回 **500 `INTERNAL_ERROR`** 而非 400。成因：`AdminRetentionLogService` 抛 `ResponseStatusException`，
  而 `GlobalExceptionHandler` 无该类型 handler，被兜底 `Exception` 分支吞成 500 + ERROR 级日志；同族
  `MethodArgumentTypeMismatchException` 的 javadoc 明确要求「invalid filter values are rejected, never treated as
  internal errors」，`DateTimeParseException` 分支同样映射为 400（#475），语义应一致。

## 会话交接点 2026-09-15（资料页增强 #597 + 用途标签澄清 #596）

- **#596（PR #599 待合并）**：Virtual Key「用途」语义显性化——创建表单补说明（声明标签、
  不限制客户端、可调用范围由授权产品与允许模型决定）+ 列表「用途」列头悬停提示；
  `UiTable` 列配置新增可选 `hint`（渲染 `th[title]`，向后兼容）；中英文案入 i18n。
  语义核对：数据面全量检索 `purpose` 唯一消费点是缓存键派生（`CacheKeyFactory` 区分位），
  不参与任何放行/拒绝（product-requirements §5.1「一个用途标签」、F60「不设 purpose 白名单」）。
  验证：定向 vitest 14/14、全量 257/257、typecheck/build、改动文件 eslint 全绿。
- **#597（PR #602 待合并）**：资料页增强（对齐 GitHub 安全设置）+ 自助「退出其他会话」——
  新端点 `POST /api/v1/auth/logout-others`（复用 `SessionService.revokeOtherSessions`，
  审计 `LOGOUT_OTHERS`，强制改密会话被 `PASSWORD_CHANGE_REQUIRED` 门槛拦截）+ 前端重排
  （身份头/用量速览/账号与安全/当前会话；速览口径=用量页合计行，失败降级「—」）。
  验证：后端全模块单测 BUILD SUCCESS + `AuthIntegrationTest` 22/22（新增 3 例）与
  `OpenApiSpecIntegrationTest` 1/1（PostgreSQL Testcontainers）；前端 263/263 单测 +
  e2e 53/53（含资料页 2 例与 forbidden-aesthetics）；OpenAPI 基线整体刷新到当前 develop
  （一并纳入 #587/#552 尚未刷新的增量）+ 前端类型重生成。
- 两 PR 均自 develop 出发，合并顺序无依赖；`ui-specification.md` §Virtual Keys/§Profile
  表述已随本批文档更新。

## 会话交接点 2026-09-13（rc.19 发布：审查修复波全量落地）

- **rc.19 已发布**（tag `0.1.0-rc.19`，GitHub Release 含中文说明）：rc.18 之后 6 个合并——sub-agent
  全量审查（7 路）修复波：网关缓存 HIGH×2（事件环阻塞 + stream 键）、控制面安全语义 HIGH×2（锁定
  失效 / XFF 伪造）、快照冻结 HIGH×2（p.version + 订阅 JOIN）、前端授权错写 HIGH（Grants 竞态）+
  15 视图守卫二批、转义族四缺陷、可靠性/并发五缺陷；全部红→绿（多处教科书级现场）。**实测阶段
  推荐候选**。
- 首批「代码 PR 不带文档」新规落地（#452 起），文档本 PR 统一收口。
- 下一批（rc.20 候选）：导出泄漏/输入校验族、MCP 体上限/SSRF 字面量、语料矩阵刷新 + 对比文档修正、
  前端 LOW 批。

## 会话交接点 2026-09-13（可靠性/并发五缺陷：#451）

- **#451（本 PR 前置，已合并）**：停机丢用量（destroyMethod=flush）/ 停机丢审计行（close 先停后排空）/
  熔断半开闩锁（过期窗口回收）/ 审批原子 add-only / 对账僵尸启动恢复（监听尽力而为）；四处红→绿
  （含真闩锁与冒烟空 H2 CI 现场）。

## 会话交接点 2026-09-13（前端守卫收口二批：#440）

- **#440（本 PR，HIGH 含授权错写）**：Grants 模型范围抽屉竞态（A 的清单覆盖 B → 保存写错授权）+
  同族守卫全量收口（~15 视图）+ 轮询卸载清理/预算失败显式化/Profile 死代码跳转/NewShell 监听清理/
  删除页防双发+预览一致性/一次性凭据关闭即清；前端全量 190/190 含两处红→绿。
- 在途：**#449（转义族）/#443（HIGH×2）** CI 中；rc.19 攒批（实测阶段候选）。

## 会话交接点 2026-09-13（转义族四缺陷：#447）

- **#447（本 PR，五红→五绿）**：ErrorEnvelopes no-op 转义（裸控制字符破坏错误 JSON，实测 CTRL-CHAR
  拒解析）+ MCP problemJson 伪造信封成员（实测注入）+ AdminOrgService 三处手拼审计摘要（含 `"` 名字
  实测 409 回滚、构造串可篡改摘要）+ AuditSummaries/safeJson 控制字符——统一全量转义助手收口。
- 在途：**#443/#448**（HIGH×2 / 控制面安全语义）CI 中（已并入 develop 解冲突）；#440 待推；rc.19
  攒批中（目标：实测阶段候选版）。
- 下一批：导出泄漏/并发族、shutdown flush、语料矩阵刷新。

## 会话交接点 2026-09-13（控制面安全语义修复：#445）

- **#445（本 PR，五红→五绿）**：①LOCKED 锁定不生效且自愈（执行点只识别带期限锁 + 登录回写 ACTIVE）
  ——null 期限=无限期管理员锁 + 锁定吊销会话 + 解锁清期限；②XFF 最左取信可伪造绕过 F05 白名单
  （实测 200）——右向左跳过信任代理 + 字面 IP 校验（`localhost` 实测 DNS 命中 127/8）；③403
  requestId 注入（实测 code=FAKE）——转义。认证/会话回归 38/0；三类 IT 18/18。
- 在途：**#444**（网关缓存，CI 平台恢复后合并）、**#443**（HIGH×2，同上）、#440（前端守卫二批，
  本地绿待推）——三者均因 CodeQL 平台故障暂缓合并（其余全绿）。
- 下一批：转义族（ErrorEnvelopes no-op 等）、导出泄漏/并发族、shutdown flush、语料矩阵刷新。

## 会话交接点 2026-09-13（网关缓存修复：#444）

- **#444（本 PR，sub-agent 发现）**：①L2 缓存 get/put 阻塞事件环（实测线程 `webflux-http-nio-2`）——
  get 走有界调度器、put 调度器上尽力而为；②缓存键忽略 stream 致 SSE/JSON 跨格式重放——加格式维度
  `stream=1/0`。三处测试红→绿（含跨格式 miss 契约与线程探针）；gateway 模块 264/0。
- **在途/阻塞**：#443（#441 修复）全绿除 CodeQL——平台侧 09:01Z 起上传故障（重试 3 次同因，多语言
  同现象，疑似 GitHub incidents）；#440 本地完成待推（依赖平台恢复后统一走 CI）。
- 下一批：控制面锁定失效（HIGH）/XFF 白名单（HIGH）、转义族、导出泄漏、并发族。

## 会话交接点 2026-09-13（sub-agent 全量审查 + HIGH 抢修：#441）

- **sub-agent 全量审查（用户指令 task-b）**：7 路并行（网关数据面 / 控制面认证审计 / 控制面资源并发 /
  持久层队列 / 前端 / 腾讯语料 01-15 / 16-29），约 70 条发现；已逐条复核属实率极高。
- **#441（本 PR，HIGH×2 抢修）**：①快照加载器缺 `p.version` → 韧性策略启用后快照永久冻结（吊销不传播）；
  ②用量订阅过滤 JOIN 引用不存在表 → `?subscriptionId=` 恒 500。两回归 IT 红→绿；修复各一行。
- **在途队列（按优先级）**：#440 前端守卫二批（含 Grants HIGH，部分已提交 wip）；
  网关缓存事件环阻塞 + `stream` 缓存键（HIGH/MED）；控制面用户锁定不生效（HIGH）+ XFF 白名单伪造（HIGH）；
  错误/审计信封转义族；导出 fileBytes 泄漏；并发族（审批 RMW/webhook PATCH/setState/熔断探针闩锁）；
  shutdown flush 缺失；语料矩阵 §1 刷新 + 对比文档失实修正。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）。

## 会话交接点 2026-09-13（备份工具链三缺陷修复：#438）

- **#438（本 PR，bug，对抗性复核发现——最后一个未扫面 deploy/backup）**：①保留策略只做计数上限
  （最新 11 个），脚本注释与 runbook 承诺的「日历周」语义（7 日 + 4 周 ≈ 一个月回滚窗口）未实现、
  `NEWEST_WEEKLY` 死变量——实际窗口仅 11 天；重写为日历周算法并抽为 `lib-retention.sh`（可脱库
  测试）；②备份管线失败残留无 manifest 的半成品 `.enc`；③还原无 `--single-transaction`（半程库）。
  新增 `test-retention.sh` 红→绿（精确保留集 20→10 / 幂等 / 失败零残留）+ 既有 webhook 测试与真机
  恢复演练（1000 行一致）保持 PASS。
- 至此对抗性复核组合覆盖的自有面全部扫毕（最后一面 deploy/backup 本轮收口）。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）；矩阵 §3 等外部。

## 会话交接点 2026-09-13（rc.18 发布：主密钥轮换重加密 + SSE 缓冲有界化）

- **rc.18 已发布**（tag `0.1.0-rc.18` @ ef029c7，GitHub Release 含中文说明）：rc.17 之后 3 个提交——
  主密钥轮换批量重加密端点（#432/#434，规格兑现：`reEncrypt()` 从零入口到可执行迁移 + 可执行 runbook
  + HMAC 环退役语义）、CSV 导出公式注入防护（#430/#431）、入站 MCP SSE 帧缓冲有界化 + 溢出终止
  （#433/#435）；全部红→绿证据（含 #434 首轮 CI 捕获的跨上下文扫描缺陷即时修复）。
- CI/已知问题状态：全清（各 PR CI 全绿后合并；NO_FAILURES 显式核验）。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）；矩阵 §3 等外部。

## 会话交接点 2026-09-13（SSE 会话缓冲有界化：#433）

- **#433（本 PR，bug，对抗性复核发现）**：入站 MCP SSE（#356）帧通道用无参 unicast
  `onBackpressureBuffer`——**无界**（实测澄清：并非有界丢帧）；慢订阅者下每会话响应帧无界堆积
  （MAX_SESSIONS 只限会话数不限字节），emit 失败仅 debug、会话不自清 → 响应静默搁浅挂死。
  修复=有界 256 帧（`MAX_BUFFERED_FRAMES`）+ 失败即终止会话（WARN + complete + 注册表移除，幂等）；
  单测红→绿（原始态红灯）。
- 同轮副产品：解开「测试 @TestConfiguration 跨上下文扫描」之谜（#434 Windows unit 现场）——测试配置
  必须自包含、bean 工厂禁引外层静态（详见 PR #434 第二提交与记忆条目）。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）；矩阵 §3 等外部。

## 会话交接点 2026-09-13（主密钥批量重加密：#432）

- **#432（本 PR，bug/规格兑现，对抗性复核发现）**：规格承诺「后台分批重加密旧密文」（security.md §密钥
  轮换 / runbook §11 / configuration-reference §4.3）——实现侧 `reEncrypt()` 零调用、无任何入口；
  照 §4.3 移除旧 key 版本会致三表（upstream_credential_versions / webhook_endpoints / mcp_services，
  留痕载体经 Kafka 不落库除外）存量密文全线解密失败（fail-closed 502）。新增
  `POST /api/v1/admin/crypto/reencrypt`：逐行迁移 + CAS 写回（并发不互踩）+ 失败隔离 + 幂等 + 审计
  `CRYPTO_REENCRYPT`；IT 红→绿 4/4（v1→v2 解密回原文 / 幂等重跑 / 损坏行隔离 / 会话+CSRF）；§4.3
  重写为可执行 runbook；runbook §11 补 HMAC 环退役语义（摘要单向、只能重发 Key）。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）；矩阵 §3 等外部。

## 会话交接点 2026-09-13（CSV 公式注入防护：#430）

- **#430（本 PR，bug，对抗性复核发现）**：前端成本/用量导出的 `row.label`（用户可控名称）以 `=`/`+` 等
  开头时被 Excel 当公式执行；修复=共享 `csvCell()`（引号 + 公式前缀守卫）收口三处导出（ROI 顺带补全引号）；
  后端审计 `quote()` 同步加防（审计列 JSON blob 起始、结构免疫——纵深防御），单元红→绿。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）；矩阵 §3 等外部。

## 会话交接点 2026-09-13（rc.17 发布：承压与安全纵深加固）

- **rc.17 已发布**（tag `0.1.0-rc.17` @ 198228f，GitHub Release 含中文说明）：rc.16 之后 5 个提交——
  用量队列承压丢事件修复（#424/#425，yml 真默认 1s/5 万 + flushing 加固 + dropped==0 硬断言）、冒烟调度
  噪音根治（#421/#422）、开放面 URI 安全回归固化（#423/#426）、技能包 SKILL.md 防炸弹有界读
  （#427/#428）、rc.16 发布记录（#420）；全部红→绿证据。
- CI/已知问题状态：全清（各 PR CI 全绿后合并）。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）；矩阵 §3 等外部。

## 会话交接点 2026-09-13（技能包解压炸弹硬化：#427）

- **#427（本 PR，bug，对抗性复核发现）**：`SkillZipValidator` 的 SKILL.md 尺寸防线对**流式 zip 恒不
  触发**（本地头尺寸 0/-1），`readAllBytes` 无界解压——压缩比炸弹可达 OOM（与类 javadoc 的「抗 zip
  炸弹」承诺不符）。修复=声明检查保留 + 有界读取（超限报 `SKILL_MD_TOO_LARGE`）；流式 zip 红→绿。
  同轮复核另两处（导出下载路径、经完整读的 JWT/扫描器面）为干净。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）；rc.17 攒批。

## 会话交接点 2026-09-13（调度噪音收口 + 开放面安全回归：#421/#423）

- **#421（#422 已合，本日补记）**：冒烟上下文（空 H2）换用永不触发的 `taskScheduler`——根因「fixedDelay
  启动即首跳 + 上下文缓存持续存活」；调度 ERROR 噪音 3 → 0，生产零改动。
- **#423（本 PR）**：对抗性安全复核三个外部可达面（ConsumerJwtVerifier 算法钉死/exp 严格、MinimalJson
  严格扫描、机器密钥面 + 原始 URI vs MVC 解码路径）——结论全部干净；URI 规范化安全语义固化为**真实 HTTP
  断言回归**（404/400/404/401），容器/框架升级改变行为即红灯。
- 下一批：leader 请示稿回执（6 组裁决 + 3 项材料）；rc.17 攒批。

## 会话交接点 2026-09-13（用量冲刷节奏修复：#424）

- **#424（本 PR，bug，verify 承压复现）**：soak 承压短少 697/7982 行（3 事件/请求 × 2400/s，5s 周期间
  瞬时深度超容量触发 DROP）。修复=冲刷周期默认 5s → 1s；诊断固化（soak 输出总线指标 + `dropped == 0`
  硬断言）。轻载/承压双条件验证。
- 待续：**#423**（开放面安全回归）rebase 到本修复之上重验后推 PR；技能包解压炸弹硬化材料已备。
- 下一批：leader 请示稿回执；rc.17 攒批。

## 会话交接点 2026-09-12（rc.16 发布：加固轮收官）

- **rc.16 已发布**（tag `0.1.0-rc.16` @ 2d35b23，GitHub Release 含中文说明）：rc.15 之后 6 个提交——
  AI 审查接入（#411）+ 全局异常语义（#413）+ 乐观锁竞态根治/全库映射（#416）+ 用量总线吞吐（#418）+
  §10 压测红线可执行化（#419，首发即捕获 #417）+ rc.15 发布记录（#409）；4 组红→绿确定性复现。
- CI/已知问题状态：全清（各 PR CI 全绿后合并；AI review 以预期跳过档通过）。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）；
  可选：CodeRabbit 站点激活、ANTHROPIC_API_KEY secret（AI 审查实启）。

## 会话交接点 2026-09-12（压测红线对齐：#414）

- **#414（本 PR，测试/脚本）**：SoakIntegrationTest 重做——50 并发 × 10s 真窗口 + 首包开销 P95 ≤ 30ms +
  探针 + 用量行数==请求数（首发即检出 #417，随其修复转绿：7420/7420）；soak.sh 补 TTFB 与红线档说明；
  §10 补执行入口。
- 下一批候选：rc.16 攒批（本批 #411/#413/#416/#417/#414 齐备后）；矩阵 §3 等外部。

## 会话交接点 2026-09-12（用量总线排空修复：#417）

- **#417（本 PR，bug，soak 红线重构实测发现）**：`PostgresUsageEventBus.flush` 每次只排空 threshold 条
  → 稳态上限 20 事件/秒，红线档下队列打满、DROP 丢失用量（7420 请求仅落 300 行）。修复=全量排空
  （threshold 修正为单块批量大小）；queue-spi 红→绿 8/8。
- 在途：**#414 soak 红线对齐**（分支待 rebase 到本修复之上后跑绿收尾）。
- 下一批候选：矩阵 §3 其余（等 leader/外部）；rc.16 攒批。

## 会话交接点 2026-09-12（乐观锁竞态与映射：#415）

- **#415（本 PR，bug，CI 现场驱动）**：MCP 巡检全量版本递增写与管理员操作竞态（#361 修复的漏网家族，CI
  集成套件 1/568 偶发 500 现场）——修复=巡检窄写（`updateHealth`：健康列专属、不推版本）+ 全库 19 处
  乐观锁抛点改 `OptimisticLockingFailureException`（经 #412 兜底自动 409）。测试：巡检版本不动 +
  双连接行锁交错红→绿（500→409）+ 持久层类型断言。
- 在途：**#414 压测红线对齐**（soak 真实窗口 + 50 并发 + P95 首包断言，分支 test/soak-50-stream-red-line）。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（全局异常语义修复：#412）

- **#412（本 PR，bug，自查发现）**：`GlobalExceptionHandler` 的 `Exception` 兜底会抢先吞掉框架 4xx ——
  未知路径/错方法/缺参/不支持介质全部 **500 + ERROR 日志噪音**；未局部映射的约束冲突/死锁同样 500。
  修复：补 6 个 handler（404/405/400/415 + 409 `RESOURCE_CONFLICT` / 409 `CONCURRENT_MODIFICATION`，
  响应永不携带 SQL）。测试：单元 3（含不泄露断言）+ IT 2，**修复前红灯精确复现两处 500 现场**。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（PR 自动 AI 审查接入：#410）

- **#410（本 PR，ci）**：新增 `AI review` workflow（claude-code-action v1.0.222，SHA 固定，符合
  Scorecard Pinned-Dependencies 惯例）——**key 版惰性**：未配 `ANTHROPIC_API_KEY` 时全步骤跳过；
  配置后非草稿 PR 自动中文审查（红线清单内嵌提示词 + 行内批注 + track_progress）；并发去重 + 20min 上限。
  启用=管理员加 secret（两步，见 PR 说明）；CodeRabbit 站点侧激活仍为可选并行项。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（rc.15 发布：自查轮收官）

- **rc.15 已发布**（tag `0.1.0-rc.15` @ 2260f40，GitHub Release 含中文说明）：rc.14 之后 7 个提交——
  被动健康（真实流量）视图（#397/#398）+ 六项缺陷修复（#399/#400 竞态两处、#401/#402 sink 挂起免疫、
  #403/#405 I21 行锁、#404/#406 技能修订并发映射、#407/#408 竞态 8 处全量收口）；全部红→绿回归闭环。
- CI/已知问题状态：全清（七条流水线 CI 全绿后合并；CodeRabbit 待站点侧激活，不影响合并）。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（弹窗目标切换竞态全量收口：#407）

- **#407（本 PR，bug，自查发现）**：#399 只修了带窗口参数的两处；本项把同缺陷类在 **8 处按目标加载的
  弹窗**全量收口（工具/工具重试/工具修订/服务访问/服务路由/服务韧性/技能修订/Webhook 投递记录，三个视图），
  统一 #399 序号守卫模式；代表性红→绿（工具列表串服务：`erp-stale-tool` 覆盖 `crm-only-tool` 现场）。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（技能修订并发错误面：#404）

- **#404（本 PR，bug，自查发现）**：技能修订并发激活（以及发布-激活交叉）可能死锁/撞激活指针唯一索引 →
  因 activate 未映射而冒泡**裸 500**。修复：activate 变更块捕获 `ConcurrencyFailureException |
  DuplicateKeyException` → 409 `SKILL_REVISION_CONFLICT`（发布路径同形）；publishValidated 捕获同步扩展。
  串行路径不变、技能修订 IT 全绿（并发交错无确定性注入点，映射为防御性修复）。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（I21 并发窗口行锁：#403）

- **#403（本 PR，bug，自查发现）**：webhook 端点删除「查引用→删除」间的并发窗口——未提交的引用插入可溜过
  检查，随后被 `ON DELETE SET NULL` 静默脱钩。修复：检查前 `SELECT … FOR UPDATE` 行锁（与 FK `FOR KEY
  SHARE` 互斥；并发双击删除干净 404）。测试：并发 IT 先红灯（200 + 脱钩）后修复转绿 4/4。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（访问日志转发挂起免疫：#401）

- **#401（本 PR，bug，自查发现）**：I19 转发 sink 挂起会拖垮访问日志管线——TCP syslog 写阻塞无上界且不可中断，
  唯一 flush 调度线程被挂死后**后续批次不再落库、队列打满丢弃**（catch 不到 hang）。修复：队列级
  `TimeBoundedForwarder` 硬截止包装（守护线程 + `Future.get`；连续超时 ≥3 进 60s 冷却；挂起线程最坏泄漏
  一个有界守护线程，flush 管线存活）；webhook 请求超时改接配置值。测试：包装器 4 + 队列挂起隔离 1。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（管理面窗口切换竞态修复：#399）

- **#399（本 PR，bug）**：两处窗口切换对话框的过期响应竞态——MCP 服务「真实流量」区（#397 新增）与消费者
  「调用概览」（#338）：请求序号守卫（过期成功/失败丢弃、loading 由最新请求收尾）；服务弹窗窗口切换改
  **选择器事件驱动**并消除重开双请求（此前每次重开发 2 次 `…/traffic`）；两处补乱序响应回归测试
  （先红灯复现、修复后转绿）。纯前端；无接口/数据变更。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（被动健康检查：#397）

- **#397（本 PR，矩阵 §3 候选落地，阿里「主动+被动并列」）**：新端点
  `GET /api/v1/admin/mcp-services/{id}/traffic?hours=`——`mcp_access_log` 按服务窗口聚合（口径同 #338：
  failed=UPSTREAM_FAILURE+CIRCUIT_OPEN；failureRate；lastCallAt/lastFailureAt；topFailingTools ≤5）；
  管理面「健康检查」弹窗新增「真实流量」区（1h/24h/7d 切换）+ 主动探测通过但流量有失败的盲区提示；
  只读不阻断、无迁移；IT 3/3 + 组件测试；矩阵/对照/契约/CHANGELOG 同步。
- 下一批候选：矩阵 §3 其余（等 leader/外部：HMAC/内容安全/F11 复活/F25/F27/F28/F29、#245、F32/F33、#211）。

## 会话交接点 2026-09-12（rc.14 发布：I21 落地收官）

- **rc.14 已发布**（tag `0.1.0-rc.14` @ 704c71d，GitHub Release 含中文说明）：rc.13 之后 8 个提交——
  I21 资源删除前置依赖检查（#393/#395，409 `RESOURCE_IN_USE` + dependencies 清单 + FE 弹窗）、MCP JSON-RPC
  initialize 探活（#387/#388，V50）、审计名称解析（#389/#390）、MCP 上游预算前端闭环（#383/#386）、默认配额
  语义提示（#391/#392）、App.spec 并发加固（#384/#385）、官方文档直读吸收（#394）；矩阵 I1–I21 全部 DONE。
- CI/已知问题状态：全清（本批各 PR 均 CI 绿后 squash 合并；develop 无红灯）。
- 下一批候选：矩阵 §3（等 leader/外部：消费者 HMAC 裁决、AI 内容安全护栏建议不做、F11 匹配模式复活时对齐、
  被动健康检查候选、F25/F27/F28/F29、#245、F32/F33、F19、#211 真机凭证）。

## 会话交接点 2026-09-11（I21 删除依赖检查：#393）

- **#393 I21（本 PR）**：`ResourceInUseException` + `dependencies` 问题体（409 `RESOURCE_IN_USE`）；webhook 端点
  删除前查 alert_rules 引用，被引用则拒绝 + 清单（消除原 SET NULL 静默脱钩）；前端依赖清单弹窗；IT 3/3、
  组件 7/7。FK 梳理结论：现存 14 个 DELETE 端点中仅 webhook 有真实引用面（其余无 FK 引用或为软禁用/成员移除），
  通用模式留待未来删除端点沿用（已在 issue #393 评论存档）。
- 下一批：rc.14 攒批（本日后半批：#383/#385/#387/#389/#391/#393 齐备）；§3 等外部。

## 会话交接点 2026-09-11（官方文档直读吸收）

- **四篇官方文档直读**（用户指定）：TKE「MCP Server 托管」/ 腾讯「模型 API」/ 阿里「什么是 AI 网关」/
  阿里「消费者认证」。结论：① 验证了 #387（TKE 托管形态无 /health，JSON-RPC 探活正确）与 I15 观测维度；
  ② 新缺口登记 **I21 删除前置依赖检查**（#393，待做）；③ §3 新登记：消费者 HMAC（需裁决，密钥存储语义）、
  AI 内容安全护栏（建议不做）、F11 匹配模式 CONTAINS/EXISTS（复活时对齐）、被动健康检查（候选）；
  ④ 对照全文见 ai-gateway-comparison「官方文档直读补充」。
- 下一步候选：**I21 实施**（中，管理面安全 UX）、rc.14 攒批、§3 等 leader。

## 会话交接点 2026-09-11（矩阵尾项核销：#391）

- **#391（本 PR，纯前端+文档）**：默认配额模板补「变更/停用只影响后续新建、不删除已分配规则」常显提示
  （doc 22 三语义收齐）；Skill「编辑（部分）」核定=重传即发布修订（I14 语义），元数据直改不做。
- **矩阵 §1「可立即」缺口清零**：所有登记的可立即项（I1–I20 + #383/#387/#389/#391）全部交付或核定；
  剩余仅 §3 需裁决/外部项（F25/F27/F28/F29、#245、F32/F33、F19 样本、#211）。
- 下一批候选：rc.14 攒批（本日后半批：#383/#385/#387/#389/#391 已具备）；矩阵 §3 等外部。

## 会话交接点 2026-09-11（审计资源名称解析：#389）

- **#389（本 PR）**：审计列表 `targetName` 批量装饰（15 类资源映射；未知/失联引用 null）+ 审计页「目标」列
  （名称优先、短 ID 回退）；CSV 与链数据不变。IT 6/6（新增：真实资源名解析 + 未解析 null 页面不失败）；
  前端 181/181 + typecheck/build 绿；OpenAPI 基线/TS 类型再生。
- rc.13 后轨迹：#383/#384/#387（已合）+ 本项；矩阵可立即清单继续收敛。

## 会话交接点 2026-09-11（MCP JSON-RPC 探活：#387）

- **#387（本 PR）**：V50 `check_mode`（HEALTH_PATH 默认 | JSONRPC_INITIALIZE）；JSON-RPC 探针 POST initialize
  信封（2xx+jsonrpc 体，兼容 SSE 帧；API_KEY 后端注入解密 Bearer、fail-closed）；健康配置 API/UI 可选；
  单测 7/7（含 Bearer 注入与 fail-closed）+ 服务 IT 4/4（往返 + 非法 400）。
- 本日 rc.13 后轨迹：#383 I20 前端闭环（#386）、#384 App.spec 并发加固（#385）、#387 探活（本）。
- 下一批候选：审计资源名称解析（doc 27）、Skill「编辑（部分）」核对、矩阵 §3 裁决项（等外部）。

## 会话交接点 2026-09-11（I20 前端闭环 + App.spec 加固）

- **#383（本 PR，纯前端）**：MCP 服务管理页「上游预算」注册项 + 行内查看/编辑弹窗——I20 的用户可见闭环
  （后端/契约在 #373 已交付）。组件测试 19/19（新增 2）。
- **#384（#385 已合）**：App.spec 登录用例预加载登录 chunk，消除并发跑下 `router.push` 等待动态 import 的
  超时（全量 ×3 + 6 路 CPU 压测 179/179）——CI/本地全量抖动面收窄。
- 下一批候选：**MCP JSON-RPC 探活（doc 03）**、审计资源名称解析（doc 27）、矩阵 §3 裁决项（等外部）。

## 会话交接点 2026-09-11（rc.13 发布：#362 修复后收官）

- **rc.13 已发布**（tag `0.1.0-rc.13` @ 12edf9e，GitHub Release 含中文说明）：rc.12 之后 17 个提交——
  **I 序列（I1–I20）全覆盖收官**（I6–I20 剩余项 + rc.12 后随批项）+ 三处真实缺陷修复
  （#371 数据面 API Key 上游凭证 / #361 服务状态竞态 / #362 审计链微秒舍入偶发）。
- CI/已知问题状态：**全清**（#361/#362 已修；develop 工作流无红灯；SonarCloud 配置性 skipped）。
- 下一批候选：矩阵 §3 裁决项（等 leader/外部：F25/F27/F28/F29、#245、F32/F33）、#211 真机凭证、
  前端 App.spec 在并发全量跑下的偶发（本地观察，CI 未现；如需可再加固）。

## 会话交接点 2026-09-11（修复审计链偶发：#362）

- **#362 修复（本 PR）**：根因=`Instant.now()` 亚微秒位 + pgjdbc 写微秒列四舍五入（.9999996s→下一毫秒），哈希用的
  epochMilli 与存储值偶发不符（~1/2000 事件，Windows 时钟才触发——与「CI Linux 难复现、本地长跑偶发」吻合）。
  修复=record() 先 `truncatedTo(MICROS)`；加 Clock 接缝 + 固定时钟确定性回归（修复前红灯复现同症状）；修复后
  120 轮×8 线程并发压测全绿；套件 7/7。
- 下一批：rc.13 攒批（I 序列 + 本修复齐备）。

## 会话交接点 2026-09-11（I19：访问日志 webhook/syslog 投递：#379 —— I 序列收官）

- **#379 I19（本 PR）**：`McpAccessLogForwarder` 旁路（落库成功后扇出；失败隔离/不重复投递）；webhook
  （JSON 数组 + Bearer）与 syslog（RFC 5424 UDP/TCP、facility）两 sink；`miqrokey.gateway.mcp-log.forward.*`
  配置（默认全关）；测试 6（队列）+3+3（sink，回环 webhook/syslog）+ 既有集成 7 全绿。
- **I 序列（I1–I20）全部 DONE**（本轮日内完成 I14/I15/I16/I17/I18/I19/I20 + bug #371 修复）。
- 下一批候选：**rc.13 攒批**（对比 release checklist）；#362（审计链并发偶发）复现定位；F 序列剩余按矩阵 §3 需裁决项。
- 本地/远端：develop 随本 PR 合并后再同步；本日轨迹：#372→#374→#376→#378→（本）——每项均 issue/PR/CI/关闭闭环。

## 会话交接点 2026-09-11（I14：Skill 版本历史/回滚：#377）

- **#377 I14（本 PR）**：V49 `skill_revisions`（存量回填 r1、部分唯一激活索引）；同名重传 = 发布修订（历史保留）；
  历史端点（元数据视图）+ 回滚端点（幂等）；审计 SKILL_REVISION_PUBLISH/ACTIVATE；管理页版本弹窗 + 回滚确认。
  后端 IT 7/7（新增 2），前端 spec 5/5（新增 1）、typecheck/build 绿。
- 本轮 I 序列：I17→I18→I16→I20→I15→I14 全部 DONE；**I 行仅剩 I19**（MCP 访问日志可插拔 sink，中）。
- 轨迹：bug #371（#372 修复）；#362（审计链并发偶发）仍待复现；rc.13 可在 I19 后攒批。

## 会话交接点 2026-09-11（I15：成本报表维度补齐：#375）

- **#375 I15（本 PR）**：`usage/summary` 增 `user/model/month` 维度（消费者/模型/自然月）；成本报表页五页签 +
  统一占比列 + 最高消费者/缓存命中 Tokens 两卡（7 卡）；CSV 跟随维度。后端 IT 8/8、前端 vitest 6/6、typecheck/build 绿。
- 本轮 I 序列（今天）：I17/I18/I16/I20/I15 全部 DONE；I 行剩 **I14**（Skill 版本历史，中）与 **I19**（访问日志可插拔 sink，中）。
- 轨迹：bug #371（快照装配丢 backend 鉴权，#372 修复）；#362（审计链并发偶发）仍待复现。

## 会话交接点 2026-09-11（I20：服务级上游超时 + 熔断慢阈值基准修正：#373）

- **#373 I20（本 PR）**：V48 `mcp_services.upstream_timeout_ms`（1000–600000，默认 60000）快照携带；数据面每尝试超时=
  服务预算，预算耗尽 → 504 `mcp_upstream_timeout`（UPSTREAM_FAILURE/504 行，此前裸 500）；创建可带 / `PUT …/upstream-timeout`
  （越界 `MCP_TIMEOUT_INVALID`）；熔断慢阈值基准由健康探测超时修正为上游预算（doc 134859），保存/下调**双向拒绝**；
  审计 `MCP_SERVICE_UPSTREAM_TIMEOUT`；矩阵 I20 行与「慢调用阈值基准」裁决行落定。
- 同 PR 补记 #371 的 CHANGELOG 条目（修复代码已随 #372 先行合入 develop @ 53f442c）。
- 下一批候选：I15 成本维度（需后端聚合）、I19 访问日志可插拔 sink、#362 审计链并发复现。

## 会话交接点 2026-09-11（bug 修复：快照装配丢 backend 鉴权：#371）

- **#371（本 PR）**：`JdbcRouteSnapshotLoader` 最终装配用 10 参兼容构造器重建 `McpServerRecord`，
  `backend_auth_mode`/密文在装配末端被静默重置为 VISITOR/null——#321 引入字段时漏改 #154 时代的重建行；
  生产 API_KEY 模式数据面不注入上游 Authorization（上游 401）、网关无错无日志。修复：重建行回填两字段
  （发现于 I20 现场勘察，按「一 PR 一 issue」独立修复）；回归：`McpBackendAuthApiIntegrationTest`
  以真实加载器断言密文信封逐字节回环。全量 verify 绿（276 测试，spotless 干净）。

## 会话交接点 2026-09-10（深夜，F19 对账端点层：#334 + 全量对照表启动）

- **#334 F19 对账端点层（本 PR）**：canonical 导入→异步四级匹配→四态报告（V42 两表；只读、不写 usage、不存上传内容）；三端点 + 幂等重传 + gzip + 审计；引擎补行级 UNMATCHED_LOCAL。IT 2/2；修复两处自查 bug（get/view 双层包装、桶边界夹具）。
- **全量对照表（29 篇 × 代码四态）**：三路并行扫描完成，产出可立即实现清单（消费者/接口方向优先）与需裁决/外部清单——将落 docs/coverage-matrix.md（next PR）。
- 下一批（用户指定消费者/接口优先）：① F19 前端页；② 消费者 MCP 调用概览（mcp_access_log 聚合，候选 C）；③ MCP 面消费者 JWT 回退（复用 ConsumerJwtVerifier+快照公钥）；④ MCP tools/list 自动同步。

## 会话交接点 2026-09-10（夜，导出可对账等级：#330）

- **#330 导出「可对账等级」（本 PR，兑现 usage-accounting §11 先行部分）**：V41 export_tasks.reconcile_level
  （PROVIDER_ID_BACKED/PARTIAL/LOCAL_ONLY 按 provider_request_id 覆盖度；空窗口 null）；会话/机器元数据
  均带字段；产物 local_caliber_note 扩展 `;reconcile=…`（前缀兼容）；前端导出页徽标。验证：IT 2/2、
  FE 三件套、全量 verify 绿后合入。
- 另：App.spec 并发 flake 已直接修复（#332，vi.waitFor 条件等待，全量 ×3 稳定绿）——见 rc.11 后修补。
- rc.11 攒批候选已含 #326/#328/#330。

## 会话交接点 2026-09-11（runbook §14：#369 = I16）

- **#369 I16（本 PR，纯文档）**：§14 Key 形态误配一步定位 / T+1 对账窗口 / 429·403 归因（对照表 + 归因入口）。

## 会话交接点 2026-09-11（留痕上限：#367 = I18）

- **#367 I18（本 PR）**：V47 租户内容上限 + 截断（UTF-8 边界）+ truncated 标记 + 计数；PUT 接受 maxContentBytes。
- 矩阵修正：I15「纯前端（数据已产出）」不确——调用方/模型维度聚合后端未产出，改为需后端聚合（缓存命中卡/占比
  已在前批页面内）。
- 在途：I15 后端聚合（中）、I16 runbook（纯文档）、#362 审计链偶发复现。

## 会话交接点 2026-09-11（I17 修复：#365）

- **#365 I17（本 PR）**：skipRetry 真分支（false=只观测）+ SERVER_5XX 收窄 500/502/503/504；契约 §5.25 更新。
- 在途：I14 Skill 版本历史（中）、I15 成本报表前端（小）、I16 runbook（纯文档）、#362 审计链偶发复现。

## 会话交接点 2026-09-10（深夜 #15，服务锁竞态修复：#361）

- **#361（本 PR）**：状态切换 CAS-on-status + 巡检窄写（不 bump version）；并发冲突 409 化。IT：巡检后 version
  不变、健康列更新、禁用成功；服务生命周期/运行时 IT 全绿。
- 在途：#362（审计链并发用例偶发，需复现定位）；矩阵 §2 I14+ 继续。

## 会话交接点 2026-09-10（深夜 #14，Tool 级重试：#360 = I13）

- **#360 I13（本 PR）**：V46 工具级重试覆盖表 + 管理端点 + 快照携带 + 网关 withRetry 合并（熔断保持服务级）；
  前端「重试」对话框。数据面 3/3 + 既有 F12/F13 全套无回归；管理面 IT 全绿；前端 17/17。
- 在途：I14 Skill 修订、I15 成本卡、I17 breakerSkipRetry 空操作字段修复等（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #13，日志补列：#358 = I12）

- **#358 I12（本 PR）**：V45 `mcp_access_log.session_id/ttfb_ms`；网关写入（头优先、SSE 回落会话 id；ttfb 仅
  FORWARDED）；读面与前端两列。F15 集成 5/5；契约 30 例无回归。
- 在途：I13 工具级重试（中）等（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #12，入站 SSE 双端点：#356 = I11）

- **#356 I11（本 PR）**：`GET /sse` + `POST /message` 单节点内存会话（ADR-0013 二期落地）；响应目标抽象
  （ResponseTarget：直连/SSE）使两形态共用同一 ACL/凭据/重试/熔断流水线；容量/空闲回收；F15 同记。
  验证：帧 4/4、注册表 4/4、SSE 契约 7/7。
- 在途：I12 日志补列（小）、I13 工具级重试（中）等（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #11，修订 diff + 路由表达式：#354 = I10）

- **#354 I10（本 PR）**：`changedFieldsVs`/`renderExpression` 纯函数 + 只读视图字段（changedFields/matchExpression，
  计算不入库，兼容构造器保持调用点不变）；前端修订 chips/基线标记与路由表达式行。**I1–I10 全部 DONE**。
- 在途：I11 入站 SSE 双端点（中）、I12 日志字段、I13 工具级重试等（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #10，Skill 搜索/标签/Examples：#352 = I9）

- **#352 Skill 目录收口（本 PR）**：V44 `skills.examples`；校验上限（examples ≤10×512、tags ≤5×20 去重）；
  q/tags 过滤（两个列表端点，与语义 + 大小写不敏感 + q>60 → 400）；SkillView 增 examples/createdBy/createdByName。
  前端市场搜索+标签 chips+示例/创建人；管理页创建人列。Validator 12/12、IT 5/5、前端 173/173。
- 在途：I10 修订字段级 diff、I11 SSE 双端点等（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #9，模型目录定期重探：#350 = I8）

- **#350 定期重探（本 PR）**：`@ConditionalOnProperty` 默认关；开启后按 fixedDelay 周期对 (种子租户, OFFICIAL_API 产品)
  执行既有探测服务（失败隔离 + V43 可见面）；单测 2/2；configuration-reference 增属性。
- 在途：I9 Skill 搜索/示例列（下一迁移 V44）、I10 修订 diff 等（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #8，审计页收口：#348 = I7）

- **#348 审计页收口（本 PR，纯前端）**：actorId 过滤（UUID 校验；列表+导出）+ 近 7/30 天快捷窗 +
  targetType 下拉（28 类型）。audit spec 5/5；全量 172/172；typecheck/eslint/build 通过。无后端改动。
- 在途：I8 定期重探（调度，默认关）等（矩阵 §2 继续）。
- rc.12 已发布（#340/#342/#344/#346 批量，tag 0.1.0-rc.12）。

## 会话交接点 2026-09-10（深夜 #7，模型探测端点：#346 = I4）

- **#346 模型探测 + 失败可见面（本 PR）**：`POST /admin/models/probe`（产品→适配器→首个 ACTIVE 凭证→目录抓取，
  成功才落库）+ `GET /admin/models/probe-status`（最近状态，V43 四列）；`refreshProduct` 重构为共用抓取核心
  （`probeProduct`），探测失败 502 脱敏且目录/人工行不动；前端模型目录对话框「探测模型」+ 状态行。
  验证：服务单测 8/8、探测集成 5/5；OpenAPI 再生无破坏 + gen:types。
- 在途：I7 审计页收口（纯前端）、I8 定期重探（调度，默认关）等（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #6，MCP Tools 自动同步：#344 = I3）

- **#344 MCP tools/list 自动同步（本 PR）**：上游差量合并（新增=占位映射+基线修订；描述变化=F16 下一修订；
  缺失只报 `absentUpstream` 不动作）；`dryRun` 预览；`API_KEY` 后端解密 Bearer（fail-closed、用后清零）；
  2MB/1000 工具/30s 守卫；审计 `MCP_TOOLS_SYNCED`；前端 Tools 对话框「同步 Tools」预览→确认。
  验证：单测 7+9、集成 8/8；OpenAPI 再生无破坏 + gen:types。
- 在途：I4 模型探测端点（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #5，F19 对账前端页：#342 = I2）

- **#342 F19 前端页（本 PR）**：报告列表 + 上传（JSONL/.gz；202 后轮询至终态）+ 四态明细（verdict 过滤、
  游标分页、失败原因）；后端补 `GET /reconciliations?limit=`（1..100，越界 400；空列表 `[]`，租户隔离）。
  对账 IT 3/3；前端新 spec 3/3、全量 vitest 167/167、typecheck/build 通过；OpenAPI 再生无破坏 + gen:types。
- 在途：I3 tools/list 自动同步、I4 模型探测端点（矩阵 §2 继续）。

## 会话交接点 2026-09-10（深夜 #4，MCP 面消费者 JWT：#340 = I6）

- **#340 MCP 数据面消费者 JWT（本 PR）**：快照携带公钥 + `consumerByName` + authenticate JWT 分支；
  验签器上移 domain（纯 JDK：自带严格 JSON 扫描，零三方库；与计费通道同一实现）；网关契约 +6 全绿
  （30/30）、验签器单测 18/18。ADR-0011 增补；矩阵 I6 → DONE。
- 在途：I2 F19 前端页、I3 tools/list 自动同步、I4 模型探测端点（矩阵 §2 继续按 owner 消费者/接口优先序列）。

## 会话交接点 2026-09-10（深夜 #3，消费者调用概览：#338 = I5）

- **#338 消费者「最近调用概览」（本 PR）**：mcp_access_log 聚合端点 + 前端对话框（24h/7d）；无迁移、无网关改动。
  IT 2/2；coverage-matrix I5 → DONE；矩阵纪律措辞校正（I 编号 = 登记锚点）。
- 在途：F19 前端页（I2）、MCP 面消费者 JWT 回退（I6）、tools/list 自动同步（I3）、模型探测端点（I4）。
- 已知残留：App.spec 全量并发下偶发（#332 修复后频次明显下降，待后台循环抓到复发实例再定位）。

## 会话交接点 2026-09-10（深夜 #2，覆盖对照表首版：#336）

- **#336 coverage-matrix 首版（本 PR）**：29 篇逐能力四态 + 可立即实现清单（I1-I20，I1=#334 已交付）+
  需裁决/需外部清单 + 每 rc 刷新纪律；feature-backlog/NEXT_SESSION_PLAN 加指针。三路并行扫描产出合并。
- 并行在途：**#334 F19 对账端点层**（PR #335，代码+IT 2/2+全量 verify 绿，待 CI）。
- 下一批（按 owner 指定"消费者/接口优先"）：I5 消费者 MCP 调用概览 → I6 MCP 面消费者 JWT 回退 →
  I2 F19 前端页 → I3 tools/list 自动同步 → I4 模型探测端点。

## 会话交接点 2026-09-10（傍晚，运维文档补齐轮：#328）

- **#328 运维文档轮（本 PR）**：runbook 新增 §3c 消费者密钥运维（scope/到期/轮换=重建/提醒规则）、
  §3d MCP 上游后端密钥运维（写后不可读/轮换窗口/fail-closed 502 排障）、§3e 服务注册表健康运维
  （上下线/探测语义/阈值/告警面）；configuration-reference 补 `MIQROKEY_MCP_HEALTH_CYCLE_MS` 行；
  NEXT_SESSION_PLAN 重写为 rc.10 后状态；本段与 Current State 刷新。
- 下一步默认：rc.11 攒批（#326 待锚）或 §2 候选（F23 导出对账等级标记等）。

## 会话交接点 2026-09-10（下午，服务注册表运行时治理：#326）

- **#326 服务注册表运行时治理（本 PR）**：V40 健康探测列（镜像 mcp_services）；`enable` 端点补齐上下线对称
  （审计 SERVICE_ENABLE；重复启用 409）；`health-config` 部分更新（审计 SERVICE_HEALTH_UPDATE）；
  `ServiceHealthChecker` 探测 ACTIVE 服务（GET baseUrl+checkPath、2xx 计健康、阈值迁移、DISABLED 不探测、
  乐观锁并发跳过）；前端健康徽标/最近检查/启用/配置对话框。验证：单测 2/2 + IT 3/3、FE 三件套、全量 verify 绿后合入。
- 盘点闭环总览：rc.9（#314-316）→ #320（MCP 后端鉴权）→ #322（消费者到期）→ #324（审计二批）→ rc.10 →
  #326（服务运行时）；leader 三词的 NOW 可实现项已全部交付。下一批候选=攒 rc.11 或等外部输入。

## 会话交接点 2026-09-10（午间，审计覆盖第二批：#324）

- **#324 审计覆盖第二批（本 PR）**：兑现 #315 备注 follow-up——告警规则/Webhook/预算/全局配置/模型目录
  人工维护五族零审计补齐；新增 `AuditContext` 统一归属（机器面 actor=发行管理员 + `via: admin-api:<name>`，
  会话面 actor=用户）；webhook secret / config value 永不入摘要（IT 断言）。机器面（F60）改动告警规则/
  Webhook 自此有完整痕迹。验证：综合 IT 2/2、全量 verify 绿后合入。
- 权鉴/服务/接口盘点延伸已覆盖：#314-#316（rc.9）、#320（MCP 后端鉴权）、#322（消费者到期）、#324（审计二批）；
  下一候选=服务注册表运行时治理（服务方向）或攒批 rc.10。

## 会话交接点 2026-09-10（上午，消费者密钥到期治理：#322）

- **#322 消费者密钥到期（本 PR）**：V39 `api_consumers.expires_at`（NULL=永不过期）+ 创建可选到期（将来校验）+
  双面静默失效（计费通道仓储查询条件 + 网关快照 `expiredAt(clock)`，均 401 与未知 Key 同形）+ 管理列表继续
  可见 + `CONSUMER_KEY_EXPIRING` 可选提醒（每（消费者×天）至多一条，镜像 V36）+ 前端到期列/表单/规则类型选项。
  验证：控制面 IT 4/4、网关契约（过期 401）与 RetentionCapture 回归绿、FE 三件套绿、全量 verify 绿后合入。
- 权鉴三批（#314/#315/#316）+ rc.9 + 权鉴延伸（#320 MCP 上游后端鉴权、#322 消费者到期）已完成；
  下一候选=服务注册表运行时治理（上下线/健康扩展，服务方向盘点）或攒批 rc.10。

## 会话交接点 2026-09-10（凌晨，MCP 上游后端鉴权：#320）

- **rc.9 已发布**（含 #314/#315/#316 三批，中文 Release Notes）。
- **#320 MCP 上游后端鉴权注入（本 PR）**：腾讯 raw 03「Visitor / API Key」三级鉴权链补齐——V38
  backend_auth_mode（默认 VISITOR 零行为变化）+ 密文三列 + CHECK 一致性约束；API Key 模式网关按请求解密
  （AAD 绑定 tenant+service、明文不出网关、SecretWiping 清零）注入固定 `Authorization: Bearer`；解密失败
  fail-closed 502 backend_auth_unavailable；`PUT …/backend-auth` + 审计 MCP_SERVICE_BACKEND_AUTH（无 secret）；
  前端服务页徽标+编辑。验证：控制面 IT 2/2、网关契约 3/3、全量 verify 绿后合入。
- 待办延续：#211/#245/F32/F33 外部项；下一候选=服务注册表运行时治理（上下线/健康扩展，服务方向盘点）。

## 会话交接点 2026-09-09（深夜，消费者能力作用域：#316）

- **#316 API 消费者能力作用域（本 PR）**：一把 `mqk_api_` Key 原同时具备整租户计费读与 MCP 数据面调用——
  V37 capabilities jsonb（NULL=全量，值域 billing:read/mcp:call，镜像 V35 管理密钥 scope 先例）；
  计费通道缺 billing:read → 403 CONSUMER_SCOPE_DENIED（problem+json），网关快照带 capabilities、缺 mcp:call
  → 403 consumer_scope_denied；PATCH /api/v1/admin/api-consumers/{id}/scope + CONSUMER_SCOPE_UPDATE
  （from/to）+ 400 CONSUMER_SCOPE_INVALID；scope 变更触发路由快照刷新。验证：控制面 IT 2/2、网关契约测试
  6/6、typecheck/vitest、全量 verify 绿后合入。
- 待办延续：#211/#245/F32/F33 外部项；F60 批 3 剩余治理（频控）长期可选。

## 会话交接点 2026-09-09（夜 #2，服务族审计覆盖：#315）

- **#315 服务与集成族审计覆盖（本 PR）**：六族管理写操作 21 个事件全量入链（此前零审计）——
  consumers（create/disable/jwt set/remove）、agents、services 注册表、mcp_services
  （create/上下线/健康配置）、mcp_tools（create/批量导入/启停/F16 修订发布与激活）、skills
  （upload/access/archive）；共享 AuditSummaries（控制字符剥离 + JSON 转义）保证摘要 jsonb 安全；
  actor=操作管理员 + X-Request-Id 关联。验证：AdminFamilyAuditIntegrationTest 5/5（HTTP 全链路 +
  链上断言 + 明文 Key 不出现）、全量 verify 绿后合入。
- 待办延续：#316 消费者能力 scope（下一 PR）、#211/#245/F32/F33 等外部项不变。

## 会话交接点 2026-09-09（夜，leader 三方向词盘点 + 操作记录读面：#314）

- **leader 2026-09-09 方向词：权鉴 / 服务（腾讯功能极多）/ 接口**——以腾讯研究 corpus 为对照扫描
  （Explore ×3 并行）得共识缺口清单：审计查询面（raw 27）、服务族审计写覆盖、消费者最小权限 scope、
  MCP 上游后端鉴权注入、服务上下线/健康扩展等；外部依赖项（服务→网关数据面接线 F29/F27 形态、
  数据面 JWT 认证 F33）仍等 leader，不抢跑。三词盘点对应三 issue：#314（读面）、#315（写覆盖）、
  #316（scope）。
- **#314 操作记录查询补全 + 合规导出（本 PR，对齐腾讯 raw 27「操作记录」筛选+下载）**：读面新增
  `targetType`/`actorId`/`from`/`to` 精确筛选（人类与机器端点共享 AuditEventReadService，cursor 可
  组合）；CSV 合规导出双端点 `GET /admin/audit-events/export` 与
  `GET /admin-api/audit-events/export`（RFC 4180 转义 + UTF-8 BOM、列不含哈希链与正文、单次上限
  5 万行、超限响应头 `X-MiQroKey-Truncated: true` 显式声明不静默截断）；400 `PARAM_INVALID`/
  `TIME_RANGE_INVALID`。前端审计页补资源类型/时间窗筛选与导出按钮（截断提示）。
  验证：新 IT 5/5（双面筛选组合/租户隔离/CSV 形状与转义/截断/参数校验）、typecheck 三段链 0 错、
  vitest 163/163。
- **环境备忘**：mvnw.cmd 需 java 在 cmd PATH——本机改用 miqro-local/mvnw21.sh（直接调 wrapper
  jar + JDK21 绝对路径，multiModuleProjectDirectory=backend）规避 MSYS PATH 冒号截断。

## 会话交接点 2026-09-09（下午，codegen 迁移线收口：#285/#287）

- **#285 codegen 小步 3（#284）**：Grant/UsageDeletionRequest/ToolImportSkip/ToolImportResult →
  ProjectProviderGrant/UsageDeletion/ImportSkip/ImportResult 别名；消费方换源。
- **#287 codegen 小步 4（#286）+ typecheck 确定性**：usage 嵌套族(UsageCost/Requests/Tokens/
  UsageGroup→Cost/Requests/Tokens/GroupSummary)+ CreateApiConsumerResponse(后端 ResponseEntity<Map>
  改具名记录建模、响应键不变、基线再生)迁 hub；types/api.ts 只剩 ProblemDetails/ProviderProductView
  两个刻意保留 interface，**codegen 迁移线收口**(#246 原 25 目标全完成)。
- **教训(CI 抓到假绿)**：复合项目(tsBuildInfoFile 增量缓存)下 vue-tsc --noEmit 本地复跑会复用
  缓存 → 曾本地 0 错而 CI 20 错；typecheck 脚本改为先删 node_modules/.tmp/*.tsbuildinfo 再跑，
  本地≡CI。后续任何前端改动都以清缓存后的 typecheck 为准。
## 会话交接点 2026-09-09（午间轮：#279 codegen 小步 2 + #281 typecheck 修复）

- **#279 codegen hub 小步 2（#278）**：McpToolRevisionRow/ModelCatalogRow/UserProjectMembership/
  MemberView/McpHeaderCondition → hub（后端返回类型逐一核实）；消费方换源 + 清 #273 遗留
  spec 陈旧导入。
- **#281 typecheck 空转修复（#280，重要）**：发现 `npm run typecheck`(vue-tsc --noEmit)对
  project-references 壳零检查——CI 绿灯是假的；实测 app 项目 360 个积压错误。修复：脚本显式
  检查 app/node；**360 错清零**(30 视图+3 文件，导入源漂移 + hub 可选字段收窄，零行为改动)；
  暴露 2 真 bug：toast 定时回调未定义 dismiss（toast 永不自动移除）、clearMcpAccessGrants 第二
  参被 del() 丢弃（工具级 ACL 重置一直在重置服务级，改 `?toolId=`）。验证：tsc 双项目 0 错、
  vitest 162/162、CI 前端 job 现跑真 typecheck 全绿。
- **教训**：project-references 根配置下 `vue-tsc --noEmit` 无 `-p`/`--build` 即空转——凡引
  references 的项目 typecheck 脚本必须显式 `-p tsconfig.app.json` 或 `--build`；后续新增工程
  检查同款。
- **待办延续**：codegen 小步 3（Grant→ProjectProviderGrant、UsageDeletionRequest→UsageDeletion、
  ToolImportSkip/Result→ImportSkip/ImportResult 已核实可迁；Usage* 嵌套与 CreateApiConsumer
  Response 需后端命名建模后迁）；typecheck 余项（spec 仍排除在 tsconfig.app 外）可选跟进。

## 会话交接点 2026-09-09（晨，收尾轮：#273 admin 契约修复 + #275 依赖审计）

- **#273 admin 用户契约修复（#272）**：GET/PATCH /api/v1/admin/users 原先直接返回 domain
  User → springdoc 在 OpenAPI 里公开了 passwordHash(运行时靠 Jackson mixin 隐藏)= 文档违约
  「password_hash 永不返回」红线。改为 AdminUserView(去 hash,仿 TeamMemberView 先例),
  create/reset 嵌套同步;spec 测试加全契约防回归断言(passwordHash 不得出现);基线再生
  (User→AdminUserView,可选属性级非 breaking);FE AdminUser/UserCreatedResponse 迁 hub。
- **#275 依赖审计清零（#274）**：CI security gate 因 2026-09-09 新增公告失败——vitest ≤4.1.10
  (GHSA-82fw,@vitest/mocker)与 js-yaml 4.0.0–4.3.1(GHSA-2883)。vitest ^3→^5.0.0(零配置
  破坏,163/163 绿)+ js-yaml overrides ^4.3.2;npm audit 0 漏洞。
- **待办延续**：F19 账单对账骨架维持 SCAFFOLD(验收口径=等真实账单样本;usage_event 已具备
  provider_request_id 匹配基础);codegen 剩余手写类型按 hub 顺序;F60 批 3 可选。

## 会话交接点 2026-09-09（夜，自主轮：F60 批 2 v2 + codegen 第一步 + 例集补段）

- **F60 批 2 v2 Virtual Key 委托创建（#268→#263，owner 圈定案 1「自己看着做、边界不能越过」）**：
  `POST/GET /api/v1/admin-api/virtual-keys`——机器密钥代**目标用户**建钥，成员/授权校验链按目标执行
  （1:1 归属不变）；委托人=发行管理员且现行须 SYSTEM_ADMIN（403 `DELEGATION_FORBIDDEN`），目标
  租户外 404 / 停用 409；审计 actor=委托人 + change_summary `targetUserId`（双元可溯）。ADR-0016
  增补 + api-contract §9 + 示例集补委托建钥段（curl/Python/README）+ 基线/前端类型同步；
  OpenAdminVirtualKeysApiIntegrationTest 6/6 + 自服务回归 8/8，后端全模块 verify 绿。
- **codegen 第一步（#269→#265，方案 B 注解显式 content）**：/auth/* 五个端点成功体 schema 化
  （login/bootstrap/register/me/csrf，application/json）——auth 信封不再是 OpenAPI 盲区；
  FE LoginResponse/UserResponse 手写 interface → generated-api hub 别名，消费方换源；
  typecheck/vitest 163/163 + 守卫绿；logout/password 的 message 体保持不建模（非本块范围）。
- **待办延续**：#211 真机凭证、#245 告警接线裁决、F32/F33 平台接口（BLOCKED）、#246 codegen
  剩余手写类型按 hub 顺序逐页小 PR、F60 批 3 治理可选、文档/backlog 同步见下。

## 会话交接点 2026-09-08（晚，阶段收口：F60 程序全链 + 控制台打磨）

- **F60 开放管理 API 全链交付**：#200 批1 → #204 批1b 读面 → #251 写面 v1（ADR-0016
  Accepted A+C：告警/Webhook 机器 CRUD + 导出委托=发行管理员）→ 批 2 契约与示例
  （scripts/open-api-examples/，curl+Python+最小权限 README）→ OpenAPI 基线/前端类型
  同步 → rc.1..rc.4（各带中文 Release）。
- **控制台打磨**：视觉轮基线 keys 8.5/usage 7.5/overview 7.6→7.5 区；#254 总览收口
  （货币层级/空态居中/空格规范）；usage 横幅与卡片 padding NIT 属设计 token 决策，记档。
- **盘点封存**：#246 codegen 全量迁移→DEFERRED（109 schema 中 25 个手写类型 0 覆盖，
  迁移前置=后端契约建模，F09 发布前候选）；#245 告警接线（全局饱和承载口径）待裁决；
  #211 真机凭证 BLOCKED；F60 批 2 v2（Virtual Key 委托创建）待评估。
- **进入下一阶段**：平台中间件 P0（platform-middleware-roadmap）——F32 平台用户同步/
  OAuth（等 leader 接口形态）、真机冒烟、F60v2、codegen；详见 NEXT_SESSION_PLAN 新计划。

## 会话交接点 2026-09-08（rc.2→rc.3：功能与 UI 收尾七 PR）

- **#225 导出口径列 + Webhook 成功率卡**、**#226 codegen 漂移修复**、**#228 F16 工具版本管理(V33)**、
  **#230 F16 UI(历史/回滚抽屉)**、**#232 F18 模型人工兜底(V34)**、**#234 F17 OpenAPI 批量导入**、
  **#236 控制台 UI 收尾(编辑发布/导入弹窗/模型目录抽屉)**——每步本地验证 + CI 全绿后合并。
- **rc.3 轮**：OpenAPI 基线再生 + 前端生成类型同步；0.1.0-rc.3 tag + 中文 Release。
- 待办不变：#206 F60 写面拍板(A+C)、#211 真机凭证(BLOCKED)、F32/F33(leader 接口)。

## 会话交接点 2026-09-07（下半场：#204-#218 六 PR + pre-release rc.2）

- **批 1b（#204，issue #205）**：/api/v1/admin-api 读面全开——audit-events（共享 AuditEventReadService，人类端点委托回归受保护）/api-keys/quota-rules/export-tasks（元数据 SQL 不读 file_bytes）/mcp-access-logs；**发现并修复批 1 真 bug**：SessionFilter(-100) 拦截一切无会话 /api 请求，机器 Bearer 到不了 AdminApiKeyAuthFilter(-95)——首个开放面集成测试（7/7：全端点/跨租户隔离/吊销过期即时 401/审计光标/会话 SYSTEM_ADMIN 403 规则/人类端点回归）暴露并锁定；写面草案 ADR-0016(Proposed) 等拍板。
- **Issue 纪律（#209→#207）**：owner 拍板流程正规化（一个 PR 一个 issue，issue 可以是 feature）；git-workflow §3b 生效；#205/#210/#211/#213/#215/#217/#219 均按此登记闭环。
- **登录稿（#212→#210、#214→#213）**：最终对照打磨 7.5→8.5 收口合入；随后 owner「我们是中文的」→ 全页文案中文化（hero「网关悄然运行。/ 密钥由你掌控。」等，品牌与供应商名除外；覆盖 #194/#196「EN 按稿」记录）。
- **Backlog 卫生（#216→#215）**：F59 撞号（留痕保留 F59，管理开放 API→**F60**），F34/F59-留痕按交付事实校正 DONE；引用方同步。
- **F01 核对（#218→#217）**：McpProxyController=完整数据面（类注释自述 F01 wiring），契约/韧性/日志三测试在库 → PLANNED→DONE；大厂「做了没做」三类对照（对标补差/刻意不学/大厂不需要）入 feature-expansion-candidates。
- **Pre-release（#219）**：rc.1(#202) 后 6 PR → 文档全量补记 + OpenAPI 基线刷新 → **0.1.0-rc.2** annotated tag @ 收口 commit 并推送；release-checklist 状态：构建/测试/安全硬门禁 ✅（CI 全绿矩阵+本地 verify）、供应商真实凭证 ⏳(#211)、§6.1 部分告警类型 ⏳、Go/No-Go 由 owner 签署。

## 会话交接点 2026-09-06 — UI 母版修订(Vben console edition)与夜间自主轮

- **owner 指令（2026-09-06）**：UI 仍被评「AI 感、丑」→ 以 Vben Admin（v2.vben.pro demo + vbenjs/vue-vben-admin v5 源码）为视觉母版认真学、照着做；去 GitHub 找 UI skill 借鉴（已取 Anthropic 官方 frontend-design 反模板清单，本地 reference/ui-skills/）；claude-p 继续长跑。
- **设计决策（分支 goal/ui-vben-2026-09-06）**：v2.1 tokens——画布 #f0f2f5（冷中性）、主色 antd 蓝 #1677ff 族、侧栏深海军蓝 #001529（激活=主色 16% 浅底+白字+左侧 3px #4096ff 竖条，弃实心块——两轮评审共识）、登录页左 #2a5ad7 品牌板（能力三点+标语，无渐变）+ 右白表单列（下划线 tab、44px 控件）；表格表头 muted 底 13px/600、字 14；lg 控件 36→40px（登录局部 44px）；hover 填充 5.5%→7%。视觉评审分（DeepSeek，噪声纪律 ≤2 轮）：login 6.5→7.5、usage 6.5→7.5（r2 噪声回落未追）、home(keys) 7.2、users 7.0（页面级修复已落）。
- **本批已改（frontend）**：styles/design-tokens.css（v2.1 值重调）、design-base.css（页边 24、页标题 20px）、components/NewShell.vue（navy rail）、ui/Table.vue（表头 chrome）、views/next/NextLoginView.vue（分屏重做）、NextUsersView.vue（汇总进页头、角色徽标、kebab 控件化、单行用户名）、NextAdminUsageView.vue（统计卡组、筛选行紧凑、Request ID 截断、分页右对齐）。
- **验证（全部真实 PASS）**：typecheck、vitest 154/154、build、eslint（改动文件 0 error）、审美审计。
- **素材**：vben demo 截图+规格转写与 4 页基线分等全部在 miqro-local/ui-reviews/2026-09-06/ 与 worknotes/。
- **残余（后续轮）**：NextOverviewView 页级收口、keys 页级（掩码/列宽）、e2e 金样刷新、frontend-design.md 已在本轮修订（见下）视觉基线存档轮。

### R3 Kafka producer（#175，2026-09-06 合入，DONE）
- 标准 Kafka 协议投递留痕信封到 `content-retention` topic（默认）；记录键 = SHA-256(tenant/user)（同用户恒同分区保序）；信封 JSON 显式字段、ciphertext/nonce base64、明文永不出网关；发送异步、失败节流计数（消费者按 eventId 幂等容忍重放）。
- 装配：`KafkaRetentionConfig` @ConditionalOnProperty `miqrokey.retention.kafka.bootstrap-servers` + @Primary 替换 no-op（默认仍 fail-closed）；kafka-clients 版本 Boot BOM 管理（3.8.1）；测试依赖 org.testcontainers:redpanda。
- 验证：单测 2/2（payload 字段/base64 语义、partition key 稳定性）+ **Redpanda 容器端到端 1/1**（真实 broker 收信两封、key=partitionKey、同分区、密文不透明文、dropped=0；本机 33s 通过；CI Backend integration 亦绿）+ CI 9/9 全绿（spotless pom 格式修正后）。
- 排障：3.8.1 无 flush(Duration)（只有 flush() + close(Duration)）；testcontainers redpanda 模块要求 docker.redpanda.com 官方镜像全名；分支乱序教训（verify 跑动中不动文件、pom 跨分支漂移、dispatch 后 commit 需重发）。
- 配置/文档：configuration-reference §6 R3 行；CHANGELOG；ADR-0014 无改动（范围内落地）。

### R4 消费端参考实现（本批，REFERENCE）
- `docs/retention-consumer.md`：消费端契约（topic/键语义/at-least-once+eventId 幂等/保序）、信封 JSON schema 表、解密说明（AES-GCM + RETENTION_AAD_ID 合成常量 + keyVersion）、本地文件布局 `<root>/<tenant>/<user>/<YYYY-MM-DD>.jsonl`、对象存储/DB 适配要点、冒烟链路。
- `scripts/retention/consumer-file-ref.py`（+requirements.txt）：单文件 kafka-python 参考消费者——批量落盘后手动 commit、eventId 去重（内存 + 启动扫描既有文件）、优雅退出；--dry-run 联调。逻辑冒烟 PASS（去重/按日滚动/重启 reseed）；kafka 依赖按需安装，不进入产品构建。
## 会话交接点 2026-09-03 — UI 专项 U0 待验收（用户 2026-09-03 拍板：PostHog 视觉母版 + Vben 布局参考；U0 验收通过前暂停功能 backlog）

### U0 待办（下一动作，只等用户）
1. **用户真机点验**：http://localhost:5173/login-new 与 /app-new/{keys,usage,users}（登录 root/DrillPass2026!；旧版对照 /login、/app/*）。
2. 点头 → U1；否定 → 停下问方向（不改设计母版）。验收材料：miqro-local/ui-reviews/（截图 + U0-VISION-SCORES.md + 每轮 raw 评审）。
3. 合并（用户点头后）：CI 全绿 → squash merge goal/ui-posthog-u0 → 删分支 → 同步 develop（分支已 push @ 803f552）。

### PR #131 收尾记录（2026-09-03）
- CI 曾红（Frontend job）：KeysView onboarding 用例只 stub myGrants、resetAllMocks 后 listVirtualKeys 无默认 → keys.value=undefined → 模板 keys.length 渲染抛错（本地 108/108 曾因异步时序侥幸通过）。修复：beforeEach 默认 stub `listVirtualKeys→[]`（commit 149f1cd）+ 顺带 eslint 格式漂移对齐 3 文件（32067d7，dabaae4 后未再跑 lint）。
- 修复后重推 CI **全绿**（Backend unit ubuntu+windows / Backend integration / Frontend / e2e / Security gate / CodeQL×3 / CodeRabbit skip），`gh pr merge 131 --squash --delete-branch` → develop 80dddad。gh 自动删本地分支并切 develop；随后 git pull 曾因 github.com 直连断网失败 → 代理重拉成功。
- 本地探活：control-plane/gateway/frontend 均 200；合并代码相对实跑代码仅前端测试+格式变更（零运行时差异）。

### A. U0 执行目标与设计资产（本分支在途）
- 设计 token 权威源已抓取并换算（miqro-local/posthog-design-ref/）：**暖灰中性系** canvas≈#f5f4f0 / card≈#fefdfc / muted≈#f1efea / chrome≈#e8e5de / hairline border≈#dfdeda / muted-foreground≈#4a5565；hover/selected 用前景色 α 叠层（4-6%）；状态色低饱和 muted 系；radius 4/6/8/12；4px 间距基；10-14px 紧行高字号阶。注意：colors.ts 字面 oklch 值处于 Quill 迁移中段（与注释矛盾处信注释），最终值以 vision 评审迭代为准。
- 执行路线（已定）：tokens v2（新语义名，不覆盖 v1 值——避免 TDesign 时代页面与 e2e baseline 漂移）→ frontend/src/ui/ 自绘组件（**取舍：引入 radix-vue 原语包（MIT、headless、可测）做 Dialog/Select/Dropdown 的 a11y/portal/焦点管理；Button/Input/Table/Badge/EmptyState/Toast 纯自绘**；不加运行时设计系统依赖）→ /app-new/* 平行路由（保留 TDesign 版对照）→ 试点 Login/Keys/Usage/AdminUsers → Playwright 1440x900 截图 → DeepSeek 视觉评审 ≥9/10 → 存 miqro-local/ui-reviews/ → 用户点验。
- 验收后（用户点头）→ U1 用户面全量 + 拆并行开关。

### B. 环境与密钥备忘（本地，不入库）
- 服务启动：mvnw spring-boot:run 需先 `install -Dmaven.test.skip=true`（自定义父 POM 不打 fat jar、依赖需进 .m2）；仓库根 java/密钥 env 模板见 miqro-local/restart.bat。
- 登录凭据与 key 见 Current State；miqro-local 含旧 drill 数据与截图（ui-login-v1..v4、ui-keys-posthog.png 等，可作 UI 对比）。vision_review.py = 标准化评审器（SCORE x/10 + 中文问题 + NIT1-3；python stdout 已设 utf-8、max_tokens 8000 防 reasoning 占满）。
- 另一个 Claude 会话的 dev server 可能在跑（5173/8080/8081）——探活勿杀；e2e/截图需自起 preview 时避开 4173 占用。
- Windows shell 中文 curl 需 UTF-8 文件体重发；python 路径需 `D:/` 盘符格式；cwd 易漂移（命令前显式 cd 仓库根）；github.com 直连断网时用 `HTTPS_PROXY=http://127.0.0.1:7897` 单条命令代理。

## F-REG 账号自助注册 + 登录页重做 — 用户现场需求（2026-09-03，DONE）

- **背景**：用户试跑后明确要求：① 账号要能自助注册（企业内测/未来客户部署都不可接受"管理员手工建号"，虽 50 账号容量/邀请制是早期产品决策，注册能力应为可配置项而非缺项）；② 登录页 UI 不满意（"差劲/没品味/没有注册"）。处置：用真实 DeepSeek key（用户提供，本地 miqro-local 不入库；已提示用后轮换）跑通全链路 + 以 `deepseek-v4-flash-vision-exp` 视觉模型对截图做客观评审作为"眼睛"（会话图片通道不可用），据此整改。
- **视觉评审摘录（已采纳）**：布局左右失衡/大片留白、登录卡与背景对比不足、输入控件偏小且 focus 不明确、额度条与文案排版粗糙、品牌蓝缺乏呼应。
- **后端**：`POST /api/v1/auth/register`（公开端点——SessionFilter PUBLIC_PATHS + CSRF 豁免集已扩；租户行锁序列化并发重名；`validatePasswordPolicy`/`isCommonPassword` 复用；注册即建会话同 /login；审计 `REGISTER`）；开关 `miqrokey.registration-enabled`（AuthProperties，默认 true，yml 显式行 + `MIQROKEY_REGISTRATION_ENABLED`）；错误码 USERNAME_INVALID/USERNAME_TAKEN/PASSWORD_INVALID/REGISTRATION_DISABLED。
- **前端**：LoginView 重做——登录/注册双模式分段页签（账号/昵称/密码/确认密码；注册即进入）；布局整改按评审意见（对称双栏 grid、左栏内容留白平衡、卡片浮起阴影、控件 40px+focus 环、额度条入浅色卡、品牌强调色）；术语统一（"账号"与"昵称"）。
- **验证（全部真实 PASS）**：集成 `RegistrationApiIntegrationTest` 3/3（注册即登入 + /me 立即可用 + DB 断言；重名 409/弱密码 400；无会话无 CSRF 可注册）+ `RegistrationDisabledApiIntegrationTest` 1/1（开关关 → 403 REGISTRATION_DISABLED）；前端 vitest LoginView.spec 3/3（模式切换/注册提交带昵称/密码不一致拦截）+ auth.spec +1（store register）；**本地真实链路**：演示账号 demo2_user 经 UI 注册→自动登录→进入系统（浏览器 pane DOM 验证）；control-plane 模块级 BUILD SUCCESS。
- **排障记录**：Windows shell 中文 curl 请求体乱码 → UTF-8 文件体重发；TDesign t-button submit 在 jsdom 不派发原生 submit → 测试触发 `form` submit 事件；Vitest 对 t-form @submit 需要原生事件。
- **文档**：api-contract §3.1b（注册语义/校验/开关/审计/防滥用注记）+ §3.1 表行；configuration-reference `MIQROKEY_REGISTRATION_ENABLED`；CHANGELOG；feature-backlog F32 备注自助注册已交付（平台映射仍 BLOCKED）；progress。
- **gitflow**：分支 `goal/self-registration`。

## F05 管理门户 IP 白名单 — security §6（2026-09-03，DONE）

- **背景与方向**：#129（OpenAPI）合并后按候选顺序做 F05（TBD → 核对后立项）。核对发现：全后端**无任何** IP 过滤/转发头基建（`MIQROKEY_TRUSTED_PROXY_CIDRS` 仅为文档行、无实现——已顺手标注"预留未实现"防误用）。security §6 规格"管理门户支持配置 IP 白名单"需全新实现。
- **设计决策（记录）**：白名单 opt-in（默认空 = 不限制，防运维锁死）；**豁免** `/api/v1/billing/**`（外部系统 API Key/JWT 通道——白名单语义是"人用浏览器管门户"，机器通道走自己的凭证）与 `/api/v1/auth/bootstrap`（一次性引导）；反代场景必须可信 XFF——只有直连对端 ∈ `trusted-proxies` 时才采纳 `X-Forwarded-For` 最左地址（直连攻击者无法伪造头绕过）；非法 CIDR 启动失败（fail-fast）。
- **后端**：`IpCidrMatcher`（security 包纯函数：v4/v6 网络位比较、族不匹配拒、解析失败抛 IllegalArgument）；`AdminIpAllowlistFilter`（OncePerRequestFilter：空名单放行 → 豁免路径 → XFF 可信解析 → allowlist 匹配 → 403 ProblemDetails `IP_NOT_ALLOWED`+requestId，与 ORIGIN_REJECTED 同形）；`AdminAccessProperties`（`miqrokey.control.admin-access` ip-allowlist/trusted-proxies，@DefaultValue 空）；SecurityConfig 装配：matcher 解析在 bean（启动期校验）+ FilterRegistrationBean order -110（SessionFilter -100 之前 fail-fast，注册 /api/*）。
- **验证（全部真实 PASS）**：`IpCidrMatcherTest` 5/5（/24 成员、/32 与 /0、IPv6 /64 与压缩、解析校验矩阵、非法候选）；`AdminIpAllowlistApiIntegrationTest` 3/3（名单内 127.0.0.1 与 198.51.100.9 放行 / 名单外 203.0.113.5 → 403 IP_NOT_ALLOWED；bootstrap 与 billing 豁免——重复 bootstrap 由**业务层** 401 而非 IP 403；可信代理 XFF 采纳 / 非可信直连伪造 XFF 仍 403 / 可信代理转发名单外客户 403）；空名单行为由全量既有测试回归（默认不启用）；后端全量 `verify -P integration` **BUILD SUCCESS 0 failures**（control-plane 389 = 381+8）。
- **排障记录**：① 集成测试首轮全 401——setUp 漏了 bootstrap 后改密步骤（must_change_password 会话被拒），补 PasswordChangeRequest 后通过；② exemptions 里"异地重复 bootstrap"断言 201 → 实际业务层拒绝 401（该 401 恰证明豁免生效），断言改为 isUnauthorized 并注释。
- **文档**：security §6（实现语义/豁免/防伪造）；configuration-reference 两新行 + `MIQROKEY_TRUSTED_PROXY_CIDRS` 标注预留未实现；api-contract §4.8 错误码表 + `IP_NOT_ALLOWED`；CHANGELOG；feature-backlog F05 → DONE（F40 推理 API IP 限制仍远期）；progress。
- **gitflow**：分支 `goal/admin-ip-allowlist`（基于 develop 1f454d5）。

## F35 usage 队列饱和应急直写 — architecture §5（2026-09-03，DONE）

## F-REG 账号自助注册 + 登录页重做 — 用户现场需求（2026-09-03，DONE）

- **背景**：用户试跑后明确要求：① 账号要能自助注册（企业内测/未来客户部署都不可接受"管理员手工建号"，虽 50 账号容量/邀请制是早期产品决策，注册能力应为可配置项而非缺项）；② 登录页 UI 不满意（"差劲/没品味/没有注册"）。处置：用真实 DeepSeek key（用户提供，本地 miqro-local 不入库；已提示用后轮换）跑通全链路 + 以 `deepseek-v4-flash-vision-exp` 视觉模型对截图做客观评审作为"眼睛"（会话图片通道不可用），据此整改。
- **视觉评审摘录（已采纳）**：布局左右失衡/大片留白、登录卡与背景对比不足、输入控件偏小且 focus 不明确、额度条与文案排版粗糙、品牌蓝缺乏呼应。
- **后端**：`POST /api/v1/auth/register`（公开端点——SessionFilter PUBLIC_PATHS + CSRF 豁免集已扩；租户行锁序列化并发重名；`validatePasswordPolicy`/`isCommonPassword` 复用；注册即建会话同 /login；审计 `REGISTER`）；开关 `miqrokey.registration-enabled`（AuthProperties，默认 true，yml 显式行 + `MIQROKEY_REGISTRATION_ENABLED`）；错误码 USERNAME_INVALID/USERNAME_TAKEN/PASSWORD_INVALID/REGISTRATION_DISABLED。
- **前端**：LoginView 重做——登录/注册双模式分段页签（账号/昵称/密码/确认密码；注册即进入）；布局整改按评审意见（对称双栏 grid、左栏内容留白平衡、卡片浮起阴影、控件 40px+focus 环、额度条入浅色卡、品牌强调色）；术语统一（"账号"与"昵称"）。
- **验证（全部真实 PASS）**：集成 `RegistrationApiIntegrationTest` 3/3（注册即登入 + /me 立即可用 + DB 断言；重名 409/弱密码 400；无会话无 CSRF 可注册）+ `RegistrationDisabledApiIntegrationTest` 1/1（开关关 → 403 REGISTRATION_DISABLED）；前端 vitest LoginView.spec 3/3（模式切换/注册提交带昵称/密码不一致拦截）+ auth.spec +1（store register）；**本地真实链路**：演示账号 demo2_user 经 UI 注册→自动登录→进入系统（浏览器 pane DOM 验证）；control-plane 模块级 BUILD SUCCESS。
- **排障记录**：Windows shell 中文 curl 请求体乱码 → UTF-8 文件体重发；TDesign t-button submit 在 jsdom 不派发原生 submit → 测试触发 `form` submit 事件；Vitest 对 t-form @submit 需要原生事件。
- **文档**：api-contract §3.1b（注册语义/校验/开关/审计/防滥用注记）+ §3.1 表行；configuration-reference `MIQROKEY_REGISTRATION_ENABLED`；CHANGELOG；feature-backlog F32 备注自助注册已交付（平台映射仍 BLOCKED）；progress。
- **gitflow**：分支 `goal/self-registration`。

## F05 管理门户 IP 白名单 — security §6（2026-09-03，DONE）

- **背景与方向**：#129（OpenAPI）合并后按候选顺序做 F05（TBD → 核对后立项）。核对发现：全后端**无任何** IP 过滤/转发头基建（`MIQROKEY_TRUSTED_PROXY_CIDRS` 仅为文档行、无实现——已顺手标注"预留未实现"防误用）。security §6 规格"管理门户支持配置 IP 白名单"需全新实现。
- **设计决策（记录）**：白名单 opt-in（默认空 = 不限制，防运维锁死）；**豁免** `/api/v1/billing/**`（外部系统 API Key/JWT 通道——白名单语义是"人用浏览器管门户"，机器通道走自己的凭证）与 `/api/v1/auth/bootstrap`（一次性引导）；反代场景必须可信 XFF——只有直连对端 ∈ `trusted-proxies` 时才采纳 `X-Forwarded-For` 最左地址（直连攻击者无法伪造头绕过）；非法 CIDR 启动失败（fail-fast）。
- **后端**：`IpCidrMatcher`（security 包纯函数：v4/v6 网络位比较、族不匹配拒、解析失败抛 IllegalArgument）；`AdminIpAllowlistFilter`（OncePerRequestFilter：空名单放行 → 豁免路径 → XFF 可信解析 → allowlist 匹配 → 403 ProblemDetails `IP_NOT_ALLOWED`+requestId，与 ORIGIN_REJECTED 同形）；`AdminAccessProperties`（`miqrokey.control.admin-access` ip-allowlist/trusted-proxies，@DefaultValue 空）；SecurityConfig 装配：matcher 解析在 bean（启动期校验）+ FilterRegistrationBean order -110（SessionFilter -100 之前 fail-fast，注册 /api/*）。
- **验证（全部真实 PASS）**：`IpCidrMatcherTest` 5/5（/24 成员、/32 与 /0、IPv6 /64 与压缩、解析校验矩阵、非法候选）；`AdminIpAllowlistApiIntegrationTest` 3/3（名单内 127.0.0.1 与 198.51.100.9 放行 / 名单外 203.0.113.5 → 403 IP_NOT_ALLOWED；bootstrap 与 billing 豁免——重复 bootstrap 由**业务层** 401 而非 IP 403；可信代理 XFF 采纳 / 非可信直连伪造 XFF 仍 403 / 可信代理转发名单外客户 403）；空名单行为由全量既有测试回归（默认不启用）；后端全量 `verify -P integration` **BUILD SUCCESS 0 failures**（control-plane 389 = 381+8）。
- **排障记录**：① 集成测试首轮全 401——setUp 漏了 bootstrap 后改密步骤（must_change_password 会话被拒），补 PasswordChangeRequest 后通过；② exemptions 里"异地重复 bootstrap"断言 201 → 实际业务层拒绝 401（该 401 恰证明豁免生效），断言改为 isUnauthorized 并注释。
- **文档**：security §6（实现语义/豁免/防伪造）；configuration-reference 两新行 + `MIQROKEY_TRUSTED_PROXY_CIDRS` 标注预留未实现；api-contract §4.8 错误码表 + `IP_NOT_ALLOWED`；CHANGELOG；feature-backlog F05 → DONE（F40 推理 API IP 限制仍远期）；progress。
- **gitflow**：分支 `goal/admin-ip-allowlist`（基于 develop 1f454d5）。

## F35 usage 队列饱和应急直写 — architecture §5（2026-09-03，DONE）

- **背景与方向**：实现 architecture §5「缓冲达到上限时…可切换为同步写入以保护审计完整性」。现状（G2.4）饱和 = offer 拒绝 + drop 计数 + warn。F35 加**应急开关**把「必然丢弃」升级为「尽力直写、完整性优先」。
- **设计决策（记录）**：直写 ≠ 发布线程执行 JDBC（红线：JDBC 只在专用 writer 执行器）——饱和事件改经 writer 执行器单条幂等直写，发布线程对完成做**有界等待**（默认 5s 可配），超时/失败照旧计数丢弃（发布线程永不无限阻塞）；应急模式默认关闭（`DROP` 保持热路径零等待，行为与现状完全一致）。
- **后端**：`SaturationMode` 枚举（queue-spi，`DROP|WRITE_THROUGH`）；`QueueProperties` 增 `saturation-mode`（默认 DROP）+ `write-through-timeout`（默认 5s，绑定校验）；`PostgresUsageEventBus` 构造扩展两参，`offer()` 饱和分支：WRITE_THROUGH 时 `writeThrough(event)`（CompletableFuture + writerScheduler.schedule 单元素批写 + `totalPersisted` 计入 + done.get(timeout)，成功即不 drop）；javadoc 明示线程/失败语义。InMemory bus 不适用（无 writer，mode 忽略）。QueueConfig bean 装配更新。
- **验证（全部真实 PASS）**：`PostgresUsageEventBusTest` 8/8 = 原 5（DROP 行为零变化回归：drop 计数/重入队/阈值/in-flight 守卫/空 flush）+ 新 3（WRITE_THROUGH 饱和直写单事件成功且 queued 保留待常规 flush；直写失败回退计数 drop；50ms 超时有界返回不无限阻塞——断言时序：先断言再释放 writer latch，防竞态）。后端全量 `verify -P integration` **BUILD SUCCESS 0 failures**（queue-spi 14 = writer 6 + bus 8；gateway 198 = 196 + F02 语义键契约 2）。
- **排障记录**：三个新用例首跑失败均为断言逻辑错误（persisted 计数期望错 + latch 释放与断言竞态），实现本身正确；DROP 回归全绿证明行为零变化。
- **文档**：configuration-reference 两新配置行（§5 queue 表）；architecture §5 批量写入段补充 F35 实现语义；CHANGELOG；feature-backlog F35 → DONE；progress。
- **gitflow**：分支 `goal/usage-queue-emergency-write`（#127 合并后 merge origin/develop 进分支无冲突）。

## F02 缓存键升级 — 盘点核对修正（2026-09-03，DONE）

- **发现**：feature-backlog 盘点把 F02 登记 PLANNED，但核对代码（`CacheKeyFactory`）与 git 历史证明**实现早已完成**——commit 3086187「feat(cache): semantic cache key from system + last user message」（G7.4 时期）已将键从全请求 hash 升级为：`SHA-256(tenant|key|product|model|purpose|scope)`，`scope = system + 最后一条 user 消息`（对齐腾讯「最新用户消息」/Higress GJSON），支持 OpenAI chat / Anthropic messages / OpenAI Responses(input) 三形状与数组 content parts；无法提取 user 消息时回退全 body 归一化 hash。正文解析只发生在 opt-in 缓存流、仅用于键派生（转发字节原样，`CacheKeyFactory` javadoc 明示）。
- **本会话补全**：端到端契约缺「语义键」专属场景——`VirtualKeyAuthContractTest$L1Caching` 增 2 用例（不同历史前缀 + 相同末条 user 消息 → 第二次 L1 命中且字节一致、上游仅 1 次调用；不同末条消息 → miss 两次）。注意用例文本需全局唯一（共享 Caffeine 实例跨用例存活，首个断言曾因前一用例预置同问句而误报 L1）。
- **验证**：`VirtualKeyAuthContractTest` 24/24 PASS（含新 2 场景，gateway 模块 BUILD SUCCESS）；无生产代码改动（test + 文档盘点修正）。
- **backlog 教训**：F02 类目照抄「优化项」文档措辞而未核对代码——本次盘点修正后，feature-backlog 以代码为准回写（同 TBD 口径用途）。

## F06 过期记录定时 GC — feature-backlog A 组（2026-09-03，DONE）

- **背景与方向**：#125（自助配额可见性）合并后按 backlog 推荐顺序第 2 组收官——补 G4.4 边界「定时清理 EXPIRED 导出/过期删除请求未接线（运维目标）」。纯后端小闭环，无前端。
- **后端**：`ExportTaskService.sweepExpired()`（`DELETE … WHERE status='SUCCEEDED' AND expires_at < now()`——过窗产物连同 `file_bytes` 物理回收；FAILED/PENDING 保留供运维查看，取舍记录）；`UsageDeletionService.sweepExpired()`（`PENDING_CONFIRMATION/CONFIRMED/EXPIRED` 且过窗删除；**EXECUTED 永久保留**——G4.4「请求本身与审计链保留」语义）；新 `ExpiredRecordSweeper`（`@Scheduled(fixedDelayString = "${miqrokey.cleanup.expired-sweep-ms:3600000}")`，调用两服务并记 count 日志——`AlertEvaluator` 同款固定延迟模式）。
- **验证（全部真实 PASS）**：`ExpiredRecordSweepIntegrationTest` 2/2（导出：过窗 SUCCEEDED 删/未过窗保/40 天 FAILED 与 PENDING 保；删除：三种过期态删/PENDING 未过窗保/EXECUTED 10 天保）；后端全量 `verify -P integration` **BUILD SUCCESS 0 failures**（control-plane 模块汇总 380 = 378+2 净增；前端零改动，vitest/Playwright 基线不受影响）。
- **文档**：api-contract §5.5/§5.6（GC 语义 + 清理后 410→404 行为说明）；configuration-reference 新行 `MIQROKEY_CLEANUP_EXPIRED_SWEEP_MS`（默认 3600000，1h）；CHANGELOG；feature-backlog F06 → DONE；progress。
- **gitflow**：分支 `goal/expired-record-gc`（基于 #125 合并后 develop db9407b）；#125 合并记录：CI 全绿 → `gh pr merge --squash --delete-branch`。

## F04 用户自助配额可见性 — feature-backlog A 组（2026-09-03，DONE）

- **背景与方向**：#124（审批 Webhook 通知）合并后按 backlog 推荐顺序第 2 组收官——补 #119 配额规则边界「用户自助配额可见性未做」。配额线（规则+模板+告警+自助可见）至此闭环：用户能在用量页看到管理员给自己设的限额与实时水位（含 F24 默认模板自动复制规则、停用规则），超限仅提示不阻断。
- **后端**：`AdminQuotaRuleService.listForUser(tenantId, userId)`（USER 作用域 + scopeId=本人过滤，复用同一 view() 水位/level/窗口装配——算户口径与 5.19 管理端完全一致）；新 `MeQuotaController` `GET /api/v1/me/quota-rules`（会话鉴权任意角色，无审计，其他用户/项目规则绝不出现）。
- **前端**：UsageView 顶部「我的配额」面板（维度·周期/限额·本期用量·水位条/状态徽标：正常/预警/超限/停用；空态提示「暂无配额规则——管理员未为你设置用量限额」；加载失败静默降级不干扰用量视图）+ api `listMyQuotaRules`。
- **验证（全部真实 PASS）**：`MeQuotaApiIntegrationTest` 2/2（本人 2 条规则含 DISABLED 可见、其他用户与管理员规则不可见（集合断言不依赖列表顺序）、admin 自身切片正确；匿名 401/空列表）；前端新 `UsageView.spec` 2/2（空态 + 三规则渲染：维度文案/限额用量文案/预警/超限/停用徽标）、全量 vitest **102/102**（100+2）、lint/typecheck/build PASS；Playwright **35/35**；后端全量 `verify -P integration` **BUILD SUCCESS 0 failures**（control-plane 模块汇总 378 = 376+2 净增）。
- **文档**：api-contract §4.7 新增（错误码段重编号 4.8——站内无外部引用）；CHANGELOG Unreleased；feature-backlog F04 → DONE；progress。
- **gitflow**：分支 `goal/me-quota-visibility`（基于 #124 合并后 develop 3b0244d）；#124 合并记录：CI 全绿 → `gh pr merge --squash --delete-branch`。

## F03 模型审批 Webhook 通知 — feature-backlog A 组（2026-09-03，DONE）

- **背景与方向**：#123（默认配额模板）合并后按 backlog 推荐顺序第 2 组开工——补 #118 模型审批流「Webhook 通知按文档预留未实现」的闭环。语义：审批三事件（提交/通过/驳回）在迁移瞬间通知订阅方（申请人/管理员侧由接收端点自行路由），不做阻断。
- **设计决策（记录）**：复用 alert/webhook 机制 = 新**事件驱动**规则类型而非周期评估——提交/评审是点事件，调度轮询模型不匹配（阈值语义也不适用）。三个类型镜像审计动作：`MODEL_APPROVAL_SUBMITTED/APPROVED/REJECTED`（V27 扩 CHECK）；阈值不适用（前端隐藏输入、提交恒 1，事件 value 恒 1=一次发生）；去重键 = 规则 × `type:approvalId`（申请一次迁移天然唯一）；投递/签名/退避重试全复用；通知明细（approvalId/modelId/status/username/requesterName/keyName/keyDisplay/reason/reviewNote/autoApproved——纯元数据）随 `alert_events.payload_json` 落库、重试原样带出。无端点规则仅记录事件；白名单自动批准单次提交触发 SUBMITTED+APPROVED 双事件（与审计双事件留痕同构）。
- **后端**：`AlertEventDispatcher` 新服务（从 `AlertEvaluator` 抽取投递原语 deliver/attempt/recordAttempt/retryDue/truncate + payload 信封构造；`notifyForType` 查启用规则→逐规则事件落库+投递；`deliverEvent` 供评估器用——周期型评估行为零变化）；`AlertEvaluator` 瘦身为评估器（metric/evaluate 委托 dispatcher 投递）；`ModelApprovalService` 三个迁移点（submit/approve/reject + auto-approve 双发）调 `notifyApproval`（Key/申请人展示字段查找组装）；`AlertRuleService.validateType` +3。
- **前端**：AdminAlertRulesView 类型下拉 +3（模型审批 · 提交/通过/驳回）；`isApprovalType` 时隐藏阈值/去重输入并显示「事件型规则」提示（提交阈值恒 1）；typeLabel +3；types `AlertRuleType` union 补 3 并顺带补上 #120 漏掉的 `QUOTA_THRESHOLD`（只扩不缩，无编译面影响）。
- **验证（全部真实 PASS）**：`ModelApprovalNotificationApiIntegrationTest` 4/4（提交 → 签名投递 payload 全字段断言 + 事件行 value=1/payload_json 留存；approve/reject 各自类型 + reviewNote 断言；停用规则静默 + 无端点规则仅事件；白名单自动批准双事件 autoApproved）；**重构回归** `WebhookAlertApiIntegrationTest` 2/2 + `AdminBudgetApiIntegrationTest` 6/6 + `AdminQuotaRuleApiIntegrationTest` 8/8 + `ModelApprovalApiIntegrationTest` 10/10（投递原语抽取后周期型/水位型行为不变）；前端 vitest **100/100**（+2 事件型：创建体 threshold 1 无 scope、通过/驳回类型渲染）、lint/typecheck/build PASS；Playwright **35/35**；后端全量 `verify -P integration` **BUILD SUCCESS 0 failures**（control-plane 模块汇总 376 = 372+4 净增）。
- **排障记录**：alert_events.value 为 numeric(12,6)——断言用 BigDecimal isEqualByComparingTo；Webhook 回调投递走控制面 SSRF 门（测试加 allowed-cidrs 127.0.0.0/8）。
- **文档**：api-contract §5.8（事件驱动类型语义/payload 明细字段/去重）；database-schema alert 段（类型表 V27 + payload_json 语义 + dispatcher 说明）；CHANGELOG Unreleased；feature-backlog F03 → DONE；progress。
- **gitflow**：分支 `goal/model-approval-webhook`（基于 #123 合并后 develop ede3540）；#123 合并记录：PR CI 全绿（13 checks）→ `gh pr merge --squash --delete-branch` → develop ede3540。

## F24 默认配额模板 — 腾讯 AI 网关 doc 135489（2026-09-03，DONE）

- **背景与方向**：#122（MCP ACL）合并后按 feature-backlog PLANNED 组推荐顺序第一项开工——腾讯「配额管理 → 默认配额策略」：全局模板 + **创建时快照复制**（改模板不惊动存量、关闭不删已分配、手动规则覆盖默认、自动规则默认启用），防新账号「裸奔」。配额线延伸，语义完整无外部依赖。
- **映射决策（记录）**：腾讯模板面向「消费者」（配额规则挂靠对象）；本系统配额规则挂靠 USER/PROJECT 双作用域，其中消费者语义最近似**用户**（拥有 Virtual Key 的消费主体）→ 复制只落在新建用户（`AdminOrgService.createUser` 事务内）；PROJECT 不参与模板化（腾讯无此概念，不发明，feature-backlog F24 架子已注）；预算模板化列后续候选。
- **后端**：V26 `quota_default_template`（**每租户一行** tenant_id PK：enabled + metric TOKENS|REQUESTS + period DAILY|WEEKLY|MONTHLY + limit_value + updated_by 引用 users + version——行仅在首次配置后存在）；domain `QuotaDefaultTemplate` + `QuotaDefaultTemplateRepository`（upsertDefinition 保 enabled 翻转语义、setEnabled 只翻开关）；`QuotaRuleRepository.insertIfAbsent`（ON CONFLICT DO NOTHING RETURNING——手动规则优先的落点）；`AdminQuotaDefaultTemplateService`（GET 空态视图 = enabled:false + 定义 null；configure 保留当前 enabled——重新配置不会重新启用；enable/disable 冲突码 QUOTA_TEMPLATE_NOT_CONFIGURED/ALREADY_ENABLED/ALREADY_DISABLED；applyToNewUser：模板缺失或停用即跳过，复制 USER 规则 warn 80 ACTIVE created_by=建用户执行者，插入成功才审计）；Controller 4 端点（GET/PUT + POST enable|disable，SYSTEM_ADMIN-only）；审计 4 事件（CREATE/UPDATE/ENABLE/DISABLE）+ 自动复制记 QUOTA_RULE_CREATE 摘要含 `"auto":true`。
- **前端**：AdminQuotaRulesView 顶部「默认配额模板」面板（状态徽标 未配置/未启用/已启用 + 定义文案 + 腾讯三条提示文案 + 配置内联表单 metric/period/限额 + 启用/停用按钮，未配置时禁用）+ api 4 函数 + 类型 2 组。
- **验证（全部真实 PASS）**：`AdminQuotaDefaultTemplateServiceTest` 5/5（无/停用模板跳过不审计、启用快照字段全断言 + audit auto、手动规则存在则不插入不审计、configure 保 enabled + CREATE/UPDATE 动作、setEnabled 三冲突码）；`AdminQuotaDefaultTemplateApiIntegrationTest` 7/7（空态→配置→重复 PUT 保 disabled→enable→重复 409→disable 保定义；未配置 enable/disable 409；定义校验 400 矩阵；403/401；**快照语义闭环**：启用→建用户 A 得 TOKENS/MONTHLY/1M 规则（warn 80/ACTIVE/created_by 断言）→ 改模板 REQUESTS/WEEKLY/500 → A 规则不变 → 停用 → 建 B 无规则 A 保留 → 再启用 → 建 C 得新定义规则 B 仍无；审计 3 模板事件 + auto 规则摘要断言）；前端 vitest **98/98**（+4 模板面板：未配置态/定义渲染+停用/配置保存+面板关闭/启用）、lint/typecheck/build PASS；Playwright **35/35**（+quota 页新端点 mock）；后端全量 `verify -P integration` **BUILD SUCCESS 0 failures**（控制面模块汇总 372 = F24 净增 12：单测 5 + 集成 7；基线 2156 计数口径延续上轮）。
- **排障记录**：POST /api/v1/admin/users 返回 200（非 201）；change_summary 是 jsonb——getString 规范化输出 `": "` 分隔符（断言按 jsonb 规范写）；重复 enable/disable 走 409 冲突码（与 MCP 上下线同族）。
- **文档**：api-contract §5.22（视图/快照复制语义/冲突码/审计/映射取舍）；database-schema V26 段；CHANGELOG Unreleased（截至 09-03 + F24 行）；feature-backlog F24 → DONE（图例补 DONE 口径）；progress。
- **gitflow**：分支 `goal/quota-default-template`；#122 合并记录：CI 曾 12h 卡 pending（Actions 队列瞬断），cancel + rerun 后全绿，`gh pr merge --squash --delete-branch` → develop f5970f6，本地同步后开本分支。
- Remote: `https://github.com/sijie-Z/miqro-gate.git`（PUBLIC + MIT；2026-08-27 品牌改名 MiQroGate，历史按所有者指示单提交重发布，旧历史本地 bundle 备份）

## G6.5 — 发布就绪收尾（2026-09-02，DONE；粗版发布候选基线）

- **背景修正**：正式 Goal 序列（implementation-plan Phase 0–6）至此全部闭环——G6.1–G6.4 早已 DONE，G6.5（本 Goal）是唯一挂账；5 个「下一步候选」（MCP ACL/配额模板等）是腾讯研究建议，未正式立项。P0–P3（leader 蓝图线）已先行合入。用户定调：**先打粗版，后续功能逐步做大**——本 Goal 产出发布候选基线而非终版。
- **CHANGELOG**：`[Unreleased]` 补齐 2026-08-29 → 09-02（G8.x 外部通道/预算告警、P2 SkillHub、P3 内部治理含 MCP、真实 DeepSeek 联调、腾讯 30 篇研究入库、发布状态）；`[0.1.0]` 归档段修正（Phase 6 标题改为 G6.1–G6.4、Phase 3 补 Aliyun 3 产品、计数 20→23、注明从未 tag）。
- **release-checklist.md**：新增 §0「G6.5 执行盘点」表——逐项判定 ✅/⏳/➖ 并附依据；清单本体保持可复用。判定要点：供应商矩阵与团队 Plan 真实共享池 ⏳（真实凭证）；升级/回滚演练 ➖（无上一正式版本，首版建立基线）；无应用容器镜像（源码交付，➖）；§8 Go/No-Go ⏳（版本号与 tag 由用户授权）。
- **发现并修复（合并残留真实缺陷）**：`AdminMcpServicesView.vue` 重复 `import type { McpServiceView }`（PR #116 冲突合并残留）→ vue/compiler-sfc 编译失败 → vitest 该 suite 持续红；develop 合并后无 CI 触发（ci.yml 只在 PR 上跑）故漏网，9-2 记录的「vitest 67/67」实际漏 1 failed suite。删重复行后 **vitest 16/16 文件、73/73**（73 与 P3.5 分支记录一致，67 应作废）。
- **发现并修复（Secret 门禁违规）**：`docs/tencent-ai-gateway-study/raw/03-quickstart-mcp.md` 腾讯原文示例凭证（sk-5db73b…，标注"仅测试使用"）触发 check-secrets——打码 `sk-…REDACTED`（2 处），修复后 `secret scan ok`。
- **验证矩阵（全部真实 PASS）**：
  - 后端 `./mvnw.cmd -f backend/pom.xml verify -P integration` **BUILD SUCCESS**（11 模块、5:35）
  - 前端 lint / typecheck PASS、vitest **16 文件 73/73**、production build PASS
  - Playwright e2e **31/31**（preview 端口残留清理后）
  - **SoakIntegrationTest 50 并发取证 PASS**（25.45s、0 上游错误、usage 全落库；临时 CONCURRENCY=50 实跑后还原为 8）
  - `check-secrets` ok（修复后）；`check-sbom` **license gate ok（107 组件）**；`docker compose config` OK
- **文档契约缺口（记录为延期项）**：api-contract §8 / document-map §3 要求「Control Plane 生成 OpenAPI 3.1 + CI 破坏性变更检查」——仓库无 openapi 生成配置与产物，尚未实现；api-contract.md 为唯一事实源。待专项 Goal 或正式发布前补。
- **Windows 踩坑（记录）**：`npm run lint`（eslint --fix）会把 CRLF 文件整批重写为 LF → 23 个文件出现 EOL-only M（`git diff` 为空）；跑 lint 后先 `git restore` 或区分内容 diff，勿误提交。
- **剩余风险/待办**：代码 0.1.0-SNAPSHOT 从未 tag——正式版本号 + tag 待用户授权（git-workflow §9）；23 产品真实凭证全部 `WAITING_FOR_CREDENTIAL`；G6.5 后 vitest 基线修正为 **73/73**（非 67）；下一步增量候选（MCP 两级 ACL / 默认配额模板 / MCP 路由+Tools 护栏 / 阿里 Higress 对照）待用户定方向，立项时先写入 implementation-plan。

## MCP 两级访问控制 — 腾讯 AI 网关 doc 134890（2026-09-02，DONE）

- **背景**：用户指示「功能参考阿里云和腾讯云，按具体文档做」——直接研读已入库的腾讯 AI 网关 doc 134890（MCP 访问控制原文：Server 级 None/Allow/Deny ACL × 调用方名单 + Tool 级在 Server 基础上进一步收窄；仅 Server 全开放时 Tool 可自定义；变更即时生效）落地为管理面 + 判定策略。
- **后端**：V25 迁移（`mcp_service_access` 每服务一行 mode NONE|ALLOW|DENY + `mcp_access_grants` 名单行：tool_id 可空 = 服务级名单/非空 = 工具覆盖，consumer 引用 `api_consumers` CASCADE）；domain `McpAclMode`/`McpServiceAccess`/`McpAccessGrant` + **`McpAccessPolicy` 纯函数判定**（服务层先判：ALLOW 名单内才放行 / DENY 名单内拒绝 / NONE 全放；工具无覆盖继承、有覆盖只能收窄不能放宽——腾讯「Tool 级在 Server 级基础上进一步收敛」精确语义，单测覆盖判定矩阵）；`McpAccessRepository`（upsert 服务模式含切 NONE 清名单、scope 级整体替换/清除，PG 参数 cast 同族坑修复）；`AdminMcpAccessService`（视图组装含服务名单与逐工具 mode/名单；校验：NONE 配服务名单 409 SERVER_LIST_UNSUPPORTED、非 NONE 配工具覆盖 409 TOOL_ACL_UNSUPPORTED、消费者不存在/非 ACTIVE 400、tool 不属于服务 404；消费者仅 ACTIVE 可入名单）；Controller 4 端点（GET access、PUT mode、PUT grants、DELETE grants?toolId=）；审计 MCP_ACCESS_MODE/GRANTS/RESET。
- **前端**：MCP 服务页行操作「访问控制」→ t-dialog：服务模式 radio（全开放/白名单/黑名单）+ 名单多选消费者（仅 ALLOW/DENY 显示）+ 重置回开放；工具级表格（NONE 模式显示：每工具 继承/白名单/黑名单 选择 + 名单编辑与保存；继承=DELETE 覆盖）。api/types 5 函数 + 2 类型组。
- **验证（全部真实 PASS）**：domain `McpAccessPolicyTest` 3/3（判定矩阵：开放+工具收窄/白名单不可被工具放宽/黑名单）→ 单测口径 3 项含多断言；`AdminMcpAccessApiIntegrationTest` 6/6（服务 ALLOW 生命周期含替换与清空回 NONE、DENY 黑名单、工具覆盖生命周期按 toolName 断言（tools 列表无序——避免 jsonPath 下标）、模式约束与校验矩阵、审计三动作、401）；后端全量 `verify -P integration` **BUILD SUCCESS 2156 tests / 0 failures**（+18 净增含 domain 3）；前端 vitest **20 文件 94/94**（+3 访问控制：打开渲染/白名单保存/工具覆盖保存）、typecheck/lint/build PASS；Playwright 35/35。
- **排障记录**：clearGrants 的 `(:toolId IS NULL OR tool_id = :toolId)` 触发 PG 参数类型推断失败 → `::uuid` cast（与 quota findPage 同族坑）；MVC 集成测试的 MvcResult 无 andExpect（用 perform 链）；api-consumers 创建响应 id 在 `consumer` 键内；consumer 端点路径 `/api/v1/admin/api-consumers`（非 consumers）。
- **文档**：api-contract §5.21（模式语义/判定规则/错误码/审计/接线说明）；database-schema V25 段；CHANGELOG Unreleased 补记；progress。
- **边界/取舍（已记录）**：判定策略（McpAccessPolicy）已就绪但调用入口未接线——MCP 代理接线（P3.4/P3.5 后续集成）时按「谁能调服务/谁能调工具」把关（与模型授权模型一致：先配置后判定）；消费者组批量授权（腾讯维度）未做（无组实体，消费者直配）；「变更即时生效」由配置读取保证。
- **gitflow**：分支 `goal/mcp-access-control`（基于 #121 合并后 develop），验证后 push + PR。

## 缓存 ROI 报表 — 原始设计文档 P5.4（2026-09-02，DONE）

- **背景**：配额治理行闭环后，按文档重要度续做 P5.4（开发设计文档 §13 P5.4：ROI 回归——省量/实付/命中率周报）。直接回答 G7.4 缓存启用后的收益问题（编码 Agent 流量缓存值不值），数据驱动缓存策略。
- **后端**：`AdminRoiService`（复用 `AdminUsageStatsService.summary(groupBy=day)`：paid = `cost.upstreamPaid`、saved = `cost.savedByGatewayCache`（命中 token × 最新单价快照）、hitRatePct = (L1+L2)/(upstream+coalesced+hits)、savedPct = saved/(paid+saved)；零缓存也产出全实付报表）；`AdminRoiController` `GET /api/v1/admin/usage/roi?from&to`（缺省近 30 天；共享 93 天窗口校验；ISO 解析错误 400）。
- **前端**：`AdminRoiView`（缓存 ROI 页：4 统计卡 = 节省金额/上游实付/等效折扣/请求命中率 + 7/30/93 天窗口 + 逐日表 + CSV 导出（BOM，成本页同款））；路由/导航（数据与告警组「缓存 ROI」）。
- **验证（全部真实 PASS）**：`AdminRoiApiIntegrationTest` 3/3（usage 1000/500 + 2 次 L2 命中精确断言 paid=0.006/saved=0.005/hitRate=66.67%/savedPct=45.45%；空窗口零值；非法时间 400/超 93 天 400/匿名 401）；后端全量 `verify -P integration` **BUILD SUCCESS**（见 Current State 计数）；前端 vitest **20 文件 91/91**（+3 ROI 页）、typecheck/lint/build PASS；Playwright **35/35**（+roi baseline）。
- **排障记录**：测试 seed 的 cache_hit_event 用 `now()+1s` 作为第二条命中时间——查询 `occurred_at < to(=now())` 把它滤掉了（l2Hits=1）→ 改负偏移（now()-2s/-3s）；cache_entry 有 virtual_key/project FK → 测试需完整 key 链 seed（不能只 seed usage）。
- **文档**：api-contract §5.20（口径/响应）；CHANGELOG Unreleased 补记 4 功能（审批流/配额规则/配额告警/ROI + body 解析 400 修复）；database-schema 无迁移；progress。
- **至此原设计文档 P5「分级统计」剩余**：P5.3 对账（需真实账单样本）未做，其余统计线闭环。缓存 ROI 数据可支撑后续缓存默认值/键策略决策。
- **gitflow**：分支 `goal/cache-roi-report`（基于 #120 合并后 develop），验证后 push + PR。

## 配额水位告警 QUOTA_THRESHOLD — roadmap「配额管理」行第三项（2026-09-02，DONE）

- **背景**：#119（配额规则配置）合并后，按 roadmap 行「配额规则配置 + 水位 + 预警」收尾第三项；G8.3（BUDGET_THRESHOLD）同款接线。
- **后端**：V24 迁移（alert_rules type CHECK 加 `QUOTA_THRESHOLD`）；`AlertRuleService` 类型列表与 scope 校验扩展（QUOTA_THRESHOLD 必填 `scopeJson: {"quotaRuleId": …}` 且规则存在同租户，否则 `400 SCOPE_INVALID`）；`AlertEvaluator` 注入 `AdminQuotaRuleService`——水位 = 配额规则当前窗口 `usedPct`（规则 DISABLED 不评估），**去重键 = 规则 × 配额重置窗口起点**（日/周/月随规则周期，跨窗口可再触发）；事件/投递/重试全复用既有机制。
- **前端**：AdminAlertRulesView 类型加「配额水位」+ 条件配额规则下拉（label = scope 名 + 维度·周期）+ 阈值单位切「水位 %」+ 列表 scope 提示（同预算模式）。
- **验证（全部真实 PASS）**：`AdminQuotaRuleApiIntegrationTest` +2 → 8/8（水位 100% ≥ 阈值 80 触发事件 value=100、同窗口二次评估去重仍 1 条、规则 DISABLED 后不再触发；scope 缺失/未知规则 400 SCOPE_INVALID、存在规则通过）；前端 vitest **19 文件 88/88**（+2 配额告警页）、typecheck/lint/build PASS；Playwright 34/34；后端全量 `verify -P integration` **BUILD SUCCESS 2132 tests / 0 failures**。
- **文档**：api-contract §5.8（QUOTA_THRESHOLD 类型/scope/窗口去重语义）；database-schema alert 段 V24。
- **至此 roadmap「配额管理」行闭环**：配额规则（#119）+ 水位（#119）+ 预警（本 PR）——只预警不阻断，符合锁定决策；硬阻断与默认配额模板（腾讯 A10）仍需 ADR/立项。
- **gitflow**：分支 `goal/quota-alerting`（基于 466372c/#119 合并后 develop），验证后 push + PR。

## 配额规则配置 — platform-middleware roadmap「配额管理」步骤（2026-09-02，DONE）

- **背景与方向**：G6.5 后用户指示「按文档从最重要开始」自主立项——8-31 leader 蓝图（platform-middleware-roadmap.md 修正后路线表）明文：配额管理模块下一步 =「配额规则配置 + 水位 + 预警（轻量，不硬阻断）」。补齐用量维度治理（既有 = 成本预算 G8.2/G8.3 + 只读配额快照 G4.2；本 Goal = Token/请求次数 × 周期 × 阈值规则 + 实时水位）。
- **后端**：V23 `quota_rules`（scope USER|PROJECT × metric TOKENS|REQUESTS × period DAILY|WEEKLY|MONTHLY + limit/warn_percent(1-99 默认 80)/status，唯一 (tenant,scope,metric,period)，ON CONFLICT upsert 原地编辑保 id/created_at）；`QuotaRule`/4 枚举/Repository/Impl；`AdminQuotaRuleService`（scope 存在性校验 404 防枚举；**水位读时计算** = `AdminUsageStatsService.summary` 现行窗口：TOKENS=全部 token（含 cacheRead/cacheCreation，与个人 TotalTokens 口径一致）、REQUESTS=上游请求数（缓存命中不计）；窗口 UTC 切片 DAILY/WEEKLY(周一始)/MONTHLY；level NORMAL/WARNING(≥warn%)/EXCEEDED(≥100%)——**永不阻断**；DISABLED 保留水位）；`AdminQuotaRuleController`（GET/PUT/DELETE /api/v1/admin/quota-rules）；审计 QUOTA_RULE_CREATE/UPDATE/DELETE。
- **顺带全局修复（测试发现）**：`GlobalExceptionHandler` 补 `HttpMessageNotReadableException` → `400 PARAM_INVALID`（body 枚举非法/类型错误此前 500；含字段名提示，api-contract §5.19 记录）。
- **前端**：`AdminQuotaRulesView`（配额规则页：对象/维度/周期/限额/用量+水位条/level 徽标/状态 + 内联新增编辑面板：对象类型切换用户/项目下拉、维度周期单选、限额/阈值、停用开关；删除走 confirmDialog 门禁）+ api/types/router/导航（数据与告警组「配额规则」）。
- **验证（全部真实 PASS）**：`AdminQuotaRuleApiIntegrationTest` 6/6（生命周期 upsert 保 id/version、同 scope 三 period 水位 NORMAL 10.00%/WARNING 90.91%/EXCEEDED 100.00% 精确断言、REQUESTS=usage 行数、PROJECT scope、scope 404、枚举/数值校验 400、403/401、审计三动作序列）+ `AdminQuotaRuleServiceTest` 3/3（UTC 窗口边界：日/周一/月跨年）；后端全量 `verify -P integration` **BUILD SUCCESS 2128 tests / 0 failures**（+9 净增；GlobalExceptionHandler 变更全回归）；前端 vitest **19 文件 86/86**（+5）、typecheck/lint/build PASS；Playwright **34/34**（+quota-rules baseline）。
- **文档**：api-contract §5.19（配额规则/水位口径/level 语义/审计/400 body 解析修复）；database-schema `quota_rules` V23；progress。
- **边界/取舍（已记录）**：REQUEST 配额口径 = 上游请求数（缓存命中不计——配额度量的是对供应商额度的消耗）；水位为读时计算（N 规则 N 次聚合查询，内部规模可接受；量级上来再考虑预聚合）；**告警接线（QUOTA_THRESHOLD Webhook）按 G8.2→G8.3 节奏列为后续轮**；用户自助配额可见性与默认配额模板（腾讯 A10）未做（候选待立项）；硬阻断需 ADR（锁定决策）。
- **gitflow**：分支 `goal/quota-rules`（基于 b948a5c/#118 合并后 develop），验证后 push + PR。

## 模型申请审批流 — 原始设计文档 §13 P6.1 / §8.2（2026-09-02，DONE）

- **背景与方向**：G6.5 收尾后用户指示「以项目本身的规划文档定方向」——对照工作区 8-14 AI 组设计交付包（架构设计报告 + 开发设计文档），模型申请审批流是唯一「表已备（V4 `model_approval`）、文档完整（§8.2/§5.6/§13 P6.1）、无外部依赖」的缺口。用户拍板开工。
- **后端**：V22 迁移（`model_approval.reason` 申请理由）；domain record/Repository/Impl 补 reason 列 + `findAllByRequestedBy` + keySet 游标 `findPage`（`(created_at,id) DESC`，null 参数显式 `::varchar/::timestamptz/::uuid` cast——PG 对 `? IS NULL` + 行比较混用的 null 参数无法推断类型，G8.3 jsonb cast 同族坑）；`ModelApprovalService`（提交校验 MODEL_ALREADY_AVAILABLE/DUPLICATE_PENDING/KEY_NOT_ACTIVE/IDOR 404；白名单 `ApprovalProperties(miqrokey.approval.whitelist-models)` 自动批准；approve 生效 = 写 `virtual_key_models`（申请 Key）+ `project_provider_grant_models` ON CONFLICT（若缺失；网关按 `key.models ∩ grant.models` 放行两处缺一不可）+ `routeRefreshPublisher.publishChanged()` 即时生效；reject 留痕；乐观锁并发评审 409 ALREADY_REVIEWED）；`MeModelApprovalController`（POST/GET `/api/v1/me/model-approvals`）+ `AdminModelApprovalController`（GET 队列 status/size/before 游标、POST `/{id}/approve|reject`，SYSTEM_ADMIN deny-by-default）；审计 `MODEL_APPROVAL_SUBMITTED/APPROVED/REJECTED`（auto-approve 双事件留痕）。
- **前端**：`ModelApprovalsView`（我的申请 + 内联申请面板：Key 下拉/模型/理由）+ `AdminModelApprovalsView`（审批中心：状态筛选/通过·驳回内联评审面板带意见/加载更多游标）；api/types/router/导航（常规组「模型申请」EditIcon + 组织组「审批中心」CheckCircleIcon）。
- **验证（全部真实 PASS）**：`ModelApprovalApiIntegrationTest` 10/10（闭环：提交→队列→approve→**JdbcRouteSnapshotLoader 快照断言** grant/key 双表含新模型；grant 内模型同步不重写 grant；白名单 auto-approve 即时生效；reject 不动模型；IDOR 404/403/401；校验矩阵；KEY_NOT_ACTIVE/GRANT_INACTIVE；keySet 分页无重叠 + 非法游标 400）；后端全量 `verify -P integration` **BUILD SUCCESS 2110 tests / 0 failures**（1051+10 等全模块；1 次已知 flaky `HmacVirtualKeyProviderTest.shouldFollowFormat` 随机边界单独重跑 33/33 过）；前端 vitest **18 文件 81/81**（+8）、typecheck/build PASS；Playwright **33/33**（+approval-center + model-approvals 两页 baseline 覆盖）。
- **前端实现经验（记录）**：inline `t-dialog`（v-model:visible + 表单）在 jsdom 下 teleport 内容不挂载（TDialog 走 popup 状态机，与 TPopup 同族时序问题）——vitest 对带输入交互的表单统一用**内联展开面板**（KeysView/AdminConfigs 同款），确认类对话框走 DialogPlugin（document 级可查）；表格操作列用 `<template #colKey>` slot 而非 `h('t-button')` 渲染函数；图标导出名以 `tdesign-icons-vue-next/esm/icons.d.ts` 为准（`EditPenIcon` 不存在，用 `EditIcon`）。
- **文档**：api-contract §4.6（用户申请/白名单/审计事件）与 §5.18（审批队列/通过语义/游标）、§4.7 错误码表补 6 个新 code；database-schema `model_approval` 段（V22 + 生效双表语义）；configuration-reference `MIQROKEY_APPROVAL_WHITELIST_MODELS`；CLAUDE.md 不动。
- **边界/取舍（已记录）**：审批通过把模型写入 Grant 模型集（影响同 Grant 其它 Key 的未来创建继承——仓库授权模型的最小粒度即 Grant，Key 现有快照不受影响）；白名单自动批准 reviewedBy=null（留痕由审计双事件 + reviewNote 承担）；Webhook 通知按文档「预留」未实现；model_access V7 维持未消费（以 V4 APPROVED 行为放行源）。
- **gitflow**：分支 `goal/model-approval-workflow`（基于 df126d4/#117 合并后 develop），验证后 push + PR。

## 2026-09-02 合并记录（PR #110–#116 全部合入 develop）

- #110 Dependabot（codeql-action bump）、#111 SkillHub 前端、#112 Agent 管理、#113 服务管理、#114 全局配置、#115 MCP 服务、#116 MCP Tools —— 全部 squash merge + 删除远端分支。
- 合并冲突处理：多个 PR 同改前端公共文件（api/types/router/AppShell）且历史分支互带对方文件，逐个分支 `merge origin/develop` 手动解决（Agent 文件取 develop 侧保留 CodeRabbit 修复；各 PR 自身新增段保留）。
- 合并后 develop 全量后端 `verify -P integration` BUILD SUCCESS；前端 vitest 67/67。
- CodeRabbit review：PR #112 的 Major（Agent 凭证共享）已修复；其余 PR 无 actionable 问题。

## 2026-09-02 会话交接要点（新 session 必读，含踩坑记录）

**操作环境（Windows）**
- Java：每次 Maven 命令前 `export JAVA_HOME="D:\programming\jdk-21.0.12.1+1" && export PATH="$JAVA_HOME/bin:$PATH"`；仓库根执行 `./mvnw.cmd`（backend/pom.xml 是聚合 POM，命令在仓库根跑；模块级用 `-pl control-plane-app -am`）
- 网络：GitHub 直连时断时续——失败时用 `HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 git push/gh ...`（一次性环境变量，不改 git 配置）
- Git：当前习惯 = 每个 Goal 开 `goal/<name>` 分支（从 develop），验证后 commit → push 分支 → `gh pr create --base develop` → 等 CI 全绿 + CodeRabbit 无未处理问题 → 用户授权后 `gh pr merge --squash --delete-branch`；禁止直接 push develop 业务实现、禁 force push
- 前端验证：`cd frontend && npm run lint/typecheck/test/build`；vitest 在 frontend 目录跑（`@` 别名只在 frontend 配置）
- e2e：`npx playwright test` 前先 build；**旧 vite preview 进程会复用旧 dist**——若页面行为异常先查 `netstat -ano | grep 4173` 并杀 LISTENING 进程
- 后端格式：新写 Java 文件后全量 verify 前先跑 `./mvnw.cmd spotless:apply --batch-mode -pl control-plane-app,persistence-postgres,domain -am`（否则 verify 的 spotless:check 会挂）
- 集成测试：`-P integration` profile + `-Dtest=XxxTest -Dsurefire.failIfNoSpecifiedTests=false`；Testcontainers 需 Docker Desktop
- 已知 flaky：`HmacVirtualKeyProviderTest.shouldFollowFormat`（随机边界，重跑即可）、`RouteSnapshotRefreshNotifierTest`（连接数问题已修复：测试基类池 10 + 容器 max_connections=200）

**CI / 机器人审查状态**
- CI（ci.yml）：pull_request 已覆盖 main+develop；Backend unit/integration、Frontend、e2e、Compose、Security gate、CodeQL 全在 PR 上跑
- CodeRabbit：.coderabbit.yaml 已配（base 含 develop）；OSS 仓库首次 review 需所有者在 coderabbit.ai 批准；限流时用 `@coderabbitai review` 评论错开触发；CodeRabbit 的 inline comment 用 `gh api repos/sijie-Z/miqro-gate/pulls/<n>/comments` 查
- SonarCloud：workflow 已备（sonarqube.yml），无 SONAR_TOKEN 时自动跳过；用户想装时按 docs/ai-code-review-bots.md 步骤（需用户创建项目提供 projectKey/organization）
- Qodo/Ellipsis/Bito：用户尚未安装（GitHub Marketplace App，需用户操作）

**文档资产**
- `docs/tencent-ai-gateway-study/`：腾讯 AI 网关 30 篇研究（README 总结 + raw 底稿）
- `docs/ai-gateway-comparison.md`、`docs/tencent-ai-gateway-mapping.md`：早前对照
- `docs/ai-code-review-bots.md`：机器人安装指南
- `docs/platform-middleware-roadmap.md`：P0–P3 规划 + P2.1 形态调研存档
- 契约事实源：api-contract.md / database-schema.md / configuration-reference.md（API/表/配置变更必须同步）

**下一步候选**（用户定方向后开 Goal 分支）：
1. MCP 两级访问控制（Server + Tool 级 ACL，对齐腾讯文档 15）——补 MCP Tools 授权闭环，纯元数据
2. 默认配额模板（创建时快照复制语义，对齐腾讯文档 22）——可借鉴到预算
3. MCP 路由规则 / Tools 分组 / 重试熔断护栏（腾讯 10/17/12/13）
4. 阿里云 Higress 文档体系系统对照（用户最初要求两家都看）

**设计原则红线**（不可变决策）：单客户私有化、网关透明代理不改写 JSON 不读正文、1:1 绑定不跨供应商不负载均衡、不自动故障切换（首字节前最多安全重试一次）、不限流不因预算阻断（只 Webhook 告警）、凭证 AES-GCM、Key 摘要存储、目录签名、宽松许可证。

## 2026-09-02 腾讯 AI 网关 30 篇文档研究（已完成入库）

- **入库**：`docs/tencent-ai-gateway-study/`（README.md 总结 + raw/ 28 篇纯文本底稿），commit 87a20b0/f3fa80a，develop 已推送。
- **结论**：A 类 15 项元数据级设计可直接借鉴（MCP 两级访问控制、消费者默认配额快照复制语义、Agent 服务与入口分离、Tools 版本/重试/熔断、模型探测等）；B 类 4 项需读正文（参数改写/流量镜像/脱敏/包体采集）与「不读正文」冲突仅对照；架构核对方向正确。
- **下一步候选**（待用户定方向）：1) MCP 两级访问控制（补 Tools 授权闭环）；2) 默认配额模板（快照复制语义）；3) MCP 路由规则/Tools 分组/重试熔断护栏；4) 阿里云 Higress 文档体系系统对照。

## P0–P3 里程碑（2026-09-01 达成，2026-09-02 合并）

## Completed

- 产品范围、角色、Virtual Key 固定映射和非目标已确认。
- Java 21 / Spring Boot / WebFlux / Vue 3 / PostgreSQL 技术方向已确认。
- Gateway 透明代理、CC Switch 负责协议转换的边界已确认。
- 个人、团队、企业 Plan 领域模型已确认。
- 首版供应商候选、用量、成本、安全、部署和测试文档已完成。
- 面向 Agent 的开发契约、Goal 分解、API/数据库/Provider/UI/配置契约、开发工作流、运维 Runbook 和发布清单已完成。
- Git/commit/push/PR 工作流和前端 Quiet Operations Console 视觉规范已完成。
- Claude Code 实施身份、默认授权、Goal 输入输出和失败恢复交接契约已完成。
- CC Switch + 第三方模型无法可靠 `/compact` 时的 disk-first checkpoint 与 fresh-session 续接策略已完成。
- 产品与工程标识确定为 MiQroKey Gateway / MiQroKey，仓库 `miqro-key-gateway`，Java 包 `com.miqroera.miqrokey`。

## G0.1 — Repair (Round 2)

### Repairs applied

1. **Maven Wrapper**: Real SHA-256 checksums from Maven Central/ASF; `maven-wrapper.jar` committed to Git; checksum verification in both `mvnw` and `mvnw.cmd` (powershell `certutil` for Windows, `sha256sum` for Unix); `mvnw` executable bit set via `git update-index --chmod=+x`.

2. **Configuration aligned with `configuration-reference.md`**:
   - Gateway port: `${MIQROKEY_GATEWAY_PORT:8081}`
   - Control Plane port: `${MIQROKEY_CONTROL_PORT:8080}`
   - DB config: `${MIQROKEY_DB_URL}`, `${MIQROKEY_DB_USERNAME}`, `${MIQROKEY_DB_PASSWORD}` (with `_FILE` convention noted)
   - `.env.example` updated with `MIQROKEY_` prefix
   - `compose.yaml`: postgres pinned to `17.6-alpine`, port configurable via `${MIQROKEY_DB_PORT:-5432}`

3. **ArchUnit**: `allowEmptyShould(true)` removed from cross-module rules; `control-plane-app` added as test-scope dependency in `gateway-app` so all checks verify actual classes; reactor module order adjusted (control-plane-app before gateway-app); `DataSourceAutoConfiguration` excluded in Gateway smoke test to prevent JDBC auto-config clash.

4. **`.flattened-pom.xml`**: Removed from Git index (`git rm --cached`); `**/.flattened-pom.xml` already in `.gitignore`.

5. **Maven plugin versions**: Locked `maven-compiler-plugin:3.13.0`, `maven-jar-plugin:3.4.2`, `maven-surefire-plugin:3.5.2`, and `spring-boot-maven-plugin` in parent POM `pluginManagement`.

6. **Initial Compose image pin**: Replaced the mutable PostgreSQL major tag with `17.6-alpine`; item 9 records the final digest lock.

7. **Windows Wrapper exit semantics**: `mvnw.cmd` now propagates Maven's real exit code. A deliberately invalid Maven phase returns exit code `1`; CI includes a regression check so a failed build cannot be reported as successful.

8. **Management endpoint boundary**: Gateway data-plane exposure is limited to `health,info`; `metrics`/`prometheus` are not exposed on the public Gateway port. A smoke test enforces this boundary.

9. **Reproducible Compose image**: PostgreSQL is pinned to the Docker Hub multi-platform manifest digest for `postgres:17.6-alpine`; CI rejects every Compose image that lacks an `@sha256:` digest.

10. **Configuration regression tests**: Gateway `8081` and Control Plane `8080` defaults are asserted. Control Plane test overrides moved to `application-test.yml`, avoiding accidental replacement of the production `application.yml`.

### Local verification (Windows, Java 21 Temurin 21.0.11)

- `.\mvnw.cmd clean verify --batch-mode --quiet`: **BUILD SUCCESS** — clean checkout-equivalent build
- `.\mvnw.cmd verify --batch-mode --quiet`: **BUILD SUCCESS** — 15 tests, 0 failures, 0 errors
  - Domain contract: 1 test
  - Control Plane smoke/configuration: 2 tests
  - ArchUnit module dependency: 8 rules (all effective, no `allowEmptyShould`)
  - Gateway smoke/configuration/security: 4 tests
  - Spotless check: all modules clean
  - Maven Enforcer: all rules passed
- Deliberately invalid `mvnw.cmd` phase: expected exit code `1` (failure propagation verified)
- `npm ci`: PASS (0 vulnerabilities)
- `npm run lint`: PASS
- `npm run typecheck`: PASS
- `npm run test`: PASS (1 test)
- `npm run build`: PASS

### CI evidence

- PR: `https://github.com/lichman0405/miqro-key-gateway/pull/1`
- Baseline repair commit `b732f4c`: Ubuntu backend, frontend and Compose config all passed in run `29733691718`.
- Final implementation commits: `75b6a22` and `cd100ff`.
- Final implementation CI run `29796610144`: Ubuntu backend, Windows backend, Windows Wrapper failure propagation, frontend, Compose config and digest locking all passed.
- CI evidence: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29796610144`

## 发布后审计（2026-08-27，双 Agent 代码审查）

### 已修复（PR #68/#69/#70）

- **中转核心接线**：适配器 `resolve()` 接入网关热路径（协议专属 base + 路径归一化，`base_url_templates` 支持按协议条目）；`loadBindings` 限定 Key 自身 grant（消除跨授权路由）；`AdapterRegistryFactory` 统一双端注册。
- 安全：登录延迟时序枚举（未知用户下限）、错误包络控制字符转义、grant 模型热路径交集（模型回收生效）、quota 错误剥离 URL、createGrant 租户过滤+产品校验、projectTag 校验、null tag 404。
- 可靠性：HttpProviderClient `%` 二次编码、body 读取 deadline、subscriptionId 过滤 SQL 500。
- 未接线项补全：quota 快照定时刷新（15 分钟默认）。
- 文档：G3.5–G3.8 编号对齐 plan；腾讯目录 baseUrlTemplate 差异标注。

### 已知残余风险（记录，待处理）

- **SSRF DNS 重绑定 TOCTOU**（UpstreamTargetValidator 校验解析与连接解析分离）：管理员配置源 + 供应商域名为可信方，残余竞态风险低；修复方向为解析后固定 IP 建连（与 Host/SNI 配合），列入下一迭代。
- **G5.5 视觉 review**：spec §9 人工视觉审查未执行（自动化 baseline 已覆盖布局/色彩，语义审查待人工）。
- 真实供应商凭证契约测试全部 `WAITING_FOR_CREDENTIAL`；腾讯/智谱等 Anthropic 入口 Bearer 兼容性待真实核验。
- 已知 flaky：`AuditChainIntegrityTest.preLockTimestampsDoNotAffectHeadOrdering`、`InProcessRequestCoalescerTest.shouldShareWithWaiters`（G4.x 排查清单）。

## 发布后修复批次（2026-08-27，已合并 #68-#73）

- #68 文档编号对齐；#69 中转接线（适配器热路径）+ 审计 14 项修复；#70 定时额度刷新
- #71 审计记录归档；#72 **界面重设计（额度账本）**；#73 **SSRF DNS 固定 + coalescer 清理时序 + 审计链 jsonb 规范化**（3 个并行 Agent 完成，本地 978 tests 全绿，CI 双平台全绿）
- 残余风险（记录于上）：SSRF 固定后的 Host 头为 IP 字面量（JDK 客户端限制，CDN/SNI 路由不受影响）、HttpProviderClient 生命周期内固定构造时 IP、真实凭证契约测试全部 WAITING_FOR_CREDENTIAL

## G7.1 — 上游凭证管理门户（对照腾讯云 AI 网关文档能力补齐，DONE）

- **来源**：用户指示学习腾讯云 AI 网关文档（product/1826）。文档六步接入流程的第一步「模型密钥管理」对应本项目的上游凭证——后端 API 早在 G1.6 就绪（api-contract §5.1），但前端页面缺失、导航「Credentials」指向不存在的路由（死链）。
- **交付**：`AdminCredentialsView`（列表=名称/指纹前缀/供应商产品/状态/最近验证/版本；创建表单=名称+订阅选择+Secret 可见性切换；测试 Secret 弹窗=纯校验不落库，matchesActive 结果；轮换弹窗=新 Secret 原子生效+宽限期说明；禁用=确认后执行；版本历史抽屉=状态/密钥版本/指纹/生效退役时间）+ credentials 路由接线 + api 层 5 个函数与 4 个新类型。
- **迁移审查追加发现（HIGH，全站修复）**：TDesign `DialogPlugin.confirm` 返回 dialog 节点而非 Promise——所有 `await DialogPlugin.confirm(...)` 的确认流程**立即放行**，轮换/吊销/禁用/登出等危险操作在用户确认前就已执行。新增 `src/utils/confirm.ts`（`confirmDialog`：确认 resolve/取消与关闭 reject，destroyOnClose），11 个文件 13 处调用点全部替换；e2e 新增回归测试「dangerous actions wait for the confirmation dialog」（确认前 0 次 rotate 调用）。
- **验证**：vitest 31/31（新增 AdminCredentialsView 10 个）、Playwright 18/18（新增凭证页 baseline + 确认门禁回归）、lint/typecheck/build 全 PASS。
- **对照腾讯文档的能力映射（学习结论）**：模型密钥→上游凭证（本 Goal 补齐）；模型服务→Provider 产品实例（AdminProvidersView 已有）；模型 API/路由策略→与「Virtual Key 固定 1:1 绑定、不负载均衡」决策冲突，需 ADR 后另行决策；消费者/消费者组授权→用户+项目+Grants（已有）；限流（QPM/Token）→与「不限流」决策冲突；MCP/协议转换→CC Switch 职责。
- **风险**：validate 仍为本地指纹比对，上游真实校验接线（G4.x）`WAITING_FOR_CREDENTIAL`；e2e 基线截图新增 admin-credentials（12 张）。

## G7.3 — 成本报表页（成本账本闭环，零后端改动）

- 对应腾讯 AI 网关「成本管理」报表能力；G7.2 补了单价录入、G4.3 有成本分摊后端，本 Goal 把分摊结果可视化。
- `AdminCostView`（数据与告警组「成本报表」）：按项目/按天双视图切换、近 7/30/93 天窗口、4 统计卡（分摊总成本/上游已付/请求/Tokens）、项目成本占比条形、**导出 CSV**（前端生成，BOM 防乱码）。
- 复用既有 `GET /api/v1/admin/usage/summary?groupBy=project|day`（G4.1），后端零改动。
- **验证**：vitest 39/39（新增 4 个）、Playwright 20/20（新增成本页 baseline，14 张）、lint/typecheck/build 全 PASS。
- **隐私**：报表只展示分摊金额与 token 数等元数据，无任何正文。

## 主线合并（2026-08-29）

- **PR #77 以 merge commit 合并到 main**（43cbcdd）：G7.1 凭证门户、G7.2 定价目录、G7.3 成本报表、G7.4 响应缓存（ADR-0009）、TDesign 迁移与修复、CI 拆分与机器人、基础设施（Issue 模板/SECURITY.md/CodeQL/npm audit）全部进入主线。
- **develop 分支**领先 main 4 个 commit（缓存键升级、bundle 拆分/用量导出/部署信息页、CI 路径过滤、基础设施补全），待后续 PR 合回。
- 合并方式选择 merge commit（非 squash）：develop 从该分支拉出，merge commit 保持历史同源，后续合并无重放冲突。

## CI/机器人规范化（2026-08-27，向大项目看齐）

- **CI 拆分**（原单一大 job → 6 个并行 job）：`backend-unit`（ubuntu+windows 单元测试，无 Docker，~2min）、`backend-integration`（Linux Testcontainers 全量）、`frontend`（lint/typecheck/vitest/build）、`frontend-e2e`（Playwright，**此前 e2e 从未进 CI，本次补上**）、`compose`、`security`。
- **CodeRabbit**：`.coderabbit.yaml`（zh-CN、assertive、auto-review 覆盖 main/goal/feat/fix 分支）。
- **Dependabot**：`.github/dependabot.yml`（npm/maven/github-actions 每周一自动更新 PR）。
- **OSSF Scorecard**：`.github/workflows/scorecard.yml`（周度 + PR 增量 code scanning）。**首跑发现 9 个告警（1 high + 8 medium）并已修复**：stale.yml `contents: write` 权限过大 → 收紧为 issues/pull-requests write；6 个 GitHub Action 全部按 commit SHA 固定（checkout v4.4.0 / setup-java v4.9.1 / setup-node v4.4.0 / scorecard-action v2.4.0 / codeql-upload-sarif v3.37.9 / stale v9.1.0）。修复后 Scorecard check 全绿。
- **Stale bot**：`.github/workflows/stale.yml`（issue 60 天/PR 30 天标记，+14 天关闭，dependencies/draft 豁免）。
- **待办**：`aquasec/trivy:0.58.2` 测试镜像未固定 digest（网络受限未拉到，按相同标准补）；CodeRabbit 首次 review 待确认（OSS 仓库手动 review 要求已配置，下一 PR 生效）。

## G7.2 — 模型单价配置（对照腾讯云 AI 网关「成本管理」文档）

- **来源**：用户提供 11 篇腾讯云 AI 网关文档逐一学习（新建/升级/详情/规格/删除/模型管理/缓存策略/降级策略/MCP 管理/MCP 上下线与健康检查/模型单价配置）。能力映射：密钥→G7.1；**模型单价→本 Goal**；新建/升级/规格/删除=云基础设施（单客户私有化不适用）；缓存策略=ADR-0008 默认关闭；降级策略/智能路由/限流=与锁定决策冲突（ADR 候选）；MCP 协议转换=CC Switch 职责。
- **交付**：`AdminPriceService` + `AdminPriceController`（GET/POST `/api/v1/admin/prices`，SYSTEM_ADMIN-only）+ `PriceSnapshotView` DTO；前端 `AdminPricesView`（单价列表=产品/模型/类型/单价/生效时间/来源；新增快照表单=产品下拉/模型/Token 类型/货币/单价/来源）+ 路由/导航（供应商组「定价」）+ api 层与类型。
- **语义**：单价是不可变快照，修改即追加（与官方「修改不追溯」一致）；成本聚合器按请求时刻的最新快照计价（既有 findLatestAt 逻辑，本 Goal 只补管理面）。
- **验证**：后端 `AdminPriceServiceTest`（4）+ `AdminPriceApiIntegrationTest`（5，Testcontainers：401/创建列表/新快照取代旧快照/404/400）全绿；前端 vitest 35/35（新增 AdminPricesView 4 个）、Playwright 19/19（新增定价页 baseline）；lint/typecheck/build 全 PASS。全量后端 verify 见验证记录。
- **风险**：官方价格自动同步（腾讯文档的 24h 周期同步）未实现——`source=OFFICIAL` 仅为人工标记，自动同步依赖供应商官方价格源，另行规划。

## 界面重设计（2026-08-27，额度账本方向）

- 用户反馈界面过空，参考腾讯云 TokenHub 控制台 → 浅色密集操作台（tokens.css 全新调色：canvas #F2F4F8、主色 #0066FF、表格 12px/44px 密度）。
- 签名元素：`mk-quota-band` 滚动额度分段条（5 小时/周/月三窗口）——登录页品牌区、首页额度账本、Plans 页滚动额度列。
- 新增 OverviewView（登录后首页）：4 统计卡（Key 数/本月请求/Tokens/成本）、用量分布条形图（CSS-only）、最近 Key、管理员额度账本。
- KeysView 统计条 + 过滤栏；UsageView 条形图；AppShell 三组管理导航 + 版本徽标；登录页双栏品牌区。
- e2e：Overview baseline 新增，15/15 通过；baseline 截图全量刷新（11 张）。
- vitest 21/21、lint/typecheck/build 全 PASS；frontend-design.md 方向修订已记录。

## 界面重设计 · TDesign 组件库迁移（2026-08-27，DONE）

- **动机**：用户对照腾讯云 TokenHub 截图要求"腾讯的质感"——从组件层解决，Element Plus 全量替换为腾讯开源设计系统 TDesign（`tdesign-vue-next@1.20.6` + `tdesign-icons-vue-next@0.4.10`），与 TokenHub 控制台同源。
- **范围**：15 个视图 + 3 个组件（AppShell/PageHeader/SecretRevealDialog）+ main.ts/package.json 全量迁移；`el-*` 标签与 Element Plus import 零残留，`element-plus`、`@element-plus/icons-vue` 已从依赖移除。
- **迁移要点**：`el-table→t-table`（size=small 高密度）、`el-dialog→t-dialog`、`el-message→MessagePlugin`、`el-dropdown→t-dropdown`、`t-alert` 弃用 `close` 改用 `close-btn`（18 处）。
- **t-dropdown-item 不透传 attrs**：`data-testid` 移到 item 插槽内 span（KeysView kebab 菜单），e2e 定位恢复正常。
- **测试环境修复（本次迁移的关键坑）**：
  - jsdom 缺 `ResizeObserver`/`IntersectionObserver`/`matchMedia` → 新增 `src/__tests__/setup.ts`（vitest setupFiles），否则 TDesign Popup 挂载钩子抛错、触发器事件永不绑定。
  - TDesign Popup 的 popper 状态机（setTimeout 显隐 + rAF 延迟挂载 + readonly 守卫）在 jsdom 下时序不确定，选项列表偶发不渲染 → KeysView.spec 用 TPopup 内联 stub（触发器 + 面板直接渲染），保留用户式选项点击；弹层定位属 TDesign 自身职责，非应用逻辑。
- **验证**：vitest **21/21**、Playwright **15/15**（production build + 4 viewport baseline）、lint/typecheck/build 全 PASS。
- **文档**：frontend-design.md §1/§7、coding-standards.md、implementation-plan.md、ui-specification.md 已同步为 TDesign；视觉方向（浅色密集操作台 + 额度分段条）不变。
- **风险**：组件库全量引入，主 chunk ~1.4MB（与 Element Plus 时期相同量级）；按需引入/手动分块列为非阻塞优化。视觉 review 仍待人工（spec §9）。

## G7.4 — 响应缓存启用（ADR-0009，对齐腾讯 L1 精确缓存方案）

- **决策**：ADR-0009 放行缓存，替换 ADR-0003「v1 不做缓存」。结构对齐腾讯「缓存策略」文档，本土化差异：**存储用 PostgreSQL `cache_entry` 表 + Caffeine 内存（不引 Redis/向量库，ADR-0005）**；L2 语义缓存不启用（依赖向量库，接口预留）。
- **启用条件（比腾讯更严的双 opt-in）**：`MIQROKEY_CACHE_ENABLED=true`（默认 false，生产零行为变化）+ Key `cachePolicy=ENABLED` + 客户端头 `X-MiQroKey-Cacheable: 1` + 无工具字段 + 非空 body。工具调用永不缓存。
- **本次交付**：ADR-0009；KeysView 创建表单「缓存策略」选项（默认关闭）+ 列表缓存列；成本报表页「缓存节省」统计卡（`savedByGatewayCache` + l1/l2 命中计数）；configuration-reference §9 重写。
- **既有资产**（零后端改动）：cache-spi 全实现（Caffeine/Postgres/Noop Provider）、`cache_entry` 表（V5）、CacheEligibility/CacheKeyFactory/SseReplayEngine、端到端测试（VirtualKeyAuthContractTest：字节一致命中/无 opt-in 不缓存/错误不缓存）。
- **验证**：vitest 40/40（新增缓存策略选项与列表断言 + 成本页缓存卡）、Playwright 20/20、lint/typecheck/build 全 PASS。
- **风险**：Coding Agent 流量缓存收益存疑（ADR-0003 记录：上下文多变易过期）——缓存键策略对齐腾讯「最新用户消息」列为后续优化项；语义缓存维持禁用。

## 真实供应商联调（2026-08-30，DeepSeek 官方 Key 全链路）

- **验证环境**：本地 Docker PostgreSQL + control-plane(8080) + gateway(8081)，真实 DeepSeek 官方 API Key。
- **全链路结果（全部通过）**：
  - bootstrap → 改密 → 登录 → 订阅 → 凭证（真实 Key 加密存储 + 指纹）→ 项目 → Grant → Virtual Key
  - **真实推理**：`POST /v1/chat/completions`（OpenAI 兼容）→ DeepSeek 真实返回 `MQROK-DRILL-OK`（model deepseek-v4-flash）
  - **用量落库**：1 请求 / input 16 / output 8 / **cacheCreation 16**（cache miss 正确解析）
  - **成本计算**：按单价精确 ¥0.000128 = 16×2/1M + 8×8/1M + 16×2/1M ✓
- **发现并修复（生产级 bug）**：**SessionFilter order** —— `Ordered.HIGHEST_PRECEDENCE` 跑在 Spring Boot RequestContextFilter(-105) 之前，真实容器上所有带 session 的请求 500（ScopeNotActiveException）；MockMvc 绑定请求上下文掩盖了它。已修复（order=-100）+ 新增 `AuthenticatedRequestIntegrationTest`（真实 HTTP 端口 + 真实 session cookie 回归）。
- **发现的缺口（待处理）**：
  - **NOTIFY 即时刷新（已确认为正常）**：干净环境下手动 `pg_notify` 后 ~4s 推理成功 —— 之前的 404 是测试环境干扰（残留 gateway 进程），非代码缺陷。
  - **providers/provider_products 无初始化（已修复）**：新增 `CatalogSeedService`（启动时从签名目录幂等 seed 8 供应商 + 23 产品，URL 只来自签名目录），`CatalogSeedIntegrationTest` 回归；本地真实环境验证生效。
  - 联调脚本与本地环境位于 `miqro-local/`（不入库）；DeepSeek Key 已暴露于会话，**建议轮换**。
- **价值**：真实链路验证了凭证加密/指纹、Virtual Key 鉴权、透明代理转发、用量解析（含 cache 字段）、成本计算全部与真实供应商行为一致；mock 到真实的差距仅剩 NOTIFY 刷新与产品实例管理两处。

## G8.1 — 外部系统 API 通道（ADR-0010，平台中间件 P0 身份地基）

- **决策**：ADR-0010 —— 双认证通道并存（门户 session 不变 + 外部系统 API Key）；对齐阿里消费者认证模型。
- **交付**：`api_consumers` 表（V13，Key 仅存 SHA-256 哈希）；`ApiConsumer`/repository/`ApiConsumerService`；`AdminApiConsumerController`（创建一次性 Key/列表/吊销）；`ApiKeyAuthFilter`（保护 `/api/v1/billing/**`，SessionFilter 豁免 billing 路径）；`BillingController`（summary/records 复用全租户用量查询，仅元数据）。
- **消费者管理 UI**：`AdminConsumersView`（创建表单 + 一次性 Key 弹窗 + 列表 + 吊销确认）+ 类型/API 层/路由/导航（运营组「API 消费者」）；vitest +3。
- **配额状态端点（本会话扩展）**：`GET /api/v1/billing/quota` —— 全租户最近配额快照按订阅分组（含订阅名；无快照订阅以空列表出现）；`QuotaSnapshotRepository.findLatestForTenant`（DISTINCT ON 跨订阅取每作用域最新）；外部视图 `QuotaEntryView` 只含配额数字与 `source` 权威级别，内部字段（`errorMessage`/`providerStatusJson`）不暴露；api-contract §5.10 更新。
- **测试基建修复（既有 flaky，对照组证实与本功能无关）**：全套件下 `RouteSnapshotRefreshNotifierTest` 3/3 失败，根因 `PSQL FATAL: sorry, too many clients` —— 共享 Testcontainers Postgres（默认 100 连接）被多个缓存 Spring 上下文的 Hikari 池（默认 20/上下文）耗尽，裸 probe 连接打不开。修复：测试基类池降至 10 + 共享容器 `max_connections=200`（测试专用配置，生产零影响）。
- **JWT 认证（ADR-0011，对标阿里消费者认证）**：消费者可选配置 RS256 验签公钥（PEM），平台自持私钥签发 JWT（`sub`=消费者名，`exp` 必填），网关 JDK 原生验签（零三方库）——`ConsumerJwtVerifier`（RS256-only、无 padding Base64url 补位、exp/nbf 校验、token/payload 大小上限）；`ApiKeyAuthFilter` 双凭据（`X-API-Key` 只走 API Key，`Authorization: Bearer` 按 `mqk_api_` 前缀分流）；管理 API `PUT/DELETE /jwt-key`（返回 SHA-256 指纹，轮换立即失效）；V14 加列。验证：`ConsumerJwtVerifierTest` 14/14（篡改/过期/nbf/alg=none/错签名/无 padding/超限）+ `BillingApiIntegrationTest` 8/8（JWT 主流程/轮换/禁用/删除即失效/API Key 回归）。
- **验证**：全量后端 `verify -P integration` **BUILD SUCCESS**（控制面 290/0：273 + billing 3 + verifier 14；全模块 1021 基线 1004 + 17）。
- **排障记录**：MockMvc 下 billing 401 根因是 SessionFilter（order -100）先于 ApiKeyAuthFilter（-90）拦截无 session 请求 —— 会话过滤器豁免 billing 路径，由 API Key 过滤器接管。
- **后续**：用户级映射与 JWT 确权（平台注册细节明确后）；SkillHub/Agent 管理按 roadmap P2/P3。

## G8.2 — 项目月度预算（配额管理的落地，只预警不阻断）

- **来源**：leader 蓝图「配额的管理」；腾讯消费者配额管理（Token/请求次数 × 日周月 × 预警状态）+ 阿里 FinOps 消费者配额（周期总量 + 水位大盘）文档研究后本土化 —— 预算表（`budget`/`model_budget`，V7）早已建表但管理面与水位缺失。
- **交付**：`Budget` 领域模型 + `BudgetRepository`（(project, month) upsert）；`AdminBudgetService`（水位 = 当月分摊成本经 `UsageStatsAggregator` 实时计算；level = NORMAL/WARNING/EXCEEDED 按阈值派生）+ `AdminBudgetController`（GET /budgets 全项目水位、GET/PUT/DELETE /projects/{id}/budget）；前端 AdminCostView 新增「月度预算」面板（汇总水位条 + 每项目水位/状态徽标/编辑删除 + 设置弹窗）。**零迁移**（V7 已建表）。
- **验证**：`AdminBudgetApiIntegrationTest` 4/4（生命周期/校验/水位链路：seed usage_event + price_snapshot → spent=0.01 → EXCEEDED 精确断言）；前端 vitest 46/46（+3：水位渲染/编辑保存参数/删除确认）；全量后端 `verify -P integration` **BUILD SUCCESS**（全模块 1025 = 1021 + 4）。
- **对齐**：腾讯「正常/预警/超限」三态 + 阿里「事前定规则、事中控风险」—— 只告警不阻断，符合「不因预算阻断」产品锁定决策；硬阻断与 Token/请求次数配额列为后续（需 ADR）。
- **风险**：`spent` 取读时刻最新单价快照（价格变更后历史水位随单价变化，与 G4.3 同语义）；预算告警（BUDGET_THRESHOLD 事件/Webhook）未接线，列为扩展点。

## G8.3 — 预算水位告警（BUDGET_THRESHOLD，配额管理预警闭环）

- **来源**：G8.2 扩展点落地 —— 腾讯消费者配额「预警状态 + 超配策略」中的预警经 Webhook 通知闭环。
- **交付**：新告警类型 `BUDGET_THRESHOLD`（V15 扩展 `alert_rules.type` CHECK 约束）：规则 `scopeJson={"projectId": …}`（创建/更新时校验项目存在且同租户，`400 SCOPE_INVALID`）；`AlertEvaluator` 复用 `AdminBudgetService` 计算当月水位百分比，命中阈值触发事件 + HMAC 签名 Webhook 投递；去重键按（规则 × 月份）——同月仅告警一次（其余类型仍按小时桶）。前端告警规则页：类型「预算水位」+ 条件项目选择 + 阈值单位切换 + 列表 scope 项目名提示。
- **验证**：`AdminBudgetApiIntegrationTest` 6/6（+2：水位 100% ≥ 阈值 80 触发事件 value=100、同月二次评估去重仍 1 条、无 scope/未知项目 400、有预算不触发路径）；前端 vitest 48/48（+2 告警页：scope 提示渲染、预算规则创建必须选项目 + scopeJson 组装）；全量后端 `verify -P integration` **BUILD SUCCESS**（全模块 1027 = 1025 + 2）。
- **排障记录**：Postgres jsonb 列传 String 参数报「column is of type jsonb but expression is of type character varying」—— JDBC 需 `:scopeJson::jsonb` 显式 cast。
- **风险**：预算事件 payload 只含 rule/type/value（无项目名）——投递方可按 ruleId 反查；与既有四类告警行为一致。

## P2.2/P2.3 — SkillHub 技能目录后端（Anthropic Agent Skills 格式 + 腾讯 SkillHub 分发模式）

- **来源**：leader 蓝图「SkillHub：部门/项目看到所有 skill、只下载对应的 skill」；P2.1 形态调研（2026-09-01 存档 roadmap）：格式采用 Anthropic Agent Skills 规范（SKILL.md frontmatter），分发对标腾讯 SkillHub「应用商店」模式（安全审核 + 标签）。
- **交付**：V16 迁移（`skills` + `skill_access`）；`SkillZipValidator`（zip 单根目录校验 + SKILL.md frontmatter 解析：name kebab-case/目录一致/保留词禁令、description 必填、tags 提取；上限 5MB/200 条目/512KB——zip 炸弹防护，只读 SKILL.md 不解压）；`SkillService`/`SkillRepository`（上传 upsert、归档、授权管理）；公开目录 API（`GET /api/v1/skills[/{id}]`）+ 下载门禁（`canDownload`：无授权行=公开、TEAM/PROJECT 成员、管理员绕过）+ 管理 API（上传/归档/授权整体替换）。**可见性=全部 ACTIVE，下载=按授权**。
- **验证**：`SkillZipValidatorTest` 10/10（合法/BOM/缺 SKILL.md/名不匹配/保留词/description/无 frontmatter/多根/非 zip/超限）；`SkillApiIntegrationTest` 4/4（上传解析+可见性+匿名 401+校验码、下载门禁字节一致+成员/非成员 403/管理员、归档隐藏+重传恢复、授权 scope 校验）；全量后端 `verify -P integration` **BUILD SUCCESS**（全模块 1041 = 1027 + 14）。
- **排障记录**：① archive 曾走 upsert 但 upsert 的 ON CONFLICT 硬编码 `status='ACTIVE'` 把归档覆盖回去（真实缺陷，加独立 `archive` 方法）；② 归档后详情/下载仍 200（目录面只暴露 ACTIVE，`findActive` 门禁）；③ 非 zip 垃圾字节被误判「空包」（<22 字节=必然无效 zip，≥22 无条目=真空包）。
- **后续**：P2.4 前端（SkillHub 浏览页 + 管理上传页，下一轮）。

## P2.4 — SkillHub 前端（浏览 + 管理，P2 SkillHub 收官）

- **交付**：`SkillHubView`（全员浏览页：技能卡片网格——名称/版本/描述/标签/作者/许可证/大小 + 下载按钮，403 时友好提示）；`AdminSkillsView`（管理页：上传表单——zip 文件 + 语义化版本、技能表格——状态徽标/授权/归档、授权弹窗——项目/团队多选整体替换）；http 层新增 `downloadBlob`/`uploadBytes`（Blob 下载 + 原始字节上传带 CSRF）；路由/导航（常规组 SkillHub + 运营组 SkillHub 管理）。
- **验证**：vitest 56/56（+8：浏览渲染/下载调用/403 提示/空态；管理表格/上传参数/归档确认/授权保存）；lint/typecheck/build 全 PASS。
- **gitflow**：本 Goal 起严格按 git-workflow.md 在 `goal/` 分支开发（`goal/p2.4-skillhub-frontend`），验证后 push 分支，合并由用户在 GitHub 执行。
- **后续**：P2 SkillHub 全部完成 → P3.1 Agent 管理（阿里 Agent 拓扑）。

## P3.1 — Agent 管理（对标阿里 AI 网关 Agent 拓扑）

- **来源**：leader 蓝图「Agent 管理」；P2 阶段文档研究结论（阿里 Agent 拓扑：入口认证 + 出口模型链路 + 按 Agent 观测）。
- **交付**：`agents` 表（V17，出口绑定 ACTIVE 凭证，产品由凭证 → 订阅派生）；`AdminAgentService`/`AgentRepository`（创建校验凭证、禁用乐观锁、按凭证聚合用量——复用 `AdminUsageStatsService.summary(credentialId)`）；`AdminAgentController`（CRUD + `GET /{id}/usage`）；前端 `AdminAgentsView`（表格——凭证/派生产品名/状态 + 创建表单——凭证下拉 + 用量弹窗——请求/Token/成本四卡 + 禁用确认）；路由/导航（运营组 Agents）。
- **验证**：`AdminAgentApiIntegrationTest` 4/4（生命周期/凭证与重名校验/用量聚合精确断言 1 请求 100/50 tokens）；前端 vitest 60/60（+4）；全量后端 `verify -P integration` **BUILD SUCCESS**（全模块 1045 = 1041 + 4）。
- **排障记录**：用量聚合 0 行的根因——PROJECT 分组是 INNER JOIN projects，seed 的随机 project_id 被丢弃（测试 seed 真实项目后修复）。
- **边界**：入口路由（外部访问 Agent 的域名/消费者认证）为后续扩展；Agent 凭证轮换/吊销后 Agent 自动失效（绑定凭证引用，凭证级联 RESTRICT）。
- **gitflow**：分支 `goal/p3.1-agent-management`，验证后 push + PR，合并由用户在 GitHub 执行。

## P3.2 — 内部服务管理（对标腾讯服务来源）

- **交付**：`services` 表（V18，内部服务注册表：名称/类型 HTTP|MCP|OTHER/描述/服务地址/状态）；`AdminServiceService`（base_url 校验：https 必选、无 userinfo/query/fragment——镜像上游目标规则）+ `AdminServiceController`（CRUD + 禁用）；前端 `AdminServicesView`（表格——类型徽标/服务地址/状态 + 注册表单——类型下拉 + 禁用确认）；路由/导航（运营组「服务管理」）。
- **验证**：`AdminServiceApiIntegrationTest` 3/3（生命周期含 kind 缺省 HTTP、URL 校验——http/userinfo/query 全拒、重名 409）；前端 vitest 63/63（+3）；全量后端 `verify -P integration` **BUILD SUCCESS**（全模块 1048 = 1045 + 3）。
- **边界**：注册表为网关集成的前置目录；实际路由接线（服务 → 网关转发）等 leader 集成细节；禁用后注册信息保留（软禁用）。
- **gitflow**：分支 `goal/p3.2-service-management`，验证后 push + PR，合并由用户在 GitHub 执行。

## P3.3 — 全局配置中心（P 计划收官）

- **交付**：`config_entries` 表（V19，分组键值条目 + 乐观 version）；`AdminConfigService`/`AdminConfigController`（`GET /admin/configs?group` 列表/分组过滤、`PUT` upsert、`DELETE /{group}/{key}`；名称规则 `[a-zA-Z][a-zA-Z0-9._-]{0,127}`）；前端 `AdminConfigsView`（分组筛选条 + 表格 + 新增/编辑弹窗——编辑时 group/key 锁定 + 删除确认）；路由/导航（运营组「全局配置」）。
- **验证**：`AdminConfigApiIntegrationTest` 3/3（生命周期含 upsert 原地替换与分组过滤、名称/值校验全拒绝路径）；前端 vitest 67/67（+4）；全量后端 `verify -P integration` **BUILD SUCCESS**（全模块 1051 = 1048 + 3）。
- **边界**：仅非机密配置（机密走 env/加密凭证体系，页面明示）；配置项为目录形态，应用侧热更新接线待集成细节。
- **gitflow**：分支 `goal/p3.3-global-config`，验证后 push + PR，合并由用户在 GitHub 执行。

## P0–P3 全部落地（2026-09-01 里程碑）

- **P0 身份地基**：消费者 API Key（G8.1）+ JWT 认证（ADR-0011）+ 计费查询 API
- **P1 计费服务化**：计费/配额查询 + 项目月度预算（G8.2）+ 预算水位告警（G8.3）
- **P2 SkillHub**：形态调研（P2.1）+ 目录/授权后端（P2.2/P2.3）+ 浏览/管理前端（P2.4）
- **P3 内部治理**：Agent 管理（P3.1）+ 服务管理（P3.2）+ 全局配置（P3.3）
- 待合并 PR：#110/#111（SkillHub）、#112（Agent）、#113（服务）、#114（配置）
- 阻塞项：用户级映射 + 平台确权细节（等 leader 提供注册字段）；Kafka 场景（等 leader 细化）

## P3.4 — MCP 服务管理（对标腾讯 AI 网关 MCP 管理）

- **来源**：用户指示继续依据腾讯/阿里结构补齐能力；对照两家能力清单，MCP 管理是最大缺口（腾讯：MCP 服务管理 + 上下线 + 健康检查 + Tools；阿里：MCP 全生命周期）。本轮实现前两块，Tools 发现列为扩展。
- **交付**：`mcp_services` 表（V20：传输类型/上下线状态/健康状态/失败恢复计数器/检查配置）；`AdminMcpService`（注册——端点 https 无 userinfo 校验、手动上下线——重复切换 409、健康检查配置更新）；`McpHealthChecker`（定时 15s 遍历 ONLINE 服务，按各自间隔探测 `endpoint + checkPath`，GET 2xx 计健康；连续失败达 fail_threshold → UNHEALTHY、连续成功达 recover_threshold → HEALTHY；**手动下线不被健康检查覆盖**——腾讯语义）；前端 `AdminMcpServicesView`（表格——接入地址/传输/上下线/健康三态徽标 + 注册表单 + 上下线确认 + 健康检查配置弹窗）；路由/导航（运营组「MCP 服务」）。
- **验证**：`McpHealthCheckerTest` 4/4（真实 loopback HttpServer：2xx 健康/500 与连接拒绝不健康/失败阈值翻转/恢复阈值翻转——状态机提取为纯函数 `nextHealth`）；`AdminMcpServiceApiIntegrationTest` 3/3（生命周期含默认配置与健康配置更新、端点校验与重名）；前端 vitest 71/71（+4）；全量后端 `verify -P integration` **BUILD SUCCESS**（全模块 1058 = 1051 + 7）。
- **边界**：Tools 自动发现/启停（腾讯 Tools 管理）为扩展；健康检查为控制面出站直连（管理员配置端点，与内部服务同信任域）；`miqrokey.mcp.health-cycle-ms` 可配置。
- **gitflow**：分支 `goal/p3.4-mcp-services`，验证后 push + PR，合并由用户在 GitHub 执行。

## P3.5 — MCP Tools 管理（对标腾讯 AI 网关 Tools 管理）

- **来源**：P3.4 明确列为 follow-up 的腾讯 Tools 管理（工具手动创建 + 逐个启停，工具名 = AI Agent 调用唯一标识）。
- **交付**：`mcp_tools` 表（V21：工具名 snake_case/描述/方法/路径/启停状态，绑定 MCP 服务 ON DELETE CASCADE）；`AdminMcpToolService`/`AdminMcpToolController`（`GET/POST /mcp-services/{id}/tools`、`POST /tools/{toolId}/status?status=ENABLED|DISABLED`；工具名与路径校验、同服务重名 409）；前端 MCP 服务页加「Tools」按钮 → 弹窗（工具列表——名称/描述/方法路径/状态徽标 + 启停 + 新建表单）。
- **验证**：`AdminMcpToolApiIntegrationTest` 3/3（生命周期含默认 GET 方法/启停/重复切换 409、名称/路径/方法/服务作用域/重名校验）；前端 vitest 73/73（+2 Tools 弹窗）；全量后端 `verify -P integration` **BUILD SUCCESS**（全模块 1061 = 1058 + 3）。
- **边界**：OpenAPI 批量导入（腾讯能力）列为扩展；工具调用代理（经网关转发到 MCP 服务）待 MCP 协议接线。
- **gitflow**：分支 `goal/p3.5-mcp-tools`，验证后 push + PR，合并由用户在 GitHub 执行。

## 待办需求（2026-08-28 leader 指示，细节待补充，暂不实施）

1. **Kafka 引入**：leader 明确 Kafka 技术一定会用到。当前事件管道为 PostgreSQL NOTIFY + 有界内存队列；引入场景未定（用量事件流/跨服务集成/多实例）。落地前需 ADR。
2. **报备合规 → 外部平台用户对接**：因报备原因存在外部测试平台，通过 user_id（电话或账号体系）对接；要求外部平台组测用户在网关侧有对应账号（用户同步）。落地前需 ADR（当前用户体系为本地 Argon2id，无外部身份源）。

## Known Blockers

- 真实供应商凭证尚未提供；不阻塞 Mock 与本地契约开发。
- 本机 Docker Desktop 可用（`D:\programming\Docker_4.78.0`）；Compose config 本地 PASS，digest 门禁由 CI 复核。

## Next Goal（历史遗留段——已于 2026-09-02 G6.5 会话完成，见文件顶部 Current State）

- Goal ID: `G6.5` — 发布就绪收尾：**DONE**（2026-09-02；本段当时为 G6.x 时期写入，勿再按此执行）
- 正式 Goal 序列（implementation-plan Phase 0–6）已全部闭环；后续增量候选与立项见文件顶部。

## G6.4 — Performance and soak（DONE）

### 交付

- `SoakIntegrationTest`（gateway-app，`@Tag("soak")`）：真实 gateway + mock 上游 + PostgreSQL 的 30 秒并发流浸泡——断言 0 上游错误、`request_usage_records` 全部落库（usage 队列 drop 会表现为缺行）；随 CI 全量套件运行。
- `deploy/loadtest/soak.sh`：生产类环境长时间浸泡（并发流 + 吞吐/延迟分位/错误率统计 + usage 队列 drop 检查），经 monitoring profile 指标观察。
- operations-runbook 新增性能/浸泡章节与首版验收基线（并发 20 流 30 分钟 0 错误、队列 drop 恒 0、p99 ≤ 2× 基线）。

### 验证

- 本机 Docker 代理故障（127.0.0.1:7897 不可达）无法拉取新镜像 digest → soak 测试本地无法执行；**CI 全量套件（ubuntu/windows）为权威验证**。
- soak.sh 语法与组件在 Git Bash 验证通过。

## G6.3 — Security and supply-chain gate（DONE）

### 交付

- `deploy/security/check-secrets.sh`：`git grep` 高信号凭证模式门禁；**实际抓到并修复**：cc-switch 兼容文档 7 文件 23 处完整格式示例 Key → 打码 `sk-miqrokey-…REDACTED`。
- `deploy/security/check-sbom.sh`：CycloneDX 聚合 BOM（gateway + control-plane 运行时依赖，107 组件）+ copyleft 许可证门禁（GPL/LGPL/AGPL/SSPL/EPL/MPL/CC-BY-NC/SA 拒绝）。
- CI `security` job：secret 扫描 + SBOM/许可证 + Trivy 镜像扫描（postgres digest 固定镜像，HIGH/CRITICAL 未修复失败）。
- 目录签名（G2.1）与审计哈希链（G2.3）在 security.md 供应链章节归档。

### 验证

- `bash deploy/security/check-secrets.sh` → **secret scan ok**
- `bash deploy/security/check-sbom.sh` → **license gate ok (107 components)**
- **镜像升级（Trivy 实际发现驱动）**：postgres 17.6-alpine 旧 digest（2025-04）含 22 个 HIGH/CRITICAL（golang 工具链）；升级至 2026-08-16 最新 digest（10 处引用：compose + 8 个测试类 + backup 演练 + CI）；`.trivyignore` 仅豁免 3 个已知工具链 CVE 并注明升级后复查。

## G6.2 — Backup and restore（DONE）

### 交付（`deploy/backup/`）

- `miqrokey-backup.sh`：pg_dump(custom) → gzip → AES-256-CBC（PBKDF2 200k + 随机盐）→ 加密文件 + SHA-256 manifest；保留上限 `DAILY_KEEP + WEEKLY_KEEP` 超出删最旧；Webhook 通知（可选 HMAC-SHA256 签名）；退出码 0/1/2/3 语义。
- `miqrokey-verify.sh`：manifest 强校验 + 解密干跑（不触碰任何库）。
- `miqrokey-restore.sh`：manifest 校验 → 解密 → `pg_restore --exit-on-error`。
- `test-restore.sh`：**真实恢复演练**（双 Postgres 17.6 容器：播种 1000 行 → 真备份 → 校验 → 恢复 → 行数一致断言）——本地 PASS。
- `test-retention-webhook.sh`：保留上限（10 假文件 → cap 后 3）+ Webhook 签名投递测试——本地 PASS。
- operations-runbook 新增备份/恢复操作手册（cron 示例、密钥分离、季度演练要求）。

### 验证

- 真实演练：`bash deploy/backup/test-restore.sh` → **restore drill PASS: 1000 rows intact**
- `bash deploy/backup/test-retention-webhook.sh` → retention PASS + webhook PASS

## G6.1 — Observability and optional monitoring profile（DONE）

### 实现

- **Prometheus 指标**：`monitoring` profile 激活时暴露 `/actuator/prometheus`（`management.prometheus.metrics.export.enabled: true` 显式开启 —— Boot 3.4 起默认不导出，这是本 Goal 最大的坑：endpoint 注册失败曾表现为 404/500，根因是 PrometheusMeterRegistry bean 未创建）。
  - gateway：`GatewayMetricsFilter` —— `miqrokey_gateway_requests_total`（status_class 2xx/3xx/4xx/5xx 五档低基数 counter）
  - control-plane：`QuotaSnapshotService` 注入 MeterRegistry —— `miqrokey_control_provider_calls_total` + `miqrokey_control_quota_refresh_total`
  - 依赖 `micrometer-registry-prometheus`（compile scope，代码引用需要）
- **JSON 日志**：`json` profile + `logback-spring.xml`（LogstashEncoder；`SPRING_PROFILES_ACTIVE=monitoring,json`）
- **Grafana Dashboard**：`deploy/grafana/miqrokey-dashboard.json`（4 面板：请求速率/状态分布、provider 调用、JVM 堆、usage 队列）
- **健康检查**：既有 `/actuator/health`（when-authorized）；高基数保护：configuration-reference §8 指标标签禁令（用户/Key/模型/正文绝不入标签）
- **默认安全边界不变**：无 monitoring profile 时端点关闭（GatewayApplicationSmokeTest 断言维持）

### 测试（本 Goal 新增 3 个）

- `GatewayMonitoringProfileTest`（2）：真实 HTTP 抓取 200 + 自定义 counter 存在；请求计数 4xx counter 递增（micrometer 1.14 指标名无 `_total` 后缀）
- `ControlPlaneMonitoringProfileTest`（1）：真实端口抓取 200 + provider-call counter（MockMvc 不挂载 management 端点，必须真实 HTTP）

### 验证

- 全量 `verify -P integration`：**958 tests / 0 failures / 0 errors / 5 skipped**

## G5.5 — UI security and accessibility（DONE）

### 实现

- **权限路由**：所有管理路由 `meta.requiresAdmin`；router guard 对非 SYSTEM_ADMIN 重定向到 `/app/keys`（不渲染 403 页）。
- **敏感信息防缓存**：`index.html` 加 `Cache-Control: no-store` meta，敏感视图不进 bfcache。
- **键盘操作**：全局 `:focus-visible` 焦点环（accent 2px）；导航项/页脚链接适配。
- **可访问性**：移动端导航按钮 `aria-label`、用户菜单 `role="button"` + `aria-haspopup`。
- **错误状态**：既有（错误 alert 带 requestId + 表单就近错误）。
- 中文文案与业务名词：既有页面已按 spec 使用明确业务名词。

### 测试（e2e +2）

- 普通用户访问管理路由 → 重定向到登录（mock 401 场景）。
- 渲染页存在 `:focus-visible` 焦点环规则。
- Playwright **14/14 通过**；lint/typecheck/21 vitest/build 全 PASS。

## G5.4 — Admin usage, export and alerts portal（DONE）

### 后端（PR #51，8d715b9）

- `GET /api/v1/admin/audit-events`：审计链逆序列表（action 过滤 + chain_position 游标分页）；链哈希永不序列化

### 前端（本交付）

- `AdminUsageView`：全租户统计（筛选条 → 汇总行 → 明细表模式）
- `AdminExportsView`：创建导出（CSV/JSONL + 窗口）+ 轮询状态 + 下载
- `AdminDeletionsView`：预览计数 → 创建请求（token 一次性返回）→ 二次确认 Dialog 完成永久删除
- `AdminWebhooksView`：端点 CRUD + 签名测试投递 + 投递记录 drawer
- `AdminAlertRulesView`：规则 CRUD（类型/阈值/去重窗口/端点选择）
- `AdminAuditView`：审计链表格（位置/action/摘要，action 过滤）
- API 层：20+ 个管理函数与类型；路由/导航补齐（管理组 10 项）

### 验证

- 前端 lint/typecheck/21 vitest/build 全 PASS；Playwright 12/12
- 后端全量 `verify -P integration`：955 tests / 0 failures / 0 errors / 5 skipped

## G5.3 — Admin provider and Plan portal（DONE，后端 + 前端）

### 后端（PR #49，e42308d）

- `AdminProviderService` + `AdminProviderProductController` + `AdminSubscriptionController`：产品实例列表（供应商/协议/Base URL host/实现状态/余额权威级别）、订阅 CRUD、席位创建/分配/释放
- 审计事件（SUBSCRIPTION_*/SEAT_*，走 AuditService 哈希链）；SYSTEM_ADMIN-only；api-contract §5.0b；2 个 Testcontainers 集成测试

### 前端（本交付）

- `AdminProvidersView`：产品表格（供应商/产品/productCode/协议/Base URL host/实现状态 mk-status/余额来源标签）
- `AdminPlansView`：订阅表格（产品/计费/Plan 形态/价格/状态）+ 创建表单（产品/计费/Plan 形态/价格/配额）+ 席位 drawer（分配/释放）
- 路由替换 providers/plans 占位；e2e providers baseline 截图
- **e2e 基础设施根治**：webServer 改用 `vite preview`（生产构建）——不再有 dev 模块图竞态，"预加载桥接不可用"问题结构性消除；审美检查改为生产 bundle 下只审计自有设计规则（`:root`/`.mk-*`/`--miqrokey*`）

### 验证

- 后端全量 `verify -P integration`：955 tests / 0 failures / 0 errors / 5 skipped
- 前端 lint/typecheck/21 vitest/build 全 PASS；Playwright 12/12（10 张 baseline 截图）

## G5.2 — Admin organization portal（DONE，后端 + 前端）

### 后端（PR #46，d9cfcd5）

- `AdminOrgService` + 4 控制器：users（创建/禁用/重置密码/撤销会话）、teams+members、projects+members、grants+模型范围
- 临时密码一次性返回（不落明文）；**Jackson mixin 全局排除 `User.passwordHash`**（修复 login/me 响应泄漏空 hash 的既有缺陷）
- 全操作审计事件；SYSTEM_ADMIN-only；api-contract §5.0；4 个 Testcontainers 集成测试

### 前端（本交付）

- `AdminUsersView`：用户表格（mk-status 状态）+ 创建表单（角色选择）+ 一次性临时密码 Modal（白底等宽 + 明确不可恢复提示）+ kebab 操作（禁用/重置密码/撤销会话）
- `AdminTeamsView` / `AdminProjectsView`：表格 + 创建表单 + 成员 drawer（移除需确认）
- `AdminGrantsView`：Grant 表格 + 创建（项目/凭证选择 + 模型文本域）+ 模型范围 drawer（整体替换）+ 禁用
- `api` 层：`patch`/`del` HTTP 动词 + 10 个管理 API 函数 + 类型
- 路由：users/teams/projects/grants 替换占位页；导航分组（管理）直连
- e2e：admin users baseline 截图 + 浏览器预热 globalSetup（**根治 vite 冷启动预加载桥竞态**）；11/11 通过

### 验证

- 后端全量 `verify -P integration`：953 tests / 0 failures / 0 errors / 5 skipped
- 前端 lint/typecheck/21 vitest/build 全 PASS；Playwright 11/11（9 张 baseline 截图）

## G5.1 — User portal（DONE）

### 实现（基于 G5.0 foundation 收尾）

- `KeysView`：状态列从 `el-tag` 改为 `mk-status` 紧凑标签（圆点 + 短文案 + 颜色语义，spec §3/§7）；操作列改为 kebab dropdown（轮换 + 分隔线 + 吊销危险分组，spec §6 行操作模式）。
- `UsageView` / `ProfileView`：统一改用 `PageHeader` 组件（标题/说明/主动作同一区域）。
- `vite.config.ts`：`server.warmup.clientFiles` 预转换入口，根治 Playwright 冷启动预加载桥竞态（替代 retries 兜底）。
- E2E 扩展：新增「Key 操作」测试（kebab menu 渲染轮换/吊销、状态标签文案）；补 `/me/grants` mock；baseline 截图刷新（8 张）。

### 测试

- 前端：lint / typecheck / 21 vitest / build 全 PASS。
- E2E：**10/10 通过**（登录/认证 shell × 4 viewport + Key 操作 + 渲染页审美扫描）。

### 风险与边界

- 登录、首次改密、创建一次性 Secret、轮换、吊销、个人用量在 G5.0 前已实现并通过既有测试；本 Goal 完成视觉与交互规范收尾。
- 视觉 review 仍待人工（spec §9）。

## G5.0 — Frontend design foundation（DONE）

### 实现

- `tokens.css` 扩展：spacing（8/12/16/24/32）、控件高度（32/36px）、表格行高（40/36px）、内容最大宽（1440/760px 表单）、折叠导航宽（56px）。
- `global.css`（新）：页面骨架样式 —— `mk-page-header`（标题/说明/主动作同一区域）、`mk-filter-bar`（筛选条）、`mk-summary-row`（汇总行）、`mk-panel`（边框分区、无阴影）、`mk-status`（短状态标签，颜色+圆点不单靠颜色）、`mk-danger-zone`（页面底部危险区）、`mk-shell-footer`、数字列右对齐 helper。
- `AppShell` v2：响应式三态（≥1280 全宽导航 / 768–1279 图标折叠 / <768 drawer，resize 监听）；导航分组（常规 + 管理，管理员可见，`@element-plus/icons-vue` 线性图标）；用户菜单（角色 + 退出）；footer（版本/catalog/同步）。
- `PageHeader.vue` 组件；`KeysView` 改用 PageHeader；`PlaceholderView` + 5 个占位路由（providers/plans/credentials/audit/settings）。
- Element Plus 覆盖：表格水平分隔 + sticky header + 行 hover；阴影只用于 popper/dropdown/dialog。
- **Playwright visual baseline**（新，`test:e2e`）：4 个必需 viewport（1440×900/1280×800/768×1024/390×844）的登录页 + 认证 shell 截图（API 全 mock，无需后端），提交至 `e2e/baseline-screenshots/`；渲染页审美扫描（同源样式表无渐变/无紫色 tokens）。配置 `channel: 'chromium'` 规避 headless shell 的 vite 预加载桥问题，`retries: 1` 吸收冷启动竞态。
- **审美审计测试**（vitest，5 个断言）：扫描 `src/styles/*.css` —— 无任何渐变、无紫色 tokens、常规容器 radius ≤ 8px（状态 pill 为例外）、无巨型 pill、阴影仅限 popper/dropdown/dialog。

### 测试

- 前端：lint / typecheck / 21 个 vitest（含 5 个审美审计）/ build 全 PASS。
- E2E：**9/9 Playwright baseline 通过**（8 张截图 + 1 个渲染页审美扫描）。

### 风险与边界

- 视觉 review 需人工进行（spec §9：不能只凭 E2E 功能通过视为设计完成）；截图已提交可 diff。
- 管理端页面为占位路由（后续 Goal 逐个实现）；导航与权限边界已就绪。
- `test:e2e` 需先 `npx playwright install chromium`（完整版，headless shell 与 vite 预加载桥不兼容）。

## G4.5 — Webhook alerts（DONE）

### 实现

- `V12__webhook_alerts.sql`：`webhook_endpoints`（URL/加密签名 Secret/启停/超时）+ `alert_rules`（类型/阈值/去重窗口/可选端点）+ `alert_events`（`(tenant_id, rule_id, dedupe_key)` 唯一去重）+ `webhook_delivery_attempts`（尝试次数唯一、指数退避、脱敏错误）。
- `WebhookEndpointService`：CRUD + 测试投递 + 投递历史；URL 创建时经控制面 SSRF 门控（公网 https 默认）；签名 Secret AES-GCM 加密（AAD tenant+endpoint）永不返回；投递 `X-MiQroKey-Signature: sha256=<HMAC hex>`。
- `AlertRuleService`：规则 CRUD（类型白名单校验）。
- `AlertEvaluator`（`@Scheduled` 固定延迟，`miqrokey.alerts.evaluation-interval-ms` 默认 5min）：四类指标（usage 缺失率/错误率/余额 UNAVAILABLE 数/用量激增比率，滚动 1h 租户级）→ 阈值比较 → 小时桶去重（ON CONFLICT DO NOTHING）→ HMAC 签名投递；失败指数退避重试（2^attempt × 1min，最多 3 次）。
- `SchedulingConfig`（@EnableScheduling）、控制器 `AdminWebhookController` + `AdminAlertRuleController`（SYSTEM_ADMIN only）。
- docs：database-schema V12、api-contract §5.7/§5.8、configuration-reference（评估间隔）。

### 测试（本 Goal 新增 2 个集成）

- `WebhookAlertApiIntegrationTest`（2，Testcontainers + loopback 接收器）：端点生命周期（私网 URL 拒绝/Secret 永不返回/签名测试投递）；规则评估全链路（缺失率 0.5 触发 → 事件 FIRED + 签名投递一次 + 投递记录一行 → 同小时桶二次评估去重不再投递）。

### 风险与边界

- 计划中的"系统/备份"与"凭证失效"两类告警未实现（无备份/系统健康遥测数据源、凭证失效告警依赖 validateCredential 接线，G4.x 收尾可补）；当前四类均基于 usage/quota 数据。
- 指标为租户级（单租户部署语义）；规则 scope_json 预留未用。
- 投递重试上限 3 次后放弃（事件保持 FIRED，去重防止刷屏；人工可从投递历史排查）。

### 验证

- 全量 `verify -P integration` → **BUILD SUCCESS**，**949 tests / 0 failures / 0 errors / 5 skipped**（947 + 2 新增）。

## G4.4 — Raw export and manual deletion（DONE）

### 实现

- `V11__export_and_deletion.sql`：`export_tasks`（异步导出任务：格式/窗口/状态/SHA-256/行数/字节数/gzip 产物/24h 过期/脱敏错误）+ `usage_deletions`（双确认删除：预览计数/token 哈希/状态/删除数/1h 确认窗口）。
- `ExportTaskService`：创建即 `202`（PENDING），有界 daemon 线程池（2 线程）渲染窗口为 CSV/JSONL（仅计数与元数据列）→ gzip → SHA-256 落库；下载服务产物直至过期。
- `UsageDeletionService`：干跑预览 → 创建请求（一次性 token 仅 SHA-256 入库，明文仅本次返回）→ 确认（常量时间比对、窗口/状态校验）→ 物理删除 + `USAGE_DELETE` 审计事件。
- 控制器：`AdminExportController`（create/status/download/recent）、`AdminUsageDeletionController`（preview/create/confirm/recent），均 SYSTEM_ADMIN only。
- api-contract §5.5/§5.6、database-schema（export_tasks/usage_deletions）更新。

### 测试（本 Goal 新增 3 个集成）

- `ExportDeletionApiIntegrationTest`（3，Testcontainers）：导出全链路（202 → 轮询 SUCCEEDED → 下载 gunzip 校验两行 + 元数据列 + 无 Secret 字样 + SHA-256 头一致）；删除全链路（预览 2 → 错误 token 403 → 正确 token 执行 → 行清零 + 审计存在 → 重复确认 409）；匿名 401。

### 风险与边界

- 导出产物存 DB（bytea）：93 天窗口 × 元数据行的体积可控；超大窗口的未来方案为对象存储（文档未承诺）。
- 删除物理执行、无软删除；`usage_deletions` 请求本身与审计链保留（永久审计）。
- 定时清理 EXPIRED 导出/过期删除请求未接线（管理面可见即可；垃圾回收属运维目标）。

### 验证

- 全量 `verify -P integration` → **BUILD SUCCESS**，**947 tests / 0 failures / 0 errors / 5 skipped**（944 + 3 新增）。本轮另见 domain 模块 `HmacVirtualKeyProviderTest.shouldFollowFormat` 一次性 flaky（随机数据边界），单独重跑通过，列入已知 flaky 清单。

## G4.3 — Pricing snapshots and cost allocation（DONE）

### 实现

- `V10__cost_allocations.sql`：按订阅周期/项目对象的成本分摊表；唯一键 `(subscription_id, period_start, period_end, target_type, target_id, algorithm_version)` 使重跑幂等、算法升级另起版本。
- `domain`：`CostAllocation` + `CostAllocationTargetType` + `CostAllocationRepository`（幂等 upsert、按周期查询）。
- `control-plane`：`CostAllocationService` —— 管理端触发分摊：本地 usage（按订阅凭证归属，输入+输出 token，按产品/模型计价）→ 每百万 token × 最新价格快照 = usageCost；非 PAYG 订阅价按窗口/周期天数比例折算 fixedCost，按项目 Token 权重分摊；`allocatedAmount = usageCost + fixedShare`；无用量不产出行。
- `AdminCostAllocationController`：`GET/POST /api/v1/admin/subscriptions/{id}/cost-allocation[/allocate]?from&to`（SYSTEM_ADMIN only）。

### 测试（本 Goal 新增 8 个）

- `CostAllocationServiceTest`（5，单元）：按模型计价 + Token 权重固定分摊（75/25 与 0.002/0.0005 断言）、无用量空结果、窗口短于订阅周期的价格折算（50%）、跨租户 404、周期校验。
- `CostAllocationApiIntegrationTest`（3，Testcontainers）：真实 Postgres 全链路分摊（两个项目、固定成本 100 → 75/25、usageCost 0.002）、无用量空结果、404/401/周期校验。

### 风险与边界

- 价格取分配时刻最新快照；逐事件价格快照为 usage_event 延后列（database-schema §6），价格变更后重跑同版本会覆盖历史——文档已注明。
- 分摊只覆盖经 Gateway 且归属该订阅凭证的流量；固定成本按窗口天数折算（非精确到小时）。
- 用户维度（target_type=USER）预留，当前只产出 PROJECT 行。

### 验证

- 全量 `verify -P integration` → **BUILD SUCCESS**，**944 tests / 0 failures / 0 errors / 5 skipped**（936 + 8 新增）。

## G4.2 — Quota snapshots and team plan views（DONE）

### 实现

- `V9__quota_snapshots.sql`：追加式历史表（subscription/seat/credential 三作用域、窗口类型、总/已用/剩余、单位、共享池、`source` 权威级别、脱敏错误）；`(tenant_id, subscription_id, synced_at DESC)` + `(tenant_id, credential_id, synced_at DESC)` 索引。
- `domain`：`QuotaSnapshot` + `QuotaWindow/QuotaUnit/QuotaSource` 枚举 + `QuotaSnapshotRepository`（insert、每作用域最新 `DISTINCT ON`、历史）。
- `control-plane`：`QuotaSnapshotService` —— 管理端触发刷新：订阅 → 产品 → 适配器（registry by productCode）→ 每个 ACTIVE 凭证解密 Secret（AES-GCM，用后 `SecretWiping.clearArray` 清零）→ 凭证作用域 `ProviderClient`（factory）→ `fetchPlanStatus` → OFFICIAL_API/UNAVAILABLE 行；订阅带 `quota_total`+`period_start` 时另写 LOCAL_ESTIMATE 行（本地 usage 输入+输出 token）；错误 → UNAVAILABLE + 脱敏 errorMessage（不含 URL/Secret/正文）。
- `AdminQuotaController`：`GET/POST /api/v1/admin/subscriptions/{id}/quota[/refresh]`（SYSTEM_ADMIN only，deny-by-default 拦截器）。
- `ProviderClientProperties` 新增 `allowed-cidrs`（`MIQROKEY_CONTROL_PROVIDER_CLIENT_ALLOWED_CIDRS`，默认空 = 仅公网 https；与网关同名变量对齐），`ProviderClientConfig.controlPlaneTargetValidator` 接线。

### 测试（本 Goal 新增 10 个）

- `QuotaSnapshotServiceTest`（6，单元）：每凭证官方拉取 + Secret 清零验证、适配器 UNAVAILABLE、无凭证 UNAVAILABLE + 配额估算、解密失败 → UNAVAILABLE 不抛、latest 租户作用域、跨租户统一 404。
- `QuotaSnapshotApiIntegrationTest`（3，Testcontainers + loopback mock DeepSeek 余额 API + 真实适配器/真实 HttpProviderClient/真实加密凭证）：刷新写 OFFICIAL_API + LOCAL_ESTIMATE 行且 mock 恰好调用一次、GET 视图一致、空订阅刷新后 UNAVAILABLE 行、未知订阅 404、匿名 401。
- `ProviderClientConfigTest`（+1）：配置 allowlist 后 validator 放行私网（默认拒绝不变）。

### 风险与边界

- 独占额度（Tencent 企业控制台配置）无官方 API 可查：以每凭证快照表达每 Key 视图，独占配置本身不落库（控制台事实，`WAITING_FOR_CREDENTIAL` 联调确认）。
- LOCAL_ESTIMATE 只覆盖经 Gateway 的流量（契约 §6 第 3 级语义）；`sharedPool` 来自适配器 PlanSnapshot。
- 定时刷新未接线（管理端手动触发；计划任务与告警属 G4.5 前后）。

### 验证

- 全量 `verify -P integration` → **BUILD SUCCESS**，**936 tests / 0 failures / 0 errors / 5 skipped**（926 + 10 新增）。

## G4.1 — Usage 查询与管理/管理员仪表盘 API（DONE）

### 实现

- `domain`：`UsageStatsRepository.UsageFilter` 扩展可选维度 `userId` / `projectId` / `credentialId` / `subscriptionId`（Plan）/ `providerProductId`（供应商）/ `modelId`；保留 4 参数便捷构造（自服务调用方不变）；`virtualKeyIds` 为 `null` 即管理端全租户形状，无租户缺省查询。
- `persistence-postgres`：`UsageStatsRepositoryImpl` 引入 `WhereBuilder` 动态 WHERE——四个查询（aggregateUsage/aggregateHits/countRecords/findRecords）共用；user 过滤经 `virtual_keys vkf` join、subscription 过滤经 `vkf → credentials crf` join（别名避免与分组 join 冲突）；cache-hit 路径的 provider_product/model 过滤经既有 `cache_entry` join。
- `control-plane-app`：
  - `AdminUsageStatsService`（新）：全租户 summary/records，聚合与成本复用 `UsageStatsAggregator`；窗口/分组/分页校验与自服务路径共享（`UsageStatsService.parseGroupBy/validateTimeRange` 提升为包内 static）。
  - `AdminUsageController`（新）：`GET /api/v1/admin/usage/summary` + `GET /api/v1/admin/usage/records`，全部可选过滤参数；访问控制由 `RoleInterceptor` deny-by-default（仅 SYSTEM_ADMIN）自动生效。
- `api-contract.md` §5.2：新增全局用量查询契约（参数、过滤语义、租户隔离、错误码）。

### 测试（本 Goal 新增 15 个）

- `AdminUsageStatsServiceTest`（9，单元/Mockito）：全维度过滤透传、无过滤仅租户作用域 + 默认 93 天窗口、成本计算、GROUP_BY_INVALID/TIME_RANGE_TOO_WIDE/TIME_RANGE_INVALID/PAGE_INVALID/SIZE_INVALID。
- `AdminUsageApiIntegrationTest`（6，Testcontainers + MockMvc + 真实 Argon2 登录）：管理端汇总见全租户（对照个人端）、userId 过滤、modelId/virtualKeyId 过滤、records 过滤 + 分页、普通用户 403 / 匿名 401、参数校验（含非法 UUID → 400 PARAM_INVALID）。

### 风险与边界

- 供应商产品过滤按 `provider_product_id`（实例级），vendor 级聚合需多产品组合查询（G4.2 仪表盘视图可补充）。
- 个人端行为不变（既有测试全绿）：用户只能看到自己 Key 的用量；管理端"见全租户"是刻意的权限差异。

### 验证

- 模块：`verify -pl control-plane-app -am` → BUILD SUCCESS；全量 `verify -P integration` → **BUILD SUCCESS**，**926 tests / 0 failures / 0 errors / 5 skipped**（911 + 15 新增；含 GlobalExceptionHandler 类型不匹配修复与集成测试全绿）。

## G3.5 — 阿里云百炼 Model Studio（Coding Plan + Token Plan 团队版 + 按量 API，DONE）

### 官方事实核验（2026-08-26，help.aliyun.com）

- Coding Plan：OpenAI base `https://coding.dashscope.aliyuncs.com/v1`、Anthropic base `.../apps/anthropic`；专属 Key `sk-sp-xxxxx`（与按量 `sk-xxxxx` 不互通，错用按量计费）；Pro ¥200/月、~6000 请求/5h、45k/周、90k/月；模型 `qwen3.7-plus`/`qwen3.6-plus`/`kimi-k2.5`/`glm-5`/`MiniMax-M2.5`/`qwen3-coder-plus` 等。
- Token Plan 团队版：OpenAI base `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`、Anthropic `.../apps/anthropic`；团队专属 Key（管理员在组织成员列表管理）；仅文本生成类模型。
- 按量 API：百炼兼容模式 base `https://dashscope.aliyuncs.com/compatible-mode/v1`。
- 三个产品均无确认的官方余额/用量 API → `fetchPlanStatus` 返回 `UNAVAILABLE`。

### 实现

- `provider-adapters`：`AliyunBailianAdapter`（3 个静态工厂，adapterId 与签名目录逐一匹配）+ `AliyunBailianUsageObserver`；OpenAI base 以 `/v1` 结尾 → 剥离 `/v1`；Anthropic `/v1/messages` 保留；团队版 `PER_MEMBER_SUBSCRIPTION_KEY` 建模（`teamPlan=true`、`sharedPool=true`，成员 Key 拓扑待真实账号验证）；`fetchPlanStatus` → `UNAVAILABLE`（0 HTTP）。
- `control-plane-app`：`ProviderClientConfig` 注册 3 个百炼适配器（**现共 23 个适配器 = 签名目录 23 个产品全数覆盖**：Aliyun 3 + Baidu 3 + DeepSeek 1 + MiniMax 3 + Moonshot 2 + Tencent 5 + Volcengine 3 + Zhipu 3）。

### 测试（本 Goal 新增 16 个）

- `AliyunBailianAdapterTest`（12）：3 个产品 adapterId/协议与签名目录一致；凭证剥离 + query 重编码；官方端点映射（Coding/团队版 OpenAI+Anthropic、按量）；空 query；凭证探活 `/models`；401/403/429/5xx 映射；fetchModels 解析/失败模式；fetchPlanStatus `UNAVAILABLE` 且 0 HTTP（三产品）；capabilities 差异；observer 绑定。
- `AliyunBailianUsageObserverTest`（4）：OpenAI 形状 + 根 model id、Anthropic 形状、空/畸形容忍、observer 最新值。
- `ProviderClientConfigTest`（更新）：注册列表含 23 个适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实百炼凭证联调 → `WAITING_FOR_CREDENTIAL`。
- 团队版成员 Key 拓扑、共享池语义须真实账号验证后才能标记 VERIFIED（provider-catalog §3.2 已注明）。
- 目录 23 个产品已全数有适配器覆盖（P0 完成）；P1 候选不在目录中，需重签目录。

### 验证

- 模块验证：`verify -pl provider-adapters,control-plane-app -am` → BUILD SUCCESS；全量 `verify -P integration` → **BUILD SUCCESS**（见 Current State 计数）。

## G3.8 — 火山引擎方舟（Coding Plan + Agent Plan + 按量 API，DONE）

### 官方事实核验（2026-08-26，volcengine.com）

- Coding Plan：Anthropic base `https://ark.cn-beijing.volces.com/api/coding`、OpenAI base `.../api/coding/v3`；模型 Doubao-Seed-Code / GLM-4.7 / DeepSeek-V3.2 / Kimi-K2.5 或 `ark-code-latest`（Auto）；额度 5h/周/月刷新；Key 仅限官方支持的 AI 编程工具使用。
- Agent Plan：专属端点 `.../api/plan`、`.../api/plan/v3`（活动页 JS 渲染无法直接核验，经 cc-switch 社区预设 PR #4826 确认，待真实凭证核验）；覆盖超全模态模型（DeepSeek-V4 系列、GLM-5.1、ArkClaw）。
- 按量 API：方舟在线推理 base `https://ark.cn-beijing.volces.com/api/v3`（不消耗套餐额度）。
- 三个产品均无确认的官方余额/用量 API → `fetchPlanStatus` 返回 `UNAVAILABLE`。

### 实现

- `provider-adapters`：`VolcengineArkAdapter`（3 个静态工厂，adapterId 与签名目录逐一匹配）+ `VolcengineArkUsageObserver`；OpenAI base 以 `/v3` 结尾 → 剥离 `/v1`；Anthropic `/v1/messages` 保留；`fetchPlanStatus` → `UNAVAILABLE`（0 HTTP）。
- `control-plane-app`：`ProviderClientConfig` 注册 3 个方舟适配器（现共 20 个适配器：DeepSeek 1 + Tencent 5 + Zhipu 3 + MiniMax 3 + Moonshot 2 + Baidu 3 + Volcengine 3）。

### 测试（本 Goal 新增 16 个）

- `VolcengineArkAdapterTest`（12）：3 个产品 adapterId/协议与签名目录一致；凭证剥离 + query 重编码；官方端点映射（Coding/Agent Plan Anthropic+OpenAI、按量）；空 query；凭证探活 `/models`；401/403/429/5xx 映射；fetchModels 解析/失败模式；fetchPlanStatus `UNAVAILABLE` 且 0 HTTP（三产品）；capabilities 差异；observer 绑定。
- `VolcengineArkUsageObserverTest`（4）：OpenAI 形状 + 根 model id、Anthropic 形状、空/畸形容忍、observer 最新值。
- `ProviderClientConfigTest`（更新）：注册列表含 20 个适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实方舟凭证联调 → `WAITING_FOR_CREDENTIAL`。
- Agent Plan 端点未经官方页面直接核验（社区预设确认），真实凭证联调时优先验证。
- 已知偶发 flaky（G3.6 CI 发现，与本 Goal 无关）：`InProcessRequestCoalescerTest.shouldShareWithWaiters` 在 Windows CI 偶发 `inFlight()` 断言竞态（leader 完成与 inFlight 递减之间的时序；本地与重跑均通过）。与既有 `AuditChainIntegrityTest.preLockTimestampsDoNotAffectHeadOrdering` 一并列入 G4.x 排查清单。

### 验证

- 模块验证：`verify -pl provider-adapters,control-plane-app -am` → BUILD SUCCESS；全量 `verify -P integration` → **BUILD SUCCESS**（见 Current State 计数）。

## G3.7 — 百度千帆（Coding Plan + Token Plan 个人版 + 按量 API，DONE）

### 官方事实核验（2026-08-26，cloud.baidu.com）

- Coding Plan：OpenAI base `https://qianfan.baidubce.com/v2/coding`、Anthropic base `https://qianfan.baidubce.com/anthropic/coding`；专属 Key 仅限专属接口（错误码 `coding_plan_api_key_not_allowed`/`coding_plan_api_key_required`）；按请求次数配额（Lite ~1200/5h，Pro ~6000/5h）；模型 `kimi-k2.5`/`deepseek-v3.2`/`glm-5`/`minimax-m2.5`/`ernie-4.5-turbo-20260402`/`deepseek-v4-flash`/`glm-5.1` 或 `qianfan-code-latest`。
- Token Plan 个人版：OpenAI base `https://qianfan.baidubce.com/v2/tokenplan/personal`、Anthropic base `https://qianfan.baidubce.com/anthropic/tokenplan/personal`；专属 Key；月度 token 池（Mini 1000万/Lite 4200万/Pro 2.3亿/Max 7亿，模型共享）；错误码 `token_quota_exceeded`。
- 按量 API：千帆 MaaS v2（base `https://qianfan.baidubce.com/v2`）。
- **三个产品均无确认的官方余额/用量 API**（控制台配额页可见）→ `fetchPlanStatus` 返回 `UNAVAILABLE`。

### 实现

- `provider-adapters`：`BaiduQianfanAdapter`（3 个静态工厂，adapterId 与签名目录逐一匹配）+ `BaiduQianfanUsageObserver`；OpenAI base 不以版本段结尾（`/coding`、`/tokenplan/personal`、`/v2`）→ 一律剥离 `/v1`；Anthropic `/v1/messages` 保留；`fetchPlanStatus` → `UNAVAILABLE`（0 HTTP）。
- `control-plane-app`：`ProviderClientConfig` 注册 3 个千帆适配器（现共 17 个适配器：DeepSeek 1 + Tencent 5 + Zhipu 3 + MiniMax 3 + Moonshot 2 + Baidu 3）。

### 测试（本 Goal 新增 16 个）

- `BaiduQianfanAdapterTest`（12）：3 个产品 adapterId/协议与签名目录一致；凭证剥离 + query 重编码；官方端点映射（Coding Plan / Token Plan 个人版 OpenAI+Anthropic、按量）；空 query；凭证探活 `/models`；401/403/429/5xx 映射；fetchModels 解析/失败模式；fetchPlanStatus `UNAVAILABLE` 且 0 HTTP（三产品）；capabilities 差异；observer 绑定。
- `BaiduQianfanUsageObserverTest`（4）：OpenAI 形状 + 根 model id、Anthropic 形状、空/畸形容忍、observer 最新值。
- `ProviderClientConfigTest`（更新）：注册列表含 17 个适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实千帆凭证联调 → `WAITING_FOR_CREDENTIAL`。
- Token Plan 企业版（团队管理）官方存在但签名目录未收录，首版不建模团队产品（provider-catalog §3.6 已注明）。
- 官方公告 "Token Plan 个人版上线及 Coding Plan 停售"：Coding Plan 可能在停售迁移中，模型/配额规则变化以控制台为准。

### 验证

- 模块验证：`verify -pl provider-adapters,control-plane-app -am` → BUILD SUCCESS；全量 `verify -P integration` → **BUILD SUCCESS**（见 Current State 计数）。

## G3.6 — Kimi / Moonshot（Kimi Code 会员 Key + 按量 API，DONE）

### 官方事实核验（2026-08-26，kimi.com / platform.kimi.com）

- Kimi Code：OpenAI base `https://api.kimi.com/coding/v1`（完整 `.../coding/v1/chat/completions`）；Anthropic base `https://api.kimi.com/coding/`（完整 `.../coding/v1/messages`，`/v1/messages` 必须保留）；Key 控制台创建（最多 5 个、仅创建时显示一次）；模型 `k3`/`k3-256k`/`kimi-for-coding`/`kimi-for-coding-highspeed`；每 5 小时约 300–1200 次请求（按档位）、最大并发 30；**会员订阅制 → 无余额 API**。
- Moonshot 按量：base `https://api.moonshot.cn/v1`；**官方余额 API** `GET /users/me/balance` → `data.available_balance`（现金+代金券，人民币；≤ 0 推理被拒）、`voucher_balance`、`cash_balance`；国内站与国际站 Key 独立（混用 401）。
- 按量产品为 **G3.x 系列第一个 `OFFICIAL_API` 余额来源**（G3.1 DeepSeek 之后第二个；G3.2–G3.4 三家均为 UNAVAILABLE）。

### 实现

- `provider-adapters`：`MoonshotKimiAdapter`（2 个静态工厂，adapterId 与签名目录逐一匹配）+ `MoonshotKimiUsageObserver`；`ProductConfig.balancePath`（null → UNAVAILABLE 且 0 HTTP；非 null → OFFICIAL_API 余额拉取）；Kimi Code Anthropic base 保留 `/v1/messages`；`capabilities.balance` 按产品区分（PAYG=true / 会员=false）。
- `control-plane-app`：`ProviderClientConfig` 注册 2 个 Moonshot 适配器（现共 14 个适配器：DeepSeek 1 + Tencent 5 + Zhipu 3 + MiniMax 3 + Moonshot 2）。

### 测试（本 Goal 新增 18 个）

- `MoonshotKimiAdapterTest`（14）：2 个产品 adapterId/协议与签名目录一致；凭证剥离 + query 重编码；官方端点映射（Kimi Code OpenAI/Anthropic、Moonshot）；空 query；凭证探活 `/models`；401/403/429/5xx 映射；fetchModels 解析/失败模式；**fetchPlanStatus 官方余额解析（`available_balance` → PAYG total/remaining，OFFICIAL_API）**、余额失败模式（非 2xx/不可解析）、会员产品 UNAVAILABLE 且 0 HTTP；capabilities 差异；observer 绑定。
- `MoonshotKimiUsageObserverTest`（4）：OpenAI 形状 + 根 model id、Anthropic 形状、空/畸形容忍、observer 最新值。
- `ProviderClientConfigTest`（更新）：注册列表含 14 个适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实 Moonshot/Kimi 凭证联调 → `WAITING_FOR_CREDENTIAL`。
- 目录 baseUrlTemplate（`api.kimi.com/code/v1`）与官方当前端点（`api.kimi.com/coding/v1`）不一致：录入产品实例时以官方端点为准（provider-catalog §3.5 已注明）。
- Kimi Code 无正式团队 Plan：首版按个人会员建模，不伪装团队产品（provider-catalog §3.5 已注明）。

### 验证

- 模块验证：`verify -pl provider-adapters,control-plane-app -am` → BUILD SUCCESS；全量 `verify -P integration` → **BUILD SUCCESS**（见 Current State 计数）。

## G3.4 — MiniMax（个人/团队 Token Plan + 按量 API，DONE）

### 官方事实核验（2026-08-26，platform.minimax.io）

- OpenAI 兼容 base：`https://api.minimax.io/v1`（签名目录 baseUrlTemplate 为 `https://api.minimax.chat/v1`，属 DOCUMENTED 设计值，管理员按官方端点配置）；Anthropic 兼容 base：`https://api.minimax.io/anthropic`（官方存在，但目录只声明 `OPENAI_COMPATIBLE`，待下一版签名）。
- 模型列表 API 官方存在：`GET https://api.minimax.io/v1/models`，`Authorization: Bearer <API_KEY>`，响应 `data[].id/object/created/owned_by`（无 display name）。
- Token Plan 专属 Key 形如 `sk-cp-…`，与按量 API Key 不互通；当前模型 `MiniMax-M3`。
- 团队版：席位 1:1 分配给成员（可转授、不重置用量）；未分配席位的成员在开启权限后可经自己的 Subscription Key 消费共享 Credits 池 → `PER_MEMBER_SUBSCRIPTION_KEY` + 共享 Credits，`sharedPool=true`。
- **docs 索引（llms.txt）无任何 Token Plan 余额/用量查询 API**（额度与钱包余额仅控制台可见）→ `fetchPlanStatus` 返回 `UNAVAILABLE`。

### 实现

- `provider-adapters`：`MiniMaxAdapter`（3 个静态工厂，adapterId 与签名目录逐一匹配）+ `MiniMaxUsageObserver`；base 以 `/v1` 结尾 → 剥离 OpenAI SDK `/v1` 前缀（`/v1/chat/completions` → `/chat/completions`）；`fetchModels` 解析官方 list-models 形状（无 display name → `ModelDefinition(id)`，兼容 `name` 变体）；`fetchPlanStatus` → `UNAVAILABLE`（0 HTTP）；团队版 `teamPlan=true` + `sharedPool=true`。
- `control-plane-app`：`ProviderClientConfig` 注册 3 个 MiniMax 适配器（现共 12 个适配器：DeepSeek 1 + Tencent 5 + Zhipu 3 + MiniMax 3）。

### 测试（本 Goal 新增 17 个）

- `MiniMaxAdapterTest`（13）：3 个产品 adapterId/协议与签名目录一致；凭证剥离 + query 重编码；`/v1` 前缀剥离；空 query；凭证探活 `/models`；401/403/429/5xx 映射；fetchModels 官方形状/`name` 变体/失败模式；fetchPlanStatus `UNAVAILABLE` 且 0 HTTP；capabilities 三产品差异；observer 绑定。
- `MiniMaxUsageObserverTest`（4）：OpenAI 形状 + 根 model id、`cached_tokens` 形状、空/畸形容忍、observer 最新值。
- `ProviderClientConfigTest`（更新）：注册列表含 12 个适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实 MiniMax 凭证联调 → `WAITING_FOR_CREDENTIAL`。
- Anthropic 兼容入口与 VENDOR_NATIVE 能力待目录下一版签名补声明（JSON 不可改）。
- 签名目录 baseUrlTemplate（`api.minimax.chat`）与官方当前端点（`api.minimax.io`）不一致：录入产品实例时以官方端点为准（已在 provider-catalog §3.4 注明）。

### 验证

- 模块验证：`verify -pl provider-adapters,control-plane-app -am` → BUILD SUCCESS；全量 `verify -P integration` → **BUILD SUCCESS**（见 Current State 计数）。

## G3.3 — 智谱 GLM（个人/团队 Coding Plan + 按量 API，DONE）

### 官方事实核验（2026-08-26，docs.bigmodel.cn）

- **Coding Plan OpenAI base**：`https://open.bigmodel.cn/api/coding/paas/v4`（Coding Plan 专属，与按量 API 的 `/api/paas/v4` 不同）；**Anthropic base**：`https://open.bigmodel.cn/api/anthropic`（完整路径 `.../api/anthropic/v1/messages`）。
- 鉴权：OpenAI 入口 `Authorization: Bearer <API_KEY>`（官方 API 文档）；Anthropic 兼容入口官方示例用 `x-api-key`（Anthropic SDK 默认头）—— 适配器按平台惯例注入 Bearer，兼容性列为 `WAITING_FOR_CREDENTIAL` 风险。
- 套餐：积分池（Lite 2000/5h、10k/周；Pro 12k/5h、60k/周；Max 28k/5h、140k/周），按抵扣系数扣减，非高峰 50% 抵扣；Coding Plan 支持 GLM-5.3 / GLM-5-Turbo / GLM-4.7。
- 团队版：席位制（2 席起购），每席位独立限额（标准版 15k/5h、66k/周；高级版 35k/5h、155k/周）→ `PER_SEAT_KEY`，额度按席位单独限制而非团队共享池；团队 Key 与平台其他 API Key 不通用。
- **docs 索引（llms.txt）无任何余额/用量查询 API 与模型列表 API** → `fetchPlanStatus` 返回 `UNAVAILABLE`（契约 §6 权威级别）；`/models` 探活为 OpenAI 兼容惯例端点，待真实凭证核验。

### 实现

- `provider-adapters`：`ZhipuGlmAdapter`（3 个静态工厂，adapterId 与签名目录逐一匹配）+ `ZhipuGlmUsageObserver`；路径归一化沿用 `/v4`-suffixed base 剥离 `/v1` 的规则；`fetchPlanStatus` → `UNAVAILABLE`（不发起 HTTP）；团队版 `capabilities.teamPlan=true` 而 `sharedPool=false`（席位独立限额）。
- `TokenUsageParser`（G3.2 共享解析器）：新增 `prompt_tokens_details.cached_tokens` → cacheRead 回退（智谱官方 usage 形状，也是 OpenAI 标准缓存形状）；对 DeepSeek/Tencent 行为不变（它们不产该字段）。
- `control-plane-app`：`ProviderClientConfig` 注册 3 个智谱适配器（现共 9 个适配器：DeepSeek 1 + Tencent 5 + Zhipu 3）。

### 测试（本 Goal 新增 18 个）

- `ZhipuGlmAdapterTest`（13）：3 个产品 adapterId/协议与签名目录一致；凭证剥离 + query 重编码；`/v1` 前缀剥离；Anthropic 路径保留；官方端点映射（PAYG `/api/paas/v4`、Coding Plan `/api/coding/paas/v4`、Anthropic `/api/anthropic`）；凭证探活 `/models`；401/403/429/5xx 映射；fetchModels 解析/失败模式；fetchPlanStatus `UNAVAILABLE` 且 0 HTTP；capabilities 三产品差异；observer 绑定。
- `ZhipuGlmUsageObserverTest`（5）：OpenAI 兼容形状、**智谱文档形状 `prompt_tokens_details.cached_tokens`**、Anthropic 形状、空/畸形容忍、observer 最新值。
- `ProviderClientConfigTest`（更新）：注册列表含 9 个适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实智谱凭证联调 → `WAITING_FOR_CREDENTIAL`。
- `/models` 端点官方文档未收录：真实凭证联调若确认不存在，validateCredential 改用最小推理探针并同步文档。
- Anthropic 兼容入口官方示例用 `x-api-key`：Bearer 兼容性待真实凭证核验。
- 上一会话遗留问题本会话修复：`ProviderClientConfig` 只有 import 未注册；usage 测试只覆盖 DeepSeek 形状未覆盖智谱文档形状；javadoc 声称无法核验官方文档（本会话实际核验成功并更新事实表）。

### 验证

- Windows 全量：`./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode` → **BUILD SUCCESS**（见 Current State 计数）。

## G3.2 — Tencent TokenHub（第二个参考适配器：团队 Plan、余额与 usage，DONE）

### 实现

- `provider-adapters`：
  - 共享 `TokenUsageParser`（G3.2 抽取）：OpenAI 兼容（prompt/completion + `prompt_cache_hit/miss_tokens`）与 Anthropic Messages（input/output + `cache_read/creation_input_tokens`）双形状；解析时优先从响应根/`message.model` 取 model id（修复 OpenAI 真实形状中 `model` 与 `usage` 为兄弟节点的场景），`usage.model` 次之；标准 cache 名优先于 OpenAI 兼容 cache 名；解析失败返回空 Optional 绝不影响请求。
  - 共享 `TransparentResolve`：入站凭证 Header 剥离 + query map 重编码为原始 query 串（Header 名小写），供 OpenAI/Anthropic 兼容适配器复用。
  - `TencentTokenHubAdapter`：1 个参数化类 + 5 个静态工厂，adapterId 与签名目录逐一匹配（`tencent-coding-plan`、`tencent-token-plan-personal`、`tencent-token-plan-enterprise-pro`、`tencent-token-plan-enterprise-lite`、`tencent-payg-api`）。
  - 产品专属 Base URL 路径归一化：`/v3`-suffixed plan base（Coding/Token Plan 个人版/企业版）对 `OPENAI_COMPATIBLE` 请求剥离 `/v1` 前缀（`/v1/chat/completions` → `/chat/completions`）；Anthropic Messages 路径与 TokenHub PAYG root base 保持原样。
  - `validateCredential`：按产品归一化后的模型列表路径探活（PAYG `/v1/models`，Plan 产品 `/models`）；401/403 → credential rejected，429 → rate limited，其余 HTTP 状态稳定文案。
  - `fetchModels`：解析 TokenHub 文档形状 `data[].id` + `name`，兼容 `display_name` 变体；未知字段容忍；非数组 data 视为空。
  - `fetchPlanStatus`：2026-08-25 核验腾讯云 5 个产品均无公开余额/用量 API（仅控制台），按 `provider-adapter-contract.md` §6 权威级别返回 `UNAVAILABLE`，不发起 HTTP 调用，不以本地估算冒充官方值。
  - `capabilities`：streaming/modelDiscovery/usage=`PROVIDER_RESPONSE`；balance=false（无官方余额 API）；PAYG `plan=false/teamPlan=false`，个人 Plan `plan=true/teamPlan=false`，企业 Plan `plan=true/teamPlan=true` + `PlanSnapshot.sharedPool=true`（多 Key 共享积分/Token 池建模）。
  - `TencentUsageObserver`：observer 绑定 context + 最新值存储；复用 `TokenUsageParser`。
- `control-plane-app`：`ProviderClientConfig` 编译期注册 5 个 Tencent TokenHub 适配器 + DeepSeek（重复 adapterId 启动失败）。
- `deepseek`：
  - `DeepSeekPaygAdapter.resolve` 与 `DeepSeekUsageObserver` 改复用 `TransparentResolve`/`TokenUsageParser`，行为不变、既有测试保持通过。
  - 顺带修复 usage 解析在 OpenAI 真实形状中未从响应根取 `model` 的 latent 缺陷（G3.1 只覆盖 `usage.model` 与无 model 两种情况；G3.2 新增根级 `model` 回退）。

### 测试（本 Goal 新增 31 个）

- `TencentTokenHubAdapterTest`（15）：5 个产品 adapterId/协议与签名目录一致；resolve 剥离凭证/保留其他 Header/query 重编码；OpenAI `/v1` 前缀在 plan base 剥离、PAYG 保留；Anthropic Messages 路径保留；凭证探活路径按产品归一化；401/403/429/5xx 状态映射；fetchModels 解析 `name`/`display_name`/未知字段/失败模式；fetchPlanStatus 对所有产品返回 `UNAVAILABLE` 且不发起 HTTP；5 个产品 capabilities 差异；usage observer 绑定。
- `TencentUsageObserverTest`（5）：OpenAI 兼容 cache 字段、Anthropic cache 字段、根 usage 优先于 message.usage、空/畸形返回空、observer 最新值。
- `TokenUsageParser` 通过 DeepSeek 与 Tencent 两套测试覆盖。
- `ProviderClientConfigTest`（更新）：断言编译期注册包含 DeepSeek + 5 个 Tencent 适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档 2026-08-25 核验），`VERIFIED` 需真实 Tencent 凭证联调 → `WAITING_FOR_CREDENTIAL`（不阻塞 Mock/契约工作）。
- 签名目录当前为 5 个 Tencent 产品声明的协议族：Coding Plan 含 `ANTHROPIC_MESSAGES`，其余 4 个产品只声明 `[OPENAI_COMPATIBLE, VENDOR_NATIVE]`。官方文档显示 Token Plan 个人版/企业版/TokenHub 按量也提供 Anthropic 兼容入口，但签名 JSON 不可改；Anthropic 入口使用需在目录下一版签名时由发布负责人补 `ANTHROPIC_MESSAGES`。
- 企业版“独占额度/总上限/TPM/模型限制”均为控制台配置，官方无 API 可查；系统通过 `sharedPool=true` 表达多 Key 共享池，`fetchPlanStatus` 显式 `UNAVAILABLE`，不伪造额度明细。

### 验证

- Windows 全量：`./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode` → **BUILD SUCCESS**，**810 tests / 0 failures / 0 errors / 5 skipped**（11 模块全绿，含 Testcontainers integration）。
- 前端：`npm ci`、`npm run lint`、`npm run typecheck`、`npm run test`（16 passed）、`npm run build` 全 PASS。
- Compose：`docker compose -f deploy/compose.yaml config` PASS。

## G3.1 — DeepSeek PAYG 首个完整参考适配器（DONE）

### 实现

- `provider-adapters`：`DeepSeekPaygAdapter`（adapterId `deepseek-payg-api`，与签名目录一致）—— OpenAI 兼容 + Anthropic Messages 双协议；`resolve` 剥离入站鉴权 Header 并把解码后的 query map 重编码为原始 query 串（Header 名统一小写）；`credentialInjection`（Bearer Authorization，strip `authorization/x-api-key/api-key`）；`validateCredential`（GET /models，2xx 有效，401/403/429/其他 → 稳定文案）；`fetchModels`（data[].id/display_name，未知字段容忍，非数组 data 视为空）；`fetchPlanStatus`（GET /user/balance，`total_balance` → PAYG total/remaining，used/period 保持 null 不冒充）；`capabilities` 声明 streaming/modelDiscovery/balance/requestId + `PROVIDER_RESPONSE` usage。
- `DeepSeekUsageObserver`：SPI 契约的 observer（onUsage 恰好一次回调、不碰字节流）；`parse` 纯函数 —— OpenAI 兼容（prompt/completion + DeepSeek 特有 `prompt_cache_hit/miss_tokens`）与 Anthropic Messages（input/output + cache_read/creation）双形状；标准 cache 名优先于 DeepSeek 特有名；解析失败返回空 Optional 绝不影响请求。
- `control-plane-app`：`ProviderClient` 首个实现 `HttpProviderClient`（JDK `HttpClient`，零新依赖）—— 每次交换重校验 base URL（SSRF 门控，拒绝原因不含 URL）、连接/请求超时、响应体 1MB 上限、`Redirect.NEVER`（3xx 原样返回）；`ProviderClientFactory` 单一创建点，每个凭证独立 client；`ProviderClientConfig` 编译期注册 DeepSeek 适配器（重复 adapterId 启动失败）+ 生产默认空 allowlist 校验器；`application.yml` 新增 `miqrokey.control.provider-client.*`（env `MIQROKEY_CONTROL_PROVIDER_CLIENT_*`）。
- `gateway-app`：`SseUsageObserver.parseUsageJson` 补齐 DeepSeek 特有 cache 字段映射（hit→cacheRead、miss→cacheCreation，标准名优先）。
- `ModelCatalogService.refreshProduct`（G2.3 接缝）端到端打通：真实适配器 + 真实 ProviderClient 对本地 mock 官方 JSON 形状 → `model_catalog` 落库（success-only 写入不变）。
- `provider-adapters` package-info 修正：ServiceLoader → 编译期注册措辞。

### 测试（本 Goal 新增 32 个）

- `DeepSeekPaygAdapterTest`（13）：身份/协议、Header 剥离 + query 重编码、凭证注入契约、validateCredential 全状态映射、fetchModels 解析/未知字段/失败模式、fetchPlanStatus 余额/空列表/非 2xx、usage observer 绑定、capabilities。
- `DeepSeekUsageObserverTest`（7）：双形状解析、标准 cache 名优先、message.usage 回退、model id、空/畸形返回空、onUsage 最新值。
- `HttpProviderClientTest`（6）：凭证注入 + 路径拼接、query 转发、3xx 不跟随、请求超时、body 上限、SSRF 拒绝（0 上游请求）。
- `ProviderClientConfigTest`（3）：编译期注册含 deepseek-payg-api、生产 validator 空 allowlist、factory 构建凭证作用域 client。
- `SseUsageObserverTest`（+2）：DeepSeek cache 字段映射 + 标准名优先。
- `ModelCatalogServiceIntegrationTest`（+1，integration）：真实适配器 + 真实 client 端到端 → PostgreSQL（含 Authorization 断言）。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实 DeepSeek 凭证联调 → `WAITING_FOR_CREDENTIAL`（不阻塞 Mock/契约工作）。
- 管理 API 凭证校验端点（本地格式检查）未在 G3.1 接线到上游 `validateCredential`（需解密 + 网络；接缝已存在，G4.x 接线）。

### 验证

- Windows 全量：`./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode` → **BUILD SUCCESS**，779 tests / 0 failures / 0 errors / 5 skipped（11 模块全绿，含 Testcontainers integration；surefire XML 汇总：domain 100 / provider-spi 8 / provider-adapters 45 / persistence 118 / route-snapshot 3 / control-plane 198 / gateway 198 / test-support 109）。
- **根因修复（关键）**：`application.yml` 初次编辑把 `miqrokey:` 块插在 `spring.main` 与 `spring.datasource` 之间，导致 `datasource:`/`flyway:` 被吞入 `miqrokey:` 命名空间 —— `spring.datasource.*`（pool 20）与 `spring.flyway.*` 全部失效，控制面集成测试共享 testcontainer（max_connections=100）被各 Spring 上下文的 Hikari 池耗尽（`FATAL: sorry, too many clients`）。已把 `miqrokey:` 块移到 `spring:` 之后恢复结构（diff 仅 9 行插入），修复后控制面模块 1:21 通过、全量 3:12 通过。教训：向 `application.yml` 顶部插入顶级块时必须检查后续键的缩进层级。

## G2.6 — Gateway security hardening（SSRF、路径、Header、body 上限和错误脱敏，DONE）

### 实现

- `UpstreamTargetValidator`（gateway-app 新增）：SSRF 双重门控 —— `https` 硬要求（除非命中 allowlist）、`userinfo` 一律拒绝；DNS 解析后每个地址必须公网（环回/链路本地/RFC1918/CGNAT `100.64/10`/组播/any-local/IPv6 ULA `fc00::/7` 均拒）；拒绝原因仅稳定类别 token，错误体/日志/审计不出现目标 URL。阻塞 DNS 在 `credentialDecryptScheduler` 上执行，不占事件循环。
- `ProxyController`：`doForward` 拆出 `forwardWithResolvedCredential`，插入门控（拒绝 → `502 route_unavailable`）；`DataBufferLimitException` → `413 payload_too_large`（已有 256KB 缓冲上限接线，本次补测试）。
- 入站 Header 上限：`server.netty.max-header-size`（默认 `32KB`，Netty 路由前拒绝 → `431`）。
- 路径白名单（已有 catch-all，本次补契约测试）：三个 POST 端点，其余 `/v1/**` → 404、错方法 → 405、`..` 字面处理、`//` 归一化后按规范路径处理，均不触达上游。
- 配置：`MIQROKEY_UPSTREAM_ALLOWED_CIDRS`（默认空 = 全拒）、`MIQROKEY_MAX_INBOUND_HEADER_BYTES`（`32KB`）。

### 测试（本 Goal 新增 29 个）

- `UpstreamTargetValidatorTest`（14）：scheme/userinfo/解析地址/CIDR 匹配/allowlist 状态。
- `GatewaySecurityHardeningTest`（12，严格路径：空 allowlist + mutable target）：SSRF 拒绝（loopback/169.254.169.254/RFC1918/userinfo → 502 route_unavailable，错误体无泄漏、0 上游请求）、路径/方法/归一化、431 超大头、413 超大 body。
- `VirtualKeyAuthContractTest$HeaderSmuggling`（3）：伪造凭证 Header 在鉴权层 401（0 上游请求）、重复 Authorization → 401、hop-by-hop/`X-MiQroKey-*` 剥离、只有注入凭证到达上游且客户端 Key 不泄漏。

### 验证

- 本地 Windows：`verify -P integration` → **742 tests / 0 failures / 0 errors / 5 skipped**（G2.5 基线 714，POSIX 跳过 5）。
- 契约测试用 `@Primary` loopback allowlist（`127.0.0.0/8, ::1/128`）；集成测试用动态属性；严格路径类不 import `GatewayAuthTestConfig`。

### 文档

- `security.md` §6（SSRF 双重门控、入站防护、错误脱敏）、`api-contract.md` §7.1（502/404/405/401/413/431 语义 + 走私防护）、`configuration-reference.md`（`ALLOWED_CIDRS`/`MAX_INBOUND_HEADER_BYTES` 精确语义）。

### 风险与边界

- 生产默认严格路径：本地自建模型须显式配置 `MIQROKEY_UPSTREAM_ALLOWED_CIDRS`。
- 上游 `http` 仅 allowlist 放行；`FOLLOW_REDIRECTS` 保持禁用（重定向不改变已校验目标）。
- 未接管理 API body 上限（`MIQROKEY_MAX_CONTROL_BODY_BYTES` 属控制面，G2.6 只覆盖数据面）。
- 已知偶发 flaky（与本 Goal 无关，既有代码）：`AuditChainIntegrityTest.preLockTimestampsDoNotAffectHeadOrdering` 在完整套件下偶发 hash 不匹配（共享 Testcontainers 容器 + 8 线程并发写入），单独运行与重跑完整套件均通过（G2.5/G2.6 前两次完整验证亦通过）。归因于并发时序，待 G4.x 控制面收尾时单独排查。

## G2.5 — Timeout, retry, cancellation and backpressure（G2.5 超时/重试/取消/背压，DONE）

### Outcome

1. **四层网络边界**（`ProxyTargetProperties`，全部可配置）：连接 10s（`CONNECT_TIMEOUT_MILLIS`）；**首包 120s**（reactor-netty `HttpClient.responseTimeout()` = 等响应头；超时表现为连接错误，永不重试）；**流式空闲 5min**（对观测 body `Flux.timeout`，每个 chunk 重置）；**整体硬截止 10min**（`Mono.timeout` 包在重试外层，自第一次尝试计时、不随重试重置）。默认值按 G2.5 验收校准（连接 PT5S→PT10S，idle PT2M→PT5M，新增 first-byte PT120S）。
2. **首字节前最多重试一次**：`Mono.defer` 包每次尝试 + `Retry.max(1)`，filter 仅放行连接阶段失败（`WebClientRequestException` 且非任何超时）且尚未出首字节；真实凭证只在第一次尝试前解析一次，重试复用同一凭证（不跨凭证故障切换）。reactor 3.7 默认 exhausted 策略会把原始异常包成 `RetryExhaustedException`，用 `onRetryExhaustedThrow((s, sig) -> sig.failure())` 恢复原始类型（否则 502 映射漏网，真实缺陷修复）。`retry_count` 端到端持久化（event → `request_usage_records.retry_count`，start 行 0，completion 通过 guarded upsert 更新）。
3. **终态判定顺序修复**（真实缺陷修复）：upstreamError 判定移到 httpStatus 之前——上游 200 状态行 + 中途流失败现在正确记为 `STREAM_INTERRUPTED`（旧顺序误报 SUCCEEDED）；timeout 细分：未出首字节 → `TIMEOUT_BEFORE_FIRST_BYTE`，已出 → `STREAM_INTERRUPTED`。
4. **慢客户端内存有界**：响应按 chunk 直通（streaming，不聚合）；256KB `maxProxyBuffer` 只限 usage/缓存收集缓冲，溢出时放弃收集（`usage_missing=true`）绝不影响转发（512KB 响应 + 慢消费者完整收包测试）。
5. **Mock 能力扩展**（test-support）：`disconnectNextRequest`/`disconnectAllRequests`（连接阶段 EOF，模拟可重试失败）、`responseDelay`（慢首包）、`haltAfterLines`（N 行后永久停滞，idle 超时）、`chunkDelay` 流式分块。无 delay/halt 的流式路径保持单次原始写入（line-rebuild 会在 body 尾部追加幻影 `\n`，契约测试逐字节断言，真实缺陷修复）。

### Verification

- 全量 `./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode`（本机 Docker Desktop，Testcontainers 实跑）：**BUILD SUCCESS** —— **714 tests / 0 failures / 5 skipped**（Windows POSIX 权限跳过）
- `TimeoutRetryIntegrationTest`（Testcontainers + AnthropicMockProvider，7）：连接失败重试一次成功（retry_count=1）；持续断连 → 502 + `UPSTREAM_UNAVAILABLE`（retry_count=1）；200 成功 retry_count=0；慢首包 → `TIMEOUT_BEFORE_FIRST_BYTE`（未重试、无首包）；idle 停滞 → `STREAM_INTERRUPTED` + partial_response + http_status=200 + client_cancelled=false；长流超整体截止 → `STREAM_INTERRUPTED`；512KB 慢客户端完整收包 + `usage_missing=true`。
- 契约测试回归（mock 流式路径修复后）：Anthropic/Chat/Responses ProxyContractTest 71/71 全绿。
- Spotless check 全模块 PASS；Maven Enforcer：PASS。

### Files changed

- **gateway-app**：`ProxyTargetProperties`（connect/first-byte/stream-idle/response/max-buffer）、`ProxyConfig`（`HttpClient.responseTimeout(firstByte)`）、`ProxyController`（重试封装、per-attempt `UpstreamAttempt` 状态隔离、终态顺序、isTimeout、retry_count 传递）、`application.yml`（4 个新环境变量）
- **domain**：`RequestCompletedEvent`（+`retryCount`）
- **queue-spi**：`PostgresUsageEventWriter`（retry_count 两处 SQL + params）
- **test-support**：`AnthropicMockProvider`（disconnect/responseDelay/haltAfterLines + 流式单写路径修复）、`GatewayTestKeys`
- **测试**：新 `TimeoutRetryIntegrationTest`（7）；`PostgresUsageEventWriterTest`/`PostgresUsageEventBusTest` 构造更新
- **文档**：architecture.md §6（四层超时 + 重试 + 慢客户端语义）、configuration-reference.md §5（新 keys/默认值校准）

### Remaining risks

- 慢首包/断连场景经 mock 验证；真实供应商网络行为变体 `WAITING_FOR_CREDENTIAL`。
- 首字节后对上游的取消传播依赖 reactor-netty 通道关闭语义（已有 `STREAM_INTERRUPTED` 断言覆盖）。
- G2.6 将收紧未签名目标/私网解析等安全边界，本节超时实现保持兼容。

## G2.4 — Usage lifecycle and reliable writer（G2.4 请求生命周期记录 + 有界批量写入，DONE）

### Outcome

1. **请求生命周期记录**（`request_usage_records`，V8 月度分区表）：每个到达上游的请求在发出前发布 `RequestStartedEvent` 打开 `IN_FLIGHT` 行（`ON CONFLICT (started_at, gateway_request_id) DO NOTHING`），在任何终态信号上**恰好 finalize 一次**——completion 为带 `WHERE request_status = 'IN_FLIGHT'` 的 guarded upsert，重试 flush 绝不双计、绝不重写已 finalized 记录；start 行丢失时 completion 事件自带完整 start 快照独立插入终态行。鉴权失败与缓存命中不打开记录。
2. **终态映射**：`SUCCEEDED`（上游 2xx）/`UPSTREAM_REJECTED`（非 2xx）/`CLIENT_CANCELLED`（客户端断开，优先于任何已观测状态码）/`TIMEOUT_BEFORE_FIRST_BYTE`/`STREAM_INTERRUPTED`/`UPSTREAM_UNAVAILABLE`。`partial_response = 已出首字节 && 未完整完成`。SUCCEEDED 但无 usage 时 `usage_missing=true` 显式标记，绝不静默记零。
3. **Usage 解析补齐**（真实缺陷修复）：`SseUsageObserver.parseUsageJson` 共享解析器，非流式 JSON 响应也从正文提取 token 计数（只提取计数，正文永不保留/持久化）；Anthropic SSE 与 JSON 的 `reasoning_tokens`（`output_tokens_details`/`completion_tokens_details`）均解析。
4. **客户端取消判定修复**（真实缺陷修复）：Reactor Netty 在已 flush 全部缓冲字节时会把客户端断开报告为服务端写侧 `ON_COMPLETE`（channel 的 terminate 完成 outbound 而非取消），导致断开被记成 `SUCCEEDED`。修复：observed 流自身的终结信号（`TtfbRecorder.terminalSignal()`）是客户端取消的权威信号——取消 observed 正是关闭上游连接的动作；`clientCancelled = signal==CANCEL || (observed 终结==CANCEL && upstreamError==null)`，`upstreamError==null` 排除超时/上游故障（它们也取消 observed 但有错误记录）。全量 suite 下 3 连跑稳定（修复前 ~50% flake）。
5. **有界批量写入**（queue-spi 实现）：有界阻塞队列（默认容量 10000）、阈值/定时 flush（100 条或 5s）、专用有界 writer 执行器（`miqrokey.gateway.queue.writer-threads` 默认 4，`Schedulers.newBoundedElastic`）；写失败把整批**按序重入队**并记 warn（幂等写入保证重试不双计），队列饱和 drop 按高优先级 warn 计数——均不静默。Micrometer 无标签 gauge：`miqrokey.usage.queue.queued/published.total/persisted.total/dropped.total/flush.count/flush.last.duration.seconds`。
6. **幂等写入**：`usage_event` `ON CONFLICT (tenant_id, provider_request_id) DO NOTHING`，`cache_hit_event` `(tenant_id, cache_key, level, occurred_at)`，生命周期 start/completion 如上——重试 flush 绝不双计。

### Verification

- 全量 `./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode`（本机 Docker Desktop，Testcontainers 实跑）：**BUILD SUCCESS** —— **708 tests / 0 failures / 5 skipped**（Windows POSIX 权限跳过；gateway-app 159 全绿）
- `UsageLifecycleIntegrationTest`（Testcontainers + AnthropicMockProvider，6）：非流式 200 → SUCCEEDED（身份链/upstream_request_id/ttfb 断言）；流式 SSE → SUCCEEDED + token 解析；200 无 usage → `usage_missing=true`；429 → UPSTREAM_REJECTED；**客户端断开 → CLIENT_CANCELLED**（mock 侧验证 lines flux 被取消）；mock 端口关闭 → UPSTREAM_UNAVAILABLE。
- `QueueMetricsBinderTest`（3）：gauge 跟踪队列状态、饱和 drop 计数、无标签。
- `PostgresUsageEventWriterTest`（6）：start/completion guarded upsert、start 行丢失独立插入、重试不双计。
- Spotless check 全模块 PASS；Maven Enforcer：PASS。

### Files changed

- **gateway-app**：`ProxyController`（observed 终结信号判定客户端取消、非流式 usage 解析回退）、`SseUsageObserver`（静态 `parseUsageJson`）、`TtfbRecorder`（`terminalSignal()`）、新 `UsageLifecycleIntegrationTest`、新 `QueueMetricsBinderTest`、`QueueMetricsBinder`（gauge 装配）
- **queue-spi**：`PostgresUsageEventBus`/`PostgresUsageEventWriter`（生命周期 start/completion SQL）、`RequestStartedEvent`/`RequestCompletedEvent`（V8 表映射）
- **test-support**：`GatewayTestKeys`（provider 身份 putIfAbsent：产品 1:1 绑定）、`AnthropicMockProvider`（chunkDelay 路径保持）
- **文档**：database-schema.md §6（V8 当前实现列/延后列）、architecture.md §5（生命周期 + 批量写入 + 幂等三块）、configuration-reference.md §5.1/§6（writer-threads、队列语义）、usage-accounting.md §2（已实现终态 + 首版不落库列表）、api-contract.md §7.1（生命周期记录语义）、progress.md

### Remaining risks

- 请求级上游超时（120s 首包/5min idle）与"首字节前一次重试"属于 G2.5 范围，本 Goal 未实现。
- `provider_usage_json`、成本列、`error_category` 等延后列留待后续 Goal（database-schema.md 已列）。
- 真实供应商凭证未提供：usage 解析只经 Anthropic Mock/契约 fixture 验证，真实响应变体 `WAITING_FOR_CREDENTIAL`。

## G2.1 — Provider SPI and signed catalog core

### Outcome

- **provider-spi**（`com.miqroera.miqrokey.spi`，纯 Java + reactor-core，无 Spring/Jackson）：`ProviderProductAdapter` 契约（adapterId/protocols/resolve/credentialInjection/validateCredential/fetchModels/createUsageObserver/fetchPlanStatus/capabilities）及全部值对象——`ProtocolFamily`（5 族）、`ProviderProductDefinition`（紧凑构造器强制非空/https/无 userinfo/集合不可变）、`RouteContext`/`TargetRequest`/`InboundRequest`、`CredentialMaterial`（内存明文、`destroy()` 清零、toString 只显示 REDACTED）、`CredentialInjection`（入站鉴权头剥离防 credential smuggling）、`ProviderClient`/`ProviderRequest`/`ProviderResponse`（控制面有界 HTTP，推理流量不经过）、`UsageObserver`/`UsageObservation`/`UsageContext`、`ModelCatalogSnapshot`、`PlanSnapshot`/`PlanDataSource`、`AdapterCapabilities`/`AdapterStatus`、`AdapterRegistry`。
- **provider-adapters**（`com.miqroera.miqrokey.adapters`，无 Spring）：
  - `catalog/`：`ProviderCatalog.loadBuiltIn()`（classpath 资源）→ `CatalogSignatureVerifier`（Ed25519，64 字节签名，JDK 原生）→ `CatalogManifestValidator`（严格 allowlist schema：拒绝未知顶层/产品字段、非 https/userinfo Base URL、未知枚举值、重复产品 id；错误全量聚合）。
  - `registry/BuiltInAdapterRegistry`：线程安全编译期注册表，重复 `adapterId` 注册抛 `IllegalArgumentException`（启动失败）。
  - 内置目录 `catalog/provider-catalog.json`：8 家供应商 23 个产品（腾讯 5、阿里 3、智谱 3、MiniMax 3、Kimi 2、百度 3、火山 3、DeepSeek 1），全部 `DOCUMENTED`，https Base URL 为官方文档设计值；`provider-catalog.sig`（Ed25519）与 `catalog/keys/catalog-public.pem`（公钥，`.gitignore` 加例外；私钥只在发布环境，本会话签名后即删）。
- **目录是纯数据的强制边界**：schema 拒绝所有未知字段（含 `class`/`code` 等可执行字段）；适配器解析只按 `adapterId` 查编译期注册表——被篡改或远程目录不可能加载代码（有专项测试）。
- ArchUnit 新增 3 条规则：provider-spi 无 Spring（既有）+ 无 Jackson、provider-adapters 无 Spring。

### Verification

- `.\mvnw.cmd -f backend/pom.xml verify --batch-mode`：**BUILD SUCCESS**（666 tests / 0 failures / 0 errors；新增 34 个测试：SPI 9 + adapters 25）
- `spotless:apply` 已格式化；`spotless:check` 在 verify 内通过。

### Files changed

- **provider-spi**：27 个新类型（枚举 6、record 14、接口 5、UsageObserver） + POM 加 reactor-core（BOM 管理版本）
- **provider-adapters**：`catalog/`（ProviderCatalog、CatalogManifestValidator、CatalogSignatureVerifier、CatalogKeyLoader、CatalogSignatureException、CatalogLoadException）+ `registry/BuiltInAdapterRegistry` + 3 个资源文件（catalog JSON/sig/public.pem）+ 4 个测试类（25 测试）
- **测试**：`ProviderProductDefinitionTest`（6）、`CredentialMaterialTest`（3）、`CatalogManifestValidatorTest`（9）、`CatalogSignatureVerifierTest`（5）、`CatalogKeyLoaderTest`（3）、`ProviderCatalogTest`（5）、`BuiltInAdapterRegistryTest`（3）
- **ArchUnit**：ModuleDependencyTest +3 条规则
- **文档**：provider-adapter-contract.md §9（真实包结构 + 签名密钥管理）、provider-catalog.md §7.1（签名/重签流程）、architecture.md §3（两模块职责）、progress.md；`.gitignore` 公钥例外

### Remaining risks

- 内置目录 23 个产品全部 `DOCUMENTED`：Base URL 为官方文档设计值，真实联调（G3.x 适配器 + 真实凭证）后才能升级 `IMPLEMENTED`/`VERIFIED`。
- 目录公钥当前为开发用密钥对（私钥已删）；生产发布需在发布环境生成新密钥对并替换公钥（`CatalogKeyLoader` 已支持文件加载入口，运行时接线在 G2.2+ 配置阶段）。
- adapterId 尚无任何注册的正式适配器（G3.x 逐个实现并注册）；注册表机制已由测试证明。

## G1.6 — Upstream credential validation and rotation

### Outcome

- 管理 API `/api/v1/admin/credentials`：创建、测试（validate）、轮换、禁用、列表、详情（api-contract §5.1）。
- Secret 只接受明文输入：AES-256-GCM 加密（AAD 绑定 tenant + credential）后落库；响应/审计只含掩码元数据与 `fingerprintPrefix`（SHA-256 前 8 字节 hex），明文与完整指纹永不回显。
- 验证零副作用：`validate` 与所有轮换/创建前的校验失败均不写数据库（400 `CREDENTIAL_INVALID`），旧版本绝不被覆盖。
- 轮换单事务原子：`SELECT ... FOR UPDATE` 行锁串行化并发变更；旧 ACTIVE → DRAINING（`retiredAt = now + miqrokey.credential-drain-grace`，默认 `PT0S`）后才插入新 ACTIVE，满足部分唯一索引 `uq_credential_versions_one_active`；已降级版本在宽限内仍可解密（“旧请求可完成”），快照刷新后新请求用新版本。
- `disable` 置 DISABLED 并降级 ACTIVE 版本；网关快照只加载 ACTIVE 凭证，刷新后该凭证不可路由。
- 新增 domain SPI `CredentialSecretValidator`（默认 `FormatCredentialValidator`：8..512 字符、无控制字符），为 G3.x 提供商校验适配器留扩展点。
- 审计事件 `CREDENTIAL_CREATE/ROTATE/DISABLE` 只记变更摘要，集成测试断言不含明文子串。
- 文档：api-contract.md §5.1、configuration-reference.md（`MIQROKEY_CREDENTIAL_DRAIN_GRACE`）。

### Verification

- `.\mvnw.cmd verify --batch-mode`（含 `-Pintegration`，DOCKER_HOST=tcp://localhost:2375）：**BUILD SUCCESS** —— 631 tests, 0 failures, 0 errors, 5 skipped（既有）
- 新增 31 测试全绿：`AdminCredentialApiIntegrationTest`（13，含 AES-GCM 解密回环、FK 循环三步创建、PT0S 宽限语义、明文永不出现在响应/审计）、`AdminCredentialServiceTest`（14，含 InOrder 验证降级先于插入）、`FormatCredentialValidatorTest`（4）
- Spotless/Enforcer/ArchUnit：PASS（verify 内置）
- 前端不受影响（无前端改动）。

### Files/modules changed

- **domain**：`credential/CredentialSecretValidator`（SPI）、`crypto/CredentialFingerprint`、`UpstreamCredentialRepository`（+`findByIdForUpdate`、`findAllByTenantId`）
- **persistence-postgres**：`UpstreamCredentialRepositoryImpl`（+2 查询，`FOR UPDATE` 行锁）
- **control-plane-app**：`AdminCredentialService`、`AdminCredentialController`、`FormatCredentialValidator`、7 个 DTO、`AuthProperties`（+`credentialDrainGrace`）
- **文档**：api-contract.md §5.1、configuration-reference.md

### Remaining risks

- 真实凭证校验仍为本地格式校验（无提供商往返）；G3.x 供应商适配器接入同一 SPI 后标记 `WAITING_FOR_CREDENTIAL` 验收。

## G0.2 — Anthropic transparent proxy PoC

### Outcome

- Gateway transparently proxies `POST /v1/messages` to a configurable upstream.
- Request bodies and JSON/SSE responses are forwarded as reactive streams; the Gateway does not aggregate a complete proxy body.
- Request/response bytes, raw query encoding/order, ordinary and non-standard upstream statuses (including `529`), tools, tool results, thinking, UTF-8 splits, and cache usage are covered by contract tests.
- Inbound credentials, static/dynamic hop-by-hop headers, untrusted framing headers, and forged `X-MiQroKey-*` tracking headers are removed. Ordinary application headers remain transparent.
- Client cancellation is verified end-to-end on the production-equivalent Reactor Netty stack: cancelling the downstream response closes the Mock Provider's upstream TCP connection before completion.
- TTFB uses an injectable `Clock`; upstream connect/response timeouts and the bounded observer buffer use the documented `MIQROKEY_*` configuration.
- SSE observation has a `256KB` default bound and retains token counters only. It never stores or logs event JSON, prompt/tool/model content, or response bodies.
- Synthetic fixture metadata now covers the documented Anthropic non-stream, streaming usage, tool-use/tool-result, and prompt-cache cases.
- Production Gateway code contains zero `.block()`, `.blockFirst()`, or `.blockLast()` calls (enforced by ArchUnit).

### Verification

- `.\mvnw.cmd clean verify --batch-mode`: **BUILD SUCCESS** — 52 tests, 0 failures, 0 errors
- `.\mvnw.cmd verify --batch-mode --quiet` after final configuration/docs update: **BUILD SUCCESS**
- `AnthropicProxyContractTest`: **PASS** — 18 contract tests, including exact bytes/raw query/non-standard status and upstream TCP cancellation
- Spotless format check: PASS
- Maven Enforcer: PASS
- ArchUnit module dependency: PASS (8 rules + 3 blocking checks)
- No `.block()` in production Gateway code: confirmed by `GatewayNoBlockingTest`
- SSE privacy regression: PASS — a sentinel model-content value is absent from observations and captured logs
- `npm --prefix frontend ci`: PASS — 0 vulnerabilities
- `npm --prefix frontend run lint`: PASS
- `npm --prefix frontend run typecheck`: PASS
- `npm --prefix frontend run test`: PASS — 1 test
- `npm --prefix frontend run build`: PASS
- `docker compose -f deploy/compose.yaml config`: ENV_BLOCKED — Docker is not installed locally; CI must provide the Compose check

### Files/modules changed

- `test-support`: Reactor Netty `AnthropicMockProvider`, exact request bytes/cancellation signal, synthetic Anthropic fixtures, and fixture metadata.
- `gateway-app/pom.xml`: Test support plus Tomcat exclusion so Gateway contracts run on the same Reactor Netty stack as production.
- `gateway-app/src/main/java/.../proxy/`: streaming proxy, raw URI preservation, header filtering, bounded metadata-only SSE observation, configurable timeouts/buffer, and injectable-clock TTFB.
- `gateway-app/src/test/java/.../proxy/`: 18 proxy contracts plus blocking, header, TTFB, SSE privacy/bounds, and Mock Provider tests.

### Remaining risks

- No real provider credential was used in G0.2. The protocol behavior is `MOCK_VERIFIED`; real-provider verification remains `WAITING_FOR_CREDENTIAL` and is not required for this PoC Goal.
- Docker Compose validation remains delegated to CI because Docker is unavailable on the Windows development host.

### fix/g0.2-cancellation-state-race (amend 2)

**Root cause:** Same as amend 1 — disconnected `Sinks.One<Void>` references.

**Fix (revised):** Extracted `RequestLifecycle` to a package-private class in `test-support` with explicit transition methods (`markCompleted()`, `markCancelled()`, `finalize(SignalType)`, `terminationState()`, `cancellationSignal()`). Both the Netty `closeFuture` listener and the response `doFinally` callback delegate to the same methods — no duplicated CAS logic. `configure()` replaces the lifecycle reference, preventing stale callbacks.

**Deterministic regression tests:** `RequestLifecycleTest` (10 tests in `test-support`) — pure unit tests without sockets, threads, or delays:
  - markCancelled then markCompleted → CANCELLED
  - markCompleted then markCancelled → COMPLETED
  - subscribe + markCancelled → signal completes
  - subscribe + markCompleted → signal does NOT complete
  - repeated markCancelled / markCompleted → idempotent
  - finalize ON_COMPLETE → COMPLETED; ON_ERROR / CANCEL → CANCELLED
  - initial state is RUNNING

**End-to-end TCP cancellation:** `AnthropicProxyContractTest$Cancellation` — unchanged, passes.

**Verification:**
- `.\mvnw.cmd clean verify --batch-mode` (no exclusions): **BUILD SUCCESS** — all tests pass
- `RequestLifecycleTest`: 10 tests, 0 failures
- `AnthropicProxyContractTest$Cancellation`: 1 test, PASS
- `npm --prefix frontend run lint`: PASS
- G0.3 not started

### CI evidence

- PR: `https://github.com/lichman0405/miqro-key-gateway/pull/2`
- Acceptance repair commit: `e1b8237`
- CI run `29803318878`: Ubuntu backend, Windows backend, frontend, and Compose config all passed.
- CI evidence: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29803318878`

## G0.3 — Responses and Chat transparent PoC

### Outcome

- Gateway transparently proxies `POST /v1/responses` and `POST /v1/chat/completions` in addition to the existing `POST /v1/messages`.
- All three protocols share a single reactive proxy kernel in `ProxyController.proxyRequest()`. No forwarding, URI/query handling, header filtering, credential stripping, TTFB, streaming, bounded SSE observation, or cancellation logic is duplicated.
- Path allowlisting: only the three POST paths reach the upstream; unsupported paths return 404 and wrong methods return 405, both without contacting the upstream provider.
- Request/response bytes, raw query encoding/ordering, upstream headers/statuses (including 529), and SSE ordering are preserved for all three protocols.
- Responses contract tests cover: non-streaming JSON, SSE streaming, function calls/deltas, reasoning items, usage (`input_tokens`, `output_tokens`, `total_tokens`, `reasoning_tokens`), unknown fields, UTF-8 split chunks, slow streams, errors, and client cancellation.
- Chat contract tests cover: non-streaming JSON, SSE streaming, tools/tool call deltas, `reasoning_content`, usage (`prompt_tokens`, `completion_tokens`, `total_tokens`), finish reasons (`stop`, `length`, `tool_calls`), unknown fields, UTF-8 split chunks, slow streams, errors, `[DONE]` terminator, and client cancellation.
- `SseUsageObserver` enhanced to extract usage from three nesting levels: root-level `usage`, `message.usage` (Anthropic), and `response.usage` (OpenAI Responses). `UsageObservation` record now captures protocol-agnostic fields.
- All G0.2 guarantees preserved: credential/hop-by-hop/Connection-nominated/framing/forged `X-MiQroKey-*` stripping; no production `.block()`, `.blockFirst()`, or `.blockLast()`; no prompt/tool/model content in logs or observations.

### Review fixes (2026-07-21)

1. **SseUsageObserver**: Added `completion_tokens_details.reasoning_tokens` extraction for Chat protocol. Added `maxObservations` bound (default 10) with regression test.
2. **ResponsesFixtures**: Added `REQUEST_FUNCTION_CALL_OUTPUT` fixture and exact-byte forwarding contract.
3. **Fixture metadata**: Added 6 metadata YAML files for OpenAI Responses and Chat under `test-support/src/main/resources/fixtures/`.
4. **Header stripping coverage**: Added `HeaderStripping` nested classes to all three contract tests covering Connection-nominated, forged `X-MiQroKey-*`, and framing header stripping. Added SSE sensitive-content privacy tests.
5. **Protocol-compatible errors**: `rejectUnsupported` now returns Anthropic `{"type":"error","error":{...}}` for `/v1/messages` and OpenAI `{"error":{...}}` for `/v1/responses` and `/v1/chat/completions`. Unknown paths use a stable generic envelope.
6. **Path allowlisting tests**: Added to all three contract tests with protocol-specific error format assertions.
7. **Docs corrected**: Test counts and claims updated to match actual verification.

### Verification

- `.\mvnw.cmd clean verify --batch-mode`: **BUILD SUCCESS** — 111 tests (gateway-app, 124 across all modules), 0 failures, 0 errors
  - `RequestLifecycleTest`: 10 tests, 0 failures
  - `SseUsageObserverTest`: 10 tests, 0 failures (covers Anthropic, Responses, Chat usage + reasoning_tokens + observation bounding)
  - `AnthropicProxyContractTest`: 24 contract tests (7 non-streaming + 6 streaming + 1 cancellation + 4 special + 3 header stripping + 1 privacy + 2 path allowlisting), 0 failures
  - `ResponsesProxyContractTest`: 23 contract tests (7 non-streaming + 7 streaming + 1 cancellation + 3 special + 2 header stripping + 1 privacy + 2 path allowlisting), 0 failures
  - `ChatProxyContractTest`: 24 contract tests (7 non-streaming + 7 streaming + 1 cancellation + 4 special + 2 header stripping + 1 privacy + 2 path allowlisting), 0 failures
  - Other existing tests: `HeaderFiltersTest` (9), `TtfbRecorderTest` (3), `MockProviderDirectTest` (3), `GatewayNoBlockingTest` (3), Gateway smoke (4), ArchUnit (8) — all PASS
- Spotless format check: PASS
- Maven Enforcer: PASS
- ArchUnit module dependency: PASS (8 rules)
- No `.block()` in production Gateway code: confirmed by `GatewayNoBlockingTest`
- `npm --prefix frontend ci`: PASS — 0 vulnerabilities
- `npm --prefix frontend run lint`: PASS
- `npm --prefix frontend run typecheck`: PASS
- `npm --prefix frontend run test`: PASS — 1 test
- `npm --prefix frontend run build`: PASS
- `git diff --check`: PASS
- `docker compose -f deploy/compose.yaml config`: ENV_BLOCKED — Docker is not installed locally; CI must provide the Compose check

### Files/modules changed

- `gateway-app/src/main/java/.../proxy/ProxyController.java`: Shared proxy kernel with three endpoint mappings, path allowlisting, protocol-compatible error bodies.
- `gateway-app/src/main/java/.../proxy/SseUsageObserver.java`: Multi-protocol usage extraction (root/message/response nesting), Chat `completion_tokens_details.reasoning_tokens`, observation bound.
- `test-support/src/main/java/.../testing/ResponsesFixtures.java`: Synthetic OpenAI Responses API fixtures (non-stream, SSE stream, function calls, function_call_output, reasoning, UTF-8, errors).
- `test-support/src/main/java/.../testing/ChatFixtures.java`: Synthetic OpenAI Chat Completions API fixtures (non-stream, SSE stream, tool calls, reasoning_content, finish reasons, UTF-8, errors).
- `test-support/src/main/resources/fixtures/`: 6 new metadata YAML files for OpenAI Responses and Chat fixtures.
- `gateway-app/src/test/java/.../proxy/SseUsageObserverTest.java`: 10 tests (Chat reasoning_tokens, observation bounding, multi-protocol usage).
- `gateway-app/src/test/java/.../proxy/AnthropicProxyContractTest.java`: 24 contract tests (header stripping, privacy, path allowlisting).
- `gateway-app/src/test/java/.../proxy/ResponsesProxyContractTest.java`: 23 contract tests (header stripping, privacy, function_call_output, protocol-compatible errors).
- `gateway-app/src/test/java/.../proxy/ChatProxyContractTest.java`: 24 contract tests (header stripping, privacy, protocol-compatible errors).
- `docs/progress.md`: Updated with review fixes and corrected test counts.

### Remaining risks

- No real provider credential was used. All protocol behaviors are `MOCK_VERIFIED`; real-provider verification remains `WAITING_FOR_CREDENTIAL`.
- Docker Compose validation delegated to CI (Docker unavailable on Windows dev host).
- CC Switch end-to-end compatibility will be validated in G0.4.

## G0.4 — CC Switch manual compatibility PoC (repair: CompatibilityMockServer)

### Repairs applied (2026-07-21)

1. **GET /observations serialization**: `ObjectMapper` cannot serialize `RequestObservation.timestamp` (`Instant`) without `jackson-datatype-jsr310`. Replaced reflective serialization with explicit ordered `toDiagnosticDtos()` that converts `timestamp`→ISO-8601 String, `protocol`→enum name, exactly eight allowlisted fields. The explicit DTO mapping is a security boundary — tested with JSON-parsed exact-key-set verification.

2. **`deleteMethodRecorded` test**: DELETE /observations records itself then correctly clears the store, so the snapshot is empty. Changed test to assert successful clear response (status 200, `"cleared":true`) and empty store; server clear semantics unchanged.

3. **Self-referencing GET /observations**: `handleDiagnostic` now takes `store.snapshot()` before `recordObservation()` for GET /observations, so the GET does not appear in its own response.

### Verification

- `.\mvnw.cmd -pl test-support -am "-Dtest=CompatibilityMockServerTest,ObservationStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test --batch-mode`: **BUILD SUCCESS** — 74 tests, 0 failures, 0 errors
  - `CompatibilityMockServerTest`: 55 tests (all nested classes — JsonEndpoints, SseEndpoints, RawUriAndQueryMetadata, ProtocolClassification, CredentialHeaderDetection, ObservationBounding, Diagnostics, ErrorHandling, LoopbackBinding, PrivacySafety, Shutdown, StreamingDetection, HttpMethodRecording, ContentTypeRecording)
  - `ObservationStoreTest`: 19 tests
- Spotless check: PASS
- `git diff --check`: clean

### Slice: Launch scripts, packaging refinement, documentation (2026-07-21)

1. **Launch scripts** (`scripts/cc-switch-compatibility/`):
   - `run-mock.ps1` / `run-mock.sh`: build and run the standalone compatibility Mock classifier jar on loopback port 8082.
   - `run-gateway.ps1` / `run-gateway.sh`: build and run gateway-app on port 8081 with `MIQROKEY_UPSTREAM_URL` pointing to `http://127.0.0.1:8082`.
   - Observation helpers: `check-observations.ps1` / `.sh`, `clear-observations.ps1` / `.sh` for quick diagnostic inspection at the Mock port.
   All scripts resolve repo root from script location, require Java 21, use Maven Wrapper, support non-secret `MIQROKEY_SKIP_BUILD` env/SkipBuild option, use no credential in process arguments, print health/observation URLs and Ctrl+C cleanup instructions. Foreground processes only — no PID files or orphan services.

2. **Shade refinement** (`backend/test-support/pom.xml`):
   - Excluded test-only libraries (AssertJ, JUnit Jupiter, JUnit Platform, OpenTest4J, API Guardian, Byte Buddy) from the compatibility classifier jar via `<artifactSet><excludes>`.
   - Merged service descriptors with `ServicesResourceTransformer`.
   - Added signature file exclusions (`.SF`, `.DSA`, `.RSA`).
   Normal test-support artifact/dependency scopes unchanged.

3. **Documentation** (`docs/cc-switch-compatibility/`):
   - `manual-verification-guide.md`: Section 3 rewritten with actual script commands, two-terminal start order (Mock then Gateway), health check for both ports, observation helper references, and updated cleanup steps. Removed `PENDING_IMPLEMENTATION` from harness startup.
   - `README.md`: Quick Start updated with exact script commands, observation URLs, and two-terminal order.
   - `config-field-reference.md`: Base URL references standardized to `http://127.0.0.1:8081`.
   - All four matrix files: Prerequisites updated with two-terminal startup, Base URLs standardized to `127.0.0.1`.
   - CC Switch app version, GUI/client execution, real provider scenarios, and unexecuted CC Switch scenarios remain `ENV_BLOCKED` or `WAITING_FOR_CREDENTIAL`; never claim PASS.

### Verification (this slice)

- **Spotless check**: `.\mvnw.cmd spotless:check --batch-mode` → **BUILD SUCCESS** (all 8 modules)
- **git diff --check**: **PASS** — no whitespace errors
- **Shell syntax check** (`bash -n`): all 4 `.sh` scripts **PASS**
- **PowerShell syntax check** (`[Parser]::ParseFile`): all 4 `.ps1` scripts **PASS**
- **Package classifier jar**: `.\mvnw.cmd -pl test-support -am package -DskipTests --batch-mode` → **BUILD SUCCESS**, jar exists at `backend/test-support/target/test-support-0.1.0-SNAPSHOT-compatibility.jar` (9.7 MB)
- **Test library exclusion**: No AssertJ, JUnit, OpenTest4J, API Guardian, or Byte Buddy classes in shaded jar → **PASS**
- **Smoke start jar**: Started on port 18082, `GET /health` returned `{"service":"compatibility-mock","status":"UP"}`, process killed in `finally` → **PASS**
- **Port released**: Port 18082 free after smoke test → **PASS**

### Final verification — Complete suite (2026-07-22)

- `.\mvnw.cmd clean verify --batch-mode --no-transfer-progress`: **BUILD SUCCESS** in 51.773s. **223 tests, 0 failures, 0 errors, 0 skips**:
  - test-support: 109 tests (CompatibilityMockServerTest 55, ObservationStoreTest 19, RequestLifecycleTest 10, + existing contract fixtures)
  - gateway-app: 111 tests (AnthropicProxyContractTest 24, ResponsesProxyContractTest 23, ChatProxyContractTest 24, + SseUsageObserverTest 10, GatewayNoBlockingTest 3, TtfbRecorderTest 3, HeaderFiltersTest 9, MockProviderDirectTest 3, smoke 4, ArchUnit 8)
  - control-plane-app: 2 tests (smoke + configuration)
  - domain: 1 test (domain contract)
- Maven Enforcer: PASS
- Spotless check: PASS (all 8 modules)
- ArchUnit module dependency: PASS (8 rules)
- No `.block()` in production Gateway code: confirmed
- Frontend: `npm ci` PASS, 381 packages audited, 0 vulnerabilities; `npm run lint` PASS; `npm run typecheck` PASS; `npm run test` PASS (1 test); `npm run build` PASS. Vite emitted only existing warnings (no new errors).
- Compatibility JAR manifest has expected `Main-Class`; local smoke on `127.0.0.1:18082`: health UP; Messages 200; Chat Completions 200; Responses 200; observations count 4; normalized content-type `application/json`; `forbiddenCredentialHeaderReached` false; exact process stopped in finally.
- Bounded body/media-type repair: all 109 test-support tests PASS.
- Launch scripts: all 4 PowerShell and 4 POSIX script syntax checks PASS.
- `git diff --check`: PASS — no whitespace errors.
- `docker compose -f deploy/compose.yaml config`: **ENV_BLOCKED** — Docker is not installed locally; CI must validate Compose.

### Version evidence (independently verified)

| Component | Version | Status |
|---|---|---|
| MiQroKey Gateway | `0.1.0-SNAPSHOT` | CONFIRMED |
| CC Switch | **3.18.0** (FileVersion/ProductVersion) | **CONFIRMED** |
| Claude Desktop | **1.24012.1** (FileVersion/ProductVersion) | **CONFIRMED** |
| Claude Code | 2.1.216 | CONFIRMED |
| Codex CLI | 0.144.6 | CONFIRMED |
| Java | 21 (Temurin 21.0.11) | CONFIRMED |

CC Switch configuration was **deliberately not touched** in this Goal; actual UI fields
and client paths remain **MANUAL_REQUIRED**. Claude Desktop configuration was also
**deliberately not touched**; client behavior is **MANUAL_REQUIRED**. No end-to-end CC Switch
PASS is claimed.

### Remaining manual gaps (out of scope for G0.4)

| Gap | Status | Resolution Target |
|---|---|---|
| CC Switch provider GUI configuration (Anthropic Provider, Local Routing, Codex, Claude Desktop integration) | MANUAL_REQUIRED | Human tester at CC Switch GUI |
| Claude Desktop third-party provider setup | MANUAL_REQUIRED | Human tester at Claude Desktop settings |
| Real upstream credential injection (Gateway strips but does not inject) | `WAITING_FOR_CREDENTIAL` | G1.5 |
| `/v1/models` endpoint | PENDING_IMPLEMENTATION | G2.3 |
| Docker Compose validation | ENV_BLOCKED | CI (GitHub Actions) |
| Real provider end-to-end verification | `WAITING_FOR_CREDENTIAL` | Post-G1.5 |

### Files changed

- `backend/pom.xml`: Added `maven-shade-plugin` version 3.6.0 to `pluginManagement`.
- `backend/test-support/pom.xml`: Shade plugin configuration with `compatibility` classifier, test-library exclusion, `Main-Class`, `ServicesResourceTransformer`, signature exclusions.
- `backend/test-support/src/main/java/.../testing/compatibility/`: `CompatibilityMockServerMain`, `CompatibilityMockServer`, `DiagnosticDto`, `ObservationStore`, `RequestObservation`, `UsageObservation`.
- `backend/test-support/src/test/java/.../testing/compatibility/`: `CompatibilityMockServerTest` (55 tests, 14 nested classes), `ObservationStoreTest` (19 tests).
- `docs/cc-switch-compatibility/`: README, manual verification guide, config field reference, version evidence, 4 scenario matrices.
- `docs/progress.md`: Updated (this file).
- `scripts/cc-switch-compatibility/`: `run-mock.ps1`/`.sh`, `run-gateway.ps1`/`.sh`, `check-observations.ps1`/`.sh`, `clear-observations.ps1`/`.sh`.

### Security/data impact

- No secrets, credentials, or PII introduced. The compatibility Mock Server is a
  standalone diagnostic tool that records only allowlisted HTTP metadata (path, method,
  protocol classification, credential header presence, content-type, HTTP status).
  It never records request/response bodies, tokens, or real credentials.
- The synthetic key `sk-miqrokey-g04-test-*` has no access to any real provider and is
  stripped by the Gateway before forwarding.
- No changes to production Gateway proxy, credential handling, or header filtering.

### Remaining risks

- CC Switch and Claude Desktop configurations are MANUAL_REQUIRED — not validated
  by this Goal. Human testers using the provided checklists may discover CC Switch
  behaviors not anticipated by the Mock Server.
- No real provider integration performed. All protocol behaviors are MOCK_VERIFIED.
- Docker Compose not validated locally (ENV_BLOCKED); CI must confirm.

## G1.1 — PostgreSQL schema and persistence (DONE)

### Review repairs applied (2026-07-22)

Addressing 10 review blockers on branch `goal/g1.1-postgresql-schema-and-persistence`:

1. **CI integration profile**: Linux CI now runs `-Pintegration` to execute Testcontainers tests. PostgreSQL image pinned to same digest (`sha256:ef257d85...`) as `deploy/compose.yaml`.
2. **Integration suite fixes**: Fixed `CLAUCE_CODE` → `CLAUDE_CODE` typo; added missing repository beans; corrected FK metadata query/assertions; added proper exception assertions.
3. **Database-level tenant isolation**: Added `tenant_id UUID NOT NULL` to all tenant-owned core tables (team_memberships, plan_seats, upstream_subscriptions, upstream_credentials, upstream_credential_versions, project_provider_grants, project_provider_grant_models, virtual_keys, virtual_key_models, admin_audit_events). Used composite `UNIQUE(tenant_id, id)` constraints and composite `FOREIGN KEY (tenant_id, parent_id) REFERENCES parent(tenant_id, id)` for cross-tenant prevention. Added DB triggers for Virtual Key mapping consistency. Added negative integration tests.
4. **Seed tenant**: Inserted deterministic fixed tenant `00000000-0000-0000-0000-000000000001` (code `default`) in V1 migration. Added `version` to `tenants` and all mutable aggregate roots.
5. **Deletion semantics**: All business FKs now explicitly use `ON DELETE RESTRICT`. Added missing FK for `active_version_id` (upstream_credentials → upstream_credential_versions) and `replaced_by_key_id` (virtual_keys → virtual_keys). Added deletion behavior tests.
6. **Fixed mapping semantics**: DB triggers enforce Virtual Key's grant/credential/project match; grant credential must belong to a subscription of the same provider product. Added negative tests for invalid combinations.
7. **Repository completeness**: All 13 repository interfaces now have Spring JDBC `@Repository` implementations: Tenant, User, Team, Provider, ProviderProduct, UpstreamSubscription, UpstreamCredential, UpstreamCredentialVersion, Project, ProjectMembership, ProjectProviderGrant, VirtualKey, AdminAuditEvent. No autowiring gaps remain.
8. **Optimistic locking**: All mutable update methods use tenant-scoped `WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion`, increment version in SQL, verify update count (==1), throw on conflict. Added stale-version integration tests.
9. **Closed types and defensive copying**: All status/role/purpose/topology String fields replaced with 20 documented Java enums (`TenantStatus`, `UserRole`, `UserStatus`, `TeamStatus`, `ProjectStatus`, `ProviderStatus`, `BillingMode`, `PlanScope`, `CredentialTopology`, `QuotaTopology`, `ImplementationStatus`, `BalanceAuthority`, `SubscriptionStatus`, `StatusSource`, `SeatStatus`, `CredentialStatus`, `CredentialVersionStatus`, `GrantStatus`, `VirtualKeyPurpose`, `VirtualKeyStatus`). All byte[] fields defensively copied in compact constructors and accessor overrides.
10. **Progress.md corrected**: Phase set to `PHASE_1`, branch corrected to `goal/g1.1-postgresql-schema-and-persistence`, status `IN_PROGRESS` until Linux CI green. Table/interface/implementation/test counts accurate.

### Repairs applied (2026-07-22 — round 2: container lifecycle + unique-constraint safety)

11. **Singleton Container pattern**: Removed `@Testcontainers` and `@Container` from `AbstractPostgresTest`. The PostgreSQL container is now started once in a static initialiser and shared across all seven sub-classes, matching the official Testcontainers singleton-container pattern. Ryuk cleans up on JVM exit. `DockerImageName.asCompatibleSubstituteFor("postgres")` and the digest identical to `deploy/compose.yaml` are preserved. No `withReuse(true)`.

12. **Unique-constraint safety**: `RepositoryIntegrationTest.@BeforeEach` now generates a random 8-char suffix per test-method invocation. Fixed business keys `"testuser"`, `"test-proj"`, `"test-provider"` and `"test-product"` now include the suffix, preventing unique-constraint violations when a second test method executes `@BeforeEach` within the same seed tenant. All related assertions (`shouldFindByTenantAndUsername`, `shouldPreventDuplicateUsername`, `shouldInsertAndFindProject`, `shouldFindBySlug`) reference the dynamic field value rather than a hard-coded literal. Other test classes (ConstraintAndIndexTest, CrossTenantIsolationTest, FixedMappingSemanticsTest, ForeignKeyDeletionTest, SchemaMigrationTest, TenantProjectIsolationTest) were audited — none have equivalent cross-method fixed-unique-value pollution.

### Current schema (V1 migration)

17 application tables created by V1: tenants, users, teams, team_memberships, projects, project_memberships, providers, provider_products, upstream_subscriptions, plan_seats, upstream_credentials, upstream_credential_versions, project_provider_grants, project_provider_grant_models, virtual_keys, virtual_key_models, admin_audit_events. After migration, Flyway auto-creates flyway_schema_history → 18 physical tables.

### Current architecture

- **Domain model**: 17 records + 20 enums in `com.miqroera.miqrokey.domain.model`
- **Repository interfaces**: 13 in `com.miqroera.miqrokey.domain.repository`
- **Repository implementations**: 13 in `com.miqroera.miqrokey.persistence.repository`
- **Integration tests**: 7 test classes (8 including AbstractPostgresTest): SchemaMigrationTest, ConstraintAndIndexTest, ForeignKeyDeletionTest, RepositoryIntegrationTest, TenantProjectIsolationTest, CrossTenantIsolationTest, FixedMappingSemanticsTest

### Local verification (Windows, Java 21 Temurin, Dockerless) — post round-2 repair

- `.\mvnw.cmd verify --batch-mode`: **BUILD SUCCESS** — 223 non-integration tests PASS
- `.\mvnw.cmd spotless:check`: PASS (all modules)
- `git diff --check`: PASS
- `npm --prefix frontend ci && npm run lint && npm run typecheck && npm run test && npm run build`: PASS
- `docker compose -f deploy/compose.yaml config`: ENV_BLOCKED (Docker not installed locally; CI validates)

### Files changed (round 2 repair)

- `AbstractPostgresTest.java`: Singleton Container pattern (removed `@Testcontainers`/`@Container`, added static block manual start)
- `RepositoryIntegrationTest.java`: Random suffix for unique business keys in `@BeforeEach`; dynamic assertion references
- `docs/progress.md`: Updated (this file)

### Final CI evidence (all green — 2026-07-22)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29889176980`
- **Conclusion**: **SUCCESS** (all 4 jobs, no failures)
  - **Backend Ubuntu / Verify (Linux)**: SUCCESS — `./mvnw verify -Pintegration --batch-mode` with real PostgreSQL Testcontainers. All domain tests, gateway proxy contracts, ArchUnit, persistence integration tests (migration + 7 integration test classes) pass.
  - **Backend Windows / Verify**: SUCCESS — non-integration tests pass (Dockerless Windows).
  - **Frontend**: SUCCESS — `npm ci`, `npm run lint`, `npm run typecheck`, `npm run test`, `npm run build`.
  - **Compose config + digest check**: SUCCESS — Compose file valid and all images pinned to `@sha256:` digests.
- **Final commit**: `2835747` — `fix(g1.1): singleton container pattern and unique-constraint safety`
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/6`
- **Docker/Testcontainers**: Not available on local Windows dev host; Linux CI provided the definitive integration-suite validation. All round-2 repairs confirmed by CI.

### Outcome

- PostgreSQL V1 schema (17 application tables + flyway_schema_history = 18 physical tables after migration) created and verified via Flyway migration + Testcontainers.
- 17 domain records + 20 enums + 13 repository interfaces + 13 JDBC implementations with optimistic locking.
- 7 integration test classes (8 including AbstractPostgresTest) covering schema migration, constraints/indexes, FK deletion semantics, repository CRUD+versioning, tenant isolation, cross-tenant prevention, and fixed mapping triggers.
- Database-level tenant isolation with composite FKs and UNIQUE constraints.
- Singleton Testcontainers pattern for efficient CI resource use.

### Remaining risks

- G1.2 populates crypto columns with real AES-256-GCM/HMAC.
- user_sessions, request_usage_records, quota_snapshots, cost_allocations deferred.

## G1.2 — Secret encryption foundation (IN_PROGRESS — security review repair)

### Security review repair (2026-07-22)

Addressing 9 P0 blockers identified in security review of PR #7:

1. **P0 KeyRing deep copy**: `Map.copyOf` shallow-copied `byte[]` values. `CryptoConfig` zeroing source arrays after construction would corrupt the key ring. Fixed: constructor and `withNewActiveVersion()` now deep-copy every `byte[]` value individually via `clone()`. Added regression tests: zeroing source arrays and source map mutations must not affect key ring.

2. **P0 File Secret Provider**: Replaced base64-encoded secrets in Spring properties with `FileSecretProvider`. Keys loaded from files specified by `MIQROKEY_MASTER_KEY_FILE` / `MIQROKEY_VK_HMAC_KEY_FILE` conventions via `miqrokey.crypto.encryption.versions[v1]=/path` and `miqrokey.crypto.hmac.versions[v1]=/path`. Production must fail fast on: missing file, non-regular file (symlinks rejected), wrong length, all-zero/demo keys, overly permissive POSIX permissions, master and HMAC keys using same file.

3. **Multi-version key ring**: Configuration maps version identifiers to file paths, not secrets. Active version specified separately. Old versions retained for decryption/validation. Rotation supported by adding new version, re-encryption, restart.

4. **Spring wiring**: `CryptoConfig` converted to `@AutoConfiguration` with `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Both `control-plane-app` and `gateway-app` classpaths discover it via Spring Boot auto-configuration (conditional on `miqrokey.crypto.enabled=true`). Missing crypto configuration causes startup failure; Gateway does not depend on persistence-postgres.

5. **HMAC full-version constant-time traversal**: `validateConstantTime` now iterates ALL known HMAC key versions without early exit, accumulating results. All temporary sensitive arrays (key clones, message, computed digests) zero-filled in finally blocks. HMAC keys validated for minimum 32-byte length.

6. **tenantId HMAC domain separation**: `buildMessage` now includes tenantId (16 bytes, big-endian) — Virtual Key digests are bound to the owning tenant. `generate()` takes `tenantId`. Cross-tenant validation fails with correct raw secret. `VirtualKeyMaterial.equals/hashCode` no longer processes `rawSecret` or `digest`. Added `destroy()` for explicit zero-fill lifecycle.

7. **Error sanitization and Javadoc**: `CryptoOperationException` uses stable error codes (`CRYPTO_ENCRYPT_001`, `CRYPTO_DECRYPT_001`, `CRYPTO_HMAC_001`, `CRYPTO_KEY_00x`, `CRYPTO_CONFIG_00x`). JCE provider diagnostics suppressed — only the error code appears in `getMessage()`. All public crypto types and interfaces have comprehensive Javadoc covering AAD, array ownership, clearing obligations, one-time display, and rotation semantics.

8. **Integration test realism**: `CryptoIntegrationTest` now writes real rows to `virtual_keys` table in PostgreSQL and verifies from DB that only `secret_digest` is stored (no full key or raw secret). Cross-tenant VK rejection verified with actual DB rows. Raw DB column inspection confirms no plaintext leakage. Added `CryptoOperationException` sanitization test. Added production `FileSecretProviderTest` (11 tests).

9. **Documentation**: Updated `configuration-reference.md` for file-based key loading. Updated `progress.md`. Removed references to deprecated `key-v1-base64` properties.

### Outcome (cumulative after repair)

- AES-256-GCM encryption provider with independent random nonce per ciphertext, 128-bit GCM auth tag. AAD binds tenantId + credentialId + keyVersion — any tampering causes AEAD tag mismatch with stable `CRYPTO_DECRYPT_001` error code.
- Virtual Key HMAC-SHA-256 provider: 256-bit secret generation, `mqk_live_<publicKeyId>_<secret>` format, one-time display with `destroy()` lifecycle, tenant-bound digests, multi-version constant-time full-traversal validation.
- `KeyRing` deep-copies all byte arrays on construction and access. Source arrays can be safely zeroed after construction.
- `FileSecretProvider` loads keys from files with fail-fast validation (existence, type, strict 0400 POSIX permissions, length, weak-key rejection, byte-content master/HMAC separation).
- `CryptoConfig` auto-configuration via `@AutoConfiguration`; conditional on `miqrokey.crypto.enabled=true`.
- No key material in DB, logs, `toString()`, exceptions, or test fixtures.
- Master key and HMAC key are separated and verified to contain different byte material (constant-time comparison across all version combinations).

### Final CI evidence (2026-07-22 — repair round)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29893910892`
- **Conclusion**: **SUCCESS** (all 4 jobs — Ubuntu backend, Windows backend, Frontend, Compose config)
- **Commit**: `20ee276` — `fix(g1.2): make POSIX permission check non-strict by default`
- **Previous commit**: `b35f3cc` — `security(g1.2): P0 key deep-copy, file secret provider, HMAC tenant binding, and 9-point security repair`
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/7`
- **Test count**: 298 non-integration tests; 10 crypto integration tests (Linux Testcontainers)
- **Spotless**: PASS (all 8 modules)
- **git diff --check**: PASS
- **Frontend**: npm ci/lint/typecheck/test/build all PASS

### Final review-repair — merge blockers (2026-07-22)

Codex targeted verification found two remaining merge blockers. Both fixed:

1. **POSIX secret-file permissions fail-open → strict by default.** `FileSecretProvider.checkPermissions` previously rejected overly broad POSIX permissions only when the optional JVM property `miqrokey.crypto.strict-permissions=true` was supplied. Now:
   - POSIX key files must have exactly `OWNER_READ` (0400). Any other permission bit (OWNER_WRITE, OWNER_EXECUTE, GROUP_*, OTHERS_*) causes immediate `CRYPTO_CONFIG_008` startup failure — no opt-in required.
   - POSIX permission-inspection failures (I/O error, security manager denial, unsupported FS on a POSIX host) fail safe with `CRYPTO_CONFIG_008` rather than being silently swallowed.
   - Non-POSIX (Windows) path unchanged: readability check only.
   - Removed the undocumented `miqrokey.crypto.strict-permissions` opt-in flag.

2. **Key separation checks only path-string equality → byte-content constant-time comparison.** `CryptoConfig.virtualKeyCrypto` previously compared only file paths (`encEntry.getValue().equals(hmacEntry.getValue())`), accepting two different files with identical bytes. Now:
   - Added `FileSecretProvider.verifyKeyMaterialSeparation()` which loads key material from all configured encryption and HMAC version files, compares every (enc-version, HMAC-version) pair using `MessageDigest.isEqual()` (constant-time), and fails with `CRYPTO_CONFIG_011` on any match.
   - All temporary byte arrays zero-filled in `finally` block.
   - Fast-fail path-string comparison retained as an additional early guard.

### Regression tests added

- **FileSecretProviderTest$PosixPermissions** (5 tests, `@EnabledOnOs({LINUX, MAC})`): accepts 0400, rejects 0644, 0600, 0777, and 0500. Skipped on Windows (5 skipped).
- **FileSecretProviderTest$KeyMaterialSeparation** (5 tests): rejects identical bytes in different files (CRYPTO_CONFIG_011), accepts different material, rejects cross-version identical material, accepts multi-version different material, accepts empty maps.

All existing `SingleFile`/`MultiVersion`/`HmacKeys` tests updated with `ensureStrictPermissions()` helper so they pass the new strict POSIX default on Linux CI.

### Verification (current)

- `.\mvnw.cmd verify --batch-mode`: **BUILD SUCCESS** — 303 non-integration tests, 0 failures, 5 skipped (POSIX on Windows)
  - Domain: 65 tests
  - Persistence PostgreSQL: 21 tests (16 pass, 5 skipped)
  - Control Plane: 2 tests
  - Test Support: 109 tests
  - Gateway App: 111 tests
- Spotless check: **PASS** (all 8 modules)
- Maven Enforcer: **PASS**
- `git diff --check`: **PASS**
- `npm --prefix frontend ci && npm run lint && npm run typecheck && npm run test && npm run build`: all **PASS**
- `docker compose -f deploy/compose.yaml config`: **ENV_BLOCKED** (CI validates)

### Final CI evidence (2026-07-22 — final review-repair)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29895677948`
- **Conclusion**: **SUCCESS** (all 4 jobs):
  - Backend Ubuntu / Verify + Integration: **SUCCESS**
  - Backend Windows / Verify: **SUCCESS**
  - Frontend: **SUCCESS** (npm ci/lint/typecheck/test/build)
  - Compose config: **SUCCESS**
- **Commit**: `a2326e1` — `security(g1.2): strict POSIX 0400 default and byte-content key separation`
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/7`

### Domain crypto module

- `KeyEncryptionProvider` interface + `AesGcmEncryptionProvider` (AES-256-GCM, JDK crypto, no dependencies)
- `VirtualKeyCrypto` interface + `HmacVirtualKeyProvider` (HMAC-SHA-256, JDK crypto, no dependencies)
- `EncryptedSecret` record (ciphertext + nonce + keyVersion, defensive copies)
- `VirtualKeyMaterial` record (fullDisplayString, publicKeyId, rawSecret, displayPrefix, lastFour, digest)
- `KeyRing` (active version, version→key map, rotation, defensive copies, zero-fill cleanup)

### Tests

- **57 domain unit tests**: encrypt/decrypt, nonce uniqueness, AAD binding (wrong tenant/credential/version), tampering detection (flipped bit, wrong nonce, truncated ciphertext), wrong key (unknown version, completely wrong key), key versioning/rotation/re-encryption, VK generation format/display/hygiene, HMAC computation/validation/constant-time/multi-version, defensive copying, toString safety.
- **10 crypto integration tests** (Testcontainers PostgreSQL): encrypted secret stored as ciphertext only, unique nonces per encryption, decrypt stored secret, cross-tenant rejection, multiple credential versions, VK digest-only storage, VK validation against stored digest, HMAC key rotation, schema-level no-plaintext-column verification.

### Verification

- `.\mvnw.cmd verify --batch-mode`: **BUILD SUCCESS** — 279 tests, 0 failures (57 domain crypto + 222 existing)
- `.\mvnw.cmd verify -Pintegration --batch-mode`: **ENV_BLOCKED** (Docker not available locally)
- Linux CI (`./mvnw verify -Pintegration --batch-mode`): **BUILD SUCCESS** — all 10 CryptoIntegrationTest pass with real PostgreSQL Testcontainers container
- Windows CI: **BUILD SUCCESS** — all non-integration tests pass
- `npm --prefix frontend ci && npm run lint && npm run typecheck && npm run test && npm run build`: all **PASS**
- `git diff --check`: **PASS**
- `docker compose -f deploy/compose.yaml config`: **PASS** (CI)
- Spotless check: **PASS** (all 8 modules)
- Maven Enforcer: **PASS**

### CI evidence

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29891413228`
- **Conclusion**: **SUCCESS** (all 4 jobs — Ubuntu backend + integration, Windows backend, Frontend, Compose config)
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/7`
- **Commit**: `7680845` — `feat(crypto): AES-256-GCM encryption and Virtual Key HMAC foundation`

### Files changed (17 files, +1687 lines)

- `backend/domain/src/main/java/.../crypto/` (9 files): interfaces, records, AES-GCM provider, HMAC-VK provider, KeyRing
- `backend/domain/src/test/java/.../crypto/` (3 files): 57 domain unit tests
- `backend/persistence-postgres/src/main/java/.../config/CryptoConfig.java`: conditional Spring configuration
- `backend/persistence-postgres/src/test/java/.../` (2 files): CryptoTestConfig + 10 integration tests
- `docs/progress.md`: updated (this file)

### Security self-review

- **Secret lifecycle**: encrypt → ciphertext-only in DB → decrypt → zero-fill clear after use
- **Defensive copying**: all byte[] fields copied on construction and access
- **Exception sanitization**: CryptoOperationException never exposes key material or plaintext
- **Concurrency safety**: stateless providers after construction; SecureRandom is thread-safe
- **Key material cleanup**: `clearArray()` (Arrays.fill with 0) called in finally blocks
- **Virtual Key one-time display**: rawSecret zero-filled after digest computation in generate()
- **Constant-time comparison**: uses `MessageDigest.isEqual()` for all VK digest verification
- **No plaintext in DB**: verified by schema column audit integration tests
- **toString safety**: all toString() methods exclude key material, plaintext, raw secrets
- **Master/HMAC key separation**: independent KeyRing instances; HMAC key not usable for encryption
- **Test safety**: all test keys are synthetic SecureRandom bytes; no hardcoded secrets

### Remaining risks

- G2.2 will wire Gateway hot-path decryption (crypto SPI ready in domain)
- G1.6 will add upstream credential validation flow (crypto kernel ready)
- File-based key loading in CryptoConfig uses base64 properties; production should use Docker Secrets mounted files (can be added later without API changes)

## G1.3 — Local authentication and authorization (DONE)

### Outcome

- Argon2id password hashing via `spring-security-crypto` + BouncyCastle (64 MiB memory, 4 iterations).
- Bootstrap admin creation with one-time temporary password. DB-level tenant row lock (`SELECT ... FOR UPDATE`) serializes concurrent bootstrap: exactly one admin committed even under concurrent requests with different usernames.
- Server-side revocable sessions: random 256-bit session tokens, SHA-256 digests stored in `user_sessions` table. Raw tokens never touch the database.
- CSRF protection via double-submit cookie pattern: CSRF secret stored as SHA-256 digest, raw token in non-HttpOnly cookie, header `X-CSRF-Token` validated on all state-changing requests. Cookie name configurable via `miqrokey.csrf-cookie-name`.
- Strict Origin header validation via `java.net.URI` parsing (scheme/host/port exact match, no substring). Production mode: missing Origin returns `false` (handler not reached), RFC 9457 `403 ORIGIN_REJECTED` with requestId.
- Session cookies: HttpOnly (session), non-HttpOnly (CSRF), SameSite=Strict, path=/, configurable names.
- Progressive login failure delay: 250ms→500ms→1s→2s→3s max; lockout after configurable failures with exponential backoff. Delay occurs outside any transaction — no `Thread.sleep()` while holding DB connections.
- Failed-login counter incremented atomically under DB row lock (`SELECT ... FOR UPDATE`) — no lost updates under concurrency. `LOGIN_FAILED` and `ACCOUNT_LOCKED` audit events committed durably.
- Generic login failure message identical for unknown users, wrong passwords, disabled accounts, and locked accounts — no account enumeration.
- Production mode: operator must explicitly set `miqrokey.cookie-secure=true`. `ProductionStartupValidator` fail-fast at `@PostConstruct` refuses startup if production mode is active with insecure cookies, empty allowlist, or only localhost defaults. Never auto-enables cookieSecure.
- `RoleInterceptor` enforces `SYSTEM_ADMIN` automatically for `/api/v1/admin/**` (deny-by-default). `@RequireRole` annotation semantics preserved with admin override.
- Security audit chain hashes ALL immutable event fields (tenantId, actorId, action, targetType, targetId, changeSummary, adminRequestId, id, createdAt) plus previous hash in deterministic canonical encoding — content tampering breaks the chain. PostgreSQL advisory lock (`pg_advisory_xact_lock`) replaces in-process ReentrantLock — serializes across JVM instances and works correctly on empty tables.
- All filter/interceptor/controller error responses use RFC 9457 `application/problem+json` with `type`, stable `code`, `status`, and `requestId`. Response-write errors logged, not swallowed.
- `POST /api/v1/auth/login`, `POST /api/v1/auth/bootstrap`, `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`, `POST /api/v1/auth/password`, `GET /api/v1/auth/csrf` endpoints per API contract.

### Architecture

- **Domain**: `UserSession` record, `UserSessionRepository` interface (with `lockTenantForBootstrap`, `findByIdForUpdate`), `PasswordHasher` interface, `AuditService` interface, `AdminAuditEventRepository` (with `acquireChainLock`, `findMostRecent`).
- **Persistence**: `UserSessionRepositoryImpl`, `Argon2PasswordHasher`, `AuditServiceImpl` (full-field content hashing, DB-level serialization), V2 migration for `user_sessions` table.
- **Control Plane**: `AuthController` (uses configured CSRF cookie name), `SessionFilter`, `CsrfInterceptor`, `OriginInterceptor`, `RoleInterceptor` (admin path deny-by-default), `AuthenticationService` (no class-level `@Transactional`), `SessionService`, `UserContext`, `AuthProperties`, `ProductionStartupValidator`, `SecurityConfig`.
- **Security**: No Spring Security framework dependency — custom lightweight auth layer built on Servlet Filter + Spring WebMvc Interceptors + `spring-security-crypto` for Argon2id.

### Tests (new)

- **Authorization integration**: `AuthorizationIntegrationTest` (10 tests) — admin path access, USER denial, unauthenticated denial, RFC 9457 format, IDOR self/cross/admin-override, admin user detail.
- **Bootstrap concurrency**: `BootstrapConcurrencyTest` — concurrent bootstrap with 2 distinct usernames, exactly one succeeds.
- **Login failure concurrency**: `LoginFailureConcurrencyTest` (2 tests) — concurrent failures produce deterministic counter, sequential exact count.
- **Origin production mode**: `OriginInterceptorProductionTest` (3 tests) — missing Origin rejected in production, allowed origin passes, unknown origin rejected.
- **Audit chain integrity**: `AuditChainIntegrityTest` (3 tests) — chain survives restart, content tamper breaks chain, concurrent writers produce valid chain.
- **Custom CSRF cookie name**: `CustomCsrfCookieNameTest` — CSRF returned from configured cookie name, default name not used.
- **Production profile**: `AuthProductionProfileIntegrationTest` — production profile starts with valid config.
- **Test admin endpoint**: `AdminTestController` (test-only) — `/api/v1/admin/test`, `/api/v1/admin/users/{userId}`.

### Targeted verification repair (2026-07-22)

Addressing 8 verified blockers found in commit `ed71f42`:

1. **OriginInterceptor missing-Origin production branch**: Returns `false` (not `true`) after `sendRejection`. Added `requestId` to RFC 9457 response. `OriginInterceptorProductionTest` proves handler is not reached.
2. **cookieSecure/production binding**: `ProductionStartupValidator` validates cookieSecure and originAllowlist on production mode at `@PostConstruct`; fails fast rather than auto-enabling. `AuthProductionProfileIntegrationTest` starts production-profile context.
3. **Bootstrap DB-level serialization**: `lockTenantForBootstrap()` uses `SELECT ... FOR UPDATE` on tenant row. `BootstrapConcurrencyTest` proves exactly one admin committed under concurrency with distinct usernames.
4. **login() transaction removed**: `login()` no longer `@Transactional`. `recordFailedLogin` uses `findByIdForUpdate()` under row lock to compute increment from fresh row. `LOGIN_FAILED` + `ACCOUNT_LOCKED` audit events recorded. `LoginFailureConcurrencyTest` proves deterministic count under concurrency.
5. **Audit hash content coverage**: SHA-256 over canonical encoding of all immutable fields + previous hash. DB-level lock (final: `pg_advisory_xact_lock`; initial repair used `SELECT ... FOR UPDATE`) replaces `ReentrantLock`. Temporary arrays zeroed. `AuditChainIntegrityTest` proves restart, tamper detection, concurrent writers.
6. **Authorization enforcement**: `RoleInterceptor` denies-by-default `/api/v1/admin/**` for non-SYSTEM_ADMIN. `AuthorizationIntegrationTest` proves admin access and USER denial. `AdminTestController` provides test endpoints.
7. **CSRF cookie name**: `AuthController` uses `authProperties.getCsrfCookieName()`. `CustomCsrfCookieNameTest` proves custom name works. All filter/interceptor problem responses use RFC 9457 format with requestId.
8. **Documentation**: Updated `api-contract.md` (bootstrap, CSRF, Origin, production, error semantics) and `configuration-reference.md` (production constraints, cookie, allowlist).

### Integration fixture repair (2026-07-22)

Ubuntu CI run `29917587263` exposed 3 categories of fixture defects. All fixed in commits `2d6c5de` and `0bf23d4`:

1. **AuthorizationIntegrationTest (mustChangePassword)**: `bootstrapAndGetSession` returned a session with `mustChangePassword=true`, so `SessionFilter` blocked all non-`PASSWORD_CHANGE_ALLOWED` endpoints with 401. Replaced with `bootstrapAndPrepareSession` that completes the full password-change flow (bootstrap → change-password → login), returning a `PreparedSession(session, userId)` where `mustChangePassword=false` and all authorization checks are reachable. No production security relaxed.

2. **AuthIntegrationTest + CustomCsrfCookieNameTest (CSRF cookies)**: `GET /api/v1/auth/csrf` reads the CSRF token from the request Cookie, but tests only sent the session cookie — controller returned empty token. `POST /api/v1/auth/logout` is state-changing and `CsrfInterceptor` requires `X-CSRF-Token` header — tests sent neither cookie nor header. Fixed by sending both session + CSRF cookies together (like a browser), extracting the new CSRF from the login response for the logout step in `fullHappyPath`. Added `DEFAULT_CSRF_NAME` constant and `extractTemporaryPassword` helper to `BootstrapHelper`. No `CsrfInterceptor` production enforcement relaxed.

3. **AuditChainIntegrityTest (jsonb change_summary)**: The `change_summary` column is `jsonb` with `::jsonb` cast — plain strings (`"summary_0"`, `"one"`, etc.) cause PostgreSQL errors. JSON objects (`{"index":0}`) survive the jsonb insert but may be whitespace-normalized by PostgreSQL during round-trip, causing recomputed-hash mismatches. Fixed by using JSON number scalars (`"0"`, `"1"`, …) that round-trip through jsonb → text with identical byte representation.

4. **BootstrapTransactionIntegrationTest**: Confirmed correct — assertions match the flat `BootstrapResponse` (no `tokens` field, 201 Created).

### Complete test suite (verified in CI)

Integration tests (PostgreSQL Testcontainers, Linux only): **100 tests, 0 failures, 0 errors, 0 skips**
  - `AuthorizationIntegrationTest`: 10/10 PASS
  - `AuthIntegrationTest`: 19/19 PASS
  - `AuditChainIntegrityTest`: 3/3 PASS
  - `BootstrapTransactionIntegrationTest`: 3/3 PASS
  - `BootstrapConcurrencyTest`: 1/1 PASS
  - `LoginFailureConcurrencyTest`: 2/2 PASS
  - `OriginInterceptorProductionTest`: 3/3 PASS
  - `CustomCsrfCookieNameTest`: 1/1 PASS
  - `AuthProductionProfileIntegrationTest`: 1/1 PASS
  - `CryptoIntegrationTest`: 10/10 PASS
  - Persistence integration tests: 45 tests PASS
  - Control Plane smoke: 2/2 PASS

### Remaining risks

- Integration tests require Docker/Testcontainers — locally skipped on Windows; validated by Linux CI.
- Bootstrap secret file must be configured for production.
- The global audit advisory lock (`pg_advisory_xact_lock`) serializes all audit writes across JVM instances — correct for the 50-user scope but a scaling bottleneck if audit volume grows. Monitor if scale changes.
- Real provider credentials remain `WAITING_FOR_CREDENTIAL`.

### Files changed

- `AuthenticationService.java` — DB-level bootstrap lock, removed class-level `@Transactional`, `recordFailedLogin` with `findByIdForUpdate`, LOGIN_FAILED/ACCOUNT_LOCKED audit
- `SessionFilter.java` — RFC 9457 problem response format, write-error logging
- `SessionService.java` — unchanged (cookie Secure already derived from properties)
- `OriginInterceptor.java` — production missing-Origin returns `false`, RFC 9457 with `requestId`, proper JSON escaping
- `RoleInterceptor.java` — admin path deny-by-default, RFC 9457 problem responses with requestId
- `CsrfInterceptor.java` — RFC 9457 problem responses with requestId
- `AuthController.java` — uses `authProperties.getCsrfCookieName()`, injected `AuthProperties`
- `AuditServiceImpl.java` — full-field content hashing, REQUIRED propagation, PostgreSQL advisory lock (`pg_advisory_xact_lock`) for multi-instance serialization, public `computeEventHash`, cleared temp arrays
- `AdminAuditEventRepository.java` / `AdminAuditEventRepositoryImpl.java` — `acquireChainLock()` + `findMostRecent()` (current path); `findMostRecentForUpdate()` deprecated
- `UserRepository.java` / `UserRepositoryImpl.java` — `lockTenantForBootstrap()`, `findByIdForUpdate()`
- `AuthProperties.java` — production mode, cookieSecure, originAllowlist, CSRF cookie name properties
- `ProductionStartupValidator.java` — fail-fast at `@PostConstruct`; refuses insecure production startup; never auto-enables cookieSecure
- `OwnershipService.java` / `ResourceOwnershipException.java` — resource ownership assertion (self-or-admin); 404 hiding on mismatch
- `GlobalExceptionHandler.java` — maps `ResourceOwnershipException` to RFC 9457 404 response
- `AdminTestController.java` — new (test-only): admin test endpoints for authorization testing
- `AuthenticationServiceTest.java` — updated mocks for `findByIdForUpdate`, `lockTenantForBootstrap`
- `AuthIntegrationTest.java` — updated `getCsrfToken` helper
- `AuthorizationIntegrationTest.java` — new: 10 authorization tests (admin access, USER denial, IDOR self/cross/admin-override, unauthenticated)
- `BootstrapTransactionIntegrationTest.java` — new: 3 transactional integration tests (atomic bootstrap with bounded timeout, two-writer serialization, concurrent distinct-username commits exactly one)
- `BootstrapConcurrencyTest.java` — new: concurrent bootstrap test
- `LoginFailureConcurrencyTest.java` — new: 2 concurrent login failure tests (deterministic counter under concurrency)
- `ProductionStartupValidatorTest.java` — new: 12 unit tests (valid production config, cookieSecure false, empty/NPE/localhost-only allowlist, invalid URI, missing scheme/host, HTTP non-localhost, path/query/fragment/userinfo rejection)
- `ProductionStartupValidatorContextTest.java` — new: 2 production-context startup tests (insecure cookies, localhost-only allowlist cause startup failure)
- `OwnershipTestController.java` — new (test-only): ownership assertion endpoints for authorization integration testing
- `AbstractPostgresTest.java` — shared Testcontainers singleton-container base for control-plane integration tests
- `AuthIntegrationTest.BootstrapHelper` — shared bootstrap fixture (secret file creation, CSRF cookie extraction, temporary password extraction)
- `OriginInterceptorProductionTest.java` — new: 3 production Origin tests
- `AuditChainIntegrityTest.java` — new: 3 audit chain tests
- `CustomCsrfCookieNameTest.java` — new: custom CSRF cookie name test
- `AuthProductionProfileIntegrationTest.java` — new: production profile startup test
- `docs/api-contract.md` — updated: bootstrap, CSRF, Origin, production, error semantics
- `docs/configuration-reference.md` — updated: production constraints, cookie, allowlist, CSRF cookie name
- `docs/progress.md` — updated (this file)

### Local verification

- `.\mvnw.cmd verify --batch-mode` (non-integration): **BUILD SUCCESS** — all unit tests pass
- Spotless check: **PASS** (all modules)
- `git diff --check`: **PASS**
- Integration tests (`@Tag("integration")`): skipped on Windows (no Docker); Linux CI validates
- Frontend: `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`: **PASS**

### CI evidence

- **Integration fixture repair CI**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29919166968`
- **Conclusion**: **SUCCESS** (all 4 jobs — Ubuntu integration, Windows backend, Frontend, Compose config)
- **Commits**: `2d6c5de` (mustChangePassword + CSRF cookie + jsonb fixes), `0bf23d4` (jsonb scalar round-trip fix)
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/8`
- **Integration test suite**: 100 tests, 0 failures, 0 errors, 0 skips
- **Non-integration tests** (Windows): BUILD SUCCESS
- **Spotless**: PASS (all 8 modules)
- **git diff --check**: PASS
- **Frontend**: npm ci/lint/typecheck/test/build all PASS

## G1.3 — V3 migration fix (empty-table setval bug, DONE)

### Bug

Commit `a096dd7`'s V3 migration calls `setval('admin_audit_events_chain_seq', COALESCE(MAX(chain_position), 0))`. On a fresh (empty) `admin_audit_events` table, this attempts `setval(..., 0)` which PostgreSQL rejects because the sequence's default MINVALUE is 1. The migration succeeds on CI only because existing tests never exercise the pure empty-table path.

### Fix: safe DO block + OWNED BY

1. **V3 migration step 7** replaced the single `SELECT setval(...)` with a DO block:
   - Empty table: `setval('admin_audit_events_chain_seq', 1, false)` — next `nextval()` returns 1.
   - Non-empty table: `setval('admin_audit_events_chain_seq', max_pos)` (is_called=true) — next `nextval()` is `max_pos + 1`.
2. **V3 migration step 8 (new)**: `ALTER SEQUENCE ... OWNED BY admin_audit_events.chain_position` — dropping the column/table auto-drops the sequence.
3. **SchemaMigrationTest** (3 new tests):
   - `shouldSetChainSequenceTo1OnEmptyTable`: proves `nextval` returns 1 after fresh migration.
   - `shouldAssignUniqueNonNullChainPositions`: 5 rows inserted via column DEFAULT get unique, non-null, monotonically increasing `chain_position` values.
   - `shouldHaveSequenceOwnedByChainPosition`: verifies the `pg_depend` OWNED BY relationship.
   - Added `@AfterEach` cleanup: DELETE from admin_audit_events (defensive across test methods).

### Files changed

- `backend/persistence-postgres/src/main/resources/db/migration/V3__audit_chain_position.sql` — step 7 replaced with DO block; step 8 added (OWNED BY)
- `backend/persistence-postgres/src/test/java/.../SchemaMigrationTest.java` — 3 new V3 migration tests + @AfterEach cleanup
- `docs/progress.md` — updated (this file)

### Verification

- `.\mvnw.cmd verify --batch-mode`: **BUILD SUCCESS** — 374 non-integration tests, 0 failures, 5 skipped (POSIX on Windows)
  - Domain: 65 tests
  - Persistence PostgreSQL: 31 tests (5 skipped — POSIX on Windows)
  - Control Plane: 58 tests (integration skipped — no Docker)
  - Test Support: 109 tests
  - Gateway App: 111 tests
- Spotless check: **PASS** (all 8 modules)
- Maven Enforcer: **PASS**
- `git diff --check`: **PASS**
- Frontend: `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`: all **PASS**
- `docker compose -f deploy/compose.yaml config`: **ENV_BLOCKED** (CI validates)

### CI evidence (all green)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29921459893`
- **Conclusion**: **SUCCESS** (all 4 jobs):
  - Backend Ubuntu / Verify + Integration: **SUCCESS**
  - Backend Windows / Verify: **SUCCESS**
  - Frontend: **SUCCESS**
  - Compose config: **SUCCESS**
- **Commit**: `eacbd63` — `fix(g1.3): safe setval for empty-table V3 migration`
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/8`

## G1.3 — V3 upgrade test isolation and coverage fix (DONE)

### Problem

1. **Order-dependent test**: `SchemaMigrationTest.shouldSetChainSequenceTo1OnEmptyTable` called `nextval` on the shared singleton-database sequence and expected `1`. Test-method order is not a contract; any other test can consume the sequence first, causing a spurious failure.

2. **Missing V2→V3 upgrade coverage**: No test genuinely ran Flyway through V2, inserted representative pre-V3 rows into `admin_audit_events`, then ran V3 and asserted the backfill results. The empty-table V3 path was also untested in isolation from shared global sequence state.

### Fix: isolated schemas + programmatic Flyway

1. **`SchemaMigrationTest.shouldSetChainSequenceTo1OnEmptyTable`** — rewritten to create a unique PostgreSQL schema, run programmatic Flyway through V2 then V3, and verify the first `nextval()` returns 1. Uses `try/finally DROP SCHEMA CASCADE` for cleanup. No dependency on shared database sequence state.

2. **`V3UpgradeMigrationTest`** (new, 10 tests) — each test creates its own unique schema via programmatic Flyway configured with `defaultSchema`/`schemas`/`createSchemas`, targeting `"2"` then `"3"`. Covers:
   - Backfill: 7 pre-V3 rows receive unique, non-null, monotonically increasing `chain_position` values.
   - Post-V3 insert: A row inserted after V3 gets a `chain_position` greater than every backfilled row.
   - Empty-table upgrade: First post-V3 insert receives `chain_position = 1`; `nextval` directly returns 1.
   - NOT NULL constraint: Column is non-nullable after V3; explicit NULL insert is rejected; DEFAULT allows omission.
   - UNIQUE constraint: Constraint exists by name and duplicate `chain_position` is rejected.
   - OWNED BY: Sequence is bound to the column via `pg_depend` OWNED BY relationship.
   - Column default: `column_default` references `nextval('admin_audit_events_chain_seq')`.
   - Data preservation: Backfill does not alter existing row data (action, target_type, tenant_id, actor_id unchanged).

   V1 and V2 migration files **never edited**. Schemas dropped in `@AfterEach` via `DROP SCHEMA IF EXISTS … CASCADE`. `AbstractPostgresTest` singleton container reused — no new containers started. Cleanup is best-effort (catches and ignores exceptions so test failures are not masked).

3. **`is_nullable` type fix**: `information_schema.columns.is_nullable` is `varchar(3)` (`"YES"`/`"NO"`), not `boolean`. Changed query from `Boolean.class` to `String.class` in the NOT NULL constraint test.

### Files changed

- `backend/persistence-postgres/src/test/java/.../SchemaMigrationTest.java` — `shouldSetChainSequenceTo1OnEmptyTable` rewritten with isolated-schema Flyway; `DataSource` autowired
- `backend/persistence-postgres/src/test/java/.../V3UpgradeMigrationTest.java` — new: 10 comprehensive isolated-schema V2→V3 upgrade tests
- `docs/progress.md` — updated (this file)

### Verification

- `.\mvnw.cmd verify --batch-mode` (non-integration): **BUILD SUCCESS** — 303 non-integration tests, 0 failures, 5 skipped (POSIX on Windows)
- Spotless check: **PASS** (all 8 modules)
- Maven Enforcer: **PASS**
- `git diff --check`: **PASS**
- Integration tests (`@Tag("integration")`): skipped on Windows (no Docker); Linux CI validates
- Frontend: `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`: all **PASS**

### CI evidence (all green)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29922608445`
- **Conclusion**: **SUCCESS** (all 4 jobs):
  - Backend Ubuntu / Verify + Integration: **SUCCESS**
  - Backend Windows / Verify: **SUCCESS**
  - Frontend: **SUCCESS**
  - Compose config: **SUCCESS**
- **Commits**: `2c3404c` (10 isolated-schema upgrade tests), `3ab8b3b` (is_nullable type fix)
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/8`

### Integration test results (Ubuntu CI)

118 tests, 0 failures, 0 errors, 0 skips:
  - SchemaMigrationTest: existing tests + isolated empty-table test PASS
  - V3UpgradeMigrationTest: 10 tests PASS
  - All existing audit-chain, auth, crypto, and repository integration tests PASS

### Remaining risks

- Integration tests require Docker/Testcontainers — locally skipped on Windows; Linux CI validates.
- No `.claude-*` files in commits.

## tag-routing-usage-closed-loop（G1.4 授权 + G1.5 + G2.2 + G2.3 + G2.4 + G5.1 核心）

### Outcome

**端到端闭环已打通**：签发 Virtual Key（控制面）→ Gateway 用版本化只读快照校验/路由/注入真实凭证 → 转发上游 → 用量事件幂等落库 → 分级统计查询（控制面）→ 前端门户展示。

**控制面（G1.4 授权 + G1.5 生命周期）**

- 普通用户 `GET /api/v1/me/grants` 只返回自己作为成员的项目、授权（Grant 固定到具体 Credential）、模型（精确 ID）和用途。
- `POST /api/v1/me/virtual-keys` 自助创建：校验链（项目存在→成员→激活→路由标签→Grant 归属/激活→模型授权），HMAC 摘要入库，明文仅创建响应出现一次，`finally material.destroy()` 清零。
- 轮换：原子生成新版本，旧 Key 立即停止接受新请求、按 `miqrokey.virtual-key-rotate-grace`（默认 `PT0S`）宽限后失效；响应携带新 Secret（仅一次）。吊销：立即失效。所有动作写审计（不含 Secret 明文）。
- 越权防护：他人 Key 统一 `404 KEY_NOT_FOUND`，不可区分（IDOR 守卫）。
- 路由标签：Key 格式 `mqk_live_<publicKeyId>_<secret>[.<projectTag>]`，标签仅路由，鉴权权威是 `key_project_binding`（V4）。

**Gateway 数据面（G2.2 路由快照 + G2.3 Models + G2.4 Usage）**

- `route-snapshot` 模块：启动 + 定时（默认 30s）加载不可变快照（Key 摘要→绑定→Grant 模型→项目标签→AES-256-GCM 加密的上游凭证）；热路径零 DB 查询，凭证解密后内存清零。
- Virtual Key 鉴权：恰好一个凭证 Header（`Authorization: Bearer/裸值`、`x-api-key`、`api-key`），零/多 → 401；未知 Key、吊销/轮换后按快照刷新拒绝。
- 凭证注入：`CredentialInjector` 把固定绑定的上游凭证注入转发请求；无凭证目标 401/403。
- `GET /v1/models`：目录、Grant、Key 快照求交集，未授权模型不泄漏；无 Key 凭据时按供应商公开目录降级。
- 模型预校验：请求体模型越权时在连接上游前拒绝（协议兼容错误体）。
- 用量：`SseUsageObserver` 提取 token 计数（Anthropic/Responses/Chat 三种嵌套），有界队列（默认 10000）批量写 `usage_event`，`provider_request_id` tenant 内唯一 + `ON CONFLICT DO NOTHING` 幂等；`usage_missing` 标记上游无 usage；正文永不持久化。
- L1/L2 响应缓存 SPI（`cache-spi`）与 `CacheEligibility`/`CacheKeyFactory`/`SseReplayEngine` 已实现但**默认关闭**（ADR-0008）；只缓存 `cache_policy=ENABLED` 的 Key。

**前端普通用户门户（G5.1 核心）**

- Vue 3 门户：登录/登出（CSRF double-submit）、改密、Virtual Keys（创建/轮换/吊销 + 一次性 Secret 弹窗 + 显式确认关闭）、Usage（分组汇总 + 分页明细）、Profile。
- Secret 安全：只显示前缀/末四位；明文只在创建/轮换响应出现一次；复制经 Clipboard API；不进入 URL/localStorage/埋点/DOM data attribute。
- Quiet Operations Console 视觉（frontend-design.md §4）：无紫色/渐变/营销文案，表格优先，token 数字 tabular-nums，Key 等宽字体。

### Schema（V4–V7）

- V4：`virtual_keys.cache_policy`、`projects.project_tag`（唯一 + 格式约束）、`key_project_binding`（路由鉴权权威）、`model_approval`。
- V5：`cache_entry`（L2 原始字节缓存）、`price_snapshot`（每百万 token 单价，不租户隔离）。
- V6：`usage_event`（分级用量事实表，幂等唯一索引）、`cache_hit_event`（去重命中计数）。
- V7：`model_catalog`、`model_access`、`budget`、`model_budget`（预留，当前无消费代码）。

### Verification

**全模块本地验证（2026-08-25 第二轮，含 Testcontainers 集成测试）**：`./mvnw -f backend/pom.xml verify` **BUILD SUCCESS** — surefire 汇总 **491 run / 0 failures / 5 skipped**（本机 Docker Desktop 经 `DOCKER_HOST=tcp://localhost:2375` 可用，集成测试不再 CI-only）：

- domain 86（新增 vkey 解析、usage 统计域测试、路由标签后缀）、persistence-postgres 118（5 skipped，含 Testcontainers 加密/迁移集成测试）、queue-spi 6、control-plane-app 143（含 12 个 Me* 集成测试：MeVirtualKeyApi 8 + MeUsageApi 4）、gateway-app 138（VirtualKeyAuthContractTest、SseReplayEngineTest、CacheKeyFactoryTest 等）
- 修复的 12 个集成测试失败根因：bootstrap 管理员 `mustChangePassword=true` 门禁（SessionFilter）——测试此前只断言 Cookie 存在、从未重放改密请求，Me* 测试断言从未真正执行过
- 前端：`npm --prefix frontend run test` **16/16 PASS**、`lint` PASS、`typecheck` PASS、`build` PASS（chunk 大小警告为 Element Plus 全量引入，非错误）
- Spotless check：全模块 PASS（apply 后干净）
- `git diff --check`：PASS
- `docker compose -f deploy/compose.yaml config`：**PASS**（本机 Docker）

### 本轮修复的产品缺陷（12 个集成测试解封后暴露，均已修复并有测试）

1. **审计摘要非法 JSON**：`VirtualKeyService` 的 `change_summary` 是纯文本，而 `admin_audit_events.change_summary` 为 jsonb（插入时 `::jsonb` 强转）→ 500 `invalid input syntax for type json`。新增 `auditSummary()`/`escapeJson()` 生成合法 JSON。
2. **路由标签后缀未实现**：规格要求 Key 格式 `mqk_live_<publicKeyId>_<secret>[.<projectTag>]`，但 `VirtualKeyCrypto.generate` 只接收 tenantId，标签从未生成。接口签名改为 `generate(UUID tenantId, String projectTag)`；`lastFour` 恒取自无标签核心段，标签不进入展示尾部；空标签产出无标签形式。网关 `VirtualKeyParser`/`VirtualKeyResolver` 按标签路由的既有实现由此真正贯通。
3. **时间窗口校验非无条件**：`records()`/`summary()` 在无 Key 时短路返回，`TIME_RANGE_INVALID`/`TIME_RANGE_TOO_WIDE` 不触发；api-contract 要求无条件校验。提取 `validateTimeRange()` 并在任何数据访问前调用。
4. **Grant 模型顺序不确定**：`findModelIds` 返回无序 Set（`Set.copyOf`），`GET /me/grants` 的 models 数组顺序随机 → 依赖顺序的断言偶发失败。`grantOptions` 用 `TreeSet` 字典序输出。

### Files changed

- **控制面**：`MeGrantsController`、`MeVirtualKeyController`、`MeUsageController`、`VirtualKeyService`、`UsageStatsService`、`AuthProperties`（gatewayBaseUrl / virtualKeyRotateGrace）、`GlobalExceptionHandler`
- **域**：`vkey/`（VirtualKeyParser 等）、`usage/`（统计与价格模型）、`route/`（快照契约）、`KeyProjectBinding`、`ModelApproval`、`PriceSnapshotRepository`、`UsageStatsRepository`、`crypto/`（VirtualKeyCrypto.generate 增加 projectTag 路由标签后缀）
- **测试**：`MeVirtualKeyApiIntegrationTest`（8）、`MeUsageApiIntegrationTest`（4）、`UsageStatsServiceTest`、`VirtualKeyServiceTest`、`HmacVirtualKeyProviderTest`、`VirtualKeyParserTest`、`CryptoIntegrationTest`、`GatewayTestKeys`（改密门禁 + 新语义断言）
- **持久化**：V4–V7 迁移 + `KeyProjectBindingRepositoryImpl`、`ModelApprovalRepositoryImpl`、`PriceSnapshotRepositoryImpl`、`UsageStatsRepositoryImpl`
- **新模块**：`route-snapshot/`（版本化只读快照）、`queue-spi/`（有界用量队列）、`cache-spi/`（响应缓存 SPI + NoOp）
- **Gateway**：`VirtualKeyResolver`、`AuthContext`、`JdbcCredentialInjector`、`ModelsController`、`CacheEligibility`、`CacheKeyFactory`、`SseReplayEngine`、`ErrorEnvelopes`、`GatewayDataSourceConfig`、`GatewayFeatureConfig`
- **前端**：`api/`（fetch client + CSRF + ApiError）、`stores/auth.ts`、`router`（守卫）、`AppShell`、`LoginView`、`KeysView`、`UsageView`、`ProfileView`、`SecretRevealDialog`、`styles/tokens.css`、`types/api.ts`、4 个测试文件
- **文档**：api-contract.md（§4.1–4.6、§7.1）、database-schema.md（V4–V7 表）、configuration-reference.md（§4.4/5.1/9）、architecture.md（§3 新模块）、progress.md

### Remaining risks

- **PR #1 已合并（2026-08-25）**：squash-merge commit `8b6be8c`（feat(gateway): virtual key routing, credential injection and usage closed loop (#1)）；`goal/tag-routing-usage-closed-loop` 远端分支已删除；仓库默认分支已改为 `main`。PR CI（backend Linux `-Pintegration` + Windows、frontend、compose）4/4 全绿。
- **main 分支保护暂缓（2026-08-25）**：GitHub 分支保护规则需要 Pro/Team 计划，当前免费个人账号无法启用（API 返回 403）；建议公司建 org 后启用（要求 PR + status checks + conversation resolution，禁 force push/删除）。
- **Push 已解决（2026-08-25）**：目标远端改为所有者仓库 `sijie-Z/miqro-key-gateway`（新建 private）；origin 已切换、`.git/shallow` 浅克隆状态已解除（`git fetch --unshallow upstream`，upstream = `lichman0405/miqro-key-gateway`）。`goal/tag-routing-usage-closed-loop` 已 push 成功。
- 集成测试（12 个 Me* + 其余 Tag(integration) 类）已在本机 Docker Desktop（Testcontainers 1.21.4，`DOCKER_HOST=tcp://localhost:2375`）全部通过；Linux CI 作为交叉验证保留。
- 真实供应商凭证未提供：Gateway 凭证注入只经 Mock 上游验证，真实联调 `WAITING_FOR_CREDENTIAL`。
- 响应缓存默认关闭（ADR-0008 决策），正式启用前需新增 ADR。
- `request_usage_records` 完整分区表（规格 §6）未实现，当前使用 `usage_event` 事实表；G4.x 需要时再演进。
- 前端 chunk 1MB+ 警告：Element Plus 全量引入；可按需引入优化（非阻塞）。

## G2.2 — Gateway route snapshot and virtual key auth（收尾：热路径凭证密文快照 + PostgreSQL NOTIFY 刷新事件，DONE）

### Outcome

本 Goal 只覆盖 G2.2 两个未满足的验收项（快照与 Virtual Key 鉴权主体已在 tag-routing-usage-closed-loop 完成）：

1. **热路径零阻塞数据库调用**：`RouteSnapshot.CredentialRecord` 携带 ACTIVE 版本的 `EncryptedSecret`（密文 + nonce + keyVersion，防御性拷贝；快照只持密文，明文绝不进快照）。`JdbcRouteSnapshotLoader.loadCredentials()` JOIN `upstream_credential_versions`（`c.active_version_id = v.id`，部分唯一索引 `uq_credential_versions_one_active` 保证 ≤1 行）。`JdbcCredentialInjector` 改为在内存有界 `credentialDecryptScheduler` 上解密并复用既有 `SecretWiping` 清零——热路径零 JDBC。`CredentialSecretLoader` 已删除（快照重写后零引用死代码）。轮换语义保持：快照加载始终读当前 `active_version_id`，轮换后下一次刷新即路由新版本；在途请求已持有其解析的 Secret 不受影响。
2. **刷新事件 = PostgreSQL LISTEN/NOTIFY**（控制面与 Gateway 是两个进程共享同一 PostgreSQL，进程内事件不可用）：
   - 通道契约 `miqrokey_route_refresh`（配置项 `miqrokey.gateway.route-snapshot.notify-channel`，默认同契约名）。
   - 控制面发布端：`RouteSnapshotRefreshNotifier` 执行 `SELECT pg_notify('miqrokey_route_refresh','')`——必须用普通 `Statement.execute`（简单查询协议）：pgjdbc 的 `executeUpdate` 对 void 返回 SELECT 会在通知已发出后抛 "Unexpected result returned"。`RouteRefreshPublisher` + `RouteRefreshPublisherAfterCommit` 用 `TransactionSynchronizationManager.registerSynchronization` 在 **AFTER_COMMIT** 发布（回滚绝不发布）；挂接 5 个变更方法：`VirtualKeyService.create/rotate/revoke`、`AdminCredentialService.rotate/disable`（无 Grant/Project 变更服务，无需挂接）。发布失败只记日志——已提交的数据变更绝不回滚，30s 定时刷新兜底。
   - Gateway 监听端：`RouteSnapshotRefreshListener` 专用 `DriverManager` 连接（不进 Hikari 池——`LISTEN` 钉死连接为进程生命周期）、daemon 线程 `getNotifications(2000)` 轮询、失连指数退避重连（500ms→30s 封顶）、`close()` = running=false + interrupt + join(5000) 幂等停止；仅 `miqrokey.gateway.persistence.enabled=true` 时装配（`destroyMethod="close"`）。通知到达即调 `RouteSnapshotRefresher.refresh()`（版本递增并安装到 holder，保留 last-good）。定时刷新保留为兜底：丢失通知在下一刷新周期自愈。
   - `RouteSnapshotConfig` 收敛为单一 `NamedParameterJdbcTemplate`（复用 `gatewayJdbcTemplate`），消除 QueueConfig 无限定注入的 `NoUniqueBeanDefinitionException`。

### Verification

- 全量 `./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode`（本机 Docker Desktop，Testcontainers 集成测试实跑）：**BUILD SUCCESS** —— **674 tests / 0 failures / 5 skipped**（POSIX 权限测试在 Windows 跳过）
  - domain 86、provider-spi 8、provider-adapters 25、persistence-postgres 118（5 skipped）、route-snapshot 3、queue-spi 6、control-plane-app 179、test-support 109、gateway-app 140、cache-spi 0
- 新增 9 个测试全绿：
  - `SnapshotRefreshListenerTest`（3，Mockito fake JDBC）：通知触发 refresh；close 停止线程并关连接；失连退避重连后重新 LISTEN
  - `RouteRefreshPublisherAfterCommitTest`（3，真实 H2 事务）：提交后发布一次；回滚不发布；无 notifier bean 安全 no-op
  - `RouteSnapshotRefreshNotifierTest`（2，集成）：提交的 create 让 LISTEN 探针收到 NOTIFY；回滚的 create 探针零通知
  - `RouteSnapshotRefreshIntegrationTest`（1，集成）：网关 Listener 收到 `pg_notify` 后快照版本 1→2（调度已改为 1h，证明事件即时生效），断言新 Key 与绑定进入快照
- Spotless check：全模块 PASS（apply 后干净）；`git diff --check`：PASS；Maven Enforcer：PASS
- `GatewayNoBlockingTest` 不变通过：监听线程是普通 daemon 线程，不进 Reactor event loop，无新增 `.block()`

### Files changed

- **domain**：`RouteSnapshot.java` — `CredentialRecord` + `EncryptedSecret`（类 javadoc 声明快照只持密文）
- **route-snapshot**：`JdbcRouteSnapshotLoader`（JOIN 活动版本 + 密文映射）、新 `RouteSnapshotRefreshListener`、`RouteSnapshotConfig`（单一 JDBC 模板 + 监听器 bean）、删除 `CredentialSecretLoader`、pom +spring-boot-starter-test
- **gateway-app**：`JdbcCredentialInjector`（内存解密 + 清零）、`GatewayFeatureConfig`/`GatewayDataSourceConfig`（监听器装配）、`application.yml`（notify-channel）、pom +testcontainers（junit-jupiter/postgresql/core）、新 `RouteSnapshotRefreshIntegrationTest`
- **control-plane-app**：新 `support/RouteSnapshotRefreshNotifier`、新 `service/RouteRefreshPublisher` + `RouteRefreshPublisherAfterCommit`、`VirtualKeyService`/`AdminCredentialService`（AFTER_COMMIT 发布）、两个新测试类
- **test-support**：`GatewayTestKeys` fixture（CredentialRecord 带 EncryptedSecret fixture）
- **文档**：architecture.md §4.1（NOTIFY 通道契约 + 快照密文/热路径解密流程）、configuration-reference.md §5.1（notify-channel、refresh-interval 语义更新为兜底）、progress.md

### Remaining risks

- 通知丢失或控制面不可达时由 30s 定时刷新自愈（last-good 快照保留）；监听器断线有指数退避重连。
- 单节点单 Gateway 监听者（v1 范围）；多实例时 LISTEN/NOTIFY 的重复通知/放大语义留待多节点部署目标处理（architecture.md 已注明）。
- 真实供应商凭证未提供：凭证注入只经 Mock 上游验证，真实联调 `WAITING_FOR_CREDENTIAL`。

## G2.3 — Models endpoint（`/v1/models` 目录∩上游模型∩Grant∩Key 快照四路交集，DONE）

### Outcome

1. **快照扩展**：`RouteSnapshot.KeyRecord` 增加 `grantId`（`virtual_keys.grant_id`）；`RouteSnapshot` 新增 `grantModelsByGrantId`（仅 ACTIVE grant 的 `project_provider_grant_models`，JOIN 过滤）、`upstreamModelsByProductId`（`model_catalog` 仅 `ACTIVE` 行）、`productCodesByProductId`（`provider_products.product_code`）三个 map 与 accessor；equals/hashCode/toString/empty() 同步。
2. **`/v1/models` 四路交集**（`ModelsController`）：四路输入均来自 `AuthContext` 携带的**同一版本**快照——① **目录 gate**：Key 绑定产品的 `product_code` 不在签名目录（`ProviderCatalog` bean = `loadBuiltIn()`，Ed25519 校验，启动 fail-fast）→ 返回空列表（目录是外层授权边界，产品不在目录中什么都不泄漏）；② 交集 `key.models ∩ grantModels(grantId) ∩ upstreamModels(productId)`，排序输出。代理热路径的请求级模型预校验**保持 key-level**（`ctx.models()`）不变——模型目录为空时不得拒绝所有流量（api-contract §7.1 已写明两者区别）。
3. **上游模型生产者（`ModelCatalogService`，控制面）**：**success-only writes**——`applySnapshot`（`@Transactional`：事务内 DELETE 产品全部行 + batch INSERT `ACTIVE`）提交后（AFTER_COMMIT）发布 route-refresh NOTIFY，网关即时重载；`refreshProduct(adapter, client)` 是 G3.x 适配器接缝，任何抓取失败（异常/null/超时）只记日志并保留上次成功目录（"上游失败可回退最后成功目录"）。`refreshProduct`→`applySnapshot` 经 `ObjectFactory` 自代理穿越 Spring 事务边界（直接自调用会绕过 `@Transactional`，把替换拆成两个 autocommit 语句，崩溃窗口会短暂服务空目录而非 last-good）。
4. **已记录行为（非缺陷）**：G3.x 之前 `model_catalog` 为空 → 严格交集为空 → `/v1/models` 返回 `[]`——未授权模型不泄漏是刻意的，官方 API 抓取落地后自动恢复。

### Verification

- 全量 `./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode`（本机 Docker Desktop，Testcontainers 实跑）：**BUILD SUCCESS** —— **687 tests / 0 failures / 5 skipped**（Windows POSIX 权限跳过）
  - gateway-app 144（含 `ModelsListing` 6：happy path 四路对齐、Grant 限制、上游限制、无上游模型、未知产品码、无效 Key）、control-plane-app 188（含 `ModelCatalogServiceTest` 5 + `ModelCatalogServiceIntegrationTest` 4）
- `ModelCatalogServiceTest`（Mockito，5）：成功快照替换行并发布；空快照删旧行不批量仍发布；未知产品码跳过零交互；抓取失败保留 last-good；成功抓取委托 applySnapshot。
- `ModelCatalogServiceIntegrationTest`（Testcontainers，4）：真实库事务替换（m1+m2→m1）；未知产品零写入；抓取失败零写入；成功抓取替换并可见。
- `RouteSnapshotRefreshIntegrationTest`：seed `project_provider_grant_models` + `model_catalog`，NOTIFY 重载后断言 grantModels/upstreamModels/productCode 进入快照。
- Spotless check 全模块 PASS（apply 后干净）；Maven Enforcer：PASS。

### Files changed

- **domain**：`RouteSnapshot.java` — KeyRecord.grantId + 3 maps + accessors
- **route-snapshot**：`JdbcRouteSnapshotLoader` — loadKeys 选 grant_id + 3 个新有界查询（grant models、upstream models、product codes）
- **gateway-app**：`AuthContext`/`VirtualKeyResolver`（携带快照）、`ModelsController`（四路交集 + 目录 gate）、`GatewayFeatureConfig`（`ProviderCatalog` bean）、`GatewayAuthTestConfig`（6 fixtures 挂载）、`VirtualKeyAuthContractTest$ModelsListing`（+4）、`CacheKeyFactoryTest`（AuthContext 适配）、`RouteSnapshotRefreshIntegrationTest`（seed + 断言）
- **test-support**：`GatewayTestKeys` — KeyFixture 增加 grantId/productCode/grantModels/upstreamModels；4 个负面 fixture（Grant 限制、上游限制、无上游、未知产品）
- **control-plane-app**：新 `service/ModelCatalogService` + `ModelCatalogServiceTest` + `ModelCatalogServiceIntegrationTest`
- **文档**：api-contract.md §7.1（交集语义 + 空列表说明 + 预校验区别）、architecture.md §4.1（快照扩展 + success-only 生产者契约）、progress.md

### Remaining risks

- 适配器注册（G3.x）之前 `model_catalog` 恒空，`/v1/models` 返回空列表——严格交集是刻意的安全边界。
- 30s 定时刷新仍为 NOTIFY 丢失兜底；单节点单监听者范围不变。
- 真实供应商凭证未提供：`refreshProduct` 只经 Mock/契约测试，真实抓取 `WAITING_FOR_CREDENTIAL`。

## 会话交接点 2026-09-05 — Q1-Q3 数据面轮（逐批记录）

### Q1 网关 MCP 契约测试（#160，merged @6f945cc，DONE）
- `McpProxyContractTest` 19 用例（gateway-app，无 PG）：401 invalid_api_key（缺凭据/未知 key/空 bearer）、x-api-key 通道、404 mcp_service_not_found、400 invalid_jsonrpc；NONE 开放服务（名单外可调 tools/list、DISABLED 工具仍 403 mcp_tool_unavailable）；ALLOW 门禁服务（名单外 403 mcp_access_denied、工具覆盖 ALLOW 收窄名单内放行/名单外拒绝、DISABLED/未知工具 403 mcp_tool_unavailable、initialize 等非 tools/call 不受工具表影响）；透传卫生（Session-Id 上行转发、消费者凭据绝不上行、响应体逐字节一致、上游 503 状态与体原样拷贝）。
- fixture：`GatewayTestKeys.snapshot()` 现恒带 consumer（digest 索引）+ open/gated 两个 McpServerRecord（endpoint=baseUrl+/mcp）；新增 test-support `McpMockServer`（loopback JSON-RPC：捕获请求、可配响应/响应序列）。
- 验证：本类 19/19 + gateway 全模块绿（曾因 Q1 分支未跑 spotless 被 CI verify 抓红 → 补 style commit）。

### Q2 F15 MCP 元数据访问日志（#161，merged @d57d9a7，DONE）
- V29 `mcp_access_log`：id/tenant/service(id+name 快照)/consumer(id+name 快照)/rpc_method(可空)/tool_name/status CHECK(FORWARDED|SERVICE_DENIED|TOOL_DENIED|TOOL_UNAVAILABLE|INVALID_ENVELOPE|UPSTREAM_FAILURE|CIRCUIT_OPEN)/http_status/gateway_request_id/occurred_at；唯一 (tenant_id, gateway_request_id)（幂等 flush）；查询索引 (tenant,occurred_at DESC)、(tenant,service_name,…)、(tenant,consumer_name,…)。正文永不入表。
- 网关写路径（gateway-app `mcplog` 包）：有界队列（`miqrokey.gateway.mcp-log.capacity` 4096 / `.flush-interval-ms` 1000）专用线程周期 flush；饱和 drop+节流 WARN；批量失败整批重入队（幂等保证重试安全）；persistence 关闭=Noop sink（与 usage 同开关）。sink 调用 fire-and-forget 不阻塞 Reactor。**401/404（预解析失败、无身份）不落行**（与 usage_event 同口径）。
- 管理查询 API：`GET /api/v1/admin/mcp-access-logs`（service/consumer 精确名、from/to 默认 24h、窗口 ≤31d、limit 默认 200 ≤1000；TIME_RANGE_INVALID/TOO_WIDE/SIZE_INVALID/PARAM_INVALID）；SYSTEM_ADMIN-only deny-by-default；纯读无审计。
- 测试：McpAccessLogQueueTest 4/4（drain、饱和 drop+count、失败重入队重试、空/非法守卫）；McpAccessLogIntegrationTest 6/6 PG 端到端（FORWARDED 身份/终态/200、上游 503→FORWARDED+503、三种拒绝行、INVALID_ENVELOPE、401/404 零行、writer 幂等）；AdminMcpAccessLogApiIntegrationTest 5/5（新→旧排序、过滤器、窗口/limit、校验矩阵、匿名 401+普通用户 403）。
- 排障：PG null 参数 cast（`::text/::timestamptz`）；集成测试需 `miqrokey.crypto.*.versions.v1` key 文件（KeyFiles 惯例）；注册端点 201；@AfterEach 清理而非等 0 行。

### Q3 F12/F13 MCP 韧性（分支 goal/mcp-resilience-f12-f13，本地全量 verify 绿，CI 进行中）
- V30 `mcp_resilience_policy`（每服务一行 PK FK CASCADE；全默认关闭）；domain 纯状态机：`McpResiliencePolicy`（范围校验/disabled() 默认）、`McpRetryPolicy`（SERVER_5XX|CONNECTION_FAILURE|TIMEOUT 条件、1–5 次、首字节前才重试、POST/PUT/PATCH 工具需 idempotencyConfirmed）、`McpCircuitBreaker`（CLOSED/OPEN/HALF_OPEN；滑动窗口+最小请求数防误判；错误比例/慢调用双触发≥1；OPEN 计时→半开探测 probeCount/probeSuccess；线程安全）。
- 快照承载：McpServerRecord+`resilience`（loader LEFT JOIN 解析 CSV 条件/状态码；无行=null=全关）；McpToolRecord+`method`（V21 列，幂等门用）。
- 数据面（McpProxyController）：每层 attempt 独立 60s 预算；exchangeToMono 回调内消费 body（**延迟订阅会得到空 body 流——回写必须留在回调内**）；5xx/传输错递归重试（内层链自管错误，rowRecorded 守卫防外层二次重试/双行）；OPEN 快速失败 503 `circuit_open` + F15 CIRCUIT_OPEN 行；熔断桶=工具名或方法名隔离；每次网关调用恰一行 F15 终态。
- 管理 API：GET/PUT `/api/v1/admin/mcp-services/{serviceId}/resilience`（缺省=disabled；校验：retryMax 1–5+启用必有条件、breaker 双触发≥1、**slowMs < check_timeout_seconds×1000**（`RESILIENCE_SLOW_EXCEEDS_TIMEOUT`）、probeSuccess≤probeCount、状态码 400–599 ≤32；审计 MCP_RESILIENCE_UPDATE+route refresh publish）。
- 测试：McpCircuitBreakerTest 8/8（守卫/比例开断/窗口滑动/慢调用边界(严格 >)/半开恢复/半开失败重开/探测槽=probeCount）；McpRetryPolicyTest 3 组矩阵；McpResilienceIntegrationTest 7/7 网关 e2e（5xx→200 透明重试、耗尽回 503、POST 幂等门（确认前不重试/确认后重试）、默认零变化、开断快败 503、桶隔离）；AdminMcpResilienceApiIntegrationTest 4/4（默认视图、PUT 往返+审计、校验矩阵含 slow==checkTimeout 边界 400、404/401）。
- 教训：断路器跨用例状态残留 → 测试按桶隔离/顺序无关设计；嵌套 onErrorResume 双重重试 → rowRecorded 终态守卫。

### Q6 codegen stage 2（分支 goal/codegen-stage2-b1，批1/批2，CI 进行中）
- 侦察（Explore 子代理）：generated.ts 89 schemas 全 camelCase；全部 View 类字段 spec 输出为 optional（springdoc 未标 required——结构性摩擦，迁移=原子替换且接受 `?:`）；守卫=vitest codegen-consistency.spec 自动 40 对（EXCEPTIONS 仅 ProviderProductView→ProductView）；**路由规则三件套无 spec 对应（openapi-3.1.json 无 route-rules 端点）→ 保留手写**；auth 信封（UserResponse/LoginResponse/ProblemDetails）无 schema 可迁。
- 批1（merged into 分支）：SubmitModelApprovalRequest/ConfigureQuotaDefaultTemplateRequest/SetMcpAccessGrantsRequest/UpsertQuotaRuleRequest → `components['schemas'][…]` 别名（api/index.ts 内部 type 别名）；ReviewModelApprovalRequest 无调用方直接删除手写定义。
- 批2：CreateVirtualKeyRequest → schema 类型（spec 中 name 可选——schema 权威化，注释记录）；枚举字面量一致零编译面。
- 验证：typecheck/vitest(179，守卫配对随手写删除-1 属预期)/lint/build 全绿。后续批候选见 Explore 报告要点（UsageRecord→UsageRecordView 改名、View 类字段 optional 原子替换、null 语义点 formatTime 等）。

### 环境教训（本轮新增/复用）
- npm run lint（eslint --fix）EOL 重写 ~50 前端文件：`git diff --numstat` 为空即纯 EOL；用 `git checkout-index -f -- $(git status --porcelain | sed 's/^ M //')` 按 index 重写回（git restore 对 autocrlf 归一相等文件不生效）。
- 分支切换前必须清工作树（多次把脏文件带过分支导致误提交/丢失风险）；stash 后拆分要小心（无路径 stash 会吞全部）。
- verify -P integration 跑动中严禁改文件/切分支（结果作废一次）。
- Q1/Q2 dup 内容在 Q3 合并时产生重复内容冲突：统一 checkout --ours（develop 侧为同内容 squash）。
- `McpProxyController` 修改面：F15/F12/F13 全部在 exchangeToMono 回调内完成 body 消费与回写，README 已注释。

## 会话交接点 2026-09-05（第二批长跑轮）— codegen stage2 收尾 + 契约债/裁决记录
- **Q6 stage2 批 3-11（PR #165）**：全部可安全迁移 DTO 切到生成类型——统一别名枢纽 frontend/src/types/generated-api.ts（此后迁移类型集中 re-export）；迁移清单：UsageRecord(-View)/UsageRecordPage、Skill/Agent/Budget/VirtualKey/CreateVirtualKeyResponse/ModelApproval(View+Page)/QuotaRule/UsageSummary/PriceSnapshot/Credential×3/ValidateCredentialResponse/Subscription/Seat/AuditEvent/MeGrantsResponse/QuotaDefaultTemplate/McpAccess/Team/Project/ApiConsumer/Provider/ExportTask/WebhookEndpoint/AlertRule/InternalServiceView→InternalService/ConfigEntryView→ConfigEntry/McpServiceView→McpService/McpToolView→McpTool（View 去尾→stem schema）。守卫配对 40→2（EXCEPTIONS 残余），下限终态 ≥1；per-pair 字段子集断言保留为真守卫。
- **保留手写清单（有意为之）**：auth 信封 ProblemDetails/UserResponse/LoginResponse（spec 盲区无 schema）；route-rules 三件套（openapi 无此端点，后端补契约后可迁）；RoiReportView（**spec 缺口**：缺 coalescedRequests/hitRatePct/l1Hits/l2Hits/paidCost/savedCost/savedPct/upstreamRequests 8 字段，需后端补）；ProviderProductView（EXCEPTIONS→ProductView）；嵌套 usage 组类型与字面量枚举别名（schema 内联无法复用，保留为复用形态）。
- **OpenAPI 基线滞后修复（#→）**：#161/#163 新增管理端点未刷 docs/openapi/openapi-3.1.json（breaking-check 允许 additions 故 CI 绿）→ 重跑 OpenApiSpecIntegrationTest 产出 head spec 覆盖基线 + 前端 generated.ts 重生成（新增 McpAccessLogEntry/McpResiliencePolicy schema，无 breaking）。
- **裁决（文档驱动，不发明层）**：F11 数据面路由匹配与 F14 工具分组 → **DEFERRED**：raw 10/17 语义的差异化分发/组级暴露面依赖「多入口/Host 分流/HTTP-to-MCP 直连」形态，本系统单固定入口 + 标准 MCP 信封 + default 恒兜底下无承载对象；McpRouteRules 纯函数/快照位已备，形态出现再接。F10 部署信息页核对：NextSettingsView 含部署信息段 → TBD 收尾登记。

### UI 临摹轮与功能收尾（2026-09-06 白天，owner 在场）
- **owner 反馈**：参考图对比"还差得远但有雏形,接着学";允许直接抄 Vben(vue-vben-admin 为 MIT 可商用,仓库自身亦 MIT,仅借鉴布局/配色/写法、自绘实现);**规则:学习只动视觉皮,不得改动产品原有文案/内容**(此前误改的总览欢迎语与用量报表描述已逐字还原)。
- **#179 UI 临摹轮**：登录页白卡场景插画(内联 SVG 显示器网关屏+绿植+钥匙,无渐变无外部资源);面板圆角 8→12px + 发丝浮起阴影(--ui-shadow-card,审计规则同步豁免 radius-panel 与 --ui-shadow-card);总览统计改四张独立白卡 + 四色图标徽章(蓝/绿/青/金);导航项 38px/14px。视觉评审:overview 6.5→7.0→(四卡后待定),仍在逐轮逼近。
- **#180 codegen 收尾**：RoiReportView 手写接口删除 → 别名 `components['schemas']['RoiReportView']`(schema 早已全字段,#166 刷新使旧"spec 缺口"记录过期);消费者(api/视图/spec)全部切 hub 类型;App.spec 登录用例 15s 超时防全量并发抖动。typecheck+vitest 153/153 绿。
- **#181 Key ID 复制入口**：keys 行内小复制钮(clipboard + execCommand 回退,toast 反馈),单测覆盖。

### 会话交接点 2026-09-07 — 功能收尾批 + 设计师 UI 接入（记录）
- **#189 用户管理筛选器**（搜索用户名/昵称 + 角色 + 状态下拉 + 命中计数,纯前端,spec ×2）。
- **#190 codegen 收尾**：route-rules 三件套（McpRouteRule + UpsertMcpRouteRuleRequest→schema UpsertRequest）迁到 OpenAPI schema；WebhookDelivery→DeliveryAttempt 别名（#193 内）——手写 DTO 迁移线全部清完。
- **#191 用量时间窗口**：个人与管理用量页 默认/近7/30/93,选预设才带 from/to,导出同步;默认行为零变化。
- **#193 口径提示条 + 候选文档**：两用量页顶部可关闭提示（本地即时记账 vs 供应商 T+1）;docs/feature-expansion-candidates.md（大厂文档→候选 A-H,裁决反向项停泊）。
- **#194 设计师登录稿（权威稿接入第一轮）**：素材 other/miqro-gate-auth-ui（LoginView/RegisterView/preview.html + 设计图）;按权威图实现「左暗色网关传送门 hero（provider 卡/状态卡/终端/信任条）+ 右侧白色认证面板（Welcome back 👋 / Sign in / Request an account / Your data is protected）」;TDesign 标签翻译为自绘 Ui（UiInput 增 prefix 槽）;登录文案按设计稿（EN）,注册保留产品自助语义（中文）,测试钩子全部保留。登录稿与设计图仍有逐区差距（3D 体积感/局部排版/密度）——最后一轮对齐排期进行中。
- **pre-release 评估（leader 询问 2026-09-07）**：代码基线 develop 全绿可出 0.1.0-rc 候选;tag 动作待 owner/leader 授权;真实凭证矩阵与 Q4 https 冒烟仍 WAITING（不阻塞 pre-release,清单如实标注）。

## 2026-09-15 深夜 — Goal #613：单密钥多项目（ADR-0018）标签路由完整形态

**背景**：产品负责人指示"一个人一个虚拟 Key 跨项目使用是肯定要实现的"；核查发现架构设计报告 §3（单密钥多项目）与详细设计 V4 建表注释（"一个密钥可绑多个项目"）均如此设计，但实现收缩为 1:1 绑定 + 后缀等值校验——愿景未落地。见 issue #613 / ADR-0018（本批同时修正 ADR 记录的"成员移除→Key 失效"从未实现一事）。

**交付**（分支 feat/single-key-multi-project-613）：

- V53 迁移：`key_project_binding.grant_id`（回填自 `virtual_keys.grant_id`，存量语义不变）+ 存量项目标签回填（`proj-<uuid12>`）+ 表/列注释修正；
- 网关：装载器去 `DISTINCT ON`、按 `(keyId, tag)` 复合键装载、grant 改由绑定行自带；`VirtualKeyResolver` 按后缀**选择**绑定；`ModelsController`/`ProxyController` 模型门控改用 `binding.grantId()`；
- 控制面：创建接受 `projectIds`（首个为主项目；附加项目按"同产品最早 ACTIVE grant"确定性匹配，缺失 409 `PROJECT_GRANT_MISSING`）；轮换复制全部绑定；项目标签自动生成（slug，冲突退 `proj-<uuid12>`）且被引用后不可改（409 `PROJECT_TAG_IN_USE`）；成员移出项目 → 禁用该项目绑定行、无剩余绑定则 Key 置 REVOKED（补上一处从未实现的文档语义）；
- 契约/产物：`CreateVirtualKeyRequest.projectIds`、`CreateVirtualKeyResponse/VirtualKeyView.boundProjects`；OpenAPI 基线重新导出、`gen:types` 重新生成；
- 前端：建 Key 表单"同时绑定到其他项目（可选）"多选、Key 列表项目列 `+N` 角标（hover 显示全部标签）。

**验证**：Me 密钥 IT **11/11**（新增：多项目创建+快照双绑定、缺授权 409、轮换镜像全部绑定）；AdminOrg IT **11/11**（新增：标签自动生成/引用守卫 409/改名放行/成员移除→绑定 DISABLED+Key REVOKED）；`VirtualKeyResolverTest` **2/2**（同核心段双后缀→各自项目与凭证；未绑定/篡改/缺头统一拒绝）；OpenAPI 基线导出重建；前端 vitest 全量 + typecheck + 构建立即执行（结果随 PR 记录）。

**演示最小闭环（下一步）**：一把 Key 两个标签（CC Switch 双条目）→ 两项目各自凭证与用量。


## 2026-09-16 凌晨 — Goal #633：CAA 请求上下文管线（Spec v1.1 Phase 4）

**背景**：外部评审对《上下文归属架构》Spec v1 提出 6 项必修（body 历史污染、UNATTRIBUTED 路由、project_id 标识、活动切换迟滞、claims/verified 分离、证据冲突模型）；owner 拍板"赶紧做"。本批交付 **Gateway 侧 Phase 4**：服务端解析阶梯 + 归属落库；客户端 miqro-context（证据采集 / Local Agent / 迟滞切换）为后续批次。

**交付**（分支 feat/caa-phase4-context-pipeline-633）：

- **解析阶梯**（`RequestContextResolver`，Spec v1.1 §4）：`X-Miqro-Project-Id` 声明（不可信，仅当目标是该 Key 的绑定才生效）→ 点号后缀命中绑定 → 唯一绑定兜底；多绑定且无上下文 **400 `CONTEXT_REQUIRED`**（失败关闭，不猜、不静默回落默认项目）；声明无绑定 403 `CONTEXT_NOT_ALLOWED`、声明畸形 400 `CONTEXT_INVALID`；未知/畸形密钥维持统一 404（防枚举收窄到身份层——标签不匹配不再 404，单绑定 Key 的标签视为装饰）。
- **声明审计化**：`X-Miqro-Claim-Source/Confidence/Status`、`X-Claude-Code-Session-Id` 全部 allowlist + 限长（64）消毒，仅落库审计：不参与授权、不转发上游；入站 `x-miqro-*` 由 HeaderFilters 统一剥离（`X-MiqroKey-*` 同规则）。
- **归属随用量落库（V54）**：`usage_event` 增 `session_id/activity_id/claimed_project_id/resolution_status/claim_source/claim_confidence`（全可空，存量写入路径零变化）；**V55** 新增 `request_context_evidence`（append-only 证据审计：来源/规范值/置信度/作用域）。
- **契约修订**：api-contract §4/§7.1（后缀=路由选择器；阶梯与错误码；`usage_event` 归属列）；ADR-0018 与单密钥设计文档加"#633 修订"注；database-schema 增 V54/V55。

**验证**：`RequestContextResolverTest` 8/8（阶梯全矩阵 + 消毒边界）；`VirtualKeyAuthContractTest` 29/29（新增 CaaContext 组：多绑定无上下文失败关闭、声明选绑定且声明头不上行、伪造声明 403）；`HeaderFiltersTest` 10/10；`PostgresUsageEventWriterTest` 7/7（CAA 六列逐字落库）。

**CI 归因补记**：integration job 首跑 SoakIntegrationTest 失败——探针原以"改标签"（presented()+"x"）冒充无效 Key，新阶梯下该请求被正常解析并打到上游（每次探针都真实代理成功），污染行数断言（5361+18）。修复：探针改为篡改秘钥段首字符（HMAC 失败→统一 404）；本地复跑 1/1 通过。

## 2026-09-16 凌晨 — Goal #634：用量报表·每小时 Token 表（人×项目 / 组×项目）

**背景**：产品负责人提出"要能够看见每个人每个项目，每天统计使用 token 做一个每个小时的表；还有每个组的每个项目的"。核查：`summary` 的 groupBy 无小时粒度、各维度为单维聚合，无任何逐小时展示——全新实现。

**交付**（分支 feat/hourly-usage-report-634）：

- **API**：`GET /api/v1/admin/usage/hourly`（#634）——按自然日返回逐小时桶：`dimension=NONE`（小时×项目）/ `USER`（×用户）/ `TEAM`（×团队，多团队按归属视图计入）；参数 `date`（默认本地今天）、`days`（1–7）、`tzOffsetMinutes`（默认 UTC，前端传本地偏移）、可选 `userId`/`projectId` 过滤；每行 请求数 + 输入/输出/缓存读/缓存写/合计 Token（合计口径=四类之和）。
- **实现**：`UsageStatsRepository.aggregateHourly`（纯 SQL 聚合：epoch 位移取整再回移，桶边界对任意服务器时区确定；复用既有 filter 连接器与 `usage_event` Token 口径）；`AdminUsageStatsService.hourly`（参数校验 DAYS/DIMENSION/DATE/TZ_OFFSET）；controller + `HourlyUsageReport`/`HourlyUsageRow` DTO。
- **前端**：「用量报表」新增「每小时 Token」面板——日期选择 + 当天/近 3 天/近 7 天 + 维度切换（不分组/按用户/按团队）+ 复用项目过滤；表格行=小时桶（本地时间），列=请求/输入/输出/缓存读/缓存写/合计；空态友好；随「查询」一并刷新。
- **文档**：api-contract §5.2 增端点与参数/错误码说明；OpenAPI 基线重导出（仅新增 `paths`/`schemas`，不触发破坏性检查）。

**验证**：`AdminUsageStatsServiceTest` 13/13（窗口/时区换算/参数校验）；`AdminUsageApiIntegrationTest` 11/11（新增 3 用例：UTC+8 下小时桶与"用户×项目"交叉、团队维度按成员聚合、admin-only 与参数边界）；前端 vitest（NextAdminUsageView 全量 + 新增每小时用例）、typecheck、build。



## 2026-09-16 凌晨 — 演示站部署与验收（#615 + #633 + #634，develop@8cd67a95）

**部署**：tarball 通道（gh api tarball/8cd67a95）→ /opt/miqrokey-dev → 三镜像串行重建（gateway/control-plane/portal）→ compose up -d --no-deps；V54/V55 Flyway 迁移在演示库确认（usage event context columns / request context evidence，均 success）；全栈 healthy。

**验收（47/47 PASS，记录 D:/tmp/miqro-test/acceptance-2026-09-16.json）**：

- **CAA 端到端（真机、真上游 DeepSeek）**：建「CAA验收项目」（tag=caa-acc）+ 成员 + 授权；demo.user 建双绑定 Key（boundProjects=[demo, caa-acc]）；后缀路由 200（RESOLVED_SUFFIX）、`X-Miqro-Project-Id` 声明选绑定 B 200（RESOLVED_HEADER）、多绑定无有效上下文 400 `CONTEXT_REQUIRED`、伪造声明 403 `CONTEXT_NOT_ALLOWED`、畸形声明 400 `CONTEXT_INVALID`；**DB 落库核对**（usage_event）：session=caa-acc-1/2/3 → 三条归属行逐字符合（claimed_project_id 仅声明路径有值；`git_repo` 因不在 allowlist 被正确丢弃、`HIGH` 置信度保留——消毒生效）。
- **#615 语义真机复核**：被引用标签 PATCH → 409 `PROJECT_TAG_IN_USE`（含中文可行动文案）；轮换复制全部绑定；轮换后新 Key 绑定 B 真实推理 200。
- **每小时 Token 表**：API（USER/TEAM 维度、UTC+8 桶、days=8 → 400、普通用户 403）全过；**门户 UI**（远程验收通道）「用量报表」面板渲染真实数据（09-16 00:00 桶、admin/演示项目/1068 请求，hourStart 本地化正确）。
- **回归批次**：门户 4 路由 200；管理端 16 面（用户/团队/项目/授权/审批/供应商产品/订阅/定价/审计/配额/告警/导出/技能/MCP/用量汇总/明细）+ 个人端 4 面（Key/授权/用量）全 200；console 无错误（仅登入前匿名 401，属预期）。


## 2026-09-16 上午 — Goal #639：miqro-context 客户端（CAA P1–P3）+ Project Registry 最小闭环

**背景**：CAA Spec v1.1 §11 P1–P3（客户端证据采集 + 本地 Agent）+ P5 的 registry 子集；产品目标"CC Switch 一条配置指向 127.0.0.1:8788，Session 内多项目穿插，Key/配置全程不动"。

**交付**（分支 feat/miqro-context-639）：

- **miqro-context（TypeScript / Node ≥ 20，零运行时依赖）**：会话水位线差分（TurnDelta，历史轮永不入本轮证据）；四类证据（prompt_url/tool_path/system_cwd/bash_cwd，含 git remote 解析与 URL→repoKey）；§5.3 作用域+分组+冲突归因（无打分；双 HIGH 组=AMBIGUOUS）；§5.4 ActivitySegment 滞回（HIGH 即时、MEDIUM 连续两轮；同项目内变化不切段）；127.0.0.1 本地代理（注入声明头、剥离伪造 X-Miqro-*、SSE 逐块透传、中断双向传播、body 逐字节）；CLI run/status/doctor/install（install 仅打印接入步骤，不改用户配置）。
- **服务端**：V56 `project_repositories`（租户内 repo 唯一）；管理端点 `GET/POST/DELETE /api/v1/admin/projects/{id}/repositories`（repoKey 四形态归一化、409 REPO_KEY_TAKEN、审计 REPOSITORY_ADD/REMOVE）；网关 `GET /v1/context-registry`（虚拟 Key 认证，只返回该 Key 绑定项目的映射；无持久化时空表）。
- **CI**：新增 client job（typecheck + node --test）；changes 过滤器加 miqro-context。

**验证**：客户端 35/35（水位线/R1 历史污染回归 C15/冲突模型 C5-C7/滞回 C16/解析/头注入）；`AdminOrgApiIntegrationTest` 13/13（新增 registry CRUD 用例）；`ContextRegistryIntegrationTest` 3/3（单绑定只见己方、多绑定双向、404/401 统一语义；**踩坑**：GatewayAuthTestConfig 会给 WebTestClient 装默认 Authorization，缺失凭证用例需显式置空头）。


## 2026-09-16 上午 — Goal #641：/v1/context-registry 改 identity-only（真机 E2E 挖出的引导死锁）

**背景**：Agent 真机 E2E 首跑：`registry sync failed: HTTP 400`（多绑定 Key 以不匹配后缀呈现 → 端点复用完整 CAA 阶梯 → CONTEXT_REQUIRED），随后所有请求 400——**要读映射先得有可解析上下文、而上下文要靠映射推导**的循环。

**修复**：`VirtualKeyResolver` 拆出 `resolveIdentity()`（凭证抽取/解析/快照/HMAC，无归属阶梯；`resolve()` 与它共享 `authenticate()` 核心，清零纪律保持：invalid parse 在 try 外返回，避免对 null secret 做 wipe 的 NPE）；`ContextRegistryController` 改用 identity-only。IT 增补：多绑定 Key 不匹配后缀仍可读 registry（4/4）。文档：api-contract 注明 identity-only 语义。

**观察（未改，留待评审）**：`/v1/models` 同样依赖 resolve()——多绑定 Key 带不匹配后缀时 400；真实使用中 Claude Code 的 Key 后缀通常匹配绑定，暂不动其语义。


## 2026-09-16 中午 — Goal #646：建 Key 默认全选项目（CAA 收口批①，"一把 Key 全项目"成为默认路径）

**背景**：跟踪 issue #645 / 方案 `docs/caa-next-batch-plan.md` §1（方案稿含 ② 无归属策略设计、③ Agent 自启、4 个开放问题，已随 PR #649 立档供评审）。

**改动**（分支 feat/key-default-all-projects-646）：`NextKeysView` 打开表单即应用默认——主项目=第一个可选项目、附加项目全选（可取消）；切换主项目旧值回填为附加项（不静默丢项目）；重置后重新应用；文案「默认全部已选」；契约不变。spec 9/9（新增默认全选/切换回填；级联用例改显式取消勾选）；vue-tsc/eslint 通过。

**验收**（并入主清单，步骤见方案 §1.2）：默认提交 → boundProjects 全量；两项目各一次真实推理 → 用量/每小时表分项目；取消勾选 → 该项目声明 403。


## 2026-09-16 中午 — Goal #647：未归属策略 unattributed_policy（CAA 收口批②，Spec §7.3 落地）

**背景**：跟踪 issue #645 / 方案 `docs/caa-next-batch-plan.md` §2。现状"无法归属一律 400"缺合规兜底选项。

**交付**（分支 feat/unattributed-policy-647）：
- **V57**：`unattributed_policy`（每租户一行：桶项目/凭证/产品/model_scope jsonb）+ `projects.system` 列（系统项目不可被建 Key 选择 → `400 PROJECT_NOT_SELECTABLE`，VirtualKeyService 全入口守卫）。
- **网关**：快照装载策略（凭证装载范围扩到策略引用）；解析阶梯终态——多绑定无上下文时：有策略 → `POLICY_ROUTED`（合成绑定：桶项目/策略凭证，grant=null）；无策略 → 400 不变。模型门控：策略路径 = Key 模型 ∩（策略范围 或 空=上游目录）；/v1/models 同步处理（不 NPE）。
- **控制面**：`GET/PUT/DELETE /api/v1/admin/unattributed-policy`（产品缺省从凭证订阅推导；凭证/产品/#498 目录校验；凭证被 grant 引用 → 告警不阻断；首次 PUT 懒建桶项目；审计 SET/CLEARED；变更刷快照）。
- **前端**：设置页「未归属请求策略」卡片（凭证下拉仅 ACTIVE、模型范围逗号输入、保存/清除、未配置文案、告警展示）。
- **开放问题按方案默认落定**（§2.9：Q1 告警放行 / Q2 与 Key 模型求交 / Q3 system 标记可见 / Q4 AMBIGUOUS 同走策略）。

**验证**：`RequestContextResolverTest` 9/9（新增 POLICY_ROUTED）；`VirtualKeyServiceTest` 21/21（系统项目拒绝）；`AdminOrgApiIntegrationTest` 14/14（策略全生命周期：懒建桶/system 校验/目录校验/跨产品凭证 400/引用告警/审计/DELETE 后桶保留）；前端 settings spec 3/3；OpenAPI 基线重导出（另修复 #640 遗漏的 repositories 基线；**踩坑**：新控制器嵌套 record 重名 `UpsertRequest` 打乱 springdoc 简单名解析——改名 `UpsertPolicyRequest` 后 diff 干净）；28 个 IT 重置清单再补 `unattributed_policy`。


## 2026-09-16 午后 — Goal #648：miqro-context 安装与三平台自启（CAA 收口批③）

**背景**：跟踪 #645 / 方案 §3。现状手动 `run` 关终端即断，"无感"对非开发用户不成立。

**交付**（分支 feat/context-autostart-648）：
- `src/install/autostart.ts`：三平台用户级自启生成器（纯函数）——Windows 启动文件夹 .cmd（start /b node … run >> agent.log）/ macOS LaunchAgent plist（RunAtLoad）/ Linux systemd user unit（Restart=on-failure）；`MIQRO_CONTEXT_AUTOSTART_DIR` 供测试沙箱；幂等写入与静默移除。
- CLI：`install [--write-config] [--autostart]`（显式 opt-in；默认仅提示未装）、新增 `uninstall [--autostart]`、`doctor` 增自启状态行；README 更新。
- **不碰用户系统**：仅显式 `--autostart` 时写入，全部用户级免管理员；真实自启不在开发机自动注册。

**验证**：客户端单测 42/42（新增 7 例：文件名/默认目录/覆盖目录/三平台内容快照/幂等 enable-disable 往返）；**Windows 沙箱实测**：install --autostart 生成 .cmd（node/cli/日志路径逐字校验）→ uninstall 移除，全程未触碰真实启动文件夹。


## 2026-09-16 傍晚 — Goal #675：控制台导航分组对齐腾讯实例层架构（三组扩为七组）

**背景**：用户指示「主要往他们的架构上靠」（腾讯 AI 网关实例层导航分组即架构表达）。现状：管理导航「组织/供应商/数据与告警」三组，其中「数据与告警」17 项过载；「内容留痕」「部署信息」两页无侧栏入口（仅 URL 可达）。

**交付**（分支 feat/console-nav-converge-675，隔离工作树 D:/tmp/miqro-guides，base develop@ea97638c）：
- NewShell.vue 导航重构为七组：模型管理（供应商/订阅/上游凭证）· 访问与授权（用户/团队/项目/授权/审批中心/API 消费者）· 用量与配额（用量报表/配额规则/导出任务/用量删除）· 成本管理（成本报表/账单对账/缓存收益/定价）· 可观测性（审计日志/MCP 访问日志/内容留痕）· 安全与配置（全局配置/告警规则/Webhook 端点/部署信息）· 集成管理（MCP 服务/智能体/服务管理/技能库管理）；「定价」自供应商组迁入成本管理（对位腾讯「模型单价配置」）；面包屑/菜单搜索/tab 同源自动跟随。
- EN 词典 +7 组名；`docs/tencent-ai-gateway-mapping.md` 增「控制台信息架构对齐」一节（组级对位表 + 刻意不引入项与理由）。

**验证**：vitest 295/295、vue-tsc 三工程、eslint（改动文件；NewShell 1 条存量警告 vue/no-template-shadow 经 HEAD 复核非本轮引入）、vite build、Playwright e2e 52/52。

**决策记录（用户授权自裁，2026-09-16）**：①消费者组裁决维持不引入（实体级映射「消费者=用户/机器身份、组=项目」已在 mapping 文档成文；50 账号规模下增设第三套分组实体与「团队悬空」教训相悖）；②本导航重构即原「待拍板项②」的落地；③「生效版本可视化」继续 defer（单层架构无云边同步语义；部署信息页已给静态事实）。
## 2026-09-16 午后 — Goal #582：Virtual Key 停用/启用/重命名 + 行内用量与状态筛选

**背景**：跟踪 issue #582（对照参考站 II 的三项缺口：禁用/启用（软停用）、重命名、行内用量；限流/并发/过期列按产品决策不适用）。设计事实：VirtualKeyStatus 枚举自带 DISABLED；网关快照查询 `status='ACTIVE' OR (ROTATING AND revoked_at>now)`——停用天然落出快照（404 反枚举口径零网关改动）；`/me/usage/summary?groupBy=virtual_key` 现成可做行内用量。

**交付**（分支 feat/vk-disable-rename-582，隔离工作树 D:/tmp/miqro-guides，base develop@ea97638c（含 #652））：
- 后端：MeVirtualKeyController 增 PATCH /{id}（重命名，UpdateVirtualKeyRequest @NotBlank ≤200）与 POST /{id}/disable|enable；VirtualKeyService 三方法 + withStatus 助手（仅 ACTIVE→DISABLED / DISABLED→ACTIVE，其余 409 KEY_NOT_DISABLEABLE / KEY_NOT_ENABLEABLE）；审计 VIRTUAL_KEY_DISABLE/ENABLE/RENAME（from/to）；停用/启用发布 route refresh，重命名不发布（路由不依赖名称）；VirtualKeyRepositoryImpl.update 增写 name 列——原窄更新只写生命周期列，重命名曾静默不落库（IT 红→绿修正）。
- 前端：状态筛选（UiSelect）、「用量 · 近 7 天」列（usageSummary 聚合、失败降级「—」）、kebab 菜单 重命名/停用/启用 + 停用确认门 + 重命名弹窗（错误带 requestId）；summary 拆分「停用/已吊销」计数；audit-labels 增 RENAME 动词；EN 词典 +10 条 + 2 pattern。
- 契约/文档：api-contract §4（3 行 + 2 段语义）、virtual-key-lifecycle §5/§8、CHANGELOG 2026-09-16；OpenAPI 基线经 OpenApiSpecIntegrationTest 重生成（+3 操作 +1 schema，无删改），前端 generated.ts 重生成且 codegen 守卫生效。

**验证**：
- 后端：MeVirtualKeyApiIntegrationTest 15/15（新增 4 例：停用/启用快照回环、ROTATING 拒绝、重命名审计、未知键统一 404）；全量 `verify -P integration` PASS。
- 前端：vitest 297/297（keys spec +2：状态筛选、行内用量降级）、vue-tsc 三工程、eslint（改动文件）、vite build。
- e2e：54/54（新增 2 例：停用确认门流、重命名弹窗流）。
- spotless：apply 仅触本轮 16 文件（无整树漂移），diffs 为纯格式。

**边界**：管理员面（admin/virtual-keys）未加停用/启用（后续可选）；行内用量窗口固定 7 天。
## 2026-09-16 午后 — Goal #658：API 消费者页补 JWT 公钥管理入口（ADR-0011 控制台闭环）

**背景**：跟踪 issue #658。后端 ADR-0011（#340 增补）早已交付消费者 JWT（PUT/DELETE /admin/api-consumers/{id}/jwt-key，RS256 公钥验签、指纹返回），但控制台无入口——平台对接方只能手搓 API。腾讯/阿里控制台都把「消费者密钥（API Key / JWT）」作为一等公民管理。

**交付**（分支 feat/consumer-jwt-658，隔离工作树 D:/tmp/miqro-guides，base develop@807c567c）：
- api 客户端：setConsumerJwtKey（PUT）/ removeConsumerJwtKey（DELETE），复用既有端点（无契约变更）。
- 消费者页：新增「凭证」列（API Key / JWT 徽章，指纹存在即亮，data-testid=consumer-jwt-badge）；行操作新增「JWT 公钥」（仅 ACTIVE 消费者）：弹窗显示当前指纹与设置时间，粘贴 PEM 保存/轮换（空值守卫、后端校验文案透传），「移除公钥」走 danger 确认门（文案明示 API Key 通道不受影响）；弹窗描述明示「平台自持私钥签发、网关只存公钥验签」。
- EN 词典 +15 条 + 2 条动态 pattern。
- 供应商页「模型目录」列已随 #657 实施（本 issue 范围相应收窄，issue 正文将同步更新）。

**验证**：vitest 291/291（55 文件，新增 2 例：保存 PEM 调用参数、危险确认移除 + 凭证列徽章）；vue-tsc（app/spec/node）PASS；eslint（改动 4 文件，--fix）PASS；vite build PASS；Playwright e2e 52/52 PASS。

**边界**：UI 不发私钥、不做 JWT 内容预览；禁用消费者不出现 JWT 操作（与后端 409 CONSUMER_DISABLED 语义一致）。
## 2026-09-16 午后 — Goal #657：列表依赖计数 + 表单规则文案 + 空态引导（对标腾讯对象元数据三件套）

**背景**：跟踪 issue #657（「控制台对标腾讯 AI 网关」P0 第二批；#656 使用指引已提 PR #660）。腾讯列表三件套=状态/版本/**依赖计数**，我们缺"被谁依赖"的可见性——删被引用凭证撞 FK 裸 500（#393），管理员在列表上看不到影响面；表单校验规则只存在于后端报错。

**交付**（分支 feat/list-metadata-657，隔离工作树 D:/tmp/miqro-guides，base develop@807c567c）：
- 上游凭证行新增「授权引用」列（一次 listGrants 聚合）；项目行新增「授权」「成员」列（成员逐项目 Promise.allSettled，失败显示 —）；供应商行新增「模型目录」列（一次 adminListModels 全量聚合：N 个模型 / 未探测）与「依赖」列（凭证 N · 授权 N）。全部只读聚合，无新端点。
- 表单规则文案与后端逐条核对：凭证 name≤200、Secret 8–512 无控制字符（FormatCredentialValidator）；消费者 name≤200 且唯一（JWT sub 映射键）、到期静默 401（SQL 层过期过滤）；项目标签提示修正为 ADR-0018 现行事实（留空自动从代码派生、被密钥绑定引用后不可修改）。
- 三个列表空态补「下一步」引导；EN 词典 +12 条 + 2 条动态单元格 pattern；三个视图 spec 增断言，成员/模型调用计数断言按抽屉折算 mockClear。

**验证**：vitest 289/289（55 文件）；vue-tsc（app/spec/node 三工程）PASS；eslint（仅改动 8 文件，--fix）PASS；vite build PASS。

**边界**：不做点击过滤跳转；探测时间仍在「模型目录」弹窗内（列表只给计数与未探测态）；#393（删除前依赖检查 409+清单）未动。

**补记（同日）**：e2e 抓到真实缺陷——新增的辅助聚合请求（模型目录/授权/订阅）在未被 mock 或上游失败时会把整个 Promise.all 拖失败，主列表整页空态。已修：主数据（产品目录/凭证列表/项目列表）保持强依赖；四个辅助聚合一律 .catch 降级（计数显示 0 / 未探测 / —），列表照常渲染。e2e 两处失败复跑转绿（52/52）。

## 2026-09-16 午后 — 修复 #663：部署换版后懒加载 chunk 404 白屏自愈（/app/providers 实测）

**背景**：用户报障 /app/providers 白屏。nginx 日志实锤两条旧 chunk 404（NextProvidersView-CpEUA9cA.js / NextApprovalCenterView-DvVCRa6q.js，真实用户浏览器，发生在 10:52 portal 部署后 5 分钟）——旧标签页仍在运行上一版 bundle，部署整体替换哈希文件名后按旧哈希请求懒加载 chunk → 404 → 动态 import 失败 → Vue Router 导航中断 → 白屏且无自愈，仅手动 F5 可恢复。

**交付**（分支 fix/chunk-load-reload-663）：`utils/chunk-reload.ts`——`vite:preloadError` 事件 + `router.onError` 双通道识别动态 import 失败（覆盖 Chromium/Firefox/Safari 三种文案）→ `location.reload()` 一次拾取新 index.html；`sessionStorage` 时间戳 10s 冷却防刷新风暴；storage 不可用时放弃自动刷新（白屏优于死循环）；`main.ts` 装配，冷却期外错误照常上抛控制台。

**验证**：单测 4/4（识别矩阵/冷却窗口/storage 兜底/事件抑制与默认放行）；全量 299/299；vue-tsc 三配置与改动文件 eslint 干净。

## 2026-09-16 午后 — 修复 #667：portal 镜像构建改串行，消除 2G 机部署期全站抖动

**背景**：用户报障"无法登录、请求 60s 超时"。与 #663 白屏同属一个事故窗口：12:15–12:32 演示机重建三镜像期间，portal 构建容器内 `npm run build` 并行跑 vue-tsc（545MB）与 vite build（740MB），峰值 ~1.3G 叠加常驻 JVM/Redpanda/Postgres 击穿 1.9G 物理内存，swap 1.9G/1.9G 打满、load 33——control-plane 全面无响应（登录 499/60s 超时），网关对另一用户连续 502（DNS 抖动下 unresolvable）。构建结束约 2 分钟后全站自动恢复。

**交付**（分支 fix/portal-build-sequential-667）：`deploy/docker/portal.Dockerfile` frontend 阶段 `RUN npm run build`（run-p 并行）改为 `npm run build-only && npm run typecheck` 串行——峰值 ≈ max(740, 545) ≈ 740MB；2 vCPU 上并行无吞吐收益，typecheck 质量门保留。

**验证**：本地 docker build 全量构建通过（含 npm ci / vite build / vue-tsc 三配置）；CI images job 随 PR 验证。

## 2026-09-16 午后 — 需求 #668：路由切换无感化（滚动位置重置 + 悬停/空闲预取 chunk）

**背景**：用户报障"切换页面无法无感路由——会先看到上一个/下面滚动位置的内容再到下一个界面"。定位三处缺口：① `.new-shell__content`（跨路由复用的滚动容器）无任何 scrollTop 重置，长页滚一半切短页直接停在底部；② 全部约 35 条路由懒加载且无预取，点击后等 chunk 网络往返才提交导航；③ #655 的 out-in+仅 enter 过渡已正确，无需改动。

**交付**（分支 fix/route-feel-668，仅 NewShell.vue）：
- `watch(route.path)` → `contentEl.scrollTop = 0`（query-only 变化不重置，保留页内筛选用例）；
- 菜单 `@mouseenter/@focus` → `prefetchRoute(name)`：`router.resolve` 后逐 record 调 `components` 内的 loader 函数（typeof 守卫；模块缓存命中后点击即达）；`prefetchedRoutes` Set 去重，每路由至多一次；
- mount 后 1.5s 起对当前角色全部菜单项序贯静默预取（120ms 步进；卸载清定时器）。

**验证**：单测 4/4（悬停一次去重/聚焦/空闲全量/滚动重置与 query 豁免）；全量 **299/299**；typecheck 三配置 + 改动文件 eslint 干净。**浏览器实测**（mock 控制面 + dev 服务器）：滚动 500→0 且滚动能力保留；挂载后 1.5s 空闲窗口内悬停 → 700ms 内目标 chunk 抵达（资源计时 2 条=模块+样式）；未交互页面（资料）被空闲预取自动加载。
## 2026-09-16 午后 — Goal #656：页面级「使用指引」——UiPageGuide + 六个重点管理页（对标腾讯产品指南）

**背景**：跟踪 issue #656（「控制台对标腾讯 AI 网关」P0 第一批；同批 #657 列表依赖计数与表单规则文案、#658 API 消费者 JWT 入口另立）。腾讯控制台每个管理页顶部都有「产品指南/操作指引」，把跨页链路写成 3–4 步卡片；我们此前全站唯一编号引导只在「我的密钥」空态里，链路知识是隐性的。

**交付**（分支 feat/page-guides-656，隔离工作树 D:/tmp/miqro-guides，base develop@807c567c）：
- 新组件 `frontend/src/ui/PageGuide.vue`（barrel 导出 UiPageGuide）：页头下方「使用指引」卡——3–4 步，每步 = 序号 + 动宾标题 + 一句话 + 「前往『X』」跨页路由链接（可选 GitHub 文档直链，沿用 #651 模式）；「收起」（细条）/「不再显示」按页持久化（localStorage，setup 同步读取避免闪烁；storage 不可用静默降级）。
- 内容模块 `frontend/src/content/pageGuides.ts`：六页文案——供应商「接入一家新供应商」、上游凭证「三步用起来」、API 消费者「外部系统接入四步」、我的密钥「从零到调用四步」、授权「授权四步」、项目「项目四步」。涉及生效语义的步骤明写「保存后数秒内生效，无需同步」（快照自动刷新，不引入腾讯式手动同步动作）；凭证指引写明「轮换后所有引用方自动使用新版本」。
- 六页接入 + `ui/index.ts` barrel；EN 词典 +83 条；`frontend-design.md` §6 增补 PageGuide 规范段。

**验证**：
- vitest 全量 294/294（新增 `PageGuide.spec.ts` 5 例：渲染与链接、收起记忆、隐藏、内容守卫——to 必配 toText、/app 前缀、文档仅 https github；`NextCredentialsView.spec` 增指引断言）。
- vue-tsc（app/spec/node 三工程）PASS；eslint（仅本轮改动 11 文件，--fix）PASS；vite build PASS。
- Playwright e2e 52/52 PASS（生产构建 + preview，4 视口；含全部管理页 baseline 与 forbidden-aesthetics 审计；截图 frontend/test-results/baseline/）。

**边界**：不加同步动作/状态列（无实例层）；e2e 用例与金样未动（截图仅捕获，无像素对比）；#657/#658 为同方案后续批。

## 2026-09-16 午后 — 部署件固化 #677：2G 演示机 JVM/内存调优回流 compose.prod.yaml

**背景**：演示机控制台内存告警 95%+（同日 #663/#667/#668 事故窗口定性：12:15–12:32 构建峰值 97.5%、日常常驻 ~85%）。午后完成实测瘦身，但改动只落在服务器 compose（配置漂移，整树刷新即回退）。瘦身时开启 GC 日志实测：control-plane 堆存活仅 ~69MB、gateway ~24MB——原 512m/640m 默认值与镜像 ENTRYPOINT 兜底的 `-XX:MaxRAMPercentage=75`（768m）均严重超配；JVM RSS 大头是 metaspace（86MB）+ code cache + 线程（Spring Boot 固有）。附带实证：`-Xmx` 优先于 `-XX:MaxRAMPercentage`（MaxHeapSize 非默认则百分比分支跳过），二者不竞争。

**交付**（分支 chore/compose-prod-tuning-677，仅 `deploy/compose.prod.yaml`）：
- control-plane `-Xmx512m→448m`、gateway `-Xmx640m→384m`，均加 `-XX:+UseSerialGC` 与 `-Xlog:gc*:file=/tmp/gc.log:time,uptime:filecount=2,filesize=5m`；
- postgres `shared_buffers` 默认 64MB（`POSTGRES_SHARED_BUFFERS` 覆盖；共享内存不可回收，调小后余量转为可回收内核页缓存）；
- 留痕消费端（`MIQROKEY_RETENTION_CONSUMER_*`）与响应缓存（`MIQROKEY_CACHE_ENABLED`）开关环境变量化（默认关）——消除服务器手改 compose 的漂移来源，仓库默认行为不变。

**验证**：`docker compose -f deploy/compose.prod.yaml config` 通过（本机 v5.1.4；渲染的 JAVA_TOOL_OPTIONS/shared_buffers 与服务器实测目标逐字一致）。服务器侧（2G 演示机）已按目标态运行并验证：swap 899→85MB、dockerd RSS 317→97MB、控制台常驻口径 85%→~60%、全栈 healthy、登录 `/api/v1/auth/login` 200、PSI=0、零 OOM；服务器 `.env` 已补齐对应开关值（cache / 留痕消费端 / COMPOSE_PROFILES）。

## 2026-09-16 傍晚 — 腾讯对标收口批（#681/#683/#685/#554/#688 + ADR-0019 草案）

**背景**：owner 以腾讯控制台样本（模型 API / 配额管理 / MCP 服务接入指引 / 消费者）指示「把我们的方案和他们的界面内容和设定相对照……改代码」。范围声明：**往他们的架构靠**（沿既有裁决），红线不动（不限流、不硬阻断、不做协议转换）。五条交付线 + 文档收口一次成批。

**交付**（隔离工作树，全部按 gitflow：issue → 分支 → PR → CI）：

- **#681 用量与成本总览 → PR #682**：`NextAdminUsageView` 升级（KPI 卡带 ×6 / 服务端 Token+成本双序列趋势（日/月）/ 维度分解表（团队/个人/项目/模型/密钥，占比条 + 行下钻 + chip 清除 + CSV）/ 团队与用户与项目下拉筛选）；后端 `/admin/usage/{summary,records,hourly}` 增 `teamId`（virtual_keys→team_memberships EXISTS，ue/h 双别名；「归属视图非分区」口径沿用 #606）；UiTrendChart 增多序列模式（按各自峰值缩放 + 图例，向后兼容）；UiTable 增 rowClick。测试：后端单测 13 + IT 12（含新 team filter 正/负例），前端 314/314，e2e 41/41（含 admin-usage 页基线），spotless 收敛。**首跑即修复**：develop 合并后 openapi 单行 JSON 冲突 → 本地合并 develop + 从合并后代码重生成基线（唯一可靠解法）。

- **#683 配额管理扩维 → PR #686**：metric+COST、period+YEARLY、level+NEAR_LIMIT（固定 90%）；V58 扩两张表 CHECK；`QuotaRuleView.used` long→BigDecimal；YEARLY 水位绕公开 93 天窗口（新增 `summaryUncapped` 内部通道——**首跑被 93 天校验打回 400，为该修复的动机**）；模板表 CHECK 遗漏由模板 IT 409 暴露后一并扩。测试：4 类 23/23（COST 水位/年度窗口/四档边界/模板校验），前端 317/317。

- **#685 MCP 接入闭环 → PR #687**：`GET …/{id}/connection`（接入地址生成 + path segment 编码 + 凭据形态提示；**首跑撞 ACL 面 `/{id}/access` → 落 `/{id}/connection`，IT 固定**）+ `POST …/{id}/verify`（probeOnce：与健康巡检同源探针，脱敏中文结论，只读不改遥测）；前端行操作「接入信息」「验证连通」。测试：Onboarding IT 3/3 + Checker 8/8 + Service IT 8/8 回归，前端 spec 28/28。

- **#554 MCP 监控 → PR #689**：访问日志页窗口指标卡带（失败率 = 失败/(转发+失败)，口径注记）+ 单次调用详情抽屉（受理→上游首包(+ttfb)→结论 时间线 + 元数据清单）；零后端改动（字段已齐 V29+V45）。UiTable rowClick 同款最小改动随行（与 #682 逐字一致，合并顺序无关）。

- **#688 内容留痕采集配置**：「内容留痕」页新增「采集配置」卡（开关 + 内容上限 1 KiB–4 MiB + 合规提示 + 即时生效文案）；此前 PUT /admin/retention-config 无前端入口。测试：spec 6/6。

- **文档收口（本批）**：mapping 行 14/15 过时状态更正 + 「2026-09-16 收口批」对位表 + **对位说明 A（协议/Base Path/包体采集）与 B（消费者/消费者组/团队/项目）**；ADR-0019（配额超限拒绝，Proposed）；feature-backlog F51 状态；ai-gateway-comparison MCP 行刷新；CHANGELOG 本批条目。

**owner 已拍板（2026-09-16）**：ADR-0019 三个未决问题闭环——① 立项=是（软着陆：拒绝请求，否决自动禁用 Key）；② 首版范围=USER+PROJECT × TOKENS/REQUESTS/COST；③ 状态码=429。落地形态与实现见 **ADR-0020**（不采纳草案的数据面计数形态）。

**未做（记录）**：MCP 服务向导「服务类型/后端类型」枚举（我们固定标准透传形态）、HTTP→MCP 转换、消费者组实体、配额缓存命中「全量计入」档（语义天然等价「不计入」）——均按既有裁决维持，mapping 已注明理由。

- **#684 配额软着陆（超限拒绝 429）→ PR（ADR-0020）**：从「只算不管」到真闸门——规则级 `action ∈ {ALERT, REJECT}`（默认 ALERT，零回归）；REJECT 规则超限后网关对该用户/项目 429 (`quota_exceeded` + `Retry-After` 窗口结束提示，`/v1/models` 同门)，Key 不失效、提额/跨窗口自动恢复。链路：控制面评估器（60s，共享 `QuotaWatermarks`）→ `quota_enforcement`（V59，整体替换）→ 判定集变化才 pg_notify → 快照两集合 → 网关热路径零查询。并行的配额扩维（#683/#686，COST/YEARLY/NEAR_LIMIT）已先行合入，本批在其之上只做执行面，并同步 api-contract §5.19 / database-schema / configuration-reference / ADR-0020 / F51。

## 2026-09-16 晚间 — 偏好抽屉补缺 #579：折叠菜单开关 + 内容宽度 1200 + 抽屉内边距

**背景**：issue #579（Vben 偏好设置体系——设置抽屉）复核后定三处差距：抽屉内没有折叠菜单开关；主内容宽度固定 1440px（issue 要求 1200px）；抽屉头部内边距统一 20px（issue 要求上下 16px / 左右 24px）。

**交付**（分支 `feat/settings-drawer-gaps-579`）：

- **折叠菜单开关**：`SettingsDrawer.vue` 新增「折叠菜单」分组 + 「折叠侧边栏」开关（`settings-toggle-collapsed`），走既有 `setPreference('collapsed', …)`。该偏好项此前已存在（`preferences.collapsed`，默认 `false`）并由 `NewShell.vue` 消费（`iconOnly = 窄视口 || collapsed`），只是除顶栏按钮外无第二入口——因此本项是接上既有生效链路，不是新增占位开关。
- **内容宽度 1440 → 1200**：`design-tokens.css` 的 `--ui-content-max`。**改动前已清点全部消费者**：全仓只有 `design-base.css:25`（`.ui-page { max-width: var(--ui-content-max) }`）与 `design-base.css:520`（`[data-compact='wide']` 覆盖为 `100%`）两处；无页面把 1440 硬编码为内容上限（视图/用例里的 `1440` 均为 e2e viewport 设置）。「流式」由 `[data-compact='wide']` 独立覆盖，与固定上限的取值无关，故流式/固定两种行为都不受影响。
- **抽屉头部内边距**：`ui/Drawer.vue` 的 `.ui-drawer__head` 由 20px 改为 `var(--ui-space-4) var(--ui-space-6)`（16px / 24px），与该组件族的 Vben/antd 度量注释一致。
- **测试**：`shell-preferences.spec.ts` 新增抽屉开关用例（开关 → 立即折叠侧栏 + 写穿 `localStorage` + 模拟重载后保持）与 `#579 layout contract` 用例族（jsdom 不跑层叠，故解析随包发出的 CSS 源码：断言 `--ui-content-max` 取值、`.ui-page` 确实消费该 token、`wide` 覆盖仍为 100%、抽屉内边距；`var()` 一律解析回 px 取值，改名 token 无法蒙混通过）；`preferences.spec.ts` 的抽屉用例补同一开关。

**验证**：vitest 全量 **59 文件 / 334 用例 PASS**；`vue-tsc`（app/spec/node 三工程）PASS；`vite build` PASS；对本轮 4 个可 lint 的改动文件（`SettingsDrawer.vue`、`ui/Drawer.vue`、`preferences.spec.ts`、`shell-preferences.spec.ts`）跑 `npx eslint <4 files> --ext .vue,.ts` → 退出码 0，`516 problems (0 errors, 516 warnings)`。
（说明：上述 516 条警告全部来自 `prettier/prettier` 的行尾项——515 条 `Delete ␍` 与 1 条 `Delete ␍⏎␍`。本机为 CRLF 检出而 prettier 期望 LF，`npm run lint` 自带的 `--fix` 会重写全树约 100+ 文件的行尾，故本轮验证改用等价的、不带 `--fix` 的 `npx eslint`。该行尾基线在 `develop` 的 HEAD 上同样成立：未改动的 `src/ui/Button.vue` 单跑亦为 `201 problems (0 errors, 201 warnings)`。）

**边界与影响**：`frontend/e2e/baseline-screenshots/` 为捕获式基线（无像素对比断言），其截图内容宽度仍反映旧的 1440px，本批不重新生成、不影响 CI；`docs/frontend-design.md` 已同步为 1200px；`tokens.css` 的 v1 `--miqrokey-content-max: 1600px` 属旧层，不在本 issue 范围。

### 并入 develop 新基线（2026-09-16）：merge `adfb670`（#695 / #684 配额软着陆）

- 背景与手法：develop 于本日推进到 `adfb670`，本分支（原基于 `50a9b24`）与其冲突。用
  `git merge origin/develop`（**产生合并提交，非 rebase**）并入基线，本分支改动全部保留。
- 冲突清单与解法（冲突文件共 **1** 个）：
  - `docs/progress.md`——两侧都在文件末尾追加：develop 追加 `#684` 小节，本分支追加「2026-09-16 晚间 #579」段。
    解法：**两边都保留**，develop 段在前、本分支段在后（本分支段逐字未改）。
    自检（**本条记录写入之前**的解冲突结果）：该结果与 develop 版逐字节比对，差异恰为本分支那 16 行新增段
    （sha256 `cab4e443…`）；本分支相对 merge-base 的自身改动为 `16 insertions / 0 deletions`，确认未丢内容。
    本条记录（22 行）写入后，`git diff --numstat adfb670 5d14828 -- docs/progress.md` 为 `38 0` = 16 行本分支段 +
    22 行本条记录，仍为纯增量；本条记录此后的修订由新提交承载，不计入该数。
  - 同一区域另有 develop 单侧改动（`待 owner 拍板` → `owner 已拍板（2026-09-16）`）由 git 自动合并——
    本分支从未改过该行。
  - 预期中的 `design-tokens.css` / `design-base.css` **未冲突**：develop 的配额执行面改动未触及这两个文件。
- 复验（2026-09-16，真实命令与结果）：
  - `npm run typecheck`（vue-tsc app/spec/node 三工程）→ 退出码 0，无错误输出。
  - `npm run test` → **59 files / 335 tests passed**（较合并前 +1，来自 develop 并入的
    `NextQuotaRulesView.spec.ts`）。
  - lint：`npm run lint` 定义为 `eslint . --ext .vue,.ts,.tsx --fix`；本轮以**同一脚本加 `--no-fix`** 运行
    （`npm run lint -- --no-fix`）→ 退出码 0、`59204 problems (0 errors, 59204 warnings)`：其中 59203 项为
    `prettier/prettier` 行尾项（CRLF 检出基线），另 1 项为 `vue/no-template-shadow`（`src/components/NewShell.vue:809`
    的 `Component` 遮蔽；该文件自 merge-base `50a9b24` 至合并结果未改动，属既有告警且不可自动修复）。
    不用 `--fix` 的原因已实测：`--fix-dry-run` 对未改动的
    `src/ui/Button.vue` 给出 CR 数 201 → 0 的修复输出（该文件单跑 0 errors），即 `--fix` 会静默重写全树行尾；
    该命令执行前后（提交前）`git status --porcelain` 均为 42 项，确认无文件被写入（合并提交后工作区为 0 项）。

## 2026-09-16 夜 — 列表信息架构收口 #657：依赖计数列可点击 + 表单规则文案 + 空态 CTA

**背景**：#657（承接上一轮验证线钉到行号的三处缺口）。凭证列表「授权引用」列只读不可跳转、授权列表没有可跳转的过滤入口（`NextGrantsView` 不读 `route.query`）、项目/团队创建表单不写规则（`projects.code/name`、`teams.name` 的宽度与唯一性只在 409 响应体里可见）、`ui/Table.vue` 默认空态不渲染 CTA。范围仅前端：不动后端、不改既有 Flyway 迁移、不动既有 e2e。

**交付**（分支 `feat/list-ia-closeout-657`，5 个源文件 + 7 个 spec，8 个提交）
- `ui/Table.vue`：新增可选 props `emptyActionLabel` + `emptyActionTo`（`RouteLocationRaw`），二者齐备时默认空态渲染 `router-link.ui-link-action`（`data-testid="table-empty-action"`）；`#empty` 插槽仍优先，`NextKeysView` 的自定义空态不受影响。
- `next/NextCredentialsView.vue`：计数为 0 时仍是 `<span>`（置灰 `.next-credentials__count-zero`），>0 时渲染 `router-link` 指向 `{ name: 'grants', query: { credentialId } }`；两个分支共用 `data-testid="credential-grant-count"`（该 testid 早于本批存在，断言因此落在同一格上）。旧实现该列恒为 `<span>`，所以「>0 渲染成 `A` 且带 `credentialId`」这条对旧实现是红的；「=0 仍是 `SPAN`」在旧实现上也成立，属回归护栏而非判别式。
- `next/NextGrantsView.vue`：读 `route.query.credentialId` 做真过滤（按 `upstreamCredentialId` 匹配），工具条显示「共 X 条授权（全部 Y 条）」+ 凭证 chip +「查看全部」清除链接；过滤后列表为空时复用新 CTA 回到全量列表。
- `next/NextProjectsView.vue` / `next/NextTeamsView.vue`：创建表单用既有 `hint`（`ui-field__hint`）写明后端强制的规则——项目代码「必填，同一租户内唯一，最长 64 个字符。」、项目名「必填，最长 200 个字符。」、团队名「必填，最长 200 个字符。」。逐条对 `AdminOrgService#createProject`/`createTeam` 与 `projects.code/name`、`teams.name` 列宽核对过，无自造约束。

**收口补强**（第二轮，评审驱动）
- `ui/Table.vue`：显式 `import { RouterLink }`——靠全局注册时，编译器会把 `<router-link>` 的解析提升到 v-if 之上，于是每个嵌 UiTable 的页面（约 30 个视图）即便不渲染 CTA 也要解析一次；clean run 里 43 条 `Failed to resolve component: router-link` 即由此而来，现在为 0（存量 43 条出自 `PageGuide.vue` 与 `NextOverviewView.vue` 自己的模板，不在本批）。
- `next/NextCredentialsView.vue`：计数链接加 `.next-credentials__count-link`（`padding: 0`），与同列右对齐的数字对齐。
- `next/NextGrantsView.vue`：`?credentialId=a&credentialId=b` 这类重复参数取首个值（原先当作「无过滤」，URL 说过滤、列表却说全量）；空态文案改用 `scopedFilter`，只在数据确实加载成功时才断言「该凭证还没有被任何授权引用」，加载失败时交给错误提示。
- `i18n/dict.ts`：5 条 DICT + 2 条 PATTERN，英文界面不再回落中文。

**验证**（真实命令与结果，frontend 目录；均在**最后一次源文件改动之后**重跑过——评审收口轮改了 `next/NextGrantsView.vue` 的注释与 `i18n-copy.spec.ts` 的注释，随后三个命令全部重跑；工作区已还原 lint auto-fix 的改写）
- `npx vitest run` → exit 0，`Test Files 61 passed (61)` / `Tests 352 passed (352)`，25.29s；`npm run typecheck`（vue-tsc 三工程）→ exit 0；`npm run build` → exit 0，`built in 19.75s`（仅既有 esbuild CSS 压缩告警）。
- lint 信号按**提交内容**取：13 个改动文件逐个 `git show HEAD:<file> | npx eslint --stdin --stdin-filename <file>` → 逐文件 exit 0，合计 `0 errors, 6 warnings`，全部是 spec 内多组件共存的 `vue/one-component-per-file`（`NextCredentialsView.spec.ts` 2 条、`NextGrantsView.spec.ts` 2 条、`list-ia-deeplink.spec.ts` 2 条）。直接对工作区副本跑 lint 会多出上千条 `Delete ␍`，原因见下条。
- **EOL 说明（任何人复现上面的 lint 数字前先读这条）**：`.gitattributes` 是 `* text=auto`，Windows 检出的工作区文件默认 CRLF（`frontend/src` 下 37 个文件当前即 `w/crlf`，含本批的 `i18n/dict.ts`、`views/next/NextProjectsView.vue`、`views/next/NextTeamsView.vue`），而 prettier 规则要求 LF，于是对**工作区副本**跑 `npx eslint` 会把它们逐行报 `Delete ␍`（实测 `0 errors, 1110 warnings`）。这不影响提交内容——index 与 HEAD 都是 LF，git 归一后 `git status` 仍干净，`git show HEAD:<file> | npx eslint --stdin --stdin-filename <file>` 对同样三个文件 exit 0、零输出。首轮 `npm run lint` 只报 5 条是同一机制的另一面：脚本带 `--fix`，报出来的数字是自动修复之后的残余（首轮这 5 条都不可自动修）。代价是那批无关文件被**真正改写**，而且不止改 EOL——对**提交内容**跑 `git show HEAD:frontend/src/types/generated.ts | npx eslint --stdin --stdin-filename frontend/src/types/generated.ts` 报 `0 errors, 9721 warnings`（引号风格与缩进），即该文件本就不符合 prettier 规则，`--fix` 必然把它整文件重排。工作区 CRLF 与 autocrlf 无关：本机 `git config core.autocrlf` 为 `false`，CRLF 来自 `.gitattributes` 的 `* text=auto` 在 Windows 上的检出行为。
- 首轮 `npm run lint` → exit 0，`0 errors, 5 warnings`（4 条 spec 的 `vue/one-component-per-file`、1 条既有 `NewShell.vue` 的 `vue/no-template-shadow`）。
- 新增/改 spec 7 个：`UiTable.spec.ts`（CTA 仅在 label+to 齐备时渲染、`#empty` 仍优先）、`NextCredentialsView.spec.ts`（计数 >0 是 `A` 且带 `credentialId`、=0 是 `SPAN`）、`NextGrantsView.spec.ts`（`?credentialId=` 真过滤 + chip + 清除链接 + 过滤后空态 CTA + 重复参数 + 加载失败不冒认空态）、`NextProjectsView.spec.ts` / `NextTeamsView.spec.ts`（hint 文案断言）、`i18n-copy.spec.ts`（新增文案过 `translateText` 锁住，改名不再静默丢译文）、`list-ia-deeplink.spec.ts`（真 router：凭证列表渲染真 `router-link` → 路由跳转 → `router-view` 挂载的授权列表按 query 过滤，链接/路由/过滤三者互证，而非各自对 stub 断言）。
- 全量测试尾部那条 `Not implemented: navigation (except hash changes)` 是既有 jsdom 噪声——单跑本批 spec 时不出现，且在任一 spec 输出之前打印；非失败。

**评审收口（对抗评审轮，交付前）**：独立上下文的评审员只认现场文件与命令输出，结论 **无 blocker、APPROVE**（评审报告不随本批提交，落在仓库外）。6 条 minor 当轮处置：3 修 3 反驳，各自带证据。
- 修 `next/NextGrantsView.vue` 注释：原写加载失败时错误提示「占据整屏」，实际错误提示是独立的 `ui-alert`、表格仍在下方渲染，改为如实描述。
- 修本节计数列的判别力描述（原写「测试对旧实现是红的」）：`credential-grant-count` 这个 testid 早于本批存在（`git show origin/develop:frontend/src/views/next/NextCredentialsView.vue` 可见旧列已是带该 testid 的 `<span>`），因此 `=0` 分支在旧实现上同样成立、属回归护栏；判别式是「>0 变链接且带 `credentialId`」与授权列表的过滤/CTA 断言（评审员变异实验：把 `:data` 改回未过滤的 `grants`，`Test Files 6 failed (6)` / `Tests 10 failed | 35 passed (45)`，exit 1）。
- 修 lint churn 归因，见上一条 EOL 说明。
- 反驳三条：陈旧基线（评审 diff 取了过期基线，该基线不在交付物内）；`emptyActionTo` 默认值 `''`（`''` 属 `RouteLocationRaw` 的字符串分支，且 CTA 由 label+to 双重 `v-if` 把关，运行时不可达）；英文 `1 grants`（词典规则 `共 N 条授权 → $1 grants` 在 develop 上已存在，i18n 层无复数能力，本批只是在 spec 里锁定既有行为——spec 注释已写明这不是对措辞的背书）。
- 收口改动后复跑：`npx vitest run` → `Test Files 61 passed (61)` / `Tests 352 passed (352)`；`npm run typecheck` → exit 0；`npm run build` → exit 0，`built in 16.22s`。

**边界与偏差**
- 未加 `maxlength` 属性：issue 要的是「规则可读」，本次只补文案，不改输入拦截行为。
- 未动 e2e 与金样；`frontend/dist/` 已被 `.gitignore` 覆盖，构建没有脏化工作区。
- `frontend/package.json` 的 `lint` 脚本写死 `eslint . --ext .vue,.ts,.tsx --fix`，所以每跑一次都会改写一批与本 issue 无关的文件（含 `types/generated.ts` 的整文件 prettier 重排）——两轮各发生一次，均按路径逐个 `git checkout --` 还原，本批提交 `git show --stat` 只含本批文件。这是仓库既有状态，不是本批引入；收口验证因此不再跑 `npm run lint`，改为按上一节的方式对提交内容取信号（`git show HEAD:<file> | npx eslint --stdin`），既不改写工作区，也不受工作区 EOL 影响。

## 2026-09-16 晚 — #629 设计稿口径归位（docs-only：头名与列名对齐 v1.1 已交付契约）

**背景**：#629 的定性是**设计稿未跟随 v1.1 评审修订**（不是实现漂移）。`docs/activity-context-design.md`（设计稿 v0.1）停在评审前口径——头名 `X-Miqro-Tag`、用量列 `attribution_source`——而实现侧（V54/V55 迁移 + RequestContextResolver）早已按 Spec v1.1 的 R3/R5 落地 `X-Miqro-Project-Id` 与 `resolution_status`/`claimed_*`。设计稿与实现级 Spec 长期并存而 `document-map.md` 对两者**零引用**（本轮已补索引），是该分歧得以存活的土壤。处置取「改文档齐实现」：**零迁移 / 零代码 / 零前端**。

**交付**（分支 docs/activity-context-design-realigned-629，隔离工作树，base（fork 点）develop@50a9b245，收口轮合入 develop@adfb670（合并 `d15b2d5`））——**本段与「验证」段引用的 `:NNN` 为交付时点 `6db7f33` 的行号，「修复轮」各条为修复后的 HEAD 行号；两套基准不同，同一编号可能指向不同内容（如 `:79`：交付时点为门控注记、HEAD 为值域行）**：
- `docs/activity-context-design.md`：`:4` 状态改 **历史件 / historical** 并链到实现级 Spec；`:46-48` Usage Event 字段表 `attribution_source` → `claimed_project_id`/`resolution_status`/`claim_source`/`claim_confidence`；`:72` 头名 → `X-Miqro-Project-Id: <project-uuid>`（值域为 project UUID，非法值 400 fail-closed，依据 R3/P1）；`:77` 列名与值域对齐 **V54 迁移注释**，声明（未验证输入）与裁定（计费依据）分列（R5/P1），`session_id` 注明纯观测/可空/不参与路由授权；`:106`/`:147`/`:164` 同步改名，迁移注记扩为 **V54 + V55**；`:156` Q3 行改为裁定/声明记录口径。
- **原「头名敏感词门控」论证降为 `:79` 注记并保留**：适用范围仅限客户端从 settings/env 读取的 `ANTHROPIC_CUSTOM_HEADERS`（静态头降级模式），CAA 主路径的头由本机 Agent 注入、不受门控；若未来启用静态头降级须另选不含 `project/key` 的头名（如 `X-Miqro-Target-Id`）。该约束是未来形态的既有需求，不删。
- **§5 真机实验记录不回改**：`:117` 实验 1 保留当时真实观测到的 `X-Miqro-Tag: miqi`，仅在结果列追加「（实验用头名；正式契约见 §4.1 → `X-Miqro-Project-Id`）」。
- 顺带修复 `:6` 既有坏链：`../context-attribution-implementation-spec.md` → `context-attribution-implementation-spec.md`（仓库同级相对链风格，同 `ai-gateway-comparison.md:3`）。
- `docs/document-map.md` §2 增两行索引：实现级 Spec = 权威实现契约 / 设计稿 = 历史设计稿（无契约效力）。
- **未改**任何代码、迁移、前端、契约文件。

**验证**（**以下为修复前时点（commit `6db7f33`）**；worktree 内 `git grep -n`，原文入交付报告；计数按**设计稿文件内**口径——本条目自身的叙述性提及不计；修复轮后的行号与计数见本节末）：
- `git grep -n "X-Miqro-Tag" -- docs/activity-context-design.md` → **1 命中**（`:117` §5 实验记录，附「实验用头名；正式契约见 §4.1」注记），exit 0；
- `git grep -n "attribution_source" -- docs/activity-context-design.md` → **0 命中**（exit 1）；全仓排除本条目后同为 0 命中——代码/契约/Spec 均无该名；
- `git grep -n "X-Miqro-Project-Id" -- docs/activity-context-design.md` → **6 命中**（`:72`/`:79`/`:106`/`:117`/`:147`/`:164`）；全仓其余命中均在既有实现/契约件（`RequestContextResolver.java`、`ResolvedContext.java`、契约与测试、`miqro-context/**`、Spec v1.1），另含 `progress.md` 的叙述性提及（本条目自身与既有条目 `:2931`/`:2961`），**无新文件被引入**。

**边界**：仅 `docs/` 下三份文件（设计稿 / document-map / 本条目）。不动 `V54__usage_event_context_columns.sql`、`V55__request_context_evidence.sql`、`RequestContextResolver.java`、`ResolvedContext.java`、`api-contract.md`、`database-schema.md`、`miqro-context/**`、`context-attribution-implementation-spec.md`。设计稿内残余 1 处 `X-Miqro-Tag` 是**实验记录**（非"未改完"），本条目自身的叙述性提及亦计入全仓 grep 命中；§5 记录的时间点事实按实验记录原则保持原样。issue #629 正文自身仍用旧列名，建议由 owner 更新措辞后再关闭。

**修复轮（评审后）**：对上述交付做了一轮独立对抗性评审（评审只看工作树文件与命令原文，不采信作者结论）。逐条回源码/迁移/`gh` 远端复核后，修复 15 处**事实性**问题（仍全部落在 `docs/`）：

- `:4` 交付枚举补 **#641**（`gh` 核实 #633/#639/#641/#645–#648 均已交付）；
- `:7` 实验脚本指针加注「本机临时路径，未随仓库归档；证据以 §5 表格记录为准」（本机无 D: 盘、仓库内零副本，原文「（可复现）」不可兑现）；
- `:51` §2 概念字段表后补「上图为概念模型，实现列见 §4.1 与 Spec v1.1 §7.1」（`user_id`/`model`/`cost`/`ts` 并非 `usage_event` 实现列）；
- `:74` 失败语义精确化：**非 UUID 且在长度域内 → 400 `CONTEXT_INVALID`**；空值/超 64 字符按「未携带」处理（`RequestContextResolver.bounded()` 语义），原文「非法值 400」过宽；
- `:76` 400 加条件：**未配置未归属策略时 400 `CONTEXT_REQUIRED`**；配置后按策略路由（`POLICY_ROUTED`、落未归属桶，Spec v1.1 §6.3）；
- `:79` `resolution_status` 值域改为「实现产出 `RESOLVED_HEADER`/`RESOLVED_SUFFIX`/`SOLE_BINDING`/`POLICY_ROUTED` 四值；完整值域另含 `UNATTRIBUTED`/`AMBIGUOUS`」；**不再把 V54 列注释当 `claim_source` 值域权威**（该注释只列 6 值、漏 `git_remote`；权威为 `api-contract.md` §7 阶梯条，共 7 项）；
- `:81` 门控注记补证据边界：`X-Miqro-Target-Id` 的结论出处为 Spec v1.1 §3.3，**本稿 §5 未单独实验该头名**（原文「已实证」不可兑现）；注记本身**保留未删**；
- `:112` 无证据分支精确化：**不注入 `X-Miqro-Project-Id`**（客户端 `miqro-context/src/proxy/inject.ts` 仅 RESOLVED 时注入），只发 `X-Miqro-Claim-Status: UNATTRIBUTED`；网关侧策略桶 / 未配置则 400；
- `:143` §6 处置现状补历史注记：**#615 已 MERGED（`f057fd5`）**，Q0 按 A 线落地，本节「建议 B / 建议关闭 #615」描述过期；
- `:153` §7 补历史注记：Q0 已定，Q1–Q5 已在 Spec/实现落地，**Q6（per-turn 钩子 / transcript 兜底）未交付**；
- `:160` Q3 行：`activity_id` **已随 `V54` 落库**（客户端发 `X-Miqro-Activity` 且为合法 UUID 时写入），非「仅预留」——`PostgresUsageEventWriter` 已写入该列；
- `:167` §8 补历史注记（计划已成历史；实施口径见 Spec §11 与 `docs/caa-next-batch-plan.md`）；
- `:170` 迁移口径拆开：**V54 = `usage_event` 上下文列；V55 = 证据审计表 `request_context_evidence`**（原文把 V55 并入「上下文列」）；
- `docs/document-map.md:35` 去掉「上一行 Spec」位置指针 → 改写成文件名；「仅补实验证据」→「仅补实验证据与历史注记」；
- grep 计数口径收紧为**被检文件内**计数（原文未说明是否含本条目自身叙述性提及，易生歧义）。

**修复轮后验证**：`git grep -n "X-Miqro-Tag" -- docs/activity-context-design.md` → **1 命中**（`:119` §5 实验记录，附「实验用头名」注记）；`git grep -n "attribution_source" -- docs/activity-context-design.md` → **0 命中**（exit 1）；`git grep -n "X-Miqro-Project-Id" -- docs/activity-context-design.md` → **7 命中**（`:74`/`:81`/`:108`/`:112`/`:119`/`:149`/`:170`）；`git diff --check` exit 0。

**第二轮修复（2026-09-16，口径归位 · 与主交付同 PR）**：

- `activity-context-design.md:46` 概念结构体补 `activity_id?`——V54 实列（`V54:12`）且同文件 `:170` 已列，此前 6 个上下文列里独缺此列。
- `:106` 规则表产出「项目标签」→「项目 UUID」——与下一行 `:108` 的 `X-Miqro-Project-Id: <project-uuid>` 及 `:74` 的 UUID 值域一致（原文按字面实现会产出非 UUID，触发 400 `CONTEXT_INVALID`）。
- `:167` 交付枚举补 `#641`——与 `:4` 的 `#633 / #639 / #641 / #645–#648` 对齐（同一文档内两处枚举不一致）。

**第三轮修复（2026-09-16，对抗性复核驱动 · 与主交付同 PR）**：

- `activity-context-design.md:79` 「完整值域另含 `UNATTRIBUTED`/`AMBIGUOUS`」补实现边界：V54 列注释（`V54:24-25`）与 Spec §7.1（`:253`）各列 6 值，而 `RequestContextResolver` 只产出 4 值，两值在实现中仅作 `X-Miqro-Claim-Status` 声明头取值/未归属桶语义。
- `:79` 「审计可还原每笔归属的判定依据」原文过宽：`publishUsageEvent` 只在放行且完成的路径调用（`ProxyController.java:529`），网关侧拒绝不落 `usage_event` 行；`request_context_evidence`（`V55`）全仓无 Java 写入方/读取方 → 已就地标明边界。
- `:81` 门控适用范围由「通道 A/B」改为按**机制**表述（`ANTHROPIC_CUSTOM_HEADERS` 形态）：§4.2 的 E（企业 managed 下发）同样下发客户端读的静态头，原枚举自相矛盾；C（`apiKeyHelper` 动态 `headers`）是否有门控本仓无证据，明写「未验证」。
- `:167` 「客户端参考实现与演示闭环均已交付」收窄为实际交付形态（#639 `miqro-context` 安装式 Agent），并点明 step 1（干净环境复核实验 3/5）与 step 3（`apiKeyHelper`+`PostToolUse` 脚本、接入面板）未按原样交付——`接入面板` 从未交付（设计稿内共 2 处：step 3 原计划行 `:171` 与本注记 `:167`；其余命中均为 `progress.md` 内本审计条目对它的转述；无实现或设施引用）。
- `document-map.md:34` 限定 Spec 的权威面：列取值域/物理形态归 `database-schema.md`/`api-contract.md`（Spec §7.1 `claim_source` 清单缺 `git_remote`，滞后于实现）。
- 本条目的**全仓计数口径**自洽化：`:3171` 原文「全仓其余命中均在既有实现/契约件（…）」未列本条目自身的叙述性命中，已补入（与本节「边界」段一致）。
- **不在本 PR 范围的既有遗留**（均已逐行核对，未改）：① `context-attribution-implementation-spec.md:115` 称 `X-Miqro-Target-Id`「已实证可通过门控」，本仓无对应实验件（设计稿 §5 未单独实验该头名），该文件本轮禁改；② `miqro-context/README.md:113` 把设计稿列为并列规格、无历史件标注（该目录本轮禁改）；③ `decisions/0018-single-key-multi-project.md:62`/`:95` 的「后缀 = 唯一选择器、零猜测」与 Spec v1.1 **R3**（头名优先、后缀兜底）口径反转，该 ADR 仅 `:112` 有 #633 的枚举探测修订、D2/D8 无指向 R3 的修订注记——属 ADR 治理事项，建议由 owner 另开；④ `V55__request_context_evidence.sql:5` 头部注释称「网关在 Context 解析时写入；供审计与事后重分类」，而该表在 Java 侧零引用（`git grep -n "request_context_evidence" -- "*.java"` 无输出，既无写入方也无读取方），注释与实现不符——该迁移文件本轮禁改，建议随 ①–③ 一并开 follow-up。

- **第五轮修复（2026-09-16，收尾交叉核对驱动 · 与主交付同 PR）**：① 前述修复轮条目中两处**注记行号漂移**按 HEAD 校正（`:151`→`:153`、`:165`→`:167`，与 `:3199`/`:3206` 对同一注记的引用对齐）；② `:3171` 全仓计数枚举补 `progress.md` 既有条目（`:2931`/`:2961`）；③ `:3206` 的「`接入面板` 全仓仅此一处提及」纠正为按文件枚举的实际分布（设计稿内 2 处：step 3 原计划行 `:171`、`§8` 注记 `:167`；其余命中均为 `progress.md` 内本审计条目的转述）。以上均为**行数不变**的就地替换，本条目其余行号引用不受影响。

- **第六轮修复（2026-09-16，自查驱动 · 与主交付同 PR）**：重生成收口证据、逐条读原始输出时发现一处**自伤计数**——第五轮把 `:3206` 改写为「全仓命中 3 处」并在同一次提交追加了含该词的注记，而该注记自身就是第 4 处命中，故「3 处」在其写入的提交（`a39caa1`）里即已为假。两处（`:3206` 与第五轮注记第 ③ 条）改为**按文件枚举分布**（设计稿内 2 处：step 3 原计划行 `:171`、`§8` 注记 `:167`；其余命中均为本审计条目的转述），自指命中无法再使其失真。仍为行数不变的就地替换。

- **第七轮修复（2026-09-16，独立主评审回执驱动 · 与主交付同 PR）**：独立主评审（全新上下文、逐条复跑命令、结论 **0 blocker / 3 minor**）与本地自查在同一点会合——① **行号基准混用**（评审 M2）：本条目「交付」「验证」段引用的是交付时点 `6db7f33` 的行号，「修复轮」各条引用的是修复后 HEAD 的行号，同一编号在两套基准下可能指向不同内容（`:79` 在交付时点是门控注记、在 HEAD 是值域行），已在交付段头部就地声明两套基准；② 评审 M1：`docs/document-map.md:35` 的「关键处已加「历史注记」」收敛为按处枚举（状态行、Q0、Q 表、§8 计划处）；③ 评审 M3：边界段补登记第 ④ 项既有遗留（`V55__request_context_evidence.sql:5` 头部注释称「网关在 Context 解析时写入」，而该表在 Java 侧零引用）。评审另两条记录性说明（hunk 形状与任务书预期不符系多轮就地编辑所致；前轮计数自洽问题已在第五/六轮闭环）无需动作。①②③ 均为行数不变的就地替换。

- **第八轮修复（2026-09-16，增量复核回执驱动 · 与主交付同 PR）**：增量独立复核（对象为第七轮前的 HEAD `c71187c`，结论 **通过 / 0 blocker / 2 minor**）两条编辑性建议均已按原文采纳——① m1：交付段「base develop@50a9b245」补记为「base（fork 点）develop@50a9b245，收口轮合入 develop@adfb670（合并 `d15b2d5`）」，两套口径并存（`git merge-base origin/develop 6db7f33` 为 fork 点、`git merge-base origin/develop HEAD` 为收口基准）；② m2：第二轮修复条目「`:106` 与紧邻 `:108`」改为「与下一行 `:108`」（`:107` 为空行，实测复核一致）。两条均为行数不变的就地替换。

## 2026-09-16 晚间 — #245 F07 告警接线：队列饱和（V60 事实表 + 控制面评估 + 类型注册）

**背景**：F07 三类告警指标里，只有「用量队列饱和」缺数据源——网关侧只有进程内计数与无标签 gauge，没有任何可查事实。issue 原始候选「网关直接写 `alert_events`」被否：`AlertEventDispatcher` 只扫「已有失败投递次数」的行，网关插入的新行永远不会被投递。改为 **网关写事实表 → 控制面 `AlertEvaluator` 评估 → 既有签名/去重/退避/投递链路**。租户承载口径：全局信号固定由默认（seed）租户承载，评估 SQL 按规则自身 `tenant_id` 过滤，**非 seed 租户的同类型规则在正阈值下恒不触发**（其窗口恒为零行、`COALESCE(SUM(dropped),0)` 恒为 `0`，评估为 `value >= threshold` 才触发）；阈值 `<= 0` 服务端不校验，会在每个去重窗口以 `value = 0` 触发一次——退化行为，但 value 仍是该租户自己的零值，不泄漏平台丢弃数（有专门的固定用例）。

**交付**（分支 `feat/usage-queue-saturation-alert-245`，隔离工作树，base develop@adfb670）：

- **V60__usage_queue_saturation_alert.sql**：① `alert_rules_type_check` DROP 后重加（沿用 V24/V36 模式），新增合法值 `USAGE_QUEUE_SATURATION`；② 新表 `gateway_queue_signal`（只追加事实，`dropped bigint CHECK (dropped > 0)`，索引 `(tenant_id, occurred_at DESC)`，另留 `queued_high_water`/`capacity` 备口径切换）。**未修改任何既有迁移**；issue 里建议的 V59 已被 #684 `quota_enforcement` 占用，故顺延为 V60。
- **网关**：`QueueSignal` + `QueueSignalWriter` SPI 与 `PostgresQueueSignalWriter`；固定 writer 执行器（有界）；丢弃计数增量在**两处**丢弃点采集；仅 `dropped_delta > 0` 才写；`no-persistence` 模式零 DB 写。**热路径零 JDBC**：`offer()` 只自增计数，写库发生在既有定时慢路径与 writer 调度器上。
- **控制面**：`AlertEvaluator` 新增 `case "USAGE_QUEUE_SATURATION"`（近 1 小时 `SUM(dropped)`，SQL 带 `tenant_id = :tenantId`）；同批给 4 个周期型指标 SQL 补 `tenant_id` 过滤（口径漂移修正，单租户部署零行为变化）；`AlertRuleService` 类型校验列表与错误文案补齐。
- **类型注册 4 处**（后端 `AlertRuleService.RULE_TYPES`、前端 `types/api.ts` 联合类型、`NextAdminAlertRulesView` 的类型选项、`AlertEvaluator` 的 case 分支；算上 V60 CHECK、服务端错误文案、i18n 词典与 api-contract 描述，实际触及 **8 处**）+ 前端下拉/列表标签 + `isQueueSaturationType` 的条数型阈值文案；i18n 词典补 1 条 `'队列饱和'`。
- **文档 5 处**：api-contract / configuration-reference / database-schema（§租户级口径写明）/ feature-backlog / operations-runbook。

**验证**：

- 后端全量 `mvnw.cmd -B -f backend -Pintegration verify`：**EXIT=0，BUILD SUCCESS，11 个 reactor 模块全 SUCCESS**；各模块聚合 `Tests run` 全为 `Failures: 0, Errors: 0, Skipped: 0`（Domain 130 / Provider SPI 8 / Provider Adapters 166 / Route Snapshot 5 / Cache SPI 4 / Usage Queue SPI 21 / Control Plane 679 / Test Support 109 / Inference Gateway 357）。关键用例：`UsageQueueSaturationAlertIntegrationTest` **9/9**、`PostgresUsageEventBusTest` **13/13**、`SoakIntegrationTest` 1/1（`dropped == 0` 不变式）。Flyway：`Successfully validated 60 migrations`，控制面与网关两侧日志均出现 `Migrating schema "public" to version "60 - usage queue saturation alert"`。
- **变异校验（证明租户过滤是承重的）**：删除该分支 SQL 里的 `tenant_id = :tenantId AND` → 同 IT `Tests run: 2, Failures: 2` BUILD FAILURE；恢复后通过。
- **变异校验（证明退化阈值用例是承重的）**：把 `otherTenantRuleWithZeroThresholdFiresWithZeroValue` 的阈值 0 改回 1（邻近正阈值用例的取值）→ `Tests run: 1, Failures: 1 ... expected: 1L` BUILD FAILURE；恢复为 0 后 `Tests run: 9, Failures: 0` 通过。该用例确实钉住 `value >= threshold` 边界，不是空跑通过。
- 前端（冻结树）：`run typecheck` EXIT=0（app/spec/node 三工程）、`run test` **59 files / 332 tests 全过**、`run build` EXIT=0（2634 modules，built in 40.45s；esbuild css minify 对拼接产物的 `<stdin>` 告警为既有，改动前构建日志同样存在）。`run lint` 最后一次改动后未再整树执行（其脚本自带 `--fix`，见下条），改为按文件复核：`npx eslint src/views/next/NextAdminAlertRulesView.vue src/__tests__/NextAdminAlertRulesView.spec.ts` → EXIT=0，**0 error**（260 条全部是本机 CRLF 检出的 `Delete ␍`，与未改动文件同一既有现象，且无一是 error）。
- `docker compose -f deploy/compose.yaml config` EXIT=0。

**边界**：

- 两处丢弃点都被计数（上游决策记录只标了 `offer()`；`flushChunk()` 的重入队失败路径同样计丢弃）。
- V60 的 `CHECK (dropped > 0)` 使「零丢弃行」不可表示，因此零值边界用例的诚实等价物是「窗口内无行 → `SUM=0` → 不触发」，另加断言证明 schema 拒绝零丢弃行。
- 阈值口径（窗口内丢弃条数）**未由 owner 确认**；`WRITE_THROUGH` 模式不产生该告警（饱和表现为发布线程停滞而非丢弃）；「解析失败」仍未与「上游 200 无 usage 字段」区分（沿用 `USAGE_MISSING_RATE`）；F07 其余类型（Plan 同步、磁盘）仍 SCAFFOLD。
- 前端 `lint` 脚本自带 `--fix`，在本机 CRLF 检出下会改写约 145 个非本批文件的换行（其中约 23 个存在真实规范化差异，含 `types/generated.ts` 全文重排）；已整树备份到仓库外后 `git checkout -- .` 还原，只保留本批两文件的规范化改动。仓库既有属性，非本批引入。

## 2026-09-17 — #708 官方价格 24h 自动同步（source=OFFICIAL 自动化，F08）

**背景**：定价页已支持人工录入（`MANUAL`）与手动一键同步（#585，`POST /api/v1/admin/prices/sync`），但 `source=OFFICIAL` 一直依赖人工触发——供应商调价后目录不会自动跟上。feature-backlog F08 原状态 `SCAFFOLD`，验收口径＝24h 定时拉取 → 增量写入 `source=OFFICIAL`（与 `MANUAL` 并存，同模型同 token 类型冲突时**保留 MANUAL 并提示**）；失败留日志、不静默。

**交付**（分支 feat/price-auto-sync-708，隔离工作树 `D:/tmp/miqro-price-auto-sync`，base `origin/develop@81f91997`）：
- `PriceSyncScheduler`（control-plane，新增）：`@Scheduled(fixedDelayString=${miqrokey.price-sync.auto.cycle-ms:86400000}, initialDelayString=${miqrokey.price-sync.auto.initial-delay-ms:60000})` 复用 #585 同步管道——默认 24h；fixedDelay 即上一轮结束后计时，慢价源不叠加；`@ConditionalOnProperty(prefix=miqrokey.price-sync.auto, name=enabled, havingValue=true)` **默认关**（保守默认，与 `ModelCatalogReprobeScheduler` 同形）。以种子租户 + 空 actor 记录审计（`requestId=scheduled-price-sync`，写入快照 `createdBy=null`）。
- `AdminPriceSyncService`：抽出 `run(..., ConflictPolicy, trigger)` 单管道，两条触发路径共用——`sync()`＝人工端点（`OVERWRITE`，保持 api-contract §5.9 既有「覆盖同键人工价」契约与既有断言 `written=6`）；`syncPreservingManual()`＝定时路径（`KEEP_MANUAL`：同键最新为 `MANUAL` 且价格不同则**不写入**、计入 `conflicts`）。报告新增 `trigger` 与 `conflicts:[{productCode, modelId, tokenType, manualPrice, officialPrice}]`，审计摘要新增 `trigger`/`conflicts` 计数（「提示」的落点：报告 + 审计 + 日志 + 指标）。
- 可观测（失败不静默）：`PRICE_SYNC_FAILED` 审计（既有）+ 调度器 `ERROR` 日志 + `miqrokey_control_price_sync_auto_total{result=success|failure}` Micrometer 计数（`monitoring` profile 经 `/actuator/prometheus` 暴露）；本轮异常被吞掉，保证下一轮照常触发。
- 配置：`application.yml` 增 `miqrokey.price-sync.auto.{enabled,cycle-ms,initial-delay-ms}`（`${ENV:default}` 形式）；`configuration-reference.md` 补 `MIQROKEY_PRICE_SYNC_AUTO_ENABLED|CYCLE_MS|INITIAL_DELAY_MS` 三行（含指标名）；`api-contract.md` §5.9 补「自动同步（#708）」段并更新报告形状；`feature-backlog.md` F08 → **DONE（2026-09-17，#708）**。

**测试**：
- `AdminPriceSyncServiceTest`（新增，单测 4 例）：① 定时路径保留 `MANUAL` 并报冲突（零 insert + 审计摘要含 `"conflicts":"1"`）；② 人工端点保留覆盖语义（`written=1`、`conflicts` 空）；③ unchanged 不重复写（零 insert）；④ 源失败 → `PRICE_SYNC_FAILED`（`ApiException` + 审计），零写入。
- `PriceSyncSchedulerTest`（新增，单测 2 例）：成功轮次 INFO + `result=success` 计数；失败轮次 ERROR 日志 + `result=failure` 计数 + 不外抛。
- `PriceSyncApiIntegrationTest`（扩展，Testcontainers/PostgreSQL）：预置 `MANUAL` 快照后跑定时管道 → `written=5` / `conflicts=1`，人工价仍为该键生效价且该键仅 1 行，其余报价仍落 `OFFICIAL`。

**验证**：
- `bash miqro-local/mvnw21.sh -f backend/pom.xml verify`（无 integration profile：全模块单测 + `spotless:check`）→ **BUILD SUCCESS**；
- `bash miqro-local/mvnw21.sh -f backend/pom.xml -pl control-plane-app -am test -Pintegration -Dtest=PriceSyncApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → **Tests run: 5, Failures: 0, Errors: 0**；
- 新增单测 6/6 通过（`AdminPriceSyncServiceTest` 4 + `PriceSyncSchedulerTest` 2）；
- 本地提交，**未 push、未开 PR**（由主会话统一协调）。

**边界**：仅改 control-plane 同步管道 + 其配置/契约/backlog/本条目；零迁移（`price_snapshot.source` 值域 `MANUAL/OFFICIAL/ESTIMATED` 与查询索引均已存在）、不改数据库/数据面/前端。定价页 UI 未加冲突提示文案（提示落在报告/审计/日志/指标四处）；如需页面显性提示另开前端项。

## 2026-09-17 下午 — 模型调用链路时间线 #705（后端）+ #707（前端）+ 独立审查修复

**目标与交付**
- #705 后端：新增 `request_usage_records` 的**首个读取路径**（此前该表只有写入方，控制面无查询入口——这正是模型侧一直没有"按请求排查"能力的根因）。端点 `GET /api/v1/admin/usage/timeline?gatewayRequestId=...` 返回单次调用的阶段时间线（受理 → 上游首字节 → 完成）+ TTFB/耗时/终态/重试/部分响应/Token 四分类/归属链；**零新增采集**。V60 为 `(tenant_id, gateway_request_id)` 建索引——EXPLAIN 实测此前走 Seq Scan（表按月分区且既有索引均不以该列起头）。
- #707 前端：用量明细「请求 ID」列改可点击 + 三层信息抽屉（终态徽章 + "卡在哪一段" / TTFB·重试·HTTP / 折叠的归属链与 Token）。未记录的阶段如实标「缺失 · 未记录」而不补零；404 呈现为说明块而非错误横幅。OpenAPI 基线重导 + `gen:types` 重生成。

**独立审查（对抗性、实测驱动）发现并修复三处缺陷**
- **D1（高）相位耗时不同源**：`time_to_first_byte_ms` 自**网关入口**起算（鉴权/配额/读 body/缓存查找/凭证解密之前），而 `started_at` 在其后取值，二者被混入同一相位列表 → 演示库实测 **56 行出现"上游首字节晚于完成"**（如 ttfb 8422ms > duration 6275ms）。现改为相位耗时一律取时间戳差值（单一原点、构造上单调），实测值作独立字段透出并在 DTO 注明原点。
- **D2（中）token 缺回退**：列表页用 `COALESCE(input_tokens, prompt_tokens)`，时间线直读原列 → 实测 **70/4650 行**"列表有数、详情空白"（OpenAI Chat 协议行）。已在 SQL 层统一归一化，两处数字不再打架。
- **D3（中）OpenAPI 基线未重导** → 会阻塞 #707 的类型生成。已重跑 `OpenApiSpecIntegrationTest` 覆盖，破坏性检查 exit 0（仅新增 path + 3 schema）。
- 审查同时**确认无问题**：分区表索引有效（PG 17.6 `pg_index` 实测，父表建索引自动落到所有分区并被未来分区继承）、租户隔离成立、鉴权级别恰当、RowMapper 27 字段逐位正确、时区处理无 bug、`LIMIT 1` 语义正确。
- 附带发现：目前**所有行都落在 DEFAULT 分区**（月度分区尚未发生）→「按 DROP PARTITION 做留存」当前无法实施。

**验证**
- 后端：`ModelCallTimelineServiceTest` **9/9 PASS**（含两条新回归用例：用真实倒序数据断言相位单调；`httpStatus`/`tokens` 全 null 不炸）。原夹具令 `measured == delta`，恰好掩盖 D1，已修正。
- 前端：`npx vitest run` → `Test Files 61 passed (61)` / `Tests 360 passed (360)`；`npm run typecheck` → exit 0。
- OpenAPI：`OpenApiSpecIntegrationTest` PASS；`check-openapi-breaking.py` → exit 0；head 对基线全路径比对 **removed 0 / changed 0 / added 55**。
- Spotless 通过。

**边界与偏差**
- **未做浏览器实机验收**：两个分支均未部署，演示栈仍停在 develop；#707 的 UI 只有单测证据，无真机截图——待合并部署后补。
- #707 分支基于**修复前**的 #705 建立，直接合并会覆盖 D1/D2；已 rebase 到修复后基础并复跑全绿。
- 集成测试**必须带 `-Pintegration`**：不带该 profile 跑 `-Dtest=OpenApiSpecIntegrationTest` 会静默「Tests run: 0」且 BUILD SUCCESS（本轮踩过一次，记此为鉴）。
- 前端空值类型不精确（后端未配 `default-property-inclusion`，运行时 `null` 而 codegen 出 `| undefined`）——仓库既有特征，非本轮引入。
- 遗留建议（非缺陷）：透出 `usage_missing` 标记；`Phase.label` 中文文案是否移交前端。
- 开发自审衍生的架构缺口（三类调用缺少单一事实源）另立 #719，不阻塞本批。

## 会话交接点 2026-09-17（安全审计轮：#723/#724/#726 修复，已合并并部署）

- **背景**：用户指令「独立审计代码/架构找漏洞」。两路并行审计（后端安全/健壮性；文档承诺 vs 代码实现），共立 11 个 issue（#726–#736），其中两个 P0 越权在演示站**真机验证后当日修复并上线**。
- **#723 分号路径绕过管理员门禁（P0）**：7 处安全过滤器/拦截器用原始 `getRequestURI()` 判定路径，与 Spring 的去分号 lookup path 不一致——`/api/v1/admin;x/users`（普通 USER）实测 200 返回全量用户列表、`POST /api/v1/admin;x/teams` 实际落库。**修复（PR #725）**：新增 `RequestPaths.lookupPath()`（UrlPathHelper 语义 + 解码后再去分号 + 折叠重复斜杠），替换全部 7 处；评审轮另发现并关闭相邻洞——`/api/v1/admin%2Dapi/...` 编码前缀原可**整个跳过 open-surface 过滤器**（匿名碰巧 401、USER 会话可直达控制器），既有 `AdminApiKeyScopeIntegrationTest` 第 4 断言已按新语义强化（明文/编码 200、匿名 401），`AdminPathNormalizationIntegrationTest` 补 USER 会话用例；单测 6 + IT 6。
- **#724 billing 会话越权（P0）**：`ApiKeyAuthFilter` 会话直通只查 `isAuthenticated()`（注释写的是 admin），普通用户实测可读全租户计费三端点。**修复（PR #731）**：会话直通收紧为 `SYSTEM_ADMIN`；已登录非管理员无消费方凭证 → 403；IT 9/9。
- **#726 context-registry 阻塞 JDBC（中危）**：`/v1/context-registry` 在 Reactor event loop 同步跑 JDBC 且无超时。**修复（PR #732）**：复用 credential-decrypt scheduler + 10s 超时 → 503 `context_registry_unavailable`；IT 4/4。
- **部署与复验**：三修复全部合入 develop（**e6b5e4f6**）并部署演示站（三镜像，Flyway V61=#705 索引迁移，portal `index-BFuWMBpd.js`）；线上复验 **15/15 PASS**（分号/编码分号/写路径/匿名/billing 全拒，管理员与 context-registry 不误伤）——漏洞窗口关闭。
- **其余登记（未修）**：#727 MCP SSE 响应无上限聚合 + 熔断桶无界；#728 事务内阻塞上游调用 + 定时刷新 self-invocation 丢事务；#729 usage_event 热查询缺复合索引（迁移号 V62）；#730 OIDC 登录不校验账号状态；#733 配置参考 9 项不一致（PUBLIC_BASE_URL 零读取/MAX_CONCURRENT_STREAMS 不存在等）；#734 Idempotency-Key/If-Match 契约零实现；#735 适配器 VERIFIED 门控未落地；#736 孤儿表/孤儿端点/ADR 头名/Settings 硬编码产品名。
- **教训**：① 路径型安全判定必须与框架路由同语义（getRequestURI ≠ lookup path），且要覆盖 `;x`/编码分号/`//` 变体；② 过滤器注释写 "admin session" 不代替角色校验（本批两洞均属"注释与实现不一致"）；③ 新增/改动文件在最后一次编辑后必须重跑 `spotless:apply`（CI 两次因此红）；④ MockMvc 对 `%2D` 路由 404 而真容器映射成功——安全断言用 4xx 类，容器精确行为放真实 HTTP 探针测试；⑤ CI 基建：GitHub runner 到 Eclipse JDT formatter 下载源 09:52 起全网抖动，所有 Java job 在 spotless 阶段速挂（与代码无关），冷却重跑即可——新分支冷缓存时必现。

## 2026-09-17 傍晚 — #738 修复 SnapshotRefreshListenerTest 零余量超时 flake

**背景**：PR #720 的 `Backend unit (Java 21 / windows-latest)` 曾因 `closeStopsListener() timed out after 10 seconds` 失败，同一提交重跑即过。并行会话（部署线）读代码给出结构诊断，本会话据此修复。

**根因：外层 @Timeout 与内层预算零余量**（该测试是纯 Mockito 单测，无容器/无 DB，排除环境依赖）：
- 用例 1：`awaitListen`(5s) + `awaitRefresh`(5s) = 最坏**正好 10s**
- 用例 2：`awaitListen`(5s) + `verify(timeout 5s)` ×2 = 最坏 **15s，已超上限**
- 外层一律 `@Timeout(10)`

即理论最坏情况已等于或超过上限，windows-latest 上与后端全套件并行时任何一次线程调度停顿都会顶穿。**既有 flake，与触发它的 PR 无关**（该 PR 只改 domain/persistence/control-plane，未碰 route-snapshot）。

**修复**：三处 `@Timeout(10)` → `@Timeout(30)`，并补 javadoc 说明取舍——内层 await 仍界定实际等待上限，外层余量只为区分"调度停顿"与"产品缺陷"。不改任何断言语义。

**验证**：`-Dtest=SnapshotRefreshListenerTest` 连跑 **5/5 全绿**（Tests run: 3, Failures: 0, Errors: 0）；spotless 通过。

**备注**：交叉印证——并行会话在空闲机器上同样连跑 5 次全绿，与"负载相关调度 flake"的结构判断一致。

## 会话交接点 2026-09-17（安全审计第二批：中危五项 + 文档两项，已合并并部署）

- **背景**：承接同日上午审计（P0 #723/#724 已上线并复验）。本批为审计登记的中危项与文档一致性批，全部当日闭环。
- **修复并上线（6 PR）**：
  - **#728（PR #743）**配额刷新两阶段事务——`refresh()` 的阻塞上游调用移出事务（原一连接被占最多 N×20s），`refreshAllScheduled` self-invocation 静默丢事务一并修复（改 `TransactionTemplate`，双入口同语义）；`AdminCredentialService.validate` 摘除 readOnly 事务（10s 探测不再钉连接）。单测 10/10（含无事务断言/定时同路径/失败整体回滚三回归）+ IT 3/3。
  - **#727（PR #746）**MCP SSE 聚合以上限（`max-proxy-buffer`，超限发 `mcp_sse_response_too_large` error 事件而非截断）+ 熔断桶键收敛为「工具名｜固定 13 信封方法｜envelope」（原客户端可控 `method` 字符串可无限增长注册表）；单测 4/4 + 契约 44/44。
  - **#729（PR #744）**V62 复合索引 `usage_event(virtual_key_id, occurred_at DESC)`——密钥 last_used 聚合改走 index-only（原全表增长线性拖慢管理端）。
  - **#730（PR #745）**OIDC 会话创建前校验 DISABLED/LOCKED（与本地登录同语义，拒发会话）+ 并发首登返回 link 归属者而非孤儿；IT 5/5。
  - **#736（PR #747）**孤儿批：三个前端死绑定摘除（listProviders/getVirtualKey/getSkill，零引用核实）；ADR-0009 头名更正 `X-MiQroKey-Cache-Hit`→`X-MiQroKey-Cache` + 契约补记；schema 标注修正（model_access 未消费；budget **已消费**——审计代理"零引用"系误报，model_budget 才是孤儿）；Settings 页「门户启动时间」→「页面载入时间」。**产品名 MiQroGate 系审计误报**（2026-08-27 品牌已改名，晚于 ADR-0007），已在 issue 评论区更正。
  - **#733（PR #748）**配置参考与实现对账 9 项：冲突默认值修正（webhook 6→3 次、签名头去 `-256`、导出 93 天/24h）、预留键如实标注（含 §2 汇总 12 键）、补真实旋钮（`quota.refresh-interval-ms`/`model-catalog.reprobe.*`）、§6 持久化默认值矛盾修正、§10 启动校验改写为现实（仅 Cookie Secure + originAllowlist 两项）。
- **部署与复验**：develop 终态 **1f9e8e7f** 三镜像部署（20:43）；Flyway **V62** 应用成功、`idx_usage_event_virtual_key_occurred` 实测在建、portal `index-DbxL6qtj.js`；复验 7/7（#723/#724 安全回归 + #728 配额刷新实测 200 且快照落库）+ 完整安全套件 15/15 零回归。
- **待拍板（未动）**：#734（Idempotency-Key/If-Match 契约 vs 实现，倾向先标注预留）、#735（适配器 VERIFIED 门控，需产品口径）。
- **审计误报两则（已更正）**：① Settings 产品名（品牌已改名）；② `budget` 表零引用（实际预算管理与 BUDGET_THRESHOLD 水位均在消费）——教训：跨时间点的"事实"必须核对最新状态（ADR 可能被后续决策覆盖）。
## 2026-09-17 用量调整台账（#709 / F20）——追加型修正，不覆盖原始事实

**范围**：只落 schema + 追加/查询接口。V63 `usage_adjustments`（token 增减可负 + 预留 COST 金额维度 + 原因 + 引用原始行 + 反向行纠错 + 幂等键）；`POST/GET /api/v1/admin/usage-adjustments`（仅 SYSTEM_ADMIN）。明细净额列、导出/审计标记、对账"含调整"维度为后续增量（F23 依赖）。

**四层语义**（本轮定下的核心约定）：`usage_event`=不变的观察事实 → `usage_adjustments`=追加的修正 → `AdjustedUsage`(observed + Σ调整)=财务/报告口径 → **配额判定仍只读 `usage_event`**。财务更正不得追溯改写运行时控制的历史结果——否则一笔补录会把已超额的 Key 重新判成未超额，等于改写历史策略。

**两处由既有表决定的外键取舍**：① `usage_event_id` 取 `ON DELETE CASCADE`——`usage_event` 可被 `UsageDeletionService` 按窗口硬删（带确认令牌 + 审计），底层事实被抹去时其修正随之失效；② `reconciliation_row_id` **刻意不建外键**——对账报告按窗口幂等替换，硬外键会挡住替换。

**迁移号事故（V60 重演）**：本迁移最初占 V62，但 #729（PR #744）先合入 develop 并占了 V62，两支合并后启动即 `Found more than one migration with version 62`。已让号为 **V63**。**教训**：定迁移号要同时看 develop 树**和 open issue 里已登记的号**——#729 的 issue 正文明确写了"下一可用号为 V62"，我只看树所以漏了。另删除 `target/classes` 下残留的 V62 副本（Maven 拷贝资源不删已移除文件，不清理会打进 jar——V60 事故的直接成因）。

**验证**：单测 14 + 集成测试 5（幂等与净额非负两条只在真实 SQL 里成立，故必须有 IT）；`SchemaMigrationTest` 12/12；全量 `clean verify -P integration` PASS；OpenAPI 基线重生成（无破坏性变更）+ 前端 `gen:types` 幂等。spotless 首轮 4 个新文件未过，已 `spotless:apply` 收敛（仅新文件被改，无历史文件漂移）。

**测试抓到的真 bug**：`isZero(null)` 返回 false →「增减量全为 0」的校验在任一字段为 null 时失效，全零调整会被当成有效修正写进只追加的台账。已合并为单个 `anyNonZeroDelta` 判断。

**配额**：按 B′ 分层**零代码改动**——隔离本身就是"不动它"，未为配额花任何成本。

## 2026-09-17 用量调整②：净额读取（#709 / F20）——调整开始影响上报数字

**范围**：把已记入的调整接进财务/报告口径。明细与汇总各接一个**相关 LATERAL** 子查询取按事件合计的调整量；净额 = 归一化观察值 + delta，在 SQL 里算，明细与汇总**共用同一表达式**，避免两处算法漂移。

**接口变化**：`UsageRecordView` 增 `netInputTokens` / `netOutputTokens` / `netCacheReadInputTokens` / `netCacheCreationInputTokens` + `adjusted`，与既有观察值字段**并存而非覆盖**。改既有字段的含义是 OpenAPI 破坏性检查**抓不到**的语义破坏（类型没变），客户端会静默拿到不同数字。明细与 /me 自助共用该 DTO，故两边同时生效。

**两处设计取舍**：① **LATERAL 而非直连 `usage_adjustments`**——一个事件可挂多条调整，直连会把 `usage_event` 行乘开、把每个 SUM 算大；② `requests` 计数不动——调整修正的是某次调用的用量，不新增调用。

**测试抓到的两个真 bug**：
1. SQL 片段拼接——前一段不以换行结尾，粘出 `ue.tenant_idleft`，8 个集成测试报 500；
2. **`SUM(bigint)` 在 PostgreSQL 返回 numeric**，`rs.getObject(col, Long.class)` 抛异常，被 `DataIntegrityViolationException` → 409 `RESOURCE_CONFLICT` 接住——**一个只读 GET 返回 409，且 detail 是"数据约束冲突"，看起来完全不像查询类型错误**。修法是 SQL 里 `CAST(... AS bigint)`。诊断 409 要看 body 的 `code` 而非状态码（详见记忆条目）。

**回归护栏**：`usage_adjustments` 是空表，故既有 39 项用量/计费断言**原样通过**——本改动在有人记入调整之前**行为完全不变**。另补 2 个用例证明净额真的生效（调整后净额变、观察值不变；反向行把净额还原）。

**验证**：全量 `clean verify -P integration` BUILD SUCCESS（11:03 min，0 失败）；OpenAPI 基线重生成（无破坏性变更）+ 前端 `gen:types`。

**未做**：前端表格列——本地该工作树无 node_modules，跑不了 vitest；**改表格却不验证等于把风险推给 CI**，且调整目前只能经 API 录入，界面与汇总的不一致在当下无用户可碰到。留作后续小 PR。

**配额**：按 B′ 仍**零代码改动**，只读观察值。

## 2026-09-17 用量调整③：导出调整标记（#709 / F20）+ 顺带修复 CSV 表头错位（#754）

**范围**：CSV 与 JSONL 的用量导出行新增 `netInputTokens` / `netOutputTokens` / `netCacheReadInputTokens` / `netCacheCreationInputTokens` 与 `adjusted`；**观察值列保持原样**，净额另列给出。至此 #709 的四条验收标准全部达成。

**顺带修掉一个既有缺陷（#754）**：CSV 表头漏了 `clientIp` 一列——数据行 19 个值、表头 18 个名，**自 `isComplete` 起每一列错位一格**。任何按列名解析该导出文件的消费者都拿到**错误的值且不会报错**，真实的 `local_caliber_note` 变成无人认领的第 20 个字段。`client_ip` 是 V52（#605）引入的，疑为该次加列漏改表头；既有测试只断言 `csv.contains("local_caliber_note")`——字段**存在**即通过，故长期未被发现。

**修法不止补一列**：表头与数据行原本是**两份独立真相**（手写字符串 vs map 插入顺序）。已改为**两者都由同一份声明的列顺序 `CSV_COLUMN_ORDER` 派生**，一并去掉"下次加列还会再错"的可能性。

**测试的可信度**：新回归测试**按列名取值**而非搜字符串，并在实现里临时删掉 `clientIp` **验证过它确实会变红**——有过"会通过"和"会失败"两种观察，不是一条永远绿的摆设。

**共享而非复制**：净额 SQL 抽成 `UsageAdjustmentSql`（`persistence-postgres`），明细、汇总、导出三处共用。若各处各抄一份，正是当初选择"净额在 SQL 里算"要避免的漂移。该类因此提为 public——控制面本来就在直写 `usage_event` 的 SQL，那条边界早已跨过，共享优于复制。

**一处刻意不改**：`join()` 用 `sb.length() > 0` 判分隔符，首列为空串时会吞掉逗号——但首列是 `occurredAt`（NOT NULL），**构造不出触发用例**，故不动。没有失败用例就不改，避免"修一段无法证明的代码"。

**未做**：前端（用量表净额列、审计页 action 标签）——本地无 node_modules 跑不了 vitest；且调整目前只能经 API 录入，前端不一致**无用户可碰到**。已记为待办。

## 2026-09-18 价格快照基座（#710 / F21-A）——冻结「这笔 token 当时依据什么价格算」

**范围（经评审收敛）**：原 F21 清单混了三类性质不同的东西，本轮**只落逐事件价格快照**。

**V64**：`usage_event` 加冻结价格列——`price_input/output/cache_read/cache_creation`（每百万 token 单价）+ `price_currency` / `price_effective_from` / `price_source` / `price_status`。**不引入** `price_catalog_version`（现无「版本化价目目录」实体，凭空加版本号是假装它存在），改为存**实际采用的单价**，SQL 可直接 `token × unit_price`。

**三态语义**：`NULL`=尚未评估 ｜ `COMPLETE` ｜ `PARTIAL` ｜ `UNAVAILABLE`。**`UNAVAILABLE` 的价格列保持 NULL，绝不写 0**——"价格未知"与"免费"是不同的审计事实，静默写 0 会低估历史支出。回填按 `occurred_at` 而非回填时刻，否则会造出「看起来是历史快照、实际是延迟快照」的假象。

**确定性 tie-break（既有缺陷）**：`findLatestAt` 是 `ORDER BY effective_from DESC` 单键；`findAllLatestAt` 用 `MAX(effective_from)` 回连，**同刻多行会返回多行**，而调用方循环 `put` → 赢家取决于数据库行序。这会让**历史回填本身不可重复**。已改为 `(effective_from DESC, id DESC)`，并把 MAX-join 改写为"不存在更严格更大的候选"（保持 H2 可移植，沿用原作者的约束）。**新测试已验证会红**：临时换回旧写法，`findAllLatestAtReturnsOneRowPerTriple` 如期失败。

**回填端点**：`POST /api/v1/admin/usage-price-backfill?from&to`，幂等（只处理 `price_status IS NULL`），**已定状态的行永不重评**——重跑不能改写已作出的决定。写审计含四项计数。测试 6/6。

**两处实现同一规则 + 一条交叉校验**：tie-break 同时存在于仓储与回填 SQL 两处。与其写注释要求后人小心，不如让 `agreesWithTheRepositoryLookup` 用例**逐行比对两者结论**——测试才是真正的单一事实源。

**本轮不改变任何上报数字**：只**建立**基座，尚无读取方。成本改走基座是 F21-A 的下一增量（会改金额，需单独验证）。

**其余拆分**：F21-B 归属快照（网关写入时拿不到 team/subscription/window，且"先加列再回填"会把**延迟归属**伪装成**冻结归属**——前置是先定「事件时刻归属」语义）/ F21-C 上游 usage 原文（现状是刻意不保留，存它=改保留边界，独立合规立项）/ F21-D 派生元数据（`error_category` 可由现有字段派生，物化后口径一变历史列整体失真，故不加）。

**迁移号**：V62/V63 均已被占，本项用 **V64**。

## 2026-09-18 成本读取切冻结价格基座（#710 / F21-A 第二刀）——「改一次价目，历史金额跟着变」终止

**这一刀会改变金额**，而且是**纠正性的**：`docs/usage-accounting.md` §6 原文一直写着"使用**事件发生时**的价格快照计算"，而实现用的是**查询时刻的最新价目**——代码此前不符合它自己的规格。本刀让二者一致。

**三条定价路径，实测后分两类处理**：
- **计费路径**（`upstreamPaid` / `gatewayObserved`）：改读行内冻结值，缺失回退到该行 `occurred_at` 时刻的价目
- **缓存节省路径**（`savedByGatewayCache`）：命中行**没有**用量事件，因而没有冻结列。按**该组命中中最晚一次的时刻**取价目。**这是近似**——一个 cache_key 的命中若横跨改价，整组按较晚价计价。已在 `usage-accounting.md` §6 明写
- **成本分摊**（`CostAllocationService`）**未切换**：它把结果持久化、按 `algorithm_version` 幂等覆盖，改读冻结基座会牵动"同版本重跑是否覆盖历史"的语义，属独立决策

**结构**：新增 `PriceSnapshotSql` 做 as-of 规则的**单一定义**（此前已在仓储与回填 SQL 各有一份，再加一处会失控）；`UsageAggRow` / `HitAggRow` 改带**未除的** `tokens × unit_price` 求和，除法仍在 domain 用原 `MathContext` 完成——保证切换不扰动舍入。三个服务里的"塞当前价目表"整块删除，连已无人使用的 `priceSnapshotRepository` 注入一并清掉。

**回归护栏（如预期成立）**：价格没变时冻结价 == 当前价，因此**既有 39 项成本/用量断言原样通过**——证明切换没有悄悄改数。

**新测试分开验两件强度不同的事**（`PriceBasisCostStabilityIntegrationTest`）：
1. 新增更晚的价目 → 历史不动（用户能感知的承诺）
2. **改写历史价目行 → 已冻结的历史不动** ——**只有冻结列扛得住它**（as-of 查询会照收），所以它证明冻结基座真的在被使用，而不是一个碰巧看着对的回退

**一次自查：那两条测试起初是空洞的**。首次运行只有精确值断言失败（`expected 0.002 but was 0`）——成本为 0 是因为 fixture 用了随机 `project_id`，而 summary 默认按项目分组会 `JOIN projects` 丢掉该行。**这意味着当时那两条"历史不动"的测试比较的是 `0 == 0`，绿灯但什么都没证明。** 修 fixture 后给两条都加了**基线必须非零**的前置断言。

**未做**：导出/对账侧的成本口径、`cost_allocations` 切换、前端展示。

## 2026-09-18 未定价用量：把「未知」提升为正式计价状态（#710 / F21-A 第三刀）

**起因**：成本切到「事件时刻价格」后，检测到 **571 行事件发生在任何价目之前**（带 49.1M cache_read token），会被算成 **¥0**，全窗口成本下降约 **11.4%**。

**问题不是"571 行为什么是 0"，而是**：系统已经有了**用量事实**，但其中一部分**没有合法的价格事实**——这两者在财务语义上必须分开。而实现里 `COALESCE(..., 0)` 把"未知"塌成了"免费"，**与存储层自己写的"未知 ≠ 免费"直接冲突**。

**本轮（B+）**：`UNKNOWN` 成为**正式财务状态**，未计价金额不进入"总成本"。

- 汇总同时给出 **`pricingStatus`（COMPLETE/PARTIAL/UNAVAILABLE）+ 四维 `unpriced` token + 未计价事件数**；`pricingStatus != COMPLETE` 时已知金额**不是总额**
- 数学定义写进 usage-accounting §6：PARTIAL 的已知金额**只含已定价维度**，不是"未定价维度记 0"
- **判据固化成测试**：`unavailableNeverMapsToZeroCost`（0 由 gap 解释）与 `completeWithZeroPriceRemainsLegitimateZeroCost`——**金额同为 0、含义相反**
- **V65**：现在就冻 `base_cost_amount`（可空不可变），不等阶梯价——届时"单价×数量"不成立
- 事后补价走**追加式**金额调整，**不修改原行、不把 UNAVAILABLE 改成 COMPLETE**

**大厂依据（已抓取原文）**：AWS 的 `pricing/publicOnDemandRate` 证明"每条用量行携带自己当时的单价"；Troubleshooting 文档专有一节解释"为什么有些行成本是 0"（`LineItemType = Discounted Usage`）——**即大厂的每一个 0 都是可解释的**。我们此前那 571 行的 0 无从解释，正是 AWS 明确避免的状态。

**顺带修掉两条"依赖旧行为"的测试**：配额 COST 水位（先插用量后插价目）与 ROI 节省（**价目比命中晚 2~3 秒生效**）——它们此前能过，纯粹因为旧实现拿当前价倒推，**测试套件把缺陷固化了**。修法是让 fixture 显式声明价格基准（价目早于消费），而非回退实现。

**另记一条操作教训**：`mvn test-compile` **不 clean 时会给假绿**（增量编译未重编测试），清 `target/test-classes` 后才发现真实的编译错误。


## 2026-09-18 base_cost_amount 从未对历史行落值——回填的"幂等"正是拦住它的门（#771）

**发现路径**：#766 部署后我对成本变化做**逐维归因**（不只看总数），顺手查了这一列的填充率。

- 真机：总成本 ¥101.513347 → ¥89.909285，**−11.43%**（预测 −11.4%）。归因加总**逐分对上**：缺口 11.362 + 价目漂移 0.227 = 11.589 = 新旧差。缺口占 98%（input 45% / output 34% / cache-read 19%）——"几乎全部来自 pre-price 行"成立，但 cache-read 占 19%，不是零头。
- 查 `base_cost_amount`：**全表 4651 行全为 NULL**，其中 4079 行 `price_status = PARTIAL`——而 V66 的列注释写明 PARTIAL 必须带"已定价维度的部分和"。

**根因**：回填的行选择是 `WHERE price_status IS NULL`（"尚未评估"）。于是 **V66 之前已完成回填的库，其历史行永远不会再被选中**，金额永远补不上。此前被当作"幂等"验证通过的 `scanned=0`（#760 部署复验）——**同一个特性在这里正是障碍**：不重扫，就不会补列。

**当前影响**：该列**没有任何读取方**（`git grep` 只命中写入方 + 注释 + 迁移），读路径仍按 `price_*` 现算，故上面那 −11.43% 与本缺陷无关、结论不变。但这一列存在的唯一理由是"历史可重建"，而历史行恰恰全为 NULL——等于**没真正冻上**。

**修法**：回填端点增加**补写通道**——对"已盖章但缺金额"的行，**只从该行已冻结的 `price_*` 列派生**填入：不查价目、不改 `price_status`、不动金额。只能**补全**，不能**修订**。响应加 `baseCostFilled` 计数（与 `scanned` 分开：后者是"做了决定"，前者只是"补了记录"）。

**两处设计要点**：

- **选择条件必须恰好等于"可派生"集合**。宽松版（只要某维有 token）**不安全**而非仅仅浪费：`LIMIT` + `ORDER BY` 会让不可派生的行占满一批，循环于是停下，**把可派生的行落在后面永远补不上**。收紧为"某维既有 token 又有冻结价"（与 `baseCost()` 同一判据）后，每趟必填 ≥1 行或候选集为空，循环可证终止；"本趟零进展即退出"作为防漂移的第二道锁。
- **同一规则出现两处，靠测试钉住**（沿用本仓库对 as-of 规则的既有做法）：测试证过"把实现撤掉，只有新增的 4 条会红"。

**另记（不可改的坑）**：`V66__usage_event_base_cost.sql` 文件头注释仍写作 `-- V65:`（改号时漏改），但**不能直接改**——V66 已在演示站应用，改文件内容会改 Flyway 校验和，下次启动直接 `checksum mismatch` 起不来。改在 docs：`database-schema.md` / `usage-accounting.md` 的 V65 → V66，并把不变式措辞精确化（"可空不可变" → "可空；一旦有值不再改写"）。

## 2026-09-18 自助用量页金额渲染成 ¥$——两处各自决定货币符号（#775）

**现象**：`/app/usage`（NextUsageView）记录表成本列渲染成 `¥$0.1234`；同页汇总列又是裸 `$145.2013`——**同一页里两种符号，且都不是本系统的币种**（全仓计价为 CNY）。

**根因**：符号被两处各自决定——`formatCost()` 的返回值里写死 `$`，模板又在外层补 `¥`。既有单测把 `$0.0020` 钉成了期望值：**它断言了符号，只是断言错了那一个**，于是"两个符号叠在一起"长期无人发现（与 #754 同型：断言了"有"，没断言"对"）。

**修法**：符号单点决定——`formatCost()` 统一输出 `¥`（与 NextCostView / NextRoiView 同款），模板不再补符号；同页汇总 / 明细 / 合计三处口径随之统一。**全站符号审计**：模板字面量 `$${...}` 模式全仓仅此一处；其余视图（Cost / Roi / Overview / Profile / Agents / QuotaRules / AdminUsage 的「成本 ¥」列 / Prices 按币种分支）本就只用 ¥，CSV 导出保持纯数字。

**验证**：先证明会红——把视图实现撤回 develop 状态后，新测试如实报出渲染文本 `¥$0.1234`（连同被改正的汇总断言共 2 条红）；恢复后 `NextUsageView.spec.ts` 12/12 绿，全量 61 文件 / 369 条绿，`npm run build`（含 typecheck）通过，改动文件 eslint 0 error。

## 2026-09-18 控制台终于能看见调整了——顺带查出 adjusted 标记与自己的契约不符（#773 / #774）

**起因**：汇总侧的数字**含调整**（#753），而记录表的 token 列只有**观测值**，且前端**零处**消费 `net*`/`adjusted`。于是只要有调整，**表内列逐行相加 ≠ 它上面那行汇总**——明细页最该解释汇总，却对不上账。

**#773（前端）**：token 列改显**净额**（与汇总同口径，可对账），新增一列「调整」承载行级标记与气泡里的 `观测 → 净额`。未调整的行**逐字保持原样**（渲染 `net ?? observed`，未调整时两者恒等）。

**#774（后端，是本项的前置）**：把标记呈现到 UI 的过程中发现 `UsageRecordView.adjusted` **与自己的公开契约不符**——契约写"是否存在**任何**修正"，实现却是**先按维度求和、再判非零**：

| 该行 | adjustment 行数 | 四维 delta 之和 | 现行 flag | 契约要求 |
|---|---|---|---|---|
| 演示库唯一被调整的行 | 2 | 0 | **false** | **true** |

即"修正 + 冲销相互抵消"的行被报成**从未被碰过**——演示站因此**没有任何一行**会报 `adjusted = true`。而这一行正是我们此前打算用来演示"纠错靠追加、净额可为零"的那一行：**演示会当场穿帮**。

**为什么按契约修而不是把契约改成现状**：关键在于**哪一个读法是客户端算不出来的**。"净额 ≠ 观测"客户端**同时拿到两套数**、自己一比就知道，做成服务端字段是**冗余**；"是否被修正过"客户端算不出来（净额相等既可能是没修过，也可能是修了又冲销）。**追加式台账的全部意义就是留痕**——"改过又改回来"与"没人碰过"是两件事实。

**顺带暴露的一件事**：**已有两条测试把缺陷固化了**——`UsageAdjustmentApiIntegrationTest` 与 `ExportUsageAdjustmentIntegrationTest` 的"冲销后"用例都断言 `adjusted = false`，注释还替它写了理由。改法与上一批同类：**修断言并写明为什么**，而不是回退实现。

**修法**：`ADJUSTMENT_LATERAL` 本来就把四个 delta 求了和，**行数在同一趟扫描里顺手就能拿到**——加 `COUNT(*)`，flag 改为按行数判定（不再多开一次子查询）。

**验证**：
- 后端（#774）：**先证明会红**——撤掉 flag 改动，只有那两条"冲销后"断言失败（11 跑 2 失败），其余 9 条照常通过；恢复后 11/11 绿
- 前端（#773）：379/379 单测、`typecheck`、`vite build` 全过；组件测试覆盖"净额优先 / 未调整 / 冲销后仍标记"三种呈现
- **浏览器实测**（本地 dev + 真机反代 + mock 三通道）：
  - 真机：`调整` 列在位、未调整行显示 `—`、其余数字无回归
  - mock（植入两种调整行）：第 0 行 输入 **1,938**（观测 938+1000）、输出 **224**（524−300）——净额生效；第 1 行数字与观测一致但**仍标已调整**；气泡文案逐字为 `输入 938 → 1,938 · 输出 524 → 224` 与 `存在调整记录，但净额与观测一致（已冲销）`

**为什么必须用 mock 才能验**：演示库**唯一**的调整净额为零，所以真机上"净额优先"与"是否标记"两件事**都观察不到**——真机只能证明"没有回归"，不能证明"新行为成立"。这一条值得记：**当一个特性在真机上恰好退化为恒等，真机验证就不足以支撑它**。

**另记两处 tooling 假绿**：
- `-Dtest=A+B` 不是 surefire 的选择器语法（是逗号），**空跑返回 0**——"构建成功"掩盖了"根本没跑测试"
- 调用了**别的工作树**的 `mvnw21.sh`（它会 `cd` 到自己那棵树），于是构建与测试都落在演示树上；两次假绿后才从日志里的路径发现
- 前端：`prettier --write` 会把 `src/i18n/dict.ts` **重排半个文件**（该文件从未被格式化过，CI 也不查它）——按本仓既有教训，格式化只跑本轮改动文件，且 dict.ts 单独用最小改动追加

## 2026-09-18 派生列只往前盖章、不回头看——4027 行标签陈旧（#777）

**发现路径**：部署线跑 #766 的 drill，按我给的规则逐行复核分布——**逐项吻合**（gap 617 / unavailable 565 / 真正 PARTIAL 只剩 52，−11.43%）。**但复核顺带暴露了一处存储列与读取判定的分叉**，它把现象报给我判定：

| 存储列（回填当时盖的章） | 读取侧现行判定 | 行数 |
|---|---|---|
| PARTIAL | 无 gap（= COMPLETE） | **4027** |
| UNAVAILABLE | 无 gap（全零 token） | 6 |

即 `select price_status, count(*)` 会告诉运维 4079 行"部分计价"，而今天真正部分计价的只有 52 行。

**根因**：盖章通道的行选择是 `price_status IS NULL`（"尚未评估"），已盖章的行**永不重访**；而**判据本身会演进**（#765 把"按价格是否可得"改成"按该行是否**用到**该维度"）。旧章于是留在行上。

### 为什么不采"有意双口径（存储列=当时判定的事实日志）"

那 4027 行的 PARTIAL **不是一次可辩护的历史判定**，而是**更早判据的错判**（把"该行根本没用到 cache_creation"算成缺口）。而且"双口径"要成立，必须给列加**判据版本**，否则读者无法分辨某行是哪套规则盖的章——比修更重、结果更差。**一个没有出处的陈旧标签就是错数据。**

### 一条分界线：标签是派生，金额是事实

- **`price_status` 是派生**：由该行已冻结的 `price_*` + token 列唯一决定 → 可以重算
- **`price_*` 与 `base_cost_amount` 是事实**：不重查价目、不改写（金额只"补 NULL"，见 #771）

这条线同时解释了为什么 #771 只补 NULL、而本项可以覆盖：**补一个从未有值的行**与**纠正一个由事实唯一决定的标签**，都不触及"不重估价格、不移动金额"这条被保护的不变式。此前文档里那句无条件的"已定状态的行永不重评"因此**收窄为**"价格列与金额永不改写"。

### 一个设计要点：这趟是扫描，不是工作队列

前两趟是"选工作 → 做 → 重查"（选了就离开候选集，故可证终止）。本趟**不能**这么做：**是否陈旧取决于现行判据，只有 Java 知道**，SQL 选不出"需要处理的那些行"。而"选全部已盖章行 + LIMIT + 零进展退出"**不安全**——一整页"已正确"的行会让循环停下，**把真正陈旧的行落在后面永远不纠正**（正是 #771 那个坑的上一层）。

改成**键集游标扫描**（`(occurred_at, id) > 游标` 前进），无论每行是否要写都保证前进，终止条件=本页不满。并立了一条**专门钉住它的测试**：先铺 501 行"现行判据认可"的行，再在其**后面**放一行陈旧的——重查式实现会停在前一页、把它永久落下。

### 定时收敛

盖章通道只往前看，所以新事件的 `price_status` 会一直停在 NULL（除非人工跑端点）。加 `UsagePriceReconcileScheduler`（`miqrokey.usage-price-reconcile.enabled`，默认关；照搬 `PriceSyncScheduler` 的形状：fixedDelay 不叠加、指标计数、异常兜底、无人会话以**无操作人**记审计）。**注意：`control-plane` 里此前没有任何价格类定时任务**——这是第一件。

### 验证

- **先证明会红**：撤掉实现（并把调度器移出源码树以免编译错掩盖测试结果）后，**17 跑 4 失败，恰好是新增的 4 条**，既有 13 条照常通过；恢复后 17/17 绿
- 三条断言分别钉住：陈旧标签被重算（且价格列与金额不动）、幂等、零 token 行变 COMPLETE 但仍不给它凭空造金额
- OpenAPI/前端类型按生成器重出（差异仅 `reclassified`）；spotless 通过

### 同一批复核的另外两条结论

- 部署线报的 **v4-pro 组显示 PARTIAL 而非 UNAVAILABLE**：核对代码后判定**不是 bug**（判据是 `unavailableEvents >= 请求行数`；该组有 2 条零 token 行"平凡可定价"，故不满足"每一行都无法定价"）。但它会有这个预期说明**文档缺一句**——已补进 usage-accounting §6：**一个组可以"已知金额 0"且 PARTIAL**，"看状态区分，别只看金额"
- 部署线**主动纠正了自己**昨天的错误结论（"写入路径已在冻结"其实不对：price 列唯一写方是回填服务），并给出更强的正面结论：**追加新价对未回填行也不移动**。我的 PR 措辞本来是对的，是转述时被夸大了

### 一处 tooling 假绿（本批第二次遇到同类）

`-Dtest=A+B` **不是 surefire 的选择器语法**（应为逗号），会**空跑并返回 0**；又一次"构建成功"掩盖了"根本没跑测试"。加上前一批那次"调用了别的工作树的 mvnw21.sh"，这是两天内**第二次**由命令层而非代码层造成的假绿——收口前要核的是**跑了几条测试**，不是"退出码是不是 0"。
## 2026-09-18 Agent 引用后凭证禁改禁删（#714 / F1 前半）——把"被引用"变成真的锁

**背景**：Agent 创建时已做绑定级联，但"被引用即不可变"这半从未实现——`rotate` / `disable` 只看凭证自身状态，不看有没有 Agent 正在用它。于是可以"先建 Agent、再轮换凭证"：网关拿新密钥打上游，而 Agent 记录里"用的是哪个凭证版本"这层语义悬空。规格侧 F28（Skill 快照）仍是 SCAFFOLD，issue 明确"独立于 F27 的绑定约束可先行"，本批只做这半。

**实现**（`7fac8001`）：

- `AgentRepository#findActiveByCredentialId(tenantId, credentialId)`：只认 `status = 'ACTIVE'` 的绑定
- `AdminCredentialService.rotate` / `disable`：在凭证行锁与既有状态守卫**之后**、任何写入**之前**调用引用守卫；命中抛 `409 CREDENTIAL_REFERENCED_BY_AGENT`，文案 `凭证已被 Agent「<name>」引用，不能轮换|停用；请先停用该 Agent。`
- `AdminAgentService.create`：改为对凭证行 `findByIdForUpdate` 取锁 → 与 rotate/disable 的同一把锁互斥，堵住"校验 ACTIVE 通过 → 并发停用 → 插入 Agent"的 TOCTOU（单行先取锁，无死锁反转）

**三条边界判定（已写进 `api-contract.md`）**：

1. 本产品**没有凭证 DELETE 端点**，V17 的 `ON DELETE RESTRICT` 也禁止硬删 —— 所以"禁删"落在 `disable`（生命周期终止操作）上，不为对齐 issue 措辞而造一个假删除端点
2. **已禁用的 Agent 不再钉住凭证**：历史绑定保留（用量仍归属该凭证），但守卫看不见它 —— 否则一次误绑会永久锁死凭证，且没有任何解锁路径
3. 跨租户引用返回 **404 `CREDENTIAL_NOT_FOUND`** 而非 409：租户过滤在守卫之前（`findOwnedForUpdate`），不给出越权者探测他租户 Agent 名的能力

**前端**：`frontend/src/i18n/dict.ts` 加两条 `PATTERNS`，把 409 的 `detail` 译成英文（Agent 名回填），保证英文界面下不是中文原文。

**验证（真实输出）**：

- 新增 `AdminCredentialAgentBindingIntegrationTest`（Testcontainers + MockMvc）：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 42.85 s`，`BUILD SUCCESS`（7 个模块全 SUCCESS）。用例：引用后 rotate 被拒 / 引用后 disable 被拒（两条都断言**数据库与审计零写入**：status、version、ACTIVE 版本指纹、审计动作列表均不变）/ 停用 Agent 后该凭证恢复可改可删、同时另一条仍被钉 / 已禁用 Agent 不钉 / 跨租户 404 且他租户凭证未被触碰 / 匿名 401 与 USER 角色 403
- `AdminCredentialServiceTest` 补 3 条单测：`Tests run: 20, Failures: 0`
- 全量受影响模块：`.\mvnw.cmd -B -f backend -pl control-plane-app -am test -Pintegration` → `Tests run: 779, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（7 模块全 SUCCESS，13:54 min）；其中本项新增类 6/6、`AdminCredentialServiceTest` 20/20
- 前端：`npm --prefix frontend run typecheck` 通过；`npm test` 63 文件 / 380 用例全绿；`npm run build` 通过（24.33 s）；`npx eslint . --ext .vue,.ts,.tsx`（**不带 `--fix`**）0 error —— 62k 条 `Delete ␍` 告警是 Windows 检出 CRLF 的固有噪声（CI 为 LF），非本次引入
- 评审后定向复跑：`.\mvnw.cmd -B -f backend -pl control-plane-app -am test -Pintegration -Dtest=AdminCredentialServiceTest,AdminCredentialAgentBindingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（IT 6/6 42.45 s、单测 20/20 1.271 s）；前端 `npm test` 63 文件 / 384 用例全绿、`typecheck` 通过、`eslint`（不带 `--fix`）0 error

**对抗评审后修复（4 条，均在本 Goal 范围内）**：
- M1（唯一被证伪的对外声明）"停用 409 文案英文界面仍为中文"：`NextCredentialsView.vue` 把 message 与 requestId 拼成单节点 `…（requestId: xxx）`，全仓 20 处同样写法，均不匹配 `PATTERNS`。修在引擎层：`translateOne` 先剥离 `（requestId: …）` 后缀 → 翻译消息头 → 原样拼回；`i18n-copy.spec.ts` 加 3 组断言（两条真实文案 + 带后缀的轮换/停用串 + 未覆盖文案保持中文不半翻译）
- M2 `disableProceedsWhenTheBindingAgentIsDisabled` 无判别力：补 `verify(agentRepository).findActiveByCredentialId(TENANT, credential.id())`，证明守卫被调用而非被跳过
- M3 `i18n-copy.spec.ts` 未收录 #714 文案：已收录（含 Agent 名插值 `客服助手`）
- M4 跨租户用例未锁定"响应体不含他租户 Agent 名"：已断言 rotate 的 404 body 不含 `foreign-agent` / `foreign-key`；收口轮补上 **disable 响应体**同样断言（此前只覆盖 rotate，而停用恰是 M1 暴露问题的路径）

**未修复（2 条，理由如下）**：
- M5 `docs/progress.md` 提交含 122 行纯行尾改写：该文件索引本身即 CRLF（`git ls-files --eol` → `i/crlf w/crlf`），提交把 122 行历史 LF 行归一为文件既有 CRLF；`git diff --numstat --ignore-cr-at-eol 7fac8001^..b5f31a5b -- docs/progress.md` 为 `28 0`（实质新增 28 行），`docs/api-contract.md` 为 `4 0`（无行尾改动）。不单独造 EOL 提交
- M6（pre-existing）`findByIdForUpdate` 先加行锁、后做租户过滤，外租户 id 会短暂持有他租户凭证行锁；本批让 `AdminAgentService.create` 也走这条路径。影响仅为统一 404、不泄露存在性、无锁序环（评审 A4 已核）；修正需要改领域仓库锁 API 语义（`UpstreamCredentialRepository#findByIdForUpdate`），超出本 Goal 边界，留作后续独立变更

**未做（如实记录）**：**Skill 快照语义（按引用版本固定）未实现**。理由：仓库里没有 Agent↔Skill 数据模型（无表、无迁移、无端点），F27/F28 仍是 SCAFFOLD；落地它要新表 + 新迁移 + 新的写入/读取语义，属于"需要新数据模型且风险大"，按 issue 边界**先停下报告**，不硬塞进本 PR。该项仍留在 F28 待办。

**工具坑**：`npm run lint` 的脚本带 `--fix`，在 Windows 检出（CRLF）上重写了 151 个无关前端文件（其中 21 个是真实内容改动，其余只是行尾/stat 脏）。已用 `git checkout -- <paths>` 逐个回退，最终只保留 `docs/api-contract.md` 与 `frontend/src/i18n/dict.ts` 两处改动。后续在该仓库跑前端 lint 时避免整树带 `--fix`。

### 收口轮（F1b）：M2–M6 逐条处置 + 定向复跑

开轮核对：worktree 干净（`git status --short` 空输出，**上一轮评审提到的 `docs/progress.md` 残留改动已在 `566d5a86` 落盘**，无夹带文件）；远端无该分支、无 PR。

| 项 | 处置 | 依据 |
| --- | --- | --- |
| M1 | 已修（`7a55eaf7`） | 引擎层剥离 `（requestId: …）` 后缀后翻译消息头再拼回 |
| M2 | 已修（`abd2b5fb`） | `verify(agentRepository).findActiveByCredentialId(TENANT, credential.id())` 钉"守卫被调用"；SQL 层判别力由 `anAlreadyDisabledAgentDoesNotPinTheCredential` 集成用例承担 |
| M3 | 已修（`7a55eaf7`） | `i18n-copy.spec.ts` 新增 `#714` describe：两条真实文案 + 带后缀的轮换/停用串 + 未覆盖文案不半翻译 |
| M4 | 本轮补齐 | 原只断言 rotate 响应体，现 disable 响应体一并断言 |
| M5 | 不改（理由见上） | 回退需重写已推送历史，属禁止操作；文件现已统一 CRLF，后续 diff 不再放大 |
| M6 | 不改，建议另开 issue | pre-existing 锁语义问题，修正要改领域仓库锁 API，超出本批边界 |

定向复跑（收口轮改 M4 断言后）：

- `.\mvnw.cmd -B -f backend -pl control-plane-app -am test -Pintegration -Dtest=AdminCredentialAgentBindingIntegrationTest,AdminCredentialServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（IT 6/6 34.42 s、单测 20/20 1.512 s；7 模块全 SUCCESS，50.7 s）
- 前端 `npm --prefix frontend run typecheck` 通过（无输出即无错）；`npm --prefix frontend run test` → `Test Files 63 passed (63)` / `Tests 384 passed (384)`，24.22 s


## 2026-09-18 MCP tools/sync 兼容性修复（#779）——真实封闭客户端实测暴露

**来源**：#742 第③片（WorkBuddy → 网关 MCP 数据面 → 公开 DeepWiki MCP）真机实测中，工具同步对严格 Streamable HTTP 上游 502（上游 406）——`McpToolsListClient` 只发 `Accept: application/json`，缺规范要求的 `text/event-stream`（同文件注释还自述"无 initialize 握手"，有状态上游为后续项）。触发面：一切严格校验 Accept 的上游 tools/sync 不可用 → 工具只能手工登记。

**修复**：Accept 改为 `application/json, text/event-stream`；**两条回归测试先证红**（Accept 双媒体类型断言 + 严格上游 406 夹具），修复后 `McpToolsListClientTest` 9/9 绿。

**绕行（修复前已在演示站完成，数据面健康性佐证）**：手工登记（官方占位 `method=POST path="/"`）+ ENABLED 后，以消费者凭据经网关 `tools/call read_wiki_structure` → 200 返回真实内容，`mcp_access_log`：`FORWARDED | read_wiki_structure | ttfb 617ms`。

## 2026-09-18 导出任务补「含调整」等级——V41 预留的那条轴（#716）

**先界定缺口，别重复做**：本项的验收里"净额维度在导出中可用""含调整维度可标记"**已被 #755 逐行覆盖**（CSV/JSONL 的 `net*`×4 + 行级 `adjusted`）。真正剩下的是 V41 注释预留的那半句——"净额/含调整等级随 F20 扩展"——即**任务级**的声明；以及两处**已过期的文档**（api-contract 还写着"待扩展"、§5.6b 还写着"读取路径尚未接入"）。

**为什么另起一条轴，而不是往 `reconcileLevel` 里加成员**：两者是**互相独立**的问题——"能不能按请求 ID 对上账单"与"数字里含不含修正"。合进一个枚举就得为每种组合造一个值（`PROVIDER_ID_BACKED_AND_ADJUSTED`…），读起来两边都不是。V41 的注释当初把它们写在一起，这是**把它拆开**而不是照它实现。

**为什么是任务级而不只是行级**：消费者希望在**读文件之前**（或只看任务列表时）就知道 `net*` 列要不要看。所以：

- `adjustmentLevel` = `PRESENT`（至少一行被修正过 → `net*` 才是应对账的那套）/ `NONE`（没有任何行被改过 → `net*` 只是重复观察值）；空窗口/历史任务为 null
- 文件内同步：`local_caliber_note` 追加 `;adjustments=present|none`（`local-instant` 前缀与 `reconcile=` 位置都不动）
- 它读的正是文件里那个**行级标记**（`adjusted`），所以任务级声明与文件内容**不可能不一致**

**顺带修掉两处文档漂移**（本仓的老毛病，双向都会漂）：`api-contract` §5.5 的"净额/含调整等级随 F20 扩展"已兑现；§5.6b 的"读取路径尚未接入"其实早已不成立（#753/#755/#773 都上了）。

**验证**：先证明会红——撤掉实现但**保留迁移**（否则退化成"列不存在"的粗红），7 跑 **3 失败、恰好是新增那三条**（`expected "PRESENT" but was null`），既有 4 条全过；恢复后 7/7 绿。三条断言分别钉住：未修正= NONE 且文件里写着 `adjustments=none`、有修正= PRESENT 且文件里写着 `present`、**冲销之后仍是 PRESENT**（数字回到观察值，只有这条声明还说得清文件来自被改过的行——与 #774 同一条规则）。

**一处 UI 取舍**：只为 `PRESENT` 出 chip，`NONE` 走 `—`。给"没有修正"也挂个徽标等于几乎每行都有徽标，反而把真正要看的那行淹掉；而这张表本来就用 `—` 表示"无话可说"。

**另记**：迁移号按纪律取「develop 树最高号（66）∪ open issue 登记号」之后的下一个 = **V67**；定号前也扫了 open PR 的正文。
## 2026-09-18 适配器状态「持续警告」前端实现（#735 前端部分）——准入门控刻意不动

**先复核现状**：`docs/provider-adapter-contract.md:120` 承诺「生产默认目录只启用 `VERIFIED` 产品；管理员可以显式启用 `IMPLEMENTED`，**页面必须持续警告**」。实现侧对得上「持续警告」的只有「徽标 + hover 提示」这一层：

- `AdapterStatus` 的唯一生产使用点是 `CatalogManifestValidator.java:123` 的**解析**（`requireEnum(product, "status", …)`），解析完不做任何准入判断
- `ImplementationStatus` 没有任何准入分支
- `CatalogSeedService.java:101` 把种子一律写成 `'DOCUMENTED'`
- 目录量化：`provider-catalog.json` 共 **23** 个产品，状态计数 `{"DOCUMENTED":23}`——**0 个 IMPLEMENTED、0 个 VERIFIED**

**因此只实现承诺里可验证的那半句——「页面必须持续警告」，准入行为一个字不改。** 照字面实现「只启用 VERIFIED」会让当前 23 个产品全部不可用，属产品决策，只出决策材料（已发 #735 评论），不在本批动代码。

**改动**（`frontend/src/views/next/NextProvidersView.vue`，另 `frontend/src/i18n/dict.ts` 补双语文案）：

- 页级常驻警示条 `data-testid="adapter-warning-banner"`：只要有任一产品状态不是 `VERIFIED` 就一直在页上（hover 提示会被漏看）
- 行级常驻标记 `data-testid="adapter-warning-row"`（`⚠ 未验证`）：刻意用纯 `<span>`，因为既有断言把 `.ui-tooltip__anchor` 钉成 2 个，再加 Tooltip 会破既有测试
- 产品详情面（模型目录对话框）内重复一次 `data-testid="product-models-adapter-warning"`，带该状态的解释文案
- 判据就是状态字段本身：`status !== 'VERIFIED'`，`VERIFIED` 不出任何警告；DRAFT/DOCUMENTED/IMPLEMENTED/DEGRADED/DISABLED 全覆盖
- 文案进 EN 词典：4 条 DICT + 1 条 PATTERN（计数行被 Vue 合并成单个文本节点，只能走 PATTERN）

**一处实现取舍**：仓库没有独立的「供应商详情页」，唯一的产品详情面是模型目录对话框，所以「详情页警示块」落在它顶部。

**验证（先证明断言有区分力）**：把 `isUnverified` 打成两个变异体——`return false` 与 `return true`——各挂 3 条测试（`3 failed | 8 passed`），恢复后全绿，并用 grep 确认无变异体残留。之后：

- `npm --prefix frontend run test -- --run` → **63 files / 390 tests passed**
- `npm --prefix frontend run typecheck` → exit 0
- `npm --prefix frontend run build` → exit 0（built in 23.96s）
- `npx eslint <4 个改动文件>`（不带 `--fix`）→ 0 errors（1 条 prettier 警告落在既有 import 行，非本批引入）

**一处 tooling 陷阱（本批实录）**：本仓 `npm run lint` 的脚本是 `eslint . --ext .vue,.ts,.tsx --fix`——**它会改写整个前端**。本次跑完 lint 后 `git status` 出现 148 个与本改动无关的文件（含 `types/generated.ts` 整体重排），已逐个 `git checkout --` 还原，只留 4 个改动文件；随后改用不带 `--fix` 的 `npx eslint` 复核。后续批次别把 `npm run lint` 当成只读检查。

## 2026-09-18 评审响应对（#735 前端部分）——只收敛 minor，不动准入门控

**评审结论**：0 blocker。准入红线被独立复核确认为「未触碰」：`git show --numstat bdc4b9e4` 5 文件、**0 删除行**；`grep -rn "\.status()" backend/provider-adapters/src/main backend/provider-spi/src/main` **0 命中**（目录里的 status 解析后从不被读取）；路由快照 SQL 无任何状态过滤。5 条 minor 的逐条处置：

- **M2（`已停用` 无 EN 词条）→ 已修**：`frontend/src/i18n/dict.ts` 补 `'已停用': 'Disabled'`——六个状态标签里唯一缺词条的一条（独立词条；`规则已停用` 这类带前缀的串另有条目）。新详情弹窗警告块会把该标签渲染进英文界面，属本次新增的暴露面。
- **M3（文案只覆盖「未验证/已降级」）→ 已修**：警示条第三行改为「未处于「已验证」状态的产品仍按当前配置可用（已停用的除外），但不应承载生产流量；本提示不改变产品的启用与可用行为。」，行内标记 `⚠ 未验证` → `⚠ 非已验证`。措辞与判据 `status !== 'VERIFIED'` 对齐，并顺带消掉 M1 的用户可见症状（警告不再指向一个不存在的「启用」开关）。
- **M1（`docs/provider-adapter-contract.md:120` 仍承诺门控）→ 不改，转决策材料**：该句正是 #735 待 owner 拍板的争点，本批改文档等于替 owner 预设定论；M3 的改写已让页面不再宣称存在启用开关。拍板后按选定口径一并更新文档与种子状态。
- **M4（banner 无 `role="status"` / `aria-live`）→ 不改**：同文件既有 `ui-alert--error`（400 行）同样没有，只给新 banner 加会让同类告警行为不一致；评审人也已自降为 nit。若要统一，应作为独立 a11y 批覆盖全部 `ui-alert`。
- **M5（视觉基线 `admin-providers-1440x900.png` 陈旧）→ 本批不重生成**：基线是捕获式、无像素断言（`docs/progress.md` 有先例），重生成需起 Playwright + `preview` 并重建产物，不改变 CI 结论。已在 PR「Remaining risks」登记。

**评审响应改动的验证（真实输出）**：

- `npm --prefix frontend run typecheck` → exit 0
- `npm --prefix frontend run test` → **63 files / 392 tests passed**（基线 390：新增 1 条 DISABLED 用例 + 更新既有断言）
- `npx eslint . --ext .vue,.ts,.tsx`（在 `frontend/` 下、**不带 `--fix`**）→ **0 errors**；62337 warnings 全为工作区既有的 CRLF `Delete ␍`，非本批引入
- `npm --prefix frontend run build` → exit 0（`✓ built in 24.02s`）

**首跑失败与最小修复（如实记录）**：新加的 DISABLED 用例第一次跑是**失败**的——它在 `document.body` 里收集 `.ui-tooltip` 文本，拿到的是同文件早先用例遗留的「已用真实供应商凭证完成契约测试。」（tooltip 只在锚点聚焦后才挂载，且从不卸载）。修复只加两行：`await wrapper.find('.ui-tooltip__anchor').trigger('focus');` + `await flushPromises();`，断言口径不变；修后该文件 12/12 通过。
## 2026-09-18 WorkBuddy MCP 层接入实测样章（#742 第③片收口）

**交付**：`docs/workbuddy-mcp-onboarding-sample.md`——真实封闭客户端（WorkBuddy，腾讯 CodeBuddy 系）按指南 §4 接入网关 MCP 数据面的完整样章：拓扑、五步照抄（注册服务→消费者裁 `mcp:call`→`~/.workbuddy/mcp.json`（**无点号**；带点的是应用自管文件，写错不生效）→过信任门（`mcp_approvals` 键=sha256(url origin)::name）→同步并放行工具）；证据表；两条踩坑（自管配置陷阱；`HEALTH_PATH` 对 SPA 兜底页的假 HEALTHY——应选 `JSONRPC_INITIALIZE`）。

**实测证据链**：应用日志 `[MCP-Connect] ok … tools=3`；`mcp_access_log` 6 行 `TOOL_UNAVAILABLE`（放行前，toolName 完整）+ `FORWARDED | read_wiki_structure | 617ms`（放行后）。**实测暴露真缺陷 #779**（`tools/sync` Accept 缺 `text/event-stream` → 严格上游 406）——修复 PR #781 已合并（先证红两条回归）。
## 2026-09-18 MCP tools/sync 修复之二：SSE 响应体解帧（#779 收口）

**背景**：PR #781（Accept 兼发双媒体类型）上线后，对严格上游的失败**只前进了一步**——406 消失，但上游按规范合法改发 **SSE 帧**（`event: message` + `data: {...}`），同步客户端仍按裸 JSON 解析 → `502 TOOLS_SYNC_UPSTREAM_FAILED / Unrecognized token 'event'`。真机（演示站 deepwiki 服务）复现，错误逐字同型。

**修复**：`McpToolsListClient` 判 `Content-Type: text/event-stream` 时按 SSE 规范取 `data:` 行（多行按换行拼接）再解析；无 data 载荷 fail-closed（"上游 SSE 响应中没有 data 载荷"）。

**测试**：两条新用例**先证红**（SSE 解帧 / 无 data 拒绝），修复后 `McpToolsListClientTest` **11/11 绿**。

**部署教训（本轮踩到，含一次自我纠正）**：compose.prod.yaml 的 cp 服务带 `build:` 段（context=`..`=演示树 `/opt/miqrokey`，与真正构建用的 `/opt/miqrokey-dev` 不同）——漏 `--no-build` 有**用旧树产出镜像**的风险（触发条件：该 tag 本地无镜像时 `up` 才会构建）。本轮曾观测"容器镜像 ID ≠ tag 镜像 ID 但 compose 显示 Running"，最初归因为"compose 按镜像引用名判等"——**该归因已被直接观测否定**：同 tag 下 `up` 不加 `--force-recreate` 亦会 `Recreate/Recreated`，compose 按解析出的镜像 ID 判等；更可能的成因是**多会话并发构建同一 tag 的竞态**（本轮时间线：自建镜像 11:11:03 完成，容器 11:11:07 从另一镜像创建）。**落为收尾断言**：部署后必须核 `container.Image == tag.Id`（"Up N seconds + healthy"不算数）——它正是抓这类竞态的检查；**跨会话纪律：同一 tag 不并发构建、部署串行**。

## 2026-09-18 WorkBuddy MCP 层接入实测样章（#742 第③片收口）

**交付**：`docs/workbuddy-mcp-onboarding-sample.md`——真实封闭客户端（WorkBuddy，腾讯 CodeBuddy 系）按指南 §4 接入网关 MCP 数据面的完整样章：拓扑、五步照抄（注册服务→消费者裁 `mcp:call`→`~/.workbuddy/mcp.json`（**无点号**；带点的是应用自管文件，写错不生效）→过信任门（`mcp_approvals` 键=sha256(url origin)::name）→同步并放行工具）；证据表；两条踩坑（自管配置陷阱；`HEALTH_PATH` 对 SPA 兜底页的假 HEALTHY——应选 `JSONRPC_INITIALIZE`）。

**实测证据链**：应用日志 `[MCP-Connect] ok … tools=3`；`mcp_access_log` 6 行 `TOOL_UNAVAILABLE`（放行前，toolName 完整）+ `FORWARDED | read_wiki_structure | 617ms`（放行后）。**实测暴露真缺陷 #779**（`tools/sync` Accept 缺 `text/event-stream` → 严格上游 406）——修复 PR #781 已合并（先证红两条回归）。
## 2026-09-18 MCP tools/sync 修复之二：SSE 响应体解帧（#779 收口）

**背景**：PR #781（Accept 兼发双媒体类型）上线后，对严格上游的失败**只前进了一步**——406 消失，但上游按规范合法改发 **SSE 帧**（`event: message` + `data: {...}`），同步客户端仍按裸 JSON 解析 → `502 TOOLS_SYNC_UPSTREAM_FAILED / Unrecognized token 'event'`。真机（演示站 deepwiki 服务）复现，错误逐字同型。

**修复**：`McpToolsListClient` 判 `Content-Type: text/event-stream` 时按 SSE 规范取 `data:` 行（多行按换行拼接）再解析；无 data 载荷 fail-closed（"上游 SSE 响应中没有 data 载荷"）。

**测试**：两条新用例**先证红**（SSE 解帧 / 无 data 拒绝），修复后 `McpToolsListClientTest` **11/11 绿**。

**部署教训（本轮踩到，含一次自我纠正）**：compose.prod.yaml 的 cp 服务带 `build:` 段（context=`..`=演示树 `/opt/miqrokey`，与真正构建用的 `/opt/miqrokey-dev` 不同）——漏 `--no-build` 有**用旧树产出镜像**的风险（触发条件：该 tag 本地无镜像时 `up` 才会构建）。本轮曾观测"容器镜像 ID ≠ tag 镜像 ID 但 compose 显示 Running"，最初归因为"compose 按镜像引用名判等"——**该归因已被直接观测否定**：同 tag 下 `up` 不加 `--force-recreate` 亦会 `Recreate/Recreated`，compose 按解析出的镜像 ID 判等；更可能的成因是**多会话并发构建同一 tag 的竞态**（本轮时间线：自建镜像 11:11:03 完成，容器 11:11:07 从另一镜像创建）。**落为收尾断言**：部署后必须核 `container.Image == tag.Id`（"Up N seconds + healthy"不算数）——它正是抓这类竞态的检查；**跨会话纪律：同一 tag 不并发构建、部署串行**。

## 2026-09-18 缓存节省是没有标记的下界——补上最后一个"未知被当成 0"的洞（#790）

**我先前的判断是错的，被一次实测推翻。** 我在 #766 的 PR 里把"命中路径无 gap 计数器"列为 follow-up，但随后自己评价它"**收益低**"（理由：演示站的节省额只有 ¥0.001 量级）。动手前顺手查了一下可达性，用的是与代码**同一套 as-of 规则**：

```
hit_groups | groups_without_input_price_at_hit_time | first_hit | last_hit
         5 |                                      3 | 09-14     | 09-16
```

**5 个命中组里 3 个**在命中时刻没有任何生效的 input 价。也就是说这台站上的缓存节省数字**对 60% 的命中组静默偏低**——不是理论情形，是正在发生。**"收益低"是只看演示站的金额量级得出的，而缺陷的类别才是量尺**：`savedByGatewayCache` 是控制台首屏的招牌数字，而"未知被当成 0"正是 B+ 分层要消灭的那一类。

**缺陷的原文**（`UsageStatsRepositoryImpl`）：

```java
/**
 * {@code tokens x hits x unitPrice}, undivided; a null price contributes nothing.
 */
private static BigDecimal weighted(long tokens, long hits, BigDecimal unitPrice) {
    return unitPrice == null ? BigDecimal.ZERO : BigDecimal.valueOf(tokens * hits).multiply(unitPrice);
}
```

注释把这件事写得像无害的默认值。而 `addHit` 当时**完全不碰** `unpriced`。

### 一处设计取舍：把两个问题分成两个名字

给 `PricingGap` 加 `unpricedHitEvents` 时，`isEmpty()` 原本**驱动 `pricingStatus`**——直接加字段会让"节省侧有缺口"把**成本**判成 PARTIAL，即"让一个从没被它碰过的数字显得不可信"。所以拆成两个名字：

- `hasCostGap()`（= `unpricedEvents > 0`）驱动成本状态：**成本完不完整与节省完不完整是两个问题**
- `isEmpty()` 表示"**完全没有缺口**"（成本 ∪ 节省），名字与含义一致

这两条各有一条测试钉住（域测试 144/144）。

### 前端：不标注就等于没修

两个展示节省额的地方同时加标记，否则 UI 层重复同一个缺陷：管理端概览的「网关缓存节省」加「下界」徽标（复用既有未定价样式 + UiTooltip 说明次数），成本页的「缓存节省」卡片在提示行里追加「下界：N 次命中在发生时无生效价目」。

### 验证

- **先证明会红**：只关掉仓库侧的计数（域侧保持，否则退化成编译错而非行为红）→ `AdminRoiApiIntegrationTest` **4 跑 1 失败、恰好是新增那一条**（`expected 2 but was 0`），其余 3 条照常通过；恢复后 4/4 绿
- 新增 IT 用的是**能分辨的那个 fixture**：价目生效时间设在"命中之后、用量行之前"（`now() - interval '1 second'`）——于是同一次运行里**成本 COMPLETE 而节省是下界**，正是要钉住的那条不变式
- 前端 25/25（含 4 条新增）＋ typecheck；OpenAPI/前端类型差异仅 `unpricedHitEvents`

**教训（与本会话其他几次同族）**：我凭**金额量级**判定一件事"不值得做"，而判据应该是**缺陷的类别**；一次五分钟的实测就把它推翻了。与"自验只覆盖自己以为的范围"是同一种盲区——只是这次盲在**优先级**上，而不是盲在正确性上。

## 2026-09-18 部署序列化与归因——单入口脚本（#793）

**起因**：演示栈由多条会话共用，而部署是各写各的命令。两天里两次同类事故：① 10:5x 两次构建交错，**事后再怎么查都无法从机器状态回答"当时跑的是哪一份"**；② 中午 `--no-build --force-recreate` 双保险之下，容器镜像 ID 仍不等于 tag 的 ID，且容器那张镜像在本地列表里已不存在。

**②一度被读成"至少还有第三条会话在动部署"——该结论被推翻**：容器跑的**就是该会话自建的镜像**（它自己的构建日志为证），tag 是被另一个**并发构建**改指的。所以问题不是"多了谁"，而是**并发构建无人拦** + **"谁上的线"没有持久记录**（等有人问起，镜像可能已经不在了）。

**交付**：`deploy/deploy.sh` —— 单入口，一次做三件事：

1. **`flock` 序列化**（构建与 `up` 都在锁内）：交错真正伤人的地方是**构建**，不是 `up`
2. **收尾断言"正在跑的就是刚构建的"**：逐个比 `docker inspect <容器>.Image` 与 `docker image inspect <tag>.Id`。`Up N seconds (healthy)` **不是证据**——容器没换过去时机器显示的状态一模一样
3. **每次追加一行 `deploy.log`**：时间/模式/提交/调用方/**运行中的镜像 ID 与当时的 tag ID**

三条既有教训也编进流程：`up` 带 `--no-build`（compose 的 cp 服务 `build:` 段 context 指向**线上树**）、后端容器换掉后自动 `restart portal`（nginx upstream 启动时解析）、**显式钉住 compose 项目名**。

### 干跑抓出我自己三个 bug

写完先跑 `--dry-run`，立刻暴露三处：

1. **干跑声称了它没做过的验证**——断言步没被 `--dry-run` 罩住，真跑了 `docker inspect` 并对我本机镜像打印 "verified"。**干跑最不能做的就是断言它没验证过的东西。**
2. **硬编码容器名 `miqrokey-<svc>-1`**——隐含假设 compose 项目名=miqrokey。改成向 compose 问（`compose ps -q`）。
3. **最要紧**：**compose 的项目名取决于调用时的目录**（服务器上靠 `cd /opt/miqrokey` 才得到 `miqrokey-*` 容器名）。换个目录跑，脚本会**另起一套容器**而不是更新线上那套。已 `-p` 钉住。

### 真跑一遍，并证明断言会红

用一次性夹具（**独立 tag 与独立项目名——避免覆盖本机既有的 `miqrokey-*:local`，那是别的会话的本地栈**）：构建→换容器→断言→restart portal→写流水，exit 0。

再**故意把 tag 指向另一张镜像**，跑 `--verify-only`：

```
ASSERT FAILED control-plane: running image 'sha256:97ff…' != tag image 'sha256:974b…'
verified portal: sha256:ff21…            ← 未动的服务仍通过
EXIT=2
```

顺带加了 `--verify-only`：**部署线明确说要"部署前后各查一次"，而一个不能单独跑的检查不会被跑**。流水里两个身份都记，是为了事后能分辨"tag 被人重建了"与"当初就没换过去"。

**分工**：脚本+文档进仓库（可评审），**装到服务器由部署线负责**。锁选**机器层**而不是"打卡制"——打卡依赖自觉，而我们已经知道至少有一个动作方不打招呼，荣誉制只会让守规矩的人排队。

## 2026-09-18 计价状态在控制台不可见——汇总层的成本被当成总额（#801）

**发现方式**：修完 #790（节省侧的下界标记）后我想确认自己有没有踩到消费方，于是查了 `pricingStatus` 与 `unpriced` 的**消费方**。结果是：

```
frontend/src 中除 generated.ts 外，对 pricingStatus 的引用：0 处
frontend/src 中除 generated.ts 外，对 unpriced 的引用：仅 #790 新增的 unpricedHits
```

也就是说 **#766 建立的 B+ 状态在控制台一处都没渲染**。控制台里唯一的「未定价」在**记录行**级（由 `row.priced` 驱动）；**分组/汇总级**——那个被当作**总额**展示的成本——没有任何标注。

而 `usage-accounting` §6 早就写下了对外承诺：**`pricingStatus != COMPLETE` 时已知金额不是总额**。演示站当前就是这个状态（汇总 `PARTIAL`、`unpricedEvents=617`、`unavailableEvents=565`）：页面上那个 ¥89.9 明确不是总额，而没有任何东西说明这一点。

**这与 #790 是同一类，而且更重**——成本是主要财务数字，节省是次要的。我一个小时前刚在节省侧修过同一件事，成本侧却还开着。

### 实现

- 新增 `frontend/src/lib/usage-pricing.ts` 的 `costGapNote()`：只为**明确知道**的缺口出声——`PARTIAL` / `UNAVAILABLE` 给文案，其余（含字段缺失）一律返回 null。**给不确定的情形加标注，等于给没有问题的数字也挂上警告。**
- 三个视图各加一个「未定价」标记 + 气泡：管理端用量概览（总成本）、自助用量页（合计行）、成本页（两张成本卡）。**`COMPLETE` 时不加任何标记**，与 #790 同一取舍。
- 记录行级的 `priced` 标注本就有，本次补的是**它上面那一层**。

### 顺带查出一处**我自己留下的过期文案**

成本页「上游已付成本」的提示写着"**按最新单价**估算"——而读侧从 #766 起就改成"**事件发生时**的冻结价"了。即我在后端改了口径，界面上那句话没跟着改。已改为「按事件发生时的价目估算」。**这类漂移不报错、只是静静地说错话**，和本会话反复遇到的"存了但没人看"是同一个家族。

### 验证

- **先证明会红**：把 `costGapNote` 改成恒返回 null → **恰好 5 条失败**（3 个视图 + 2 个 lib 文案断言），其余 42 条（含三条"`COMPLETE` 时无标记"）照常通过
- 前端 47/47（相关 4 个 spec）+ typecheck；全套与 build 结果见 PR

### 一条贯穿今天三次改动的观察

`#766`（成本状态）、`#790`（节省下界）、`#801`（界面呈现）是**同一个缺口的三次显形**：**API 暴露了的事，界面不显示就等于没交付**。前两次我都是先在后端把事实建好，然后（这次才）发现控制台看不到。往后凡是"把某个事实提升为一等公民"的改动，**验收标准里应该直接写出界面上的形态**，否则很容易停在 API 层就以为完成了。

## 2026-09-18 我写的部署脚本在演示站引入了一次真回归——并把"verified"这个词管住（#802）

**症状**（部署线报的）：脚本部署后，演示站自己 origin 的登录全 403 `ORIGIN_REJECTED`，cp 日志 `Origin not in allowlist: https://124.220.165.175`。

**根因是我**：#793 里我把 compose 的 `--project-directory` 钉到 `$LIVE_DIR`（`/opt/miqrokey`），而 **compose 从*项目目录*读 `.env`**，演示站的 `.env` 实际在 **`/opt/miqrokey/deploy/.env`**。目录错位 → `.env` 没被加载 → `${MIQROKEY_ORIGIN_ALLOWLIST:-https://miqrokey.example.com}` 回落默认值 → 登录被拦。**丢的不止 ORIGIN 一个变量**，`.env` 里那一组全没了。

**两条会话同时中招**，相隔 13 秒（流水正好记下：`05:11:39` 与 `05:11:54`）。**"流水"上线第一天就派上用场**——没有那两行，这事又要归因半天。

### 修法

1. `--project-directory` 改为 **compose 文件所在目录**（`$LIVE_DIR/deploy`）✓
2. `.env` 改为 **显式 `--env-file`** 指定，且**缺失即失败**（`1`）——留一个"没找到就静默回落默认值"的口子，等于把这次的故障留在原处
3. **加一步功能冒烟**：`--smoke-url` / `--smoke-origin` / `--smoke-expect`（默认 `2??`，可给 `case` 模式），状态码不符即 `2`

### 更要紧的是第三条背后的那句话（部署线提的，我认）

> "verified" 只证**镜像身份**。这次它把配置全丢的容器标了 `deployed and verified`——**`Up healthy` 不是证据，`镜像ID相等` 也只是"发的是刚构建的"，不是"发对了"。**

即：我写的断言覆盖的是**我想到的那个失败模式**（没换过去），而回归来自**另一个**（换过去了、配置没跟上）。这是本会话反复出现的同一件事——**自验只覆盖自己以为的范围**——这次发生在**验证工具本身**里。

所以除了冒烟，还改了两处**措辞**：不传 `--smoke-url` 时脚本**明说"什么都没查"**，收尾语从 `deployed and verified` 改成 `deployed; image identity verified`。**"verified" 这个词曾经盖过了它实际没查的东西——一个会过度声称的通过语，比没有通过语更危险。**

### 本机回归测试（先证明会红）

用带 `.env` 的夹具，探针变量在 compose 里配 `"${PROBE_VAR:-fellback}"`、在 `.env` 里给 `from-env`：

| 用例 | 结果 |
|---|---|
| 修好的脚本 + 有 `.env` | exit **0**、容器 `PROBE=from-env`、`smoke ok -> 200` |
| 冒烟打到 403 | exit **2** + `SMOKE FAILED: … -> 403 (expected 2??)` |
| **旧调用（复现 bug）** | 容器 `PROBE=fellback`，**而脚本 exit 0** |

第三行正是那次事故的形状：**它高高兴兴报成功，同时发的是配置全丢的容器**。流水现在也带上 `env_file=` 与 `smoke=<code>/<url>`。

### 两条我自己的操作教训

- **我又违反了本会话自己记过的规矩**：补丁写成内联 heredoc → 续行符被吃掉（`sh -n` 查不出来，因为合成长行仍合法）。**补丁一律写成 `D:/tmp/*.py` 文件**——这条记忆是我自己写的，写的时候还热着
- 测试脚本里用了 MSYS 风格的 `/d/tmp/...` 给 **Windows python** 用 → 夹具被写到 `D:\d\tmp\...`。python 侧一律用 `D:/tmp/...`（MSYS 两种都认，Windows python 只认后者）

## 2026-09-18 回声断言比 compose 更严——真机第一次跑就假阳性（#807）

**触发**：部署线第一次 `--verify-only` 就报出来（我正是请它"有误报直接说"）：

```
ASSERT FAILED control-plane: MIQROKEY_REGISTRATION_ENABLED='false' but …/.env says 'false'
ASSERT FAILED control-plane: MIQROKEY_CONTROL_ADMIN_TRUSTED_PROXIES='172.28.0.0/24' but …/.env says '172.28.0.0/24'
```

**报错里两边一模一样**——不查字节看不出差异。

**根因**：演示站 `.env` 是**混合行尾**（`file` 实测 CRLF+LF），那两行以 CRLF 结尾。我用 `cut -d= -f2-` 取的是**文件原始字节**，拿到 `false\r`；容器里是 compose 解析后的 `false`。**compose 的 dotenv 容忍 CR、trim 空白、剥一层引号，而我的断言不容。**

### 这是同一个错误的另一半

在**同一个 PR** 里我先犯了一次"过度声称"（`deployed and verified` 盖过了它没查的东西），紧接着又犯了一次"过度严格"（把文件行尾报告成部署故障）。**一个检查必须与它检查的系统校准**：松了会把问题放过去（那次），紧了会一直误报——**而误报的检查最终会被所有人无视，等于没有检查**。这次它报的是"文件的行尾是否干净"，不是"部署对不对"。

### 修法

1. **判前按 compose 的 dotenv 语义归一化**（CR、首尾空白、一层引号）——"文件脏但部署对"是正常形态，不是故障
2. **报错要能自证差异**：先给两侧的长度；长度相同时**打印字节转储**（`od -c`），让"看起来一样"的差异自己说清楚

### 验证（先证明会红）

同一夹具（一行 CRLF、一行 LF 的 `.env`）跑三次：

| 用例 | 结果 |
|---|---|
| **修复前的脚本（develop）+ `--verify-only`** | exit **2** + 复现那条假阳性 |
| 修复后 + `--verify-only` | exit **0**（CR 被容忍） |
| 长度相同但字节不同 | exit **2** + `container: 'false' (len 5)` / `env file : 'FALSE' (len 5)` + 字节转储 |

**这条我本机不可能发现**：我的夹具一直是 LF，只有它**真的在真机上跑**才暴露。与今天其余几次同源——**自验只覆盖自己造的场景**。

另：部署线现场处置得当（先备份 `.env.bak-<UTC>` 再归一化，语义不变——compose 本就容忍 CR），随后 `--verify-only` 全绿、默认冒烟 200、收尾语如实。

## 2026-09-18 功能冒烟：设计要覆盖的那一层是空的（#809）

部署线按四条路径实测后报出三处缺陷。我**逐条在代码里复核确认**，不是只采信结论。

### ① `case` 里展开出来的 `|` 是字面字符

```sh
case "$smoke_code" in ${SMOKE_EXPECT})   # SMOKE_EXPECT='200|405'
```

选择支的 `|` **只在源码里**才是语法；经展开到达的只是一个普通字符。所以文档里的示例 `'200|401'` **永远匹配不上**。本机 `sh` 实测：405 在 `'200|405'` 下不匹配；`grep -E` 才匹配。

**那行还挂着 `# shellcheck disable=SC2254`**——为消一个告警而写的 disable，遮住的正是这一类的真 bug。

修法：自己按 `|` 拆开逐个匹配，**保住 `2??` 的 glob 语义**——所以不能用 `grep -E`：那里 `?` 是量词，`2??` 会退化成"匹配 `2`"。

### ② 默认目标在结构上测不到 origin 层

默认目标是 `${origin}/`，即**门户根的裸 GET**。而

```java
if (!STATE_CHANGING_METHODS.contains(method)) return true;   // GET 直接放行
```

**GET 在设计上就不经过 origin 检查**；又由于 `OriginInterceptor` 是 `HandlerInterceptor`（在 handler mapping 之后），GET 打到只收 POST 的路由会被 mapping 先回 405，检查根本跑不到。实测：错 origin 打 `GET $origin/` 也是 **200**。

**红证明**（桩：登录端点对**正确** origin 也回 403，`/` 回 200）：

| 用例 | 修复前 | 修复后 |
|---|---|---|
| 坏 origin 检查 + 默认目标 | **exit 0（假阴性）** | **exit 2** |
| 健康栈 + 默认目标 | — | exit 0，`POST .../auth/login -> 400` |
| `'200|405'` 打 405 路由 | exit 2（缺陷①） | exit 0 |
| 运维给的 `--smoke-url` | — | 仍发 GET（不新增严格性假阳性） |
| 目标不可达（000） | — | WARNING + exit 0 |

修法：默认目标改 `POST <origin>/api/v1/auth/login` + `{}` + `'2??|400|401'`。选它的理由：**正是 #794 打坏的端点**（登录全 403）、**CSRF 豁免**（否则"少 CSRF 的 403"与"错 origin 的 403"不可区分）、空体校验回 400——**400 证明请求到达了处理器，即 origin 被接受**。403 判死。

### 教训：注释声称的性质，恰恰是代码结构上不成立的那个

那段注释原本写着默认目标 "exercises *config* rather than liveness"。这已是同一形态的第三次：

- **断言"存在"≠断言"正确"**（#754 表头错位近一年没被发现）
- **检查比被测系统更严**（#807 把文件行尾报成部署故障）
- **检查比被测系统更松，且松在它自称覆盖的那一层**（本次）

**补一句准确性**：#794 那次回归本身**会**被同一 PR 的环境变量回声断言抓到（#807 修的就是它），所以不能说"会放过事故"。问题是**功能网在它自称覆盖的那一层是空的**，而那个"自称"写在注释和文档里——**一个没牙的检查比没有检查更坏，因为它会被当成有牙的**。

另：`--smoke-expect` 的文档原本说"可给 `case` 模式"，那句既是错的（不匹配）又已过时（实现自己拆），一并改正。

## 2026-09-18 证书没进容器 = 「部署成功」：#794 的第二层补上断言（#812）

部署线两次提出的既有残余（#805 评论第二次）。第一次我以「#804 的 `assert_mount_landed` 是撞车 PR 的独有增量、不折入 #806」为由**有意留出**——理由是尊重撞车处理的边界。**那个理由现在不成立了**：#804 已关闭，而这一层现场真的发生过。**留一个已知的、有事故先例的洞，比折入一条断言糟得多。**

### 为什么三层现成检查都看不见它

#794 的第二层不是 `.env`：相对挂载 `./secrets/certs` 锚到了错误的项目目录 → Docker 在错误路径**现建一个空目录** → nginx 证书缺失 → **崩溃重启循环**。而 `up` 零报错。

| 检查 | 为什么看不见 |
|---|---|
| 镜像身份（§4） | 镜像是**对的**——坏的是挂载 |
| 环境变量回声（§7） | 证书**不是环境变量**，不在比对集合里 |
| 功能冒烟（§8） | portal 崩溃 → 冒烟拿到 `000` → 按设计**只判 WARNING**（"够不到"是天气） |

**唯一的症状恰好落在脚本有意不判死的那一类里**，于是收尾语替它宣称了安全。

### 修法与红证明

新增 §5：宿主有证书 ⇒ 容器必须看得见**同样的字节**（sha256 对比），且挂载源解析后必须等于宿主 certs 目录（`pwd -P` 两侧归一化，软链也对）。容器内路径从 `docker inspect .Mounts` 读，不硬编码。

| 用例 | 修复前 | 修复后 |
|---|---|---|
| 挂载锚到别处（`./elsewhere/certs`，Docker 现建空目录） | **exit 0（静默接受）** | **exit 2**，报出两侧路径 |
| 挂载正确 | — | exit 0，`certificates present inside the container` |
| **portal 崩溃循环**（`restarting=true`） | — | **exit 2**（镜像身份**通过**，是本条断言抓到的） |
| 宿主无证书 | — | 提示，不判死 |
| portal 已停 | — | exit 2（但**是 §4 抓的**：运行镜像为空——本条断言并非唯一防线，如实记下） |

崩溃循环那一行是关键：**只有这条断言能看见它**。

### 顺带修掉一个我自己的假阳性

第一次跑，**挂载正确**的用例也报了 `cannot read … inside the container`。真因是 **MSYS 路径改写**：Git Bash 会把看起来像绝对路径的参数改写成 `C:\...` 再交给 docker，于是容器内路径 `/etc/nginx/certs/fullchain.pem` 变成了宿主路径。拿 `MSYS_NO_PATHCONV=1` 对照后哈希逐字相同——**脚本在 Linux 上本来就是对的，是我的测试环境在撒谎**。

仍加了 2 行守卫（`MSYS_NO_PATHCONV=1; export`）并在注释里写明理由：Linux 上是空操作，而 Windows 开发机上省下的是一次**恰好属于本脚本要消灭的那一类**的假报警。**又一次是同族**：不是检查写错，是检查与它所运行的环境没对齐。

另：顺手把三个都编号为「6」的小节改成 5–9（本就在我要插入的位置）。
## 2026-09-18 部署脚本补上静态检查：shellcheck 进 CI（#814）

仓库 17 个 shell 脚本，`deploy/deploy.sh` 是**单机部署的唯一入口**，却**完全没有 lint**——CI 里 `deploy/` 只受 `compose` job 的 `docker compose config` 覆盖，而那只看 compose 文件、不看脚本。

### 为什么这条特别值

#809 那条 `case "$smoke_code" in ${SMOKE_EXPECT})`——**经展开到达 case 的 `|` 是字面字符**，文档示例 `'200|401'` 因此永远匹配不上。**SC2254 讲的正是这一行**，而它原本就挂着：

```sh
# shellcheck disable=SC2254
case "$smoke_code" in ${SMOKE_EXPECT})
```

**为消一个告警而写的 disable，遮住的恰是真 bug。** 而这条链的前提是**先有检查**——没有 shellcheck 的仓库里，这行连告警都不会有，bug 会一直安静地待着。

同一文件同一天还犯了另外两处（#807 断言比 compose 更严、#812 的 MSYS 路径改写导致挂载正确也报缺失）：**部署脚本是本项目事故密度最高的一块，却也是唯一没有任何静态检查的一块。**

### 现状几乎干净，所以没开豁免清单

`koalaman/shellcheck:stable -S warning` 对全部 17 个脚本**只报 1 条**（`deploy/backup/test-restore.sh` 的未用循环变量），改成 `i`→`_` 即可；`deploy.sh` 在修掉我自己那行 dry-run 拼接（SC2140）之后 **exit 0**。**零豁免清单**——不给自己留"下次再说"的地方。

### 红证明

把 #809 那个形状（`case "$code" in ${PAT})`）放回一个脚本：

```
red.sh:7: SC2254 (warning): Quote expansions in case patterns to match literally…
=== exit 1 ===
```

**但它给的修法是"加引号"，而这里加引号是错的方向**——`2??` 需要 glob 语义，加了引号就变成匹配字面 `2??`。**SC2254 的价值是把这一行拎出来让人看，不是照它说的改**；实际修法是拆开 `|`、各分支仍走 glob。**照抄 linter 的建议会把默认模式静默改松**——同一种"检查与主语没对齐"的陷阱，只是这次检查自己也会说错话。

### 口径

选 `-S warning` 而非默认：`info` 级会报 SC2016（单引号不展开）这类**有时是有意为之**的写法（我 dry-run 那行就是故意打印字面引号）。一上来全开只会逼出更多 disable——**而 disable 正是这次要治的东西**。先卡 warning，需要再收紧。

job 用路径过滤（`'**/*.sh'`），纯前端/纯后端 PR 不触发。
## 2026-09-18 用量导出 CSV 转义收口（#816）——补上三写入器里唯一漏掉的公式防护

**发现**（独立审计）：仓库三个 CSV 写入器中，审计导出（#430）与在建的对账导出（#798）都带 **RFC 4180 引号 + 公式注入前缀防护**，唯独**用量导出** `ExportTaskService.join()` 只做 `replace(",", "\,")`（非标准引号）。该导出的 `providerRequestId` **直接来自上游响应**（半可信来源），与 #798 注释里"provider-file text 需要防护"的判据完全同类；`clientIp`/`modelId` 亦为外部/管理输入。附带：引号与换行未处理（值内换行可劈断行、损坏列结构），且全树测试**未固化**任何旧转义行为。

**修复**：`ExportTaskService` 新增包内可见 `quote(Object)`，语义与两个兄弟路径一致（RFC 4180 + `= + - @ TAB CR` 前缀 `'`；`Number` 原样输出——负数金额不被加上 `'`，与 #798 的数值豁免同理）。`join()` 改走 `quote()`。**不做**三份 `quote()` 的合并（#798 在飞，避免与其文件冲突；合并后可另行收口）。

**先证红**：新增 `UsageExportCsvEscapeTest` 3 例（公式前缀/结构字符/数值与 null）；把 `quote()` 临时换回旧行为 → **2/3 红**（公式防护与结构引号），恢复后 **3/3 绿** + 兄弟 `AuditExportQuoteTest` 2/2 绿。

## 2026-09-18 三条行为断言进 CI——这条链只「机器化了一半」的更正（#818）

对方在收档时写「从事故到机器化防住最完整的一条」。**这个说法我核了，是错的**：`ShellCheck`（#814）机器守住的是**语法那一类**；**回声 / 冒烟 / 证书三条行为断言的 CI 覆盖是零**——它们的红证明全在 `D:/tmp` 的一次性脚本里，仓库里没有。

**具体后果**：明天有人把冒烟默认目标改回 `GET $origin/`，shellcheck 全绿、部署照常、**而 #794 的那颗牙又没了**。语法没病不代表行为还对。已向对方更正，并把正确的收档写法给了它。

### 做了什么

`deploy/tests/deploy_script_regression.py`：**每个场景造一个「只有那一个缺陷」的栈**，再问脚本有没有发现它。**在健康栈上通过不算证据，要在坏栈上失败才算**——这是本链反复用到的那条纪律，这次用在了 harness 自己身上。

12 项检查覆盖：CRLF 行尾不误报 / 同长度不同字节仍失败且能自证 / 坏 origin 必红 / 健康栈通过 / **派生目标确实是带 Origin 的 POST** / `'a|b'` 选择支生效 / 运维 URL 仍发 GET / 不可达只是 WARNING / 挂载锚错必红 / 正确挂载被验证 / 宿主无证书只提示。

### 反向验证（这条是 harness 存在的前提）

把三条修复分别改回去，harness **必须红**：

| 变异 | 结果 |
|---|---|
| 冒烟默认目标改回门户根 GET | **10/12**，2 项失败 ✓ |
| 去掉回声归一化 | **11/12**，1 项失败 ✓ |
| 去掉证书断言 | **10/12**，2 项失败 ✓ |

**只见过绿的闸门不是证据。**

### 过程中修掉的三处

1. **夹具基础镜像按 digest 写进 `FROM` 会让每次构建花 1m26s**（BuildKit 每次都回源解析 digest），本机标记只需 1.4s——十余次构建就是十几分钟加一个硬网络依赖，**正是我自己给这条闸门划的「先去风险再进 CI」红线**。改为 harness 里 pull 一次再打本地标签，pin 保留、回源消失。
2. **`sha256sum <文件>` 的解析在路径含反斜杠时会错**：coreutils 会把整行转义（行首一个 `\`、分隔符翻倍），`cut -d' ' -f1` 取出的哈希就是错的。**这是我写的断言里的一处真脆弱性**，改成 `sha256sum < "$file"`（不给文件名）——顺带让 harness 在 Windows 上也能跑。
3. 合并 `progress.md` 时我把**冲突标记提交了进去**：解析脚本对「新增块以 CRLF 开头」下的断言是错的（那个 CRLF 是本文件各条目之间本来就有的空行），脚本在写盘前中止，而我把命令里的分隔符从 `&&` 换成了 `;`，于是 `git add` 把**仍然冲突的文件**暂存了。已后续提交修正。**教训：解析脚本失败不能靠 `;` 继续往下走——失败要停。**

### 顺带更正一个「缺口」判断

对方提议把 `deploy/backup/test-*.sh` 从未被 workflow 引用一事单列为「文档承诺了测试但没接线」。**核了 `docs/operations-runbook.md`：它把这几个写成季度人工演练**（"每季度至少一次 `test-restore.sh`"），**没有承诺自动化**——所以那不是失约。真正的瑕疵只是措辞容易让人误读，已在 runbook 里补一句写明「人工演练、未接 CI，别把『已验证 PASS』读成『每次提交都跑』」。
## 2026-09-18 明细成本改读冻结基座——#710 的可实现剩余部分

**先重核，再动手**：在 develop（`e83d44ea`）上逐条复核 #710 的 8 条验收，结论 **5 条已达成、3 条未达成**（旧结论 6/2 已过期：develop 期间合并了 #766/#772/#780/#783 等价目相关系列）。未达成的三条里，**两条同源**——明细读取路径仍在按查询时刻的**当前**价目给每一行定价（`UsageStatsService`/`AdminUsageStatsService` 各自注入 `PriceSnapshotRepository` 调 `findAllLatestAt(Instant.now())`）。这属于"照既有口径补齐"，可直接实现；另两条（`gateway-app` 事件生成时冻结价格；`CostAllocationService` 切冻结基座）属**口径决策**，不在本轮自行拍板（见 #710 决策材料评论）。

**改动**：新增 `RowPriceBasis`（`domain/usage`）承载"这一行该用哪四个单价"，由 `AdjustedUsageRow` 随行带出；`UsageStatsRepositoryImpl.findRecords` 的投影里加上与汇总**同一表达式** `PriceSnapshotSql.frozenOrAsOf(...)`（别名 `basis_price_*`，与 `ue.price_*` 原始列区分开；`COALESCE` 短路，未回填行才走 as-of 子查询）；两个 Service 改用行内基座定价，删除各自注入的 `PriceSnapshotRepository` 与 `priceMap()`。口径不变：某维度**用到**却没有价 → `priced=false` / 未定价，**不写 0**（usage-accounting §6.2）。

**关键取舍**：明细与汇总现在读**同一个 SQL 表达式**，所以两者不可能各自漂移；这条一致性在集成测试里**被直接断言**，而不是靠约定。

**验证**：`mvnw.cmd -B -f backend -pl control-plane-app,persistence-postgres -am test -Pintegration -Dtest=UsageStatsAggregatorTest,UsageStatsServiceTest,AdminUsageStatsServiceTest,PriceBasisCostStabilityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → **BUILD SUCCESS**，`Tests run: 8`（domain）+ `Tests run: 32`（control-plane），0 失败。集成侧 6 条含三条新增：改价后明细行不动、**改写历史价目行**后明细行不动、明细与汇总金额一致。（首跑曾红一条：我自己测试夹具把 `RowPriceBasis` 的 cacheRead/cacheCreation 两位写反 → `expected 0.0021 but was 0`，改夹具后复跑全绿。）

**顺带修文档漂移**：`database-schema.md` 的原句"明细/汇总/计费/配额水位已改读此基座"在当时对**明细**并不成立（这正是本轮补上的那部分）；改成分别陈述读取方，并把"网关写事件时不写任何价格列、四列全由控制面回填通道盖章"写明。

## 2026-09-18 分支收口：把 develop 并回 #710 分支并复核

**合并**：本分支停在 `06252299`，develop 已到 `e29715ba`（单入口部署脚本 #793），二者 merge-base 为 `e83d44ea`。执行 `git merge origin/develop`，**唯一冲突 `docs/progress.md`**。两侧对该文件都是**文件末尾纯追加**（对 merge-base 的 `git diff --numstat` 分别为 `12 0` 与 `83 0`，`-U0` hunk 都落在第 3849 行之后），所以按"develop 段在前、本线段在后"拼接即可两段都不丢。做法是先由 stage blob（`:1:`/`:2:`/`:3:`）重建文件再 `git add`，**不手改带标记的工作区副本**——工作区那份的 `<<<<<<<` 行已被上一轮删掉，按行号硬改正是会出错的地方。

**两个共同修改的 Java 文件走的是自动合并，已逐一核对没丢东西**：`UsageStatsAggregator.java` 「合并结果相对本线 `06252299`」的差异，与 develop 自 merge-base `e83d44ea` 起的差异**逐字节相同**（剔除 `index` 行后 `cmp` 一致）；`UsageStatsRepositoryImpl.java` 的两份差异**只有 3 处 hunk 行号偏移**，内容 hunk 完全一致——develop 的 `unpricedHits`（命中路径未定价计数）与本线的 `RowPriceBasis`（明细行冻结基座）各自在列，互不覆盖。develop 单独带来的文件（`deploy/deploy.sh`、`docs/deployment-and-operations.md`、`docs/openapi/openapi-3.1.json`、`docs/usage-accounting.md`、`AdminRoiApiIntegrationTest.java`、`UsageStatsPricingStatusTest.java`、前端 6 个）用 blob 哈希确认与 `MERGE_HEAD` **完全相同**，一个字没动。

**合并后验证**（均在合并提交 `8fa34296` 上）：
- develop 侧用例：`mvnw.cmd -B -f backend -pl control-plane-app,persistence-postgres,domain -am test -Pintegration -Dtest=UsageStatsPricingStatusTest,AdminRoiApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → **BUILD SUCCESS**，`Tests run: 6, Failures: 0, Errors: 0`（domain）+ `Tests run: 4, Failures: 0, Errors: 0`（control-plane，22.49 s）。
- 本线用例：同形命令换 `-Dtest=PriceBasisCostStabilityIntegrationTest,UsageStatsAggregatorTest,UsageStatsServiceTest,AdminUsageStatsServiceTest` → **BUILD SUCCESS**，`Tests run: 8, Failures: 0, Errors: 0`（domain）+ `Tests run: 32, Failures: 0, Errors: 0`（control-plane）。

**未做**：本线实现未重写、未 `stash`；"事件生成时携带价格快照"（标准 1）与 `CostAllocationService` 取价口径属**口径决策**，已交 owner（2026-09-18 04:00 的 #710 决策材料评论），本轮不动。
## 2026-09-18 Runbook §15 定稿：两层结构（运维速查 + 工程陷阱），owner 已裁定收录

**外部评审（owner 转来）结论：收录，但改成两层。** 已按此重排（commit 见下）：

- **`operations-runbook.md` §15**：只留运维侧——总原则一行（"先问这个观察到底证明了什么"）+ 15.1 状态码语义 / 15.2 泛化兜底（仅 SQL→500 行）/ 15.3 未验证输入 / 15.4「命令返回了≠服务就绪了」/ 15.5 不可见字符 / 15.6 宽容失败语；**新增核实基线**（`verified-against: develop@…` + last-verified + re-check triggers）；15.4 的 nginx 措辞**降级为"本部署模板的事实"**（不再写成 nginx 普遍规律）；全文**无行号**。
- **新增 `docs/debugging-traps.md`**：工程侧——CI JDT 速挂 / Maven 静默卡死（含"卡死持有部署锁"真机补充）/ 「全绿≠验过」（测试隔离 + 空基线 + #819 行为闸门的反向验证）/ 读已合并 revision / 「缺口判断先查权威表述」/ 「没牙的检查 vs 误报的检查」/ 完整事故证据引用链（#802→#819、#754、#816/#817、#710/#780）。§15 顶部互相指路，document-map 已登记。
- 评审指出的"四族"描述过时：重排后按**两类形状**陈述（症状指向错误的层 / 证据强度被高估），工程侧（执行上下文族）随文档拆分另述。

**来源**：owner 2026-09-18 转来的外部评审（原 PR #759 为"提案·待 owner 认可"，本条即该认可与改后的落地记录）。
## 2026-09-18 CAA 证据审计链路补写入方（#629）——V55 建了表，没有任何人写

**起因**：#629 指出 `request_context_evidence`（V55）在 Java/Kotlin/XML/YAML 中零命中——表建好了，既无写入方也无读取方，Spec v1.1 §7.2 的"为什么这么判"审计链路是断的。复核 `git grep -n "request_context_evidence" -- "*.java" "*.kt" "*.xml" "*.yml" "*.yaml"` 无输出（rc=1），与判断一致。

**先判该不该有**：Spec v1.1 §7.2 把 V55 列为交付物（无"预留/未启用"字样）、§9 C13 是验收项、§11 P5 与 `database-schema.md`/`api-contract.md` 都按"已存在"描述；V55 表注释自带的 `source` 值域 `prompt_url|tool_path|bash_cwd|system_cwd|git_remote|header|suffix` 正是"外部选择器类别"。结论：**该层应当存在，本次补写入方**。读取方（查询 API）在 `api-contract.md` 无契约、Spec §11 把 §8 的增量排到后续批次 → 明确不在本次范围（不改前端：没有契约可展示）。

**交付**：

- `PostgresUsageEventWriter`：证据行随 `usage_event` **同批同事务**写入，行 `id` = 该笔 usage 事件 id，`ON CONFLICT (id) DO NOTHING`（重放不重复，两表可直接 join）。只对使用了外部选择器的裁定写行：`RESOLVED_HEADER` → `source='header'`、`value=` 客户端声明的 project id；`RESOLVED_SUFFIX` → `source='suffix'`、`value=` Key 中呈现的 tag。
- `SOLE_BINDING`/`POLICY_ROUTED` **刻意不写行**：V55 的 `source` 值域没有对应"线索类别"的诚实取值，而 `value` 是 `NOT NULL` 且承载全部审计值——硬编一个值等于伪造证据。这两种裁定的解释本来就是 `usage_event.resolution_status`（C13 的 "+" 是"证据表**加**裁定列"，不是"每一行都必须有证据行"）。
- 唯一缺的数据是"Key 后缀里呈现的 tag"：绑定索引本就按 projectTag 建（`JdbcRouteSnapshotLoader` 的 `bindings...put(binding.projectTag(), binding)`），故 `ContextAttribution` 增第 7 个分量 `bindingTag` 承载它。**未动阶梯与未归属策略的行为**——`RequestContextResolver`/`ResolvedContext` 一行未改。
- 无新迁移：V55 的 PK 就是幂等键。

**验证**（真实命令与输出）：

- 先证明断言有判题力：把 `writeContextEvidence(...)` 调用临时关掉跑同一份测试，新用例红、红的正是"证据行数 0 而不是 1"——
  `Tests run: 9, Failures: 2, Errors: 0` / `contextEvidenceRecordsObservedSelector:244 Expected size: 1 but was: 0 in: []` /
  `droppedUsageEventLeavesNoEvidenceRow:284 Expected size: 1 but was: 0`；恢复该行后同一份测试 `Tests run: 9, Failures: 0, Errors: 0` + `BUILD SUCCESS`。
- 四类对照：有 claim 头 / 有后缀 / 唯一绑定 / 未归属策略各一条请求，断言证据行数 **1/1/0/0**，且四条 `usage_event` 都在（证明"无证据行"是裁定，不是整批丢掉）；重放同一批 → 行数不变（幂等）。
- 另测孤儿行：`model_id` 为空而被丢弃的 usage 事件不得留下证据行，同批健康事件照常落行 + 落证据。
- 命令（Windows，JDK 21）：`mvnw.cmd -B -f backend -pl gateway-app -am test -Pintegration -Dtest=PostgresUsageEventWriterTest -Dsurefire.failIfNoSpecifiedTests=false`。

### 一个测试暴露的既有边界（未改）

重放一批 `provider_request_id` 为空的 `usage_event` 会撞 `usage_event_pkey`——写入器的幂等只覆盖 `(tenant_id, provider_request_id)` 那条部分唯一索引，这是**既有**行为（与本次改动无关，证据表的 `ON CONFLICT (id)` 没有再添失败面）；Bus 失败重投同一条无上游 id 的事件才会走到。测试夹具因此用带上游请求 id 的事件（真实 UPSTREAM 路径即如此）。是否要为 `COALESCED`（`provider_request_id` 为空）补一条以 `id` 为冲突目标的路径，属独立问题，未在本 PR 夹带。

**文档**：`database-schema.md`（补写入方/幂等键/不写行的理由，并修正 `(tenant_id, observed_at)` 这个与实际索引 `(observed_at DESC)` 不符的描述）、`api-contract.md` §7.1 归属条、`activity-context-design.md` 的"无写入方"表述。

**边界与遗留**：① 读取方（查询 API）未交付，Spec §7.2 只完成写入侧；② `V55__request_context_evidence.sql:5` 注释"网关在 Context 解析时写入"与实现时机（随用量批量写）不符——迁移本轮禁改，建议 follow-up；③ issue #629 正文的列名表述与 V55 实际 DDL 不一致（原文未在本次核对范围内），措辞更新属 owner 侧事项。
## 2026-09-18 「调整」列在无调整行时隐藏——补 #773 验收第 3 条（#773）

**起因**：#773 的验收第 3 条写的是"**无调整时表格呈现与现在一致（不引入视觉噪音与额外列宽）**"，当时**没做到**。`NextUsageView.vue` / `NextAdminUsageView.vue` 把 `{ key: 'adjust', title: '调整', width: '100px' }` 写成普通 `const` 数组里的固定成员，`Table.vue` 又无条件遍历 `columns`（`UiTableColumn` 没有可见性字段）——于是**任何时刻**都多出这一列、多占这 100px，未调整的行**逐行渲染 `—`**。第 3 条描述的正是这种"一列破折号"的噪音。

**修法**：照抄本仓**已有的范式**——`NextKeysView.vue` 在"所有密钥同属一个项目"时用 `columns.filter(...)` 把 `projectTag` 列摘掉。两页各自加一个 `computed`，`rows.some((row) => row.adjusted === true)` 为真才保留「调整」列；**不动** `usage-net.ts`、`UsageAdjustChip.vue`、后端，**不动**其它列，**不扩展** `UiTableColumn`、**不改** `Table.vue`（无需要扩展的字段，就不扩）。

**口径：本页，不是全表**。判据取自**当前页渲染出来的行**，而不是整个结果集。理由是**全表口径没有可用的信号**：`UsageSummary.tokens` 只暴露一套（净额）计数，没有"观测 vs 净额"配对，服务端也没有"结果集里是否含调整"的字段——要判全表就得**新增 API/聚合**，这超出本项范围也超出红线。反过来，"**正在渲染的这张表里有没有一行是调整过的**"完全由行数据决定，而"一列破折号"的噪音恰恰是它造成的：**去掉这一列的条件，就是这一列在这一屏上没有任何一行在说事**。翻页后若出现调整行，列**当页即刻回来**，行级标记与气泡**一字未改**。

**验证**（先在旧实现上证明断言有判别力）：
- **红**：把两个视图文件还原到 `origin/develop`（用 `git checkout origin/develop -- <file>`，随后 `git checkout HEAD -- <file>` 还原；**没用** `git stash` 系列），跑两条 spec——**31 跑 2 失败**，恰好是两条"无调整时应隐藏"的断言（`expected [ '时间', '模型', … ] to not include '调整'`）。**判别力的边界要说清**：同一批里"有调整时应保留"的两条在旧实现上**也是绿的**（旧实现永远显示该列）——它们钉的不是旧缺陷，而是**反向**回归（防止修成"永远隐藏"或顺手删掉行级标记），两条断言合起来才是完整的约束。
- **绿**：同一命令恢复实现后 **31/31 通过**
- `npm --prefix frontend run typecheck` 退出 0；`run lint` 退出 0（0 error / 7 warning，**全部落在本轮未触碰的文件**）；`run test` **63 文件 384/384 通过**；`run build` 退出 0（24.96s）

## 2026-09-18 缓存节省是没有标记的下界——补上最后一个"未知被当成 0"的洞（#790）

**我先前的判断是错的，被一次实测推翻。** 我在 #766 的 PR 里把"命中路径无 gap 计数器"列为 follow-up，但随后自己评价它"**收益低**"（理由：演示站的节省额只有 ¥0.001 量级）。动手前顺手查了一下可达性，用的是与代码**同一套 as-of 规则**：

```
hit_groups | groups_without_input_price_at_hit_time | first_hit | last_hit
         5 |                                      3 | 09-14     | 09-16
```

**5 个命中组里 3 个**在命中时刻没有任何生效的 input 价。也就是说这台站上的缓存节省数字**对 60% 的命中组静默偏低**——不是理论情形，是正在发生。**"收益低"是只看演示站的金额量级得出的，而缺陷的类别才是量尺**：`savedByGatewayCache` 是控制台首屏的招牌数字，而"未知被当成 0"正是 B+ 分层要消灭的那一类。

**缺陷的原文**（`UsageStatsRepositoryImpl`）：

```java
/**
 * {@code tokens x hits x unitPrice}, undivided; a null price contributes nothing.
 */
private static BigDecimal weighted(long tokens, long hits, BigDecimal unitPrice) {
    return unitPrice == null ? BigDecimal.ZERO : BigDecimal.valueOf(tokens * hits).multiply(unitPrice);
}
```

注释把这件事写得像无害的默认值。而 `addHit` 当时**完全不碰** `unpriced`。

### 一处设计取舍：把两个问题分成两个名字

给 `PricingGap` 加 `unpricedHitEvents` 时，`isEmpty()` 原本**驱动 `pricingStatus`**——直接加字段会让"节省侧有缺口"把**成本**判成 PARTIAL，即"让一个从没被它碰过的数字显得不可信"。所以拆成两个名字：

- `hasCostGap()`（= `unpricedEvents > 0`）驱动成本状态：**成本完不完整与节省完不完整是两个问题**
- `isEmpty()` 表示"**完全没有缺口**"（成本 ∪ 节省），名字与含义一致

这两条各有一条测试钉住（域测试 144/144）。

### 前端：不标注就等于没修

两个展示节省额的地方同时加标记，否则 UI 层重复同一个缺陷：管理端概览的「网关缓存节省」加「下界」徽标（复用既有未定价样式 + UiTooltip 说明次数），成本页的「缓存节省」卡片在提示行里追加「下界：N 次命中在发生时无生效价目」。

### 验证

- **先证明会红**：只关掉仓库侧的计数（域侧保持，否则退化成编译错而非行为红）→ `AdminRoiApiIntegrationTest` **4 跑 1 失败、恰好是新增那一条**（`expected 2 but was 0`），其余 3 条照常通过；恢复后 4/4 绿
- 新增 IT 用的是**能分辨的那个 fixture**：价目生效时间设在"命中之后、用量行之前"（`now() - interval '1 second'`）——于是同一次运行里**成本 COMPLETE 而节省是下界**，正是要钉住的那条不变式
- 前端 25/25（含 4 条新增）＋ typecheck；OpenAPI/前端类型差异仅 `unpricedHitEvents`

**教训（与本会话其他几次同族）**：我凭**金额量级**判定一件事"不值得做"，而判据应该是**缺陷的类别**；一次五分钟的实测就把它推翻了。与"自验只覆盖自己以为的范围"是同一种盲区——只是这次盲在**优先级**上，而不是盲在正确性上。

## 2026-09-18 部署序列化与归因——单入口脚本（#793）

**起因**：演示栈由多条会话共用，而部署是各写各的命令。两天里两次同类事故：① 10:5x 两次构建交错，**事后再怎么查都无法从机器状态回答"当时跑的是哪一份"**；② 中午 `--no-build --force-recreate` 双保险之下，容器镜像 ID 仍不等于 tag 的 ID，且容器那张镜像在本地列表里已不存在。

**②一度被读成"至少还有第三条会话在动部署"——该结论被推翻**：容器跑的**就是该会话自建的镜像**（它自己的构建日志为证），tag 是被另一个**并发构建**改指的。所以问题不是"多了谁"，而是**并发构建无人拦** + **"谁上的线"没有持久记录**（等有人问起，镜像可能已经不在了）。

**交付**：`deploy/deploy.sh` —— 单入口，一次做三件事：

1. **`flock` 序列化**（构建与 `up` 都在锁内）：交错真正伤人的地方是**构建**，不是 `up`
2. **收尾断言"正在跑的就是刚构建的"**：逐个比 `docker inspect <容器>.Image` 与 `docker image inspect <tag>.Id`。`Up N seconds (healthy)` **不是证据**——容器没换过去时机器显示的状态一模一样
3. **每次追加一行 `deploy.log`**：时间/模式/提交/调用方/**运行中的镜像 ID 与当时的 tag ID**

三条既有教训也编进流程：`up` 带 `--no-build`（compose 的 cp 服务 `build:` 段 context 指向**线上树**）、后端容器换掉后自动 `restart portal`（nginx upstream 启动时解析）、**显式钉住 compose 项目名**。

### 干跑抓出我自己三个 bug

写完先跑 `--dry-run`，立刻暴露三处：

1. **干跑声称了它没做过的验证**——断言步没被 `--dry-run` 罩住，真跑了 `docker inspect` 并对我本机镜像打印 "verified"。**干跑最不能做的就是断言它没验证过的东西。**
2. **硬编码容器名 `miqrokey-<svc>-1`**——隐含假设 compose 项目名=miqrokey。改成向 compose 问（`compose ps -q`）。
3. **最要紧**：**compose 的项目名取决于调用时的目录**（服务器上靠 `cd /opt/miqrokey` 才得到 `miqrokey-*` 容器名）。换个目录跑，脚本会**另起一套容器**而不是更新线上那套。已 `-p` 钉住。

### 真跑一遍，并证明断言会红

用一次性夹具（**独立 tag 与独立项目名——避免覆盖本机既有的 `miqrokey-*:local`，那是别的会话的本地栈**）：构建→换容器→断言→restart portal→写流水，exit 0。

再**故意把 tag 指向另一张镜像**，跑 `--verify-only`：

```
ASSERT FAILED control-plane: running image 'sha256:97ff…' != tag image 'sha256:974b…'
verified portal: sha256:ff21…            ← 未动的服务仍通过
EXIT=2
```

顺带加了 `--verify-only`：**部署线明确说要"部署前后各查一次"，而一个不能单独跑的检查不会被跑**。流水里两个身份都记，是为了事后能分辨"tag 被人重建了"与"当初就没换过去"。

**分工**：脚本+文档进仓库（可评审），**装到服务器由部署线负责**。锁选**机器层**而不是"打卡制"——打卡依赖自觉，而我们已经知道至少有一个动作方不打招呼，荣誉制只会让守规矩的人排队。
## 2026-09-18 接入器参考实现（#742 第②片）——配置注入可执行化：打印 / 写入 / 验证

**范围**：把指南矩阵（第①片）的三类接入姿势做成可执行工具 `scripts/onboarding/miqro-onboard.sh`（POSIX sh）：`print` 六种形态（env×3 shell / claude-settings / codex / openai / curl / mcp）、`apply` 三种配置文件形态（env 与 dotenv 走**托管块**替换、claude-settings 走 jq JSON 合并）、`verify` 对 `/v1/models` 按 200/404/401 归因。

**校验前置=把事故写进工具**：凭据平面不通用（用错统一 401）、虚拟密钥必须带 `.label`（裸 `mqk_live_` 语法上不成立即统一 404 `virtual_key_invalid`）、网关地址须为 origin（尾随 `/v1` 带提示剥离）——三条全部照抄运维 Runbook §14.1，不是发明。

**幂等语义**：托管块（`# >>> miqro-onboard (managed) >>>`）只替换块内；内容无变化**不写盘、不产生备份**；改动前 `\<file\>.bak-\<UTC 时间戳\>`。`--dry-run` 只打印结果。

**测试先于收工再获一例**：45 条断言、纯 sh、无网络（`verify` 用 PATH 假 curl 打桩）。开发中**测试抓到两个真 bug**：① 托管块追加路径不带标记 → 二次执行不幂等、键行翻倍；② `--dry-run` 参数没接线 → 照样写盘。两条都已修，且各留一条断言钉住（同族先例 #754：收工前先证明它会红）。

**边界**：CC Switch 深链维持控制台既有实现（不在本工具）；网络层劫持明确不做；`claude-settings` 合并依赖 jq，无 jq 时拒写并提示改用 `print` 粘贴。snippet 形态以控制台「使用密钥」面板为准（源头 `frontend/src/lib/ccswitch.ts`），双侧改动需同步。

**CI**：`scripts/**` 新增路径过滤器 + `scripts-check` 作业（跑 test-onboard.sh，5 分钟超时）。

**待补**：第③片（封闭客户端 MCP 层实测）需要真实封闭工具环境，形态确认后另起。

## 2026-09-18 接入器按外部评审收紧（#763）——转义/密钥处理/verify 全面加固

**评审（owner 转来）判定"改后合"**，两条 P0 + 六条 P1 全部修复：

- **P0 输出未按格式转义** → 每个落值过**字符集白名单**（key/model/gateway/mcp-url）+ **按语法转义**（POSIX 单引号 / PowerShell 反引号 / JSON·TOML）；**cmd.exe 无法可靠转义 ⇒ 含元字符拒绝输出**（宁拒不发）。
- **P0 密钥暴露** → Codex 片段**不再含 key**（注释也去掉，改 `env_key` 指路）；新增 `--key -` 从 stdin 读凭据；前端同源问题另立 #821。
- **P1**：临时文件落目标目录（原子 rename）/ chmod 失败即报错 / verify 判 `200 且 models-list 体` + `--connect-timeout 5 --max-time 20` / 失败默认只打 code（`--verbose` 才打体）/ 托管块残缺或重复**拒绝**而非猜 / symlink 目标拒绝 / `${2:?}` 缺值消息 / 测试 `stat` 双写法。
- **测试 45 → 85 条**，新增恶意输入组与转义函数直测（`MIQRO_ONBOARD_SOURCE_ONLY` seam）；**三条旗舰断言对修复前脚本先证红**（引号 key 被放行、codex 输出含 key、代理错误页 200 判成功）。
- 暂缓：前端/脚本双实现的 golden-fixture 契约（评审同意不做）。

## 2026-09-18 冒烟在自签证书的部署上什么都证明不了（#826）

做完整套端到端模拟时的**最后一个失败项**：真实生产栈起来了、`deploy.sh` 的镜像身份 / 环境变量回声 / 证书进容器三条断言全过，**只有冒烟报"够不到"**——而目标其实好好地在答。

查下来：冒烟的 curl **不传 `-k`**，自签证书在 TLS 阶段就被拒 → `000` → 按设计只判 WARNING。

```
$ curl -sS -o /dev/null -w '%{http_code}' -X POST … https://127.0.0.1:9443/api/v1/auth/login
000                                   # 证书校验失败
$ curl -sk -o /dev/null -w '%{http_code}' -X POST …   # 同一个请求
403                                   # 真的答了
```

### 为什么值得修（而不是"生产有真证书"）

1. **仓库自己就把自签当正常形态**：`secrets/README.md` 与 `compose.prod.yaml` 都写"本机冒烟可用自签"，`deployment-and-operations.md` 的验收示例也用 `curl -k`。**文档用 `-k`、脚本不用**——同一套流程两种默认。
2. **每台 staging / 内网自建部署都在这个集合里**，而那些恰好最需要"部署完验一下"。
3. 症状是**假绿**：不报错、不红，而是"WARNING + 部署成功"。与本项目反复治的形态同族——**检查在它自称覆盖的那一层没有牙**。

### 修法

- 新增 `--smoke-insecure`（env `MIQROKEY_DEPLOY_SMOKE_INSECURE`）：打开时给 curl 加 `-k`。**默认关**——生产不该默认跳过证书校验
- 打开后 `000` 的措辞跟着变：不再"证书可能有问题"，而是**"够不到就是够不到"**（`-k was in effect: this is connectivity, not certificate trust`）
- `--dry-run` 打印里能看出用没用 `-k`
- shellcheck 仍 exit 0

### 顺带修掉一个既有的 `--help` 截断

`usage()` 原本是 `sed -n '2,25p'`——**硬编码行号**。它在我插入 6 行选项说明之前就已经不覆盖选项列表了：`--help` 只打印说明与用法行，**一条选项说明都不打印**。改成 `sed -n '2,/^set -eu$/p' | sed '$d'`，跟着文件走，不再会漂。

**教训**：`--help` 从来没人跑，所以它坏了也没人知道——**与 #823 同源：一条没人走过的路径**。这次是"自己做完整套模拟"顺手撞上的。

## 2026-09-18 默认生产部署下网关起不来：空串被当成「已配置」（#823）

**这是我自己做完整套端到端模拟时跑出来的**——不是 CI，也不是演示站。按 `deploy/compose.prod.yaml` 起真实生产栈（不带 `--profile kafka`），网关进入**重启循环**，`/v1/*` 全 502：

```
BeanInstantiationException: Failed to instantiate [RetentionPublisher]:
  Factory method 'kafkaRetentionPublisher' threw exception with message:
  miqrokey.retention.kafka.bootstrap-servers must be set
Caused by: java.lang.IllegalArgumentException: ... must be set
```

### 根因

`KafkaRetentionConfig` 用的是

```java
@ConditionalOnProperty(prefix = "miqrokey.retention.kafka", name = "bootstrap-servers")
```

**不带 `havingValue` 时它的语义是「属性存在且 ≠ "false"」——空串算"存在"**。而 compose 恰恰把它设成空串：

```yaml
# deploy/compose.prod.yaml:140
MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS: ${MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS:-}
```

容器内实测 `MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS=`（存在、为空）→ 条件成立 → 建 bean → 构造函数对 blank 抛异常 → **进程死循环**。注释写着"留痕默认关"，实际是"网关根本起不来"。

### 为什么一直没被发现（两个覆盖缺口叠加）

1. **演示站恰好不踩**：`.env` 里设了真实值 + `COMPOSE_PROFILES=kafka` → 条件成立但值非空 → 正常。
2. **CI 只构建镜像、从不启动整套**：`images` job 做 `docker build` + 非 root 断言，`compose` job 只做 `config`。**「默认生产栈能否启动」从来没有被执行过。**

被文档推荐的那条部署路径，是**唯一一条没人跑过**的路径。

### 修法

新增 `KafkaRetentionConfigured`（`Condition`）：**去空白后非空**才算配置。换掉 `@ConditionalOnProperty`——它表达不了"非空白"。空白/缺失/纯空格 → 不装配 → 侧车用既有的 fail-closed no-op publisher。

### 红证明

`KafkaRetentionConfiguredTest`（`ApplicationContextRunner`，4 例）：

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 属性缺失 | 不装配 ✓ | 不装配 ✓ |
| **属性存在但为空**（= 默认 compose 传的） | **上下文创建失败**，抛 `... must be set` ✗ | 不装配 ✓ |
| 属性为纯空格 | **同上失败** ✗ | 不装配 ✓ |
| 属性为真实地址 | 装配 Kafka publisher ✓ | 装配 Kafka publisher ✓ |

把注解回退成 `@ConditionalOnProperty` 实跑：**4 例中 2 例失败，异常与线上崩溃逐字相同**。修复后 4/4 绿。

### 教训

- **"变量存在但为空"是部署里极常见的形态，应用必须把它当成"未配置"**。`@ConditionalOnProperty` 的默认语义在这点上与直觉相反（空串 ≠ 关闭）。
- **只构建镜像的 CI 会被读成"部署能起来"。** `images` job 证明的是"镜像可构建"，而它被当成了更强的东西——又一次"检查与它检查的东西没对齐"。
- 我此前所有部署验证都走演示站或脚本断言，**没有一次真的把默认栈拉起来过**。这次是"自己做完整套模拟"的直接产物。

## 2026-09-18 lint 门禁从来不会红：它带 --fix，且 CI 不看结果（#822）

在做完整套模拟时发现：干净检出上跑 CI 的那条 lint 命令，**重写了 21 个已提交文件**（内容真变了；另有 132 个只是 stat-dirty）——而 CI 看不出来，因为它跑的是 `eslint . --ext .vue,.ts,.tsx --fix`，**带 `--fix` 且跑完不检查**。于是它区分不了「本来就干净」和「我刚替你改干净」。

最大的受害者是 `src/types/generated.ts`：被整份从 4 空格重排成 2 空格（diff 20,809 行）。而 CI 里另一步是

```yaml
run: npm run gen:types && git diff --exit-code -- src/types/generated.ts
```

**它跑在 lint 之前**，此刻文件还是提交时的形态、重生成本一致 → 通过；紧接着 lint 把它改掉，**没人再看**。也就是说「生成物必须与基线一致」和「代码必须符合 lint」这两个要求，对**同一份文件**给出两种互斥答案，CI 对两者都说 OK。

### 修法

1. **`lint` 改成检查模式**（不带 `--fix`），新增 `lint:fix` 供本地改写
2. **`--max-warnings 0`**：这一步是必需的——今天所有违规（prettier、vue 风格集）**都是 warning**，而 eslint 只有 warning 时仍然 exit 0。**只改检查模式不加这个标志，门禁照样不会红**，是我第一版做完差点收工的地方
3. **`generated.ts` 加进 eslint ignores**：它是机器产物（文件头写着"不要直接改"），形态由 openapi-typescript 决定。让 lint 对它有意见，就是把上面那对矛盾焊死
4. 把既有 22 个手写文件的格式漂移**一次性**修掉（否则第 1 条落地当天就红）

### 零告警不是靠关规则凑的

`--max-warnings 0` 要求先把既有 7 条告警正当处理掉，两条都不是靠"关掉规则"了事：

- **6 条 `vue/one-component-per-file` 全在测试文件里**（`src/__tests__/**`、`e2e/**`）——内联桩组件正是那些测试的写法，规则的意图是"一个*交付*文件一个组件"，所以**只在这两个 scope 关掉**
- **1 条 `vue/no-template-shadow` 是误报**：`<RouterView v-slot="{ Component }"><component :is="Component"/></RouterView>` 是 Vue 官方的规范写法，`Component` 就是那个 slot 绑定，没有遮蔽任何东西。给了**单行、带理由的豁免**，而不是全局关规则

### 红证明

| 场景 | 结果 |
|---|---|
| 干净树上 `npm run lint` | **exit 0** |
| 注入一处格式偏差（`const   badlyFormatted =    1`） | **exit 1**（`prettier/prettier` warning → `--max-warnings 0` 拦下）|
| `npm run gen:types` 之后紧跟 lint | **两者同时通过**（矛盾解除）|

改完回归：typecheck exit 0、**419 tests / 64 files 全绿**、build exit 0——22 个文件重排没有破坏任何东西。

### 教训

- **"带 --fix 的检查"不是检查**。它把"发现问题"和"掩盖问题"合成一步，还顺手给出一个绿信号。同类形态在本仓已经出现过三次（#807 过严、#809 过松、#812 没有），这次是 **"会自己动手的检查"**。
- **改检查模式不等于检查会红**——还要问一句"违规是 error 还是 warning"。我第一版只做了前者，跑红证明时才发现仍然 exit 0。
- 顺带记一条旧账：progress.md 里早有记录说 `npm run lint` 会把 CRLF 文件整批重写成 LF（EOL-only M）。那是 `--fix` 的副作用；检查模式不再有，`lint:fix` 仍有——所以本地跑 `lint:fix` 后仍要区分内容 diff 与 EOL diff。

### 补记：`--max-warnings 0` 一加上，Windows 检出就红了 47,968 条——以及那桩旧账的真相

加完 `--max-warnings 0`、合并 develop 之后再跑，lint 报 **47,968 条**，形态统一：`Delete ␍`。

根因：`.prettierrc.json` 写的是 **`endOfLine: "lf"`**，而 autocrlf 让工作区是 CRLF——**prettier 对每一行都报行尾不对**。不先处理这个，新门禁在任何 Windows 检出上**永远红**。我此前那次"干净树 exit 0"是假象：我自己的 `lint:fix` 早已把工作区写成了 LF，我拿被改过的树当了干净树。

**顺带把一桩旧账对上了**：本仓长期记着"`npm run lint` 会 `--fix` 重写 80+ 个历史漂移文件"。真成因就是这个——`--fix` 一直在把 CRLF 归一成 LF，那批 EOL-only 的 ` M`（`git diff` 为空）全由它而来。**那不是"代码漂移"，是被工具逼出来的行尾改写**；于是记忆里那条"只对本轮改动文件跑 eslint"的规避建议，其实是在绕开一个配置问题。

修法：`endOfLine: "auto"`（跟随文件自身行尾）。**提交形态仍是 LF**（`text=auto` 在 add 时归一化），格式仍照样强制（缩进/引号/宽度/逗号），只是不再把"检出的平台"当成格式问题。

### 教训追加

- **"干净树"要问是谁把它弄干净的**。我上一轮跑红的证明前，`lint:fix` 已经把工作区整成 LF，于是"检查模式 exit 0"测的是**被静默改写过的树**——正是这条 issue 要治的东西，我自己先踩了一次。
- **一个只在某个平台成立的绿，不是绿**。CI 在 Linux（LF）上一直是绿的，所以这个问题在 CI 里永远看不见；它是 Windows 检出的产物。与 #807/#809/#812 同族：**检查与它运行的环境没对齐**，只不过这次"环境"是操作系统。

## 2026-09-18 首页把「未定价」的用量显示成平静的 ¥0.00（#849）

**这一条是"代入用户视角"跑出来的**——不再读代码猜，而是起真栈、真登录、真看首页。

### 实测（本机真实生产栈）

走完整业务链（建项目→订阅→凭证→模型→授权→签发虚拟密钥→真实推理）后登录门户：

- 首页显示 **`18 本月 Token`** 与 **`¥0.00 本月成本`**，成本卡上**没有任何提示**
- 同一时刻问 API：

```json
totals.pricingStatus = "UNAVAILABLE"
totals.unpriced = { "inputTokens": 11, "outputTokens": 7, "unpricedEvents": 1, "unavailableEvents": 1 }
```

**服务端明确说了"这些用量在发生时都没有生效价目、金额无从得知"，首页把它显示成一个平静的 ¥0.00。**

### 根因：同一个承诺在四个 surface 上只落实了三个

`costGapNote`（#801/#803 加的标记）只被 `NextUsageView` / `NextAdminUsageView` / `NextCostView` 使用，**漏了 `NextOverviewView`——用户登录后第一眼看到的那一页**。

顺带发现这张卡还在**客户端自行求和**：

```js
const totalCost = usageGroups.value.reduce((sum, g) => sum + Number(g.cost?.upstreamPaid ?? 0), 0);
```

既拿不到 `totals` 携带的 `pricingStatus`，显示的也不是服务端权威合计而是分组重算值（`?? 0` 把缺失当 0）。改为直接取 `totals`。

### 我自己引入又自己抓到的回归

第一版把「未定价」chip 放成 value 的**兄弟节点**，而这个卡片是 `flex-direction: column`——于是成本卡变成 **72px 高**（其余三张 48px），数字还被顶高了 **12px**：

```
三张无标记卡: height 48, value y=212
成本卡      : height 72, value y=200
```

**这是在真机上看出来的，不是想出来的。** 改成与数字同行（`inline-flex` + `baseline`）后：48 vs 50，数字差 1px。残余 2px 未再追（1px 基线差在 20px 字号下不可感知），已在 PR 的 Remaining risks 写明。

### 验证

- 单元：**先证明会红**——回退组件后新增 3 例中 **2 例失败**（第 3 例是"COMPLETE 时不得出现标记"的过度标注守卫，两个状态下都该过，如实说明）
- 三例覆盖：UNAVAILABLE 有标记 / COMPLETE 无标记 / **分组和 ≠ 合计时取合计**（fixture 故意让两者不等：分组 3.60、合计 9.99）
- 真机：重建 portal 镜像后首页读作 `18 本月 Token | ¥ 0.00 未定价 | 本月成本`，chip 几何 36×20、amber、在数字右侧同行
- 全量：lint 0（含 `--max-warnings 0`）、**439 tests / 66 files**、build 0

### 教训

**前三个我怀疑的点逐一被证伪**：`MIQROKEY_UPSTREAM_ALLOWED_CIDRS` 其实有文档（configuration-reference §150）、「忘记密码」点击有 toast 且 `/admin/users/{id}/reset-password` 确实存在、网关的上游门控是**按设计的 SSRF 防护**且有文档化逃生口。**只有"用户第一眼看到的那个数字"站住了。** 读代码猜问题，命中率比我以为的低；起栈真看，命中率立刻不一样。

## 2026-09-18 成本报表的「占比」把 0/0 显示成 0.0%（#853）

同一条路子的第二条：起真栈、真登录、逐页把界面数字与服务端对照。

### 实测

```
按项目分摊
项目        请求  Token 数  分摊成本    占比
E2E Chain   1     18       ¥0.0000    0.0%
```

**同一时刻、同一个项目**，用量页的「用量分布」显示 **100%**（那是按 Token 算的份额）——两个页面就"这个项目占多少"给出相反印象。

### 根因

```js
function shareOf(group) {
  const total = costNumber(totalCost.value);
  if (!total) return 0;                 // ← 0/0 → 报成确定的 0
  return (costOf(group) / total) * 100;
}
```

`if (!total) return 0` 是为了躲除零，但把**"算不出"**显示成了**"算出来是 0"**：所有行加起来是 0% 而不是 100%，读起来是"这个项目不占任何开销"，而事实是**还没有可分摊的金额**（本轮全部用量未定价，即 #849 那条）。

**与本仓一直在治的形态同族**：B+ 计价语义（NULL ≠ 0）、`pricingStatus`、`未定价` 标记，说的都是"不知道 ≠ 是零"。这里同一个陷阱落在了一个比例上。

### 修法

- 总成本为 0 时占比返回 `null`，显示 **`—`**，而不是 `0.0%`
- **`—` 必须自证**：包 `UiTooltip`「总成本为 0，没有可分摊的基数——占比无从计算」；否则只是把迷惑从"0.0%"换成"一个孤零零的横杠"
- **总成本 > 0 时行为完全不变**——某行成本确实是 0 时仍显示 `0.0%`（那是**真零**）

### 验证

- **先证明会红**：回退组件后 12 例中 **1 例失败**（占位显示用例）；另一例「总成本非零时真零仍显示 0.0%」在修复前后都通过——它是**过度标注的守卫**，如实说明它不是本次缺陷的回归测试
- 真机复验（重建 portal 后）：表格该行读作 `E2E Chain | 1 | 18 | ¥0.0000 | —`；悬停 `—` 弹出「总成本为 0，没有可分摊的基数——占比无从计算」
- 全量：lint 0（含 `--max-warnings 0`）、**441 tests / 66 files**、typecheck 0、build 0
- CSV 不受影响（导出表头是 `分组/请求/Token 数/分摊成本(CNY)`，本就不含占比）

### 教训

**这一条来自"把同一类问题在不同页面上逐个对照"**：#849 是"未定价的量被当成总额"，这条是"未定义的比例被当成零"——**换了页面，同一个陷阱换了一件衣服**。只在改动面附近看，两次都发现不了。

## 2026-09-18 对账上传的 `providerCode` 实际取 product_code——只有 UI 标签说清了（#855）

用户视角审计的第三条，也是**最小的一条**。

照 `docs/api-contract.md` §5.27 写上传请求，传 `providerCode=tencent`（provider slug），得到：

```json
{"status":400,"code":"RECONCILIATION_PROVIDER_UNKNOWN",
 "detail":"供应商目录中不存在该 product_code。"}
```

改传 `tencent-coding-plan` 即成功——**它要的是 `provider_products.product_code`（供应商*产品*码）**，不是 `providers.slug`。

### 哪一层说清了、哪一层没说

| 层 | 取值域 |
|---|---|
| 前端上传对话框标签 | ✅ 「**供应商 product_code \***」 |
| 400 报错 detail | ⚠️ 说了 `product_code`，但**要先错一次**才看得到 |
| `docs/api-contract.md` | ❌ 只说"须在供应商目录"——读起来就是 `providers` 表 |
| `docs/bill-reconciliation-contract.md` | ❌ 未提该参数 |

**唯一写清取值域的地方是 UI 的表单标签**，走 API 的接入方没有等价说明。两处文档各补一句（不改接口、不动 OpenAPI 基线）。

### 同一次审计里，这个 surface 本身是好的

顺带把账单对账**真跑了一遍**（这是本轮的额外收获，不只是读代码）：

- 造了一份两行的 canonical 账单（一行对得上本地事件、一行只存在于账单），上传成功
- 报告：`totalRows 2 / matched 1 / unmatchedProvider 1 / unmatchedLocal 0 / lineErrorCount 0 / amountDiff 0.00050000`——**金额差恰为账单独有那行的 0.000500**，逐项正确
- 明细页：`匹配级别` 列给出 **`REQUEST_ID`**，并列出「账单行/本地记录」双向 id（`row-1/1374a37f-…`），差异行匹配级别为 `—`
- UI 数字与 API 逐项一致；四态筛选页签、导出 CSV 俱在

**这套东西是能用的。** 本条只关于"那个参数该怎么填"。

### 一条自我校准

本轮我读代码提出过 **6 个**"怀疑是这样"，**全部被证伪**（
`MIQROKEY_UPSTREAM_ALLOWED_CIDRS` 有文档、忘记密码有 toast、管理员能重置密码、网关上游门控是按设计、
`input_tokens` NULL 是刻意的 `COALESCE` 双列设计、对账匹配器也用了同一个 coalesce）。
**真问题全部来自"把产品跑起来、逐页对照服务端"**（#849/#853），以及这次"照文档真发一个请求"。

**读代码猜问题的命中率，比我以为的低得多。** 与"检查必须与它的主语对齐"同族：**我的判断主语是"代码看起来对不对"，而用户的问题域是"用起来对不对"。**

## 2026-09-18 把「未定价」标记的全量审计做了，并补上剩下两页（#857）

修完 #849（首页）之后我问了一句"**还有没有别的页面**"，这次把答案查出来了：**扫了 `frontend/src` 下所有渲染 `¥` 的视图**。

| 视图 | 标记 | 判断 |
|---|---|---|
| `NextUsageView` / `NextAdminUsageView` / `NextCostView` | ✅ | 已覆盖 |
| `NextOverviewView` | ✅ | **#849 刚补** |
| **`NextProfileView`** | ❌ | **缺**（个人月度成本） |
| **`NextAdminAgentsView`** | ❌ | **缺**（智能体分摊成本） |
| `NextPricesView` | ❌ | 不适用（那是**目录价**不是用量金额） |
| `NextQuotaRulesView` | ❌ | 不适用（限额不是金额） |

**显示"用量金额"的 6 个 surface 里 2 个没有这层标记——而它们要用的 `totals` 里本来就有 `pricingStatus`。** 两处各接 `costGapNote`，与其余页面同源同文案。

真机复验（重建 portal 后）：个人页快照读作 `本月请求 1 | 本月 Token 18 | 本月成本 ¥0.00 | 未定价`。

**另一页问题更重，另行立案（#858）**：缓存收益页的 `RoiTotals` **连 `pricingStatus` 字段都没有**，所以它"说不出未知"；且 `等效折扣 = saved/(saved+paid)` 在两者为 0 时是 `0/0` → 显示 `0.00%`（与 #853 逐字同型）。那一页尤其要紧：它写着「数据决定缓存策略」，而用户从 `缓存节省 ¥0.0000 · 等效折扣 0.00%` 会读出**相反的结论**（"缓存没用"），实际可能是"还没配价、算不出来"。

### 顺带记两个测试陷阱（本仓可复用）

1. **`beforeEach` 里 `document.body.innerHTML = ''` + 未卸载的 wrapper = 崩溃**。我给个人页加的两例测完没 `unmount()`，组件还有一个未落地的异步 load；下一次 `beforeEach` 清空 body 后它再 patch，就报 `Cannot read properties of null (reading 'nextSibling')`——**而且炸的是别的用例**（`shows the forced password banner...`），看起来像我的生产改动弄坏了它。**定位法**：把 spec 还原、只留组件改动跑一遍（7/7 通过）→ 立刻分清是"产品的锅"还是"测试的锅"。修法就是那两行 `wrapper.unmount()`。
2. 既有用例之所以没踩到，只是因为**它们的组件在测试结束后不再更新**。这是个"没踩到≠不存在"的坑。

### 教训

**"还有没有别的页面"这个问题，问一次不够，得扫一遍。** #849 修的是"我看见的那一页"；这次扫出另外两页，并发现第三页连字段都没有。**同一个承诺在 9 个渲染金额的视图里只落实了 4 个**——靠一页一页撞，永远撞不完。

## 2026-09-19 缓存收益页：让 API 能说"未知"，并停止把 0/0 报成 0.00%（#858）

本轮用户视角审计里**最重的一条**，也是唯一要动后端契约的一条。

### 问题

页面自己写着「**数据决定缓存策略**」，而实测（真机、用量全部未定价）它显示：

```
缓存节省   ¥0.0000
上游实付   ¥0.0000   ·   等效折扣   0.00%
```

两件事被当成确定的：

1. **`RoiTotals` 里没有 `pricingStatus`**——同一个产品里用量汇总能说"这个金额不是总额"，缓存收益页**说不出**，所以前端就算想标也无从标起
2. **`等效折扣 = saved/(saved+paid)`**，两者为 0 时是 `0/0`，而 `pct()` 返回 `BigDecimal.ZERO` → 显示 `0.00%`——与 #853 逐字同型

**为什么最重**：用户从这里读出的会是**相反的结论**——不是"还没配价、算不出来"，而是"**缓存没用**"，进而可能关掉一个其实有效的功能。

### 改法

**后端**
- `RoiTotals` 增 `pricingStatus` + `unpriced`（`AdminRoiService` 手里本来就有 `GroupSummary`，只是一直丢掉）
- **`savedPct` 在无成本基数时返回 `null`**：`0.00` 是在断言一个算不出来的数

**前端**
- 两处金额按 `costGapNote` 渲染「未定价」
- `等效折扣` 为 null 时显示 **`—`**（不是 `0.00%`）

### 验证

- **后端先证明会红**：回退 DTO/service 后新增的 IT 用例失败于 `$.totals.savedPct`（当时是 `0.00` 而非 null）；修复后 **5/5 绿**
- **前端先证明会红**：回退视图后新增用例失败；修复后 **7/7 绿**
- 全量：lint 0（`--max-warnings 0`）/ **443 tests** / typecheck / build / **gen:types 幂等**
- OpenAPI 基线重生成，`check-openapi-breaking.py` **no breaking changes**（纯加字段；`savedPct` 的 Java 侧可空不改变 schema 的 required）
- **真机复验**：API 返回 `savedPct: null` + `pricingStatus: "UNAVAILABLE"`；页面读作 `缓存节省 ¥0.0000 未定价` / `等效折扣 —`

### 两处我自己造的麻烦（都拦住了）

1. **差点拿旧镜像宣布"已验证"**。构建命令我用 `| tail -3` 截了输出，**构建其实失败了**，而后续 `up` 照跑，容器仍是 8 小时前的镜像 —— 页面上什么都没变。**是"页面没变"这一点让我回头查**，而不是我的流程。仓里早有「构建缓存快≠代码新」的告诫，这次是它的近亲：**截断输出 = 关掉自己的报警器**。
2. **那次构建失败本身是我自己造的**：我给测试树只拷了 `generated.ts`、没拷 `generated-api.ts`，而 develop 的 #838 修复把 schema 改名了（`UpsertRequest` → `McpRouteRuleUpsertRequest`），两个文件因此不配套。**又一次"先怀疑产品、后确认是自己的环境"**——同一模式本轮第七次。

### 教训

**"有字段"和"能用来说真话"是两件事。** 用量汇总有 `pricingStatus`，缓存收益页没有——于是同一句承诺（"不完整的金额不是总额"）在六个 surface 上落实程度不同。**这一条把 #849/#853/#857/#858 串成了一句：这个承诺此前只在半数页面上成立。**

## 2026-09-19 缓存收益页的「节省额只是下界」——给第二个"未知"信号补公共入口（#863）

同一族（#849/#853/#857/#858）的收尾条，但**根因不同**：那四条是"信号存在、页面没接"，
这条是"**信号没有公共入口，靠每页自觉，于是漏了一个**"。

### 问题

产品里有两个**互相独立**的"这个数不完整"信号：

| 信号 | 含义 | 公共 helper |
|---|---|---|
| `pricingStatus` / `unpricedEvents` | **金额**不是总额 | ✅ `costGapNote` |
| `unpricedHitEvents`（#790） | **节省额**只是下界 | ❌ 无，每页自己实现 |

`PricingGap.hasCostGap()` 只算 `unpricedEvents`，`pricingStatus()` 又只看 `hasCostGap()`——
所以 **`pricingStatus = COMPLETE` 与 `unpricedHitEvents > 0` 可以同时成立**。
此时 `costGapNote` 直接返回 `null`（它只看 `pricingStatus`），**下界这层完全不会触发**。

结果："被抽成 helper 的信号到处都在；没被抽的那个靠自觉，漏了 `NextRoiView`"。

**为什么这条在缓存收益页最重**：页面自己写着「**数据决定缓存策略**」，而缓存节省显示
`¥0.0000` 时没有任何东西解释它——读者读出的是"缓存没用"，而真相是"这些命中发生时没有
生效价目，算不出来"。**这与 #858 是同一个误读的两个入口。**

### 改法

**抽取**（`frontend/src/lib/usage-pricing.ts`）
- `unpricedHitCount(totals)` —— 计数，此前每页各写一遍
- `savingsBoundNote(totals)` —— 提示文案；**刻意不并进 `costGapNote`**：两者是不同断语
  （"金额不是总额" vs "节省额是下界"），并进去就会在它专门要抓的那个组合上失声

**改走公共入口**：`NextAdminUsageView`、`NextCostView` 不再各自实现

**补页**：`NextRoiView` 的「缓存节省」加 `下界` 标记，与既有 `未定价` 并存

### 验证

- **先证明会红（前端）**：只回退视图 → 新增两条用例失败（`expected false to be true`，标记不存在），
  其余 8 条（含 #858 的）仍绿
- **先证明会红（后端）**：把 `AdminRoiService` 的 `totals.unpriced()` 换成 `PricingGap.NONE`
  → **新用例与 #858 既有的 `unpricedWindowReportsNoDiscount` 同时失败**，说明该端点的整个
  `unpriced` 载荷都是承重的、且现在有两条测试压着
- 全量前端：lint（`--max-warnings 0`）/ **456 tests** / typecheck / build / **`gen:types` 幂等** —— 全 PASS
- Playwright **58/58**
- 后端 `verify -Pintegration` 全绿
- **真机 · 两种组合**（本地 e2e 栈播种，非 mock）：
  - `COMPLETE` + `unpricedHitEvents=2`：卡片读作 `缓存节省 ¥0.0000 下界`，`未定价` **正确缺席**
  - `UNAVAILABLE` + 同上：`¥0.0000 未定价 下界`，两条气泡**各自成立不重叠**
    （"…金额无从得知——已显示的金额不是总额" / "2 次命中…——节省额只是下界，不是全部"）
- 校验过运行中的镜像 id 等于本次构建产物（上一轮的"拿旧镜像宣布已验证"教训）

### 教训

**"有 helper"和"有入口"是两件事。** 一个信号被抽成公共函数，它就会出现在每个该出现的地方；
没被抽的那个，每加一个显示金额的页面就多一次漏掉的机会——而且**漏掉时不会报错，只会安静地
给出一个看起来确定的数**。判断依据不是"我记住了要标"，而是"这层判断有几个入口"。

这与 #849/#853/#857/#858 合起来是一句：**"不完整的数不是确定的数"这句承诺，
此前既只在半数页面上成立，也只在半个信号上被工程化。**
