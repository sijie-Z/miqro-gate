# Version Evidence

> Status key:
> - **CONFIRMED** — version observed in running tool output (e.g. `--version`) or
>   executable properties (e.g. FileVersion/ProductVersion)
> - **MANUAL_REQUIRED** — tool is present and version is CONFIRMED, but
>   configuration and client-path validation require a human tester at the
>   tool's own GUI/CLI; not performed in this Goal
> - **ENV_BLOCKED** — tool or environment is not accessible on the current
>   development host (e.g. Docker not installed); version not independently
>   confirmed
> - **DOCUMENTED** — version taken from official documentation or release notes,
>   not from a live instance

## MiQroKey Gateway

| Component | Version | Status | Evidence |
|---|---|---|---|
| Gateway | `0.1.0-SNAPSHOT` | CONFIRMED | Built from current workspace; `mvnw clean verify` passes 223 tests (test-support 109, gateway-app 111, control-plane-app 2, domain 1); 0 failures/errors/skips |
| Java | 21 (Temurin 21.0.11) | CONFIRMED | `java -version` on development host |
| Spring Boot | 3.4.x | CONFIRMED | Parent POM `spring-boot-starter-parent` |
| Reactor Netty | 1.2.x | CONFIRMED | Resolved by Spring Boot dependency management |

## CC Switch

| Component | Version | Status | Evidence |
|---|---|---|---|
| CC Switch application | **3.18.0** | **CONFIRMED** | Executable FileVersion and ProductVersion both read `3.18.0`; independently verified on the development host |
| CC Switch GUI config fields / UI layout | N/A | **MANUAL_REQUIRED** | CC Switch configuration was deliberately not touched in this Goal; actual UI field names, dropdown values, and provider setup flows require a human tester to open the CC Switch GUI and document |

## Client Tools

| Component | Version | Status | Evidence |
|---|---|---|---|
| Claude Code | 2.1.216 | CONFIRMED | `claude --version` returns `2.1.216` (npm: `@anthropic-ai/claude-code`) |
| Codex CLI | 0.144.6 | CONFIRMED | `codex --version` returns `0.144.6` (npm: `@openai/codex`) |
| Claude Desktop | **1.24012.1** | **CONFIRMED** | Executable FileVersion and ProductVersion both read `1.24012.1`; independently verified on the development host |
| Claude Desktop configuration / client behavior | N/A | **MANUAL_REQUIRED** | Claude Desktop configuration was deliberately not touched in this Goal; third-party provider setup and GUI behavior require a human tester |

## Real Client Paths (Unexecuted / MANUAL_REQUIRED)

The following real-client end-to-end paths have **not been executed** and are
**MANUAL_REQUIRED** because the Gateway's upstream credential injection is
not yet implemented (target G1.5) and CC Switch provider configuration
requires a human tester at the CC Switch GUI:

| Path | Reason |
|---|---|
| Claude Code → CC Switch Anthropic Provider → MiQroKey Gateway → real Anthropic upstream | No real upstream API key configured; G1.5 will add credential injection |
| Claude Code → CC Switch Local Routing → MiQroKey Gateway → real OpenAI-format upstream | No real upstream API key configured; CC Switch routing configuration MANUAL_REQUIRED |
| Codex → MiQroKey Gateway → real OpenAI Responses upstream | No real upstream API key configured |
| Claude Desktop → CC Switch → MiQroKey Gateway → real upstream | Claude Desktop configuration MANUAL_REQUIRED; no real upstream API key |
| CC Switch GUI provider configuration | MANUAL_REQUIRED — human tester must configure CC Switch providers at the GUI |

## Docker

| Component | Version | Status | Evidence |
|---|---|---|---|
| Docker Engine | N/A | **ENV_BLOCKED** | Docker is not installed on the development host; `docker compose -f deploy/compose.yaml config` cannot be run locally. CI must validate Compose. |

## Mock-Verified Capabilities

The following capabilities are verified by contract tests (**223 tests total across
all modules, all PASS**) but have **not** been validated against real CC Switch
instances (MANUAL_REQUIRED for the CC Switch leg):

| Capability | Contract Test Evidence | Real Client Status |
|---|---|---|
| Anthropic Messages `/v1/messages` non-stream + SSE | `AnthropicProxyContractTest` (24 tests) | **MANUAL_REQUIRED** |
| OpenAI Responses `/v1/responses` non-stream + SSE | `ResponsesProxyContractTest` (23 tests) | **MANUAL_REQUIRED** |
| OpenAI Chat `/v1/chat/completions` non-stream + SSE | `ChatProxyContractTest` (24 tests) | **MANUAL_REQUIRED** |
| Credential stripping (authorization, x-api-key, api-key) | All three contract test `HeaderStripping` suites | **MANUAL_REQUIRED** |
| Hop-by-hop and framing header removal | All three contract test `HeaderStripping` suites | **MANUAL_REQUIRED** |
| SSE usage observation (token counting, no body logging) | `SseUsageObserverTest` (10 tests) + `Privacy` suites | **MANUAL_REQUIRED** |
| Client cancellation propagation | All three contract test `Cancellation` suites | **MANUAL_REQUIRED** |
| Path allowlisting + protocol-compatible errors | All three contract test `PathAllowlisting` suites | **MANUAL_REQUIRED** |
| TTFB recording | `TtfbRecorderTest` (3 tests) | **MANUAL_REQUIRED** |
| No blocking calls in production code | `GatewayNoBlockingTest` (3 tests) | **MANUAL_REQUIRED** |
| Compatibility Mock Server (full HTTP contract) | `CompatibilityMockServerTest` (55 tests) + `ObservationStoreTest` (19 tests) | **MOCK_VERIFIED** |
| Bounded body / media-type repair | `RequestLifecycleTest` (10 tests) + all 109 test-support tests | **MOCK_VERIFIED** |

