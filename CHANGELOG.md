# Changelog

MiQroKey Gateway — 内部凭证治理网关。所有改动按 Goal 汇总；版本号语义化（MAJOR.MINOR.PATCH）。

## [Unreleased] — 截至 2026-09-03（发布候选基线）

### 2026-09-06
- **ADR-0012/0014 Accepted + 留痕 R1 配置面（V31）**：Kafka 引入与内容留痕管道获所有者批准（v3 默认值，P1–P8 已裁决）。V31 新增 `retention_config`（租户级默认关开关，快照下发即时生效）与 `user_identity_link`（平台 OAuth 映射骨架）；管理 API `GET/PUT /api/v1/admin/retention-config`（审计 + 即时刷新）。网关密文采集/信封、Kafka producer、消费端参考实现为后续批次。

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
