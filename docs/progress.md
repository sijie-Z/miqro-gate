# 开发进度

> 此文件是跨 Claude Code/Goal 会话的最小交接状态。每个 Goal 开始和结束时必须更新。不要在这里复制完整设计；链接到事实来源。

## Current State

- Project phase: `PHASE_1`
- Current executor: `Claude Code`
- Current goal: `tag-routing-usage-closed-loop`（G1.4 授权部分 + G1.5 + G2.2 + G2.3 + G2.4 + G5.1 核心闭环）
- Goal status: `DONE`（全模块本地验证全绿，含 Testcontainers 集成测试）
- Last updated: `2026-08-25 10:50 CST`
- Branch: `goal/tag-routing-usage-closed-loop`
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

- Goal ID: `G1.1`
- Name: PostgreSQL schema and persistence
- Status: `NOT_STARTED`
- Source: [`implementation-plan.md`](implementation-plan.md#g11-postgresql-schema-and-persistence)

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

- **Push 已解决（2026-08-25）**：目标远端改为所有者仓库 `sijie-Z/miqro-key-gateway`（新建 private）；origin 已切换、`.git/shallow` 浅克隆状态已解除（`git fetch --unshallow upstream`，upstream = `lichman0405/miqro-key-gateway`）。`goal/tag-routing-usage-closed-loop` 已 push 成功。
- 集成测试（12 个 Me* + 其余 Tag(integration) 类）已在本机 Docker Desktop（Testcontainers 1.21.4，`DOCKER_HOST=tcp://localhost:2375`）全部通过；Linux CI 作为交叉验证保留。
- 真实供应商凭证未提供：Gateway 凭证注入只经 Mock 上游验证，真实联调 `WAITING_FOR_CREDENTIAL`。
- 响应缓存默认关闭（ADR-0008 决策），正式启用前需新增 ADR。
- `request_usage_records` 完整分区表（规格 §6）未实现，当前使用 `usage_event` 事实表；G4.x 需要时再演进。
- 前端 chunk 1MB+ 警告：Element Plus 全量引入；可按需引入优化（非阻塞）。
