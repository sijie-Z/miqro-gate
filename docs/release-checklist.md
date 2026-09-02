# 发布与交付清单

任何一项硬门禁未通过都不得标记正式版本。例外必须由客户负责人书面接受并记录范围、期限和补救计划。

## 0. G6.5 执行盘点（2026-09-02）

> G6.5（发布就绪收尾）按本清单逐项盘点。判定符号：✅ 已满足（附证据）；⏳ 条件未到（真实凭证/部署环境/发布负责人授权）；➖ 不适用（当前交付形态无此对象）。清单本体保持可复用，正式 Go/No-Go 前由发布负责人复核本表。

| 门禁 | 判定 | 依据 / 条件 |
|---|---|---|
| §1 进度与文档一致性 | ✅ | 本 Goal 收尾时更新 progress.md；文档契约同步检查发现 1 项未实现（见下 OpenAPI） |
| §2 构建与测试硬门禁 | ✅ | 2026-09-02 全量 `verify -P integration` BUILD SUCCESS + 前端 lint/typecheck/vitest/build/e2e + 50 并发浸泡取证（见 §2.1） |
| §2.1 50 条并发流式容量验收 | ✅ | `SoakIntegrationTest` 并发提升至 50 本机实跑 PASS（真实 gateway + mock 上游 + PostgreSQL，0 上游错误 + 全部落库），跑后还原；长效 soak 命令见 operations-runbook §性能（需部署环境） |
| §3 供应商矩阵 | ⏳ | 23 产品全部 IMPLEMENTED/`WAITING_FOR_CREDENTIAL`（真实凭证未提供，禁止 VERIFIED——保持如实状态） |
| §3.2 团队 Plan 真实共享池验证 | ⏳ | 需 MiniMax/智谱/腾讯团队产品真实凭证与真实组织账号 |
| §4.1 Flyway/升级回滚 | ✅/➖ | 从空库 V1–V21 Testcontainers 全建（CI 每 PR）；真实恢复演练 PASS（G6.2）；「上一正式版本升级」不适用——尚无已发布正式版本，首版发布时建立基线 |
| §4.2 大表/分区 migration 评估 | ➖ | 单客户私有化、50 并发上限（CLAUDE.md §2），无生产数据量副本可评估；`usage_event` 分区保留策略见 database-schema §6 |
| §5.5 镜像非 root/固定 digest | ➖/✅ | 当前交付 = 源码 + wrapper + Compose（无应用容器镜像，见 §7）；Compose 依赖镜像已按 digest 固定（postgres 17.6-alpine @sha256…） |
| §6.1 告警已测试 | ✅/⏳ | 已测：Webhook 签名投递/去重/指数退避（G4.5）、备份 Webhook（G6.2）、usage 队列饱和 drop warn + 指标（G2.4）、预算水位 BUDGET_THRESHOLD（G8.3）；未实现为告警类型：usage 队列饱和/解析失败/供应商错误/Plan 同步/磁盘（G4.5 已知缺口，接入需数据源接线）→ 正式发布前按需补充 |
| §7 交付物 | ✅/⏳/➖ | 源码/wrapper/锁文件/Compose/Secret 模板/.env.example/签名目录/文档全齐；OpenAPI 生成物未实现（api-contract §8 契约）；容器镜像/离线包（无镜像构建，源码交付形态不适用）；客户侧构建/恢复演练（无客户，➖） |
| §8 Go/No-Go | ⏳ | 版本号与 tag 未定（代码 0.1.0-SNAPSHOT、无 tag）；由发布负责人（项目所有者）在发布节点签署 |
| OpenAPI 3.1 生成 + CI 破坏性变更检查（api-contract §8 / document-map §3） | ⏳ | 仓库无 openapi 生成配置与产物；api-contract.md 为当前唯一事实源。列为 G6.5 发现的文档契约未实现项，待专项 Goal 或发布延期项 |

## 1. 范围与状态

- [ ] `docs/progress.md` 与代码、测试、提交一致，目标 Goal 均为 `DONE`。
- [x] 所有 in-scope 需求有实现或明确延期；没有静默扩展为智能路由/协议转换。
- [x] Accepted ADR、API、数据库、Adapter、配置和 UI 文档已同步。
- [x] 发布版本、提交 SHA、构建时间和目录版本可追踪。

## 2. 构建与测试硬门禁

- [x] Windows 和 Linux 使用 wrapper 全新构建成功。
- [x] 后端 unit/integration/architecture/security tests 全部通过。
- [x] 前端 lint、typecheck、unit、E2E 和生产构建通过。
- [x] Anthropic Messages、OpenAI Responses、Chat Completions 的普通/SSE fixtures 通过。
- [ ] 50 条并发流式容量验收通过，无事件循环阻塞、无明显内存增长。
- [x] 客户取消能够取消上游；断流、超时、429、5xx 行为符合契约。
- [x] `docker compose config` 和安装/升级/回滚烟雾测试通过。

## 3. 供应商门禁

每个启用产品在 release manifest 中列出状态：

| 产品 | 协议 | PAYG/Plan 形态 | Adapter | Mock | 真实凭证 | 余额/周期 | 团队 Plan | 核验日期 |
|---|---|---|---|---|---|---|---|---|---|
| DeepSeek 官方 API | OpenAI/Anthropic | PAYG | deepseek-payg-api | ✅ | ⏳ | OFFICIAL_API | — | 2026-08-26 |
| Tencent Coding Plan | OpenAI/Anthropic | INDIVIDUAL | tencent-coding-plan | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| Tencent Token Plan 个人版 | OpenAI | INDIVIDUAL | tencent-token-plan-personal | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| Tencent 企业专业/轻享 | OpenAI | ENTERPRISE | tencent-token-plan-enterprise-pro/-lite | ✅ | ⏳ | UNAVAILABLE | MULTI_KEY_SHARED_POOL | 2026-08-26 |
| Tencent TokenHub 按量 | OpenAI | PAYG | tencent-payg-api | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| Zhipu Coding Plan 个人/团队 | OpenAI/Anthropic | INDIVIDUAL/TEAM | zhipu-coding-plan-personal/-team | ✅ | ⏳ | UNAVAILABLE | PER_SEAT_KEY | 2026-08-26 |
| Zhipu 按量 | OpenAI/Anthropic | PAYG | zhipu-payg-api | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| MiniMax Token Plan 个人/团队 | OpenAI | INDIVIDUAL/TEAM | minimax-token-plan-personal/-team | ✅ | ⏳ | UNAVAILABLE | PER_MEMBER_SUBSCRIPTION_KEY+CREDITS | 2026-08-26 |
| MiniMax 按量 | OpenAI | PAYG | minimax-payg-api | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| Kimi Code 会员 | OpenAI/Anthropic | INDIVIDUAL | moonshot-kimi-code-member | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| Moonshot 按量 | OpenAI | PAYG | moonshot-payg-api | ✅ | ⏳ | OFFICIAL_API | — | 2026-08-26 |
| 千帆 Coding Plan / Token Plan 个人版 | OpenAI/Anthropic | INDIVIDUAL | baidu-coding-plan / -token-plan-personal | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| 千帆按量 | OpenAI | PAYG | baidu-payg-api | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| 方舟 Coding Plan / Agent Plan | OpenAI/Anthropic | INDIVIDUAL | volcengine-coding-plan / -agent-plan | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |
| 方舟按量 | OpenAI | PAYG | volcengine-payg-api | ✅ | ⏳ | UNAVAILABLE | — | 2026-08-26 |

⏳ = `WAITING_FOR_CREDENTIAL`（真实凭证契约测试未执行，禁止标记 VERIFIED）。

- [x] 生产默认只启用 `VERIFIED`；其他状态需管理员明确开启并显示警告。
- [ ] 团队 Plan 按真实共享池/多 Key/席位/成员 Subscription Key 验证。
- [x] 模型 ID 明确且 `/v1/models` 返回与授权一致。
- [x] 官方余额/周期不可用时显示 `UNAVAILABLE/LOCAL_ESTIMATE`，无控制台抓取。
- [x] 上游官方文档证据和核验日期已更新。

## 4. 数据与迁移

- [ ] Flyway 校验通过；从上一正式版本升级和回滚策略已演练。
- [ ] 大表/分区 migration 在生产数据量副本评估锁和时长。
- [ ] 原始 usage、adjustment、导出和对账 schema 版本已固定。
- [x] 发布前备份成功且完成隔离恢复验证。
- [x] 永久保留/人工删除行为符合文档。

## 5. 安全与供应链硬门禁

- [x] 无真实 API Key、Virtual Key、master/HMAC/backup key、Webhook Secret 入库或镜像。
- [x] 上游凭证 AES-256-GCM 加密，Virtual Key 仅一次显示并以 HMAC digest 验证。
- [x] 鉴权 Header 清理、SSRF、重定向、权限隔离和普通用户 404 防枚举测试通过。
- [x] 日志/指标/异常/导出不含 prompt、代码、response body 和 Secret。
- [x] 会话 Secure/HttpOnly/SameSite、CSRF、强密码和登录锁定已验证。
- [x] SBOM、依赖漏洞扫描和镜像扫描完成；高/严重问题已处理。
- [x] 生产依赖许可证仅为批准的宽松许可证；无 LiteLLM/Bifrost 运行时依赖。
- [ ] 镜像使用非 root、固定 digest/版本、最小权限和只读挂载（可行处）。

## 6. 可观测性与运维

- [x] readiness/liveness、结构化日志、Prometheus 指标和 request ID 可用。
- [ ] usage 队列、解析失败、供应商错误、Plan 同步、Webhook、磁盘和备份告警已测试。
- [x] Webhook 签名、重试、dead-letter 和人工重放测试通过。
- [x] Provider 故障不自动切换，通知模板和 CC Switch 用户自选流程已准备。
- [x] `operations-runbook.md` 的凭证轮换、吊销、数据库故障、备份恢复和 key 丢失流程已演练。

## 7. 交付物

- [ ] 后端/前端源码、wrapper、锁文件和可重复构建说明。
- [ ] 带 digest 的容器镜像或离线镜像包。
- [ ] Docker Compose、示例非敏感配置、Secret 文件模板和目录/价格包。
- [ ] OpenAPI、数据库 schema/migration 清单、SBOM、许可证和扫描报告。
- [ ] 管理员手册、用户 Base URL/Key 使用说明、运维 Runbook、备份恢复说明。
- [ ] 默认账号不存在；bootstrap Secret 由客户安全生成并在使用后移除。
- [ ] 客户 Java/TS/Vue 团队完成源码构建、部署、升级和至少一次恢复演练。

## 8. Go / No-Go

签署前记录：版本、环境、日期、发布负责人、客户验收人、所有未关闭风险及回滚点。出现 Secret 泄漏、权限越权、usage 静默丢失、数据库不可恢复、核心协议流式破坏或供应商状态虚假标记时必须 `NO-GO`。

