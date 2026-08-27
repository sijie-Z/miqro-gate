# 可执行实现计划

## 1. 使用方式

本文件面向 Claude Code Goal 模式。一次只执行一个 Goal。每个 Goal 应控制在一个可独立验证的垂直切片内；完成后更新 `progress.md` 并停止。

通用完成标准见根目录 `CLAUDE.md`。

## Phase 0：工程骨架与数据面 PoC

### G0.1 Repository bootstrap

目标：建立跨 Windows/Linux 可复现的工程骨架。

范围：

- 初始化有效 Git 仓库（仅在用户授权或当前目录确实未初始化时）。
- 按 `git-workflow.md` 把设计文档基线推送到 `https://github.com/lichman0405/miqro-key-gateway.git`，再创建 G0.1 Goal 分支。
- Maven Wrapper 与多模块：`domain`、`provider-spi`、`provider-adapters`、`persistence-postgres`、`gateway-app`、`control-plane-app`、`test-support`。
- `com.miqroera.miqrokey` 基础包。
- Java 21 toolchain、Spring Boot BOM、Maven Enforcer、Spotless。
- Vue 3 + TypeScript + Vite + Vue Router + Pinia + TDesign（tdesign-vue-next），使用 npm lockfile。
- PostgreSQL Docker Compose 和最小健康检查。
- `.editorconfig`、`.gitignore`、`.env.example`，不包含 Secret。
- 后端/前端最小 smoke test。

验收：

- Windows `mvnw.cmd verify` 与 Linux `./mvnw verify`。
- `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`。
- `docker compose -f deploy/compose.yaml config`。
- ArchUnit 验证模块依赖方向的初始测试。

禁止：此 Goal 不实现业务表、登录或真实代理。

### G0.2 Anthropic transparent proxy PoC

目标：证明 WebFlux 可以无损代理 Anthropic Messages SSE。

范围：

- `test-support` Reactor Netty Mock Provider。
- 固定内存路由，不接数据库。
- `POST /v1/messages` 普通/流式转发。
- Header 过滤、取消传播、首包延迟指标。
- 旁路观察 SSE，不保存正文。
- UTF-8 跨 chunk、tools、thinking、cache usage fixture。

验收：契约测试证明除允许变化外，请求/响应语义一致；客户端取消关闭上游连接；无 `.block()`。

### G0.3 Responses and Chat transparent PoC

目标：补齐 CC Switch 可能向 Gateway 发出的 OpenAI 协议。

范围：

- `POST /v1/responses` 普通/流式。
- `POST /v1/chat/completions` 普通/流式。
- Responses items、function calls、reasoning、usage fixture。
- Chat tools、reasoning_content、usage fixture。
- 路径允许列表与不支持路径错误。

验收：透明契约通过；三个协议共享代理内核，不复制网络转发实现。

### G0.4 CC Switch manual compatibility PoC

目标：用门户尚未实现前的测试 Key/Base URL 验证真实 CC Switch 链路。

范围：

- Claude Code 经 Anthropic Provider。
- Claude Code 经 CC Switch Local Routing 到 OpenAI 格式 Mock。
- Codex Responses。
- Claude Desktop Direct/Mapping（仅在可用桌面环境手工验证）。

验收：记录 CC Switch 版本、配置字段、请求路径和缺口；缺少 GUI 环境时生成明确手工测试清单，不伪造 PASS。

## Phase 1：数据、身份与密钥

### G1.1 PostgreSQL schema and persistence

目标：实现 `database-schema.md` 的核心表和 Flyway V1。

范围：tenant、user、team、project、membership、provider/product、subscription/seat、credential/version、grant、virtual_key、audit 基础表。

验收：Testcontainers migration、约束、索引、repository 集成测试；数据库从空库可完整创建。

### G1.2 Secret encryption foundation

目标：实现真实 Key AES-256-GCM 和 Virtual Key HMAC。

范围：文件 Secret Provider、AAD、key version、轮换 API 内核、一次性 Virtual Key 生成、日志脱敏。

验收：加密/篡改/错 key/轮换/常量时间校验测试；数据库无明文。

### G1.3 Local authentication and authorization

目标：实现系统管理员与普通用户登录。

范围：Argon2id、临时密码、首次修改、Session Cookie、CSRF、失败延迟/锁定、两级角色。

验收：正常登录、锁定、禁用、CSRF、越权测试；无 MFA。

### G1.4 Organization and project grants

目标：管理员可管理账号、团队、项目、成员和项目供应商授权。

验收：普通用户只能读取自己的项目授权；Grant 固定到具体 Credential；模型授权为精确 ID。

### G1.5 Virtual Key lifecycle

目标：普通用户免审批创建、轮换、吊销自己的 Key。

验收：完整 Key 仅创建响应出现一次；默认永不过期；移除成员/停用 Grant 后即时拒绝；所有动作有审计。

### G1.6 Upstream credential validation and rotation

目标：管理员只录入产品要求的 Key，并可验证和无感轮换。

验收：新 Key 验证失败不覆盖当前版本；成功切换只影响新请求；旧请求可完成；密文版本可追踪。

## Phase 2：生产透明代理

### G2.1 Provider SPI and signed catalog core

目标：实现 `provider-adapter-contract.md` 的稳定 SPI 与内置目录加载。

验收：编译期适配器注册；目录 schema 校验；签名验证；远程目录不能加载代码。

### G2.2 Route snapshot and Virtual Key gateway auth

目标：用真实数据库配置替换 PoC 固定路由。

范围：版本化只读快照、Virtual Key 多 Header 解析、固定 Credential、模型预校验、刷新事件。

验收：热路径不执行阻塞数据库调用；撤销/轮换传播符合设计；冲突鉴权头拒绝。

### G2.3 Models endpoint

目标：实现按 Key 过滤的 `/v1/models`。

验收：目录、上游模型、Grant 和 Key 快照求交集；上游失败可回退最后成功目录；未授权模型不泄漏。

### G2.4 Usage lifecycle and reliable writer

目标：实现请求开始/完成记录、usage 解析和有界批量写入。

验收：数据库短暂故障不静默丢失；队列有指标/告警；usage 缺失标记明确；正文不进入持久化。

### G2.5 Timeout, retry, cancellation and backpressure

目标：实现全部网络安全边界。

验收：10s 连接、120s 首包、5min idle 配置；首字节前一次重试；首字节后零重试；慢客户端内存有界。

### G2.6 Gateway security hardening

目标：SSRF、路径、Header、body 上限和错误脱敏。

验收：未签名目标、私网解析、任意路径、超大头/body、走私 Header 测试通过。

## Phase 3：供应商产品

每个 Goal 都必须实现产品预设、credential validator、path/auth、models、usage、plan status、Mock fixture、真实测试开关和证据链接。

### G3.1 DeepSeek PAYG

作为首个完整参考适配器：OpenAI/Anthropic 透明入口、models、官方余额、价格与 usage。

### G3.2 Tencent TokenHub

覆盖 Coding Plan、Token Plan 个人版、企业专业/轻享、按量 API；重点测试多 Key 共享池、独占/上限和 Plan 专属 Base URL。

### G3.3 Zhipu GLM

覆盖个人/团队 Coding Plan 和按量 API；重点测试 Seat 与成员专属 Key。

### G3.4 MiniMax

覆盖个人/团队 Token Plan 和 PAYG；重点测试每 Team/成员 Subscription Key、席位与共享 Credits。

### G3.5 Alibaba Model Studio

覆盖 Coding Plan、Token Plan 团队版、PAYG；重点测试专属 Key/Base URL 与普通 Key 隔离。

### G3.6 Kimi/Moonshot

覆盖 Kimi Code 会员 Key 和 PAYG；不得把个人会员标为团队 Plan。

### G3.7 Baidu Qianfan

覆盖 Coding Plan、Token Plan 个人版、PAYG；团队能力未经证据验证前保持未支持。

### G3.8 Volcengine Ark

覆盖 Coding Plan、Agent Plan、PAYG；无稳定官方余额 API 时使用 DEGRADED/LOCAL_ESTIMATE。

## Phase 4：统计、Plan 和告警

### G4.1 Usage query and personal/admin dashboards API

实现时间、用户、项目、Key、Credential、Plan、供应商、模型筛选及权限隔离。

### G4.2 Quota snapshots and team plan views

实现官方 API/本地估算/不可用三种来源；团队共享池、席位、成员 Key、独占额度。

### G4.3 Pricing snapshots and cost allocation

实现 PAYG 估算、Plan 固定成本、按 Token 权重的项目摊销和算法版本。

### G4.4 Raw export and manual deletion

实现异步 CSV/JSONL gzip、SHA-256、临时下载、删除预览/二次确认/永久审计。

### G4.5 Webhook alerts

实现凭证失效、错误率、余额、异常增长、系统/备份、usage 缺失六类告警及签名、去重和重试。

## Phase 5：门户

### G5.0 Frontend design foundation

目标：在编写业务页面前建立可复用、可验收的企业控制台视觉基础。

范围：实现 `frontend-design.md` 的 tokens、应用 shell、导航、排版、表格/表单/状态样式和 Storybook 或等价组件预览；建立 Playwright visual baseline。禁止渐变、过度圆角、卡片堆叠和营销型 AI 文案。

验收：1440/1280/768/390 viewport 的 shell、表格、表单、Modal、空/错/加载态截图通过；CSS 审美禁用项检查通过；Windows 100%/125% 缩放人工检查通过。

### G5.1 User portal

登录、首次改密、个人首页、Key 创建一次性展示、列表、轮换、吊销、个人用量。

### G5.2 Admin organization portal

账号、团队、项目、成员、Grant 和模型授权。

### G5.3 Admin provider and Plan portal

产品预设、Subscription、Seat、Credential、验证、轮换、余额来源。

### G5.4 Admin usage, export and alerts portal

统计、原始流水、导出、删除、Webhook、审计。

### G5.5 UI security and accessibility

权限路由、敏感信息防缓存、键盘操作、错误状态、中文文案和基础可访问性。

## Phase 6：交付

### G6.1 Observability and optional monitoring profile

Prometheus 指标、JSON 日志、Grafana Dashboard、健康检查和高基数保护。

### G6.2 Backup and restore

每日/每周保留策略、校验、Webhook、恢复命令和真实恢复测试。

### G6.3 Security and supply-chain gate

SBOM、许可证、依赖/镜像/Secret 扫描、目录签名、审计完整性。

### G6.4 Performance and soak

50 并发 SSE、P95 额外首包延迟、取消、慢客户端、数据库故障和长时间稳定性。

### G6.5 Release candidate and customer handoff

执行 `release-checklist.md`，生成版本镜像、校验值、SBOM、许可证、备份恢复证据、运维手册和已验证供应商矩阵。

## 2. 依赖关系

```text
G0.1 → G0.2 → G0.3 → G0.4
  ↓
G1.1 → G1.2 → G1.3 → G1.4 → G1.5 → G1.6
  ↓
G2.1 → G2.2 → G2.3 → G2.4 → G2.5 → G2.6
  ↓
G3.1 → G3.2...G3.8
  ↓
G4.x 与 G5.x 可在 API 稳定后交错推进；G5.0 先于其他 G5
  ↓
G6.1 → G6.2 → G6.3 → G6.4 → G6.5
```

供应商 Goal 可按真实凭证可获得性调整顺序，但 G3.1 应先作为参考适配器。
