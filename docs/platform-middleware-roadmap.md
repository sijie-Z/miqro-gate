# 平台中间件演进规划（2026-08-31 leader 蓝图）

> 用户转述 leader 设想：把 MiQroGate 从「凭证治理网关」演进为**核心中间件** —— 串起平台组网页、公司 Agent 与模型调用，同时作为计费数据源向平台提供查询。

## 完整架构

```
平台组网页 ──注册(电话/userid)──▶ MiQroGate ──模型调用──▶ 供应商
     ▲                            │  ▲
     │────计费/用量查询 API───────┤  │
     └────────OAuth 确权──────────┘  │
                                     │
     MiqroForge(Agent) ──经网关调用──┘
```

## 模块与现状/目标/难点

| 模块 | 现状 | 目标 | 难点 |
|---|---|---|---|
| 用户同步 | 本地 Argon2id 自建用户 | 平台注册 → 电话/userid 同步创建对应网关用户 | 映射规则、冲突/重复、状态同步（禁用/删除） |
| OAuth 确权 | 无（自研 SessionFilter+CSRF） | 平台经 OAuth 访问网关受保护资源 | 认证模型叠加（OIDC/JWT vs 现有 session）；资源授权粒度；需 ADR |
| 计费查询 API | 用量/成本在门户内可查 | 向平台开放计费/用量查询 | 开放维度与范围、认证（OAuth token）、配额账本作为数据源 |
| SkillHub | 无（skill 未建模） | skill 目录：部门/项目可见全部、仅可下载授权项 | skill 形态定义（MCP tool/提示词包/代码包）；可见 vs 下载的双层权限 |
| Agent 管理 | 无 | Agent（MiqroForge 等）注册与管理 | Agent 与网关的凭证衔接 |
| 服务管理 | Provider 产品实例（供应商侧） | 内部服务接入管理 | 服务注册/发现模型 |
| 全局配置 | 各应用自有 | 网关侧全局配置 | 作用域、热更新 |

## 分阶段建议

- **P0 身份地基**：用户同步 + OAuth 接入（外部集成的先决条件；触碰现有安全模型，需 ADR）
- **P1 计费服务化**：网关作为计费数据源向平台开放查询 API（数据已有，包装+认证+范围）
- **P2 SkillHub**：先定义 skill 形态（调研 CC Switch 与腾讯 skill 概念）再建模
- **P3 内部治理**：Agent/服务管理、全局配置

## 待办细节（leader 后续补充）

- 平台注册接口/字段（电话 or userid 的确切格式）
- OAuth 的具体形态（平台自建 OAuth server？标准 OIDC？）
- SkillHub 的 skill 来源（我们公司 skill 的存放/格式）
- 计费查询 API 的开放维度（按项目？按用户？按供应商？）

## 关联

- 报备平台用户对接需求（2026-08-28 记录，`future-kafka-and-reporting-users.md`）—— 本规划的用户同步是其具体化
- 现有认证边界见 `security.md`；协议差异分析建议扒 CC Switch 源码（GitHub: lichman0405/cc-switch 相关或社区）
