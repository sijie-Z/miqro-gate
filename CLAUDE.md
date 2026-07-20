# Claude Code 项目指令

本仓库用于开发 MiQroEra 内部凭证治理产品 **MiQroKey Gateway**（简称 MiQroKey）。**读到本文件的 Claude Code 是后续默认实施者**：当前规格作者不会继续替你编写代码、补测试或操作 Git。你的首要任务是严格实现现有规格，不是重新设计产品。责任、授权和每次交付格式见 [`docs/claude-code-execution-contract.md`](docs/claude-code-execution-contract.md)。

## 1. 开始任何 Goal 前

必须按顺序执行：

1. 阅读本文件。
2. 阅读 [`docs/claude-code-execution-contract.md`](docs/claude-code-execution-contract.md)，确认自己承担完整实施责任。
3. 阅读 [`docs/progress.md`](docs/progress.md)，确认当前阶段、下一 Goal 和已知阻塞。
4. 阅读 [`docs/document-map.md`](docs/document-map.md)，找到本 Goal 的唯一事实来源。
5. 阅读 Goal 涉及的规格和 ADR。
6. 阅读 [`docs/git-workflow.md`](docs/git-workflow.md)，检查当前分支、远端和未提交改动。
7. 前端 Goal 还必须阅读 [`docs/frontend-design.md`](docs/frontend-design.md)。
8. 检查现有代码、测试和工作区状态，保留用户已有改动。
9. 按执行契约报告 Goal/branch/scope/out-of-scope/verification，再开始修改。

不得为了“顺手”提前实现下一个 Goal。Goal 完成后停止并报告结果。

## Compact Instructions

本项目不能假定 CC Switch + 第三方模型的 `/compact` 永远可用。压缩时必须保留：当前 Goal、分支、修改文件、用户已有改动、实现决定、实际测试命令/结果、未解决错误、安全边界和下一条具体动作；丢弃冗长工具输出。压缩后重新读取 `docs/progress.md` 和当前 Goal。

上下文约 60% 或 `/compact` 首次失败时，按 [`docs/claude-code-context-strategy.md`](docs/claude-code-context-strategy.md) checkpoint，结束会话并用新会话继续当前 Goal。不得等到窗口耗尽，也不得因换会话启动下一个 Goal。

所有无人值守 `/goal` 条件必须有回合上限：默认 8 个 evaluated turns。条件必须要求“若 8 回合内不能 DONE，则更新 `progress.md` checkpoint 并安全停止”。禁止启动无回合/时间边界的长 Goal；auto-compact 只是优化，不是可靠的续航机制。

## 2. 不可改变的产品决策

- 单客户私有化部署，首版 50 个账号、最多 50 条并发流。
- Java 21；Gateway 使用 Spring WebFlux/Reactor Netty；前端 Vue 3 + TypeScript；PostgreSQL。
- Gateway 是多协议透明代理，不做 Anthropic/OpenAI/Gemini 跨协议转换。
- CC Switch 负责客户端配置、协议转换与模型映射。
- 一个 Virtual Key 固定绑定一个用户、项目、供应商产品、真实凭证和用途；不跨供应商，不负载均衡。
- 不自动故障切换；首字节前最多安全重试一次，流开始后不重试。
- 普通用户免审批创建自己的 Virtual Key；系统管理员管理其他全部资源。
- 不限流、不因预算阻断，只做 Webhook 告警。
- 不保存 prompt、代码、工具正文和模型回答。
- 原始用量永久保留，直到管理员手动删除。
- 第一版不做模型响应缓存、不部署 Redis，但保留缓存 SPI。
- `/v1/models` 必须按 Virtual Key 权限返回模型。
- 支持 PAYG、个人 Plan、团队 Plan、企业 Plan；团队 Plan 不能简化为单一共享 Key。
- 不依赖 LiteLLM/Bifrost 运行时。
- 生产依赖只允许宽松许可证。

改变上述决策前必须新增 ADR，并获得用户明确同意。

## 3. 技术边界

- `gateway-app` 使用 WebFlux；禁止在 Reactor event loop 上执行 JDBC、文件 IO、加密密钥加载等阻塞操作。
- `control-plane-app` 使用 Spring MVC + Spring JDBC，优先采用普通、可维护的同步代码；不为了“全栈响应式”引入 R2DBC。
- Gateway 的路由和凭证使用版本化只读快照；热路径不同步查询控制面。
- usage 持久化在专用有界执行器中批量写入 PostgreSQL；队列必须有容量、指标和告警，不能无界增长。
- 透明代理不得重排、标准化或补写推理请求 JSON。
- 所有上游 URL 必须来自编译期适配器或已签名数据目录，禁止用用户输入构造任意代理目标。
- Virtual Key 只保存 HMAC 摘要；真实上游 Key 使用 AES-256-GCM 加密。
- 禁止记录或测试快照中出现完整 Secret。

详细规则见 [`docs/coding-standards.md`](docs/coding-standards.md)。

## 4. Goal 执行协议

每个 Goal 必须遵循：

1. 将 `docs/progress.md` 的 Current Goal 更新为 `IN_PROGRESS`。
2. 只实现 [`docs/implementation-plan.md`](docs/implementation-plan.md) 中该 Goal 的范围。
3. 先补测试或同时补测试，不接受只写实现。
4. 运行该 Goal 的最小验证，再运行受影响模块的完整验证。
5. 检查格式、许可证、Secret 和数据库迁移。
6. 更新相关规格、ADR 和 `docs/progress.md`。
7. 只有验收条件全部满足时才标记 `DONE`；否则标记 `BLOCKED` 或保持 `IN_PROGRESS`。
8. 报告修改文件、测试命令、结果、剩余风险；停止，不自动进入下一 Goal。

当前 Goal 验收通过后，按 `docs/git-workflow.md` 创建规范 commit 并 push 当前 Goal 分支。Push 不等于 merge；禁止直接推送业务实现到 `main`、force push、自动 merge 或删除远端分支。

没有真实供应商 Key不构成 Mock/契约代码的阻塞。完成可完成部分，并把真实联调标记为 `WAITING_FOR_CREDENTIAL`。

## 5. 完成定义

一个 Goal 只有同时满足以下条件才算完成：

- 代码符合架构边界，没有临时绕过。
- 正常路径、失败路径和权限路径有自动化测试。
- Windows 可执行命令和 Linux CI 命令都可用。
- 没有明文 Secret、正文日志、未固定依赖或禁止许可证。
- 文档和代码一致。
- 所有验收命令成功；不能运行的命令注明客观原因。
- `docs/progress.md` 已更新。

## 6. 默认验证命令

Phase 0 创建 Wrapper 后使用：

```powershell
.\mvnw.cmd verify
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run build
docker compose -f deploy/compose.yaml config
```

Linux 对应使用 `./mvnw verify`。端到端和真实供应商测试按 [`docs/testing-and-acceptance.md`](docs/testing-and-acceptance.md) 执行。

## 7. 禁止事项

- 禁止重写或删除用户未授权的工作区改动。
- 禁止 `git reset --hard`、强制推送或跳过失败测试。
- 禁止修改已经进入任何共享环境的 Flyway migration；必须追加新 migration。
- 禁止为了让测试通过而弱化鉴权、TLS、Secret 或审计规则。
- 禁止引入自动跨供应商路由、硬限流、响应缓存或远程可执行插件。
- 禁止把供应商网页抓取或浏览器 Cookie 当作余额接口。
- 禁止在没有官方证据或真实契约测试时把适配器标记为 VERIFIED。

## 8. 文档维护

- 产品边界变化：更新 `product-requirements.md` 并新增 ADR。
- 模块/数据流变化：更新 `architecture.md`。
- 表和约束变化：更新 `database-schema.md` 与 Flyway。
- 管理 API 变化：更新 `api-contract.md` 与生成的 OpenAPI。
- ProviderProduct 变化：更新 `provider-catalog.md`、`provider-adapter-contract.md` 和 fixture。
- 前端布局/tokens 变化：更新 `frontend-design.md` 和 visual baseline。
- 运维配置变化：更新 `configuration-reference.md` 与 runbook。
- 分支/提交/推送规则变化：更新 `git-workflow.md`。
- 每个 Goal：更新 `progress.md`。
