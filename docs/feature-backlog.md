# 功能登记表（Feature Backlog）

> **用途**：跨文档功能清单的总登记与总状态表——凡文档（设计包、两家大厂研究、roadmap/蓝图、ADR、规格、runbook）提及且**尚未交付**的功能/能力/待办，在此登记；实现状态以 `docs/progress.md` 为准，本表只管「未做与候选」。所有条目给出来源文档、清晰度、前置条件与处理方式：清晰可直接立项 → 不清晰留**架子**（登记想法与依赖，内容条件具备后再填）。
>
> 口径图例：`PLANNED`=已立项可做 | `SCAFFOLD`=清晰度不足，先留架子 | `BLOCKED`=外部阻塞（等 leader/平台/凭证） | `ADR`=需决策反转/新 ADR 方可做 | `DECLINED`=刻意不做（红线，防误入） | `DEFERRED`=明确远期（无立即价值） | `DONE`=已交付（交付记录见 docs/progress.md） | `TBD`=待核对代码/出处
>
> 本表登记于 2026-09-02（G6.5→MCP ACL 六连交付后全景盘点），来源跨 9 文档族。

## A 组 · 数据面与治理闭环（清晰待做）

| ID | 功能 | 出处 | 清晰度 | 状态 | 前置/依赖 | 架子与要点 |
|---|---|---|---|---|---|---|
| F01 | MCP 调用代理接线（McpAccessPolicy 把关 + `/mcpservers/{name}/mcp` 路由形态） | progress P3.5/ACL 边界；study C | 清晰（机制明确，路由形态有一候选） | PLANNED | 需定 MCP 传输实现（SSE/Streamable HTTP 客户端）、调用鉴权接线 | 判定策略已就绪；接入时同步落 MCP 元数据日志（F15） |
| F02 | 缓存键升级为「提取最后一条 user 消息」（对齐腾讯/阿里） | ADR-0009 后果；mapping 启示 1 | 清晰（两家一致做法） | DONE（2026-09-03 盘点核对） | 需回归缓存契约测试（字节一致仍须成立） | **盘点修正**：盘点时登记 PLANNED 有误——代码 commit 3086187（G7.4 时代）早已实现：键 = tenant/Key 作用域 + **system+最后一条 user 消息**语义键（CacheKeyFactory.semanticScope，OpenAI chat/Responses/Anthropic 三形状 + content parts），非 chat 形状回退全请求归一化 hash；正文解析仅限 opt-in 缓存流且只用于键派生、转发仍原样字节。本会话补**端到端契约场景 ×2**（VirtualKeyAuthContractTest$L1Caching：不同历史同末条消息命中、不同末条消息 miss） |
| F03 | 模型审批 Webhook 通知（文档「预留」） | progress 审批流边界 | 清晰（复用 alert/webhook 机制） | DONE（2026-09-03） | 无 | 交付见 progress「模型审批 Webhook 通知」：事件驱动规则类型 ×3（提交/通过/驳回，V27）+ 审批迁移瞬间即时投递（共享 AlertEventDispatcher）+ 前端类型选项；不做阻断 |
| F04 | 用户自助配额可见性（个人看自己的配额规则水位） | progress 配额规则边界 | 清晰 | DONE（2026-09-03） | 无 | 交付见 progress「用户自助配额可见性」：`GET /api/v1/me/quota-rules`（只读本人 USER 规则 + 水位）+ 用量页「我的配额」面板 |
| F05 | 管理门户 IP 白名单 | security §6；middleware 安全边界 | 清晰 | DONE（2026-09-03） | 无 | 交付见 progress「管理门户 IP 白名单」：admin-access 双配置（allowlist + trusted-proxies XFF 防伪造）、billing/bootstrap 豁免、IpCidrMatcher v4/v6；推理 API 来源 IP 限制为远期 F40 |
| F06 | 过期导出/删除请求定时清理（GC） | progress G4.4 边界 | 清晰 | DONE（2026-09-03） | 无 | 交付见 progress「过期记录定时 GC」：@Scheduled 回收 SUCCEEDED 过窗导出（file_bytes 释放）与过期删除请求；EXECUTED/审计永久保留（G4.4 语义） |
| F07 | 告警类型补齐（usage 队列饱和/解析失败/供应商错误/Plan 同步/磁盘等 → alert_rules 类型） | progress G4.5 风险；release-checklist §6.1 | 部分（多数类型定义清晰，数据源需接线） | SCAFFOLD | 各类型数据源接线 | 现有框架支持新增类型；先登记类型清单与数据源，逐类接线 |
| F08 | 官方价格 24h 自动同步（source=OFFICIAL 自动化） | progress G7.2 风险 | 部分（依赖供应商官方价格源） | SCAFFOLD | 供应商价格源确认 | source=OFFICIAL 现为人工标记；无源则保持人工 |
| F09 | OpenAPI 3.1 生成 + CI 破坏性变更检查 | api-contract §8；release-checklist §0 | 清晰（规格已写死） | DONE（2026-09-03） | 无（发布前补项，本会话完成） | 交付见 progress「OpenAPI 3.1 生成」：springdoc `/v3/api-docs`（3.1.0）+ 鉴权 scheme 建模 + 基线 docs/openapi/openapi-3.1.json + CI 破坏性 diff（deploy/openapi/check-openapi-breaking.py）；**前端 TS client codegen（document-map §3 愿景）未纳入**——手写 api/types 继续维护，codegen 迁移列为发布前候选 |
| F10 | 网关部署信息页核对与补齐 | mapping 表行 8 | 清晰（核对完成） | **DONE（2026-09-05 核对）** | 无 | 核对：NextSettingsView 含部署信息段（网关/控制面版本与仓库信息页）；无独立补齐缺口 |

## B 组 · MCP 运行时护栏（腾讯 A 类研究建议，方向明确）

| ID | 功能 | 出处 | 清晰度 | 状态 | 前置/依赖 | 架子与要点 |
|---|---|---|---|---|---|---|
| F11 | MCP 路由规则（default 兜底不可改删 + 自定义高优先级；Path/Host/Method/Header 匹配；冲突实时校验；不读正文） | study A4（raw 10） | 清晰 | **DONE（2026-09-04，配置面）** | F01 代理接线后才有调用面（数据面待接线） | 交付见 CHANGELOG 2026-09-04：V28 规则表（default 随服务创建+存量回填）+ domain 纯函数 `McpRouteRules`（RE2 全匹配/冲突等价面）+ 管理 API §5.23 + 前端「路由规则」抽屉；数据面按优先级匹配待 F01 接线（F44 路由级指标同挂）——**2026-09-05 裁决：DEFERRED**：单固定入口 + default 恒兜底下无差异化分发承载（raw 10 的多 Host/多租户/灰度场景属多入口形态），McpRouteRules 纯函数与 V28 配置面已备，形态出现（多域名入口/私有化多租户）再接 |
| F12 | Tools 重试门禁（非幂等显式确认；首字节前才重试；默认关闭） | study A6（raw 12） | 清晰 | DONE（2026-09-05，V30+数据面，PR #→） | F01 | 语义与网关「首字节前一次重试」同源：5xx/连接失败/超时条件可选，POST/PUT/PATCH 工具需 `idempotencyConfirmed` 才重试 |
| F13 | Tools 熔断（三态；最小请求数防误判；慢调用阈值 < 后端超时校验；熔断期跳过重试；429 可触发） | study A7（raw 13） | 清晰 | DONE（2026-09-05，V30+数据面，PR #→） | F01 | 状态机纯函数 + 默认关闭；慢阈值校验对照服务 check_timeout_seconds；429 需显式加入触发状态码；熔断桶按工具名/方法名隔离 |
| F14 | Tools 分组（引用不复制；组内唯一；AutoPrefix 冲突处理；单组默认 10 个控 Token） | study A9（raw 17） | 清晰 | **DEFERRED（2026-09-05 裁决）** | F01 | raw 17 的组级暴露面=HTTP-to-MCP 直连端点（组名入路径）；本系统标准 MCP 信封单一入口无承载；形态出现（HTTP-to-MCP/Agent 组直连）再立项 |
| F15 | MCP 纯元数据访问日志（`aigw.mcp.*` 固定前缀，不存正文） | study A8（raw 16） | 清晰 | DONE（2026-09-05，V29+网关 writer+查询 API，PR #→） | F01 | 随代理接线落：网关异步批量写 `mcp_access_log`（幂等/饱和 drop+计数），管理端 `GET /api/v1/admin/mcp-access-logs`；401/404 无可信身份不落行 |
| F16 | Tools 版本管理（配置快照与运行分离；语义版本化；生效版永不裁剪；幂等回滚） | study A3（raw 11） | 部分（语义清晰、落点需定） | SCAFFOLD | 无（可独立于 F01） | 架子：工具定义加版本列 + 快照表设计待细化 |
| F17 | Tools OpenAPI 批量导入 | progress P3.5 边界 | 部分（导入格式依赖工具 OpenAPI 结构） | SCAFFOLD | 无 | 解析器契约先立（vendor 扩展字段未知） |
| F18 | 模型探测失败的手动录入兜底（模型目录人工维护入口） | study A5 | 清晰 | SCAFFOLD | 模型目录现仅成功抓取写入 | 手动录入需保留「目录来源」标记防与抓取冲突 |

## C 组 · 财务与统计深化（清晰度分层）

| ID | 功能 | 出处 | 清晰度 | 状态 | 前置/依赖 | 架子与要点 |
|---|---|---|---|---|---|---|
| F19 | 官方账单导入 + 自动差异报告（四级匹配：request ID → 指纹+模型+时间 → Token/费用 → 时间窗） | usage-accounting §11；roadmap 后续 | 部分（匹配规则文档清晰；账单格式依赖真实样本） | SCAFFOLD | 真实账单样本（任一供应商） | api-contract §6 规格已超前：异步任务 + MATCHED/PARTIAL/UNMATCHED_LOCAL/UNMATCHED_PROVIDER；导入器契约可先行定义，供应商解析器待样本 |
| F20 | 用量差异「追加 adjustment」机制（不覆盖原始事实） | operations-runbook §7 | 清晰（追加语义明确） | SCAFFOLD | F19 差异产生后闭环需要 | 架子：adjustment 表结构（追加行 + 原因 + 引用原始行）待建；release-checklist 的 adjustment schema 门禁随之可勾 |
| F21 | usage_event 延后列批量落地（team/subscription/名称指纹快照/error_category/token authority/provider_usage_json/price 快照/成本列/plan_window_ref/usage_integrity） | database-schema §6 | 部分（列清单明确；写路径与成本语义需定） | SCAFFOLD | 与 F19/成本重算语义绑定 | 架子：列清单已登记；逐事件价格快照解决「价格变更重算历史」语义风险 |
| F22 | 成本分摊 USER 维度（target_type=USER 预留） | progress G4.3 边界 | 清晰 | DEFERRED | 无 | 表唯一键已支持；需按人聚合的产品决策 |
| F23 | 导出与文档「可对账等级」标记落地 | usage-accounting §11 | 部分（规格承诺） | SCAFFOLD | F19 | 导出列加 reconcile-level（provider_request_id 有无） |

## D 组 · 默认模板与消费者（研究建议方向）

| ID | 功能 | 出处 | 清晰度 | 状态 | 前置/依赖 | 架子与要点 |
|---|---|---|---|---|---|---|
| F24 | 默认配额模板（全局模板；创建时快照复制；改模板不惊动存量；关闭不删已分配；手动规则覆盖默认） | study A10（raw 22）；roadmap 配额 | 清晰（语义完整） | DONE（2026-09-03） | 无 | 交付见 progress「默认配额模板」：V26 每租户单行模板 + 新建用户自动快照复制（USER 作用域规则）+ 前端配额页面板；映射取舍：复制只落新用户（腾讯「消费者」≈本系统用户），PROJECT 不参与模板化；预算模板化列为后续候选 |
| F25 | 消费者组（组级启停一键熔断 / 删除依赖检查 / 改属性与管成员解耦） | study A11（raw 21） | 部分（组实体建模需定） | SCAFFOLD | MCP ACL 消费者组批量授权（腾讯维度） | 架子：consumer_group 表 + 成员表 + 组级 status；部分能力可被消费者级吊销替代 |
| F26 | 计费查询开放维度扩展（按消费者等） | middleware 修正路线 | 部分（维度选择等 leader） | SCAFFOLD | 开放维度决策 | 现 summary/records/quota 已平台化；维度扩接消费者分组 |

## E 组 · Agent / 服务 / 外部平台（需外部细节 → 架子为主）

| ID | 功能 | 出处 | 清晰度 | 状态 | 前置/依赖 | 架子与要点 |
|---|---|---|---|---|---|---|
| F27 | Agent 对外入口认证 + 按 Agent 观测补齐 | middleware 修正路线；阿里 Agent 拓扑 | 部分（认证机制候选多） | SCAFFOLD | 入口形态决策（域名/消费者认证/Key） | 架子：注册/禁用/用量已交付；入口路由与观测维度登记待细化 |
| F28 | Agent 引用「绑定后禁改禁删」+ Skill 快照语义 | study A2 | 部分 | SCAFFOLD | 与 F27 一起 | 凭证引用已绑定级联；「不可变标识」语义登记 |
| F29 | 服务来源分层 / 服务→网关实际路由接线 | middleware；P3.2 边界 | 部分 | BLOCKED | leader 集成细节 | 注册表已交付（HTTP/MCP/OTHER 目录） |
| F30 | 全局配置应用侧热更新接线 | middleware；P3.3 边界 | 部分 | BLOCKED | 集成细节 | 配置目录已交付；消费侧热更新待定 |
| F31 | SkillHub 公司内 skill 来源接入 | middleware 待办 | 部分 | BLOCKED | 公司 skill 存放/格式 | 上传/目录/下载已按 Agent Skills 实现 |
| F32 | 平台用户同步（电话/userid 注册 → 网关账号） | middleware P0；报备需求 | 部分 | BLOCKED | 平台注册字段/形态（leader） | 需 ADR（触碰本地 Argon2 体系）；JWT sub→平台 user_id 叠加为 ADR-0011 后果。**自助注册（F-REG，2026-09-03）已交付**（`/auth/register` + 开关），平台级映射/同步仍等 leader |
| F33 | 平台 OAuth 确权（OAuth/OIDC 访问受保护资源） | middleware P0 | 部分 | BLOCKED | OAuth 形态决策 | JWT 通道（ADR-0011）为前置形态 |
| F34 | Kafka 引入（事件管道演进） | leader×平台沟通（2026-09）；ADR-0014 | 清晰（场景=内容留痕事件流，见 ADR-0014） | BLOCKED | ADR-0014 Accepted + 拓扑拍板 | 场景已细化：请求内容合规留痕事件（按用户可追溯/加密/冷数据）经 Kafka 投递、平台消费端多进程持久化；落地需 ADR-0012/0014 Accepted |

## F 组 · 基础设施与远期（多数 DEFERRED/SCAFFOLD）

| ID | 功能 | 出处 | 清晰度 | 状态 | 前置/依赖 | 架子与要点 |
|---|---|---|---|---|---|---|
| F35 | usage 队列饱和切换同步写入（应急模式） | architecture §5 | 清晰 | DONE（2026-09-03） | 无 | 交付见 progress「usage 队列饱和应急直写」：`saturation-mode=WRITE_THROUGH`（默认 DROP 行为不变）+ `write-through-timeout`（默认 5s）；单事件经 writer 执行器幂等直写并有界等待，JDBC 仍只在 writer 执行器 |
| F36 | 阿里云 Higress 文档体系系统对照研究 | progress 候选 4 | —（研究型） | DEFERRED | 用户定方向 | 腾讯 30 篇已入库；阿里对照补全后更新本表 |
| F37 | 多实例/K8s 演进（Helm、NOTIFY 放大语义、副本） | roadmap；deployment §10；ADR-0005 | 部分 | DEFERRED | 部署形态决策 | 无状态化/Secret 外注已预留；Redis 经 SPI 引入点为 ADR-0005 |
| F38 | OIDC/LDAP 认证、云 KMS、OpenTelemetry、细粒度项目角色 | roadmap 后续版本 | 部分 | DEFERRED | 各自形态决策 | KeyEncryptionProvider 已留 KMS 锚点 |
| F39 | 前端按需引入/手动分块 | progress TDesign 风险 | 清晰 | DEFERRED | 无 | 主 chunk ~1.4MB 非阻塞优化 |
| F40 | 推理 API 来源 IP 限制（可选策略） | security §6 | 清晰 | DEFERRED | 默认不限制以免影响远程开发 | 与 F05 管理门户白名单分开登记 |
| F41 | 语义缓存 L2（向量库） | ADR-0009 后果；study B | — | ADR | 新 ADR（向量库依赖） | 接口预留；维持禁用 |
| F42 | 分布式会话缓存（Redis+TTL 1800s） | study C | — | ADR/DEFERRED | 违背 ADR-0005 | 多实例场景候选 |
| F43 | 用量保留轮转策略（MANUAL_ONLY 外的模式） | configuration-reference §6 | — | DEFERRED | 产品决策 | 现枚举名预留 |
| F44 | Prometheus 按 Route 粒度开关采集 | study C | 清晰 | SCAFFOLD | 路由实体（F11）后 | 现租户级无标签指标；防热路径开销 |
| F45 | 凭证类审计差异化保留期 | study A13 | 清晰 | SCAFFOLD | 审计策略决策 | 现审计永久保留 |

## G 组 · 决策候选（需 ADR / 刻意不做——登记防误入）

| ID | 功能 | 出处 | 状态 | 说明 |
|---|---|---|---|---|
| F46 | 智能路由/自动选模型；降级 Fallback；跨供应商故障切换 | comparison；roadmap 明确不做；CLAUDE.md §2/§7 | DECLINED | 红线：不负载均衡/不跨供应商/不读正文 |
| F47 | QPM/Token 限流（用户级） | comparison | DECLINED | 锁定「不限流」；只告警 + 容量 503 |
| F48 | 协议转换（HTTP↔Anthropic/OpenAI/MCP） | comparison | DECLINED | CC Switch 职责 |
| F49 | 参数改写/流量镜像/数据脱敏转发/日志包体采集 | study B（06/07/08/26） | ADR | 与「不读正文/不改写」冲突；合规需求出现时再评估（B 类四件套登记为反面清单） |
| F50 | Virtual Key 多服务绑定/Header 分流路由 | mapping 表行 11 | ADR | 与「1:1 固定绑定、不负载均衡」冲突 |
| F51 | 配额硬阻断（超限拒绝） | middleware 配额 | ADR | 需反转「不因预算阻断」；现有规则/水位/预警闭环 |
| F52 | Credits 归一化度量（阿里 FinOps） | middleware 配额 | ADR/评估 | 先单价后预算；涉硬拦截需 ADR |

## H 组 · 实测与收尾（非功能）

| ID | 条目 | 出处 | 状态 | 说明 |
|---|---|---|---|---|
| F53 | 真实供应商凭证实测矩阵（23 产品含团队 Plan 共享池/Anthropic 入口 Bearer 兼容/Agent Plan 端点等细分） | progress G3.x | BLOCKED | WAITING_FOR_CREDENTIAL；逐产品登记于各 Goal 记录 |
| F54 | 长期浸泡验收（并发 20×30min、队列 drop=0、p99≤2×） | runbook 性能 | BLOCKED | 需部署环境；本机 50 并发 PASS |
| F55 | Trivy 测试镜像 digest 固定 | progress CI 待办 | **DONE（2026-09-05 盘点核对）** | 全部 testcontainers PG 镜像与 CI Trivy 均 digest 固定（postgres:17.6-alpine@sha256:18cfe3…，见 AbstractControlPlaneIntegrationTest/Gateway 各集成测试/ci.yml）——TBD 系当时网络受限误记 |
| F56 | spec §9 人工视觉审查 | progress G5.5 | TBD | 需人工执行 |
| F57 | 版本号与 tag（0.1.0-SNAPSHOT） | progress G6.5 | BLOCKED | 待所有者授权 |
| F58 | release-checklist 未勾门禁复核（§1/§4/§6 相关项随 F19/F20 落地） | release-checklist | DEFERRED | 逐项由对应功能闭合 |
| F59 | 请求内容合规留痕管道（加密冷存/按用户追溯/OAuth uid 映射骨架） | leader×平台沟通；ADR-0014 | 清晰（2026-09-05 Accepted，P1-P8 v3 默认裁决） | **IN_PROGRESS（2026-09-06 R1 配置面完成：V31 retention_config+user_identity_link+管理开关 API；R2 网关密文采集/信封 → R3 Kafka producer → R4 消费端参考实现待续）** | 无（红线已获所有者显式放行，见 ADR-0014 §1） | 见 ADR-0014：网关旁路密文信封→Kafka→消费端持久化（文件/S3/DB 候选）；「不存正文」例外通道=默认关 |

## 文档一致性缺口（盘点发现，登记待修）

| ID | 缺口 | 说明 |
|---|---|---|
| D01 | ADR-0008 文件缺失（architecture/mapping 引用「缓存默认关闭/coalescer 默认关闭」） | decisions/ 仅有 0001-0007、0009-0011；decisions/README 停在 0007 未列 0009-0011——需补 0008 或修正引用 |
| D02 | future-kafka-and-reporting-users.md 已被引用但不存在 | middleware 关联段引用；内容已并入本表 F32/F34 |
| D03 | 「429 信号量」出处待核对（architecture 风险清单提到的 P1 实测项在原始设计包，仓库文档无信号量表述） | 若实现需回到原始设计包核证 |
| D04 | 生产启动校验「开启响应缓存拒绝」表述与 ADR-0009 opt-in 并存需复核 | configuration-reference §10 |
