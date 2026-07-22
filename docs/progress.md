# 开发进度

> 此文件是跨 Claude Code/Goal 会话的最小交接状态。每个 Goal 开始和结束时必须更新。不要在这里复制完整设计；链接到事实来源。

## Current State

- Project phase: `PHASE_1`
- Current executor: `Claude Code`
- Current goal: `G1.1`
- Goal status: `DONE`
- Last updated: `2026-07-22`
- Branch: `goal/g1.1-postgresql-schema-and-persistence`
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

## Next Goal

- Goal ID: `G1.2`
- Name: Secret encryption foundation
- Status: `NOT_STARTED`
- Source: [`implementation-plan.md`](implementation-plan.md#g12-secret-encryption-foundation)
