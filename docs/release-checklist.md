# 发布与交付清单

任何一项硬门禁未通过都不得标记正式版本。例外必须由客户负责人书面接受并记录范围、期限和补救计划。

## 1. 范围与状态

- [ ] `docs/progress.md` 与代码、测试、提交一致，目标 Goal 均为 `DONE`。
- [ ] 所有 in-scope 需求有实现或明确延期；没有静默扩展为智能路由/协议转换。
- [ ] Accepted ADR、API、数据库、Adapter、配置和 UI 文档已同步。
- [ ] 发布版本、提交 SHA、构建时间和目录版本可追踪。

## 2. 构建与测试硬门禁

- [ ] Windows 和 Linux 使用 wrapper 全新构建成功。
- [ ] 后端 unit/integration/architecture/security tests 全部通过。
- [ ] 前端 lint、typecheck、unit、E2E 和生产构建通过。
- [ ] Anthropic Messages、OpenAI Responses、Chat Completions 的普通/SSE fixtures 通过。
- [ ] 50 条并发流式容量验收通过，无事件循环阻塞、无明显内存增长。
- [ ] 客户取消能够取消上游；断流、超时、429、5xx 行为符合契约。
- [ ] `docker compose config` 和安装/升级/回滚烟雾测试通过。

## 3. 供应商门禁

每个启用产品在 release manifest 中列出状态：

| 产品 | 协议 | PAYG/Plan 形态 | Adapter | Mock | 真实凭证 | 余额/周期 | 团队 Plan | 核验日期 |
|---|---|---|---|---|---|---|---|---|
| 待发布填充 |  |  |  |  |  |  |  |  |

- [ ] 生产默认只启用 `VERIFIED`；其他状态需管理员明确开启并显示警告。
- [ ] 团队 Plan 按真实共享池/多 Key/席位/成员 Subscription Key 验证。
- [ ] 模型 ID 明确且 `/v1/models` 返回与授权一致。
- [ ] 官方余额/周期不可用时显示 `UNAVAILABLE/LOCAL_ESTIMATE`，无控制台抓取。
- [ ] 上游官方文档证据和核验日期已更新。

## 4. 数据与迁移

- [ ] Flyway 校验通过；从上一正式版本升级和回滚策略已演练。
- [ ] 大表/分区 migration 在生产数据量副本评估锁和时长。
- [ ] 原始 usage、adjustment、导出和对账 schema 版本已固定。
- [ ] 发布前备份成功且完成隔离恢复验证。
- [ ] 永久保留/人工删除行为符合文档。

## 5. 安全与供应链硬门禁

- [ ] 无真实 API Key、Virtual Key、master/HMAC/backup key、Webhook Secret 入库或镜像。
- [ ] 上游凭证 AES-256-GCM 加密，Virtual Key 仅一次显示并以 HMAC digest 验证。
- [ ] 鉴权 Header 清理、SSRF、重定向、权限隔离和普通用户 404 防枚举测试通过。
- [ ] 日志/指标/异常/导出不含 prompt、代码、response body 和 Secret。
- [ ] 会话 Secure/HttpOnly/SameSite、CSRF、强密码和登录锁定已验证。
- [ ] SBOM、依赖漏洞扫描和镜像扫描完成；高/严重问题已处理。
- [ ] 生产依赖许可证仅为批准的宽松许可证；无 LiteLLM/Bifrost 运行时依赖。
- [ ] 镜像使用非 root、固定 digest/版本、最小权限和只读挂载（可行处）。

## 6. 可观测性与运维

- [ ] readiness/liveness、结构化日志、Prometheus 指标和 request ID 可用。
- [ ] usage 队列、解析失败、供应商错误、Plan 同步、Webhook、磁盘和备份告警已测试。
- [ ] Webhook 签名、重试、dead-letter 和人工重放测试通过。
- [ ] Provider 故障不自动切换，通知模板和 CC Switch 用户自选流程已准备。
- [ ] `operations-runbook.md` 的凭证轮换、吊销、数据库故障、备份恢复和 key 丢失流程已演练。

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

