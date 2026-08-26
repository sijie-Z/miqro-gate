# 开发进度

> 此文件是跨 Claude Code/Goal 会话的最小交接状态。每个 Goal 开始和结束时必须更新。不要在这里复制完整设计；链接到事实来源。

## Current State

- Project phase: `PHASE_1`
- Current executor: `Claude Code`
- Current goal: `G3.4`（MiniMax：个人/团队 Token Plan、按量 API；每成员 Subscription Key、席位与共享 Credits）
- Goal status: `DONE`（全量验证通过：845 tests / 0 failures / 0 errors / 5 skipped，Windows POSIX）
- Last updated: `2026-08-26 CST`
- Branch: `goal/g3.4-minimax`
- Remote: `https://github.com/sijie-Z/miqro-key-gateway.git`

## Completed

- 产品范围、角色、Virtual Key 固定映射和非目标已确认。
- Java 21 / Spring Boot / WebFlux / Vue 3 / PostgreSQL 技术方向已确认。
- Gateway 透明代理、CC Switch 负责协议转换的边界已确认。
- 个人、团队、企业 Plan 领域模型已确认。
- 首版供应商候选、用量、成本、安全、部署和测试文档已完成。
- 面向 Agent 的开发契约、Goal 分解、API/数据库/Provider/UI/配置契约、开发工作流、运维 Runbook 和发布清单已完成。
- Git/commit/push/PR 工作流和前端 Quiet Operations Console 视觉规范已完成。
- Claude Code 实施身份、默认授权、Goal 输入输出和失败恢复交接契约已完成。
- CC Switch + 第三方模型无法可靠 `/compact` 时的 disk-first checkpoint 与 fresh-session 续接策略已完成。
- 产品与工程标识确定为 MiQroKey Gateway / MiQroKey，仓库 `miqro-key-gateway`，Java 包 `com.miqroera.miqrokey`。

## G0.1 — Repair (Round 2)

### Repairs applied

1. **Maven Wrapper**: Real SHA-256 checksums from Maven Central/ASF; `maven-wrapper.jar` committed to Git; checksum verification in both `mvnw` and `mvnw.cmd` (powershell `certutil` for Windows, `sha256sum` for Unix); `mvnw` executable bit set via `git update-index --chmod=+x`.

2. **Configuration aligned with `configuration-reference.md`**:
   - Gateway port: `${MIQROKEY_GATEWAY_PORT:8081}`
   - Control Plane port: `${MIQROKEY_CONTROL_PORT:8080}`
   - DB config: `${MIQROKEY_DB_URL}`, `${MIQROKEY_DB_USERNAME}`, `${MIQROKEY_DB_PASSWORD}` (with `_FILE` convention noted)
   - `.env.example` updated with `MIQROKEY_` prefix
   - `compose.yaml`: postgres pinned to `17.6-alpine`, port configurable via `${MIQROKEY_DB_PORT:-5432}`

3. **ArchUnit**: `allowEmptyShould(true)` removed from cross-module rules; `control-plane-app` added as test-scope dependency in `gateway-app` so all checks verify actual classes; reactor module order adjusted (control-plane-app before gateway-app); `DataSourceAutoConfiguration` excluded in Gateway smoke test to prevent JDBC auto-config clash.

4. **`.flattened-pom.xml`**: Removed from Git index (`git rm --cached`); `**/.flattened-pom.xml` already in `.gitignore`.

5. **Maven plugin versions**: Locked `maven-compiler-plugin:3.13.0`, `maven-jar-plugin:3.4.2`, `maven-surefire-plugin:3.5.2`, and `spring-boot-maven-plugin` in parent POM `pluginManagement`.

6. **Initial Compose image pin**: Replaced the mutable PostgreSQL major tag with `17.6-alpine`; item 9 records the final digest lock.

7. **Windows Wrapper exit semantics**: `mvnw.cmd` now propagates Maven's real exit code. A deliberately invalid Maven phase returns exit code `1`; CI includes a regression check so a failed build cannot be reported as successful.

8. **Management endpoint boundary**: Gateway data-plane exposure is limited to `health,info`; `metrics`/`prometheus` are not exposed on the public Gateway port. A smoke test enforces this boundary.

9. **Reproducible Compose image**: PostgreSQL is pinned to the Docker Hub multi-platform manifest digest for `postgres:17.6-alpine`; CI rejects every Compose image that lacks an `@sha256:` digest.

10. **Configuration regression tests**: Gateway `8081` and Control Plane `8080` defaults are asserted. Control Plane test overrides moved to `application-test.yml`, avoiding accidental replacement of the production `application.yml`.

### Local verification (Windows, Java 21 Temurin 21.0.11)

- `.\mvnw.cmd clean verify --batch-mode --quiet`: **BUILD SUCCESS** — clean checkout-equivalent build
- `.\mvnw.cmd verify --batch-mode --quiet`: **BUILD SUCCESS** — 15 tests, 0 failures, 0 errors
  - Domain contract: 1 test
  - Control Plane smoke/configuration: 2 tests
  - ArchUnit module dependency: 8 rules (all effective, no `allowEmptyShould`)
  - Gateway smoke/configuration/security: 4 tests
  - Spotless check: all modules clean
  - Maven Enforcer: all rules passed
- Deliberately invalid `mvnw.cmd` phase: expected exit code `1` (failure propagation verified)
- `npm ci`: PASS (0 vulnerabilities)
- `npm run lint`: PASS
- `npm run typecheck`: PASS
- `npm run test`: PASS (1 test)
- `npm run build`: PASS

### CI evidence

- PR: `https://github.com/lichman0405/miqro-key-gateway/pull/1`
- Baseline repair commit `b732f4c`: Ubuntu backend, frontend and Compose config all passed in run `29733691718`.
- Final implementation commits: `75b6a22` and `cd100ff`.
- Final implementation CI run `29796610144`: Ubuntu backend, Windows backend, Windows Wrapper failure propagation, frontend, Compose config and digest locking all passed.
- CI evidence: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29796610144`

## Known Blockers

- 真实供应商凭证尚未提供；不阻塞 Mock 与本地契约开发。
- 本机 Docker Desktop 可用（`D:\programming\Docker_4.78.0`）；Compose config 本地 PASS，digest 门禁由 CI 复核。

## Next Goal

- Goal ID: `G3.5`
- Name: Kimi / Moonshot（Kimi Code 会员 Key + 按量 API；官方余额查询 API）
- Status: `NOT_STARTED`
- Source: [`implementation-plan.md`](implementation-plan.md)（Phase 3 供应商产品）

## G3.4 — MiniMax（个人/团队 Token Plan + 按量 API，DONE）

### 官方事实核验（2026-08-26，platform.minimax.io）

- OpenAI 兼容 base：`https://api.minimax.io/v1`（签名目录 baseUrlTemplate 为 `https://api.minimax.chat/v1`，属 DOCUMENTED 设计值，管理员按官方端点配置）；Anthropic 兼容 base：`https://api.minimax.io/anthropic`（官方存在，但目录只声明 `OPENAI_COMPATIBLE`，待下一版签名）。
- 模型列表 API 官方存在：`GET https://api.minimax.io/v1/models`，`Authorization: Bearer <API_KEY>`，响应 `data[].id/object/created/owned_by`（无 display name）。
- Token Plan 专属 Key 形如 `sk-cp-…`，与按量 API Key 不互通；当前模型 `MiniMax-M3`。
- 团队版：席位 1:1 分配给成员（可转授、不重置用量）；未分配席位的成员在开启权限后可经自己的 Subscription Key 消费共享 Credits 池 → `PER_MEMBER_SUBSCRIPTION_KEY` + 共享 Credits，`sharedPool=true`。
- **docs 索引（llms.txt）无任何 Token Plan 余额/用量查询 API**（额度与钱包余额仅控制台可见）→ `fetchPlanStatus` 返回 `UNAVAILABLE`。

### 实现

- `provider-adapters`：`MiniMaxAdapter`（3 个静态工厂，adapterId 与签名目录逐一匹配）+ `MiniMaxUsageObserver`；base 以 `/v1` 结尾 → 剥离 OpenAI SDK `/v1` 前缀（`/v1/chat/completions` → `/chat/completions`）；`fetchModels` 解析官方 list-models 形状（无 display name → `ModelDefinition(id)`，兼容 `name` 变体）；`fetchPlanStatus` → `UNAVAILABLE`（0 HTTP）；团队版 `teamPlan=true` + `sharedPool=true`。
- `control-plane-app`：`ProviderClientConfig` 注册 3 个 MiniMax 适配器（现共 12 个适配器：DeepSeek 1 + Tencent 5 + Zhipu 3 + MiniMax 3）。

### 测试（本 Goal 新增 17 个）

- `MiniMaxAdapterTest`（13）：3 个产品 adapterId/协议与签名目录一致；凭证剥离 + query 重编码；`/v1` 前缀剥离；空 query；凭证探活 `/models`；401/403/429/5xx 映射；fetchModels 官方形状/`name` 变体/失败模式；fetchPlanStatus `UNAVAILABLE` 且 0 HTTP；capabilities 三产品差异；observer 绑定。
- `MiniMaxUsageObserverTest`（4）：OpenAI 形状 + 根 model id、`cached_tokens` 形状、空/畸形容忍、observer 最新值。
- `ProviderClientConfigTest`（更新）：注册列表含 12 个适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实 MiniMax 凭证联调 → `WAITING_FOR_CREDENTIAL`。
- Anthropic 兼容入口与 VENDOR_NATIVE 能力待目录下一版签名补声明（JSON 不可改）。
- 签名目录 baseUrlTemplate（`api.minimax.chat`）与官方当前端点（`api.minimax.io`）不一致：录入产品实例时以官方端点为准（已在 provider-catalog §3.4 注明）。

### 验证

- 模块验证：`verify -pl provider-adapters,control-plane-app -am` → BUILD SUCCESS；全量 `verify -P integration` → **BUILD SUCCESS**（见 Current State 计数）。

## G3.3 — 智谱 GLM（个人/团队 Coding Plan + 按量 API，DONE）

### 官方事实核验（2026-08-26，docs.bigmodel.cn）

- **Coding Plan OpenAI base**：`https://open.bigmodel.cn/api/coding/paas/v4`（Coding Plan 专属，与按量 API 的 `/api/paas/v4` 不同）；**Anthropic base**：`https://open.bigmodel.cn/api/anthropic`（完整路径 `.../api/anthropic/v1/messages`）。
- 鉴权：OpenAI 入口 `Authorization: Bearer <API_KEY>`（官方 API 文档）；Anthropic 兼容入口官方示例用 `x-api-key`（Anthropic SDK 默认头）—— 适配器按平台惯例注入 Bearer，兼容性列为 `WAITING_FOR_CREDENTIAL` 风险。
- 套餐：积分池（Lite 2000/5h、10k/周；Pro 12k/5h、60k/周；Max 28k/5h、140k/周），按抵扣系数扣减，非高峰 50% 抵扣；Coding Plan 支持 GLM-5.3 / GLM-5-Turbo / GLM-4.7。
- 团队版：席位制（2 席起购），每席位独立限额（标准版 15k/5h、66k/周；高级版 35k/5h、155k/周）→ `PER_SEAT_KEY`，额度按席位单独限制而非团队共享池；团队 Key 与平台其他 API Key 不通用。
- **docs 索引（llms.txt）无任何余额/用量查询 API 与模型列表 API** → `fetchPlanStatus` 返回 `UNAVAILABLE`（契约 §6 权威级别）；`/models` 探活为 OpenAI 兼容惯例端点，待真实凭证核验。

### 实现

- `provider-adapters`：`ZhipuGlmAdapter`（3 个静态工厂，adapterId 与签名目录逐一匹配）+ `ZhipuGlmUsageObserver`；路径归一化沿用 `/v4`-suffixed base 剥离 `/v1` 的规则；`fetchPlanStatus` → `UNAVAILABLE`（不发起 HTTP）；团队版 `capabilities.teamPlan=true` 而 `sharedPool=false`（席位独立限额）。
- `TokenUsageParser`（G3.2 共享解析器）：新增 `prompt_tokens_details.cached_tokens` → cacheRead 回退（智谱官方 usage 形状，也是 OpenAI 标准缓存形状）；对 DeepSeek/Tencent 行为不变（它们不产该字段）。
- `control-plane-app`：`ProviderClientConfig` 注册 3 个智谱适配器（现共 9 个适配器：DeepSeek 1 + Tencent 5 + Zhipu 3）。

### 测试（本 Goal 新增 18 个）

- `ZhipuGlmAdapterTest`（13）：3 个产品 adapterId/协议与签名目录一致；凭证剥离 + query 重编码；`/v1` 前缀剥离；Anthropic 路径保留；官方端点映射（PAYG `/api/paas/v4`、Coding Plan `/api/coding/paas/v4`、Anthropic `/api/anthropic`）；凭证探活 `/models`；401/403/429/5xx 映射；fetchModels 解析/失败模式；fetchPlanStatus `UNAVAILABLE` 且 0 HTTP；capabilities 三产品差异；observer 绑定。
- `ZhipuGlmUsageObserverTest`（5）：OpenAI 兼容形状、**智谱文档形状 `prompt_tokens_details.cached_tokens`**、Anthropic 形状、空/畸形容忍、observer 最新值。
- `ProviderClientConfigTest`（更新）：注册列表含 9 个适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实智谱凭证联调 → `WAITING_FOR_CREDENTIAL`。
- `/models` 端点官方文档未收录：真实凭证联调若确认不存在，validateCredential 改用最小推理探针并同步文档。
- Anthropic 兼容入口官方示例用 `x-api-key`：Bearer 兼容性待真实凭证核验。
- 上一会话遗留问题本会话修复：`ProviderClientConfig` 只有 import 未注册；usage 测试只覆盖 DeepSeek 形状未覆盖智谱文档形状；javadoc 声称无法核验官方文档（本会话实际核验成功并更新事实表）。

### 验证

- Windows 全量：`./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode` → **BUILD SUCCESS**（见 Current State 计数）。

## G3.2 — Tencent TokenHub（第二个参考适配器：团队 Plan、余额与 usage，DONE）

### 实现

- `provider-adapters`：
  - 共享 `TokenUsageParser`（G3.2 抽取）：OpenAI 兼容（prompt/completion + `prompt_cache_hit/miss_tokens`）与 Anthropic Messages（input/output + `cache_read/creation_input_tokens`）双形状；解析时优先从响应根/`message.model` 取 model id（修复 OpenAI 真实形状中 `model` 与 `usage` 为兄弟节点的场景），`usage.model` 次之；标准 cache 名优先于 OpenAI 兼容 cache 名；解析失败返回空 Optional 绝不影响请求。
  - 共享 `TransparentResolve`：入站凭证 Header 剥离 + query map 重编码为原始 query 串（Header 名小写），供 OpenAI/Anthropic 兼容适配器复用。
  - `TencentTokenHubAdapter`：1 个参数化类 + 5 个静态工厂，adapterId 与签名目录逐一匹配（`tencent-coding-plan`、`tencent-token-plan-personal`、`tencent-token-plan-enterprise-pro`、`tencent-token-plan-enterprise-lite`、`tencent-payg-api`）。
  - 产品专属 Base URL 路径归一化：`/v3`-suffixed plan base（Coding/Token Plan 个人版/企业版）对 `OPENAI_COMPATIBLE` 请求剥离 `/v1` 前缀（`/v1/chat/completions` → `/chat/completions`）；Anthropic Messages 路径与 TokenHub PAYG root base 保持原样。
  - `validateCredential`：按产品归一化后的模型列表路径探活（PAYG `/v1/models`，Plan 产品 `/models`）；401/403 → credential rejected，429 → rate limited，其余 HTTP 状态稳定文案。
  - `fetchModels`：解析 TokenHub 文档形状 `data[].id` + `name`，兼容 `display_name` 变体；未知字段容忍；非数组 data 视为空。
  - `fetchPlanStatus`：2026-08-25 核验腾讯云 5 个产品均无公开余额/用量 API（仅控制台），按 `provider-adapter-contract.md` §6 权威级别返回 `UNAVAILABLE`，不发起 HTTP 调用，不以本地估算冒充官方值。
  - `capabilities`：streaming/modelDiscovery/usage=`PROVIDER_RESPONSE`；balance=false（无官方余额 API）；PAYG `plan=false/teamPlan=false`，个人 Plan `plan=true/teamPlan=false`，企业 Plan `plan=true/teamPlan=true` + `PlanSnapshot.sharedPool=true`（多 Key 共享积分/Token 池建模）。
  - `TencentUsageObserver`：observer 绑定 context + 最新值存储；复用 `TokenUsageParser`。
- `control-plane-app`：`ProviderClientConfig` 编译期注册 5 个 Tencent TokenHub 适配器 + DeepSeek（重复 adapterId 启动失败）。
- `deepseek`：
  - `DeepSeekPaygAdapter.resolve` 与 `DeepSeekUsageObserver` 改复用 `TransparentResolve`/`TokenUsageParser`，行为不变、既有测试保持通过。
  - 顺带修复 usage 解析在 OpenAI 真实形状中未从响应根取 `model` 的 latent 缺陷（G3.1 只覆盖 `usage.model` 与无 model 两种情况；G3.2 新增根级 `model` 回退）。

### 测试（本 Goal 新增 31 个）

- `TencentTokenHubAdapterTest`（15）：5 个产品 adapterId/协议与签名目录一致；resolve 剥离凭证/保留其他 Header/query 重编码；OpenAI `/v1` 前缀在 plan base 剥离、PAYG 保留；Anthropic Messages 路径保留；凭证探活路径按产品归一化；401/403/429/5xx 状态映射；fetchModels 解析 `name`/`display_name`/未知字段/失败模式；fetchPlanStatus 对所有产品返回 `UNAVAILABLE` 且不发起 HTTP；5 个产品 capabilities 差异；usage observer 绑定。
- `TencentUsageObserverTest`（5）：OpenAI 兼容 cache 字段、Anthropic cache 字段、根 usage 优先于 message.usage、空/畸形返回空、observer 最新值。
- `TokenUsageParser` 通过 DeepSeek 与 Tencent 两套测试覆盖。
- `ProviderClientConfigTest`（更新）：断言编译期注册包含 DeepSeek + 5 个 Tencent 适配器。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档 2026-08-25 核验），`VERIFIED` 需真实 Tencent 凭证联调 → `WAITING_FOR_CREDENTIAL`（不阻塞 Mock/契约工作）。
- 签名目录当前为 5 个 Tencent 产品声明的协议族：Coding Plan 含 `ANTHROPIC_MESSAGES`，其余 4 个产品只声明 `[OPENAI_COMPATIBLE, VENDOR_NATIVE]`。官方文档显示 Token Plan 个人版/企业版/TokenHub 按量也提供 Anthropic 兼容入口，但签名 JSON 不可改；Anthropic 入口使用需在目录下一版签名时由发布负责人补 `ANTHROPIC_MESSAGES`。
- 企业版“独占额度/总上限/TPM/模型限制”均为控制台配置，官方无 API 可查；系统通过 `sharedPool=true` 表达多 Key 共享池，`fetchPlanStatus` 显式 `UNAVAILABLE`，不伪造额度明细。

### 验证

- Windows 全量：`./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode` → **BUILD SUCCESS**，**810 tests / 0 failures / 0 errors / 5 skipped**（11 模块全绿，含 Testcontainers integration）。
- 前端：`npm ci`、`npm run lint`、`npm run typecheck`、`npm run test`（16 passed）、`npm run build` 全 PASS。
- Compose：`docker compose -f deploy/compose.yaml config` PASS。

## G3.1 — DeepSeek PAYG 首个完整参考适配器（DONE）

### 实现

- `provider-adapters`：`DeepSeekPaygAdapter`（adapterId `deepseek-payg-api`，与签名目录一致）—— OpenAI 兼容 + Anthropic Messages 双协议；`resolve` 剥离入站鉴权 Header 并把解码后的 query map 重编码为原始 query 串（Header 名统一小写）；`credentialInjection`（Bearer Authorization，strip `authorization/x-api-key/api-key`）；`validateCredential`（GET /models，2xx 有效，401/403/429/其他 → 稳定文案）；`fetchModels`（data[].id/display_name，未知字段容忍，非数组 data 视为空）；`fetchPlanStatus`（GET /user/balance，`total_balance` → PAYG total/remaining，used/period 保持 null 不冒充）；`capabilities` 声明 streaming/modelDiscovery/balance/requestId + `PROVIDER_RESPONSE` usage。
- `DeepSeekUsageObserver`：SPI 契约的 observer（onUsage 恰好一次回调、不碰字节流）；`parse` 纯函数 —— OpenAI 兼容（prompt/completion + DeepSeek 特有 `prompt_cache_hit/miss_tokens`）与 Anthropic Messages（input/output + cache_read/creation）双形状；标准 cache 名优先于 DeepSeek 特有名；解析失败返回空 Optional 绝不影响请求。
- `control-plane-app`：`ProviderClient` 首个实现 `HttpProviderClient`（JDK `HttpClient`，零新依赖）—— 每次交换重校验 base URL（SSRF 门控，拒绝原因不含 URL）、连接/请求超时、响应体 1MB 上限、`Redirect.NEVER`（3xx 原样返回）；`ProviderClientFactory` 单一创建点，每个凭证独立 client；`ProviderClientConfig` 编译期注册 DeepSeek 适配器（重复 adapterId 启动失败）+ 生产默认空 allowlist 校验器；`application.yml` 新增 `miqrokey.control.provider-client.*`（env `MIQROKEY_CONTROL_PROVIDER_CLIENT_*`）。
- `gateway-app`：`SseUsageObserver.parseUsageJson` 补齐 DeepSeek 特有 cache 字段映射（hit→cacheRead、miss→cacheCreation，标准名优先）。
- `ModelCatalogService.refreshProduct`（G2.3 接缝）端到端打通：真实适配器 + 真实 ProviderClient 对本地 mock 官方 JSON 形状 → `model_catalog` 落库（success-only 写入不变）。
- `provider-adapters` package-info 修正：ServiceLoader → 编译期注册措辞。

### 测试（本 Goal 新增 32 个）

- `DeepSeekPaygAdapterTest`（13）：身份/协议、Header 剥离 + query 重编码、凭证注入契约、validateCredential 全状态映射、fetchModels 解析/未知字段/失败模式、fetchPlanStatus 余额/空列表/非 2xx、usage observer 绑定、capabilities。
- `DeepSeekUsageObserverTest`（7）：双形状解析、标准 cache 名优先、message.usage 回退、model id、空/畸形返回空、onUsage 最新值。
- `HttpProviderClientTest`（6）：凭证注入 + 路径拼接、query 转发、3xx 不跟随、请求超时、body 上限、SSRF 拒绝（0 上游请求）。
- `ProviderClientConfigTest`（3）：编译期注册含 deepseek-payg-api、生产 validator 空 allowlist、factory 构建凭证作用域 client。
- `SseUsageObserverTest`（+2）：DeepSeek cache 字段映射 + 标准名优先。
- `ModelCatalogServiceIntegrationTest`（+1，integration）：真实适配器 + 真实 client 端到端 → PostgreSQL（含 Authorization 断言）。

### 风险与边界

- 适配器状态 `IMPLEMENTED`（官方文档核验），`VERIFIED` 需真实 DeepSeek 凭证联调 → `WAITING_FOR_CREDENTIAL`（不阻塞 Mock/契约工作）。
- 管理 API 凭证校验端点（本地格式检查）未在 G3.1 接线到上游 `validateCredential`（需解密 + 网络；接缝已存在，G4.x 接线）。

### 验证

- Windows 全量：`./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode` → **BUILD SUCCESS**，779 tests / 0 failures / 0 errors / 5 skipped（11 模块全绿，含 Testcontainers integration；surefire XML 汇总：domain 100 / provider-spi 8 / provider-adapters 45 / persistence 118 / route-snapshot 3 / control-plane 198 / gateway 198 / test-support 109）。
- **根因修复（关键）**：`application.yml` 初次编辑把 `miqrokey:` 块插在 `spring.main` 与 `spring.datasource` 之间，导致 `datasource:`/`flyway:` 被吞入 `miqrokey:` 命名空间 —— `spring.datasource.*`（pool 20）与 `spring.flyway.*` 全部失效，控制面集成测试共享 testcontainer（max_connections=100）被各 Spring 上下文的 Hikari 池耗尽（`FATAL: sorry, too many clients`）。已把 `miqrokey:` 块移到 `spring:` 之后恢复结构（diff 仅 9 行插入），修复后控制面模块 1:21 通过、全量 3:12 通过。教训：向 `application.yml` 顶部插入顶级块时必须检查后续键的缩进层级。

## G2.6 — Gateway security hardening（SSRF、路径、Header、body 上限和错误脱敏，DONE）

### 实现

- `UpstreamTargetValidator`（gateway-app 新增）：SSRF 双重门控 —— `https` 硬要求（除非命中 allowlist）、`userinfo` 一律拒绝；DNS 解析后每个地址必须公网（环回/链路本地/RFC1918/CGNAT `100.64/10`/组播/any-local/IPv6 ULA `fc00::/7` 均拒）；拒绝原因仅稳定类别 token，错误体/日志/审计不出现目标 URL。阻塞 DNS 在 `credentialDecryptScheduler` 上执行，不占事件循环。
- `ProxyController`：`doForward` 拆出 `forwardWithResolvedCredential`，插入门控（拒绝 → `502 route_unavailable`）；`DataBufferLimitException` → `413 payload_too_large`（已有 256KB 缓冲上限接线，本次补测试）。
- 入站 Header 上限：`server.netty.max-header-size`（默认 `32KB`，Netty 路由前拒绝 → `431`）。
- 路径白名单（已有 catch-all，本次补契约测试）：三个 POST 端点，其余 `/v1/**` → 404、错方法 → 405、`..` 字面处理、`//` 归一化后按规范路径处理，均不触达上游。
- 配置：`MIQROKEY_UPSTREAM_ALLOWED_CIDRS`（默认空 = 全拒）、`MIQROKEY_MAX_INBOUND_HEADER_BYTES`（`32KB`）。

### 测试（本 Goal 新增 29 个）

- `UpstreamTargetValidatorTest`（14）：scheme/userinfo/解析地址/CIDR 匹配/allowlist 状态。
- `GatewaySecurityHardeningTest`（12，严格路径：空 allowlist + mutable target）：SSRF 拒绝（loopback/169.254.169.254/RFC1918/userinfo → 502 route_unavailable，错误体无泄漏、0 上游请求）、路径/方法/归一化、431 超大头、413 超大 body。
- `VirtualKeyAuthContractTest$HeaderSmuggling`（3）：伪造凭证 Header 在鉴权层 401（0 上游请求）、重复 Authorization → 401、hop-by-hop/`X-MiQroKey-*` 剥离、只有注入凭证到达上游且客户端 Key 不泄漏。

### 验证

- 本地 Windows：`verify -P integration` → **742 tests / 0 failures / 0 errors / 5 skipped**（G2.5 基线 714，POSIX 跳过 5）。
- 契约测试用 `@Primary` loopback allowlist（`127.0.0.0/8, ::1/128`）；集成测试用动态属性；严格路径类不 import `GatewayAuthTestConfig`。

### 文档

- `security.md` §6（SSRF 双重门控、入站防护、错误脱敏）、`api-contract.md` §7.1（502/404/405/401/413/431 语义 + 走私防护）、`configuration-reference.md`（`ALLOWED_CIDRS`/`MAX_INBOUND_HEADER_BYTES` 精确语义）。

### 风险与边界

- 生产默认严格路径：本地自建模型须显式配置 `MIQROKEY_UPSTREAM_ALLOWED_CIDRS`。
- 上游 `http` 仅 allowlist 放行；`FOLLOW_REDIRECTS` 保持禁用（重定向不改变已校验目标）。
- 未接管理 API body 上限（`MIQROKEY_MAX_CONTROL_BODY_BYTES` 属控制面，G2.6 只覆盖数据面）。
- 已知偶发 flaky（与本 Goal 无关，既有代码）：`AuditChainIntegrityTest.preLockTimestampsDoNotAffectHeadOrdering` 在完整套件下偶发 hash 不匹配（共享 Testcontainers 容器 + 8 线程并发写入），单独运行与重跑完整套件均通过（G2.5/G2.6 前两次完整验证亦通过）。归因于并发时序，待 G4.x 控制面收尾时单独排查。

## G2.5 — Timeout, retry, cancellation and backpressure（G2.5 超时/重试/取消/背压，DONE）

### Outcome

1. **四层网络边界**（`ProxyTargetProperties`，全部可配置）：连接 10s（`CONNECT_TIMEOUT_MILLIS`）；**首包 120s**（reactor-netty `HttpClient.responseTimeout()` = 等响应头；超时表现为连接错误，永不重试）；**流式空闲 5min**（对观测 body `Flux.timeout`，每个 chunk 重置）；**整体硬截止 10min**（`Mono.timeout` 包在重试外层，自第一次尝试计时、不随重试重置）。默认值按 G2.5 验收校准（连接 PT5S→PT10S，idle PT2M→PT5M，新增 first-byte PT120S）。
2. **首字节前最多重试一次**：`Mono.defer` 包每次尝试 + `Retry.max(1)`，filter 仅放行连接阶段失败（`WebClientRequestException` 且非任何超时）且尚未出首字节；真实凭证只在第一次尝试前解析一次，重试复用同一凭证（不跨凭证故障切换）。reactor 3.7 默认 exhausted 策略会把原始异常包成 `RetryExhaustedException`，用 `onRetryExhaustedThrow((s, sig) -> sig.failure())` 恢复原始类型（否则 502 映射漏网，真实缺陷修复）。`retry_count` 端到端持久化（event → `request_usage_records.retry_count`，start 行 0，completion 通过 guarded upsert 更新）。
3. **终态判定顺序修复**（真实缺陷修复）：upstreamError 判定移到 httpStatus 之前——上游 200 状态行 + 中途流失败现在正确记为 `STREAM_INTERRUPTED`（旧顺序误报 SUCCEEDED）；timeout 细分：未出首字节 → `TIMEOUT_BEFORE_FIRST_BYTE`，已出 → `STREAM_INTERRUPTED`。
4. **慢客户端内存有界**：响应按 chunk 直通（streaming，不聚合）；256KB `maxProxyBuffer` 只限 usage/缓存收集缓冲，溢出时放弃收集（`usage_missing=true`）绝不影响转发（512KB 响应 + 慢消费者完整收包测试）。
5. **Mock 能力扩展**（test-support）：`disconnectNextRequest`/`disconnectAllRequests`（连接阶段 EOF，模拟可重试失败）、`responseDelay`（慢首包）、`haltAfterLines`（N 行后永久停滞，idle 超时）、`chunkDelay` 流式分块。无 delay/halt 的流式路径保持单次原始写入（line-rebuild 会在 body 尾部追加幻影 `\n`，契约测试逐字节断言，真实缺陷修复）。

### Verification

- 全量 `./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode`（本机 Docker Desktop，Testcontainers 实跑）：**BUILD SUCCESS** —— **714 tests / 0 failures / 5 skipped**（Windows POSIX 权限跳过）
- `TimeoutRetryIntegrationTest`（Testcontainers + AnthropicMockProvider，7）：连接失败重试一次成功（retry_count=1）；持续断连 → 502 + `UPSTREAM_UNAVAILABLE`（retry_count=1）；200 成功 retry_count=0；慢首包 → `TIMEOUT_BEFORE_FIRST_BYTE`（未重试、无首包）；idle 停滞 → `STREAM_INTERRUPTED` + partial_response + http_status=200 + client_cancelled=false；长流超整体截止 → `STREAM_INTERRUPTED`；512KB 慢客户端完整收包 + `usage_missing=true`。
- 契约测试回归（mock 流式路径修复后）：Anthropic/Chat/Responses ProxyContractTest 71/71 全绿。
- Spotless check 全模块 PASS；Maven Enforcer：PASS。

### Files changed

- **gateway-app**：`ProxyTargetProperties`（connect/first-byte/stream-idle/response/max-buffer）、`ProxyConfig`（`HttpClient.responseTimeout(firstByte)`）、`ProxyController`（重试封装、per-attempt `UpstreamAttempt` 状态隔离、终态顺序、isTimeout、retry_count 传递）、`application.yml`（4 个新环境变量）
- **domain**：`RequestCompletedEvent`（+`retryCount`）
- **queue-spi**：`PostgresUsageEventWriter`（retry_count 两处 SQL + params）
- **test-support**：`AnthropicMockProvider`（disconnect/responseDelay/haltAfterLines + 流式单写路径修复）、`GatewayTestKeys`
- **测试**：新 `TimeoutRetryIntegrationTest`（7）；`PostgresUsageEventWriterTest`/`PostgresUsageEventBusTest` 构造更新
- **文档**：architecture.md §6（四层超时 + 重试 + 慢客户端语义）、configuration-reference.md §5（新 keys/默认值校准）

### Remaining risks

- 慢首包/断连场景经 mock 验证；真实供应商网络行为变体 `WAITING_FOR_CREDENTIAL`。
- 首字节后对上游的取消传播依赖 reactor-netty 通道关闭语义（已有 `STREAM_INTERRUPTED` 断言覆盖）。
- G2.6 将收紧未签名目标/私网解析等安全边界，本节超时实现保持兼容。

## G2.4 — Usage lifecycle and reliable writer（G2.4 请求生命周期记录 + 有界批量写入，DONE）

### Outcome

1. **请求生命周期记录**（`request_usage_records`，V8 月度分区表）：每个到达上游的请求在发出前发布 `RequestStartedEvent` 打开 `IN_FLIGHT` 行（`ON CONFLICT (started_at, gateway_request_id) DO NOTHING`），在任何终态信号上**恰好 finalize 一次**——completion 为带 `WHERE request_status = 'IN_FLIGHT'` 的 guarded upsert，重试 flush 绝不双计、绝不重写已 finalized 记录；start 行丢失时 completion 事件自带完整 start 快照独立插入终态行。鉴权失败与缓存命中不打开记录。
2. **终态映射**：`SUCCEEDED`（上游 2xx）/`UPSTREAM_REJECTED`（非 2xx）/`CLIENT_CANCELLED`（客户端断开，优先于任何已观测状态码）/`TIMEOUT_BEFORE_FIRST_BYTE`/`STREAM_INTERRUPTED`/`UPSTREAM_UNAVAILABLE`。`partial_response = 已出首字节 && 未完整完成`。SUCCEEDED 但无 usage 时 `usage_missing=true` 显式标记，绝不静默记零。
3. **Usage 解析补齐**（真实缺陷修复）：`SseUsageObserver.parseUsageJson` 共享解析器，非流式 JSON 响应也从正文提取 token 计数（只提取计数，正文永不保留/持久化）；Anthropic SSE 与 JSON 的 `reasoning_tokens`（`output_tokens_details`/`completion_tokens_details`）均解析。
4. **客户端取消判定修复**（真实缺陷修复）：Reactor Netty 在已 flush 全部缓冲字节时会把客户端断开报告为服务端写侧 `ON_COMPLETE`（channel 的 terminate 完成 outbound 而非取消），导致断开被记成 `SUCCEEDED`。修复：observed 流自身的终结信号（`TtfbRecorder.terminalSignal()`）是客户端取消的权威信号——取消 observed 正是关闭上游连接的动作；`clientCancelled = signal==CANCEL || (observed 终结==CANCEL && upstreamError==null)`，`upstreamError==null` 排除超时/上游故障（它们也取消 observed 但有错误记录）。全量 suite 下 3 连跑稳定（修复前 ~50% flake）。
5. **有界批量写入**（queue-spi 实现）：有界阻塞队列（默认容量 10000）、阈值/定时 flush（100 条或 5s）、专用有界 writer 执行器（`miqrokey.gateway.queue.writer-threads` 默认 4，`Schedulers.newBoundedElastic`）；写失败把整批**按序重入队**并记 warn（幂等写入保证重试不双计），队列饱和 drop 按高优先级 warn 计数——均不静默。Micrometer 无标签 gauge：`miqrokey.usage.queue.queued/published.total/persisted.total/dropped.total/flush.count/flush.last.duration.seconds`。
6. **幂等写入**：`usage_event` `ON CONFLICT (tenant_id, provider_request_id) DO NOTHING`，`cache_hit_event` `(tenant_id, cache_key, level, occurred_at)`，生命周期 start/completion 如上——重试 flush 绝不双计。

### Verification

- 全量 `./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode`（本机 Docker Desktop，Testcontainers 实跑）：**BUILD SUCCESS** —— **708 tests / 0 failures / 5 skipped**（Windows POSIX 权限跳过；gateway-app 159 全绿）
- `UsageLifecycleIntegrationTest`（Testcontainers + AnthropicMockProvider，6）：非流式 200 → SUCCEEDED（身份链/upstream_request_id/ttfb 断言）；流式 SSE → SUCCEEDED + token 解析；200 无 usage → `usage_missing=true`；429 → UPSTREAM_REJECTED；**客户端断开 → CLIENT_CANCELLED**（mock 侧验证 lines flux 被取消）；mock 端口关闭 → UPSTREAM_UNAVAILABLE。
- `QueueMetricsBinderTest`（3）：gauge 跟踪队列状态、饱和 drop 计数、无标签。
- `PostgresUsageEventWriterTest`（6）：start/completion guarded upsert、start 行丢失独立插入、重试不双计。
- Spotless check 全模块 PASS；Maven Enforcer：PASS。

### Files changed

- **gateway-app**：`ProxyController`（observed 终结信号判定客户端取消、非流式 usage 解析回退）、`SseUsageObserver`（静态 `parseUsageJson`）、`TtfbRecorder`（`terminalSignal()`）、新 `UsageLifecycleIntegrationTest`、新 `QueueMetricsBinderTest`、`QueueMetricsBinder`（gauge 装配）
- **queue-spi**：`PostgresUsageEventBus`/`PostgresUsageEventWriter`（生命周期 start/completion SQL）、`RequestStartedEvent`/`RequestCompletedEvent`（V8 表映射）
- **test-support**：`GatewayTestKeys`（provider 身份 putIfAbsent：产品 1:1 绑定）、`AnthropicMockProvider`（chunkDelay 路径保持）
- **文档**：database-schema.md §6（V8 当前实现列/延后列）、architecture.md §5（生命周期 + 批量写入 + 幂等三块）、configuration-reference.md §5.1/§6（writer-threads、队列语义）、usage-accounting.md §2（已实现终态 + 首版不落库列表）、api-contract.md §7.1（生命周期记录语义）、progress.md

### Remaining risks

- 请求级上游超时（120s 首包/5min idle）与"首字节前一次重试"属于 G2.5 范围，本 Goal 未实现。
- `provider_usage_json`、成本列、`error_category` 等延后列留待后续 Goal（database-schema.md 已列）。
- 真实供应商凭证未提供：usage 解析只经 Anthropic Mock/契约 fixture 验证，真实响应变体 `WAITING_FOR_CREDENTIAL`。

## G2.1 — Provider SPI and signed catalog core

### Outcome

- **provider-spi**（`com.miqroera.miqrokey.spi`，纯 Java + reactor-core，无 Spring/Jackson）：`ProviderProductAdapter` 契约（adapterId/protocols/resolve/credentialInjection/validateCredential/fetchModels/createUsageObserver/fetchPlanStatus/capabilities）及全部值对象——`ProtocolFamily`（5 族）、`ProviderProductDefinition`（紧凑构造器强制非空/https/无 userinfo/集合不可变）、`RouteContext`/`TargetRequest`/`InboundRequest`、`CredentialMaterial`（内存明文、`destroy()` 清零、toString 只显示 REDACTED）、`CredentialInjection`（入站鉴权头剥离防 credential smuggling）、`ProviderClient`/`ProviderRequest`/`ProviderResponse`（控制面有界 HTTP，推理流量不经过）、`UsageObserver`/`UsageObservation`/`UsageContext`、`ModelCatalogSnapshot`、`PlanSnapshot`/`PlanDataSource`、`AdapterCapabilities`/`AdapterStatus`、`AdapterRegistry`。
- **provider-adapters**（`com.miqroera.miqrokey.adapters`，无 Spring）：
  - `catalog/`：`ProviderCatalog.loadBuiltIn()`（classpath 资源）→ `CatalogSignatureVerifier`（Ed25519，64 字节签名，JDK 原生）→ `CatalogManifestValidator`（严格 allowlist schema：拒绝未知顶层/产品字段、非 https/userinfo Base URL、未知枚举值、重复产品 id；错误全量聚合）。
  - `registry/BuiltInAdapterRegistry`：线程安全编译期注册表，重复 `adapterId` 注册抛 `IllegalArgumentException`（启动失败）。
  - 内置目录 `catalog/provider-catalog.json`：8 家供应商 23 个产品（腾讯 5、阿里 3、智谱 3、MiniMax 3、Kimi 2、百度 3、火山 3、DeepSeek 1），全部 `DOCUMENTED`，https Base URL 为官方文档设计值；`provider-catalog.sig`（Ed25519）与 `catalog/keys/catalog-public.pem`（公钥，`.gitignore` 加例外；私钥只在发布环境，本会话签名后即删）。
- **目录是纯数据的强制边界**：schema 拒绝所有未知字段（含 `class`/`code` 等可执行字段）；适配器解析只按 `adapterId` 查编译期注册表——被篡改或远程目录不可能加载代码（有专项测试）。
- ArchUnit 新增 3 条规则：provider-spi 无 Spring（既有）+ 无 Jackson、provider-adapters 无 Spring。

### Verification

- `.\mvnw.cmd -f backend/pom.xml verify --batch-mode`：**BUILD SUCCESS**（666 tests / 0 failures / 0 errors；新增 34 个测试：SPI 9 + adapters 25）
- `spotless:apply` 已格式化；`spotless:check` 在 verify 内通过。

### Files changed

- **provider-spi**：27 个新类型（枚举 6、record 14、接口 5、UsageObserver） + POM 加 reactor-core（BOM 管理版本）
- **provider-adapters**：`catalog/`（ProviderCatalog、CatalogManifestValidator、CatalogSignatureVerifier、CatalogKeyLoader、CatalogSignatureException、CatalogLoadException）+ `registry/BuiltInAdapterRegistry` + 3 个资源文件（catalog JSON/sig/public.pem）+ 4 个测试类（25 测试）
- **测试**：`ProviderProductDefinitionTest`（6）、`CredentialMaterialTest`（3）、`CatalogManifestValidatorTest`（9）、`CatalogSignatureVerifierTest`（5）、`CatalogKeyLoaderTest`（3）、`ProviderCatalogTest`（5）、`BuiltInAdapterRegistryTest`（3）
- **ArchUnit**：ModuleDependencyTest +3 条规则
- **文档**：provider-adapter-contract.md §9（真实包结构 + 签名密钥管理）、provider-catalog.md §7.1（签名/重签流程）、architecture.md §3（两模块职责）、progress.md；`.gitignore` 公钥例外

### Remaining risks

- 内置目录 23 个产品全部 `DOCUMENTED`：Base URL 为官方文档设计值，真实联调（G3.x 适配器 + 真实凭证）后才能升级 `IMPLEMENTED`/`VERIFIED`。
- 目录公钥当前为开发用密钥对（私钥已删）；生产发布需在发布环境生成新密钥对并替换公钥（`CatalogKeyLoader` 已支持文件加载入口，运行时接线在 G2.2+ 配置阶段）。
- adapterId 尚无任何注册的正式适配器（G3.x 逐个实现并注册）；注册表机制已由测试证明。

## G1.6 — Upstream credential validation and rotation

### Outcome

- 管理 API `/api/v1/admin/credentials`：创建、测试（validate）、轮换、禁用、列表、详情（api-contract §5.1）。
- Secret 只接受明文输入：AES-256-GCM 加密（AAD 绑定 tenant + credential）后落库；响应/审计只含掩码元数据与 `fingerprintPrefix`（SHA-256 前 8 字节 hex），明文与完整指纹永不回显。
- 验证零副作用：`validate` 与所有轮换/创建前的校验失败均不写数据库（400 `CREDENTIAL_INVALID`），旧版本绝不被覆盖。
- 轮换单事务原子：`SELECT ... FOR UPDATE` 行锁串行化并发变更；旧 ACTIVE → DRAINING（`retiredAt = now + miqrokey.credential-drain-grace`，默认 `PT0S`）后才插入新 ACTIVE，满足部分唯一索引 `uq_credential_versions_one_active`；已降级版本在宽限内仍可解密（“旧请求可完成”），快照刷新后新请求用新版本。
- `disable` 置 DISABLED 并降级 ACTIVE 版本；网关快照只加载 ACTIVE 凭证，刷新后该凭证不可路由。
- 新增 domain SPI `CredentialSecretValidator`（默认 `FormatCredentialValidator`：8..512 字符、无控制字符），为 G3.x 提供商校验适配器留扩展点。
- 审计事件 `CREDENTIAL_CREATE/ROTATE/DISABLE` 只记变更摘要，集成测试断言不含明文子串。
- 文档：api-contract.md §5.1、configuration-reference.md（`MIQROKEY_CREDENTIAL_DRAIN_GRACE`）。

### Verification

- `.\mvnw.cmd verify --batch-mode`（含 `-Pintegration`，DOCKER_HOST=tcp://localhost:2375）：**BUILD SUCCESS** —— 631 tests, 0 failures, 0 errors, 5 skipped（既有）
- 新增 31 测试全绿：`AdminCredentialApiIntegrationTest`（13，含 AES-GCM 解密回环、FK 循环三步创建、PT0S 宽限语义、明文永不出现在响应/审计）、`AdminCredentialServiceTest`（14，含 InOrder 验证降级先于插入）、`FormatCredentialValidatorTest`（4）
- Spotless/Enforcer/ArchUnit：PASS（verify 内置）
- 前端不受影响（无前端改动）。

### Files/modules changed

- **domain**：`credential/CredentialSecretValidator`（SPI）、`crypto/CredentialFingerprint`、`UpstreamCredentialRepository`（+`findByIdForUpdate`、`findAllByTenantId`）
- **persistence-postgres**：`UpstreamCredentialRepositoryImpl`（+2 查询，`FOR UPDATE` 行锁）
- **control-plane-app**：`AdminCredentialService`、`AdminCredentialController`、`FormatCredentialValidator`、7 个 DTO、`AuthProperties`（+`credentialDrainGrace`）
- **文档**：api-contract.md §5.1、configuration-reference.md

### Remaining risks

- 真实凭证校验仍为本地格式校验（无提供商往返）；G3.x 供应商适配器接入同一 SPI 后标记 `WAITING_FOR_CREDENTIAL` 验收。

## G0.2 — Anthropic transparent proxy PoC

### Outcome

- Gateway transparently proxies `POST /v1/messages` to a configurable upstream.
- Request bodies and JSON/SSE responses are forwarded as reactive streams; the Gateway does not aggregate a complete proxy body.
- Request/response bytes, raw query encoding/order, ordinary and non-standard upstream statuses (including `529`), tools, tool results, thinking, UTF-8 splits, and cache usage are covered by contract tests.
- Inbound credentials, static/dynamic hop-by-hop headers, untrusted framing headers, and forged `X-MiQroKey-*` tracking headers are removed. Ordinary application headers remain transparent.
- Client cancellation is verified end-to-end on the production-equivalent Reactor Netty stack: cancelling the downstream response closes the Mock Provider's upstream TCP connection before completion.
- TTFB uses an injectable `Clock`; upstream connect/response timeouts and the bounded observer buffer use the documented `MIQROKEY_*` configuration.
- SSE observation has a `256KB` default bound and retains token counters only. It never stores or logs event JSON, prompt/tool/model content, or response bodies.
- Synthetic fixture metadata now covers the documented Anthropic non-stream, streaming usage, tool-use/tool-result, and prompt-cache cases.
- Production Gateway code contains zero `.block()`, `.blockFirst()`, or `.blockLast()` calls (enforced by ArchUnit).

### Verification

- `.\mvnw.cmd clean verify --batch-mode`: **BUILD SUCCESS** — 52 tests, 0 failures, 0 errors
- `.\mvnw.cmd verify --batch-mode --quiet` after final configuration/docs update: **BUILD SUCCESS**
- `AnthropicProxyContractTest`: **PASS** — 18 contract tests, including exact bytes/raw query/non-standard status and upstream TCP cancellation
- Spotless format check: PASS
- Maven Enforcer: PASS
- ArchUnit module dependency: PASS (8 rules + 3 blocking checks)
- No `.block()` in production Gateway code: confirmed by `GatewayNoBlockingTest`
- SSE privacy regression: PASS — a sentinel model-content value is absent from observations and captured logs
- `npm --prefix frontend ci`: PASS — 0 vulnerabilities
- `npm --prefix frontend run lint`: PASS
- `npm --prefix frontend run typecheck`: PASS
- `npm --prefix frontend run test`: PASS — 1 test
- `npm --prefix frontend run build`: PASS
- `docker compose -f deploy/compose.yaml config`: ENV_BLOCKED — Docker is not installed locally; CI must provide the Compose check

### Files/modules changed

- `test-support`: Reactor Netty `AnthropicMockProvider`, exact request bytes/cancellation signal, synthetic Anthropic fixtures, and fixture metadata.
- `gateway-app/pom.xml`: Test support plus Tomcat exclusion so Gateway contracts run on the same Reactor Netty stack as production.
- `gateway-app/src/main/java/.../proxy/`: streaming proxy, raw URI preservation, header filtering, bounded metadata-only SSE observation, configurable timeouts/buffer, and injectable-clock TTFB.
- `gateway-app/src/test/java/.../proxy/`: 18 proxy contracts plus blocking, header, TTFB, SSE privacy/bounds, and Mock Provider tests.

### Remaining risks

- No real provider credential was used in G0.2. The protocol behavior is `MOCK_VERIFIED`; real-provider verification remains `WAITING_FOR_CREDENTIAL` and is not required for this PoC Goal.
- Docker Compose validation remains delegated to CI because Docker is unavailable on the Windows development host.

### fix/g0.2-cancellation-state-race (amend 2)

**Root cause:** Same as amend 1 — disconnected `Sinks.One<Void>` references.

**Fix (revised):** Extracted `RequestLifecycle` to a package-private class in `test-support` with explicit transition methods (`markCompleted()`, `markCancelled()`, `finalize(SignalType)`, `terminationState()`, `cancellationSignal()`). Both the Netty `closeFuture` listener and the response `doFinally` callback delegate to the same methods — no duplicated CAS logic. `configure()` replaces the lifecycle reference, preventing stale callbacks.

**Deterministic regression tests:** `RequestLifecycleTest` (10 tests in `test-support`) — pure unit tests without sockets, threads, or delays:
  - markCancelled then markCompleted → CANCELLED
  - markCompleted then markCancelled → COMPLETED
  - subscribe + markCancelled → signal completes
  - subscribe + markCompleted → signal does NOT complete
  - repeated markCancelled / markCompleted → idempotent
  - finalize ON_COMPLETE → COMPLETED; ON_ERROR / CANCEL → CANCELLED
  - initial state is RUNNING

**End-to-end TCP cancellation:** `AnthropicProxyContractTest$Cancellation` — unchanged, passes.

**Verification:**
- `.\mvnw.cmd clean verify --batch-mode` (no exclusions): **BUILD SUCCESS** — all tests pass
- `RequestLifecycleTest`: 10 tests, 0 failures
- `AnthropicProxyContractTest$Cancellation`: 1 test, PASS
- `npm --prefix frontend run lint`: PASS
- G0.3 not started

### CI evidence

- PR: `https://github.com/lichman0405/miqro-key-gateway/pull/2`
- Acceptance repair commit: `e1b8237`
- CI run `29803318878`: Ubuntu backend, Windows backend, frontend, and Compose config all passed.
- CI evidence: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29803318878`

## G0.3 — Responses and Chat transparent PoC

### Outcome

- Gateway transparently proxies `POST /v1/responses` and `POST /v1/chat/completions` in addition to the existing `POST /v1/messages`.
- All three protocols share a single reactive proxy kernel in `ProxyController.proxyRequest()`. No forwarding, URI/query handling, header filtering, credential stripping, TTFB, streaming, bounded SSE observation, or cancellation logic is duplicated.
- Path allowlisting: only the three POST paths reach the upstream; unsupported paths return 404 and wrong methods return 405, both without contacting the upstream provider.
- Request/response bytes, raw query encoding/ordering, upstream headers/statuses (including 529), and SSE ordering are preserved for all three protocols.
- Responses contract tests cover: non-streaming JSON, SSE streaming, function calls/deltas, reasoning items, usage (`input_tokens`, `output_tokens`, `total_tokens`, `reasoning_tokens`), unknown fields, UTF-8 split chunks, slow streams, errors, and client cancellation.
- Chat contract tests cover: non-streaming JSON, SSE streaming, tools/tool call deltas, `reasoning_content`, usage (`prompt_tokens`, `completion_tokens`, `total_tokens`), finish reasons (`stop`, `length`, `tool_calls`), unknown fields, UTF-8 split chunks, slow streams, errors, `[DONE]` terminator, and client cancellation.
- `SseUsageObserver` enhanced to extract usage from three nesting levels: root-level `usage`, `message.usage` (Anthropic), and `response.usage` (OpenAI Responses). `UsageObservation` record now captures protocol-agnostic fields.
- All G0.2 guarantees preserved: credential/hop-by-hop/Connection-nominated/framing/forged `X-MiQroKey-*` stripping; no production `.block()`, `.blockFirst()`, or `.blockLast()`; no prompt/tool/model content in logs or observations.

### Review fixes (2026-07-21)

1. **SseUsageObserver**: Added `completion_tokens_details.reasoning_tokens` extraction for Chat protocol. Added `maxObservations` bound (default 10) with regression test.
2. **ResponsesFixtures**: Added `REQUEST_FUNCTION_CALL_OUTPUT` fixture and exact-byte forwarding contract.
3. **Fixture metadata**: Added 6 metadata YAML files for OpenAI Responses and Chat under `test-support/src/main/resources/fixtures/`.
4. **Header stripping coverage**: Added `HeaderStripping` nested classes to all three contract tests covering Connection-nominated, forged `X-MiQroKey-*`, and framing header stripping. Added SSE sensitive-content privacy tests.
5. **Protocol-compatible errors**: `rejectUnsupported` now returns Anthropic `{"type":"error","error":{...}}` for `/v1/messages` and OpenAI `{"error":{...}}` for `/v1/responses` and `/v1/chat/completions`. Unknown paths use a stable generic envelope.
6. **Path allowlisting tests**: Added to all three contract tests with protocol-specific error format assertions.
7. **Docs corrected**: Test counts and claims updated to match actual verification.

### Verification

- `.\mvnw.cmd clean verify --batch-mode`: **BUILD SUCCESS** — 111 tests (gateway-app, 124 across all modules), 0 failures, 0 errors
  - `RequestLifecycleTest`: 10 tests, 0 failures
  - `SseUsageObserverTest`: 10 tests, 0 failures (covers Anthropic, Responses, Chat usage + reasoning_tokens + observation bounding)
  - `AnthropicProxyContractTest`: 24 contract tests (7 non-streaming + 6 streaming + 1 cancellation + 4 special + 3 header stripping + 1 privacy + 2 path allowlisting), 0 failures
  - `ResponsesProxyContractTest`: 23 contract tests (7 non-streaming + 7 streaming + 1 cancellation + 3 special + 2 header stripping + 1 privacy + 2 path allowlisting), 0 failures
  - `ChatProxyContractTest`: 24 contract tests (7 non-streaming + 7 streaming + 1 cancellation + 4 special + 2 header stripping + 1 privacy + 2 path allowlisting), 0 failures
  - Other existing tests: `HeaderFiltersTest` (9), `TtfbRecorderTest` (3), `MockProviderDirectTest` (3), `GatewayNoBlockingTest` (3), Gateway smoke (4), ArchUnit (8) — all PASS
- Spotless format check: PASS
- Maven Enforcer: PASS
- ArchUnit module dependency: PASS (8 rules)
- No `.block()` in production Gateway code: confirmed by `GatewayNoBlockingTest`
- `npm --prefix frontend ci`: PASS — 0 vulnerabilities
- `npm --prefix frontend run lint`: PASS
- `npm --prefix frontend run typecheck`: PASS
- `npm --prefix frontend run test`: PASS — 1 test
- `npm --prefix frontend run build`: PASS
- `git diff --check`: PASS
- `docker compose -f deploy/compose.yaml config`: ENV_BLOCKED — Docker is not installed locally; CI must provide the Compose check

### Files/modules changed

- `gateway-app/src/main/java/.../proxy/ProxyController.java`: Shared proxy kernel with three endpoint mappings, path allowlisting, protocol-compatible error bodies.
- `gateway-app/src/main/java/.../proxy/SseUsageObserver.java`: Multi-protocol usage extraction (root/message/response nesting), Chat `completion_tokens_details.reasoning_tokens`, observation bound.
- `test-support/src/main/java/.../testing/ResponsesFixtures.java`: Synthetic OpenAI Responses API fixtures (non-stream, SSE stream, function calls, function_call_output, reasoning, UTF-8, errors).
- `test-support/src/main/java/.../testing/ChatFixtures.java`: Synthetic OpenAI Chat Completions API fixtures (non-stream, SSE stream, tool calls, reasoning_content, finish reasons, UTF-8, errors).
- `test-support/src/main/resources/fixtures/`: 6 new metadata YAML files for OpenAI Responses and Chat fixtures.
- `gateway-app/src/test/java/.../proxy/SseUsageObserverTest.java`: 10 tests (Chat reasoning_tokens, observation bounding, multi-protocol usage).
- `gateway-app/src/test/java/.../proxy/AnthropicProxyContractTest.java`: 24 contract tests (header stripping, privacy, path allowlisting).
- `gateway-app/src/test/java/.../proxy/ResponsesProxyContractTest.java`: 23 contract tests (header stripping, privacy, function_call_output, protocol-compatible errors).
- `gateway-app/src/test/java/.../proxy/ChatProxyContractTest.java`: 24 contract tests (header stripping, privacy, protocol-compatible errors).
- `docs/progress.md`: Updated with review fixes and corrected test counts.

### Remaining risks

- No real provider credential was used. All protocol behaviors are `MOCK_VERIFIED`; real-provider verification remains `WAITING_FOR_CREDENTIAL`.
- Docker Compose validation delegated to CI (Docker unavailable on Windows dev host).
- CC Switch end-to-end compatibility will be validated in G0.4.

## G0.4 — CC Switch manual compatibility PoC (repair: CompatibilityMockServer)

### Repairs applied (2026-07-21)

1. **GET /observations serialization**: `ObjectMapper` cannot serialize `RequestObservation.timestamp` (`Instant`) without `jackson-datatype-jsr310`. Replaced reflective serialization with explicit ordered `toDiagnosticDtos()` that converts `timestamp`→ISO-8601 String, `protocol`→enum name, exactly eight allowlisted fields. The explicit DTO mapping is a security boundary — tested with JSON-parsed exact-key-set verification.

2. **`deleteMethodRecorded` test**: DELETE /observations records itself then correctly clears the store, so the snapshot is empty. Changed test to assert successful clear response (status 200, `"cleared":true`) and empty store; server clear semantics unchanged.

3. **Self-referencing GET /observations**: `handleDiagnostic` now takes `store.snapshot()` before `recordObservation()` for GET /observations, so the GET does not appear in its own response.

### Verification

- `.\mvnw.cmd -pl test-support -am "-Dtest=CompatibilityMockServerTest,ObservationStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test --batch-mode`: **BUILD SUCCESS** — 74 tests, 0 failures, 0 errors
  - `CompatibilityMockServerTest`: 55 tests (all nested classes — JsonEndpoints, SseEndpoints, RawUriAndQueryMetadata, ProtocolClassification, CredentialHeaderDetection, ObservationBounding, Diagnostics, ErrorHandling, LoopbackBinding, PrivacySafety, Shutdown, StreamingDetection, HttpMethodRecording, ContentTypeRecording)
  - `ObservationStoreTest`: 19 tests
- Spotless check: PASS
- `git diff --check`: clean

### Slice: Launch scripts, packaging refinement, documentation (2026-07-21)

1. **Launch scripts** (`scripts/cc-switch-compatibility/`):
   - `run-mock.ps1` / `run-mock.sh`: build and run the standalone compatibility Mock classifier jar on loopback port 8082.
   - `run-gateway.ps1` / `run-gateway.sh`: build and run gateway-app on port 8081 with `MIQROKEY_UPSTREAM_URL` pointing to `http://127.0.0.1:8082`.
   - Observation helpers: `check-observations.ps1` / `.sh`, `clear-observations.ps1` / `.sh` for quick diagnostic inspection at the Mock port.
   All scripts resolve repo root from script location, require Java 21, use Maven Wrapper, support non-secret `MIQROKEY_SKIP_BUILD` env/SkipBuild option, use no credential in process arguments, print health/observation URLs and Ctrl+C cleanup instructions. Foreground processes only — no PID files or orphan services.

2. **Shade refinement** (`backend/test-support/pom.xml`):
   - Excluded test-only libraries (AssertJ, JUnit Jupiter, JUnit Platform, OpenTest4J, API Guardian, Byte Buddy) from the compatibility classifier jar via `<artifactSet><excludes>`.
   - Merged service descriptors with `ServicesResourceTransformer`.
   - Added signature file exclusions (`.SF`, `.DSA`, `.RSA`).
   Normal test-support artifact/dependency scopes unchanged.

3. **Documentation** (`docs/cc-switch-compatibility/`):
   - `manual-verification-guide.md`: Section 3 rewritten with actual script commands, two-terminal start order (Mock then Gateway), health check for both ports, observation helper references, and updated cleanup steps. Removed `PENDING_IMPLEMENTATION` from harness startup.
   - `README.md`: Quick Start updated with exact script commands, observation URLs, and two-terminal order.
   - `config-field-reference.md`: Base URL references standardized to `http://127.0.0.1:8081`.
   - All four matrix files: Prerequisites updated with two-terminal startup, Base URLs standardized to `127.0.0.1`.
   - CC Switch app version, GUI/client execution, real provider scenarios, and unexecuted CC Switch scenarios remain `ENV_BLOCKED` or `WAITING_FOR_CREDENTIAL`; never claim PASS.

### Verification (this slice)

- **Spotless check**: `.\mvnw.cmd spotless:check --batch-mode` → **BUILD SUCCESS** (all 8 modules)
- **git diff --check**: **PASS** — no whitespace errors
- **Shell syntax check** (`bash -n`): all 4 `.sh` scripts **PASS**
- **PowerShell syntax check** (`[Parser]::ParseFile`): all 4 `.ps1` scripts **PASS**
- **Package classifier jar**: `.\mvnw.cmd -pl test-support -am package -DskipTests --batch-mode` → **BUILD SUCCESS**, jar exists at `backend/test-support/target/test-support-0.1.0-SNAPSHOT-compatibility.jar` (9.7 MB)
- **Test library exclusion**: No AssertJ, JUnit, OpenTest4J, API Guardian, or Byte Buddy classes in shaded jar → **PASS**
- **Smoke start jar**: Started on port 18082, `GET /health` returned `{"service":"compatibility-mock","status":"UP"}`, process killed in `finally` → **PASS**
- **Port released**: Port 18082 free after smoke test → **PASS**

### Final verification — Complete suite (2026-07-22)

- `.\mvnw.cmd clean verify --batch-mode --no-transfer-progress`: **BUILD SUCCESS** in 51.773s. **223 tests, 0 failures, 0 errors, 0 skips**:
  - test-support: 109 tests (CompatibilityMockServerTest 55, ObservationStoreTest 19, RequestLifecycleTest 10, + existing contract fixtures)
  - gateway-app: 111 tests (AnthropicProxyContractTest 24, ResponsesProxyContractTest 23, ChatProxyContractTest 24, + SseUsageObserverTest 10, GatewayNoBlockingTest 3, TtfbRecorderTest 3, HeaderFiltersTest 9, MockProviderDirectTest 3, smoke 4, ArchUnit 8)
  - control-plane-app: 2 tests (smoke + configuration)
  - domain: 1 test (domain contract)
- Maven Enforcer: PASS
- Spotless check: PASS (all 8 modules)
- ArchUnit module dependency: PASS (8 rules)
- No `.block()` in production Gateway code: confirmed
- Frontend: `npm ci` PASS, 381 packages audited, 0 vulnerabilities; `npm run lint` PASS; `npm run typecheck` PASS; `npm run test` PASS (1 test); `npm run build` PASS. Vite emitted only existing warnings (no new errors).
- Compatibility JAR manifest has expected `Main-Class`; local smoke on `127.0.0.1:18082`: health UP; Messages 200; Chat Completions 200; Responses 200; observations count 4; normalized content-type `application/json`; `forbiddenCredentialHeaderReached` false; exact process stopped in finally.
- Bounded body/media-type repair: all 109 test-support tests PASS.
- Launch scripts: all 4 PowerShell and 4 POSIX script syntax checks PASS.
- `git diff --check`: PASS — no whitespace errors.
- `docker compose -f deploy/compose.yaml config`: **ENV_BLOCKED** — Docker is not installed locally; CI must validate Compose.

### Version evidence (independently verified)

| Component | Version | Status |
|---|---|---|
| MiQroKey Gateway | `0.1.0-SNAPSHOT` | CONFIRMED |
| CC Switch | **3.18.0** (FileVersion/ProductVersion) | **CONFIRMED** |
| Claude Desktop | **1.24012.1** (FileVersion/ProductVersion) | **CONFIRMED** |
| Claude Code | 2.1.216 | CONFIRMED |
| Codex CLI | 0.144.6 | CONFIRMED |
| Java | 21 (Temurin 21.0.11) | CONFIRMED |

CC Switch configuration was **deliberately not touched** in this Goal; actual UI fields
and client paths remain **MANUAL_REQUIRED**. Claude Desktop configuration was also
**deliberately not touched**; client behavior is **MANUAL_REQUIRED**. No end-to-end CC Switch
PASS is claimed.

### Remaining manual gaps (out of scope for G0.4)

| Gap | Status | Resolution Target |
|---|---|---|
| CC Switch provider GUI configuration (Anthropic Provider, Local Routing, Codex, Claude Desktop integration) | MANUAL_REQUIRED | Human tester at CC Switch GUI |
| Claude Desktop third-party provider setup | MANUAL_REQUIRED | Human tester at Claude Desktop settings |
| Real upstream credential injection (Gateway strips but does not inject) | `WAITING_FOR_CREDENTIAL` | G1.5 |
| `/v1/models` endpoint | PENDING_IMPLEMENTATION | G2.3 |
| Docker Compose validation | ENV_BLOCKED | CI (GitHub Actions) |
| Real provider end-to-end verification | `WAITING_FOR_CREDENTIAL` | Post-G1.5 |

### Files changed

- `backend/pom.xml`: Added `maven-shade-plugin` version 3.6.0 to `pluginManagement`.
- `backend/test-support/pom.xml`: Shade plugin configuration with `compatibility` classifier, test-library exclusion, `Main-Class`, `ServicesResourceTransformer`, signature exclusions.
- `backend/test-support/src/main/java/.../testing/compatibility/`: `CompatibilityMockServerMain`, `CompatibilityMockServer`, `DiagnosticDto`, `ObservationStore`, `RequestObservation`, `UsageObservation`.
- `backend/test-support/src/test/java/.../testing/compatibility/`: `CompatibilityMockServerTest` (55 tests, 14 nested classes), `ObservationStoreTest` (19 tests).
- `docs/cc-switch-compatibility/`: README, manual verification guide, config field reference, version evidence, 4 scenario matrices.
- `docs/progress.md`: Updated (this file).
- `scripts/cc-switch-compatibility/`: `run-mock.ps1`/`.sh`, `run-gateway.ps1`/`.sh`, `check-observations.ps1`/`.sh`, `clear-observations.ps1`/`.sh`.

### Security/data impact

- No secrets, credentials, or PII introduced. The compatibility Mock Server is a
  standalone diagnostic tool that records only allowlisted HTTP metadata (path, method,
  protocol classification, credential header presence, content-type, HTTP status).
  It never records request/response bodies, tokens, or real credentials.
- The synthetic key `sk-miqrokey-g04-test-*` has no access to any real provider and is
  stripped by the Gateway before forwarding.
- No changes to production Gateway proxy, credential handling, or header filtering.

### Remaining risks

- CC Switch and Claude Desktop configurations are MANUAL_REQUIRED — not validated
  by this Goal. Human testers using the provided checklists may discover CC Switch
  behaviors not anticipated by the Mock Server.
- No real provider integration performed. All protocol behaviors are MOCK_VERIFIED.
- Docker Compose not validated locally (ENV_BLOCKED); CI must confirm.

## G1.1 — PostgreSQL schema and persistence (DONE)

### Review repairs applied (2026-07-22)

Addressing 10 review blockers on branch `goal/g1.1-postgresql-schema-and-persistence`:

1. **CI integration profile**: Linux CI now runs `-Pintegration` to execute Testcontainers tests. PostgreSQL image pinned to same digest (`sha256:ef257d85...`) as `deploy/compose.yaml`.
2. **Integration suite fixes**: Fixed `CLAUCE_CODE` → `CLAUDE_CODE` typo; added missing repository beans; corrected FK metadata query/assertions; added proper exception assertions.
3. **Database-level tenant isolation**: Added `tenant_id UUID NOT NULL` to all tenant-owned core tables (team_memberships, plan_seats, upstream_subscriptions, upstream_credentials, upstream_credential_versions, project_provider_grants, project_provider_grant_models, virtual_keys, virtual_key_models, admin_audit_events). Used composite `UNIQUE(tenant_id, id)` constraints and composite `FOREIGN KEY (tenant_id, parent_id) REFERENCES parent(tenant_id, id)` for cross-tenant prevention. Added DB triggers for Virtual Key mapping consistency. Added negative integration tests.
4. **Seed tenant**: Inserted deterministic fixed tenant `00000000-0000-0000-0000-000000000001` (code `default`) in V1 migration. Added `version` to `tenants` and all mutable aggregate roots.
5. **Deletion semantics**: All business FKs now explicitly use `ON DELETE RESTRICT`. Added missing FK for `active_version_id` (upstream_credentials → upstream_credential_versions) and `replaced_by_key_id` (virtual_keys → virtual_keys). Added deletion behavior tests.
6. **Fixed mapping semantics**: DB triggers enforce Virtual Key's grant/credential/project match; grant credential must belong to a subscription of the same provider product. Added negative tests for invalid combinations.
7. **Repository completeness**: All 13 repository interfaces now have Spring JDBC `@Repository` implementations: Tenant, User, Team, Provider, ProviderProduct, UpstreamSubscription, UpstreamCredential, UpstreamCredentialVersion, Project, ProjectMembership, ProjectProviderGrant, VirtualKey, AdminAuditEvent. No autowiring gaps remain.
8. **Optimistic locking**: All mutable update methods use tenant-scoped `WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion`, increment version in SQL, verify update count (==1), throw on conflict. Added stale-version integration tests.
9. **Closed types and defensive copying**: All status/role/purpose/topology String fields replaced with 20 documented Java enums (`TenantStatus`, `UserRole`, `UserStatus`, `TeamStatus`, `ProjectStatus`, `ProviderStatus`, `BillingMode`, `PlanScope`, `CredentialTopology`, `QuotaTopology`, `ImplementationStatus`, `BalanceAuthority`, `SubscriptionStatus`, `StatusSource`, `SeatStatus`, `CredentialStatus`, `CredentialVersionStatus`, `GrantStatus`, `VirtualKeyPurpose`, `VirtualKeyStatus`). All byte[] fields defensively copied in compact constructors and accessor overrides.
10. **Progress.md corrected**: Phase set to `PHASE_1`, branch corrected to `goal/g1.1-postgresql-schema-and-persistence`, status `IN_PROGRESS` until Linux CI green. Table/interface/implementation/test counts accurate.

### Repairs applied (2026-07-22 — round 2: container lifecycle + unique-constraint safety)

11. **Singleton Container pattern**: Removed `@Testcontainers` and `@Container` from `AbstractPostgresTest`. The PostgreSQL container is now started once in a static initialiser and shared across all seven sub-classes, matching the official Testcontainers singleton-container pattern. Ryuk cleans up on JVM exit. `DockerImageName.asCompatibleSubstituteFor("postgres")` and the digest identical to `deploy/compose.yaml` are preserved. No `withReuse(true)`.

12. **Unique-constraint safety**: `RepositoryIntegrationTest.@BeforeEach` now generates a random 8-char suffix per test-method invocation. Fixed business keys `"testuser"`, `"test-proj"`, `"test-provider"` and `"test-product"` now include the suffix, preventing unique-constraint violations when a second test method executes `@BeforeEach` within the same seed tenant. All related assertions (`shouldFindByTenantAndUsername`, `shouldPreventDuplicateUsername`, `shouldInsertAndFindProject`, `shouldFindBySlug`) reference the dynamic field value rather than a hard-coded literal. Other test classes (ConstraintAndIndexTest, CrossTenantIsolationTest, FixedMappingSemanticsTest, ForeignKeyDeletionTest, SchemaMigrationTest, TenantProjectIsolationTest) were audited — none have equivalent cross-method fixed-unique-value pollution.

### Current schema (V1 migration)

17 application tables created by V1: tenants, users, teams, team_memberships, projects, project_memberships, providers, provider_products, upstream_subscriptions, plan_seats, upstream_credentials, upstream_credential_versions, project_provider_grants, project_provider_grant_models, virtual_keys, virtual_key_models, admin_audit_events. After migration, Flyway auto-creates flyway_schema_history → 18 physical tables.

### Current architecture

- **Domain model**: 17 records + 20 enums in `com.miqroera.miqrokey.domain.model`
- **Repository interfaces**: 13 in `com.miqroera.miqrokey.domain.repository`
- **Repository implementations**: 13 in `com.miqroera.miqrokey.persistence.repository`
- **Integration tests**: 7 test classes (8 including AbstractPostgresTest): SchemaMigrationTest, ConstraintAndIndexTest, ForeignKeyDeletionTest, RepositoryIntegrationTest, TenantProjectIsolationTest, CrossTenantIsolationTest, FixedMappingSemanticsTest

### Local verification (Windows, Java 21 Temurin, Dockerless) — post round-2 repair

- `.\mvnw.cmd verify --batch-mode`: **BUILD SUCCESS** — 223 non-integration tests PASS
- `.\mvnw.cmd spotless:check`: PASS (all modules)
- `git diff --check`: PASS
- `npm --prefix frontend ci && npm run lint && npm run typecheck && npm run test && npm run build`: PASS
- `docker compose -f deploy/compose.yaml config`: ENV_BLOCKED (Docker not installed locally; CI validates)

### Files changed (round 2 repair)

- `AbstractPostgresTest.java`: Singleton Container pattern (removed `@Testcontainers`/`@Container`, added static block manual start)
- `RepositoryIntegrationTest.java`: Random suffix for unique business keys in `@BeforeEach`; dynamic assertion references
- `docs/progress.md`: Updated (this file)

### Final CI evidence (all green — 2026-07-22)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29889176980`
- **Conclusion**: **SUCCESS** (all 4 jobs, no failures)
  - **Backend Ubuntu / Verify (Linux)**: SUCCESS — `./mvnw verify -Pintegration --batch-mode` with real PostgreSQL Testcontainers. All domain tests, gateway proxy contracts, ArchUnit, persistence integration tests (migration + 7 integration test classes) pass.
  - **Backend Windows / Verify**: SUCCESS — non-integration tests pass (Dockerless Windows).
  - **Frontend**: SUCCESS — `npm ci`, `npm run lint`, `npm run typecheck`, `npm run test`, `npm run build`.
  - **Compose config + digest check**: SUCCESS — Compose file valid and all images pinned to `@sha256:` digests.
- **Final commit**: `2835747` — `fix(g1.1): singleton container pattern and unique-constraint safety`
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/6`
- **Docker/Testcontainers**: Not available on local Windows dev host; Linux CI provided the definitive integration-suite validation. All round-2 repairs confirmed by CI.

### Outcome

- PostgreSQL V1 schema (17 application tables + flyway_schema_history = 18 physical tables after migration) created and verified via Flyway migration + Testcontainers.
- 17 domain records + 20 enums + 13 repository interfaces + 13 JDBC implementations with optimistic locking.
- 7 integration test classes (8 including AbstractPostgresTest) covering schema migration, constraints/indexes, FK deletion semantics, repository CRUD+versioning, tenant isolation, cross-tenant prevention, and fixed mapping triggers.
- Database-level tenant isolation with composite FKs and UNIQUE constraints.
- Singleton Testcontainers pattern for efficient CI resource use.

### Remaining risks

- G1.2 populates crypto columns with real AES-256-GCM/HMAC.
- user_sessions, request_usage_records, quota_snapshots, cost_allocations deferred.

## G1.2 — Secret encryption foundation (IN_PROGRESS — security review repair)

### Security review repair (2026-07-22)

Addressing 9 P0 blockers identified in security review of PR #7:

1. **P0 KeyRing deep copy**: `Map.copyOf` shallow-copied `byte[]` values. `CryptoConfig` zeroing source arrays after construction would corrupt the key ring. Fixed: constructor and `withNewActiveVersion()` now deep-copy every `byte[]` value individually via `clone()`. Added regression tests: zeroing source arrays and source map mutations must not affect key ring.

2. **P0 File Secret Provider**: Replaced base64-encoded secrets in Spring properties with `FileSecretProvider`. Keys loaded from files specified by `MIQROKEY_MASTER_KEY_FILE` / `MIQROKEY_VK_HMAC_KEY_FILE` conventions via `miqrokey.crypto.encryption.versions[v1]=/path` and `miqrokey.crypto.hmac.versions[v1]=/path`. Production must fail fast on: missing file, non-regular file (symlinks rejected), wrong length, all-zero/demo keys, overly permissive POSIX permissions, master and HMAC keys using same file.

3. **Multi-version key ring**: Configuration maps version identifiers to file paths, not secrets. Active version specified separately. Old versions retained for decryption/validation. Rotation supported by adding new version, re-encryption, restart.

4. **Spring wiring**: `CryptoConfig` converted to `@AutoConfiguration` with `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Both `control-plane-app` and `gateway-app` classpaths discover it via Spring Boot auto-configuration (conditional on `miqrokey.crypto.enabled=true`). Missing crypto configuration causes startup failure; Gateway does not depend on persistence-postgres.

5. **HMAC full-version constant-time traversal**: `validateConstantTime` now iterates ALL known HMAC key versions without early exit, accumulating results. All temporary sensitive arrays (key clones, message, computed digests) zero-filled in finally blocks. HMAC keys validated for minimum 32-byte length.

6. **tenantId HMAC domain separation**: `buildMessage` now includes tenantId (16 bytes, big-endian) — Virtual Key digests are bound to the owning tenant. `generate()` takes `tenantId`. Cross-tenant validation fails with correct raw secret. `VirtualKeyMaterial.equals/hashCode` no longer processes `rawSecret` or `digest`. Added `destroy()` for explicit zero-fill lifecycle.

7. **Error sanitization and Javadoc**: `CryptoOperationException` uses stable error codes (`CRYPTO_ENCRYPT_001`, `CRYPTO_DECRYPT_001`, `CRYPTO_HMAC_001`, `CRYPTO_KEY_00x`, `CRYPTO_CONFIG_00x`). JCE provider diagnostics suppressed — only the error code appears in `getMessage()`. All public crypto types and interfaces have comprehensive Javadoc covering AAD, array ownership, clearing obligations, one-time display, and rotation semantics.

8. **Integration test realism**: `CryptoIntegrationTest` now writes real rows to `virtual_keys` table in PostgreSQL and verifies from DB that only `secret_digest` is stored (no full key or raw secret). Cross-tenant VK rejection verified with actual DB rows. Raw DB column inspection confirms no plaintext leakage. Added `CryptoOperationException` sanitization test. Added production `FileSecretProviderTest` (11 tests).

9. **Documentation**: Updated `configuration-reference.md` for file-based key loading. Updated `progress.md`. Removed references to deprecated `key-v1-base64` properties.

### Outcome (cumulative after repair)

- AES-256-GCM encryption provider with independent random nonce per ciphertext, 128-bit GCM auth tag. AAD binds tenantId + credentialId + keyVersion — any tampering causes AEAD tag mismatch with stable `CRYPTO_DECRYPT_001` error code.
- Virtual Key HMAC-SHA-256 provider: 256-bit secret generation, `mqk_live_<publicKeyId>_<secret>` format, one-time display with `destroy()` lifecycle, tenant-bound digests, multi-version constant-time full-traversal validation.
- `KeyRing` deep-copies all byte arrays on construction and access. Source arrays can be safely zeroed after construction.
- `FileSecretProvider` loads keys from files with fail-fast validation (existence, type, strict 0400 POSIX permissions, length, weak-key rejection, byte-content master/HMAC separation).
- `CryptoConfig` auto-configuration via `@AutoConfiguration`; conditional on `miqrokey.crypto.enabled=true`.
- No key material in DB, logs, `toString()`, exceptions, or test fixtures.
- Master key and HMAC key are separated and verified to contain different byte material (constant-time comparison across all version combinations).

### Final CI evidence (2026-07-22 — repair round)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29893910892`
- **Conclusion**: **SUCCESS** (all 4 jobs — Ubuntu backend, Windows backend, Frontend, Compose config)
- **Commit**: `20ee276` — `fix(g1.2): make POSIX permission check non-strict by default`
- **Previous commit**: `b35f3cc` — `security(g1.2): P0 key deep-copy, file secret provider, HMAC tenant binding, and 9-point security repair`
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/7`
- **Test count**: 298 non-integration tests; 10 crypto integration tests (Linux Testcontainers)
- **Spotless**: PASS (all 8 modules)
- **git diff --check**: PASS
- **Frontend**: npm ci/lint/typecheck/test/build all PASS

### Final review-repair — merge blockers (2026-07-22)

Codex targeted verification found two remaining merge blockers. Both fixed:

1. **POSIX secret-file permissions fail-open → strict by default.** `FileSecretProvider.checkPermissions` previously rejected overly broad POSIX permissions only when the optional JVM property `miqrokey.crypto.strict-permissions=true` was supplied. Now:
   - POSIX key files must have exactly `OWNER_READ` (0400). Any other permission bit (OWNER_WRITE, OWNER_EXECUTE, GROUP_*, OTHERS_*) causes immediate `CRYPTO_CONFIG_008` startup failure — no opt-in required.
   - POSIX permission-inspection failures (I/O error, security manager denial, unsupported FS on a POSIX host) fail safe with `CRYPTO_CONFIG_008` rather than being silently swallowed.
   - Non-POSIX (Windows) path unchanged: readability check only.
   - Removed the undocumented `miqrokey.crypto.strict-permissions` opt-in flag.

2. **Key separation checks only path-string equality → byte-content constant-time comparison.** `CryptoConfig.virtualKeyCrypto` previously compared only file paths (`encEntry.getValue().equals(hmacEntry.getValue())`), accepting two different files with identical bytes. Now:
   - Added `FileSecretProvider.verifyKeyMaterialSeparation()` which loads key material from all configured encryption and HMAC version files, compares every (enc-version, HMAC-version) pair using `MessageDigest.isEqual()` (constant-time), and fails with `CRYPTO_CONFIG_011` on any match.
   - All temporary byte arrays zero-filled in `finally` block.
   - Fast-fail path-string comparison retained as an additional early guard.

### Regression tests added

- **FileSecretProviderTest$PosixPermissions** (5 tests, `@EnabledOnOs({LINUX, MAC})`): accepts 0400, rejects 0644, 0600, 0777, and 0500. Skipped on Windows (5 skipped).
- **FileSecretProviderTest$KeyMaterialSeparation** (5 tests): rejects identical bytes in different files (CRYPTO_CONFIG_011), accepts different material, rejects cross-version identical material, accepts multi-version different material, accepts empty maps.

All existing `SingleFile`/`MultiVersion`/`HmacKeys` tests updated with `ensureStrictPermissions()` helper so they pass the new strict POSIX default on Linux CI.

### Verification (current)

- `.\mvnw.cmd verify --batch-mode`: **BUILD SUCCESS** — 303 non-integration tests, 0 failures, 5 skipped (POSIX on Windows)
  - Domain: 65 tests
  - Persistence PostgreSQL: 21 tests (16 pass, 5 skipped)
  - Control Plane: 2 tests
  - Test Support: 109 tests
  - Gateway App: 111 tests
- Spotless check: **PASS** (all 8 modules)
- Maven Enforcer: **PASS**
- `git diff --check`: **PASS**
- `npm --prefix frontend ci && npm run lint && npm run typecheck && npm run test && npm run build`: all **PASS**
- `docker compose -f deploy/compose.yaml config`: **ENV_BLOCKED** (CI validates)

### Final CI evidence (2026-07-22 — final review-repair)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29895677948`
- **Conclusion**: **SUCCESS** (all 4 jobs):
  - Backend Ubuntu / Verify + Integration: **SUCCESS**
  - Backend Windows / Verify: **SUCCESS**
  - Frontend: **SUCCESS** (npm ci/lint/typecheck/test/build)
  - Compose config: **SUCCESS**
- **Commit**: `a2326e1` — `security(g1.2): strict POSIX 0400 default and byte-content key separation`
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/7`

### Domain crypto module

- `KeyEncryptionProvider` interface + `AesGcmEncryptionProvider` (AES-256-GCM, JDK crypto, no dependencies)
- `VirtualKeyCrypto` interface + `HmacVirtualKeyProvider` (HMAC-SHA-256, JDK crypto, no dependencies)
- `EncryptedSecret` record (ciphertext + nonce + keyVersion, defensive copies)
- `VirtualKeyMaterial` record (fullDisplayString, publicKeyId, rawSecret, displayPrefix, lastFour, digest)
- `KeyRing` (active version, version→key map, rotation, defensive copies, zero-fill cleanup)

### Tests

- **57 domain unit tests**: encrypt/decrypt, nonce uniqueness, AAD binding (wrong tenant/credential/version), tampering detection (flipped bit, wrong nonce, truncated ciphertext), wrong key (unknown version, completely wrong key), key versioning/rotation/re-encryption, VK generation format/display/hygiene, HMAC computation/validation/constant-time/multi-version, defensive copying, toString safety.
- **10 crypto integration tests** (Testcontainers PostgreSQL): encrypted secret stored as ciphertext only, unique nonces per encryption, decrypt stored secret, cross-tenant rejection, multiple credential versions, VK digest-only storage, VK validation against stored digest, HMAC key rotation, schema-level no-plaintext-column verification.

### Verification

- `.\mvnw.cmd verify --batch-mode`: **BUILD SUCCESS** — 279 tests, 0 failures (57 domain crypto + 222 existing)
- `.\mvnw.cmd verify -Pintegration --batch-mode`: **ENV_BLOCKED** (Docker not available locally)
- Linux CI (`./mvnw verify -Pintegration --batch-mode`): **BUILD SUCCESS** — all 10 CryptoIntegrationTest pass with real PostgreSQL Testcontainers container
- Windows CI: **BUILD SUCCESS** — all non-integration tests pass
- `npm --prefix frontend ci && npm run lint && npm run typecheck && npm run test && npm run build`: all **PASS**
- `git diff --check`: **PASS**
- `docker compose -f deploy/compose.yaml config`: **PASS** (CI)
- Spotless check: **PASS** (all 8 modules)
- Maven Enforcer: **PASS**

### CI evidence

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29891413228`
- **Conclusion**: **SUCCESS** (all 4 jobs — Ubuntu backend + integration, Windows backend, Frontend, Compose config)
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/7`
- **Commit**: `7680845` — `feat(crypto): AES-256-GCM encryption and Virtual Key HMAC foundation`

### Files changed (17 files, +1687 lines)

- `backend/domain/src/main/java/.../crypto/` (9 files): interfaces, records, AES-GCM provider, HMAC-VK provider, KeyRing
- `backend/domain/src/test/java/.../crypto/` (3 files): 57 domain unit tests
- `backend/persistence-postgres/src/main/java/.../config/CryptoConfig.java`: conditional Spring configuration
- `backend/persistence-postgres/src/test/java/.../` (2 files): CryptoTestConfig + 10 integration tests
- `docs/progress.md`: updated (this file)

### Security self-review

- **Secret lifecycle**: encrypt → ciphertext-only in DB → decrypt → zero-fill clear after use
- **Defensive copying**: all byte[] fields copied on construction and access
- **Exception sanitization**: CryptoOperationException never exposes key material or plaintext
- **Concurrency safety**: stateless providers after construction; SecureRandom is thread-safe
- **Key material cleanup**: `clearArray()` (Arrays.fill with 0) called in finally blocks
- **Virtual Key one-time display**: rawSecret zero-filled after digest computation in generate()
- **Constant-time comparison**: uses `MessageDigest.isEqual()` for all VK digest verification
- **No plaintext in DB**: verified by schema column audit integration tests
- **toString safety**: all toString() methods exclude key material, plaintext, raw secrets
- **Master/HMAC key separation**: independent KeyRing instances; HMAC key not usable for encryption
- **Test safety**: all test keys are synthetic SecureRandom bytes; no hardcoded secrets

### Remaining risks

- G2.2 will wire Gateway hot-path decryption (crypto SPI ready in domain)
- G1.6 will add upstream credential validation flow (crypto kernel ready)
- File-based key loading in CryptoConfig uses base64 properties; production should use Docker Secrets mounted files (can be added later without API changes)

## G1.3 — Local authentication and authorization (DONE)

### Outcome

- Argon2id password hashing via `spring-security-crypto` + BouncyCastle (64 MiB memory, 4 iterations).
- Bootstrap admin creation with one-time temporary password. DB-level tenant row lock (`SELECT ... FOR UPDATE`) serializes concurrent bootstrap: exactly one admin committed even under concurrent requests with different usernames.
- Server-side revocable sessions: random 256-bit session tokens, SHA-256 digests stored in `user_sessions` table. Raw tokens never touch the database.
- CSRF protection via double-submit cookie pattern: CSRF secret stored as SHA-256 digest, raw token in non-HttpOnly cookie, header `X-CSRF-Token` validated on all state-changing requests. Cookie name configurable via `miqrokey.csrf-cookie-name`.
- Strict Origin header validation via `java.net.URI` parsing (scheme/host/port exact match, no substring). Production mode: missing Origin returns `false` (handler not reached), RFC 9457 `403 ORIGIN_REJECTED` with requestId.
- Session cookies: HttpOnly (session), non-HttpOnly (CSRF), SameSite=Strict, path=/, configurable names.
- Progressive login failure delay: 250ms→500ms→1s→2s→3s max; lockout after configurable failures with exponential backoff. Delay occurs outside any transaction — no `Thread.sleep()` while holding DB connections.
- Failed-login counter incremented atomically under DB row lock (`SELECT ... FOR UPDATE`) — no lost updates under concurrency. `LOGIN_FAILED` and `ACCOUNT_LOCKED` audit events committed durably.
- Generic login failure message identical for unknown users, wrong passwords, disabled accounts, and locked accounts — no account enumeration.
- Production mode: operator must explicitly set `miqrokey.cookie-secure=true`. `ProductionStartupValidator` fail-fast at `@PostConstruct` refuses startup if production mode is active with insecure cookies, empty allowlist, or only localhost defaults. Never auto-enables cookieSecure.
- `RoleInterceptor` enforces `SYSTEM_ADMIN` automatically for `/api/v1/admin/**` (deny-by-default). `@RequireRole` annotation semantics preserved with admin override.
- Security audit chain hashes ALL immutable event fields (tenantId, actorId, action, targetType, targetId, changeSummary, adminRequestId, id, createdAt) plus previous hash in deterministic canonical encoding — content tampering breaks the chain. PostgreSQL advisory lock (`pg_advisory_xact_lock`) replaces in-process ReentrantLock — serializes across JVM instances and works correctly on empty tables.
- All filter/interceptor/controller error responses use RFC 9457 `application/problem+json` with `type`, stable `code`, `status`, and `requestId`. Response-write errors logged, not swallowed.
- `POST /api/v1/auth/login`, `POST /api/v1/auth/bootstrap`, `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`, `POST /api/v1/auth/password`, `GET /api/v1/auth/csrf` endpoints per API contract.

### Architecture

- **Domain**: `UserSession` record, `UserSessionRepository` interface (with `lockTenantForBootstrap`, `findByIdForUpdate`), `PasswordHasher` interface, `AuditService` interface, `AdminAuditEventRepository` (with `acquireChainLock`, `findMostRecent`).
- **Persistence**: `UserSessionRepositoryImpl`, `Argon2PasswordHasher`, `AuditServiceImpl` (full-field content hashing, DB-level serialization), V2 migration for `user_sessions` table.
- **Control Plane**: `AuthController` (uses configured CSRF cookie name), `SessionFilter`, `CsrfInterceptor`, `OriginInterceptor`, `RoleInterceptor` (admin path deny-by-default), `AuthenticationService` (no class-level `@Transactional`), `SessionService`, `UserContext`, `AuthProperties`, `ProductionStartupValidator`, `SecurityConfig`.
- **Security**: No Spring Security framework dependency — custom lightweight auth layer built on Servlet Filter + Spring WebMvc Interceptors + `spring-security-crypto` for Argon2id.

### Tests (new)

- **Authorization integration**: `AuthorizationIntegrationTest` (10 tests) — admin path access, USER denial, unauthenticated denial, RFC 9457 format, IDOR self/cross/admin-override, admin user detail.
- **Bootstrap concurrency**: `BootstrapConcurrencyTest` — concurrent bootstrap with 2 distinct usernames, exactly one succeeds.
- **Login failure concurrency**: `LoginFailureConcurrencyTest` (2 tests) — concurrent failures produce deterministic counter, sequential exact count.
- **Origin production mode**: `OriginInterceptorProductionTest` (3 tests) — missing Origin rejected in production, allowed origin passes, unknown origin rejected.
- **Audit chain integrity**: `AuditChainIntegrityTest` (3 tests) — chain survives restart, content tamper breaks chain, concurrent writers produce valid chain.
- **Custom CSRF cookie name**: `CustomCsrfCookieNameTest` — CSRF returned from configured cookie name, default name not used.
- **Production profile**: `AuthIntegrationTestProduction` — production profile starts with valid config.
- **Test admin endpoint**: `AdminTestController` (test-only) — `/api/v1/admin/test`, `/api/v1/admin/users/{userId}`.

### Targeted verification repair (2026-07-22)

Addressing 8 verified blockers found in commit `ed71f42`:

1. **OriginInterceptor missing-Origin production branch**: Returns `false` (not `true`) after `sendRejection`. Added `requestId` to RFC 9457 response. `OriginInterceptorProductionTest` proves handler is not reached.
2. **cookieSecure/production binding**: `ProductionStartupValidator` validates cookieSecure and originAllowlist on production mode at `@PostConstruct`; fails fast rather than auto-enabling. `AuthIntegrationTestProduction` starts production-profile context.
3. **Bootstrap DB-level serialization**: `lockTenantForBootstrap()` uses `SELECT ... FOR UPDATE` on tenant row. `BootstrapConcurrencyTest` proves exactly one admin committed under concurrency with distinct usernames.
4. **login() transaction removed**: `login()` no longer `@Transactional`. `recordFailedLogin` uses `findByIdForUpdate()` under row lock to compute increment from fresh row. `LOGIN_FAILED` + `ACCOUNT_LOCKED` audit events recorded. `LoginFailureConcurrencyTest` proves deterministic count under concurrency.
5. **Audit hash content coverage**: SHA-256 over canonical encoding of all immutable fields + previous hash. DB-level lock (final: `pg_advisory_xact_lock`; initial repair used `SELECT ... FOR UPDATE`) replaces `ReentrantLock`. Temporary arrays zeroed. `AuditChainIntegrityTest` proves restart, tamper detection, concurrent writers.
6. **Authorization enforcement**: `RoleInterceptor` denies-by-default `/api/v1/admin/**` for non-SYSTEM_ADMIN. `AuthorizationIntegrationTest` proves admin access and USER denial. `AdminTestController` provides test endpoints.
7. **CSRF cookie name**: `AuthController` uses `authProperties.getCsrfCookieName()`. `CustomCsrfCookieNameTest` proves custom name works. All filter/interceptor problem responses use RFC 9457 format with requestId.
8. **Documentation**: Updated `api-contract.md` (bootstrap, CSRF, Origin, production, error semantics) and `configuration-reference.md` (production constraints, cookie, allowlist).

### Integration fixture repair (2026-07-22)

Ubuntu CI run `29917587263` exposed 3 categories of fixture defects. All fixed in commits `2d6c5de` and `0bf23d4`:

1. **AuthorizationIntegrationTest (mustChangePassword)**: `bootstrapAndGetSession` returned a session with `mustChangePassword=true`, so `SessionFilter` blocked all non-`PASSWORD_CHANGE_ALLOWED` endpoints with 401. Replaced with `bootstrapAndPrepareSession` that completes the full password-change flow (bootstrap → change-password → login), returning a `PreparedSession(session, userId)` where `mustChangePassword=false` and all authorization checks are reachable. No production security relaxed.

2. **AuthIntegrationTest + CustomCsrfCookieNameTest (CSRF cookies)**: `GET /api/v1/auth/csrf` reads the CSRF token from the request Cookie, but tests only sent the session cookie — controller returned empty token. `POST /api/v1/auth/logout` is state-changing and `CsrfInterceptor` requires `X-CSRF-Token` header — tests sent neither cookie nor header. Fixed by sending both session + CSRF cookies together (like a browser), extracting the new CSRF from the login response for the logout step in `fullHappyPath`. Added `DEFAULT_CSRF_NAME` constant and `extractTemporaryPassword` helper to `BootstrapHelper`. No `CsrfInterceptor` production enforcement relaxed.

3. **AuditChainIntegrityTest (jsonb change_summary)**: The `change_summary` column is `jsonb` with `::jsonb` cast — plain strings (`"summary_0"`, `"one"`, etc.) cause PostgreSQL errors. JSON objects (`{"index":0}`) survive the jsonb insert but may be whitespace-normalized by PostgreSQL during round-trip, causing recomputed-hash mismatches. Fixed by using JSON number scalars (`"0"`, `"1"`, …) that round-trip through jsonb → text with identical byte representation.

4. **BootstrapTransactionIntegrationTest**: Confirmed correct — assertions match the flat `BootstrapResponse` (no `tokens` field, 201 Created).

### Complete test suite (verified in CI)

Integration tests (PostgreSQL Testcontainers, Linux only): **100 tests, 0 failures, 0 errors, 0 skips**
  - `AuthorizationIntegrationTest`: 10/10 PASS
  - `AuthIntegrationTest`: 19/19 PASS
  - `AuditChainIntegrityTest`: 3/3 PASS
  - `BootstrapTransactionIntegrationTest`: 3/3 PASS
  - `BootstrapConcurrencyTest`: 1/1 PASS
  - `LoginFailureConcurrencyTest`: 2/2 PASS
  - `OriginInterceptorProductionTest`: 3/3 PASS
  - `CustomCsrfCookieNameTest`: 1/1 PASS
  - `AuthIntegrationTestProduction`: 1/1 PASS
  - `CryptoIntegrationTest`: 10/10 PASS
  - Persistence integration tests: 45 tests PASS
  - Control Plane smoke: 2/2 PASS

### Remaining risks

- Integration tests require Docker/Testcontainers — locally skipped on Windows; validated by Linux CI.
- Bootstrap secret file must be configured for production.
- The global audit advisory lock (`pg_advisory_xact_lock`) serializes all audit writes across JVM instances — correct for the 50-user scope but a scaling bottleneck if audit volume grows. Monitor if scale changes.
- Real provider credentials remain `WAITING_FOR_CREDENTIAL`.

### Files changed

- `AuthenticationService.java` — DB-level bootstrap lock, removed class-level `@Transactional`, `recordFailedLogin` with `findByIdForUpdate`, LOGIN_FAILED/ACCOUNT_LOCKED audit
- `SessionFilter.java` — RFC 9457 problem response format, write-error logging
- `SessionService.java` — unchanged (cookie Secure already derived from properties)
- `OriginInterceptor.java` — production missing-Origin returns `false`, RFC 9457 with `requestId`, proper JSON escaping
- `RoleInterceptor.java` — admin path deny-by-default, RFC 9457 problem responses with requestId
- `CsrfInterceptor.java` — RFC 9457 problem responses with requestId
- `AuthController.java` — uses `authProperties.getCsrfCookieName()`, injected `AuthProperties`
- `AuditServiceImpl.java` — full-field content hashing, REQUIRED propagation, PostgreSQL advisory lock (`pg_advisory_xact_lock`) for multi-instance serialization, public `computeEventHash`, cleared temp arrays
- `AdminAuditEventRepository.java` / `AdminAuditEventRepositoryImpl.java` — `acquireChainLock()` + `findMostRecent()` (current path); `findMostRecentForUpdate()` deprecated
- `UserRepository.java` / `UserRepositoryImpl.java` — `lockTenantForBootstrap()`, `findByIdForUpdate()`
- `AuthProperties.java` — production mode, cookieSecure, originAllowlist, CSRF cookie name properties
- `ProductionStartupValidator.java` — fail-fast at `@PostConstruct`; refuses insecure production startup; never auto-enables cookieSecure
- `OwnershipService.java` / `ResourceOwnershipException.java` — resource ownership assertion (self-or-admin); 404 hiding on mismatch
- `GlobalExceptionHandler.java` — maps `ResourceOwnershipException` to RFC 9457 404 response
- `AdminTestController.java` — new (test-only): admin test endpoints for authorization testing
- `AuthenticationServiceTest.java` — updated mocks for `findByIdForUpdate`, `lockTenantForBootstrap`
- `AuthIntegrationTest.java` — updated `getCsrfToken` helper
- `AuthorizationIntegrationTest.java` — new: 10 authorization tests (admin access, USER denial, IDOR self/cross/admin-override, unauthenticated)
- `BootstrapTransactionIntegrationTest.java` — new: 3 transactional integration tests (atomic bootstrap with bounded timeout, two-writer serialization, concurrent distinct-username commits exactly one)
- `BootstrapConcurrencyTest.java` — new: concurrent bootstrap test
- `LoginFailureConcurrencyTest.java` — new: 2 concurrent login failure tests (deterministic counter under concurrency)
- `ProductionStartupValidatorTest.java` — new: 12 unit tests (valid production config, cookieSecure false, empty/NPE/localhost-only allowlist, invalid URI, missing scheme/host, HTTP non-localhost, path/query/fragment/userinfo rejection)
- `ProductionStartupValidatorContextTest.java` — new: 2 production-context startup tests (insecure cookies, localhost-only allowlist cause startup failure)
- `OwnershipTestController.java` — new (test-only): ownership assertion endpoints for authorization integration testing
- `AbstractPostgresTest.java` — shared Testcontainers singleton-container base for control-plane integration tests
- `AuthIntegrationTest.BootstrapHelper` — shared bootstrap fixture (secret file creation, CSRF cookie extraction, temporary password extraction)
- `OriginInterceptorProductionTest.java` — new: 3 production Origin tests
- `AuditChainIntegrityTest.java` — new: 3 audit chain tests
- `CustomCsrfCookieNameTest.java` — new: custom CSRF cookie name test
- `AuthIntegrationTestProduction.java` — new: production profile startup test
- `docs/api-contract.md` — updated: bootstrap, CSRF, Origin, production, error semantics
- `docs/configuration-reference.md` — updated: production constraints, cookie, allowlist, CSRF cookie name
- `docs/progress.md` — updated (this file)

### Local verification

- `.\mvnw.cmd verify --batch-mode` (non-integration): **BUILD SUCCESS** — all unit tests pass
- Spotless check: **PASS** (all modules)
- `git diff --check`: **PASS**
- Integration tests (`@Tag("integration")`): skipped on Windows (no Docker); Linux CI validates
- Frontend: `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`: **PASS**

### CI evidence

- **Integration fixture repair CI**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29919166968`
- **Conclusion**: **SUCCESS** (all 4 jobs — Ubuntu integration, Windows backend, Frontend, Compose config)
- **Commits**: `2d6c5de` (mustChangePassword + CSRF cookie + jsonb fixes), `0bf23d4` (jsonb scalar round-trip fix)
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/8`
- **Integration test suite**: 100 tests, 0 failures, 0 errors, 0 skips
- **Non-integration tests** (Windows): BUILD SUCCESS
- **Spotless**: PASS (all 8 modules)
- **git diff --check**: PASS
- **Frontend**: npm ci/lint/typecheck/test/build all PASS

## G1.3 — V3 migration fix (empty-table setval bug, DONE)

### Bug

Commit `a096dd7`'s V3 migration calls `setval('admin_audit_events_chain_seq', COALESCE(MAX(chain_position), 0))`. On a fresh (empty) `admin_audit_events` table, this attempts `setval(..., 0)` which PostgreSQL rejects because the sequence's default MINVALUE is 1. The migration succeeds on CI only because existing tests never exercise the pure empty-table path.

### Fix: safe DO block + OWNED BY

1. **V3 migration step 7** replaced the single `SELECT setval(...)` with a DO block:
   - Empty table: `setval('admin_audit_events_chain_seq', 1, false)` — next `nextval()` returns 1.
   - Non-empty table: `setval('admin_audit_events_chain_seq', max_pos)` (is_called=true) — next `nextval()` is `max_pos + 1`.
2. **V3 migration step 8 (new)**: `ALTER SEQUENCE ... OWNED BY admin_audit_events.chain_position` — dropping the column/table auto-drops the sequence.
3. **SchemaMigrationTest** (3 new tests):
   - `shouldSetChainSequenceTo1OnEmptyTable`: proves `nextval` returns 1 after fresh migration.
   - `shouldAssignUniqueNonNullChainPositions`: 5 rows inserted via column DEFAULT get unique, non-null, monotonically increasing `chain_position` values.
   - `shouldHaveSequenceOwnedByChainPosition`: verifies the `pg_depend` OWNED BY relationship.
   - Added `@AfterEach` cleanup: DELETE from admin_audit_events (defensive across test methods).

### Files changed

- `backend/persistence-postgres/src/main/resources/db/migration/V3__audit_chain_position.sql` — step 7 replaced with DO block; step 8 added (OWNED BY)
- `backend/persistence-postgres/src/test/java/.../SchemaMigrationTest.java` — 3 new V3 migration tests + @AfterEach cleanup
- `docs/progress.md` — updated (this file)

### Verification

- `.\mvnw.cmd verify --batch-mode`: **BUILD SUCCESS** — 374 non-integration tests, 0 failures, 5 skipped (POSIX on Windows)
  - Domain: 65 tests
  - Persistence PostgreSQL: 31 tests (5 skipped — POSIX on Windows)
  - Control Plane: 58 tests (integration skipped — no Docker)
  - Test Support: 109 tests
  - Gateway App: 111 tests
- Spotless check: **PASS** (all 8 modules)
- Maven Enforcer: **PASS**
- `git diff --check`: **PASS**
- Frontend: `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`: all **PASS**
- `docker compose -f deploy/compose.yaml config`: **ENV_BLOCKED** (CI validates)

### CI evidence (all green)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29921459893`
- **Conclusion**: **SUCCESS** (all 4 jobs):
  - Backend Ubuntu / Verify + Integration: **SUCCESS**
  - Backend Windows / Verify: **SUCCESS**
  - Frontend: **SUCCESS**
  - Compose config: **SUCCESS**
- **Commit**: `eacbd63` — `fix(g1.3): safe setval for empty-table V3 migration`
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/8`

## G1.3 — V3 upgrade test isolation and coverage fix (DONE)

### Problem

1. **Order-dependent test**: `SchemaMigrationTest.shouldSetChainSequenceTo1OnEmptyTable` called `nextval` on the shared singleton-database sequence and expected `1`. Test-method order is not a contract; any other test can consume the sequence first, causing a spurious failure.

2. **Missing V2→V3 upgrade coverage**: No test genuinely ran Flyway through V2, inserted representative pre-V3 rows into `admin_audit_events`, then ran V3 and asserted the backfill results. The empty-table V3 path was also untested in isolation from shared global sequence state.

### Fix: isolated schemas + programmatic Flyway

1. **`SchemaMigrationTest.shouldSetChainSequenceTo1OnEmptyTable`** — rewritten to create a unique PostgreSQL schema, run programmatic Flyway through V2 then V3, and verify the first `nextval()` returns 1. Uses `try/finally DROP SCHEMA CASCADE` for cleanup. No dependency on shared database sequence state.

2. **`V3UpgradeMigrationTest`** (new, 10 tests) — each test creates its own unique schema via programmatic Flyway configured with `defaultSchema`/`schemas`/`createSchemas`, targeting `"2"` then `"3"`. Covers:
   - Backfill: 7 pre-V3 rows receive unique, non-null, monotonically increasing `chain_position` values.
   - Post-V3 insert: A row inserted after V3 gets a `chain_position` greater than every backfilled row.
   - Empty-table upgrade: First post-V3 insert receives `chain_position = 1`; `nextval` directly returns 1.
   - NOT NULL constraint: Column is non-nullable after V3; explicit NULL insert is rejected; DEFAULT allows omission.
   - UNIQUE constraint: Constraint exists by name and duplicate `chain_position` is rejected.
   - OWNED BY: Sequence is bound to the column via `pg_depend` OWNED BY relationship.
   - Column default: `column_default` references `nextval('admin_audit_events_chain_seq')`.
   - Data preservation: Backfill does not alter existing row data (action, target_type, tenant_id, actor_id unchanged).

   V1 and V2 migration files **never edited**. Schemas dropped in `@AfterEach` via `DROP SCHEMA IF EXISTS … CASCADE`. `AbstractPostgresTest` singleton container reused — no new containers started. Cleanup is best-effort (catches and ignores exceptions so test failures are not masked).

3. **`is_nullable` type fix**: `information_schema.columns.is_nullable` is `varchar(3)` (`"YES"`/`"NO"`), not `boolean`. Changed query from `Boolean.class` to `String.class` in the NOT NULL constraint test.

### Files changed

- `backend/persistence-postgres/src/test/java/.../SchemaMigrationTest.java` — `shouldSetChainSequenceTo1OnEmptyTable` rewritten with isolated-schema Flyway; `DataSource` autowired
- `backend/persistence-postgres/src/test/java/.../V3UpgradeMigrationTest.java` — new: 10 comprehensive isolated-schema V2→V3 upgrade tests
- `docs/progress.md` — updated (this file)

### Verification

- `.\mvnw.cmd verify --batch-mode` (non-integration): **BUILD SUCCESS** — 303 non-integration tests, 0 failures, 5 skipped (POSIX on Windows)
- Spotless check: **PASS** (all 8 modules)
- Maven Enforcer: **PASS**
- `git diff --check`: **PASS**
- Integration tests (`@Tag("integration")`): skipped on Windows (no Docker); Linux CI validates
- Frontend: `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`: all **PASS**

### CI evidence (all green)

- **CI run**: `https://github.com/lichman0405/miqro-key-gateway/actions/runs/29922608445`
- **Conclusion**: **SUCCESS** (all 4 jobs):
  - Backend Ubuntu / Verify + Integration: **SUCCESS**
  - Backend Windows / Verify: **SUCCESS**
  - Frontend: **SUCCESS**
  - Compose config: **SUCCESS**
- **Commits**: `2c3404c` (10 isolated-schema upgrade tests), `3ab8b3b` (is_nullable type fix)
- **PR**: `https://github.com/lichman0405/miqro-key-gateway/pull/8`

### Integration test results (Ubuntu CI)

118 tests, 0 failures, 0 errors, 0 skips:
  - SchemaMigrationTest: existing tests + isolated empty-table test PASS
  - V3UpgradeMigrationTest: 10 tests PASS
  - All existing audit-chain, auth, crypto, and repository integration tests PASS

### Remaining risks

- Integration tests require Docker/Testcontainers — locally skipped on Windows; Linux CI validates.
- No `.claude-*` files in commits.

## tag-routing-usage-closed-loop（G1.4 授权 + G1.5 + G2.2 + G2.3 + G2.4 + G5.1 核心）

### Outcome

**端到端闭环已打通**：签发 Virtual Key（控制面）→ Gateway 用版本化只读快照校验/路由/注入真实凭证 → 转发上游 → 用量事件幂等落库 → 分级统计查询（控制面）→ 前端门户展示。

**控制面（G1.4 授权 + G1.5 生命周期）**

- 普通用户 `GET /api/v1/me/grants` 只返回自己作为成员的项目、授权（Grant 固定到具体 Credential）、模型（精确 ID）和用途。
- `POST /api/v1/me/virtual-keys` 自助创建：校验链（项目存在→成员→激活→路由标签→Grant 归属/激活→模型授权），HMAC 摘要入库，明文仅创建响应出现一次，`finally material.destroy()` 清零。
- 轮换：原子生成新版本，旧 Key 立即停止接受新请求、按 `miqrokey.virtual-key-rotate-grace`（默认 `PT0S`）宽限后失效；响应携带新 Secret（仅一次）。吊销：立即失效。所有动作写审计（不含 Secret 明文）。
- 越权防护：他人 Key 统一 `404 KEY_NOT_FOUND`，不可区分（IDOR 守卫）。
- 路由标签：Key 格式 `mqk_live_<publicKeyId>_<secret>[.<projectTag>]`，标签仅路由，鉴权权威是 `key_project_binding`（V4）。

**Gateway 数据面（G2.2 路由快照 + G2.3 Models + G2.4 Usage）**

- `route-snapshot` 模块：启动 + 定时（默认 30s）加载不可变快照（Key 摘要→绑定→Grant 模型→项目标签→AES-256-GCM 加密的上游凭证）；热路径零 DB 查询，凭证解密后内存清零。
- Virtual Key 鉴权：恰好一个凭证 Header（`Authorization: Bearer/裸值`、`x-api-key`、`api-key`），零/多 → 401；未知 Key、吊销/轮换后按快照刷新拒绝。
- 凭证注入：`CredentialInjector` 把固定绑定的上游凭证注入转发请求；无凭证目标 401/403。
- `GET /v1/models`：目录、Grant、Key 快照求交集，未授权模型不泄漏；无 Key 凭据时按供应商公开目录降级。
- 模型预校验：请求体模型越权时在连接上游前拒绝（协议兼容错误体）。
- 用量：`SseUsageObserver` 提取 token 计数（Anthropic/Responses/Chat 三种嵌套），有界队列（默认 10000）批量写 `usage_event`，`provider_request_id` tenant 内唯一 + `ON CONFLICT DO NOTHING` 幂等；`usage_missing` 标记上游无 usage；正文永不持久化。
- L1/L2 响应缓存 SPI（`cache-spi`）与 `CacheEligibility`/`CacheKeyFactory`/`SseReplayEngine` 已实现但**默认关闭**（ADR-0008）；只缓存 `cache_policy=ENABLED` 的 Key。

**前端普通用户门户（G5.1 核心）**

- Vue 3 门户：登录/登出（CSRF double-submit）、改密、Virtual Keys（创建/轮换/吊销 + 一次性 Secret 弹窗 + 显式确认关闭）、Usage（分组汇总 + 分页明细）、Profile。
- Secret 安全：只显示前缀/末四位；明文只在创建/轮换响应出现一次；复制经 Clipboard API；不进入 URL/localStorage/埋点/DOM data attribute。
- Quiet Operations Console 视觉（frontend-design.md §4）：无紫色/渐变/营销文案，表格优先，token 数字 tabular-nums，Key 等宽字体。

### Schema（V4–V7）

- V4：`virtual_keys.cache_policy`、`projects.project_tag`（唯一 + 格式约束）、`key_project_binding`（路由鉴权权威）、`model_approval`。
- V5：`cache_entry`（L2 原始字节缓存）、`price_snapshot`（每百万 token 单价，不租户隔离）。
- V6：`usage_event`（分级用量事实表，幂等唯一索引）、`cache_hit_event`（去重命中计数）。
- V7：`model_catalog`、`model_access`、`budget`、`model_budget`（预留，当前无消费代码）。

### Verification

**全模块本地验证（2026-08-25 第二轮，含 Testcontainers 集成测试）**：`./mvnw -f backend/pom.xml verify` **BUILD SUCCESS** — surefire 汇总 **491 run / 0 failures / 5 skipped**（本机 Docker Desktop 经 `DOCKER_HOST=tcp://localhost:2375` 可用，集成测试不再 CI-only）：

- domain 86（新增 vkey 解析、usage 统计域测试、路由标签后缀）、persistence-postgres 118（5 skipped，含 Testcontainers 加密/迁移集成测试）、queue-spi 6、control-plane-app 143（含 12 个 Me* 集成测试：MeVirtualKeyApi 8 + MeUsageApi 4）、gateway-app 138（VirtualKeyAuthContractTest、SseReplayEngineTest、CacheKeyFactoryTest 等）
- 修复的 12 个集成测试失败根因：bootstrap 管理员 `mustChangePassword=true` 门禁（SessionFilter）——测试此前只断言 Cookie 存在、从未重放改密请求，Me* 测试断言从未真正执行过
- 前端：`npm --prefix frontend run test` **16/16 PASS**、`lint` PASS、`typecheck` PASS、`build` PASS（chunk 大小警告为 Element Plus 全量引入，非错误）
- Spotless check：全模块 PASS（apply 后干净）
- `git diff --check`：PASS
- `docker compose -f deploy/compose.yaml config`：**PASS**（本机 Docker）

### 本轮修复的产品缺陷（12 个集成测试解封后暴露，均已修复并有测试）

1. **审计摘要非法 JSON**：`VirtualKeyService` 的 `change_summary` 是纯文本，而 `admin_audit_events.change_summary` 为 jsonb（插入时 `::jsonb` 强转）→ 500 `invalid input syntax for type json`。新增 `auditSummary()`/`escapeJson()` 生成合法 JSON。
2. **路由标签后缀未实现**：规格要求 Key 格式 `mqk_live_<publicKeyId>_<secret>[.<projectTag>]`，但 `VirtualKeyCrypto.generate` 只接收 tenantId，标签从未生成。接口签名改为 `generate(UUID tenantId, String projectTag)`；`lastFour` 恒取自无标签核心段，标签不进入展示尾部；空标签产出无标签形式。网关 `VirtualKeyParser`/`VirtualKeyResolver` 按标签路由的既有实现由此真正贯通。
3. **时间窗口校验非无条件**：`records()`/`summary()` 在无 Key 时短路返回，`TIME_RANGE_INVALID`/`TIME_RANGE_TOO_WIDE` 不触发；api-contract 要求无条件校验。提取 `validateTimeRange()` 并在任何数据访问前调用。
4. **Grant 模型顺序不确定**：`findModelIds` 返回无序 Set（`Set.copyOf`），`GET /me/grants` 的 models 数组顺序随机 → 依赖顺序的断言偶发失败。`grantOptions` 用 `TreeSet` 字典序输出。

### Files changed

- **控制面**：`MeGrantsController`、`MeVirtualKeyController`、`MeUsageController`、`VirtualKeyService`、`UsageStatsService`、`AuthProperties`（gatewayBaseUrl / virtualKeyRotateGrace）、`GlobalExceptionHandler`
- **域**：`vkey/`（VirtualKeyParser 等）、`usage/`（统计与价格模型）、`route/`（快照契约）、`KeyProjectBinding`、`ModelApproval`、`PriceSnapshotRepository`、`UsageStatsRepository`、`crypto/`（VirtualKeyCrypto.generate 增加 projectTag 路由标签后缀）
- **测试**：`MeVirtualKeyApiIntegrationTest`（8）、`MeUsageApiIntegrationTest`（4）、`UsageStatsServiceTest`、`VirtualKeyServiceTest`、`HmacVirtualKeyProviderTest`、`VirtualKeyParserTest`、`CryptoIntegrationTest`、`GatewayTestKeys`（改密门禁 + 新语义断言）
- **持久化**：V4–V7 迁移 + `KeyProjectBindingRepositoryImpl`、`ModelApprovalRepositoryImpl`、`PriceSnapshotRepositoryImpl`、`UsageStatsRepositoryImpl`
- **新模块**：`route-snapshot/`（版本化只读快照）、`queue-spi/`（有界用量队列）、`cache-spi/`（响应缓存 SPI + NoOp）
- **Gateway**：`VirtualKeyResolver`、`AuthContext`、`JdbcCredentialInjector`、`ModelsController`、`CacheEligibility`、`CacheKeyFactory`、`SseReplayEngine`、`ErrorEnvelopes`、`GatewayDataSourceConfig`、`GatewayFeatureConfig`
- **前端**：`api/`（fetch client + CSRF + ApiError）、`stores/auth.ts`、`router`（守卫）、`AppShell`、`LoginView`、`KeysView`、`UsageView`、`ProfileView`、`SecretRevealDialog`、`styles/tokens.css`、`types/api.ts`、4 个测试文件
- **文档**：api-contract.md（§4.1–4.6、§7.1）、database-schema.md（V4–V7 表）、configuration-reference.md（§4.4/5.1/9）、architecture.md（§3 新模块）、progress.md

### Remaining risks

- **PR #1 已合并（2026-08-25）**：squash-merge commit `8b6be8c`（feat(gateway): virtual key routing, credential injection and usage closed loop (#1)）；`goal/tag-routing-usage-closed-loop` 远端分支已删除；仓库默认分支已改为 `main`。PR CI（backend Linux `-Pintegration` + Windows、frontend、compose）4/4 全绿。
- **main 分支保护暂缓（2026-08-25）**：GitHub 分支保护规则需要 Pro/Team 计划，当前免费个人账号无法启用（API 返回 403）；建议公司建 org 后启用（要求 PR + status checks + conversation resolution，禁 force push/删除）。
- **Push 已解决（2026-08-25）**：目标远端改为所有者仓库 `sijie-Z/miqro-key-gateway`（新建 private）；origin 已切换、`.git/shallow` 浅克隆状态已解除（`git fetch --unshallow upstream`，upstream = `lichman0405/miqro-key-gateway`）。`goal/tag-routing-usage-closed-loop` 已 push 成功。
- 集成测试（12 个 Me* + 其余 Tag(integration) 类）已在本机 Docker Desktop（Testcontainers 1.21.4，`DOCKER_HOST=tcp://localhost:2375`）全部通过；Linux CI 作为交叉验证保留。
- 真实供应商凭证未提供：Gateway 凭证注入只经 Mock 上游验证，真实联调 `WAITING_FOR_CREDENTIAL`。
- 响应缓存默认关闭（ADR-0008 决策），正式启用前需新增 ADR。
- `request_usage_records` 完整分区表（规格 §6）未实现，当前使用 `usage_event` 事实表；G4.x 需要时再演进。
- 前端 chunk 1MB+ 警告：Element Plus 全量引入；可按需引入优化（非阻塞）。

## G2.2 — Gateway route snapshot and virtual key auth（收尾：热路径凭证密文快照 + PostgreSQL NOTIFY 刷新事件，DONE）

### Outcome

本 Goal 只覆盖 G2.2 两个未满足的验收项（快照与 Virtual Key 鉴权主体已在 tag-routing-usage-closed-loop 完成）：

1. **热路径零阻塞数据库调用**：`RouteSnapshot.CredentialRecord` 携带 ACTIVE 版本的 `EncryptedSecret`（密文 + nonce + keyVersion，防御性拷贝；快照只持密文，明文绝不进快照）。`JdbcRouteSnapshotLoader.loadCredentials()` JOIN `upstream_credential_versions`（`c.active_version_id = v.id`，部分唯一索引 `uq_credential_versions_one_active` 保证 ≤1 行）。`JdbcCredentialInjector` 改为在内存有界 `credentialDecryptScheduler` 上解密并复用既有 `SecretWiping` 清零——热路径零 JDBC。`CredentialSecretLoader` 已删除（快照重写后零引用死代码）。轮换语义保持：快照加载始终读当前 `active_version_id`，轮换后下一次刷新即路由新版本；在途请求已持有其解析的 Secret 不受影响。
2. **刷新事件 = PostgreSQL LISTEN/NOTIFY**（控制面与 Gateway 是两个进程共享同一 PostgreSQL，进程内事件不可用）：
   - 通道契约 `miqrokey_route_refresh`（配置项 `miqrokey.gateway.route-snapshot.notify-channel`，默认同契约名）。
   - 控制面发布端：`RouteSnapshotRefreshNotifier` 执行 `SELECT pg_notify('miqrokey_route_refresh','')`——必须用普通 `Statement.execute`（简单查询协议）：pgjdbc 的 `executeUpdate` 对 void 返回 SELECT 会在通知已发出后抛 "Unexpected result returned"。`RouteRefreshPublisher` + `RouteRefreshPublisherAfterCommit` 用 `TransactionSynchronizationManager.registerSynchronization` 在 **AFTER_COMMIT** 发布（回滚绝不发布）；挂接 5 个变更方法：`VirtualKeyService.create/rotate/revoke`、`AdminCredentialService.rotate/disable`（无 Grant/Project 变更服务，无需挂接）。发布失败只记日志——已提交的数据变更绝不回滚，30s 定时刷新兜底。
   - Gateway 监听端：`RouteSnapshotRefreshListener` 专用 `DriverManager` 连接（不进 Hikari 池——`LISTEN` 钉死连接为进程生命周期）、daemon 线程 `getNotifications(2000)` 轮询、失连指数退避重连（500ms→30s 封顶）、`close()` = running=false + interrupt + join(5000) 幂等停止；仅 `miqrokey.gateway.persistence.enabled=true` 时装配（`destroyMethod="close"`）。通知到达即调 `RouteSnapshotRefresher.refresh()`（版本递增并安装到 holder，保留 last-good）。定时刷新保留为兜底：丢失通知在下一刷新周期自愈。
   - `RouteSnapshotConfig` 收敛为单一 `NamedParameterJdbcTemplate`（复用 `gatewayJdbcTemplate`），消除 QueueConfig 无限定注入的 `NoUniqueBeanDefinitionException`。

### Verification

- 全量 `./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode`（本机 Docker Desktop，Testcontainers 集成测试实跑）：**BUILD SUCCESS** —— **674 tests / 0 failures / 5 skipped**（POSIX 权限测试在 Windows 跳过）
  - domain 86、provider-spi 8、provider-adapters 25、persistence-postgres 118（5 skipped）、route-snapshot 3、queue-spi 6、control-plane-app 179、test-support 109、gateway-app 140、cache-spi 0
- 新增 9 个测试全绿：
  - `SnapshotRefreshListenerTest`（3，Mockito fake JDBC）：通知触发 refresh；close 停止线程并关连接；失连退避重连后重新 LISTEN
  - `RouteRefreshPublisherAfterCommitTest`（3，真实 H2 事务）：提交后发布一次；回滚不发布；无 notifier bean 安全 no-op
  - `RouteSnapshotRefreshNotifierTest`（2，集成）：提交的 create 让 LISTEN 探针收到 NOTIFY；回滚的 create 探针零通知
  - `RouteSnapshotRefreshIntegrationTest`（1，集成）：网关 Listener 收到 `pg_notify` 后快照版本 1→2（调度已改为 1h，证明事件即时生效），断言新 Key 与绑定进入快照
- Spotless check：全模块 PASS（apply 后干净）；`git diff --check`：PASS；Maven Enforcer：PASS
- `GatewayNoBlockingTest` 不变通过：监听线程是普通 daemon 线程，不进 Reactor event loop，无新增 `.block()`

### Files changed

- **domain**：`RouteSnapshot.java` — `CredentialRecord` + `EncryptedSecret`（类 javadoc 声明快照只持密文）
- **route-snapshot**：`JdbcRouteSnapshotLoader`（JOIN 活动版本 + 密文映射）、新 `RouteSnapshotRefreshListener`、`RouteSnapshotConfig`（单一 JDBC 模板 + 监听器 bean）、删除 `CredentialSecretLoader`、pom +spring-boot-starter-test
- **gateway-app**：`JdbcCredentialInjector`（内存解密 + 清零）、`GatewayFeatureConfig`/`GatewayDataSourceConfig`（监听器装配）、`application.yml`（notify-channel）、pom +testcontainers（junit-jupiter/postgresql/core）、新 `RouteSnapshotRefreshIntegrationTest`
- **control-plane-app**：新 `support/RouteSnapshotRefreshNotifier`、新 `service/RouteRefreshPublisher` + `RouteRefreshPublisherAfterCommit`、`VirtualKeyService`/`AdminCredentialService`（AFTER_COMMIT 发布）、两个新测试类
- **test-support**：`GatewayTestKeys` fixture（CredentialRecord 带 EncryptedSecret fixture）
- **文档**：architecture.md §4.1（NOTIFY 通道契约 + 快照密文/热路径解密流程）、configuration-reference.md §5.1（notify-channel、refresh-interval 语义更新为兜底）、progress.md

### Remaining risks

- 通知丢失或控制面不可达时由 30s 定时刷新自愈（last-good 快照保留）；监听器断线有指数退避重连。
- 单节点单 Gateway 监听者（v1 范围）；多实例时 LISTEN/NOTIFY 的重复通知/放大语义留待多节点部署目标处理（architecture.md 已注明）。
- 真实供应商凭证未提供：凭证注入只经 Mock 上游验证，真实联调 `WAITING_FOR_CREDENTIAL`。

## G2.3 — Models endpoint（`/v1/models` 目录∩上游模型∩Grant∩Key 快照四路交集，DONE）

### Outcome

1. **快照扩展**：`RouteSnapshot.KeyRecord` 增加 `grantId`（`virtual_keys.grant_id`）；`RouteSnapshot` 新增 `grantModelsByGrantId`（仅 ACTIVE grant 的 `project_provider_grant_models`，JOIN 过滤）、`upstreamModelsByProductId`（`model_catalog` 仅 `ACTIVE` 行）、`productCodesByProductId`（`provider_products.product_code`）三个 map 与 accessor；equals/hashCode/toString/empty() 同步。
2. **`/v1/models` 四路交集**（`ModelsController`）：四路输入均来自 `AuthContext` 携带的**同一版本**快照——① **目录 gate**：Key 绑定产品的 `product_code` 不在签名目录（`ProviderCatalog` bean = `loadBuiltIn()`，Ed25519 校验，启动 fail-fast）→ 返回空列表（目录是外层授权边界，产品不在目录中什么都不泄漏）；② 交集 `key.models ∩ grantModels(grantId) ∩ upstreamModels(productId)`，排序输出。代理热路径的请求级模型预校验**保持 key-level**（`ctx.models()`）不变——模型目录为空时不得拒绝所有流量（api-contract §7.1 已写明两者区别）。
3. **上游模型生产者（`ModelCatalogService`，控制面）**：**success-only writes**——`applySnapshot`（`@Transactional`：事务内 DELETE 产品全部行 + batch INSERT `ACTIVE`）提交后（AFTER_COMMIT）发布 route-refresh NOTIFY，网关即时重载；`refreshProduct(adapter, client)` 是 G3.x 适配器接缝，任何抓取失败（异常/null/超时）只记日志并保留上次成功目录（"上游失败可回退最后成功目录"）。`refreshProduct`→`applySnapshot` 经 `ObjectFactory` 自代理穿越 Spring 事务边界（直接自调用会绕过 `@Transactional`，把替换拆成两个 autocommit 语句，崩溃窗口会短暂服务空目录而非 last-good）。
4. **已记录行为（非缺陷）**：G3.x 之前 `model_catalog` 为空 → 严格交集为空 → `/v1/models` 返回 `[]`——未授权模型不泄漏是刻意的，官方 API 抓取落地后自动恢复。

### Verification

- 全量 `./mvnw.cmd -f backend/pom.xml verify -P integration --batch-mode`（本机 Docker Desktop，Testcontainers 实跑）：**BUILD SUCCESS** —— **687 tests / 0 failures / 5 skipped**（Windows POSIX 权限跳过）
  - gateway-app 144（含 `ModelsListing` 6：happy path 四路对齐、Grant 限制、上游限制、无上游模型、未知产品码、无效 Key）、control-plane-app 188（含 `ModelCatalogServiceTest` 5 + `ModelCatalogServiceIntegrationTest` 4）
- `ModelCatalogServiceTest`（Mockito，5）：成功快照替换行并发布；空快照删旧行不批量仍发布；未知产品码跳过零交互；抓取失败保留 last-good；成功抓取委托 applySnapshot。
- `ModelCatalogServiceIntegrationTest`（Testcontainers，4）：真实库事务替换（m1+m2→m1）；未知产品零写入；抓取失败零写入；成功抓取替换并可见。
- `RouteSnapshotRefreshIntegrationTest`：seed `project_provider_grant_models` + `model_catalog`，NOTIFY 重载后断言 grantModels/upstreamModels/productCode 进入快照。
- Spotless check 全模块 PASS（apply 后干净）；Maven Enforcer：PASS。

### Files changed

- **domain**：`RouteSnapshot.java` — KeyRecord.grantId + 3 maps + accessors
- **route-snapshot**：`JdbcRouteSnapshotLoader` — loadKeys 选 grant_id + 3 个新有界查询（grant models、upstream models、product codes）
- **gateway-app**：`AuthContext`/`VirtualKeyResolver`（携带快照）、`ModelsController`（四路交集 + 目录 gate）、`GatewayFeatureConfig`（`ProviderCatalog` bean）、`GatewayAuthTestConfig`（6 fixtures 挂载）、`VirtualKeyAuthContractTest$ModelsListing`（+4）、`CacheKeyFactoryTest`（AuthContext 适配）、`RouteSnapshotRefreshIntegrationTest`（seed + 断言）
- **test-support**：`GatewayTestKeys` — KeyFixture 增加 grantId/productCode/grantModels/upstreamModels；4 个负面 fixture（Grant 限制、上游限制、无上游、未知产品）
- **control-plane-app**：新 `service/ModelCatalogService` + `ModelCatalogServiceTest` + `ModelCatalogServiceIntegrationTest`
- **文档**：api-contract.md §7.1（交集语义 + 空列表说明 + 预校验区别）、architecture.md §4.1（快照扩展 + success-only 生产者契约）、progress.md

### Remaining risks

- 适配器注册（G3.x）之前 `model_catalog` 恒空，`/v1/models` 返回空列表——严格交集是刻意的安全边界。
- 30s 定时刷新仍为 NOTIFY 丢失兜底；单节点单监听者范围不变。
- 真实供应商凭证未提供：`refreshProduct` 只经 Mock/契约测试，真实抓取 `WAITING_FOR_CREDENTIAL`。
