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

## 2026-09-01 文档研究补充（腾讯云 / 阿里云 AI 网关官方文档）

对照腾讯云 AI 网关（product/1826）与阿里云 AI 网关（Higress）官方文档，澄清 leader 蓝图各模块的大厂做法与本项目位置。

### 协议：解开「很多个 OpenAI 兼容」

- 两类协议族共三类标准协议：OpenAI Chat Completions（`/v1/chat/completions`）、OpenAI Responses（`/v1/responses`）、Anthropic Messages（`/v1/messages`）。
- 「很多个 OpenAI 兼容」= 同一 OpenAI 协议在 20+ 供应商、多个产品线、多地域的接入地址与路径变体：各家通用入口（dashscope/qianfan/ark/bigmodel/moonshot/minimax/deepseek…）、各家 Coding Plan 专属入口（`coding.dashscope`、`/v2/coding`、`/api/coding/v3`、`/api/coding/paas/v4`、`/coding/v1`、`/v3`）、TokenHub 多形态入口（`/v1`、`plan/v3`、`plan/anthropic`）、路径后缀差异（`/v1`、`/v3`、`/v4`、裸 root → 需路径归一化）。
- **本项目已覆盖 80%**：G3.1–G3.8 的 23 个适配器已建模全部 23 个产品的双协议入口与路径归一化（`/v1` 剥离规则、Anthropic 路径保留、专属 Key 拓扑）。
- 大厂剩余能力「协议转换」（OpenAI↔Anthropic：finish_reason 映射、SSE 状态机、thinking block 转换）为本项目产品锁定决策刻意不做（CC Switch 职责）。

### 配额管理（文档给了完整设计，本项目现状是只读快照）

| 厂商 | 做法 |
|---|---|
| 腾讯「消费者配额管理」 | Token 用量/请求次数配额 × 日/周/月周期 × 预警阈值 50–95% × 状态（正常/预警/超限）× 超配策略（拒绝/降级路由）× 消费者默认配额（防裸奔） |
| 阿里「消费者配额」FinOps | Token 配额 + **Credits 配额**（统一积分制：先配 Credits 单价，把各供应商模型归一化度量，实时硬拦截）+ 自然日/周/月重置（可设时区）+ 消费水位大盘 + 手动重置 |
| 阿里限流（与配额互补） | 三种指标（Token/请求/并发）× 六维（消费者/Header/Query/Cookie/IP/模型）× 四种匹配（**精确/前缀/正则/任意**）× 时间窗口（秒/分/时/天）——「安全防护用正则」应指此处 |

- 两家共识：配额 = 周期总量/预算管控（FinOps），限流 = 瞬时流量保护（QPS），互补。
- 本项目差距：只有只读配额快照（quota_snapshots，G4.2）+ 告警，无「配额规则」治理对象。
- **可做（不碰「不因预算阻断」锁定决策）**：配额规则配置（对象/维度/限额/周期/预警阈值）+ 用量对照水位 + 预警。硬阻断（超限拒绝）需 ADR 反转决策；阿里的 Credits 归一化（先单价后预算）与既有单价快照/成本分摊同源，值得单独评估。

### 安全防护边界

- 腾讯：认证策略（API Key/JWT/OAuth2/OIDC 四选一）+ KMS 密钥托管（HSM）+ WAF/DDoS + 参数过滤 + 数据脱敏（DMask）+ 重放防护 + IP 黑白名单 + 提示词安全。
- 阿里：ai-security-guard 内容安全插件（输入/输出检测、jsonpath 提 content、block/mask）+ WAF + OIDC + KMS；消费者鉴权细化到 MCP 工具级。
- 本项目可做：JWT 认证（进行中，ADR-0011）、IP 白名单、元数据级正则匹配规则（不读正文）。
- 本项目不做（与「不碰正文」冲突）：内容审核、提示词安全、正文日志。

### 修正后路线（文档驱动）

| 模块 | 状态 | 下一步 |
|---|---|---|
| 协议 | 23 适配器已覆盖 | 展示/文档收尾 |
| 计费查询 | summary/records/quota 已交付平台通道 | 可按消费者维度扩展 |
| **JWT 认证** | **进行中（ADR-0011）** | 平台自签 JWT → 网关公钥验签 → 消费者映射（阿里消费者认证模式） |
| 配额管理 | 只读快照 | 配额规则配置 + 水位 + 预警（轻量，不硬阻断；硬阻断另走 ADR） |
| Agent 管理 | 无 | 对标阿里 Agent 拓扑（入口认证 + 出口模型 + 按 Agent 观测） |
| SkillHub | 无 | **进行中（P2.1 形态已定）**：Anthropic Agent Skills 规范 + 腾讯 SkillHub 分发模式 |
| 服务管理/全局配置 | Provider 实例 | P3 后置 |

## 2026-09-01 SkillHub 形态调研（P2.1，存档）

### Skill 格式：采用 Anthropic Agent Skills 规范（事实标准）

- skill = 一个含 `SKILL.md` 的目录：`skill-name/`（目录名 kebab-case，与 frontmatter `name` 完全一致）+ 可选 `scripts/`（可执行代码，不进上下文）、`references/`（按需加载文档）、`assets/`（模板/静态资源）
- `SKILL.md`：YAML frontmatter 必需 `name`（≤64 字符、小写连字符、不得含 claude/anthropic 保留词）+ `description`（第三人称「做什么 + 何时触发」，200–1024 字符）；可选 `author`/`license`（SPDX）/`source`/`requires_tools`/`requires_skills`/`tags`/`dependencies`
- 打包：zip 根目录包含技能文件夹本身（`my-skill.zip └── my-skill/`）
- 渐进式披露：元数据（name/description）常驻 → SKILL.md 触发时加载 → 资源按需
- **选择理由**：Claude Code / Claude.ai 原生支持，公司内部 agent 可直接消费；验证规则明确（SKILL.md 必须存在、frontmatter 必须可解析、name 与目录一致）

### 分发模式：对标腾讯 SkillHub「应用商店」

- 腾讯 SkillHub（2026-03 上线）：OpenClaw 生态本地镜像站 —— 安全审核 + 官方认证标签 + 榜单 + 加速下载；阿里（钉钉悟空）做 To B Skill 市场；字节（Find Skill）做多源聚合
- 共识：skill 是「可标准化、封装、复用和分发的能力单元」，分发入口 = 生态控制点
- **落地到本项目**：管理员上传（zip → 服务端解包校验 SKILL.md → 元数据入库 + zip 存 DB，与 export_tasks 产物同风格）；**全部 ACTIVE skill 对登录用户可见**（目录/详情），**下载按授权**（skill 配置 TEAM/PROJECT 授权范围，用户在范围内才可下载）—— 与 leader「能看到所有的 skill，但只能下载对应的 skill」一致

### P2 拆分

- P2.2 数据模型 + 迁移（`skills` + `skill_access`，V16）
- P2.3 后端 API：上传（SKILL.md 校验）/目录/详情/下载（授权门禁）/授权管理
- P2.4 前端：SkillHub 浏览页（全员）+ 管理上传页（管理员）
