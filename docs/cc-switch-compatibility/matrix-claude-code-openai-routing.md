# Matrix: Claude Code → CC Switch Local Routing → OpenAI Chat Completions

> **Client:** Claude Code 2.1.216
> **CC Switch mode:** Local Routing (Anthropic → OpenAI Chat protocol conversion)
> **Gateway path:** `POST /v1/chat/completions`
> **Upstream protocol:** OpenAI Chat Completions (transparent pass-through)
>
> **Key boundary:** CC Switch owns protocol conversion (Anthropic Messages →
> OpenAI Chat Completions) and model mapping. The Gateway is transparent — it
> forwards the already-converted OpenAI Chat request unchanged. The Gateway
> never performs cross-protocol translation.

## Test Configuration

| Parameter | Value |
|---|---|
| Gateway Base URL | `http://127.0.0.1:8081` |
| Synthetic Key | `sk-miqrokey-g04-test-0000000000000000000000000000000000000000000000` |
| CC Switch Provider Type | Local Routing / Converted Provider (GUI field name ENV_BLOCKED) |
| CC Switch Auth Header | `Authorization: Bearer` (ENV_BLOCKED — verify on instance) |
| CC Switch Target Protocol | OpenAI Chat Completions |
| Upstream | OpenAI-compatible endpoint (real or mock) |
| Gateway Mode | Transparent — does not modify OpenAI Chat protocol |

## Prerequisites

1. [ ] **Terminal 1:** Mock running on `http://127.0.0.1:8082` (`run-mock.ps1` / `run-mock.sh`)
2. [ ] **Terminal 2:** Gateway running on `http://127.0.0.1:8081` (`run-gateway.ps1` / `run-gateway.sh`)
3. [ ] Both health checks passing (see [manual-verification-guide.md](manual-verification-guide.md) section 3.3)
4. [ ] CC Switch Local Routing Provider configured (see [config-field-reference.md](config-field-reference.md) section 4.2)
5. [ ] Claude Code set to use the CC Switch routing provider
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
| 1 | Simple prompt: "Hello, what is 2+2?" | `NOT_TESTED` | Claude Code sends Anthropic Messages to CC Switch. CC Switch converts to OpenAI Chat and sends `POST /v1/chat/completions` to Gateway. Gateway forwards to upstream. Response flows back through CC Switch conversion to Claude Code. | |
| 2 | Multi-turn conversation (3 exchanges) | `NOT_TESTED` | Each turn succeeds. CC Switch handles conversation context conversion between protocols. Gateway treats each turn as independent request. | |
| 3 | System prompt + user message | `NOT_TESTED` | CC Switch converts Anthropic `system` role to OpenAI `messages[].role: "system"`. Gateway forwards converted format unchanged. | |
| 4 | Non-English prompt (Chinese: "你好") | `NOT_TESTED` | UTF-8 preserved through full chain: Claude Code → CC Switch → Gateway → upstream → back. | |

## Streaming

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 5 | Stream: "Write a short poem" | `NOT_TESTED` | Claude Code displays streaming text. Gateway forwards SSE data chunks from upstream. CC Switch converts OpenAI SSE to Anthropic SSE for Claude Code. | |
| 6 | `stream: true` parameter propagation | `NOT_TESTED` | CC Switch sets `stream: true` in the converted OpenAI Chat request. Gateway forwards unchanged. Upstream returns SSE stream. | |

## Tool Use / Function Calling

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 7 | Tool: "List current directory files" | `NOT_TESTED` | CC Switch converts Anthropic tools to OpenAI `tools`/`functions`. Gateway forwards. Upstream returns `tool_calls`. CC Switch converts back to Anthropic `tool_use`. Claude Code executes and returns result. | |
| 8 | Tool result round-trip | `NOT_TESTED` | CC Switch converts Anthropic `tool_result` to OpenAI `tool` role message. Gateway forwards. Full round-trip succeeds. | |

## Reasoning Content

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 9 | Reasoning model: if upstream supports `reasoning_content` | `NOT_TESTED` | If upstream model returns `reasoning_content` in SSE deltas or final message, Gateway forwards unchanged. CC Switch may or may not map this to Anthropic thinking blocks. | |

## Usage

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 10 | Usage in non-streaming response | `NOT_TESTED` | Gateway's SSE observer extracts `usage.prompt_tokens`, `usage.completion_tokens`, `usage.total_tokens` from response. Also extracts `completion_tokens_details.reasoning_tokens` if present. | |
| 11 | Usage in streaming response | `NOT_TESTED` | Gateway's SSE observer extracts usage from the final SSE event or the `[DONE]`-preceding chunk. | |

## Finish Reasons

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 12 | Natural completion (`finish_reason: "stop"`) | `NOT_TESTED` | Claude Code receives complete response. Gateway logs normal completion. | |
| 13 | Token limit hit (`finish_reason: "length"`) | `NOT_TESTED` | Claude Code receives truncated response (or CC Switch signals truncation). Gateway forwards upstream finish_reason unchanged. | |
| 14 | Tool call completion (`finish_reason: "tool_calls"`) | `NOT_TESTED` | Claude Code receives tool call. CC Switch converts `tool_calls` finish reason to Anthropic `tool_use` stop reason. | |

## Error Handling

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 15 | Invalid model: request a model the upstream doesn't support | `NOT_TESTED` | Upstream returns 400/404. Gateway forwards error. CC Switch converts error format for Claude Code. Claude Code shows error. | |
| 16 | Upstream returns 500 | `NOT_TESTED` | Gateway forwards 500 + error body. CC Switch handles upstream error. Claude Code shows error. | |

## Cancellation

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 17 | Cancel mid-stream: Ctrl+C during response | `NOT_TESTED` | Claude Code disconnects from CC Switch. CC Switch should disconnect from Gateway. Gateway propagates cancellation to upstream. | |

## Credential Security

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 18 | Verify synthetic key stripped | `NOT_TESTED` | Gateway strips `Authorization` header. Upstream never sees the synthetic key. | |
| 19 | Verify `X-MiQroKey-*` headers stripped | `NOT_TESTED` | No internal tracking headers reach the upstream. | |

## CC Switch Conversion Boundary

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 20 | CC Switch model mapping: Claude Code requests "claude-sonnet-5-20251001", CC Switch maps to upstream model | `NOT_TESTED` | Gateway receives the mapped model name (e.g. "gpt-4o"), NOT the original Anthropic model. This is correct — CC Switch owns model mapping. | |
| 21 | CC Switch adds `stream_options` or other OpenAI-specific fields | `NOT_TESTED` | Gateway forwards all fields from CC Switch's converted request unchanged. The Gateway does not remove or add OpenAI-specific fields. | |

---

## Windows Tester Steps

1. Open PowerShell. Verify Claude Code is using the CC Switch Local Routing provider:
   ```
   # Check Claude Code settings to confirm provider selection
   ```
2. Run scenarios 1–21 in order.
3. For each scenario:
   a. Enter the prompt in Claude Code (use Anthropic Messages format — Claude Code's native protocol).
   b. Observe Claude Code output.
   c. Check Gateway logs for `POST /v1/chat/completions` requests (not `/v1/messages`).
   d. Verify the `model` field in Gateway logs matches the mapped model, not the original Anthropic model.
4. Record PASS/FAIL/NOT_TESTED with notes.

## Linux Tester Steps

1. Open terminal. Verify Claude Code provider selection.
2. Run scenarios 1–21 in order (same procedure as Windows).
3. Use `curl` to directly test Gateway's `/v1/chat/completions` first (see [manual-verification-guide.md](manual-verification-guide.md) section 6.2) to isolate Gateway issues from CC Switch conversion issues.
4. Record results.

## Troubleshooting CC Switch Conversion

If scenarios fail, isolate the issue:

1. **Test Gateway directly:**
   ```bash
   curl -X POST http://127.0.0.1:8081/v1/chat/completions \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer sk-miqrokey-g04-test-0000000000000000000000000000000000000000000000" \
     -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'
   ```
   If this succeeds, the Gateway is working — the issue is in CC Switch conversion.

2. **Check CC Switch logs:** CC Switch may log conversion errors or upstream
   connection failures.

3. **Verify CC Switch sends to correct path:** Gateway should see
   `POST /v1/chat/completions`. If Gateway sees `POST /v1/messages`, CC
   Switch is NOT performing conversion (may be in direct mode).

4. **Check protocol compatibility:** CC Switch v3.18 documentation indicates
   it supports Anthropic → OpenAI Chat conversion, but the exact behavior
   (which fields are mapped, how tool calls are translated) depends on the
   installed CC Switch version.

## Completion Criteria

- All testable scenarios have a recorded status.
- At least scenarios 1 (simple prompt), 5 (streaming), and 17 (cancellation) pass.
- Scenario 20 confirms CC Switch model mapping is working (Gateway receives mapped model name).
- Scenario 18 confirms Gateway strips credentials before upstream.
- Any FAIL has a clear diagnosis in the Notes column.

---

*Tester name:* _______________
*Date:* _______________
*CC Switch version (from CC Switch about/settings):* _______________
*Gateway commit SHA:* _______________
*Upstream endpoint used:* _______________
