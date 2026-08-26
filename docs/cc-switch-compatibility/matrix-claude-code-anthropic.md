# Matrix: Claude Code → Anthropic Messages (Direct Provider)

> **Client:** Claude Code 2.1.216
> **CC Switch mode:** Anthropic Provider (direct, no protocol conversion)
> **Gateway path:** `POST /v1/messages`
> **Upstream protocol:** Anthropic Messages (transparent pass-through)

## Test Configuration

| Parameter | Value |
|---|---|
| Gateway Base URL | `http://127.0.0.1:8081` |
| Synthetic Key | `sk-miqrokey-…REDACTED000000` |
| CC Switch Provider Type | Anthropic (GUI field name ENV_BLOCKED) |
| CC Switch Auth Header | `x-api-key` (ENV_BLOCKED — verify on instance) |
| Upstream | Real Anthropic API or compatible endpoint |
| Gateway Mode | Transparent — does not modify Anthropic protocol |

## Prerequisites

1. [ ] **Terminal 1:** Mock running on `http://127.0.0.1:8082` (`run-mock.ps1` / `run-mock.sh`)
2. [ ] **Terminal 2:** Gateway running on `http://127.0.0.1:8081` (`run-gateway.ps1` / `run-gateway.sh`)
3. [ ] Both health checks passing (see [manual-verification-guide.md](manual-verification-guide.md) section 3.3)
4. [ ] CC Switch Anthropic Provider configured (see [config-field-reference.md](config-field-reference.md) section 4.2)
5. [ ] Claude Code set to use the MiQroKey CC Switch provider
6. [ ] Console/terminal available for observing Claude Code and Gateway logs

## Status Key

| Status | Meaning |
|---|---|
| `NOT_TESTED` | Scenario not yet attempted |
| `PASS` | Expected behavior observed |
| `FAIL` | Unexpected behavior; details in Notes column |
| `ENV_BLOCKED` | Cannot test — missing tool, access, or configuration |
| `PENDING_IMPLEMENTATION` | Gateway feature not yet built |

---

## Basic Messaging

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 1 | Simple prompt: "Hello, what is 2+2?" | `NOT_TESTED` | Claude Code receives a text response. Gateway logs show request ID, upstream 200, TTFB recorded. | |
| 2 | Multi-turn conversation (3 exchanges) | `NOT_TESTED` | Each turn succeeds. No session state lost between turns. Gateway treats each turn as independent request. | |
| 3 | System prompt + user message | `NOT_TESTED` | Claude Code sends `system` role; Gateway forwards unchanged. Response includes system prompt effect. | |
| 4 | Non-English prompt (Chinese: "你好") | `NOT_TESTED` | Claude Code sends UTF-8. Gateway preserves UTF-8 encoding. Response received correctly. | |
| 5 | Large prompt (~4000 tokens) | `NOT_TESTED` | Claude Code sends large body. Gateway forwards without truncation. Response received. | |

## Streaming

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 6 | Stream: "Write a short poem about a cat" | `NOT_TESTED` | Claude Code displays streaming text (tokens appear incrementally). Gateway logs show SSE events forwarded. | |
| 7 | Stream with `max_tokens` limit | `NOT_TESTED` | Claude Code respects the limit. Gateway forwards `max_tokens` parameter unchanged. | |

## Tool Use

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 8 | Tool: "List files in the current directory" | `NOT_TESTED` | Claude Code sends tool definition. Gateway forwards `tools` array unchanged. Claude Code executes tool and returns result. | |
| 9 | Multi-tool call in single turn | `NOT_TESTED` | Multiple tool calls in one response. Gateway preserves tool_use/tool_result message roles. | |
| 10 | Tool result round-trip | `NOT_TESTED` | Claude Code sends `tool_result`. Gateway forwards. Model uses tool result in next response. | |

## Thinking / Extended Thinking

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 11 | Enable thinking: "Explain the CAP theorem" | `NOT_TESTED` | Claude Code sends `thinking` parameter. Gateway forwards unchanged. Response includes thinking blocks (if model supports it). | |

## Prompt Caching

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 12 | Cache breakpoints: large system prompt with `cache_control` | `NOT_TESTED` | Claude Code sends `cache_control` markers. Gateway forwards unchanged. Response may include `cache_creation_input_tokens`/`cache_read_input_tokens`. | |

## Usage

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 13 | Usage in non-streaming response | `NOT_TESTED` | Gateway's SSE observer extracts `usage.input_tokens`, `usage.output_tokens` from `message` nesting. | |
| 14 | Usage in streaming response | `NOT_TESTED` | Gateway's SSE observer extracts usage from `message_stop` or final `message_delta` event. | |
| 15 | Cache token usage in response | `NOT_TESTED` | Gateway's SSE observer extracts cache read/write tokens if present. | |

## Special Headers

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 16 | `anthropic-version` header present | `NOT_TESTED` | Gateway forwards `anthropic-version` header unchanged to upstream. | |
| 17 | `anthropic-beta` header present | `NOT_TESTED` | Gateway forwards `anthropic-beta` header unchanged to upstream. | |

## Error Handling

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 18 | Invalid model name: "nonexistent-model-v999" | `NOT_TESTED` | Upstream returns error (400/404). Gateway forwards error status and body unchanged. Claude Code displays error message. | |
| 19 | Missing required field (omit `messages`) | `NOT_TESTED` | Upstream returns 400. Gateway forwards error. Claude Code displays error. | |

## Cancellation

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 20 | Cancel mid-stream: Ctrl+C during long response | `NOT_TESTED` | Claude Code disconnects. Gateway propagates cancellation — upstream TCP connection closes. Gateway logs show cancellation signal. | |

## Credential Security

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 21 | Verify synthetic key not in upstream request | `NOT_TESTED` | Gateway strips `x-api-key` header. Upstream never sees the synthetic key. (Verify by checking upstream logs or using a mock that captures headers.) | |
| 22 | Verify `X-MiQroKey-*` headers stripped | `NOT_TESTED` | If Claude Code or CC Switch accidentally forwards internal tracking headers, Gateway strips them. | |

## Path Allowlisting

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 23 | `POST /v1/messages/count_tokens` (if CC Switch sends it) | `NOT_TESTED` | Gateway returns 404. This endpoint is not yet implemented (G2.6). | |

---

## Windows Tester Steps

1. Open PowerShell as the test user.
2. Set Claude Code to use the CC Switch Anthropic provider:
   ```
   # The exact command depends on Claude Code version
   # Typically: /provider or a settings file
   ```
3. Run scenarios 1–23 in order.
4. For each scenario:
   a. Enter the prompt in Claude Code.
   b. Observe Claude Code output (text, streaming, tools, errors).
   c. Check Gateway logs (DEBUG level for `com.miqroera.miqrokey`):
      ```
      # In the Gateway terminal window, watch for request IDs and status codes
      ```
   d. Record PASS/FAIL/NOT_TESTED with notes.
5. After all scenarios: run scenario 21–22 to verify credential security.

## Linux Tester Steps

1. Open terminal as the test user.
2. Set Claude Code to use the CC Switch Anthropic provider.
3. Run scenarios 1–23 in order (same procedure as Windows).
4. Gateway logs are in the terminal where `./mvnw -f backend spring-boot:run` is running.
5. Add `--debug` to Maven or set `logging.level.com.miqroera.miqrokey=DEBUG` in `application.yml` for detailed proxy logs.

## Completion Criteria

- All testable scenarios (not ENV_BLOCKED) have a recorded status.
- At least scenarios 1 (simple prompt), 6 (streaming), and 20 (cancellation) pass.
- Any FAIL has a clear diagnosis in the Notes column.
- Credential security scenarios 21–22 pass (no synthetic key leakage).

---

*Tester name:* _______________
*Date:* _______________
*CC Switch version (from CC Switch about/settings):* _______________
*Gateway commit SHA:* _______________
*Upstream endpoint used:* _______________
