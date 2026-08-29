# MiQroGate

MiQroEra 面向企业内部 AI 编程场景的凭证虚拟化与用量治理系统，产品简称 **MiQroGate**。

系统把一个真实上游 API Key 安全地映射为多个可独立追踪、轮换和吊销的 Virtual Key。内部用户在门户中自助创建 Virtual Key，只需把系统展示的 `Base URL` 与 `Virtual Key` 填入 CC Switch，再由 CC Switch 配置 Claude Code、Claude Desktop、Codex 等客户端。

本项目不是智能路由平台，也不是模型聚合中转站。每个 Virtual Key 固定绑定一个项目、一个供应商产品和一个真实凭证；Gateway 保持上游协议透明，仅负责鉴权替换、确定性转发、用量解析、审计与统计。跨协议转换和模型映射由 CC Switch 完成。

## 开发责任边界

本仓库的需求和设计基线已由规划阶段完成，后续默认由 **Claude Code + CC Switch 中配置的 DS V4-Pro** 逐 Goal 实施。当前规格作者不被假定继续编码。Claude Code 必须自行读取规格、实现、测试、更新文档、commit 并 push Goal 分支；项目所有者负责审查、merge 和发布。详见[Claude Code 实施交接契约](docs/claude-code-execution-contract.md)。

## 已确认的核心边界

- 单客户、私有化部署、客户自行运维。
- 首版按 50 个账号和最多 50 条并发流式请求设计。
- Java 21、Spring Boot 3、Spring WebFlux、Vue 3、TypeScript、PostgreSQL。
- Docker Compose 为首版标准交付方式；支持 Windows 开发和 Linux 部署。
- 系统管理员管理一切；普通用户只管理自己的 Virtual Key 和个人用量。
- 普通用户在已授权范围内免审批自助创建 Virtual Key。
- 不限流、不因预算阻断请求；通过 Webhook 告警。
- 不保存提示词、代码和模型回答正文。
- 原始用量流水永久保留，直到管理员手动删除。
- 第一版不做模型响应缓存，但预留缓存扩展接口，并完整保留供应商 Prompt Cache 语义。
- 不依赖 LiteLLM 或 Bifrost 运行时；Bifrost 只作为协议行为和测试参考。
- 生产依赖仅允许 Apache-2.0、MIT、BSD 等宽松许可证。

## 文档导航

- [Claude Code 项目指令](CLAUDE.md)
- [Claude Code 实施交接契约](docs/claude-code-execution-contract.md)
- [Claude Code 无压缩续接策略](docs/claude-code-context-strategy.md)
- [参与开发](CONTRIBUTING.md)
- [文档地图与事实来源](docs/document-map.md)
- [可执行 Goal 实现计划](docs/implementation-plan.md)
- [当前开发进度](docs/progress.md)
- [Claude Code / Goal 开发工作流](docs/development-workflow.md)
- [Git、Commit 与 Push 工作流](docs/git-workflow.md)
- [产品需求](docs/product-requirements.md)
- [系统架构](docs/architecture.md)
- [领域模型](docs/domain-model.md)
- [代码规范](docs/coding-standards.md)
- [数据库 Schema](docs/database-schema.md)
- [管理与推理 API 契约](docs/api-contract.md)
- [透明代理与 CC Switch 兼容](docs/proxy-and-cc-switch.md)
- [供应商与 Plan 适配](docs/provider-catalog.md)
- [Provider Adapter 契约](docs/provider-adapter-contract.md)
- [Virtual Key 生命周期](docs/virtual-key-lifecycle.md)
- [用量、成本与导出](docs/usage-accounting.md)
- [安全设计](docs/security.md)
- [门户与管理 API](docs/portal-and-api.md)
- [门户 UI 规格](docs/ui-specification.md)
- [前端视觉设计规范](docs/frontend-design.md)
- [配置参考](docs/configuration-reference.md)
- [部署与运维](docs/deployment-and-operations.md)
- [运维 Runbook](docs/operations-runbook.md)
- [测试与验收](docs/testing-and-acceptance.md)
- [协议测试夹具规范](docs/test-fixtures.md)
- [开发路线图](docs/roadmap.md)
- [发布与交付清单](docs/release-checklist.md)
- [架构决策记录](docs/decisions/README.md)
- [与腾讯 / 阿里云 AI 网关对比](docs/ai-gateway-comparison.md)

## 当前状态

Phase 1 开发中。已完成：工程骨架（G0.1）、透明代理 PoC（G0.2–G0.4）、PostgreSQL schema 与持久化（G1.1）、Secret 加密基础（G1.2）、本地认证授权（G1.3）、**Virtual Key 签发 → 网关路由/凭证注入 → 用量统计 → 用户门户的核心闭环**（当前分支 `goal/tag-routing-usage-closed-loop`）。当前进度详见 [docs/progress.md](docs/progress.md)。文档中的供应商能力分为"官方资料确认""已实现""真实凭证已验证"等层级；只有通过真实凭证契约测试的适配器才能在正式目录中标记为"已验证"。
