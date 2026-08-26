# Changelog

MiQroKey Gateway — 内部凭证治理网关。所有改动按 Goal 汇总；版本号语义化（MAJOR.MINOR.PATCH）。

## [0.1.0] — 2026-08-26（首个候选版本，未标记 VERIFIED）

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

### Phase 3 — 供应商适配器（G3.1–G3.7，20 个产品，全部 IMPLEMENTED / WAITING_FOR_CREDENTIAL）

- DeepSeek PAYG（官方余额 OFFICIAL_API）
- Tencent TokenHub 5 产品（Coding Plan / Token Plan 个人版 / 企业专业 / 企业轻享 / 按量）
- Zhipu GLM 3 产品（个人/团队 Coding Plan / 按量，`PER_SEAT_KEY`）
- MiniMax 3 产品（个人/团队 Token Plan / 按量，`PER_MEMBER_SUBSCRIPTION_KEY` + 共享 Credits）
- Moonshot/Kimi 2 产品（Kimi Code 会员 / 按量，按量官方余额 OFFICIAL_API）
- Baidu Qianfan 3 产品（Coding Plan / Token Plan 个人版 / 按量）
- Volcengine Ark 3 产品（Coding Plan / Agent Plan / 按量）
- 共享基础设施：`TokenUsageParser`（双形状 + `prompt_tokens_details.cached_tokens`）、`TransparentResolve`、`HttpProviderClient`（SSRF 门控/超时/1MB 上限）、编译期注册

### Phase 4 — 控制面服务（G4.x）

- 用量统计/成本分摊（价格快照）、导出（gzip+SHA-256）、双确认删除
- Webhook 签名投递（指数退避、去重）、告警规则（usage 缺失率/上游错误率/余额不可用/用量激增）

### Phase 5 — 门户（G5.0–G5.5）

- Quiet Operations Console：tokens、应用 shell、PageHeader、mk-status、baseline 截图
- 用户门户（登录/改密/Key 全生命周期/个人用量）+ 管理门户（用户/团队/项目/Grant/产品/订阅/席位/导出/删除/Webhook/告警/审计）
- UI 安全与可访问性（管理路由守卫、no-store 防缓存、focus-visible、aria 标签）
- Playwright 生产构建 baseline（12 项）+ vite 冷启动根治

### Phase 6 — 交付（G6.1–G6.5）

- Observability：`monitoring`/`json` profiles、Prometheus 指标（低基数标签禁令）、Logstash JSON 日志、Grafana dashboard
- Backup & Restore：加密备份（AES-256-CBC+PBKDF2+manifest）、校验、恢复、真实恢复演练 PASS
- Supply-chain gate：Secret 扫描（修复 23 处文档示例 Key）、CycloneDX SBOM + 许可证门禁、Trivy 镜像扫描（驱动 postgres 镜像 digest 升级）
- Performance & soak：并发流浸泡测试 + 生产 soak 脚本
- 本版本：**未标记 VERIFIED**（无真实供应商凭证契约测试，`WAITING_FOR_CREDENTIAL`）
