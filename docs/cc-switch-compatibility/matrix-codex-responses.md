# Matrix: Codex → OpenAI Responses

> **Client:** Codex CLI 0.144.6
> **CC Switch involvement:** Optional — Codex may route through CC Switch
> (ENV_BLOCKED) or connect directly to the Gateway
> **Gateway path:** `POST /v1/responses`
> **Upstream protocol:** OpenAI Responses (transparent pass-through)
>
> **Key boundary:** The Gateway is transparent for OpenAI Responses. If Codex
> connects through CC Switch, CC Switch may perform protocol conversion or
> model mapping; the Gateway only sees the final request CC Switch decides
> to send.

## Test Configuration

| Parameter | Value |
|---|---|
| Gateway Base URL | `http://127.0.0.1:8081` |
| Synthetic Key | `sk-miqrokey-g04-test-0000000000000000000000000000000000000000000000` |
| Codex Provider Config | See [config-field-reference.md](config-field-reference.md) section 4.3 |
| Upstream | OpenAI Responses-compatible endpoint (real or mock) |
| Gateway Mode | Transparent — does not modify OpenAI Responses protocol |

## Prerequisites

1. [ ] **Terminal 1:** Mock running on `http://127.0.0.1:8082` (`run-mock.ps1` / `run-mock.sh`)
2. [ ] **Terminal 2:** Gateway running on `http://127.0.0.1:8081` (`run-gateway.ps1` / `run-gateway.sh`)
3. [ ] Both health checks passing (see [manual-verification-guide.md](manual-verification-guide.md) section 3.3)
4. [ ] Codex CLI 0.144.6 installed and working
5. [ ] Codex configured to route through Gateway (either via CC Switch or direct Base URL)
6. [ ] Console/terminal available for observing Codex and Gateway logs

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
| 1 | Simple prompt: "What is the capital of France?" | `NOT_TESTED` | Codex sends `POST /v1/responses` with `input` field. Gateway forwards unchanged. Codex receives text response. | |
| 2 | Multi-turn conversation (3 exchanges) | `NOT_TESTED` | Codex manages conversation state (may use `previous_response_id` or include history in `input`). Gateway forwards each request independently. | |
| 3 | Instructions (system prompt equivalent): "You are a helpful assistant. Answer concisely." | `NOT_TESTED` | Codex sends `instructions` field. Gateway forwards unchanged. Response follows instructions. | |
| 4 | Non-English input: "介绍一下北京" | `NOT_TESTED` | UTF-8 preserved through full chain. Codex receives correct response. | |

## Streaming

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 5 | Stream: "Write a function to reverse a string in Python" | `NOT_TESTED` | Codex receives streaming response. Gateway forwards SSE events from upstream. Each SSE event preserves its original type and data. | |
| 6 | Stream with `max_output_tokens` limit | `NOT_TESTED` | Codex sends `max_output_tokens`. Gateway forwards unchanged. Response respects the limit. | |

## Function Calls / Tool Use

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 7 | Function call: define a function, ask Codex to use it | `NOT_TESTED` | Codex sends `tools` array in Responses format. Upstream returns function call items. Gateway forwards items unchanged. Codex executes and returns output. | |
| 8 | Function call output: Codex sends function result back | `NOT_TESTED` | Codex sends function call output item in `input`. Gateway forwards unchanged. Model uses the output in next response. | |
| 9 | Multiple function calls in single response | `NOT_TESTED` | Upstream returns multiple function call items. Gateway preserves item order and types. Codex handles all calls. | |

## Reasoning / Thinking Items

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 10 | Reasoning items: if model supports reasoning | `NOT_TESTED` | Upstream may include `reasoning` items in the response. Gateway forwards unchanged. Codex may display reasoning (depends on Codex version). | |

## Usage

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 11 | Usage in non-streaming response | `NOT_TESTED` | Gateway's SSE observer extracts `usage.input_tokens`, `usage.output_tokens`, `usage.total_tokens` from response-level or root-level `usage`. | |
| 12 | Usage in streaming response | `NOT_TESTED` | Gateway's SSE observer extracts usage from response completion event or final event with `usage` field. | |

## Error Handling

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 13 | Invalid model name | `NOT_TESTED` | Upstream returns error. Gateway forwards error status and body unchanged. Codex displays error. | |
| 14 | Upstream returns 429 (rate limit) | `NOT_TESTED` | Gateway forwards 429 + `Retry-After` header if present. Codex may retry or show rate limit error. | |
| 15 | Upstream returns 500 | `NOT_TESTED` | Gateway forwards 500 + error body. Codex shows server error. | |

## Cancellation

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 16 | Cancel mid-stream: Ctrl+C during long response | `NOT_TESTED` | Codex disconnects. Gateway propagates cancellation — upstream TCP connection closes. Gateway logs show cancellation. | |

## Credential Security

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 17 | Verify synthetic key stripped from upstream request | `NOT_TESTED` | Gateway strips `Authorization` header. Upstream never sees the synthetic key. | |
| 18 | Verify `X-MiQroKey-*` headers stripped | `NOT_TESTED` | No internal tracking headers reach upstream. | |

## Path Allowlisting

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 19 | `POST /v1/responses` (allowed) | `NOT_TESTED` | Request succeeds (or upstream error, but NOT 404/405). | |
| 20 | `GET /v1/responses/{id}` (unsupported) | `NOT_TESTED` | Gateway returns 404 or 405. This path is not in the allowlist. If Codex tries to retrieve a response by ID, this path is not yet implemented. | |

---

## Windows Tester Steps

1. Open PowerShell.
2. Verify Codex CLI version:
   ```powershell
   codex --version
   ```
   Expected: `0.144.6` (or compatible)

3. Configure Codex to route through the Gateway. Two possible approaches
   (ENV_BLOCKED — exact method depends on Codex version):

   **Approach A — Via CC Switch:**
   Configure a Codex provider in CC Switch pointing to the Gateway.
   CC Switch handles routing.

   **Approach B — Direct Base URL (if Codex supports it):**
   ```powershell
   # Example (exact env var names ENV_BLOCKED):
   $env:OPENAI_BASE_URL = "http://127.0.0.1:8081/v1"
   $env:OPENAI_API_KEY = "sk-miqrokey-g04-test-0000000000000000000000000000000000000000000000"
   ```

4. Run scenarios 1–20 in order.
5. For each scenario:
   a. Enter the prompt in Codex.
   b. Observe Codex output (text, streaming, function calls, errors).
   c. Check Gateway logs for `POST /v1/responses` requests.
   d. Record PASS/FAIL/NOT_TESTED with notes.
6. Verify credential security (scenarios 17–18).

## Linux Tester Steps

1. Open terminal. Verify Codex version.
2. Configure Codex routing (same as Windows, using `export` instead of `$env:`).
3. Run scenarios 1–20 in order (same procedure).
4. Record results.

## Troubleshooting

### Codex sends to wrong path

**Symptom:** Gateway logs show `POST /v1/chat/completions` instead of
`POST /v1/responses`.
**Cause:** Codex may be configured to use Chat Completions protocol instead
of Responses API.
**Fix:** Check Codex configuration to ensure it uses the Responses API
endpoint, or configure the Gateway to accept both paths (both are already
supported).

### Codex sends to /v1/responses but with unexpected fields

**Symptom:** Gateway forwards correctly but upstream rejects the request.
**Cause:** Codex 0.144.6 may send fields that differ from the upstream's
expectations. This is not a Gateway issue — the Gateway is transparent.
**Diagnosis:** Use a mock upstream or inspect the forwarded request to
compare against the OpenAI Responses API specification.

### Gateway returns 404 for /v1/responses/{id}

**Symptom:** Codex tries to retrieve a previous response and gets 404.
**Cause:** The Gateway only supports `POST /v1/responses`. Resource-level
GET endpoints for specific responses are not yet implemented.
**Workaround:** None in G0.4. This endpoint will be considered in G2.6
if needed for CC Switch compatibility.

## Completion Criteria

- All testable scenarios have a recorded status.
- At least scenarios 1 (simple prompt), 5 (streaming), 7 (function call),
  and 16 (cancellation) pass.
- Scenarios 17–18 confirm credential security.
- Any FAIL has a clear diagnosis in the Notes column.
- If any scenario is ENV_BLOCKED, the reason is documented.

---

*Tester name:* _______________
*Date:* _______________
*Codex CLI version:* _______________
*CC Switch version (if used):* _______________
*Gateway commit SHA:* _______________
*Upstream endpoint used:* _______________
