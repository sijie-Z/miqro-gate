# MiQroGate HTTP API 手册（逐接口）

> 交接文档 `05`。基线：`origin/develop` @ `5d4c3bca`（2026-09-21 17:17）。
> 本文所有结论以**代码**为准，行号基于上述提交。与仓库文档冲突处一律在 §7 列出。
>
> **与 `03-开发者手册-后端-管理服务.md` 的分工**：02 是"有哪些 Controller / 什么路径 / 什么作用"的目录，加上数据库 Schema、用量链路、权限模型总述。本文是**逐接口**的"要动这个接口时我需要知道什么"——参数形状、权限来自哪一层、响应字段的特殊语义，以及**坑**。两文重叠部分以本文为准（本文更细）；02 §2 的端点数清单与本文 §4 的域表如有出入，以本文表内的 `文件:行号` 为准，那些是逐条核对过的。

---

## 0. 怎么读这份文档

- **想改数据面（推理/MCP）** → §3 + §5。
- **想改控制面某个管理接口** → §4 找到域 → 表里最后一列的"坑"先读，再读 §5 的改动清单。
- **想加机器可调的新接口** → §6 先确认它该不该出现在机器面（机器面是**白名单式**设计，不是自动继承）。
- **接手第一件事**：读 §2.3（`@RequireRole` 是个死注解）与 §2.7（乐观锁现状与契约描述不符），这两个是全仓最容易误判的地方。

祖先文档（不要重复读，按需查）：
- 业务语义事实源：`D:\tmp\handover-verify\miqro-gate\docs\api-contract.md`（1355 行，§1–§9）。
- 机器可读镜像：`docs\openapi\openapi-3.1.json`（176 个 path）。
- 数据面细节：`docs\proxy-and-cc-switch.md`、`docs\usage-accounting.md`、`docs\protocol-agents.md`。

---

## 1. 接口分层总览（谁能调谁）

### 1.1 物理分层：两个 Spring Boot 应用 + 一个 nginx

生产部署由 `deploy/docker/nginx/default.conf` 定义路由，**按路径分派到两个不同进程**：

| 路径前缀 | 落到哪个应用 | 端口 | 说明 |
|---|---|---|---|
| `/api/**` | `control-plane-app`（Spring MVC + Spring JDBC） | 8080 | 认证、自助面、管理面、开放管理面、计费通道 |
| `/v1/**` | `gateway-app`（WebFlux + Reactor Netty） | 8081 | 推理数据面 |
| `/mcpservers/**` | `gateway-app` | 8081 | MCP 数据面 |
| `/`（其他） | nginx 静态 SPA（Vue） | — | 前端 |

（`deploy/docker/nginx/default.conf:1-6, 79-83, 85-97, 100-111`；端口见 `backend/gateway-app/src/main/resources/application.yml:2`、`backend/control-plane-app/src/main/resources/application.yml:2`）

**这条分层的直接后果（改接口前必须知道）**：

- 控制面**没有任何 `/v1` 或 `/mcpservers` 映射**，网关**没有任何 `/api` 映射**（全仓 `@RestController` 扫描：控制面 59 个控制器全部在 `controlplane/controller/`，网关 4 个全部在 `gateway/proxy/`）。改控制面接口永远不会影响推理热路径。
- 两个进程**唯一的共享契约是路由快照**（`RouteSnapshot`，定时刷新，默认 30s）。控制面写完库 → 发快照刷新 → 网关才看得见。所以"管理面改完立刻调数据面验证"这类测试必须处理快照延迟，或者看代码里是否显式调了刷新（本文各域的"坑"列会标）。
- nginx 对 `/v1/` 关 `proxy_buffering`、读超时 700s；对 `/mcpservers/` 读超时 3600s。**新加的数据面长连接端点如果不在 `/v1/` 或 `/mcpservers/` 前缀下，会拿到默认 60s 超时**。

### 1.2 四个面（谁能调谁）

| 面 | 路径前缀 | 认什么凭据 | 代码入口 |
|---|---|---|---|
| **数据面（推理）** | `/v1/**` | 只有 Virtual Key（`mqk_live_…`） | `VirtualKeyResolver` |
| **数据面（MCP）** | `/mcpservers/**` | 只有 API 消费者凭据（`mqk_api_…` 或消费者 JWT）——**Virtual Key 在此无效** | `McpProxyController.authenticate` |
| **控制面（人类）** | `/api/v1/auth`、`/api/v1/me/**`、`/api/v1/skills`、`/api/v1/admin/**` | 门户会话 Cookie（+ CSRF） | `SessionFilter` + `RoleInterceptor` |
| **控制面（机器）** | `/api/v1/admin-api/**` | `mqk_admin_…` 机器密钥，或 SYSTEM_ADMIN 会话 | `AdminApiKeyAuthFilter` |
| **第三方计费通道** | `/api/v1/billing/**` | `mqk_api_…` / 消费者 JWT，或 SYSTEM_ADMIN 会话 | `ApiKeyAuthFilter` |

### 1.3 凭据 × 面 支持矩阵（实测）

| 凭据 | `/v1/**` | `/mcpservers/**` | `/api/v1/{auth,me,skills,admin}/**` | `/api/v1/admin-api/**` | `/api/v1/billing/**` |
|---|---|---|---|---|---|
| Virtual Key `mqk_live_…` | ✅ 唯一合法凭据 | ❌ | ❌（`SessionFilter.java:92` 直接 401） | ❌ 前缀不符 → 401 `ADMIN_API_KEY_INVALID` | ❌ |
| 消费者 Key `mqk_api_…` | ❌ | ✅ | ❌ | ❌ 同上 | ✅（需 `billing:read`） |
| 消费者 JWT（RS256） | ❌ | ✅（`sub`→消费者名验签） | ❌ | ❌ | ✅ |
| 机器密钥 `mqk_admin_…` | ❌ | ❌ | ❌ | ✅ 唯一机器凭据 | ❌（非 `mqk_api_` 前缀 → 走 JWT 分支失败 → 401） |
| 门户会话 Cookie | ❌ | ❌ | ✅ | ✅ 但**仅 SYSTEM_ADMIN**，且仍要 CSRF | ✅ 但**仅 SYSTEM_ADMIN**（#724） |

关键实现点：
- `AdminApiKeyAuthFilter.java:87` 只接受 `Bearer` 头 + `mqk_admin_` 前缀；**没有 `X-API-Key` 通道**（与 `mqk_api_` 那套不同）。
- `ApiKeyAuthFilter.java:180-190` 的凭据抽取：`X-API-Key` 头**只当 API Key**；`Authorization: Bearer` 按前缀分流（`mqk_api_` → Key，其余 → JWT）。写错前缀不会有"两种都试"的兜底。
- `SessionFilter.java:88` 对 `/api/v1/billing` 与 `/api/v1/admin-api` 在**无会话 Cookie 时放行**，交给各自的 filter 去判——所以这两个面的 401 形状由各自 filter 决定，不是 `SessionFilter` 的。

### 1.4 每个面的守卫链（按请求实际经过的顺序）

```
[控制面]  AdminIpAllowlistFilter(-110) → SessionFilter(-100) → AdminApiKeyAuthFilter(-95, 仅 admin-api)
          → ApiKeyAuthFilter(-90, 仅 billing) → OriginInterceptor → CsrfInterceptor → RoleInterceptor → Controller
[数据面]  (无 servlet filter 链) VirtualKeyResolver / McpProxyController.authenticate → QuotaGate → 业务
```

注册处：`config/SecurityConfig.java:58-70`（SessionFilter，`/api/*`，order -100）、`:73-80`（ApiKeyAuthFilter，`/api/v1/billing/*`，order -90）、`:100-109`（AdminIpAllowlistFilter，`/api/*`，order -110）、`:117-122`（三个 interceptor 依次注册到 `/api/**`）；`config/OpenAdminApiSecurityConfig.java:22-30`（AdminApiKeyAuthFilter，`/api/v1/admin-api/*`，order -95）。

**顺序本身就是约束**：`AdminIpAllowlistFilter` 在最前 → 一旦配了 `miqrokey.control.admin-access.ip-allowlist`，**机器面调用同样受 IP 白名单约束**（它只豁免 `/api/v1/billing/` 与 bootstrap，见 `AdminIpAllowlistFilter.java:61-64`）。把 CI 机器密钥换成新出口 IP 时会忽然 403 `IP_NOT_ALLOWED`。

---

## 2. 通用约定

### 2.1 路径前缀

- 管理 API 前缀 `/api/v1`；推理 API 保持上游原生路径（`/v1/messages` 等）。
- 有一处**不在任何前缀常量里**的特例：`AdminBudgetController.java:38` 的类级映射是裸 `/api/v1/admin`，所有路由自己带 `/budgets` 或 `/projects/{id}/budget`。它仍在 `/api/v1/admin/` 门内。

### 2.2 认证：细节与反直觉处

1. **Virtual Key 的"一个凭据头"规则**（`VirtualKeyResolver.java:143-179`）：`Authorization`、`x-api-key`、`api-key` 三个头合计**必须恰好一个非空值**；0 个或 ≥2 个都 → `401 unauthorized`。裸 `Bearer`（无空格无 token）也算空凭据 → 401，不会掉到 404 解析分支。
2. **Virtual Key 的失败语义是 404 不是 401**（`VirtualKeyResolver.java:185-187`）：未知 / 畸形 / HMAC 不匹配 / 密钥已吊销，一律 `404 virtual_key_invalid`，响应体逐字一致。**MCP 面同场景是 401 `invalid_api_key`**——两个面刻意不一致，别"统一"它们（`docs/api-contract.md:1244` 有记录）。
3. **门户会话的额外拒绝理由**（`SessionFilter.java:113-157`）：DISABLED、LOCKED（含 `lockedUntil==null` 的无限期锁，见 #445）、空闲超时、`mustChangePassword=true` 时只放行 `/api/v1/auth/{password,logout,me,csrf}`。这几种都是 401，`code` 各不相同（`UNAUTHORIZED` / `SESSION_INVALID` / `SESSION_EXPIRED` / `PASSWORD_CHANGE_REQUIRED`）。
4. **消费者凭据到期是静默失效**（`McpProxyController.java:150-155`）：`now >= expires_at` 后与未知 Key 同形（401），不留"曾有效"信息。

### 2.3 授权：deny-by-default 与 `@RequireRole` 的现状

`RoleInterceptor`（`security/RoleInterceptor.java`）做两件事：

1. **前缀 deny-by-default**（`:36-49`）：路径以 `/api/v1/admin/` 开头 → 未认证 401 `UNAUTHORIZED`；角色非 `SYSTEM_ADMIN` → 403 `FORBIDDEN`（消息是中文的）。路径读的是 `RequestPaths.lookupPath`（去分号参数 + 解码 + 折叠双斜杠，#723），**不能改成 `request.getRequestURI()`**——那正是 #723 修掉的绕过（`RequestPaths.java:8-27`）。
2. **`@RequireRole` 注解**（`:51-84`）：方法级优先于类级；`SYSTEM_ADMIN` 永远放行（`:74-76`）；否则要求角色**精确相等**。

> **⚠ 坑（必读）**：`@RequireRole` 在**整个仓库里 0 处使用**（`grep -rn "@RequireRole" backend/ --include=*.java` 返回空；注解定义在 `security/RequireRole.java`）。也就是说今天**没有任何接口靠它保护**：除了 `/api/v1/admin/**` 的 SYSTEM_ADMIN 门，其余所有接口的"角色"约束**不存在**——`/api/v1/skills`、`/api/v1/me/**` 一律是"任意已登录用户"。
>
> 这意味着：**给一个新端点加权限的最省事做法不是加注解**（注解语义是"精确角色匹配"，只有一个非管理员角色 `USER`，表达力有限），而是要么放进 `/api/v1/admin/`（自动获得 SYSTEM_ADMIN-only），要么在服务层做显式归属/成员校验。要真正用起 `@RequireRole` 需要先扩 `UserRole` 并补测试。

`/api/v1/admin-api/**` **不走** `RoleInterceptor`（前缀不匹配 `/api/v1/admin/`），它的授权在 `AdminApiKeyAuthFilter` 里，见 §6。

**隐式归属校验的三种形态**（§4 各表的"坑"列会注明每个端点属于哪种）：

- **靠 `userContext` 自限定**：控制器/服务用 `userContext.getUser()` 的 userId 或 tenantId 作查询过滤条件，拿不到别人的行。`/api/v1/me/**` 全是这种。
- **靠 tenantId 过滤**：管理面绝大多数端点把会话 `tenantId` 直接塞进 SQL 的 `WHERE tenant_id = ?`。**但有两个例外是全局表、不过滤租户**：`/api/v1/admin/provider-products` 的列表/详情/供应商列表（`AdminProviderService.listProducts()/product(id)/listProviders()`）、`AdminOrgService.createGrant` 的产品存在性检查。单租户部署下无害，多租户前必须处理。
- **靠"自有资源"断言**：`VirtualKeyService.ownedKey`、`ModelApprovalService.ownedKey` 这类私有方法，同时过滤 tenantId 与 `owner == caller`（SYSTEM_ADMIN 旁路），不匹配一律 **404 `KEY_NOT_FOUND`**（防枚举）。

> **⚠ 坑**：`security/OwnershipService.java` 的 `assertSelfOrAdmin`（`:50`）**是死代码**——**没有任何控制器调用它**（本手册逐文件核对过的 59 个控制面控制器里零调用）。它看起来像"本仓库的归属校验入口"，实际上每个域都自己写了一遍。改接口时不要以为它拦住了什么。

### 2.4 CSRF / Origin / IP 白名单

| 机制 | 作用于 | 豁免 | 代码 |
|---|---|---|---|
| **CSRF** | `/api/**` 的 POST/PUT/PATCH/DELETE，要 `X-CSRF-Token` 头 | `/api/v1/auth/{login,bootstrap,register}`；**机器密钥调用**（`KEY_ATTR` 非空）；GET 类自动短路 | `security/CsrfInterceptor.java:30-31, 48-64` |
| **Origin** | `/api/**` 的 POST/PUT/PATCH/DELETE | 无（生产模式一律要求） | `security/OriginInterceptor.java:36, 50-67` |
| **IP 白名单** | `/api/*` 全部 | `/api/v1/billing/**`、`/api/v1/auth/bootstrap` | `security/AdminIpAllowlistFilter.java:61-64` |

**⚠ CSRF 的机器面豁免只认 `KEY_ATTR`**（`CsrfInterceptor.java:61-64`）。`AdminApiKeyAuthFilter` 在**门户会话**分支**不设** `KEY_ATTR`（只设 `TENANT_ATTR`/`ISSUER_ATTR`，见 `AdminApiKeyAuthFilter.java:72-82`）。所以：

- 用 `mqk_admin_` 密钥调 `/api/v1/admin-api/**` 的写端点 → 无需 CSRF。✅（`scripts/open-api-examples/curl.sh` 就是这么用的）
- 用**浏览器 SYSTEM_ADMIN 会话**调同一个写端点 → **需要 CSRF token**。

**⚠⚠ Origin 的坑（未见于任何文档）**：`OriginInterceptor` 注册在 `/api/**` 上且**没有任何按面/按凭据的豁免**。生产模式（`miqrokey.production=true` 或 `production` profile）下，对 `/api/v1/admin-api/**` 发 POST/PATCH/DELETE 且**不带 `Origin` 头**，会被 `403 ORIGIN_REJECTED` 挡掉。
> ⚠️ **顺序要分清**：servlet filter（`-110/-100/-95`）**全部先于** MVC interceptor 执行 —— 凭据**无效**时先得 `401 ADMIN_API_KEY_INVALID`，只有**凭据有效**才轮到 OriginInterceptor 拦。`scripts/open-api-examples/curl.sh`（以及 `example.py`）**都没有设置 Origin 头**——按仓库自带示例脚本照抄的自动化，在非生产环境可用、**切到生产就 403**。反之开发模式（`authProperties.isProduction()` 为假）缺 Origin 放行、localhost 也放行，所以这个问题在本地永远看不到。
> 补正方式二选一：给脚本加 `-H "Origin: <allowlist 中的源>"`，或给机器面加按 `KEY_ATTR` 的 Origin 豁免（后者是行为变更，需先确认是有意为之还是遗漏——**未能核实**是否有线上部署以 `production=false` 绕开）。

**Origin 校验方式**：严格 `java.net.URI` 解析后 scheme/host/port 全等比对，不做子串匹配（`OriginInterceptor.java:69-109`）。未知/缺失/不在 allowlist → 403 `ORIGIN_REJECTED`。

**IP 白名单的取值**：`X-Forwarded-For` **只在直连对端命中 `trusted-proxies` 时才消费**，且**从右往左**取第一个非可信地址（`AdminIpAllowlistFilter.java:79-100`）。nginx 部署 append 模式，所以必须把 nginx 容器地址写进 `miqrokey.control.admin-access.trusted-proxies`，否则白名单判的是 nginx 的内网 IP。

### 2.5 错误信封与稳定 code

所有错误都是 RFC 9457 `application/problem+json`：

```json
{"type":"about:blank","title":"…","status":409,"code":"STABLE_CODE","detail":"…","requestId":"…"}
```

字段顺序固定（`LinkedHashMap`，`GlobalExceptionHandler.java:269-279`）。`requestId` 回显客户端 `X-Request-Id`，没有则随机 UUID；**这个头是客户端可控的**，所有 filter 都经 `ProblemJson.of` 序列化而不是字符串拼接（#445/#1011，`security/ProblemJson.java` 的类注释解释了为什么六个各写一份手转义是错的）。

`GlobalExceptionHandler` 的通用映射（改接口时的兜底）：

| 异常 | HTTP | code | 行号 |
|---|---|---|---|
| `AuthenticationException` | 401 | `UNAUTHORIZED` | `:45-54` |
| `ResourceOwnershipException` | 404 | `NOT_FOUND` | `:56-63` |
| `ApiException`（服务层业务错误） | 自定义 | 原样透出 `e.getCode()` | `:79-91` |
| `ResourceInUseException` | 409 | `RESOURCE_IN_USE` + `dependencies[]` | `:93-100` |
| `MethodArgumentNotValidException`（bean validation） | 400 | `VALIDATION_FAILED` + `fieldErrors[]` | `:102-118` |
| 查询参数类型不匹配 | 400 | `PARAM_INVALID` | `:125-137` |
| 请求体 JSON 不可解析（含未知枚举） | 400 | `PARAM_INVALID`（带字段名） | `:144-160` |
| `DataIntegrityViolationException` | 409 | `RESOURCE_CONFLICT` | `:175-183` |
| `ConcurrencyFailureException`（含乐观锁失败） | 409 | `CONCURRENT_MODIFICATION` | `:190-198` |
| 缺必填查询参数 | 400 | `PARAM_INVALID` | `:206-213` |
| 方法不支持 | 405 | `METHOD_NOT_ALLOWED` | `:215-223` |
| Content-Type 不支持 | 415 | `UNSUPPORTED_MEDIA_TYPE` | `:225-233` |
| 路径不存在 | 404 | `NOT_FOUND` | `:235-241` |
| `DateTimeParseException` | 400 | `TIMESTAMP_INVALID` | `:254-260` |
| 其他 | 500 | `INTERNAL_ERROR` | `:243-251` |

**⚠ 同样的语义在不同端点可能给出不同 code**（改接口时别照抄邻居）：

- 时间参数非法：手工 `Instant.parse` 的端点得到 `TIMESTAMP_INVALID`（如 `AdminAgentController` 的 usage、`AdminUsageDeletionController`），而 `AdminMcpAccessLogController.java:53` **自己抛 `PARAM_INVALID`**。
- 上限越界：`mcp-access-logs` 的 `limit` 越界 → 400 `SIZE_INVALID`；`webhook .../deliveries` 的 `limit` 越界 → **静默 clamp 到 1..100**（`WebhookEndpointService.java:229`）；tool/skill revisions 的 `limit` 同样**静默 clamp 1..50**。
- 服务层若抛未包装的 `IllegalArgumentException`/`IllegalStateException` → 落到 500 `INTERNAL_ERROR`。例：`POST /api/v1/admin/mcp-services` 的 `transport` 未做 bean 校验，非法值撞到领域构造器 → **500 而不是 400**。

### 2.6 分页

三种**并存且不可互换**的分页风格：

| 风格 | 用在 | 参数 | 越界错误码 |
|---|---|---|---|
| `page`/`size` offset | `/api/v1/me/usage/records`、`/api/v1/admin/usage/records`、`/api/v1/admin-api/usage/records` | `page` 默认 1，`size` 默认 50（1–200） | `PAGE_INVALID` / `SIZE_INVALID` |
| keySet 游标 | `/api/v1/admin/model-approvals`（`before` + `nextCursor`，编码 `(created_at, id)`） | `size` 默认 20、上限 100 | `PARAM_INVALID` |
| `beforePosition` 游标 | `/api/v1/admin/audit-events`、`/api/v1/admin-api/audit-events` | `size` 默认 50 | — |
| `row_no` 游标 | `/api/v1/admin/reconciliations/{id}/rows`（`cursor` + `nextCursor`） | `limit` ≤500 | — |

契约 §1 声称"列表默认按 `createdAt DESC, id DESC`，使用不透明 cursor，**禁止 offset 深分页**"——实际 offset 分页在用量明细上一直存在。**以代码为准**。

### 2.7 幂等与乐观锁（现状与契约不符，必读）

- **`Idempotency-Key` 请求头：未实现**。契约 `api-contract.md:12` 自己标注了"预留：当前版本未实现（#734）"。重复提交会重复创建。
- **`If-Match` / `412 VERSION_CONFLICT`：未实现**。契约 `:13` 也已更正。
- **乐观锁的真实形态**：
  - 绝大多数 PUT/PATCH 是**服务端读改写 + 仓库层 CAS**（`WHERE version = :expectedVersion`），客户端**不提交 version**。冲突抛 `OptimisticLockingFailureException` → 409 `CONCURRENT_MODIFICATION`。仓库实现见 `persistence-postgres/.../ProjectRepositoryImpl.java:86-97`、`TeamRepositoryImpl.java:61-71`、`UserRepositoryImpl.java:100-113`、`UpstreamSubscriptionRepositoryImpl.java:81-95`、`UpstreamCredentialRepositoryImpl.java:87-96`、`ProjectProviderGrantRepositoryImpl.java:75-84`。
  - **只有少数端点把 `version` 当请求体字段**：`PATCH /api/v1/admin/subscriptions/{id}/seats/{seatId}`（`AdminSubscriptionController.java:99`，`@NotNull Long version`）、`PATCH /api/v1/admin/agents/{agentId}`（`AgentUpdateRequest`，见 §4.10）。
  - 所以契约 `:13` 那句"`version` 为**请求体字段**、随写请求提交"作为全局陈述是**不成立**的——它对 seats/agents 成立，对 users/teams/projects/subscriptions/credentials/grants/budget/quota-rules/unattributed-policy **不成立**（那些端点接受 `version` 字段也当没看见）。客户端遭遇冲突时**没有可重放的版本令牌**，只能盲重试。
  - 另一类端点**完全没有版本**：`POST /api/v1/admin/mcp-services/{id}/status` 走窄更新 `repository.updateStatus`，契约 §5.16 承诺的"并发编辑乐观锁 → 409 CONCURRENT_MODIFICATION"**在该路径上不可达**。
- **真正实现了幂等的端点**：
  - `POST /api/v1/admin/usage-adjustments` 的请求体 `idempotencyKey`（`(tenant_id, idempotency_key)` 部分唯一索引）——**与未实现的 `Idempotency-Key` 头是两套东西**。
  - `POST /api/v1/admin/usage-price-backfill` 的盖章（只处理 `price_status IS NULL`）。
  - `POST /api/v1/admin/reconciliations` 的 `(providerCode, window, currency, uploadSha256)` 去重（`FAILED` 除外）。
  - `POST /api/v1/admin/crypto/reencrypt`（可重复调用）。
  - `/api/v1/admin/costs` 类的 upsert（grant models 替换、budget upsert、quota-rules upsert）。
- **写入不幂等但被误认为幂等的**：`POST /api/v1/admin/grants/{grantId}/models` 名字像"追加"，实际是**全量替换**（删光再插）；`DELETE /api/v1/admin/grants/{grantId}` 实际是**软停用**且**没有已停用守卫**——停用已停用的 grant 会成功并再写一条审计。

### 2.8 审计归属

- 人类会话：actor = 当前用户。
- 机器面：actor = **发行该密钥的管理员**，摘要附 `via: admin-api:<密钥名>`（`AuditContext.machine(...)`，见 `OpenAdminAlertRulesController.java:88-96`、`OpenAdminExportsReadController.java:93-101`）。密钥没有发行管理员时写面直接 403 `EXECUTOR_UNKNOWN`。
- 三类端点**不写审计**且这是有意的：只读端点；`dryRun=true` 的工具同步；幂等 no-op（route-rule 启停未变、修订激活已是当前版）。
- **意外不写审计的**（疑似遗漏，§7 列出）：`POST /api/v1/admin/subscriptions/{id}/quota/refresh`（服务类无 `AuditService` 依赖）、`POST .../cost-allocation/allocate`、`POST /api/v1/admin/credentials/{id}/validate`（纯检查，无写库，合理）。
- **审计行丢 `requestId` 的**：`/api/v1/admin/api-keys` 的全部三个写操作（`AdminApiKeyService.java:57, 79, 92` 传 `null`）、`PUT/DELETE /api/v1/admin/unattributed-policy`（控制器不读 `X-Request-Id`）、`POST /api/v1/admin/quotas` 的模板自动复制（`applyToNewUser` 传 `null`）、MCP resilience 的 PUT（`AdminMcpResilienceController.java:50-53` 缺头时返回**空串**而非随机 UUID）。

---

## 3. 数据面（推理面）

### 3.1 三个协议端点（同一段代码）

| 端点 | 方法 | 代码 |
|---|---|---|
| `/v1/messages` | POST | `gateway/proxy/ProxyController.java:204-207` |
| `/v1/responses` | POST | `ProxyController.java:209-212` |
| `/v1/chat/completions` | POST | `ProxyController.java:214-217` |

**谁调**：CC Switch / Claude Code / Codex / 各类 SDK。**凭据**：Virtual Key，三种头任选其一。

**处理顺序**（`ProxyController.java:250-352`，改动前请按此顺序理解）：

1. `VirtualKeyResolver.resolve` 认证（`VirtualKeyResolver.java:74-95`）→ 401/404。
2. `QuotaGate.requireNotExceeded(ctx)` — **在任何 body 工作之前**判配额（`ProxyController.java:255`）。命中 → 429 `quota_exceeded` + `Retry-After`。
3. 缓冲整个请求体，上限 `MIQROKEY_MAX_PROXY_BUFFER_BYTES`（默认 256KB），超限 413 `payload_too_large`（`:349-351`）。
4. 合规留痕旁路捕获入站 body（ADR-0014，默认关，`retentionSidecar.capture`，`:267`）。**注意它发生在 413 预检之前**，所以被 413 拒掉的请求也可能已按留存策略入库。
5. 解析 body 取 `model`，与 Key 的模型集合比对，不通过 → **403 `model_not_allowed`**（`:301-304`）。未归属（`POLICY_ROUTED`）时集合改为"策略范围 ∩ Key 允许"（`:280-289`）。
6. 上下文上限预检（#553，`contextLimitGuard.check`，`:311-315`）→ 413 `context_limit_exceeded`。按 UTF-8 码点计整个已缓冲 body；非法 UTF-8 回退为字节数（**不低估**）。覆盖范围**只有这三个 `/v1/**` 路径**，MCP 面不套用。
7. 缓存判定（`CacheEligibility`，双重 opt-in：Key 的 `cachePolicy=ENABLED` + 请求头 `X-MiQroKey-Cacheable: 1`）→ 命中走 `SseReplayEngine.replay` 逐字节重放；未命中转发。
8. 转发：解密凭证 → SSRF 校验（`UpstreamTargetValidator`）→ 注入真实凭证头 → **字节级原样**发出请求体与查询串。
9. 上游侧熔断（#741，默认关）→ 503 `circuit_open`。
10. 生命周期记录：每个**到达上游**的请求开 `request_usage_records` 的 `IN_FLIGHT` 行并恰好 finalize 一次（含客户端取消、上游失败、超时）。**鉴权失败与缓存命中不开生命周期行**。

**响应**：HTTP 状态、响应体、SSE 事件顺序**原样透传**（`ProxyController.java:554-578`）。网关只额外做三件事：

- 加 `X-MiQroKey-Request-Id`（网关自己的 request id；`SseReplayEngine.X_MIQROKEY_REQUEST_ID`，`ProxyController.java:559`）。
- 缓存路径上加 `X-MiQroKey-Cache: L1|L2`，缓存开启但未命中时加 `miss`（`:560-562`；缓存关闭时**不加这个头**）。
- 上游已有的 request id **不覆盖**，两个 id 都进用量记录（`pickProviderRequestId`，`:563`）。

**转发前剥离的头**（`HeaderFilters.java:29-52`）：`authorization`/`x-api-key`/`api-key`（换成真实凭证）、`host`/`content-length`（重建）、hop-by-hop 8 个（按 `Connection` 提名逐个算）、以及**所有 `x-miqrokey-*` 和 `x-miqro-*` 前缀**。最后这条意味着**归属声明头永远不会到达上游**（`X-Miqro-Project-Id` 等，§3.3）。

**常见错误速查**：

| 场景 | 状态 | code | 出处 |
|---|---|---|---|
| 缺凭据 / 多个凭据头 | 401 | `unauthorized` | `VirtualKeyResolver.java:157,161` |
| 未知/畸形/已吊销 Key | 404 | `virtual_key_invalid` | `VirtualKeyResolver.java:186` |
| 多绑定 Key 无法解析归属 | 400 | `CONTEXT_REQUIRED` | 见 `docs/context-attribution-implementation-spec.md` §4 |
| 声明项目不是该 Key 的绑定 | 403 | `CONTEXT_NOT_ALLOWED` | 同上 |
| 模型不在 Key 授权内 | 403 | `model_not_allowed` | `ProxyController.java:302` |
| 配额超限（REJECT 规则） | 429 | `quota_exceeded` + `Retry-After` | `QuotaGate.java:31-45` |
| 请求体超缓冲 | 413 | `payload_too_large` | `ProxyController.java:350` |
| 上下文超阈值 | 413 | `context_limit_exceeded` | `ContextLimitGuard` |
| 熔断打开 | 503 | `circuit_open` | `ProxyController.java:468` |
| 上游不可达 / 目标被 SSRF 拒 | 502 | `upstream_unavailable` / `route_unavailable` | `:346, 418-431` |
| 错误方法（GET 打 `/v1/messages`） | 405 | `method_not_allowed` | `:233-239` |
| 其他 `/v1/**` 路径 | 404 | `unsupported_path` | `:241-243` |

**⚠ 坑**：

- 兜底 `@RequestMapping("/v1/**")`（`:228-243`）会**接住所有** `/v1/**` 的未匹配请求。加新的 `/v1/xxx` 端点时，**特异性映射优先**没问题；但如果是**同一路径的另一个方法**（比如给 `/v1/models` 加 POST），会被这个兜底接走并返回 405 而**不是你写的 handler**——排查时先看这里。
- 405/404 两个兜底响应体是**硬编码常量**（`:110-124`），`/v1/messages` 用 Anthropic 形状、其余用 OpenAI 形状。加端点时若要协议兼容错误体，得走 `ErrorEnvelopes`。
- `route_unavailable` 的错误体、日志、审计**都不含目标 URL 或主机名**（`UpstreamTargetValidator` 只回稳定类别 token）——别为了排查把它加回响应。
- 客户端取消**不算失败**（`clientCancelled` 逻辑 `:504-520`）：不喂熔断、不计入成功率的任何一侧（`outcomes` 口径见 §4.6）。
- 重试只有**首字节前的一次连接阶段重试**（`:492-493`），且**不换凭证**（没有跨凭证故障转移；ADR-0021 仍是 Proposed）。

### 3.2 `GET /v1/models`

- 代码：`gateway/proxy/ModelsController.java:57-70`。凭据同 §3.1，且**同样过 `QuotaGate`**（`:61`，被配额拦的作用域连模型列表都拿不到）。
- 返回 OpenAI 兼容 `{"object":"list","data":[{id,object,created,owned_by}]}`，`created` 恒 0、`owned_by` 恒 `"miqrokey"`（`:72-84`）。
- **四路交集**（`:90-113`）：`(已签名 provider catalog 里的产品) × (model_catalog ACTIVE 行) × (该请求绑定的 ACTIVE grant 的 models) × (Key 自己的 virtual_key_models)`。
- **⚠ 与代理热路径不是同一套判定**：热路径按 **`key.models ∩ grant.models`** 判（`ProxyController.java:280-298` 的 `snapshot.grantModels(...)` 求交，ADR-0018）——**grant 收缩立即生效**，`/v1/models` 才是四路交集。因此"模型目录为空"时 `/v1/models` 返回空列表，**但代理不会拒绝所有流量**。文档化的刻意行为（`api-contract.md:1258`）。
- `POLICY_ROUTED`（未归属）时 grant 层换成"策略范围或产品 ACTIVE 上游目录"（`:102-107`）。

### 3.3 `GET /v1/context-registry`（CAA）

- 代码：`gateway/proxy/ContextRegistryController.java:62-80`。返回 `{"entries":[{repoKey, projectId, projectTag}]}`。
- **身份-only 认证**（`:69` 用 `resolveIdentity`）：**不走归属阶梯**，所以多绑定 Key 带任意（含不匹配）后缀都能读——这是刻意的，否则循环依赖（#641）。
- 只返回该 Key **ACTIVE 绑定项目**下的仓库映射；无持久化时返回空表。
- 直接查库（10s 超时，`:46`），**不占快照**；超时 → 503 `context_registry_unavailable`。
- **不过 `QuotaGate`**（与 `/v1/models` 不同）——被配额拦的 Key 仍能读注册表。

### 3.4 MCP 数据面三个端点

| 端点 | 方法 | 代码 |
|---|---|---|
| `/mcpservers/{serviceName}/mcp` | POST | `McpProxyController.java:143-174` |
| `/mcpservers/{serviceName}/sse` | GET | `McpProxyController.java:197-229` |
| `/mcpservers/{serviceName}/message?sessionId=` | POST | `McpProxyController.java:237-288` |

**谁调**：外部 AI Agent / MCP 客户端。**凭据**：API 消费者（`mqk_api_…` 或 RS256 JWT）——**Virtual Key 在这里无效**。

**检查顺序（三个端点一致）**：认证 → 到期 (#322) → `mcp:call` 能力 (#316) → 服务名解析 → （`/message` 另加会话归属）。

| 失败 | 状态 | code |
|---|---|---|
| 凭据无效/未知/过期 | 401 | `invalid_api_key` |
| 缺 `mcp:call` 能力 | 403 | `consumer_scope_denied` |
| 服务名未知 | 404 | `mcp_service_not_found` |
| body 超缓冲 | 413 | `payload_too_large` |
| SSE 会话满（容量 256） | 503 | `session_capacity_exceeded` |
| 会话不存在/不属于该服务 | 404 | `unknown_session` |
| 会话属于别的消费者 | 403 | `session_credential_mismatch` |
| 上游超预算 | 504 | `mcp_upstream_timeout` |
| 熔断打开 | 503 | `circuit_open` |

**`/mcp` 的调用链**（`authorizeAndForward`，`:290+`）：解析 JSON-RPC 信封 → 只读 `method` 与 `tools/call` 的 `params.name`（**信封元数据是"不读正文"规则的唯一例外**）→ 服务级 ACL → 工具级 ACL（只能收窄）→ 后端凭证注入（`API_KEY` 模式注入 `Authorization: Bearer <解密后的后端密钥>`，fail-closed 502 `backend_auth_unavailable`）→ 重试/熔断（F12/F13，**默认全关**）→ 上游转发。

**`/sse` + `/message` 双端点**（#356，I11）：`/sse` 建**单节点内存会话**，首帧是 `endpoint` 事件给出 `/message?sessionId=…`，15s 注释帧保活，空闲 5 分钟被网关切流；`/message` 读完 body 立刻 `202 Accepted`，真正的 JSON-RPC 结果**沿会话流回**（`message` 事件或 `error` 事件）。会话仅进程内存、**重启即失效、不跨节点**。

**⚠ 坑**：

- **MCP 面不受配额门控制**。ADR-0020 D6 明确"MCP not in first release"，代码里 `McpProxyController` 没有 `QuotaGate` 调用。给 MCP 加配额拦截是**新功能**，不是修 bug。
- **`MCP_TIMEOUT` 是死常量**：`McpProxyController.java:107` 定义了 `Duration.ofSeconds(60)`，但实际超时用的是每服务快照值 `context.service.upstreamTimeoutMs()`（`:447, :471`）。改超时请改 `PUT /api/v1/admin/mcp-services/{id}/upstream-timeout` 那条路径，**改这个常量没有任何效果**。
- 上游流式 SSE 响应在 v1 里被**整段聚合成单条 `message` 事件**（不是逐事件转发）。
- 401/404（身份解析失败）的请求**不写 `mcp_access_log`**——与 `usage_event` 同口径，因为它们没有可信身份。
- `/message` 的会话绑定校验是**双重的**：服务 id 与会话的 serviceId 要相等（`:259`），消费者 id 也要相等（`:262`）。改这块时两个都要动。

---

## 4. 控制面管理 API（按域）

> 表中"权限"列：**ADMIN** = 路径在 `/api/v1/admin/` 下，`RoleInterceptor` deny-by-default 保证仅 `SYSTEM_ADMIN`（且匿名 401）；**会话** = 任意已登录用户，靠 `userContext` 自限定；**匿名** = `SessionFilter.PUBLIC_PATHS` 白名单。
> 所有 `/api/v1/admin/**` 的写操作默认还需要 CSRF + Origin（§2.4）。

### 4.1 认证与会话（`AuthController.java:52`，基路径 `/api/v1/auth`）

| 端点 | 权限 | 典型请求 | 关键响应字段 | 坑 |
|---|---|---|---|---|
| `POST /login`（`:70`） | 匿名 | `@Valid LoginRequest{username,password}`（各 `@NotBlank @Size(128)`） | `LoginResponse{id, username, displayName, role, mustChangePassword, sessionExpiresAt}` + 会话/CSRF Cookie | **没有 `status` 字段**（`/me` 有，两者形状不同）；失败一律手写 401 `UNAUTHORIZED`（`:86`），**不走全局处理器**；服务层有假哈希时序均衡 + 渐进延迟（`AuthenticationService.java:137-204`） |
| `POST /bootstrap`（`:90`） | 匿名（需 bootstrap secret） | `{username, displayName, password, bootstrapSecret}` | 201 `{…, temporaryPassword, shownOnce:true, sessionExpiresAt}` | **`bootstrapSecret` 有 `@Size(min=16,max=1024)`**——15 字符的 secret 得到 **400 `VALIDATION_FAILED`**，不是 401；`shownOnce` 是硬编码常量（`:102`）；租户行 `FOR UPDATE` 保证恰好一个管理员（`AuthenticationService.java:259-301`） |
| `POST /register`（`:115`） | 匿名（受开关） | `{username, displayName?, password}` | 201，**body 类型是 `LoginResponse`**（与 `/login` 同形）+ 同一套 Cookie | 错误靠 `ApiException` 直接抛出：403 `REGISTRATION_DISABLED`、400 `USERNAME_INVALID`、400 `PASSWORD_INVALID`、409 `USERNAME_TAKEN`；`mustChangePassword=false` |
| `GET /registration-status`（`:219`） | 匿名 | — | `{enabled: bool}` | **只有 1 个字段**；读的是与 `/register` 同一个 `@ConfigurationProperties` 绑定值，同进程内两者不可能不一致；**不写审计** |
| `POST /logout`（`:129`） | 会话 | — | `{"message":"Logged out"}`，清 Cookie | 方法内的 `isAuthenticated()` 分支**不可达**（`SessionFilter` 先 401） |
| `GET /me`（`:140`） | 会话 | — | `UserResponse` = `LoginResponse` **加 `status` 与 `lastLoginAt`** | 自身限定，无 id 入参；`sessionExpiresAt` 做了 null 保护 |
| `POST /password`（`:154`） | 会话 | `{currentPassword, newPassword}` | — | **⚠ 同一个字段两种错误码**：5 字符的新密码 → 400 `VALIDATION_FAILED`（bean validation，`newPassword @Size(min=8)`）；合规长度但违反策略/常见密码/当前密码错 → 400 **`PASSWORD_CHANGE_FAILED`**（所有 `AuthenticationException` 被折叠成一个码，`AuthenticationService`）。成功后撤销**其他**全部会话 |
| `POST /logout-others`（`:175`） | 会话 | — | — | 审计 `LOGOUT_OTHERS`；**刻意不清 Cookie**（当前会话继续有效）；**被 `mustChangePassword` 门槛拦截**（401 `PASSWORD_CHANGE_REQUIRED`） |
| `GET /csrf`（`:187`） | 会话 | — | `{token, expiresAt}` | **⚠ Cookie 缺失时静默返回 `token:""` + 200**（`:197`），不报错——失败要到下一次写请求才以 403 `CSRF_INVALID` 暴露 |
| `GET /oauth/providers`（`:32`） | 匿名 | — | `[{code,name}]`，**功能关闭时是 `[]`** | ADR-0017 |
| `GET /oauth/start`（`:38`） | 匿名 | — | 302 到平台 authorize + `MIQROKEY_OAUTH_STATE` Cookie（httpOnly、600s、path `/`） | **⚠ 该 Cookie 没有 `SameSite` 属性，且 state 不与任何既有会话绑定**——`/callback` 的唯一 CSRF 防御就是 state-cookie 等值比较 |
| `GET /oauth/callback`（`:50`） | 匿名 | `?code&state` | 302 到**硬编码的 `/app/keys`**（无 `returnTo`） | **⚠ 文档承诺的 `oauth_error=AUTH_ERROR` 覆盖不到 HTTP 层失败**：`code` 缺失/空白、或 token/userinfo 端点返回非 2xx 时抛 `RestClientResponseException`，**在 try 块之外**（`PlatformOidcAuthService.java:200-201, 217-218`）→ **500 `INTERNAL_ERROR`**，而不是重定向。契约 `:81-82` 列的 7 个错误码只覆盖解析失败 |

**⚠ 坑（本节最实用的一条）**：
- `SessionFilter.PUBLIC_PATHS`（`SessionFilter.java:51-53`）是**精确匹配**（`equals`，不是 `startsWith`），加公开端点必须同时改这里和（如需）`CsrfInterceptor.CSRF_EXEMPT`。`registration-status` **只在 PUBLIC_PATHS 里**——它能免 CSRF 纯粹因为 GET 会被 `CsrfInterceptor` 短路（`CsrfInterceptor.java:48-50`），**不是**因为进了豁免名单。
- **CSRF 豁免名单在文档里是漏的**：代码 `CsrfInterceptor.java:30-31` 是 `{login, bootstrap, register}`，`api-contract.md:93` 的正文只列了 login 与 bootstrap（同一文档 `:62` 又说 register 免 CSRF）——**文档 `:93` 是陈旧的**。
- `mustChangePassword=true` 的会话只能打 `/api/v1/auth/{password,logout,me,csrf}`（`SessionFilter.java:151-157`），**`/logout-others` 被刻意排除**。
- 登录失败有渐进延迟 + 账户锁定，失败计数在数据库行锁下自增。这段逻辑全在 `AuthenticationService`（534 行），改登录语义先读它。
- OIDC 自动建号用户**没有口令登录通道**（随机口令），这是刻意的。

---

### 4.2 自助面（`/api/v1/me/**`，任意已登录用户）

> 全部靠 `userContext` 自限定：查询条件里直接带 `user.id()` 或 `user.tenantId()`，拿不到别人的行。

#### 4.2.1 `MeVirtualKeyController.java:37` — `/api/v1/me/virtual-keys`

| 端点 | 典型请求 | 关键响应 | 坑 |
|---|---|---|---|
| `GET`（`:48`） | — | `VirtualKeyView[]` | 只含前缀与末四位，**永远无完整 Secret**；**包含 REVOKED 密钥**；`findAllByUserId` 的 SQL **没有 tenant 谓词**（`VirtualKeyRepositoryImpl.java:90-93`，按 `created_at DESC`）——UUID 全局唯一所以不可利用，但与同文件其它查询不一致 |
| `POST`（`:53`） | `@Valid CreateVirtualKeyRequest{name?, projectId, projectIds?, providerProductId, credentialGrantId, purpose, allowedModels?, cachePolicy?}` | 201 `{id, secret, baseUrl, display, shownOnce:true, createdAt, version, boundProjects[]}` | `secret` **只在本次响应出现**。校验顺序（`VirtualKeyService.java:138-220`）：404 `PROJECT_NOT_FOUND` → 400 `PROJECT_NOT_SELECTABLE`（系统/未归属桶项目）→ 409 `PROJECT_INACTIVE` → 409 `ROUTING_TAG_MISSING` → **403 `PROJECT_MEMBERSHIP_REQUIRED`**（SYSTEM_ADMIN 豁免）→ 400 `GRANT_INVALID`/409 `GRANT_INACTIVE` → 附加项目 409 `PROJECT_GRANT_MISSING` → 400 `MODEL_NOT_GRANTED`。**四条暗坑**：(a) **`projectId` 是 `@NotNull` 但 `projectIds` 非空时被静默忽略**（`:143-149`）——`projectId` 填错也不报错；(b) `projectIds` 的元素**没有校验**，`"projectIds":[null]` 会在 `List.copyOf` 处 **NPE → 500**（不是 400）；(c) 响应里的 `version` 是**硬编码 `1L`**（`:523`），不是真实版本；(d) **`allowedModels` 缺省/为空 = 授予该 grant 的全部模型**——省略这个字段授的权**更多**而不是更少 |
| `GET /{id}`（`:60`） | — | 单个视图 | 走 `ownedKey`（`:499-507`）：过滤 tenant 且（非 SYSTEM_ADMIN 时）`key.userId()==caller`，否则 **404 `KEY_NOT_FOUND`**。**IDOR 安全** |
| `POST /{id}/rotate`（`:65`） | — | 与创建相同的响应（新 Secret 一次） | 仅 `ACTIVE` 否则 409 `KEY_NOT_ROTATABLE`。**⚠ 是幂等的**：首次 rotate 把旧 Key 置 `ROTATING`，第二次因非 `ACTIVE` 必然 409；真并发时输家事务整体回滚、连新钥一起撤销 —— **至多一把替代钥**（`VirtualKeyRepositoryImpl.java:139-149`）→ 409 **`CONCURRENT_MODIFICATION`**，与文档里的 `KEY_NOT_ROTATABLE` **不是同一个码**。旧 Key 转 `ROTATING`，`revokedAt = now + miqrokey.virtual-key-rotate-grace`（默认 `PT0S`） |
| `POST /{id}/revoke`（`:70`） | — | `{"message":"Virtual key revoked"}` | 允许 `ACTIVE` 与 `ROTATING`；否则 409 `KEY_NOT_REVOCABLE`；**不幂等**（第二次 409） |
| `PATCH /{id}`（`:76`） | `@Valid UpdateVirtualKeyRequest{name}`（`@NotBlank @Size(200)`） | 视图 | 名称会被 `.trim()`；已 `REVOKED` → 409 `KEY_NOT_RENAMEABLE`；`ROTATING`/`DISABLED` **可改名**；**不触发路由快照刷新**（路由不依赖名称，契约 `:229` 已记） |
| `POST /{id}/disable`（`:82`） | — | 视图 | 仅 `ACTIVE` → 409 `KEY_NOT_DISABLEABLE`；发快照刷新；停用后网关按**未知密钥 404** |
| `POST /{id}/enable`（`:87`） | — | 视图 | 仅 `DISABLED` → 409 `KEY_NOT_ENABLEABLE`；**#1117**：停用期间若管理员把属主移出项目，绑定已被置 `DISABLED`，启用**不会**恢复它 |

**⚠ 契约漂移**：`VirtualKeyView` **总是带 `boundProjects`**（`dto/VirtualKeyView.java:20`），而 `api-contract.md:195-213` 的 §4.2 示例里没有这个字段。

#### 4.2.2 `MeGrantsController.java:27` — `GET /api/v1/me/grants`

返回 `{projects[], grants[], purposes[]}`。

- **作用域分两种**：非管理员 → `membershipRepository.findAllByUserId`（**只有自己是成员的项目**）；**SYSTEM_ADMIN → 该租户全部项目**（`VirtualKeyService.java:460`）。契约 `:172` 只描述了成员那一半。
- 过滤：项目必须 **ACTIVE 且非 system**（`:473-474`）——所以 `UNATTRIBUTED` 桶项目**永不出现**；grant 只列 ACTIVE。
- `GrantOption.providerProductCode/providerProductName` 在产品行缺失时为 **`null`**（`:485-486`），契约示例里没有这两个字段。
- 空成员 → 空数组 + 200。

**⚠ 坑（刻意的，代码里写了注释）**：这个列表**故意不过滤掉"创建时会拒绝的项目"**（`:436-452`）：没有 route tag 的项目仍然列出，因为 `ROUTING_TAG_MISSING` 会告诉用户去找管理员补。**不要"顺手"按准入条件过滤它**——那会把一条可操作的消息换成用户看不见也解释不了的项目（#1149）。

#### 4.2.3 `MeUsageController.java:21` — `/api/v1/me/usage/{summary,records}`

参数与响应形状见 §4.6（与管理员端**共用** `UsageStatsService` 的校验与聚合，作用域是"自己的 Key"）。

- **没有 Key 时不查用量表**，直接返回全零汇总/空页（`UsageStatsService.java:77-81`）。
- **⚠ `/me/usage/records` 没有 `page` 上限**（`UsageStatsService.java:90-97` 只校验 `page ≥ 1`），而**管理员端同一功能有 `MAX_PAGE = 1_000_000` 的溢出保护**（`AdminUsageStatsService.java:189-193`，#475）。`(page-1)*size` 溢出成负数 → `OFFSET` 为负 → 数据库报错 → **500 `INTERNAL_ERROR`**。这是本域唯一能靠参数打出 500 的地方。
- `groupBy` 的**控制器 javadoc 漏了 `TEAM`/`PRODUCT`**（`MeUsageController.java:37`），但枚举**接受**它们（契约 `:235` 是对的，代码注释是陈旧的）。
- 时间参数走 `@DateTimeFormat` → 格式错是 **400 `PARAM_INVALID`**；**对照计费通道的同类错误是 `TIMESTAMP_INVALID`**（§4.12）——同一个错误两种码。

#### 4.2.4 `MeQuotaController.java:33` — `GET /api/v1/me/quota-rules`

返回 `QuotaRuleView[]`，**只含 `scopeType==USER && scopeId==调用者` 的规则**（`AdminQuotaRuleService.java:75-79`）。停用规则仍可见；无规则返回空数组；**不写审计**（只读）。COST 规则同样带 `pricingStatus`/`unpriced`（口径见 §4.5）。

**⚠ 三个坑**：
1. **没有分页**——服务先**加载该租户全部配额规则**再在内存里过滤，并为每一行**计算一次水位**。规则多时这个 GET 很贵。
2. **用户看到的不是全部约束**：`PROJECT` 作用域规则、默认配额模板自动分配的规则等仍然约束着他，但**此端点不返回**（契约 §4.7 只承诺 USER 作用域）。所以**用户的实际有效限额可能比页面上显示的更严**——排查"为什么被拦了但我的配额还没到"时要先想到这条。
3. `scopeName`/`scopeTag` 对 USER 规则解析成的是**用户的 displayName/username**，不是规则名。

#### 4.2.5 `MeModelApprovalController.java:27` — `/api/v1/me/model-approvals`

| 端点 | 典型请求 | 坑 |
|---|---|---|
| `POST`（`:38`） | `@Valid SubmitModelApprovalRequest{virtualKeyId @NotNull, modelId @NotBlank @Size(128), reason @Size(500)}` | 归属走 `ModelApprovalService.ownedKey`（tenant+owner，SYSTEM_ADMIN 旁路），不匹配 **404 `KEY_NOT_FOUND`**。校验顺序：409 `KEY_NOT_ACTIVE` → 400 `MODEL_INVALID`（空白/超 128/控制字符）→ 400 `MODEL_ALREADY_AVAILABLE` → 409 `DUPLICATE_PENDING` → 404 `GRANT_NOT_FOUND` → **409 `MODEL_NOT_IN_CATALOG`**（#506）。**⚠ 同一个字段两种码**：129 字符的 modelId 撞 bean validation → **400 `VALIDATION_FAILED`**；短 id 里带控制字符 → **400 `MODEL_INVALID`**。白名单模型（`miqrokey.approval.whitelist-models`）**提交即 APPROVED**，`reviewedBy=null`、固定 `reviewNote`，写 SUBMITTED + APPROVED 两条审计 |
| `GET`（`:45`） | — | 自限定靠投影 `findAllByRequestedBy(user.id())`（`ModelApprovalRepositoryImpl.java:71-76`）——**SQL 里没有 tenant 谓词**（同上，UUID 全局唯一故不可利用）。**⚠ 排序只有 `created_at DESC` 没有 id tiebreaker**，而管理员端队列用的是 `(created_at, id)`——同秒创建的申请顺序不稳定，与契约"时间倒序"的承诺有出入。`requesterName` 对不存在或跨租户的用户显示字面量 **`"deleted user"``** |

#### 4.2.6 `MePlazaController.java:28` — `GET /api/v1/me/plaza/models`

**契约 §4 未记载此端点**（OpenAPI 基线里有，见 §7）。模型广场：会话要求、任意角色、完全自限定（`MePlazaService.java:75-136`）。

- 对调用者每把 **ACTIVE** 密钥：解析其**主** grant（`key.grantId()`）→ 要求 ACTIVE 且同租户 → 加载该产品 ACTIVE 的 `model_catalog` 行。
- `models` = 该 Key 的模型 ∩ grant 模型 ∩ ACTIVE 目录，按 `(产品, 模型)` 去重并附 `keys[]` 引用（id/name/掩码 display）；`requestable` = 该产品目录里**不在这把 Key 上**的模型（每个 `(模型,密钥)` 一行）。
- 价格取**当前**每 `(产品,模型,tokenType)` 的最新快照；**没有任何快照时整个 `price` 对象为 `null`**，某个 token 类型无价时该字段为 null，币种取第一个非 null（**不写 0 占位**，#878）。
- **⚠ 五个坑**：
  (a) **在 Key 上但在 grant 之外的模型，两个列表里都不出现**，也不可申请（`:109-110`）；
  (b) **grant 缺失/已撤销/跨租户的密钥被静默跳过**——没有 degraded/partial 标记（`:91-94`）；
  (c) 只看 ACTIVE 密钥，`DISABLED`/`ROTATING` 密钥的模型凭空消失；
  (d) **ADR-0018 的附加项目绑定被忽略**（只读 `key.grantId()`），多项目 Key 只显示主项目 grant 的模型；
  (e) **它把每百万 token 单价（含缓存读/写）暴露给普通用户**——是刻意的（#1201），但文档未记载。

#### 4.2.7 `SkillController.java:23` — `/api/v1/skills`

| 端点 | 权限 | 坑 |
|---|---|---|
| `GET /api/v1/skills`（`:34`） | **会话（任意用户）** | `q` **先 trim 再判长度**（`q.trim().length() > 60`）——60 个有效字符 + 任意首尾空白会**通过** → 400 `SKILL_QUERY_INVALID`（**不 trim**，`SkillService.java:94`）；`tags` 重复参数、空白丢弃、去重、**与**语义（`:97-102`），**tag 数量无上限**；只列 ACTIVE；**元数据面不做逐用户授权过滤**（契约 `:880` 已记，是有意的） |
| `GET /{skillId}`（`:40`） | 会话 | 租户隔离 + 只查 ACTIVE → 归档或跨租户技能一律 **404 `SKILL_NOT_FOUND`**；无归属校验（租户级目录） |
| `GET /{skillId}/download`（`:45`） | 会话 + 授权门禁 | 管理员旁路；否则按 TEAM/PROJECT 成员匹配；**零 `skill_access` 行 = 公开**；否则 403 `SKILL_DOWNLOAD_FORBIDDEN`。归档技能**在门禁之前**就 404。返回 `application/zip` + `Content-Disposition: attachment; filename=<name>.zip` |

**⚠ 坑**：这个控制器**不在 `/api/v1/admin/` 下**，也**没有任何 `@RequireRole`**（全仓 0 处），所以它是**任意登录用户可读**的。另外 `SkillService.java:49` 判断管理员用的是**字符串比较** `user.role().name().equals("SYSTEM_ADMIN")` 而不是枚举比较——能用，但脆弱。上传/归档/授权在 `AdminSkillController`（§4.10.4）。

#### 4.2.8 `BillingController.java:27` — `/api/v1/billing/**`

见 §4.12（独立通道，凭据不同）。

---

### 4.3 组织与身份（`/api/v1/admin/{users,teams,projects,grants}`）

> 全部 ADMIN。`AdminUserController.java:27`、`AdminTeamController.java:25`、`AdminProjectController.java:25`、`AdminGrantController.java:22`。

#### 4.3.1 用户

| 端点 | 典型请求 | 关键响应 | 坑 |
|---|---|---|---|
| `GET /users`（`:38`） | — | `AdminUserView[]` | **永不返回 `passwordHash`**（Jackson mixin 全局排除）；SYSTEM_ADMIN 行**也在列表里** |
| `GET /users/{id}/project-memberships`（`:44`） | — | `[{projectId,projectCode,projectName,projectStatus,joinedAt}]` 按 code 排序 | 用户不存在 → 404 `USER_NOT_FOUND` |
| `POST /users`（`:51`） | `{username,displayName,role}` | `{user, temporaryPassword}`（仅此一次） | **无 `@Valid`**，校验全在 `AdminOrgService.createUser`：用户名空白/超 128 → 400 `USERNAME_INVALID`；重名（**大小写不敏感**）→ 409 `USERNAME_TAKEN`；显示名 >200 → 400 `DISPLAY_NAME_INVALID`。**`role` 直接来自请求体且不加限制**——管理员可以再建一个 `SYSTEM_ADMIN`（文档未禁止，但值得知道） |
| `PATCH /users/{id}`（`:62`） | `{displayName?, status?}` | 视图 | **无 `@Valid`**；两项全空 → 400 `USER_UPDATE_EMPTY`；禁用 SYSTEM_ADMIN → 409 `ADMIN_NOT_DISABLEABLE`；`DISABLED`/`LOCKED` 会**撤销该用户全部会话**；`ACTIVE` 会清 `lockedUntil` |
| `POST /users/{id}/reset-password`（`:68`） | — | `{user, temporaryPassword}`（仅此一次） | 设 `mustChangePassword=true` 并撤销全部会话 |
| `POST /users/{id}/revoke-sessions`（`:74`） | — | — | 幂等，无 body |

**默认配额模板**：`createUser` 是 `@Transactional` 的，并在**同一事务内**调 `quotaDefaultTemplateService.applyToNewUser(...)`（`AdminOrgService.java:119`）。语义：模板未配置或未启用则 no-op；命中时建 `USER` 作用域规则（`metric/period/limitValue` 取自模板、`warnPercent=80`、`status=ACTIVE`、`action=ALERT`），用 `ON CONFLICT DO NOTHING` 插入——**手动规则永远优先**；仅在真的插入了行时才写 `QUOTA_RULE_CREATE` 且摘要含 `"auto":true`。**⚠ 这条审计的 `requestId` 是 `null`**。

#### 4.3.2 团队

| 端点 | 坑 |
|---|---|
| `GET /teams`（`:36`） | 租户过滤 |
| `POST /teams`（`:41`） | `@Valid`：`name @NotBlank @Size(max=200)`。**团队名不做唯一性检查** |
| `PATCH /teams/{id}`（`:47`） | `@Valid`：`name @Size(max=200)`（**没有 `@NotBlank`**，null 保持原值）；CAS 冲突 → 409 `CONCURRENT_MODIFICATION` |
| `GET /teams/{id}/members`（`:54`） | 联表时额外要求 `u.tenant_id = tm.tenant_id` |
| `POST /teams/{id}/members`（`:59`） | **无 `@Valid`**；`userId` 为空/跨租户 → 404 `USER_NOT_FOUND`；插入 `ON CONFLICT DO NOTHING` → **重复添加不报错**，但**仍会写 `TEAM_MEMBER_ADD` 审计** |
| `DELETE /teams/{id}/members/{userId}`（`:65`） | **成员不存在也返回成功**（不 404），并照写 `TEAM_MEMBER_REMOVE` 审计 |

#### 4.3.3 项目

| 端点 | 典型请求 | 坑 |
|---|---|---|
| `GET /projects`（`:36`） | — | 租户过滤 |
| `POST /projects`（`:41`） | `{code, name, projectTag?}` | `@Valid`：`code @NotBlank ≤64`、`name @NotBlank ≤200`。**保留 code `UNATTRIBUTED`**（精确匹配，大小写敏感）→ 409 `PROJECT_CODE_RESERVED`（小写形式仍合法）；`projectTag` 模式 `^[A-Za-z0-9_-]{1,64}$` 否则 400 `PROJECT_TAG_INVALID`；**tag 省略时自动从 code 派生 slug**（或 `proj-<uuid12>`）——管理员别留空，否则普通用户建钥会撞 `ROUTING_TAG_MISSING` |
| `PATCH /projects/{id}`（`:47`） | `{name, projectTag?}` | **改 tag 时若已有 `key_project_binding` 引用 → 409 `PROJECT_TAG_IN_USE`**；`system` 标志**不可写**（仓库 update 不设该列），但响应里会保留原值 |
| `GET /projects/{id}/members`（`:54`） | — | 联表要求用户同租户 |
| `POST /projects/{id}/members`（`:59`） | `{userId}` | **无 `@Valid`**，用户不存在 → 404 |
| `DELETE /projects/{id}/members/{userId}`（`:65`） | — | **有级联副作用**：把该用户在该项目的 ACTIVE 绑定置 `DISABLED`；若某密钥因此**没有任何 ACTIVE 绑定则整把吊销**；然后发快照刷新；审计摘要带 `bindingsDisabled`/`keysRevoked` 计数。重复调用计数为 0，但**仍写审计** |
| `GET/POST /projects/{id}/repositories`、`DELETE /{mappingId}`（`:75,80,87`） | CAA 项目注册表 | `repoKey` 接受 `github.com/a/b`、`https://…/a/b(.git)`、`git@github.com:a/b.git`、裸 `a/b`（默认 github.com），统一规范化为小写 `host/owner/repo`；重复 → 409 `REPO_KEY_TAKEN`；格式非法 → 400 `REPO_KEY_INVALID`；删除时 mapping 必须同时属于该租户**和**该项目，否则 404 `REPOSITORY_NOT_FOUND`。**⚠ 无 `@Valid`，且 `create`/`delete` 都没有 `@Transactional`**——插入 + 审计是两条自动提交语句 |

**⚠ 重要缺口**：**没有 `DELETE /api/v1/admin/projects/{id}`，也没有改 code 的端点**。所以一旦 `UNATTRIBUTED` 被非系统项目占用（409 `BUCKET_PROJECT_CONFLICT`），**产品面无法自救**，只能平台运维进库改名或删除。

#### 4.3.4 Grant（项目×产品×凭证×模型范围）

| 端点 | 典型请求 | 坑 |
|---|---|---|
| `GET /grants`（`:33`） | — | 直接返回领域行（含 `version`），不是视图 |
| `POST /grants`（`:38`） | `{projectId, providerProductId, credentialId, models[]}` | **无 `@Valid`**（所有字段可空）。校验顺序：项目 404 `PROJECT_NOT_FOUND` → 凭证 404 `CREDENTIAL_NOT_FOUND` → 产品 404 `PRODUCT_NOT_FOUND`（全局表，**不校验租户**）→ **凭证的订阅产品必须等于声明的产品**，否则 400 `GRANT_CREDENTIAL_PRODUCT_MISMATCH`（数据库触发器兜底）→ `models[]` 必须在 `model_catalog` 有行，否则 400 `MODEL_NOT_IN_CATALOG`（**仅当该产品的 catalog 至少有 1 行时才做白名单校验**，离线安装行为不变）→ 重复（**含已 DISABLED 的**）409 `GRANT_EXISTS`。⚠ `models` 里的 null/空白项**被静默丢弃**；`projectId`/`credentialId` 省略是 404 而不是 400 |
| `GET /grants/{id}/models`（`:45`） | — | 租户校验 |
| `POST /grants/{id}/models`（`:50`） | `{models[]}` | **⚠ 名字像追加，实际是全量替换**（先删光再插）。同样的 catalog 校验；写 `GRANT_MODELS` 审计；发快照刷新 |
| `DELETE /grants/{id}`（`:56`） | — | **⚠ 不是删除，是软停用**（`status=DISABLED`）。**没有"已停用"守卫**——停用已停用的 grant 一样返回成功并再写一条审计。停用后该项目的模型范围随之收缩（网关按 grant ∩ key 放行） |

---

### 4.4 凭证与订阅（`/api/v1/admin/{credentials,subscriptions,provider-products}`）

#### 4.4.1 上游凭证（`AdminCredentialController.java:34`）

| 端点 | 典型请求 | 关键响应 | 坑 |
|---|---|---|---|
| `GET /credentials`（`:45`） | — | `CredentialView[]`：掩码 `fingerprintPrefix`（前 8 字节 hex）、`activeVersionId`、`version`、`status` | 明文与完整指纹永不出现 |
| `GET /credentials/{id}`（`:50`） | — | 视图 + **完整版本历史**（新版本在前） | 非本租户 → 404 `CREDENTIAL_NOT_FOUND` |
| `POST /credentials`（`:55`） | `{name, subscriptionId, secret, seatId?}` | 201 掩码视图 | `@Valid`（name ≤200、secret ≤512）；订阅不存在 → 404 `SUBSCRIPTION_NOT_FOUND`；secret 格式非法 → 400 `CREDENTIAL_INVALID`；`seatId` 必须属于同一订阅与租户，否则 404 `SEAT_NOT_FOUND`；AES-256-GCM 加密，AAD 绑定 tenant+credential |
| `POST /{id}/validate`（`:65`） | `{secret}` | `{matchesActive, message, providerStatus, providerMessage, checkedAt}` | **纯检查，绝不写库**；`providerStatus ∈ VALID/REJECTED/UNREACHABLE/NOT_CHECKED`，**只有候选与生效版本指纹一致时才真去探活**，否则 `NOT_CHECKED`；探活阻塞上限 10s；**刻意不 `@Transactional`**（#728）；**不写审计** |
| `POST /{id}/rotate`（`:71`） | `{secret}` | 掩码视图 | 行锁 + 单事务：旧 ACTIVE 降 DRAINING（`retiredAt = now + miqrokey.credential-drain-grace`，默认 `PT0S`），新 ACTIVE 插入；部分唯一索引保证至多一个 ACTIVE；**非 ACTIVE → 409 `CREDENTIAL_NOT_ROTATABLE`**；**被 ACTIVE Agent 引用 → 409 `CREDENTIAL_REFERENCED_BY_AGENT`**（文案含 Agent 名） |
| `POST /{id}/disable`（`:77`） | — | `{"message":"Credential disabled"}` | 已 DISABLED/INVALID → 409 `CREDENTIAL_NOT_DISABLEABLE`；**同一个 Agent 引用守卫**；**没有 DELETE 端点**，disable 就是生命周期终点 |

**⚠ Agent 守卫的边界**：`CREDENTIAL_REFERENCED_BY_AGENT` 只在 **`rotate` 和 `disable`** 上生效，且只检查 **`status=ACTIVE` 的 Agent**（`agentRepository.findActiveByCredentialId`）。`create` 与 `validate` 不受影响。检查发生在行锁与状态守卫之后、任何写入之前——被拒时数据库与审计**零写入**。

#### 4.4.2 订阅与席位（`AdminSubscriptionController.java:32`）

| 端点 | 典型请求 | 坑 |
|---|---|---|
| `GET /subscriptions`、`GET /{id}`（`:43,48`） | — | 租户过滤；404 `SUBSCRIPTION_NOT_FOUND` |
| `POST /subscriptions`（`:53`） | `{providerProductId, name, billingMode?, planScope?, …}` | **无 `@Valid`**（全部字段无约束）；产品存在性检查**不带租户**（全局表）；默认 `billingMode=FIXED_SUBSCRIPTION`、`planScope=NONE`、`statusSource=MANUAL_UNKNOWN` |
| `PATCH /{id}`（`:61`） | 价格/币种/配额/状态 | **无 `@Valid`、无 `version` 字段**；读改写 CAS，冲突 → 409 `CONCURRENT_MODIFICATION`；**不发快照刷新**（与凭证/grant 不同） |
| `GET /{id}/seats`（`:68`） | — | `SeatView` 暴露 `version`/`assignedUserId`/`username`/`userDisplay`（同租户 LEFT JOIN） |
| `POST /{id}/seats`（`:73`） | `{externalSeatRef?, displayName?, assignedUserId?}` | **无 `@Valid`**。**⚠ 两个坑**：(1) 即使 `assignedUserId` 为 null 也**硬编码 `seat_status='ASSIGNED'`**，违反了 PATCH 路径强制维护的双条件；(2) **`assignedUserId` 不做存在性/租户校验**——外键只有 `assigned_user_id → users(id)` 与 `(tenant_id, subscription_id)`，跨租户或已删除的 id 会被接受，列表里显示空用户名 |
| `PATCH /{id}/seats/{seatId}`（`:80`） | `{version, assignedUserId?, displayName?}` | **这是本域唯一把 `version` 当请求体字段的端点**（`@NotNull Long version`）。非 ASSIGNED 释放会清空分配但保留显示名；两端不一致 → 400 `SEAT_ASSIGNEE_MISMATCH`；CAS 失败 → 409 `CONCURRENT_MODIFICATION`。**`assignedUserId` 依然不校验**。契约 §5.0b `:464` **没写这个必填 `version`** |

#### 4.4.3 供应商产品（`AdminProviderProductController.java:21`）

| 端点 | 坑 |
|---|---|
| `GET /provider-products`（`:30`） | **全局表，不做租户过滤**；视图暴露 `baseUrlHost`（**只给 host**，防御性解析）、`protocols`、`implementationStatus`、`balanceAuthority` |
| `GET /provider-products/{productId}`（`:35`） | 同样全局；404 `PRODUCT_NOT_FOUND`；返回**原始领域行**而不是视图 |
| `GET /provider-products/providers`（`:40`） | 全局 |

**⚠ 单租户部署下无害，但这是多租户前必须处理的例外。** 另外 `/providers` 与 `/{productId}` 是兄弟路径——Spring 优先字面量，且 productId 是 UUID，实际不冲突。

---

### 4.5 配额与预算

#### 4.5.1 配额规则（`AdminQuotaRuleController.java:29`）

| 端点 | 典型请求 | 关键响应 | 坑 |
|---|---|---|---|
| `GET /quota-rules`（`:41`） | — | `QuotaRuleView[]` + **读时计算的水位** | ⚠ **N 条规则 = N 次用量聚合**，规则多时这个 GET 很贵 |
| `PUT /quota-rules`（`:47`） | `{scopeType, scopeId, metric, period, limitValue, warnPercent?, status?, action?}` | 视图 | `@Valid`：`limitValue @Positive`、`warnPercent @Min(1)@Max(99)`；自然键 `(tenant, scopeType, scopeId, metric, period)` upsert，**PUT 即原地编辑**；scope 不存在 → 404 `SCOPE_NOT_FOUND`；默认 `warnPercent=80`、`status=ACTIVE`、`action=ALERT`。**⚠ 请求/响应都没有 version**，并发 PUT 靠 SQL `version = version + 1` 串行化，**最后写者胜**；重发同样的 PUT 也会 +1 |
| `DELETE /quota-rules/{id}`（`:52`） | — | 204 | **I21 删除前置依赖检查**：仍被 `QUOTA_THRESHOLD` 告警规则引用 → 409 `RESOURCE_IN_USE` + `dependencies[{type:"ALERT_RULE", id, name, detail:"已启用"\|"已停用"}]`。引用藏在 `alert_rules.scope_json->>'quotaRuleId'`（**jsonb，无外键**，所以"经 FK 梳理无引用面"的结论对它不适用） |

**水位口径**（读时计算，非预聚合）：`TOKENS` = 窗口内全部 token（input+output+cacheRead+cacheCreation）；`REQUESTS` = **到达上游**的请求数（缓存命中不达上游、不计入）；`COST` = 窗口内按价格快照估算的上游实付。窗口是 UTC 切片：DAILY 当日 / WEEKLY 周一起 / MONTHLY 当月 / YEARLY 自然年。`level` 四级：`NORMAL → WARNING(≥warnPercent) → NEAR_LIMIT(≥90%) → EXCEEDED(≥100%)`。

**⚠ COST 规则的"未定价"语义（#943）**：`used` 是**已定价部分之和**——窗口内没有生效价目的用量对 `used` 贡献 0。为免"未定价"与"确实没花钱"读成同一个数，**COST 规则**额外带 `pricingStatus`（`COMPLETE`/`PARTIAL`/`UNAVAILABLE`）与 `unpriced`（`PricingGap`：`unpricedEvents`/`unavailableEvents`/`unpricedHitEvents` + 各维度 token 数）。**`pricingStatus != COMPLETE` 时 `used` 是下界**。TOKENS/REQUESTS 规则这两个字段为 **`null`**。**执法语义不变**——`level`/`EXCEEDED` 仍只按 `used` 判。

**⚠ 超限动作（ADR-0020）**：`action=ALERT`（默认）只显示水位；`action=REJECT` 由控制面 `QuotaEnforcementService`（默认 60s 固定延迟）写入 `quota_enforcement` → 随快照下发 → 网关在**准入处**（Key 解析后、读 body 前）查内存集合，命中 → `429 quota_exceeded` + `Retry-After`（该作用域最早自愈的窗口结束时刻；多规则拦同一作用域取最早）。**近似语义**：判定按周期刷新，额度可能被超出一个评估周期内的量；页面水位与网关判定在一个周期内可能不一致。**不承诺恰好卡在 100%，不做限流。**

#### 4.5.2 默认配额模板（`AdminQuotaDefaultTemplateController.java:25`）

| 端点 | 坑 |
|---|---|
| `GET`（`:38`） | 从未配置时返回 `enabled=false` 且定义字段全 null |
| `PUT`（`:44`） | `@Valid`；**保留当前启用状态**（重新配置不会重新启用）；审计动作按返回 `version==0` 选 `..._CREATE` 或 `..._UPDATE` |
| `POST /enable`（`:51`） | 未配置 → 409 `QUOTA_TEMPLATE_NOT_CONFIGURED`；已启用 → 409 `QUOTA_TEMPLATE_ALREADY_ENABLED` |
| `POST /disable`（`:57`） | 已分配规则**全部保留**，只是新用户不再自动获得 |

**⚠ 结构约束**：模板只落在**新建用户**上（腾讯的"消费者"语义最近似本系统的用户）；**PROJECT 作用域不参与模板化**（刻意不发明）。已分配规则是普通规则，可随时手改/删除。

#### 4.5.3 项目月度预算（`AdminBudgetController.java:38`）

| 端点 | 坑 |
|---|---|
| `GET /budgets?month`（`:50`） | `month` 缺省当月；格式错 → 400 `MONTH_INVALID`；每行**实时算花费**（不是缓存值）；`level` 三级 `NORMAL/WARNING/EXCEEDED` |
| `GET /projects/{id}/budget?month`（`:55`） | **不做项目存在性/租户校验**——别人的项目 id 只会得到 404 `BUDGET_NOT_FOUND`，与"没有预算"不可区分（安全，但归因含糊） |
| `PUT /projects/{id}/budget`（`:61`） | `@Valid`：`month @Pattern \d{4}-(0[1-9]\|1[0-2])`、`amount @DecimalMin("0.01") @Digits(14,2)`、`currency [A-Za-z]{3}`、`alertThresholdPct` 0.01–100；项目必须存在且同租户 → 404 `PROJECT_NOT_FOUND`；upsert `(tenant, project, month)`。**⚠ `month` 在 PUT 是请求体字段、在 GET/DELETE 是查询参数**——同一个资源两种形状，客户端容易写错且不报错（会作用到默认/错误的月份） |
| `DELETE /projects/{id}/budget?month`（`:69`） | **I21 依赖检查是"月度"的**：只有删**当月**预算时才查 `BUDGET_THRESHOLD` 告警规则，有则 409 `RESOURCE_IN_USE`；删过去/将来月份**照常 204**（否则历史永远清不掉）。匹配时对存储值取 `LOWER(...)`——写入侧经 `UUID.fromString` 接受非规范拼写并原样入库，等值匹配会漏 |

**⚠ 预算是"只预警不阻断"**，`spent` = 当月分摊成本，`spentPct = spent/amount×100`。别把它和配额 `REJECT` 搞混——预算**永远不会**拒绝请求。

#### 4.5.4 未归属策略（`AdminUnattributedPolicyController.java:23`，V57）

| 端点 | 坑 |
|---|---|
| `GET`（`:34`） | 未配置返回 `{configured:false,…}`；凭证被 ≥1 个 ACTIVE 项目 grant 引用时响应带 **`warning`**（建议专用凭证，**不阻断**） |
| `PUT`（`:39`） | **无 `@Valid`**。`credentialId` 为空 → 400 `POLICY_INVALID`；凭证不存在/停用/跨租户 → 404 `CREDENTIAL_NOT_FOUND`；显式 `providerProductId` 与凭证订阅产品不符 → 400 `UNAUTH_CREDENTIAL_PRODUCT_MISMATCH`；模型不在 catalog → 400 `MODEL_NOT_IN_CATALOG`。首次配置**懒建**「未归属」系统项目（`projects.system=true`，不可被建 Key 选择 → 400 `PROJECT_NOT_SELECTABLE`）；若该 code 已被**非系统**项目占用 → **409 `BUCKET_PROJECT_CONFLICT`（不静默收养）**。**⚠ 不是 `@Transactional`**——建项目、写策略、审计、刷新是四条自动提交语句，无版本、最后写者胜；**审计 `requestId` 恒为 null**（控制器不读 `X-Request-Id`） |
| `DELETE`（`:46`） | 只删策略行，**桶项目保留**（历史用量引用它）；未配置时**静默 200**（不 404）；**仅在真的删了行时才写审计** |

#### 4.5.5 主密钥批量重加密（`AdminCryptoController.java:21`）

`POST /api/v1/admin/crypto/reencrypt`（`:37`）。覆盖三处落库密文：上游凭证版本、Webhook 签名密钥、MCP 后端密钥（留痕载体经 Kafka 出站不落库，不在迁移面）。

- 逐行解密（按行内密钥版本 + 原 AAD）后按当前 active 版本重加密；回写用 CAS（`WHERE id AND key_version = 旧版本`）→ 并发生命周期写入计为 `skipped`。
- 单行失败隔离：失败行保持原样、计入 `failed`、`failures[]` 带行 id（上限 50），批量继续。
- 响应 `{activeKeyVersion, scanned, reencrypted, skipped, failed, remaining, failures[]}`，**只有计数与行 id**。
- **`remaining = 0` 是退役旧密钥版本的前置条件**（`remaining > 0` 时移除旧版本会让对应密文解不开，fail-closed）。
- **⚠ 同步执行，单次请求内可解密最多 3×10000 行**（每表 10000 上限）。不是 `@Transactional`（逐行自动提交），中途失败会留下部分迁移但**仍然一致**的表——`remaining` 是诚实的信号。
- HMAC 密钥环**不可批量迁移**（Virtual Key 摘要单向、原值不落库），退役任一 HMAC 版本即让该版本签发的全部 Key 失效。

---

### 4.6 用量与计费

#### 4.6.1 用量查询三端点（`AdminUsageController.java:28`）

| 端点 | 参数 | 坑 |
|---|---|---|
| `GET /api/v1/admin/usage/summary`（`:69`） | `groupBy`（默认 `project`）、`from`/`to`（默认近 93 天，窗口 >93 天拒绝）、`tzOffsetMinutes`、过滤 `userId`/`projectId`/`virtualKeyId`/`credentialId`/`subscriptionId`/`providerProductId`/`modelId`/**`teamId`** | `groupBy` 增补值：`user`/`model`/`month`（I15）、`day`/`month` 按**调用方给定固定偏移**分桶（`tzOffsetMinutes` ∈ [-1080,1080]，越界 400 `TZ_OFFSET_INVALID`，与会话/服务器时区无关）、`team`（同用户多团队**分别计入**，是归属视图不是分割口径）、`product`（#758）。**⚠ `teamId` 是已实现但契约 §5.2 未记载的过滤参数** |
| `GET /api/v1/admin/usage/records`（`:88`） | 同上 + `page`/`size` + **`clientIp`** | 唯一支持 `clientIp` 精确匹配的端点（#605，盗用排查）。`summary` **没有** `clientIp`。**分页有溢出保护**：`page ∈ [1, 1_000_000]`（`AdminUsageStatsService.java` 的 `MAX_PAGE`，#475），`size ∈ [1,200]`，越界分别 400 `PAGE_INVALID`/`SIZE_INVALID`——**个人端没有这个 `MAX_PAGE` 上限**（§4.2.3）。`clientIp` 会被 trim、空白转 null。**不接受 `tzOffsetMinutes`** |
| `GET /api/v1/admin/usage/hourly`（`:109`） | `date`（默认 `tzOffsetMinutes` 下的今天）、`days`(1–7)、`dimension`(`NONE\|USER\|TEAM`)、`tzOffsetMinutes`、`userId`、`projectId`、**`teamId`** | 返回 `{date, days, dimension, tzOffsetMinutes, rows[]}`；`hourStart` 是**小时桶边界的 UTC 瞬时**（UTC+8 下 14:00 桶 = `06:00Z`），由客户端按本地时区格式化；**只返回有用量的桶**；错误码 `DAYS_INVALID`/`DIMENSION_INVALID`/`DATE_INVALID`/`TZ_OFFSET_INVALID`。**⚠ `tzOffsetMinutes` 的校验是就地复制的一份，没有调用 `tzOffset()` 助手**——改那个助手**不会**影响这个端点 |
| `GET /api/v1/admin/usage/timeline`（`:53`） | `gatewayRequestId`（声明为**可选**，但服务层对空白值抛 **400 `REQUEST_ID_REQUIRED`**；未知/跨租户 → **404 `REQUEST_NOT_FOUND`**） | **⚠ 契约 §5.2 未记载此端点**（OpenAPI 基线里有、前端在用）。单次调用的阶段时间线（#705），返回 `ModelCallTimelineView`：`gatewayRequestId/upstreamRequestId/modelId/wireProtocol/streaming/status(可能是 IN_FLIGHT)/httpStatus/clientCancelled/partialResponse/retryCount/startedAt/firstByteAt/completedAt/durationMs/timeToFirstByteMs/tokens{四类，可空}/attribution{userId,projectId,virtualKeyId,providerId,providerProductId,credentialId}/phases[]`。`phases` **只由非 null 的时间戳构成**：恒有 `ACCEPTED`（elapsedMs 0），有首字节才有 `FIRST_BYTE`，有完成才有 `COMPLETED`；`elapsedMs` **从 startedAt 量起**，**刻意不等于网关的 TTFB**（避免非单调时间线）。`timeToFirstByteMs` 从网关入口量起，**不能与 `phases` 的偏移直接相加比较**。**从未到达上游的调用（合并请求、缓存命中）不写生命周期表 → 404**。消费者：`frontend/src/api/index.ts:1208` ← `NextAdminUsageView.vue:919` 的逐行抽屉 |
| `GET /api/v1/admin/usage/roi`（`AdminRoiController.java:34`） | `from`/`to`（**就地解析字符串**，不是 `@DateTimeFormat`；空白 → 默认近 30 天）、`tzOffsetMinutes` | 返回 `{from,to,totals,byDay}`；`hitRatePct` = (L1+L2)/(上游+coalesced+命中)×100；`savedPct` = savedCost/(paidCost+savedCost)×100；**零缓存事件也产出完整报表**。**⚠ 三个坑**：(a) 契约 §5.20 的响应示例**漏了实际序列化的 `pricingStatus`/`unpriced`**——不是 COMPLETE 就说明 `paidCost`/`savedCost` 偏小；(b) **`totals.hitRatePct` 在没有任何请求时是 `null`**（未定义 ≠ 0），但 **`byDay[].hitRatePct` 在空的一天返回 `0.00`**——同一个事实两个表示；(c) ISO 格式错 → 400 `PARAM_INVALID`（不是 `TIMESTAMP_INVALID`） |

**响应字段的特殊语义（全链路共用一个聚合器，改一个会牵动全部）**：

- `requests` 四分类 `upstream/coalesced/l1Hit/l2Hit`；`tokens` 四分类 `input/output/cacheRead/cacheCreation`。
- `cost`：`upstreamPaid`（上游实付）/ `gatewayObserved`（含合并请求）/ `projectAllocated` / `savedByGatewayCache`（缓存省下的钱，**不计入 `projectAllocated`**）+ **`upstreamPaidParts`/`gatewayObservedParts`**（#1097，按 token 维度拆开的四类，**四者之和精确等于对应合计**）。
  **⚠ 为什么是两组**：两个合计覆盖的**行集不同**（合并请求进 `gatewayObserved` 不进 `upstreamPaid`；缓存节省两边都不进）。**拿一组分项去配另一个合计一定对不上账**——这是刻意设计，不是 bug，别"修"。
- `outcomes`：`succeeded = 转发+合并 − failed − cancelled`。**⚠ 客户端取消（`CLIENT_CANCELLED`）既不算成功也不算失败**，不进成功率两侧；无生命周期行的合并请求计成功侧。`avgDurationMs`/`avgTtfbMs` **只在真的观测到取值的行上平均，无观测为 `null`**。
- 明细行的 `cost`/`priced`（#758）：`priced=false` 表示**任一维度存在非零 token 但缺单价**（含缓存读/写维度），前端显示"未定价"，此时 `cost` **不可信**。缓存读/写缺价按 0 计且**不触发该标记**（真实供应商常不单列缓存写费率）。
- 归属四字段（#1128/#1139）：`resolutionStatus`（`RESOLVED_HEADER`/`RESOLVED_SUFFIX`/`SOLE_BINDING`/`POLICY_ROUTED`，另保留 `UNATTRIBUTED`/`AMBIGUOUS` 但当前不产出）、`resolutionCandidates`（候选基数；**`null` = V72 之前写入的行，消费方不得推测为 1**）、`claimSource`（7 值）、`claimConfidence`（4 值）。
  **⚠ `claimSource`/`claimConfidence` 为 `null` 有两种无法区分的情形**：客户端没发头，或发了但未通过校验——所以它们**不承载"客户端一定没声明"的语义**。**`resolutionStatus` 为 `null` 只出现在 V54 之前的行。**
- **刻意不外露**：`session_id` / `activity_id` / `claimed_project_id` / `request_context_evidence` 证据表。管理端与对外只读通道**同口径**——要加进响应得先改契约。
- `clientIp`（#605）：传输层对端；**只在直连对端命中 `MIQROKEY_TRUSTED_PROXY_CIDRS` 时才消费 `X-Forwarded-For`，且从右往左取第一个非可信地址**；非 IP 字面量一律不记录。
- `cacheLevel ∈ UPSTREAM|COALESCED|L1_HIT|L2_HIT`；缓存命中行没有 token 数（NULL→0）且 `isComplete=false` 时不作为上游用量计入。
- `usageMissing=true`：上游没回 usage（如异常中断），**该行仍入账但用量为 0**，便于排查。

**过滤语义**：无过滤 = 整个租户（管理员可见全部用户的用量，与个人端严格自见形成对照，是刻意行为）。非法 UUID 过滤参数 → 400 `PARAM_INVALID`；分页/时间窗/groupBy 错误码 `PAGE_INVALID`/`SIZE_INVALID`/`TIME_RANGE_INVALID`/`TIME_RANGE_TOO_WIDE`/`GROUP_BY_INVALID`。

#### 4.6.2 用量调整（`AdminUsageAdjustmentController.java:38`，F20）

| 端点 | 典型请求 | 坑 |
|---|---|---|
| `POST /usage-adjustments`（`:57`） | `{gatewayRequestId, inputTokensDelta?, outputTokensDelta?, cacheReadTokensDelta?, cacheCreationTokensDelta?, reason, reasonCode?, reversalOfId?, idempotencyKey?}` | **追加型，绝不覆盖原始事实**——`usage_event` 永不被改写。至少一个非 0 增量；带 `reversalOfId` 时**按被撤销行取反并忽略请求里的增减量**。**同一笔修正至多被反向一次**（第二次会把净额推到观测值之上）。**⚠ 幂等重放仍返回 201**（与新建同状态码，只是返回已存的行）——调用方无法从状态码区分。校验码：`REQUEST_ID_REQUIRED`/`REASON_REQUIRED`/`REASON_TOO_LONG`(>1000)/`REASON_CODE_TOO_LONG`(>32)/`IDEMPOTENCY_KEY_TOO_LONG`(>128)、`ADJUSTMENT_EMPTY`(400)、`ADJUSTMENT_WOULD_GO_NEGATIVE`(400)、`ADJUSTMENT_TARGET_HAS_NO_USAGE`(400，缓存命中行不承载用量)、`REVERSAL_UNSUPPORTED`(400，金额维度不可写)、`REVERSAL_TARGET_MISMATCH`/`REVERSAL_OF_REVERSAL`/`ADJUSTMENT_ALREADY_REVERSED`(400)、`USAGE_EVENT_NOT_FOUND`/`ADJUSTMENT_NOT_FOUND`(404)。响应 `UsageAdjustmentView` 的 `amountDelta`/`currencyCode`/`reconciliationRowId` **今天恒为 null** |
| `GET /usage-adjustments?gatewayRequestId`（`:70`） | `gatewayRequestId` **必填** | 空白 → 400 `REQUEST_ID_REQUIRED`；未知 → 404 `USAGE_EVENT_NOT_FOUND`。按录入时间**正序** |

**⚠ 口径边界（最容易踩）**：调整计入**财务/报告口径**（明细、汇总、计费、导出）；**配额判定仍只读 `usage_event`**——财务更正**不得**追溯改写运行时控制的历史结果。另外 `amount_delta` 金额维度表结构已备但**未开放写入**，目前只能调 token。

**⚠ 幂等两套**：请求体 `idempotencyKey` 落在 `(tenant_id, idempotency_key)` 部分唯一索引上；**这不是**契约 §1 里"预留未实现"的 `Idempotency-Key` 请求头。

#### 4.6.3 价格基座回填（`AdminUsagePriceBackfillController.java:34`，F21-A）

`POST /api/v1/admin/usage-price-backfill?from&to`（`:54`）。把"这笔 token 当时依据什么价格计算"冻结到行上。

- 取值：`price_snapshot` 中 `effective_from <= 该行 occurred_at` 的最新一行（同 `effective_from` 用 `id DESC` 做确定性 tie-break）。**不是按回填时刻**——否则会造出"看起来是历史快照、实际是延迟快照"的假象。
- 返回 `{scanned, complete, partial, unavailable, baseCostFilled, reclassified}`。**`UNAVAILABLE` 的行价格列保持 NULL，不写 0**——"价格未知"与"免费"是不同的审计事实。
- **盖章幂等**：只处理 `price_status IS NULL` 的行；**价格列与金额永不改写**。
- 同时补写 `base_cost_amount`（#771，只从该行已冻结的 `price_*` 派生，**只能补全不能修订**）与重算派生标签 `price_status`（#777，判据演进会让旧标签变成**错数据**，本趟只纠标签、不动金额）。**这条取代了此前无条件的"已定状态的行永不重评"**——被保护的不变式是"不重估价格、不移动金额"，不是"标签不可纠正"。
- 定时收敛：`miqrokey.usage-price-reconcile.enabled`（默认关）每 `cycle-ms` 扫最近 48 小时；定时通道以**无操作人**记同一条审计。

#### 4.6.4 计量类写操作（`/api/v1/admin/{usage-deletions,exports,reconciliations}`）

见 §4.7。它们与用量同域但形状差异大（干跑/确认、异步任务、上传对账）。

---

### 4.7 审计 / 留痕 / 对账 / 导出

#### 4.7.1 审计事件（`AdminAuditController.java:35`）

| 端点 | 参数 | 坑 |
|---|---|---|
| `GET /api/v1/admin/audit-events`（`:46`） | `size`（默认 50）、`action`、`targetType`、`actorId`、`from`/`to`、`beforePosition` | 因果序倒排（`chain_position DESC`，`beforePosition` = `chain_position < cursor`）；每行带**只读** `targetName`（#389：按页内 `(targetType,targetId)` 批量子查询解析的资源名，租户内未知类型或引用已不存在为 `null`，前端回退短 ID；**链上数据与导出不变**）；`actorName` 对系统/已消失用户为 null；**哈希永不序列化**。**⚠ `size` 是静默 clamp 到 1..200**（不 400）；`from`/`to` 由本地 `parseInstant` 解析，格式错 → 400 `PARAM_INVALID`（**不是 `TIMESTAMP_INVALID`**）。**读审计不写审计**（对比留痕读取是写的） |
| `GET /api/v1/admin/audit-events/export`（`:56`） | 同筛选，`produces=text/csv` | RFC 4180 + UTF-8 BOM；列 = `created_at,action,target_type,target_id,actor_id,change_summary,chain_position`，**不含哈希链与正文**；上限 5 万行，超出以 `X-MiQroKey-Truncated: true` 声明；空结果 = 仅表头 CSV。**⚠ 这个导出没有 93 天窗口上限**——`validateWindow` 只查 `from > to` → 400 `TIME_RANGE_INVALID`。**⚠ 公式注入防护没有"数字豁免"**（对比 reconciliation 导出是有的）。**5 万行是行数上限不是字节上限**：导出是流式的，一旦开始吐字节，HTTP 层没有"先算大小再决定"的机会——要硬字节预算须走异步导出任务 |

#### 4.7.2 内容留痕（`AdminRetentionConfigController.java:21` + `AdminRetentionLogController.java:27`，ADR-0014）

| 端点 | 坑 |
|---|---|
| `GET /retention-config`（`:31`） | 无行时返回 `RetentionConfig.disabled()` 默认视图；只读不写审计 |
| `PUT /retention-config`（`:39`） | 体 `{enabled, maxContentBytes?}`；`maxContentBytes` 默认 262144、范围 1024–4194304，越界 400 `RETENTION_CONFIG_INVALID`；超限按 UTF-8 边界**截断**并置 `truncated=true`；**无 bean validation**（校验在构造器里）；变更即经快照下发，运行中生效；审计 `RETENTION_CONFIG_update`（**带新 version**）。**⚠ 两个坑**：(a) **`{"enabled": null}` 会静默变成"停用"**（`Boolean.TRUE.equals(...)`），不是 400；(b) **这条端点的 `requestId` 缺省值是空串 `""` 而不是 null**——本域独有的行为。v1 固定 `contentScope=USER_TEXT_ONLY` 与 `keyVersion=v1` |
| `GET /retention-logs`（`:40`） | 分页**解密查看**（`userId`/`direction`/`protocol`/`from`/`to`、`page`/`size`）。`direction` 大小写不敏感 `INPUT\|OUTPUT`，非法 → 400 `PARAM_INVALID`；`page < 1` → 400 `PAGE_INVALID`，**但 `size` 是静默 clamp 到 1..100**（同一个处理器里两种分页错误策略）。**每一次读取都写 `RETENTION_LOG_VIEW`**（包括空页）。**⚠ 返回行里的解密 `text` 与 `dataMd5` 在密钥版本无法解密时（已退役/被篡改）都是 `null`**——只给元数据，**不抛 500**。**仅 SYSTEM_ADMIN** |
| `GET /retention-logs/export`（`:54`） | CSV，形状同审计导出（UTF-8 BOM、RFC 4180、5 万行上限、`X-MiQroKey-Truncated` 声明、空结果=仅表头）。**⚠ 与 reconciliation 导出不同，这里不发 `X-MiQroKey-Rows`**。`direction`/`from` 非法时**在写审计之前就失败**（不产生审计行）；先 count 后流式（500 行一个 keyset 块），因为响应一旦开始就无法再写头。审计 `RETENTION_LOG_EXPORT` 记 `{rows: min(total,50000), truncated}` |

**⚠ 默认全关**：无 `retention_config` 行 = 任何请求内容都不被采集。"不保存正文"的红线**只在这行 enabled 时**按 ADR-0014 §1 例外放行。数据由**可选内置消费端**（`miqrokey.retention.consumer.*`，默认关）从 `content-retention` topic 幂等落库；**行内保持密文**，明文只在控制面解密路径出现且仅出现在受审计的管理员响应中。

#### 4.7.3 原始记录导出（`AdminExportController.java:34`）

| 端点 | 坑 |
|---|---|
| `POST /api/v1/admin/exports?format=CSV\|JSONL&from&to`（`:46`） | 202 + `ExportTaskView`（纯元数据，无字节）；窗口 ≤93 天；异步执行。审计 `EXPORT_CREATE` 与插入**同事务**（`:99-101`），产物渲染在提交后调度。`format` 非法 → 400 `PARAM_INVALID` |
| `GET /exports/{taskId}`（`:59`） | 状态，**不含产物字节**；只读不写审计。**⚠ 契约 §5.5/§6 把 `EXPIRED` 列为可能状态，但代码里 `ExportStatus.EXPIRED` 从来没有被写入过**——过期只体现为下载时的 410，GC 之后连行都没了。**状态端点永远不会报 EXPIRED** |
| `GET /exports/{taskId}/download`（`:65`） | gzip 产物 + `X-MiQroKey-SHA256` + `Content-Disposition: attachment; filename="miqrokey-usage-{id}.{format}.gz"`；审计 `EXPORT_DOWNLOAD`（`format`/`rows`/`bytes`/`sha256`）。**⚠ 410 `EXPORT_EXPIRED` 同时覆盖"尚未 SUCCEEDED"和"已过期"两种情况**（一个码两个语义）。**⚠ 被拒绝的下载（410/404）不记事件**——该 action 的语义是"产物已交付"（刻意，`api-contract.md:671` 已记）。F06 GC 清理后行没了 → **404**（410 只在清理前可观测） |
| `GET /exports?limit`（`:80`） | 最近任务列表，只读不写审计；**`limit` 静默 clamp 到 1..50**；元数据查询**从不读 `file_bytes`** |

**产物语义（改导出必读）**：
- 只含**计数与元数据列**，绝不含 prompt/代码/Secret/Virtual Key 明文。
- `local_caliber_note` = `local-instant`（本地即时记账口径；供应商官方账单通常 T+1 滞后，**对账勿以官方值直接核对本地明细**）。
- **`reconcileLevel`（#330，V41）**：任务完成时按 `provider_request_id` 覆盖度声明 `PROVIDER_ID_BACKED`/`PARTIAL`/`LOCAL_ONLY`；空窗口/历史任务为 `null`。文件内同步 `local-instant;reconcile=provider-id|mixed|local-only`。
- **`adjustmentLevel`（#716，V67）**：另一条轴——**这份文件的数字里含不含修正**。`PRESENT`/`NONE`/`null`。文件内 `;adjustments=present|none`。**⚠ 为什么与 `reconcileLevel` 分开**：两者是互相独立的问题（"能不能按请求 ID 对上账单"与"数字里含不含修正"），合进一个枚举就得为每种组合造一个值。它**按任务声明**而非只在行上标注，是因为消费者希望在读文件**之前**就知道 `net*` 列要不要看。
- **净额列（#709）**：`netInputTokens`/`netOutputTokens`/`netCacheReadInputTokens`/`netCacheCreationInputTokens` + `adjusted`。既有观察值列**保持原样**；`adjusted` 按行数判定，**一笔修正被冲销后仍为真**（此时净额等于观测值，`adjusted` 是该行唯一还能说明"被改过"的痕迹，#774）。净额口径与明细、汇总**共用同一段 SQL**（`UsageAdjustmentSql`），避免三处算法漂移。
- **表头对齐（#754 的教训）**：CSV 表头曾漏 `clientIp` 一列导致**自 `isComplete` 起每列错位一格**，且不报错。现在表头与数据行**由同一份声明的列顺序派生**，并有"按列名取值"的回归测试。**改导出列时不要手写表头字符串。**
- GC（F06）：`SUCCEEDED` 且过 `expires_at` 的行连 `file_bytes` 物理删除；清理后下载 → 404 `EXPORT_NOT_FOUND`（410 语义仅在清理前可观测）。`FAILED`/`PENDING` 行保留。
- 错误码：`TIME_RANGE_INVALID`/`TIME_RANGE_TOO_WIDE`(400)、`EXPORT_EXPIRED`(410)、`EXPORT_NOT_FOUND`(404)。

#### 4.7.4 用量删除（`AdminUsageDeletionController.java:29`）

| 端点 | 坑 |
|---|---|
| `GET /usage-deletions/preview?from&to`（`:40`） | 干跑计数，返回 `{count}`；窗口 ≤93 天（`TIME_RANGE_INVALID`/`TIME_RANGE_TOO_WIDE`）；**不写审计** |
| `POST /usage-deletions?from&to`（`:48`） | 一次性确认 token **只在本次响应出现**（`{id, previewCount, confirmToken, expiresAt}`，仅存 SHA-256 哈希），TTL 1 小时；窗口重新校验一次。**创建/预览/列表都不写审计**——只有 confirm 写 |
| `POST /usage-deletions/{id}/confirm`（`:56`） | 携带 `{"confirmToken":"…"}` 确认并**永久物理删除**（无软删除）。404 `DELETION_NOT_FOUND`（行锁 `FOR UPDATE`）；状态不对 → 409 `DELETION_NOT_CONFIRMABLE`；超时 → 410 `DELETION_EXPIRED`；token 错 → 403 `DELETION_TOKEN_INVALID`（**常量时间比较**）。执行后写 `USAGE_DELETE` 审计（含删除条数）。**⚠ 坑：`confirmToken` 缺失或为 `null` 时 `sha256(null)` 抛 NPE → 500 `INTERNAL_ERROR`**，而不是文档承诺的 403 |
| `GET /usage-deletions?limit`（`:63`） | 列表**永不返回 token**；**`limit` 静默 clamp 到 1..50**；不写审计 |

执行后写 `USAGE_DELETE` 审计，**审计链本身永不删除**。GC 清理 `PENDING_CONFIRMATION`/`CONFIRMED`/`EXPIRED` 且过期的行；**`EXECUTED` 行永久保留**。

#### 4.7.5 账单对账（`AdminReconciliationController.java:35`，F19）

| 端点 | 坑 |
|---|---|
| `POST /reconciliations?providerCode&currency&windowFrom&windowTo`（`:49`） | body = canonical JSONL（UTF-8，`.gz` 可选——**按 gzip 魔数自动识别**）→ 202 + PENDING 报告。上传上限 16MB（解压 64MB / 10 万行；超限**或 gzip 损坏** → 400 `RECONCILIATION_UPLOAD_INVALID`）。**幂等**：同 `(providerCode, window, currency, uploadSha256)` 返回既有报告（`FAILED` 除外，可重试），用 `pg_advisory_xact_lock` 串行化查找+插入；**命中去重的调用不写第二条 `RECONCILIATION_CREATED`、也不启动第二次解析**。**不写 `usage_event`，只存 SHA-256 与大小，不存上传内容**。**⚠ JSON 里 `id` 是字符串**，`amountDiff` 是字符串或 null |
| `GET /reconciliations?limit=`（`:62`） | 新→旧，`limit` 1..100 默认 20，**越界 400 `RECONCILIATION_PARAM_INVALID`（拒绝，不是 clamp）**；空列表 `[]`。返回 `{reports:[…]}` |
| `GET /reconciliations/{id}`（`:67`） | 汇总 `totalRows/matched/partialBuckets/unmatchedProvider/unmatchedLocal/lineErrorCount/amountDiff` + `uploadSha256/uploadBytes` + `status`；不存在 → 404 `RECONCILIATION_NOT_FOUND`；`errorMessage` 在 FAILED 之前为 null |
| `GET /{id}/rows?state=&cursor=&limit=`（`:73`） | 四态明细（`MATCHED`/`PARTIAL`/`UNMATCHED_PROVIDER`/`UNMATCHED_LOCAL`，**大小写不敏感**，垃圾值 → 400 `RECONCILIATION_PARAM_INVALID`）；`cursor` 是 `row_no`（Long）；`limit` 默认 100、**静默 clamp 1..500**。**⚠ 翻到底时 `nextCursor` 是空串 `""`，不是 null 也不是字段缺失**——客户端必须用真假值判断"没有下一页"，判 null 会漏 |
| `GET /{id}/export?state=`（`:86`） | CSV 合规导出；UTF-8 BOM、RFC 4180 + 公式注入防护、5 万行上限、`X-MiQroKey-Truncated: true`（**只在真的超限时才发**）、**恒发 `X-MiQroKey-Rows`**（本仓库唯一发这个头的导出）。文件名 `reconciliation-{id}-{stamp}.csv`（**不是 gzip**）。**空结果返回仅表头的 CSV**（不是错误）。**⚠ 裸十进制字面量（如 `-12.34`）原样输出、不加防护单引号**——它是数字不是公式；仅"形似数字"的串（`-1+1`、`+cmd\|' /C calc'!A0`）仍按公式防护。审计 `RECONCILIATION_EXPORT`（摘要 `{rows, truncated}`，`rows` 为**截断后**实际导出的行数）**写在响应体之前**，截断/失败的响应也留痕；但**报告不存在时 404 在审计之前抛出**（不留痕） |

**⚠ 三个必踩的语义**：
1. **窗口是半开区间 `[windowFrom, windowTo)`**——`windowFrom` 含、`windowTo` 不含（#1045）。**按闭区间切分账单文件的调用方会让边界行从 MATCHED 翻成 UNMATCHED，而报告不会解释原因。**
2. **`providerCode` 取的是 `provider_products.product_code`（供应商*产品*码，如 `tencent-coding-plan`），不是 `providers.slug`**。传成 slug 会得到 `RECONCILIATION_PROVIDER_UNKNOWN`，而报错正文说的是 `product_code`。
3. 窗口 ≤31 天且 `from < to`（`RECONCILIATION_WINDOW_INVALID`）。

**⚠ canonical 路径不依赖样本**，但**供应商私有格式解析器与指纹级匹配仍 WAITING_FOR_SAMPLE**。RUNNING 是瞬时态**不入审计**。语义口径：无 ID 账单行若未匹配计入 `UNMATCHED_PROVIDER` 行，同时按 `(productCode, 5分钟桶)` 计入 `PARTIAL` 桶差；`UNMATCHED_LOCAL` 是行级的（本地有 `provider_request_id` 且未被账单消费）。

---

### 4.8 模型目录与审批

#### 4.8.1 目录人工维护（`AdminModelCatalogController.java:39`，F18）

| 端点 | 坑 |
|---|---|
| `GET /api/v1/admin/models?providerProductId&source`（`:55`） | 目录行列表，可按 `source` 过滤（如 `MANUAL`） |
| `POST /api/v1/admin/models`（`:62`） | 人工录入 `{providerProductId, modelId, displayName?, contextWindow?, maxOutputTokens?}` → 201（`source=MANUAL`）。**MANUAL 行是官方探测失败的回退入口，官方刷新永不覆盖/删除**（`ON CONFLICT DO NOTHING`）；同产品重名 → 409 `MODEL_ALREADY_IN_CATALOG`。错误码 `MODEL_ID_INVALID`(400)、`PRODUCT_NOT_FOUND`(404) |
| `DELETE /models/{rowId}`（`:100`） | 只删 MANUAL 行；**OFFICIAL 行拒绝 → 409 `MODEL_NOT_MANUAL`**；不存在 → 404 `MODEL_NOT_FOUND` |

#### 4.8.2 模型探测与在线调试（`AdminModelCatalogController` 同文件，#346/I4/#552）

| 端点 | 坑 |
|---|---|
| `POST /models/probe`（`:72`） | 解析适配器 + **该产品订阅下首个 ACTIVE 凭证**（确定性顺序）→ 30s 内抓官方目录 → 成功落 `model_catalog`（OFFICIAL）并返回 `{providerProductId, productCode, modelCount, probedAt, models[]}`；失败 **502 `MODEL_PROBE_FAILED`（原因脱敏，目录不被触碰）**。无 ACTIVE 凭证 → 400 `MODEL_PROBE_CREDENTIAL_UNAVAILABLE`；无适配器 → 400 `MODEL_PROBE_ADAPTER_UNAVAILABLE`；产品缺 base URL → 400 `MODEL_PROBE_BASE_URL_MISSING`；产品不存在 → 404 `MODEL_PROBE_PRODUCT_NOT_FOUND` |
| `GET /models/probe-status?providerProductId=`（`:83`） | `{status(SUCCEEDED/FAILED/null), error, modelCount, probedAt}`；未探测全空 |
| `POST /models/test-run`（`:92`） | `{providerProductId, modelId, prompt?}`（prompt ≤2000，空 = "请回复OK"）→ 以该产品**首个 ACTIVE 凭证**发一条 OpenAI 兼容 `POST {baseUrl}/chat/completions`（`max_tokens=256`）→ `{…, httpStatus, latencyMs, content, promptTokens, completionTokens, totalTokens}`。**⚠ 正文（prompt 与回复）不落库、不入日志**；审计 `MODEL_TEST_RUN` 只记元数据。失败 502 `MODEL_TEST_RUN_FAILED`（含上游状态与**截断后**的上游错误消息）；无可用凭证 400 `..._CREDENTIAL_UNAVAILABLE`；未知产品 404 `..._PRODUCT_NOT_FOUND` |

**探测只是触发器**：失败沿用"保留最后成功目录"，不覆盖 MANUAL 行、不影响人工配置。

#### 4.8.3 模型单价（`AdminPriceController.java:33`）

| 端点 | 坑 |
|---|---|
| `GET /api/v1/admin/prices`（`:47`） | 每个（产品,模型,tokenType）三元组的**最新生效**单价 |
| `POST /prices`（`:52`） | 追加快照 `{providerProductId, modelId, tokenType, currency, unitPrice, source}` → 201。**单价是不可变快照：修改即追加新行，历史成本不重算**（与官方控制台"修改不追溯"一致）。`tokenType ∈ INPUT/OUTPUT/CACHE_READ/CACHE_CREATION`，非法 → 400 `PARAM_INVALID`；产品不存在 → 404 `PRODUCT_NOT_FOUND` |
| `POST /prices/sync`（`:65`） | 从 OpenRouter 拉取（USD/token），按 `MIQROKEY_PRICE_SYNC_USD_CNY_RATE` 换算为 CNY/1M 写入。**只处理 PAYG 且 product_code 命中编译期映射表的产品**；模型只在该产品 `model_catalog` ACTIVE 行内匹配（后缀精确 + 少量别名）；`:batch`/`:free` 变体不参与；与最新快照一致的写入被跳过。写入 `source=OFFICIAL`、`currency=CNY`，**会覆盖同键人工价的最新值**（快照 append-only，历史仍在）。失败 → **502 `PRICE_SYNC_FAILED` 且零写入** + 审计。响应报告 `{source, trigger, usdCnyRate, written, unchanged, conflicts[], unmatched[], skippedProducts, syncedAt}`。分时价（DeepSeek 峰谷）暂取标准/高峰价，**闲时折扣是已知缺口** |

**⚠ 自动同步的语义差异（#708）**：`MIQROKEY_PRICE_SYNC_AUTO_ENABLED=true` 时调度器跑**同一条管道**（`trigger=scheduled`，审计以**种子租户 + 空 actor** 记录，写入快照 `createdBy=null`）。与手动端点的**唯一语义差异**：同键最新快照为 `MANUAL` 且价格不同时**保留人工价**——该报价不写入、计入 `conflicts` 并在审计摘要里报数。**无人值守任务不覆盖人工录入；人工触发仍以显式点击为准（覆盖）。**

**价格口径的全局注意（#710 F21-A）**：按量成本（§5.2 汇总等）自 #710 起改读**行内冻结价格**（`usage_event.price_*`），而**成本分摊端点（§5.4）仍取分配时刻的最新快照**——两者口径不同，切换会牵动"同版本重跑覆盖历史"，属独立决策。

#### 4.8.4 成本分摊（`AdminCostAllocationController.java:26`）

| 端点 | 坑 |
|---|---|
| `GET /subscriptions/{id}/cost-allocation?from&to`（`:37`） | 读**已持久化**的分摊行，**不重算** |
| `POST /subscriptions/{id}/cost-allocation/allocate?from&to`（`:45`） | 计算并持久化，返回行。`@RequestParam` 缺省/格式非法 → 400；窗口 ≤93 天（`TIME_RANGE_INVALID`/`TIME_RANGE_TOO_WIDE`）；订阅不存在 → 404 `SUBSCRIPTION_NOT_FOUND` |

行字段：`targetType`（当前 `PROJECT`）、`targetId`、`fixedCost`（订阅价按窗口/周期天数比例折算）、`usageCost`（本地 usage × **最新**价格快照）、`weightTokens`、`allocatedAmount`、`currency`、`algorithmVersion`（当前 `1`）、`generatedAt`。固定成本仅 Plan 订阅（非 PAYG）有值，按各项目 Token 权重分摊；**无用量时不产出任何行**（返回空列表，不是错误）。重复分配同一周期 = 幂等覆盖（唯一键含算法版本）。

**⚠ 两个坑**：(1) `allocate` 是**写操作但不写任何审计**——`CostAllocationService` 里没有任何 `AuditService` 调用（`CostAllocationService.java:75` 起）；对照 §5.0 那句"所有写操作写审计事件"，这是缺口。(2) 价格取**分配时刻**的最新快照，与 §4.6 的行内冻结价口径不同（见上）。

#### 4.8.5 模型审批队列（`AdminModelApprovalController.java:41`）

| 端点 | 典型请求 | 坑 |
|---|---|---|
| `GET /model-approvals`（`:55`） | `status=`（枚举，垃圾值 → 400 `PARAM_INVALID`）、`size=`（默认 20，**1..100 之外 → 400 `PARAM_INVALID`，不是 clamp**）、`before=`（上一页 `nextCursor`，不透明 base64 `"{epochMillis}:{uuid}"`，解码失败 → 400 `PARAM_INVALID`） | 返回 `{items:[ModelApprovalView], nextCursor}`，倒序；**服务取 `size+1` 条来判断是否还有下一页，`nextCursor` 为 `null` 表示到底**。`ModelApprovalView` 的可空字段：`keyName`/`keyDisplay`/`projectTag` 在 Key/项目消失时为 null；**`requesterName` 对不存在**或跨租户**的申请人是字面量 `"deleted user"`**；`reviewedByName` 未审批时为 null |
| `POST /{id}/approve`（`:71`） | `{reviewNote?}`（≤500） | 写 `virtual_key_models` + 若模型不在 Grant 中**先**写 `project_provider_grant_models`，随后**立即触发快照刷新（不等 30s 定时）**。**批准前复核目录**：提交后模型被移出/停用目录 → 409 `MODEL_NOT_IN_CATALOG`。重复审批 → 409 `ALREADY_REVIEWED`（乐观锁，并发评审只有一个成功）；Key 已吊销/停用 → 409 `KEY_NOT_ACTIVE`（**含轮换后的旧 Key：该申请永远无法生效，应改为驳回**）；Grant 已停用 → 409 `GRANT_INACTIVE`；不存在 → 404 `APPROVAL_NOT_FOUND` |
| `POST /{id}/reject`（`:78`） | `{reviewNote?}` | 同上守卫；写 `MODEL_APPROVAL_REJECTED` |

**网关的三层放行**：`key.models ∩ grant.models ∩ model_catalog(ACTIVE)`，**缺一不可**。同 Grant 其它 Key 不受影响（各自 Key 快照独立）。

---

### 4.9 MCP 管理（`/api/v1/admin/mcp-services/**`）

> 全部 ADMIN。控制器：`AdminMcpServiceController.java:32`、`AdminMcpToolController.java:37`、`AdminMcpServiceAccessController.java:27`、`AdminMcpRouteRuleController.java:34`、`AdminMcpResilienceController.java:23`、`AdminMcpAccessLogController.java:23`。

#### 4.9.1 服务注册与运行时（`AdminMcpServiceController`）

| 端点 | 坑 |
|---|---|
| `GET /mcp-services` / `GET /{serviceId}`（`:48,53`） | 响应是 `McpService` 领域记录，**没有 secret 字段**，只有 `backendAuthMode` 与 `backendSecretUpdatedAt`；404 `MCP_SERVICE_NOT_FOUND` |
| `POST /mcp-services`（`:58`） | `@Valid`；`endpoint` 必须 https、无 userinfo/query/fragment（400 `MCP_ENDPOINT_INVALID`），**并且拒绝字面量私网 IP**；重名 409 `MCP_SERVICE_NAME_TAKEN`；**注册即自动创建 default 路由**；审计 `MCP_SERVICE_CREATE`。**⚠ 返回 200 不是 201**；**⚠ `transport` 没做 bean 校验**——非法值撞到 `McpService` 紧凑构造器抛 `IllegalArgumentException` → **500 `INTERNAL_ERROR`** 而不是 400 |
| `POST /{serviceId}/status?status=ONLINE\|OFFLINE`（`:67`） | 非法值 400 `MCP_STATUS_INVALID`；**重复切换 409 `MCP_STATUS_UNCHANGED`**。⚠ 走窄更新 `repository.updateStatus`，**没有版本判定**，所以契约 §5.16 承诺的"并发编辑乐观锁 → 409 CONCURRENT_MODIFICATION"**在这条路径上不可达** |
| `POST /{serviceId}/health-config`（`:75`） | 部分更新；400 `MCP_CHECK_MODE_INVALID`；乐观 `update(...version)` → 409。**⚠ 这里 `@RequestBody` 少了 `@Valid`**，`McpServiceHealthConfigRequest` 上所有 `@Min/@Max/@Size` **全是死的**：`checkIntervalSeconds=1` 或 `99999` 会被持久化；`0`/负数撞模型构造器 → **500**。**对照 `AdminServiceController.java:72` 同一功能的镜像端点是有 `@Valid` 的** |
| `PUT /{serviceId}/backend-auth`（`:90`） | `@Valid`；`{mode: VISITOR\|API_KEY, secret?}`。`API_KEY` 必填 secret（≤4096）→ 400 `MCP_BACKEND_AUTH_INVALID`；密钥**只写不读**，AES-GCM 加密（AAD 绑定 tenant+service），明文用后清零；网关向上游注入固定 `Authorization: Bearer <secret>`；审计只记 mode |
| `PUT /{serviceId}/upstream-timeout`（`:107`） | `{upstreamTimeoutMs}` 1000..600000；越界 400 `MCP_TIMEOUT_INVALID`；**启用慢调用熔断时不得 ≤ 已配置慢阈值** → 400 `RESILIENCE_SLOW_EXCEEDS_TIMEOUT`；审计 `MCP_SERVICE_UPSTREAM_TIMEOUT`，即时快照刷新 |
| `GET /{serviceId}/traffic?hours=24`（`:120`） | 被动健康视图；`hours` ∈ [1,168] 越界 400 `PARAM_INVALID`；**空窗口返回零值视图（不 404）**；`failureRate`/`lastCallAt`/`lastFailureAt` **可空**（无健康相关流量时为 null）；只读不阻断 |
| `GET /{serviceId}/connection`（`:131`） | 接入信息 `{serviceId, name, mcpUrl, sseUrl, authHint}`——由网关 base URL + `/mcpservers/{name}/mcp`（及 `/sse`）生成，**与数据面路由逐字一致**；`authHint` 只描述凭据形态，不含密钥。⚠ **ACL 面占用了 `/{id}/access`，所以接入信息放在 `/{id}/connection`**——别把两者搞混 |
| `POST /{serviceId}/verify`（`:141`） | 按服务现有 `checkMode` **立即探测一次**，返回 `{serviceId, reachable, checkMode, latencyMs, detail, checkedAt}`；`detail` 是脱敏中文结论（错误截断到 200 字符）；**只读**：不写 `health_checked_at`（归周期巡检），超时受 `checkTimeoutSeconds` 约束；**任意状态（含 OFFLINE）可验证** |

**⚠ SSRF 的一个真实缺口**：探测/验证（`McpHealthChecker`）直接 `URI.create(service.endpoint()+checkPath)` 发请求，**没有重新校验、也没有 DNS 钉扎**——与 webhook 投递（投递时**重新校验 + 地址钉扎**）形成对照。注册时校验过的域名可以被重新指向（DNS rebinding）。**内部服务（`/util-services`）更弱**：`validateBaseUrl` 只查 scheme/userinfo/query/fragment，**完全没有 IP/DNS 检查**——`https://127.0.0.1/`、`https://10.0.0.5/` 都能过。工具同步（`tools/list`）同样**无重校验、无钉扎**。

#### 4.9.2 工具管理（`AdminMcpToolController`）

| 端点 | 坑 |
|---|---|
| `GET /mcp-services/{serviceId}/tools`（`:55`） | 404 `MCP_SERVICE_NOT_FOUND` |
| `POST /{serviceId}/tools`（`:60`） | `@Valid`；`toolName` 小写字母开头 snake_case（400 `TOOL_NAME_INVALID`）、`path` 必须以 `/` 开头（400 `TOOL_PATH_INVALID`）、同服务重名 409 `TOOL_NAME_TAKEN`；播种修订 r1；**返回 200 不是 201** |
| `POST /{serviceId}/tools/import`（`:73`） | F17 OpenAPI 批量导入。**⚠ 契约 §5.17 `:987` 写的是 body `{"spec": <OpenAPI JSON>}`，但代码把原始 body 直接交给解析器**（`ToolOpenApiParser`），集成测试也发裸 OpenAPI 对象——**照契约发 `{"spec":{…}}` 会得到 400 `SPEC_INVALID`**。逐项容错（不整体失败）；上限 100 项 → 400 `TOO_MANY_TOOLS`；缺 `paths` → 400 `SPEC_INVALID` |
| `POST /{serviceId}/tools/sync?dryRun=`（`:88`） | 上游 `tools/list` → 差量合并。`dryRun=true` **只算不写不审计**。上游非 2xx/JSON 非法/超 2MB/超 1000 工具 → 502 `TOOLS_SYNC_UPSTREAM_FAILED`（原因脱敏）；并发冲突 409 `TOOLS_SYNC_CONFLICT`。上游未返回的本地工具只列入 `absentUpstream`（**不自动禁用/删除**）。**需要 initialize 会话握手的上游不在本版范围** |
| `GET/PUT /{serviceId}/tools/{toolId}/retry-policy`（`:98,108`） | 工具级重试覆盖（**熔断字段仍保持服务级**）；越界/空条件/未知条件 → 400 `TOOL_RETRY_POLICY_INVALID`；无覆盖行 GET 返回 disabled 默认视图。**PUT 是整份替换**（省略字段回落到 disabled 默认值，不是保持原值） |
| `POST /{serviceId}/tools/{toolId}/status?status=`（`:118`） | **重复切换 409 `TOOL_STATUS_UNCHANGED`**——与 route-rule 的幂等**相反**（契约 `:1118` vs `:984` 分别记录了两种惯例） |
| `GET/POST /{serviceId}/tools/{toolId}/revisions`（`:129,139`） | 修订历史（新→旧，默认 20/上限 50，**永不裁剪**），每项含只读 `changedFields`（相邻旧版字段级差异，基线为空）。**POST 是稀疏部分编辑**：缺省字段沿用当前激活修订值，自动成为生效版并镜像到工具行；并发 409 `TOOL_REVISION_CONFLICT` |
| `POST /{serviceId}/tools/{toolId}/revisions/{revision}/activate`（`:152`） | 幂等：已是当前版 → no-op 成功、**不产生新版本号、不写审计**；不存在 → 404 `TOOL_REVISION_NOT_FOUND` |

**⚠⚠ 跨资源校验缺口（本域最值得记住的坑）**：**`{serviceId}` 路径变量在 `status` / `revisions` 列表 / `revisions` 发布 / activate 四条路径上根本没有传给服务层**（`AdminMcpToolController.java:122, 132, 143, 156`），服务层只按 `(tenant, toolId)` 查（`AdminMcpToolService.java:184-185`）。**结果：可以用服务 A 的 URL 去切换服务 B 的工具。** 而 ACL 授权（`AdminMcpAccessService.java:111-115`）与重试策略（`AdminMcpToolRetryService.java:101-103`）**是校验归属的**（404 `TOOL_NOT_FOUND`）——同一份路径参数在不同端点上语义不一致。这不是漏洞（tenant 仍然隔离），但是**接口契约的不一致**，修的时候要一起改。

#### 4.9.3 两级访问控制（`AdminMcpServiceAccessController.java:27`）

| 端点 | 坑 |
|---|---|
| `GET /{id}/access`（`:38`） | 服务模式 + 服务名单 + 每个工具的模式（null = 继承）与名单。消费者行已删的授权渲染为 `"deleted consumer"` |
| `PUT /{id}/access/mode`（`:44`） | `{mode: NONE\|ALLOW\|DENY}`；**切回 NONE 会清空服务名单**；非法枚举值 → 400 `PARAM_INVALID`（走 JSON 解析失败映射，不是 bean validation） |
| `PUT /{id}/access/grants`（`:51`） | `{toolId?, mode, consumerIds[]}` **整体替换**一层名单。校验：工具必须属于该服务 → 404 `TOOL_NOT_FOUND`；**非 NONE 模式配工具覆盖** → 409 `TOOL_ACL_UNSUPPORTED`；**NONE 模式配服务名单** → 409 `SERVER_LIST_UNSUPPORTED`；消费者不存在 → 400 `CONSUMER_NOT_FOUND`、非 ACTIVE → 400 `CONSUMER_NOT_ACTIVE`；`consumerIds` 空由 bean 校验（400 `VALIDATION_FAILED`） |
| `DELETE /{id}/access/grants?toolId=`（`:59`） | 无 toolId = 服务回全开放（NONE）；带 toolId = 该工具回继承。**无配置行时是 no-op 成功**；**⚠ 这里不校验 toolId 归属**（删别人的 toolId 只是删掉 0 行） |

**判定语义**：`NONE` 全开放（**只有此时才能配工具级覆盖**）；`ALLOW` 白名单；`DENY` 黑名单。服务层先判，工具无覆盖则继承，有覆盖则在服务放行基础上**只能收窄不能放宽**。

#### 4.9.4 路由规则（`AdminMcpRouteRuleController.java:34`，F11）

| 端点 | 坑 |
|---|---|
| `GET /{serviceId}/route-rules`（`:45`） | 优先级降序，**default 在末尾**；每项含只读 `matchExpression`（规范条件面渲染，与引擎匹配语义同源） |
| `POST /{serviceId}/route-rules`（`:50`） | `priority` 默认 1000、范围 1..65535（**0 为系统保留**）→ 400 `ROUTE_PRIORITY_INVALID`；名称 1–64、同服务唯一（409 `ROUTE_NAME_TAKEN`）、`default` 保留（400 `ROUTE_NAME_RESERVED`）；路径必须以 `/` 开头（REGEX 豁免）；正则 **RE2 且全匹配语义**，非法（含回溯引用）→ 400 `ROUTE_PATTERN_INVALID`；Header 条件最多 8 条；**冲突实时校验**：与同服务**已启用**规则匹配面完全等价 → 409 `ROUTE_MATCH_CONFLICT`。**无条件自定义规则与 default 等价 → 创建即冲突** |
| `PATCH /{ruleId}`（`:60`） | **全量替换可编辑字段**：缺省的匹配字段 = **清空/不限**（这是最容易踩的）；**例外是 `priority`**（缺省保留现值）和 `status`（永不改动）。default 规则 → 409 `ROUTE_DEFAULT_IMMUTABLE`；规则不属于该服务 → 404 `ROUTE_NOT_FOUND`。审计带 `previousName`/`previousPriority` |
| `POST /{ruleId}/status?status=ENABLED\|DISABLED`（`:70`） | **幂等**：同状态返回当前值、不写库、**不写审计**；非法值 400 `ROUTE_STATUS_INVALID`；default → 409 `ROUTE_DEFAULT_IMMUTABLE`；**重新启用会再次跑冲突校验** → 409 `ROUTE_MATCH_CONFLICT`（防休眠重复被武装） |
| `DELETE /{ruleId}`（`:76`） | default → 409 `ROUTE_DEFAULT_IMMUTABLE`；**返回 void → 200 空体**（没有 `@ResponseStatus(NO_CONTENT)`） |

**⚠ 两个坑**：(1) `{serviceId}` 在 `status`/`delete` 上被忽略（控制器 `:73, :78` 只传 ruleId，查找按 `(tenant, ruleId)`），只有 `PATCH` 校验归属——**与工具侧同一类不一致**。(2) **`AdminMcpRouteRuleService` 不持有 `RouteRefreshPublisher`**——路由规则的增删改启停**从不发快照刷新**，而 MCP 服务/工具/韧性配置的变更都会发。**改了路由规则后数据面根本不生效。**
> ⚠️ **不是「延迟」，是「无关」**：路由规则**不进 `RouteSnapshot`**（其 record 的 16 个组件里没有 route rule），`gateway-app` 全模块 grep `routerule|route_rule` **零命中** —— 发不发快照刷新都一样。（另注：网关本来每 30s 就无条件重载一次快照，所以「刷新延迟」最多 30s，也不是问题所在。） 这看起来是遗漏，改这块时优先确认。

#### 4.9.5 访问日志（`AdminMcpAccessLogController.java:23`，F15）

`GET /api/v1/admin/mcp-access-logs`（`:34`）。参数 `service`/`consumer`（名称精确）、`from`/`to`（默认近 24h，窗口 ≤31 天 → `TIME_RANGE_TOO_WIDE`；`from > to` → `TIME_RANGE_INVALID`）、`limit`（默认 200、上限 1000 → `SIZE_INVALID`）。

- 纯元数据审计行：`status ∈ FORWARDED / SERVICE_DENIED / TOOL_DENIED / TOOL_UNAVAILABLE / INVALID_ENVELOPE / UPSTREAM_FAILURE`。`rpcMethod`/`toolName`/`httpStatus`/`sessionId`/`ttfbMs` **可空**。
- **不存工具参数、请求正文或响应正文**（信封 method 与 `params.name` 是唯一被读取的正文元数据）。
- **不落行**：预解析失败（401 未知 Key、404 未知服务）无可信身份，只留在请求日志。
- 写入端：网关 `McpAccessLogSink`（有界队列 4096 + 1s 周期 flush；饱和 drop + 计数 WARN；批量失败整批重入队）；`(tenant_id, gateway_request_id)` 唯一，重试不双写。
- **⚠ 时间格式错误返回 `PARAM_INVALID`**，而同类错误在用量/Agent 端点是 `TIMESTAMP_INVALID`（同一份 `X-Request-Id` 体系里的不一致）。

#### 4.9.6 韧性配置（`AdminMcpResilienceController.java:23`，F12/F13）

`GET/PUT /api/v1/admin/mcp-services/{serviceId}/resilience`（`:34, :39`）。

- **重试与熔断默认全关**：无策略行（或全 false）时数据面行为与引入前完全一致。
- **PUT 整份替换**（省略字段 = disabled 默认值）；**body 可省略（`required=false`）**，body 为 null 会被转成全 null 请求 → 等价于**重置**。
- 校验（全在 `build` 里）：未知重试条件/`retryMax` 越界/启用但无条件/熔断启用但无触发/`probeSuccess > probeCount` → 400 `RESILIENCE_INVALID`；慢阈值 ≥ 服务上游超时 → 400 `RESILIENCE_SLOW_EXCEEDS_TIMEOUT`。
- 审计 `MCP_RESILIENCE_UPDATE`，即时触发快照刷新。**⚠ 缺 `X-Request-Id` 时 `requestId()` 返回空串**（其它控制器是随机 UUID 兜底），审计行可能带空关联 id。
- **⚠ GET 对"从未配置"与"显式全关"返回完全相同的值**（无 version/updatedAt），客户端无法区分。

**关键语义**：重试只发生在**网关把上游首字节回给调用方之前**；`SERVER_5XX` 仅含 **500/502/503/504**（501/505 等确定性状态直接回传，**不重试**）；`tools/call` 命中的工具 method 为 POST/PUT/PATCH 时，**未勾选 `idempotencyConfirmed` 一律不重试**（防重复写）。熔断三态 CLOSED/OPEN/HALF_OPEN，桶 = `tools/call` 按工具名、其余按方法名，**桶间互不影响**。`breakerSkipRetry=false` 时熔断**只观测不限流**。**`breakerSlowCallMs` 必须小于服务上游超时**（基准是后端请求超时而非健康探测超时）。

---

### 4.10 Agent / 内部服务 / 配置 / Skill 管理

#### 4.10.1 Agent（`AdminAgentController.java:34`，ADR-0025）

| 端点 | 典型请求 | 坑 |
|---|---|---|
| `GET /agents` / `GET /{agentId}`（`:45,50`） | — | `AgentView` 的 `credentialName`/`providerProductId`/`providerProductName` **是派生的、可空**（凭证/订阅/产品行缺失时为 null）。404 `AGENT_NOT_FOUND`。**`list` 不做状态过滤**（停用与启用同列，前端用徽章区分）——这是刻意保留的 |
| `POST /agents`（`:55`） | `{name, description?, credentialId}` | `@Valid`。凭证**行锁**校验存在/同租户/ACTIVE → 否则 **400 `CREDENTIAL_NOT_FOUND`**（注意是 400 不是 404）；已被别的 Agent 绑定 → 409 `AGENT_CREDENTIAL_TAKEN`（**1:1 规则，`uq_agents_tenant_credential` 在任意状态下都占位**）；重名 409 `AGENT_NAME_TAKEN`。行锁与 `rotate`/`disable` 的凭证行锁互斥，避免"校验通过→并发停用"的竞态 |
| `POST /{agentId}/disable`（`:62`） | — | 重复禁用 409 `AGENT_ALREADY_DISABLED`。**disable 同时是"解除凭证引用"操作**——禁用后该凭证恢复可轮换/可停用 |
| `POST /{agentId}/enable`（`:68`） | — | **不是 disable 的镜像**：停用期间凭证可能已被停用，此时拒绝并返回 **409 `CREDENTIAL_NOT_ACTIVE`**；凭证被**轮换**无害（Agent 绑定的是凭证行而非密文），可直接启用。重复启用 409 `AGENT_ALREADY_ENABLED` |
| `PATCH /{agentId}`（`:75`） | `{name, description, version}` | **整份可编辑状态 + 乐观锁版本**（**非稀疏 patch**），`version` 来自上次读取；重名 409 `AGENT_NAME_TAKEN`；版本过期 409 `CONCURRENT_MODIFICATION`。审计 `AGENT_UPDATE` 记 before→after（**改名后按 id 反查不到旧名**） |
| `DELETE /{agentId}`（`:84`） | — | **硬删除，不可恢复**（204）。删除同时释放租户内名称与"该凭证→唯一 Agent"的名额。**用量与对账不受影响**——没有任何表引用 `agents`，用量按凭证聚合。审计 `AGENT_DELETE` 的 detail **带名称快照**（行已不存在，仅凭 id 无法还原） |
| `GET /{agentId}/usage?from&to`（`:92`） | — | 按绑定凭证的用量汇总（默认近 93 天）；时间参数用 `Instant.parse`，格式错 → 400 `TIMESTAMP_INVALID` |

#### 4.10.2 内部服务注册表（`AdminServiceController.java:29`）

| 端点 | 坑 |
|---|---|
| `GET /services` / `GET /{serviceId}`（`:40,45`） | 404 `SERVICE_NOT_FOUND`；列表/详情返回探测配置与 `healthCheckedAt` |
| `POST /services`（`:50`） | `@Valid`；`kind ∈ HTTP\|MCP\|OTHER`（缺省 HTTP）；**`baseUrl` 必须 https、不含 userinfo/query/fragment** → 400 `BASE_URL_INVALID`；重名 409 `SERVICE_NAME_TAKEN`。**⚠ `validateBaseUrl` 完全没有 IP/DNS 检查**——`https://127.0.0.1/`、`https://10.0.0.5/` 都能过（比 MCP 端点和 webhook 都弱）；**返回 200 不是 201** |
| `POST /{serviceId}/disable` / `/enable`（`:57,64`） | 409 `SERVICE_ALREADY_DISABLED` / `SERVICE_ALREADY_ENABLED`；CAS 竞争 → 409 `SERVICE_STATE_CONFLICT` |
| `POST /{serviceId}/health-config`（`:71`） | **这里是有 `@Valid` 的**（对照 §4.9.1 的 MCP 镜像端点）——同一功能两个端点校验不一致 |

`status`（`ACTIVE|DISABLED`，手动启停，经审计）与 `healthStatus`（`UNKNOWN|HEALTHY|UNHEALTHY`，仅探测 ACTIVE 服务）**正交**；DISABLED 永不探测，手动停用不被覆盖。

#### 4.10.3 全局配置中心（`AdminConfigController.java:32`）

| 端点 | 坑 |
|---|---|
| `GET /configs?group`（`:43`） | **⚠ 返回 `value` 原文，无任何掩码**。服务注释说"机密走环境变量/加密凭证体系，不得写入此目录"，但**没有任何强制**——管理员存什么就能读回什么 |
| `PUT /configs`（`:49`） | `@Valid`；名称规则 `[a-zA-Z][a-zA-Z0-9._-]{0,127}` → 400 `CONFIG_NAME_INVALID`；值必填 → 400 `CONFIG_VALUE_REQUIRED`；按 `(group,key)` upsert；审计 `CONFIG_PUT` **只记 group/key，永不记 value**——所以**写进来的秘密在审计里看不见，但在 GET 里读得到** |
| `DELETE /configs/{group}/{key}`（`:56`） | 204；不存在 → 404 `CONFIG_NOT_FOUND`。**⚠ 路径变量这里不做格式校验**（PUT 做） |

#### 4.10.4 Skill 管理（`AdminSkillController.java:27`）

| 端点 | 坑 |
|---|---|
| `GET /skills?q&tags`（`:41`） | `q` >60 字符 → 400 `SKILL_QUERY_INVALID` |
| `POST /skills?version=`（`:54`） | **`version` 是必填查询参数**（缺失 → 400 `PARAM_INVALID`）；格式必须 `\d+\.\d+\.\d+`（400 `VERSION_INVALID`）；body 是 **raw zip byte[]**（契约说 `Content-Type: application/zip`，但**代码不强制**）。**重传同名 = 发布下一修订**（历史/旧包保留、恢复 ACTIVE、可回滚）；新名 = 建技能 + 基线 r1。包限 5MB / 条目 200 / SKILL.md 512KB（防 zip 炸弹——**只读 SKILL.md，不解压**） |
| `GET /{skillId}/revisions?limit=`（`:65`） | 新→旧，默认 20/上限 50，**越界静默 clamp**（不 400） |
| `POST /{skillId}/revisions/{revision}/activate`（`:75`） | 幂等；**只有指针真的移动时才写审计**；不存在 → 404 `SKILL_REVISION_NOT_FOUND`；并发 → 409 `SKILL_REVISION_CONFLICT` |
| `POST /{skillId}/archive`（`:83`） | 404 `SKILL_NOT_FOUND`；**⚠ 没有"已归档"守卫**——重复归档会**重复写审计事件**（与 enable/disable 家族的 409 惯例不同） |
| `PUT /{skillId}/access`（`:90`） | 体是 `List<SkillAccessScopeRequest>`，**无 `@Valid`**，记录上的 `@NotBlank/@NotNull` **不生效**；服务自己的空值检查返回 400 `SCOPE_INVALID`；未知类型/不存在/跨租户 → 400 `SCOPE_INVALID`。**空数组 = 公开**。审计 `SKILL_ACCESS` 记 scope 数量 |

---

### 4.11 告警规则与 Webhook

#### 4.11.1 告警规则（`AdminAlertRuleController.java:35`）

| 端点 | 坑 |
|---|---|
| `POST /alert-rules`（`:46`） | `@Valid`：`name @NotBlank ≤200`、`threshold @NotNull @Digits(integer=6,fraction=6)`（**故意不设下界**，`<=0` 是文档允许的退化配置）、`dedupeMinutes @Min(1)`（缺省 60）。`type` 非法 → 400 `ALERT_TYPE_INVALID`；`scopeJson` 里缺/未知/跨租户的 `projectId` 或 `quotaRuleId` → 400 `SCOPE_INVALID` |
| `GET /alert-rules` / `GET /{ruleId}`（`:54,59`） | 404 `ALERT_RULE_NOT_FOUND` |
| `PATCH /{ruleId}`（`:64`） | `@Valid`；**稀疏部分更新**（缺省字段保持原值）；`scopeJson` 按存储的类型重新校验；乐观 CAS → 409 `CONCURRENT_MODIFICATION` |
| `DELETE /{ruleId}`（`:73`） | 404 via get；无依赖检查（没有东西引用规则）。**返回 200 空体**（没有 204） |

**⚠ 两个坑**：
1. **`webhookEndpointId` 从不校验存在性与租户**（`AlertRuleService`）。外键是 `REFERENCES webhook_endpoints(id) ON DELETE SET NULL` 且**没有租户列**，所以规则可以引用**别的租户的端点 id**；不存在的 id 会以通用 409 `RESOURCE_CONFLICT` 漏出（不是干净的 404/400）。投递侧是按租户查询的，**发送时 fail-closed**。
2. **`webhookEndpointId` 无法被清空**：PATCH 里 `webhookEndpointId != null ? 新值 : 原值`——JSON `null` 与"字段缺省"不可区分，**没有任何办法通过 PATCH 把规则与 webhook 解绑**（只能删规则重建或直接改库）。

**告警类型**（评估周期 `miqrokey.alerts.evaluation-interval-ms` 默认 5min）：`USAGE_MISSING_RATE`、`UPSTREAM_ERROR_RATE`、`BALANCE_UNAVAILABLE`、`USAGE_SURGE`、`USAGE_QUEUE_SATURATION`（F07：网关用量队列近 1h **丢失条数**绝对值；事实由网关写在**默认 seed 租户**，**只有该租户的规则能评估到**，其它租户聚合到零行、正阈值下恒不触发；阈值 `<=0` 会在每个去重窗口以 `value=0` 触发一次）、`UPSTREAM_RATE_LIMITED`（V71：近 1h 上游 429 **条数**；**网关自身因配额拒绝的请求不达上游、不计入**）、`KEY_REQUEST_RATE`（V71：近 1h **单把密钥最高请求条数**，触发事件 `payload_json` 带 `keyId`/`keyName`/`requests`）、`BUDGET_THRESHOLD`（`scopeJson:{"projectId"}` 必填）、`QUOTA_THRESHOLD`（`scopeJson:{"quotaRuleId"}` 必填；规则停用即不评估）、事件型 `ADMIN_API_KEY_EXPIRING`（默认关）。

**事件驱动类型（F03）**：`MODEL_APPROVAL_SUBMITTED/APPROVED/REJECTED` —— **不参与周期评估**，在状态迁移瞬间直接触发（复用同一投递/签名/退避重试）；阈值/scope 不适用（创建时阈值恒发送 1）；去重 = 规则 × `type:approvalId`；payload 见 `api-contract.md:758`。

#### 4.11.2 Webhook（`AdminWebhookController.java:36`）

| 端点 | 坑 |
|---|---|
| `POST /webhooks`（`:47`） | `@Valid`：`name @NotBlank ≤200`、`url @NotBlank ≤500`、`secret @NotBlank`、`timeoutMs @Min(1000)@Max(600000)`（缺省 5000）。URL 过 SSRF 门控 → 400 `WEBHOOK_URL_REJECTED`；其余 → `WEBHOOK_NAME_INVALID`/`WEBHOOK_SECRET_INVALID`/`WEBHOOK_TIMEOUT_INVALID`。Secret AES-GCM 加密（AAD = tenant+endpoint）；审计只记 name 与 url host。**返回 200 不是 201** |
| `GET /webhooks` / `GET /{endpointId}`（`:54,59`） | `WebhookEndpointView` **不含任何 secret 材料**；控制器在 `:61` 显式 `view()` 包了一层，防止内部记录（带 `secretEncrypted`/`secretNonce`）被序列化。404 `WEBHOOK_NOT_FOUND` |
| `PATCH /{endpointId}`（`:64`） | `@Valid`，部分更新；**只能改 name/enabled/timeoutMs**——URL 与 secret **不可改**（刻意的）；乐观 CAS → 409 `CONCURRENT_MODIFICATION` |
| `DELETE /{endpointId}`（`:72`） | **I21 删除前置依赖检查**：行锁后查告警规则引用，有则 **409 `RESOURCE_IN_USE`** + `dependencies[{type:"ALERT_RULE", id, name, detail}]`（`detail` 是字面中文 `"已启用"`/`"已停用"`）。**⚠ 这是本域唯一会发 `dependencies` 的端点**——它是"不再静默脱钩"的改动（原来 `ON DELETE SET NULL` 会让规则悄悄失去投递目标） |
| `POST /{endpointId}/test`（`:79`） | 发 HMAC-SHA256 签名测试载荷；**投递时重新校验 URL 并钉扎到校验过的地址**、禁跟随重定向、整调用超时（含 body）。响应 `{httpStatus?, errorMessage?}`——**失败是返回而不是抛异常**；不写审计 |
| `GET /{endpointId}/deliveries?limit=20`（`:85`） | **⚠ limit 静默 clamp 到 1..100**（不 400）；`httpStatus`/`nextRetryAt`/`errorMessage` 可空；不写审计 |

**投递签名**：`X-MiQroKey-Signature: sha256=<HMAC-SHA256(secret, payload) hex>`，payload 是事件 JSON（`eventId/ruleId/type/value/occurredAt`）。**投递语义是 at-least-once**——同 `eventId` 可能重复到达，**接收方必须按 `eventId` 幂等去重**。投递失败指数退避重试最多 3 次。

#### 4.11.3 机器密钥发行（`AdminApiKeyController.java:31`）

| 端点 | 坑 |
|---|---|
| `GET /api-keys`（`:42`） | 视图**无 digest**；`capabilities` null = 全量 |
| `POST /api-keys?expiresAt=`（`:47`） | `@Valid`；`expiresAt` 必须是**将来**的时刻，否则 400 `API_KEY_EXPIRES_INVALID`；不可解析 → 400 `PARAM_INVALID`；重名 409 `ADMIN_API_KEY_NAME_TAKEN`。**201 + `{key: view, secret, shownOnce:true}`**——**这是明文唯一出现的面** |
| `POST /{keyId}/revoke`（`:58`） | 404 `ADMIN_API_KEY_NOT_FOUND`；已吊销 → 409 `ADMIN_API_KEY_ALREADY_REVOKED`。**吊销即时生效**（filter 每次请求查库）；**吊销自己的密钥只能走会话面**（机器密钥不能自吊销） |
| `PATCH /{keyId}/scope`（`:68`） | `@Valid`；`null`/缺省 = 恢复全量、`[]` = 全拒、未知或重复码 → 400 `ADMIN_API_KEY_SCOPE_INVALID`；审计记 from→to |

**⚠ 这三个端点的审计 `requestId` 恒为 `null`**（`AdminApiKeyService.java:57, 79, 92`）——控制器不读 `X-Request-Id`。这是全仓少见的、**有意的？**仍待确认的关联缺口。

---

### 4.12 第三方计费通道（`/api/v1/billing/**`，`BillingController.java:28`）

| 端点 | 参数 | 响应 |
|---|---|---|
| `GET /billing/summary`（`:41`） | `from`/`to`/`groupBy` | 租户级用量/成本汇总（与 §4.6 同口径） |
| `GET /billing/records`（`:49`） | `from`/`to`/`page`/`size` | 租户级分页明细 |
| `GET /billing/quota`（`:58`） | — | 按订阅分组的最近快照，按订阅名排序；**无快照的订阅以空列表出现** |

**权限**：`ApiKeyAuthFilter` —— 消费者 Key（`X-API-Key` 或 `Bearer mqk_api_…`）或消费者 JWT，或 **SYSTEM_ADMIN 会话**（#724）。

**⚠ 四个坑**：
1. **缺 `billing:read` 能力 → 403 `CONSUMER_SCOPE_DENIED`**（fail-closed）。`capabilities` NULL = 全量（存量兼容）。与 ACL/白名单判断正交。
2. **已登录但非 SYSTEM_ADMIN 且没有可用消费者凭据 → 403 `SESSION_WITHOUT_CONSUMER_CREDENTIAL`**，而且**绝不回落到会话的租户**（#724）。写权限模型时别以为"登录了就能看"。
3. **外部通道只暴露配额数字与权威级别**：`source ∈ OFFICIAL_API|LOCAL_ESTIMATE|UNAVAILABLE`；**`errorMessage`/`providerStatusJson` 仅管理员面可见**。`LOCAL_ESTIMATE` 是按本地用量估算的，**与官方值必须严格区分**——页面必须按 `source` 标注。
4. **到期语义（#322，V39）**：`expiresAt` 缺省 = **永不过期**（存量兼容）；到期后凭据在**所有通道**静默失效（401，与未知 Key 同形）。管理列表仍展示该行与到期时间供审计；**更新期限 = 重建消费者**。可选告警 `CONSUMER_KEY_EXPIRING`（默认关）。

**消费者管理端点**（`AdminApiConsumerController.java:35`，ADMIN）：

| 端点 | 坑 |
|---|---|
| `GET /api-consumers`（`:48`） | 掩码视图 + JWT 公钥指纹；不写审计 |
| `POST /api-consumers`（`:53`） | `@Valid`：`name @NotBlank ≤200`；`expiresAt` **就地解析**（格式错 → 400 `CONSUMER_EXPIRES_INVALID`，**不是 `PARAM_INVALID`**），且必须是将来时刻；重名 409 `CONSUMER_NAME_TAKEN`。201 + `{consumer, apiKey(明文一次), shownOnce:true}`；审计 `CONSUMER_CREATE` + 快照刷新。`expiresAt` 为 null = **永不过期** |
| `POST /{consumerId}/disable`（`:76`） | 404 `CONSUMER_NOT_FOUND`；重复 → 409 `CONSUMER_ALREADY_DISABLED`；审计 `CONSUMER_DISABLE`。**⚠ 没有 re-enable 端点**——禁用是单向的，要恢复只能重建 |
| `PUT /{consumerId}/jwt-key`（`:85`） | `@Valid {publicKeyPem @NotBlank ≤8192}`；**已禁用的消费者 → 409 `CONSUMER_DISABLED`**；非 RSA SubjectPublicKeyInfo PEM → 400 `JWT_KEY_INVALID`。返回带 `jwtKeyFingerprint`（SHA-256 前 8 字节 hex）的视图。轮换即时生效（旧 token 立刻验不过）；审计 `CONSUMER_JWT_KEY_SET` |
| `DELETE /{consumerId}/jwt-key`（`:93`） | 移除公钥（JWT 认证**立即失效**）。**⚠ 与 PUT 不对称：这里没有"已禁用"检查**——已禁用的消费者也能删公钥，而 PUT 会 409 |
| `PATCH /{consumerId}/scope`（`:119`） | `{"capabilities":[…]}`；`null`（缺省）= 全量、`[]` = 无通道；取值限 `billing:read`/`mcp:call` 且不得重复，未知码 → 400 `CONSUMER_SCOPE_INVALID`；404 `CONSUMER_NOT_FOUND`；审计 `CONSUMER_SCOPE_UPDATE`（含 from/to）+ 快照刷新。**这个 record 上没有 bean validation**，校验全在 `ConsumerCapabilities.isValid` |
| `GET /{consumerId}/activity?hours=24`（`:103`） | `mcp_access_log` 窗口聚合：`{consumerId, windowHours, totalCalls, forwarded, denied, failed, lastCallAt, topTools(≤5), topServices(≤5)}`；`denied` = `SERVICE_DENIED`/`TOOL_DENIED`/`TOOL_UNAVAILABLE`/`INVALID_ENVELOPE`；`failed` = `UPSTREAM_FAILURE`/`CIRCUIT_OPEN`；`hours ∈ [1,168]` 越界 **400 `PARAM_INVALID`（不 clamp）**；消费者不存在/跨租户 404 `CONSUMER_NOT_FOUND`；**空窗口返回零值视图（不 404）**；`lastCallAt` 无调用时为 null；**401/404 无可信身份的请求不入日志、不计入**；不写审计 |

**能力作用域（#316，V37）** 按通道校验（fail-closed）：计费通道缺 `billing:read` → 403 `CONSUMER_SCOPE_DENIED`；**网关 MCP 数据面**缺 `mcp:call` → 403 `consumer_scope_denied`（**注意是小写 error 信封，不是 problem+json**）。通道变更**即时生效**（路由快照刷新）。

---

## 5. 改一个接口的标准动作

### 5.1 加一个新端点（控制面）

**先知道参数校验在哪里**（本仓库**没有** `UsageQueryValidator` 这种统一类）：

| 校验 | 在哪 | 影响范围 |
|---|---|---|
| `groupBy` 枚举、`MAX_WINDOW=93d` 时间窗、`MAX_PAGE_SIZE=200` | `UsageStatsService` 的 `parseGroupBy`/`validateTimeRange`（**包私有静态**） | **个人端与管理员端的 usage summary/records 共用** |
| `tzOffsetMinutes ±1080` | `AdminUsageStatsService.tzOffset` | summary/records **共用**；**但 `hourly` 就地复制了一份，改这个助手不影响它** |
| `MAX_PAGE = 1_000_000` | `AdminUsageStatsService` | **只有管理员端的 records**；个人端与计费通道的行为见 §4.6 |
| **93 天窗口** | **六份各自独立的私有拷贝** | `UsageStatsService`（usage/ROI）、`ExportTaskService`、`UsagePriceBackfillService`、`UsageDeletionService`、`AuditEventReadService`（**这个没有时长上限，只查 from>to**）、`ReconciliationService`（31 天，就地写死） |

**⚠ 改窗口上限时的关键结论**：改 `UsageStatsService.MAX_WINDOW` **只会**影响 usage summary/records（个人+管理员）与 ROI；**导出、删除、价格回填、对账都不会跟着变**。要全局改必须六处一起动。

按顺序动这些文件：

| 步骤 | 动什么 | 为什么 |
|---|---|---|
| 1 | `controlplane/controller/` 下的控制器 | 路径归属决定权限：放进 `/api/v1/admin/` 就自动 SYSTEM_ADMIN-only；放进 `/api/v1/me/` 就要自己用 `userContext` 自限定 |
| 2 | 请求 DTO（`controlplane/dto/`）**并记得在参数上加 `@Valid`** | 只加注解不加 `@Valid` 是**本仓库最常见的真实缺陷**——59 个控制面控制器里有 **9 个**有 `@RequestBody` 却**完全没有 `@Valid`**：`AdminGrantController`、`AdminMcpResilienceController`、`AdminReconciliationController`、`AdminRetentionConfigController`、`AdminSkillController`、`AdminUnattributedPolicyController`、`AdminUsageAdjustmentController`、`AdminUsageDeletionController`、`AdminUserController`。`@Valid` 生效时错误码是 400 `VALIDATION_FAILED` + `fieldErrors[]` |
| 3 | 服务层（`controlplane/service/`），错误用 `ApiException(status, CODE, 中文消息)` | 错误码原样透出；不包装的任何异常（含 `IllegalArgumentException`/NPE）都是 500 `INTERNAL_ERROR` |
| 4 | **审计**：在服务层写 `auditService.record(...)`，并带上 `requestId` | 全仓多处漏 `requestId`（§2.8），新代码不要跟着漏 |
| 5 | **快照刷新**：改动影响路由/凭证/配额/模型范围的，调 `RouteRefreshPublisher` | 不调的话数据面最长 30s 后才生效；路由规则那处**完全不发**（§4.9.4） |
| 6 | 集成测试放 `backend/control-plane-app/src/test/java/.../controller/`，命名 `XxxApiIntegrationTest`（93 个同侪） | 需要 PostgreSQL 的用 `@Tag("integration")`；CI 用 `./mvnw verify -Pintegration` 跑 |
| 7 | **重新生成 OpenAPI 基线**（见 5.2） | 不做的话 CI 的 breaking-diff **不会**失败（那个脚本只查删除与收紧，新增是允许的），但前端 `gen:types` 拿不到类型 |
| 8 | 前端 `frontend/src/api/index.ts` 加调用函数 + 需要时在 `frontend/src/types/api.ts` 或 `generated-api.ts` 加别名 | `gen:types` 生成的是 `generated.ts`，手写 DTO 在 `api.ts`，中间层别名在 `generated-api.ts` |

**如果这个端点也要给机器面用** → 见 §6。

### 5.2 OpenAPI 基线与前端类型

链路（**没有一键脚本**，是手工复制）：

```
OpenApiSpecIntegrationTest（MockMvc 打 GET /v3/api-docs）
   → backend/control-plane-app/target/openapi-spec.json     （测试里写盘）
   → 人工复制覆盖 docs/openapi/openapi-3.1.json            （提交的基线，176 个 path）
   → CI: deploy/openapi/check-openapi-breaking.py 比对 基线 vs 新生成
     （删除 path/operation/响应码/参数、属性变 required 即失败；新增允许）
   → CI: deploy/openapi/check-openapi-schema-names.py 查重复 record 简单名与裸名 schema
   → npm run gen:types → frontend/src/types/generated.ts     （openapi-typescript，10422 行）
   → CI: git diff --exit-code -- src/types/generated.ts      （漂移门禁）
```

具体位置：
- 生成测试：`backend/control-plane-app/src/test/java/com/miqroera/miqrokey/controlplane/controller/OpenApiSpecIntegrationTest.java`——断言 `openapi=3.1.0`、`paths > 30`、4 个 security scheme（`portalSession`/`csrfToken`/`apiKey`/`consumerJwt`）、各面代表性 operation 存在、`schemas > 20`、且 spec 里**绝不出现 `passwordHash` 与 `confirmTokenHash`**（`:117, :122`）；`:124-127` 把结果写到 `target/openapi-spec.json`。同文件第二个测试（`:153-164`）**强制控制台面与机器面的"孪生 DTO"字段与约束完全一致**（#1073）。
- 基线泄漏守卫：`OpenApiBaselineTestControllerLeakTest.java`——扫源码里的 `@*Mapping`，**如果基线里发布了只存在于测试类路径的 operation 就失败**（`AdminTestController` 靠 `@Hidden` 排除）。
- `gen:types`：`frontend/package.json:20` = `openapi-typescript ../docs/openapi/openapi-3.1.json -o src/types/generated.ts`。
- 前端手写类型一致性：`frontend/src/__tests__/codegen-consistency.spec.ts`——读基线，把 `src/types/api.ts` 里每个导出的 interface 与同名（或 `*View` 后缀）schema 配对，断言手写字段是 schema 属性的子集。**新增手写 DTO 时这条会红。**
- CI 位置：`.github/workflows/ci.yml:284-314`（后端集成 + 两个 openapi 检查）、`:341-376`（前端 `gen:types` 漂移门禁）。

**数据面不进 OpenAPI**：推理入口以透明代理契约 + fixtures 验证，不参与管理 API 的 DTO 生成（`api-contract.md:1283`）。

### 5.3 契约测试在哪

- **管理面逐域**：`backend/control-plane-app/src/test/java/.../controller/`（93 个文件），命名 `XxxApiIntegrationTest` / `XxxInputValidationTest`。
- **横切**：`AdminPathNormalizationIntegrationTest`（路径归一化绕过）、`OriginInterceptorProductionTest`、`AdminIpAllowlistApiIntegrationTest`、`GlobalErrorSemanticsIntegrationTest`、`GlobalExceptionHandlerTest`、`CustomCsrfCookieNameTest`、`AuditChainIntegrityTest`、`AdminAuditCoverageBatch2IntegrationTest`。
- **机器面**：`OpenAdminReadApiIntegrationTest`、`OpenAdminWriteApiIntegrationTest`（后者含 #1073 的"两个面同 payload 同答案"断言）、`OpenAdminVirtualKeysApiIntegrationTest`、`AdminApiKeyScopeIntegrationTest`。
- **数据面**（网关模块，命名 `*ContractTest`，**注意它们不是 OpenAPI 契约测试**，是 wire-level 行为测试）：`VirtualKeyAuthContractTest`、`AnthropicProxyContractTest`、`ChatProxyContractTest`、`ResponsesProxyContractTest`、`McpProxyContractTest`、`QuotaGateContractTest`。
- **性能/负载**：`deploy/loadtest/`。

**改接口时的最低验证**：改哪个域就跑该域的 `*IntegrationTest`；改了错误映射跑 `GlobalErrorSemanticsIntegrationTest`；改了 spec 相关跑 `OpenApiSpecIntegrationTest` + `npm run gen:types`。

---

## 6. 开放管理 API（机器面）

### 6.1 与人类面的四个结构性差异

| 维度 | 人类面 `/api/v1/admin/**` | 机器面 `/api/v1/admin-api/**` |
|---|---|---|
| 鉴权 | 门户会话 Cookie + CSRF | `Authorization: Bearer mqk_admin_…`（**只认 Bearer，无 `X-API-Key`**）；**也接受 SYSTEM_ADMIN 会话**（会话分支额外要求 CSRF） |
| 授权 | `RoleInterceptor` 前缀 deny-by-default | `AdminApiKeyAuthFilter` 的**能力组映射** |
| 覆盖面 | 全部管理能力 | **白名单子集**——不是自动继承 |
| 执行者 | 会话用户 | 发行该密钥的管理员（+ 审计 `via: admin-api:<密钥名>`） |

**关键代码事实**：`RoleInterceptor` 的 deny-by-default 判的是 `startsWith("/api/v1/admin/")`（`RoleInterceptor.java:39`），而 `/api/v1/admin-api/...` **不以 `/api/v1/admin/` 开头**——两者是**不同前缀**。所以机器面**完全不走** `RoleInterceptor`，全部授权在 filter 里。改这块时别指望 `RoleInterceptor` 兜底。

### 6.2 能力组（scope）与它的两处反直觉

`capabilityFor(path)`（`AdminApiKeyAuthFilter.java:130-146`）把开放面路径映射到 4 个能力组：

| 能力组 | 覆盖的路径前缀 |
|---|---|
| `usage:read` | `/usage/`、`/audit-events`、`/api-keys`、`/quota-rules`、`/mcp-access-logs` |
| `alerts:write` | `/alert-rules`、`/webhooks`（**含读**） |
| `exports:create` | `/export-tasks` |
| `vkeys:delegate` | `/virtual-keys` |

**⚠ 反直觉之一**：`scopeAllows` 的实现是 `required != null && capabilities.contains(required)`（`:120-127`）。映射不到的路径 `required` 为 `null` → **返回 false**。也就是说**带 scope 的密钥访问任何未映射路径都会被拒**（fail-closed），而无 scope 的密钥（`capabilities == null`）走 `:123` 直接放行。今天所有开放面路径都有映射，但**新增开放面端点时必须同步加映射**，否则带 scope 的密钥会莫名 403。

**⚠ 反直觉之二**：`/export-tasks` 的**读**（`GET` 列表与详情）也要 `exports:create` 能力。想给一个"只能看导出任务"的只读密钥，今天配不出来——`usage:read` 不够。

**越权被拒**：403 `ADMIN_API_SCOPE_DENIED`（problem+json），**且当密钥有发行管理员时写审计** `ADMIN_API_KEY_SCOPE_DENIED`（摘要含 path，`AdminApiKeyAuthFilter.java:148-157`）。SYSTEM_ADMIN **会话**分支不做能力检查、也不审计——会话是全量的。

**scope 语义**：`null` = 全量（存量兼容）；`[]` = 全拒；未知或重复码 → 400 `ADMIN_API_KEY_SCOPE_INVALID`（在 `AdminApiKeyController` 的 PATCH，§4.11.3）。

### 6.3 现有端点（全部）

| 端点 | 能力组 | 与人类面的关系 |
|---|---|---|
| `GET /usage/summary` | `usage:read` | 同口径，但**只接 `groupBy`/`from`/`to`/`tzOffsetMinutes`——没有任何过滤维度**（`OpenAdminUsageReadController.java:34-44`），比人类面窄 |
| `GET /usage/records` | `usage:read` | 同上，**没有 `clientIp` 也没有任何 filter**；有 `page`/`size` |
| `GET /audit-events` | `usage:read` | 与人类面同筛选同 cursor 语义（`OpenAdminAuditReadController.java:37`）；哈希永不序列化 |
| `GET /audit-events/export` | `usage:read` | 与人类面 `AdminAuditController.writeCsv` **共用同一个写 CSV 方法**，形状/截断完全一致 |
| `GET /api-keys` | `usage:read` | 本租户管理密钥视图，无 digest/secret |
| `GET /quota-rules` | `usage:read` | 与人类面同口径（**共用 `AdminQuotaRuleService.list`**） |
| `GET /export-tasks?limit` / `GET /export-tasks/{id}` | `exports:create` | **不读/不返回 `file_bytes`** |
| `GET /mcp-access-logs` | `usage:read` | 参数/窗口/上限同人类面（**共用 `AdminMcpAccessLogService.view`**） |
| `POST /alert-rules`、`GET/PATCH/DELETE /alert-rules[/{id}]` | `alerts:write` | 全生命周期；**规则与人类面同语义同约束**（`#1073`） |
| `POST /webhooks`、`GET/PATCH/DELETE /webhooks[/{id}]`、`GET /{id}/deliveries`、`POST /{id}/test` | `alerts:write` | 同语义同 SSRF 校验；`DELETE` 同 I21 依赖检查 |
| `POST /export-tasks?format&from&to` | `exports:create` | **委托模式（ADR-0016 选项 A）**：任务的 `created_by` = 发行管理员。响应与下载面**不含文件字节**（下载仍在会话面） |
| `POST /virtual-keys` | `vkeys:delegate` | **委托建钥**：body = §4.2.1 的建钥字段 + 目标 `userId`；钥归属**目标用户**，成员/授权校验按目标执行；secret 仅此一次 |
| `GET /virtual-keys?userId=` | `vkeys:delegate` | 该租户某用户拥有的钥视图（无 secret） |

### 6.4 机器面的坑

- **`EXECUTOR_UNKNOWN`（403）**：密钥**没有发行管理员**时，`POST /export-tasks` 与 `POST /virtual-keys` 直接拒（`OpenAdminExportsReadController.java:72-76`、`OpenAdminVirtualKeysController.java:70-74`）。普通密钥都有 `created_by`，但**用 SQL 直接造的密钥可能没有**。
- **委托链的两道额外检查**：委托人 = 密钥发行管理员且**现行角色须仍为 SYSTEM_ADMIN**，否则 403 `DELEGATION_FORBIDDEN`；目标用户不存在 → 404 `TARGET_USER_NOT_FOUND`、停用 → 409 `TARGET_USER_INACTIVE`、非项目成员 → 403 `PROJECT_MEMBERSHIP_REQUIRED`（**SYSTEM_ADMIN 目标豁免**，与自助一致）。密钥归属落**目标用户**（`user_id` = 目标），审计 `VIRTUAL_KEY_CREATE` 的 actor = 委托人，摘要含 `targetUserId`。
- **`GET /virtual-keys?userId=` 不校验目标用户是否存在**（`VirtualKeyService.java:424-426` 直接按 `(tenantId, userId)` 查）——不存在的 userId 返回**空数组**而不是 404。这是查询语义，不是错误。
- **机器面写操作的 `AuditContext`**：`issuer` 非空 → `AuditContext.machine(issuer, keyName, requestId)`，摘要附 `via: admin-api:<密钥名>`；否则 → `AuditContext.human(会话用户)`（**SYSTEM_ADMIN 会话走这条**）。所有开放写控制器都重复了这段样板（`OpenAdminAlertRulesController.java:88-96`、`OpenAdminWebhooksController.java:93-101`、`OpenAdminExportsReadController.java:93-101`）——加新开放写端点时照抄这一段，**别漏 `via` 标记**，否则审计无法区分人和机器。
- **两面的约束必须逐条一致**：`OpenApiSpecIntegrationTest` 的孪生 DTO 测试与 `OpenAdminWriteApiIntegrationTest.bothFacesRejectTheSamePayloadsIdentically`（`:212-258`）都在守这条。**加机器面端点时复制人类面的 record 连同全部注解**，改一处必须改两处。
- **机器面不受 `RoleInterceptor` 保护，但受 `AdminIpAllowlistFilter` 约束**（§1.4）；**生产模式下写操作还要带 `Origin`**（§2.4，仓库自带示例脚本在这个点上是错的）。
- **机器面的读面比人类面窄**是**刻意的**（batch 1b 只开子集），不是 bug。要放宽得先扩能力组映射与 ADR-0015 的批次记录。

---

## 7. 代码与仓库文档的冲突清单（以代码为准）

| # | 冲突 | 代码事实（含位置） | 文档说法 |
|---|---|---|---|
| 1 | **`@RequireRole` 是死注解** | 全仓 **0 处使用**（`grep -rn "@RequireRole" backend/` 为空）；`RoleInterceptor.java:51-84` 的整段逻辑因此从不触发 | `02` 与 `api-contract` 都未说明；接手人容易以为它保护了某些接口 |
| 2 | **`version` 不是通用请求体字段** | 只有 seats PATCH（`AdminSubscriptionController.java:99`）与 agents PATCH 提交 `version`；其余全部服务端 CAS，客户端无令牌 | `api-contract.md:13` 的"**#734 更正为实现现状**：`version` 为**请求体字段**、随写请求提交" |
| 3 | **`Idempotency-Key` 头未实现** | 无任何 filter/拦截器读这个头 | `api-contract.md:12` 已自标"预留：当前版本未实现"，但 §1 正文仍列着它 |
| 4 | **`/api/v1/admin/usage/timeline` 未记载** | `AdminUsageController.java:53`，OpenAPI 基线里有 | `api-contract.md` §5.2 的端点表里没有它 |
| 5 | **`/api/v1/me/plaza/models` 未记载** | `MePlazaController.java:28`，OpenAPI 基线里有 | `api-contract.md` §4 没有它 |
| 6 | **`teamId` 过滤参数未记载** | `AdminUsageController.java:76, 97, 113`（summary/records/hourly 三个都有） | `api-contract.md` §5.2 的过滤列表只有 `userId`/`projectId`/`virtualKeyId`/`credentialId`/`subscriptionId`/`providerProductId`/`modelId` |
| 7 | **工具导入的 body 形状相反** | 代码把**原始 body** 当 OpenAPI 对象（`AdminMcpToolController.java:74` + `ToolOpenApiParser`；集成测试也发裸对象） | `api-contract.md:987` 写的是 body `{"spec": <OpenAPI JSON>}`；照文档发会 400 `SPEC_INVALID` |
| 8 | **MCP status 的乐观锁承诺不可达** | `AdminMcpService.java` 的 `updateStatus` 是窄更新、无版本判定 | `api-contract.md:958` 承诺"并发编辑乐观锁竞争 → 409 CONCURRENT_MODIFICATION" |
| 9 | **多处写操作不写审计** | `POST /subscriptions/{id}/quota/refresh`（`QuotaSnapshotService` 没有 `AuditService` 依赖）、`POST /subscriptions/{id}/cost-allocation/allocate`（`CostAllocationService.java:75` 起无审计） | `api-contract.md:439` 的"所有写操作写审计事件"与 §5.0 的 #324 覆盖段 |
| 10 | **多端点审计丢 `requestId`** | `/api/v1/admin/api-keys` 三个写操作（`AdminApiKeyService.java:57,79,92`）、`unattributed-policy` PUT/DELETE、配额模板自动复制、MCP resilience PUT（空串） | 无文档承诺；但 `AuditContext` 的形状暗示 requestId 总有值 |
| 11 | **分页规则与实现不符** | offset 分页在三个用量明细端点上长期存在 | `api-contract.md:11` "禁止 offset 深分页" |
| 12 | **`CLAUDE.md` 的限流红线已过期** | ADR-0020 已落地：`action=REJECT` 的配额规则超限返回 429 | `CLAUDE.md:38` 仍写"**不限流、不因预算阻断**，只做 Webhook 告警"；ADR-0020 `:5` 明确声明修订了这条 |
| 13 | **前端类型"手写"的描述已过期** | 已是 `gen:types`（openapi-typescript）生成 `generated.ts` + 手写别名层 | `api-contract.md:1281` 仍写"前端 TypeScript client **目前由手写** `frontend/src/api` + `types/api` 维护（未从 OpenAPI 生成）"；`docs/release-checklist.md:22` 同样陈旧 |
| 14 | **`{serviceId}` 归属校验在部分端点上缺失** | 工具有 4 条路径忽略 `{serviceId}`（`AdminMcpToolController.java:122,132,143,156`）；route-rule 的 status/delete 同样忽略 | 无文档承诺；但同一组端点里 ACL 与 retry-policy **是**校验的，行为不一致 |
| 15 | **路由规则变更不发快照刷新** | `AdminMcpRouteRuleService` 不持有 `RouteRefreshPublisher`（字段 `:39-41`） | 无文档承诺；但 §4.9 其它同类变更都发，`api-contract.md:1110` 也描述了配置面先行、数据面生效的预期 |
| 16 | **`.gitignore`/文档提到的 ADR-0008 文件不存在** | 仓库里没有 `0008-*.md`（ADR-0018 `:5` 承认了这点） | 多处引用 "ADR-0008"（如 `api-contract.md:1276`、`VirtualKeyService` 注释） |
| 17 | **webhook `dependencies.detail` 的取值** | 代码发的是字面中文 `"已启用"`/`"已停用"` | `api-contract.md:734` 写成 `"已启用\|已停用"`（渲染成带竖线的正则样），实际不是 |
| 18 | **`AdminMcpRouteRuleController`/`AdminMcpAccessLogController` 的 §编号引用错了** | 类注释分别写 §5.19 / §5.23 | 实际是 §5.23 / §5.24 |
| 19 | **CSRF 豁免名单在契约里漏了 register** | `CsrfInterceptor.java:30-31` = `{login, bootstrap, register}` | `api-contract.md:93` 正文只列 login 与 bootstrap；**同一文档 `:62` 又说 register 免 CSRF**——`:93` 是陈旧的 |
| 20 | **OAuth 回调的 HTTP 层失败会 500 而不是重定向** | `code` 缺失或 token/userinfo 端点非 2xx 时抛 `RestClientResponseException`，在 try 之外（`PlatformOidcAuthService.java:200-201, 217-218`）→ 500 `INTERNAL_ERROR` | `api-contract.md:81-82` 承诺失败重定向 `/login-new?oauth_error=AUTH_ERROR` 等 7 个取值 |
| 21 | **`/me/usage/records` 的 `page` 无上限** | `UsageStatsService` 只校验 `page ≥ 1`；管理员端同一功能有 `MAX_PAGE=1_000_000` 溢出保护（#475） | 契约未提任何上限；两者行为不一致，且个人端可被构造出 500 |
| 22 | **`ExportStatus.EXPIRED` 从不被写入** | 全仓没有地方把导出任务置为 `EXPIRED`；过期只体现为下载 410，GC 后变 404 | `api-contract.md` §5.5/§6 把 `EXPIRED` 列为 `GET /exports/{id}` 的可能状态 |
| 23 | **价格 `source` 是自由文本** | `POST /admin/prices` 的 `source` 只约束 `@NotBlank @Size(32)`，任意 32 字符串都会被原样持久化 | `api-contract.md:771` 写的是 `MANUAL`/`OFFICIAL` |
| 24 | **对账行的 `nextCursor` 到底时是空串** | `ReconciliationService` 返回 `""` | `api-contract.md:1196` 只说有 `nextCursor`；判 null 的客户端会死循环或漏判 |
| 25 | **确认删除用量时 null token 会 500** | `{"confirmToken":null}` → `sha256(null)` NPE → 500 `INTERNAL_ERROR` | `api-contract.md:685` 承诺错误 token → 403 `DELETION_TOKEN_INVALID` |
| 26 | **座位 PATCH 的必填 `version` 未记载** | `AdminSubscriptionController.java:99` `@NotNull Long version`，且会抛 400 `SEAT_ASSIGNEE_MISMATCH`、409 `CONCURRENT_MODIFICATION` | `api-contract.md:464` 的座位 PATCH 行只写"分配/释放/禁用席位"；`:466` 的错误码表也只列了三个 NOT_FOUND |
| 27 | **`/me/model-approvals` 的 GET 排序没有 id tiebreaker** | 只有 `created_at DESC`；管理员端队列用的是 `(created_at, id)` | `api-contract.md:334` 承诺"时间倒序"（同秒创建时顺序不稳定） |

---

## 8. 未能核实 / 明确的空白

以下条目**没有找到充分证据**，接手时请自行确认，不要当作结论：

1. **`OriginInterceptor` 对机器面的实际影响**：机制上生产模式会 403（§2.4），但**没有核实**是否有真实部署以 `miqrokey.production=false` 运行、或 nginx 层补了 Origin 头从而掩盖了这个问题。这是本文里最值得第一时间实测的一条。
2. **配额执法器 `QuotaEnforcementService` 的实现**：评估周期、写 `quota_enforcement` 的方式、以及"多规则命中同一作用域取最早窗口"的具体算法，**只从契约 §5.19 与 `QuotaGate.java` 的读取侧反推**，**没有读 `QuotaEnforcementService` 本身**。
3. **`SkillService` 的 zip 校验细节**：包限 5MB / 条目 200 / SKILL.md 512KB 来自契约与 `SkillZipValidator` 的转述，**没有逐条核对常量取值**。
4. **`AdminReconciliationController` 的匹配算法**：`PARTIAL` 桶的精确判定（5 分钟桶）、`matched_by` 的取值集合，**没有读匹配服务实现**，只有契约与 `bill-reconciliation-contract.md` 的转述。
5. **`AdminReconciliationController` 之外的流式 CSV 是否都先 count**：确认了留痕导出是先 count 后流式、审计与对账导出是内存构建，但**没有核实这三者在超大结果集下的内存表现**。
6. **前端各页面对特殊字段的渲染**：本文只覆盖到"类型从哪来、调用函数写在哪"。**没有核实**前端是否对 `priced=false`、`pricingStatus != COMPLETE`、`resolutionCandidates=null` 按契约要求标注"未定价/未记录"（契约多处明确要求页面并排展示裁定与声明来源）。这是接手后值得做一遍的核对。
7. **`AdminModelCatalogController.test-run` 的 `content` 空串标记**：只从代码注释知道"仅有 `reasoning_content` 时会返回空串或显式标记"，**没有核实标记的确切字面量**。
8. **`MCP_TIMEOUT` 死常量是否还有别的引用**：全仓 grep 只有声明处（`McpProxyController.java:107`）一处，但**没有核实是否有反射/配置在别处引用它**。
9. **`GET /api/v1/admin/usage/timeline` 的 `status=IN_FLIGHT` 分支在前端的呈现**：确认了字段可能取该值，**没有核实前端是否处理**。

---

*本文基于 `origin/develop @ 5d4c3bca` 逐文件核对写成。行号会随代码演进漂移——找不到时请以类名/方法名锚点为准。*
