# 文档地图与事实来源

## 1. 优先级

发生冲突时按以下顺序处理：

1. 当前用户明确指令。
2. Accepted ADR。
3. 专项契约文档：数据库、API、Provider Adapter、配置。
4. 系统架构与产品需求。
5. 实现计划和路线图。
6. 代码与测试反映的现状。

如果代码与更高优先级文档不一致，不要静默选择一边；在当前 Goal 内修复，或记录为阻塞。

## 2. 文档职责

| 文档 | 唯一负责内容 | 何时更新 |
|---|---|---|
| `CLAUDE.md` | Agent 工作协议与不可变边界 | Agent 规则变化 |
| `claude-code-execution-contract.md` | 实施者身份、授权、输入输出和交接 | 实施责任变化 |
| `claude-code-context-strategy.md` | 上下文预算、压缩失败和新会话续接 | Claude Code 上下文策略变化 |
| `product-requirements.md` | 用户、范围、业务规则、非目标 | 产品需求变化 |
| `architecture.md` | 组件、依赖方向、运行时数据流 | 架构变化 |
| `decisions/*.md` | 已接受架构决策 | 关键决策新增/替换 |
| `ai-gateway-comparison.md` | 与腾讯/阿里 AI 网关的定位与能力对比 | 三家能力变化 |
| `tencent-ai-gateway-mapping.md` | 腾讯/阿里文档吸收记录与能力映射 | 吸收新文档 |
| `live-integration-guide.md` | 真实供应商联调环境搭建、流程与踩坑记录 | 新供应商联调/环境变化 |
| `platform-middleware-roadmap.md` | 平台中间件演进蓝图（用户同步/OAuth/计费 API/SkillHub）与分阶段建议 | leader 蓝图变化 |
| `domain-model.md` | 领域概念和关系 | 领域语义变化 |
| `database-schema.md` | 物理表、约束、索引和迁移 | 数据库变化 |
| `api-contract.md` | 管理 API 与推理入口契约 | API 变化 |
| `proxy-and-cc-switch.md` | 透明代理、协议和 CC Switch 边界 | 数据面行为变化 |
| `protocol-agents.md` | Agent/模型协议全景（入站协议、客户端矩阵、上游协议声明、红线） | 协议面变化 |
| `provider-catalog.md` | 支持的供应商产品及证据 | 产品目录变化 |
| `provider-adapter-contract.md` | Java SPI 与适配器验收 | SPI/fixture 变化 |
| `usage-accounting.md` | Token、成本、导出与对账 | 计量变化 |
| `security.md` | 安全设计与威胁控制 | 安全边界变化 |
| `portal-and-api.md` | 页面和角色视图 | 门户功能变化 |
| `ui-specification.md` | 页面字段、状态和交互验收 | UI 实现变化 |
| `frontend-design.md` | 视觉语言、tokens、布局和审美门禁 | 视觉设计变化 |
| `configuration-reference.md` | 环境变量、Secret 和目录配置 | 配置变化 |
| `testing-and-acceptance.md` | 总体验收策略 | 验收变化 |
| `test-fixtures.md` | Mock/fixture 格式和覆盖 | 测试协议变化 |
| `deployment-and-operations.md` | 部署拓扑和基础运维 | 部署变化 |
| `operations-runbook.md` | 日常故障和恢复步骤 | 运维流程变化 |
| `implementation-plan.md` | 可执行 Goal 及依赖 | Goal 调整 |
| `feature-backlog.md` | 跨文档功能总登记（未做与候选：PLANNED/SCAFFOLD/BLOCKED/ADR/DECLINED + 出处与前置） | 文档提及新功能/能力时登记；条目状态变化时更新 |
| `git-workflow.md` | 分支、commit、push、PR 和发布权限 | Git 流程变化 |
| `progress.md` | 当前真实进度 | 每个 Goal |
| `release-checklist.md` | 发布门禁 | 发布流程变化 |

## 3. 生成物

实现后以下文件由代码生成，并与手写规格共同校验：

- OpenAPI JSON/YAML：由 Control Plane 生成（springdoc，`GET /v3/api-docs`，OpenAPI 3.1）；机器可读基线提交于 `docs/openapi/openapi-3.1.json`，CI 跑破坏性 diff（F09，deploy/openapi/check-openapi-breaking.py）。
- 前端 API 类型：**手写维护中**（`frontend/src/api` + `types/api`；OpenAPI codegen 迁移为发布前候选，未实现前 API 变更需人工同步两处并跑 frontend typecheck）。
- Flyway schema：migration 是数据库执行事实，`database-schema.md` 是可读规格。
- SBOM 和许可证清单：由构建生成。
- Provider catalog 签名包：由发布任务生成。

生成物禁止手工修改。
