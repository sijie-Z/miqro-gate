# 开发进度

> 此文件是跨 Claude Code/Goal 会话的最小交接状态。每个 Goal 开始和结束时必须更新。不要在这里复制完整设计；链接到事实来源。

## Current State

- Project phase: `PHASE_0`
- Current executor: `Codex` (G0.2 acceptance repair after Claude Code handoff)
- Current goal: `G0.2`
- Goal status: `DONE`
- Last updated: `2026-07-21`
- Branch: `fix/g0.2-cancellation-state-race`
- Remote: `https://github.com/lichman0405/miqro-key-gateway.git`

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
- Docker 不在当前 Windows 环境；Compose config 与镜像 digest 门禁已由 GitHub Actions 验证通过。

## Next Goal

- Goal ID: `G0.3`
- Name: Responses and Chat transparent PoC
- Source: [`implementation-plan.md`](implementation-plan.md#g03-responses-and-chat-transparent-poc)

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

## Goal Update Template

```text
Current goal: Gx.y
Goal status: IN_PROGRESS | BLOCKED | DONE
Started/finished date:
Files/modules changed:
Verification commands and results:
Decisions/ADRs added:
Remaining risks:
Next goal:
```
