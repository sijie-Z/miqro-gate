# Manual Verification Guide

> **Prerequisite reading:** [version-evidence.md](version-evidence.md) —
> understand which versions are confirmed vs ENV_BLOCKED before starting.

## 1. What This Guide Covers

This guide describes how a human tester configures CC Switch and the MiQroKey
Gateway to validate end-to-end compatibility **before the management portal
is available**. The Gateway exposes three transparent proxy entrypoints; the
tester configures CC Switch to route to them using a synthetic Virtual Key.

All instructions are for Windows (primary) and Linux (secondary). macOS is
not a Phase 0 validation platform.

## 2. Prerequisites

### 2.1 Required Software

| Tool | Minimum Version | Check Command | Required For |
|---|---|---|---|
| Java | 21 | `java -version` | Running the Gateway |
| Claude Code | 2.1.216 | `claude --version` | Anthropic matrix |
| Codex CLI | 0.144.6 | `codex --version` | Responses matrix |
| CC Switch | to be confirmed by tester | *ENV_BLOCKED* | All matrices |
| Claude Desktop | to be confirmed by tester | *ENV_BLOCKED* | Desktop matrix |

### 2.2 Required Access

- MiQroKey Gateway running and reachable at a known URL (e.g. `http://localhost:8081`)
- An upstream endpoint the Gateway can proxy to (real provider or separately
  run mock server)
- The synthetic test key (see section 5)
- CC Switch installed and able to add new Provider configurations

## 3. Starting the Gateway and Mock

The test harness uses two terminals: one for the Compatibility Mock Server
(standalone upstream on port 8082) and one for the Gateway (port 8081 proxying
to the Mock).

**Required start order:** Mock first, then Gateway.

### 3.1 Compatibility Mock Server (Terminal 1)

The mock is a standalone shaded jar in `test-support` with classifier
`compatibility`. Start it on port 8082:

**Windows (PowerShell):**
```powershell
.\scripts\cc-switch-compatibility\run-mock.ps1
```

**Linux (bash):**
```bash
./scripts/cc-switch-compatibility/run-mock.sh
```

**Options:**
- Set `$env:MIQROKEY_SKIP_BUILD = "true"` (PowerShell) or
  `export MIQROKEY_SKIP_BUILD=true` (bash) to skip the Maven build and use
  the existing jar.
- Override the port with `$env:COMPATIBILITY_MOCK_PORT` (default `8082`).

The script prints the health and observations URLs:
- Health: `http://127.0.0.1:8082/health`
- Observations: `http://127.0.0.1:8082/observations` (GET to read, DELETE to clear)

**Check mock health once running:**
```powershell
# Windows PowerShell
Invoke-WebRequest -Uri http://127.0.0.1:8082/health

# Linux
curl http://127.0.0.1:8082/health
```
Expected response: `{"service":"compatibility-mock","status":"UP"}`

**Observation helpers:**
```powershell
# Windows
.\scripts\cc-switch-compatibility\check-observations.ps1
.\scripts\cc-switch-compatibility\clear-observations.ps1

# Linux
./scripts/cc-switch-compatibility/check-observations.sh
./scripts/cc-switch-compatibility/clear-observations.sh
```

### 3.2 MiQroKey Gateway (Terminal 2)

**Pre-requisite:** The Mock must be running and healthy on port 8082.

The Gateway script pre-configures `MIQROKEY_UPSTREAM_URL=http://127.0.0.1:8082`
to point at the local Mock. No real credentials are used or required.

**Windows (PowerShell):**
```powershell
.\scripts\cc-switch-compatibility\run-gateway.ps1
```

**Linux (bash):**
```bash
./scripts/cc-switch-compatibility/run-gateway.sh
```

**Options:**
- Set `$env:MIQROKEY_SKIP_BUILD = "true"` (PowerShell) or
  `export MIQROKEY_SKIP_BUILD=true` (bash) to skip the Maven build.

The script prints:
- Gateway health URL: `http://127.0.0.1:8081/actuator/health`
- Supported paths: `POST /v1/messages`, `POST /v1/chat/completions`, `POST /v1/responses`
- The Base URL to show to CC Switch: `http://127.0.0.1:8081`

**Alternative — Point the Gateway at a real upstream endpoint:**
The Gateway is a transparent proxy. Any HTTP endpoint that speaks the
supported protocols can serve as the upstream. To use a real upstream:

**Windows (PowerShell):**
```powershell
$env:MIQROKEY_UPSTREAM_URL = "https://api.anthropic.com"
$env:MIQROKEY_UPSTREAM_CONNECT_TIMEOUT = "PT5S"
$env:MIQROKEY_UPSTREAM_RESPONSE_TIMEOUT = "PT10M"
.\mvnw.cmd -f backend spring-boot:run -pl gateway-app
```

**Linux (bash):**
```bash
export MIQROKEY_UPSTREAM_URL="https://api.anthropic.com"
export MIQROKEY_UPSTREAM_CONNECT_TIMEOUT="PT5S"
export MIQROKEY_UPSTREAM_RESPONSE_TIMEOUT="PT10M"
./mvnw -f backend spring-boot:run -pl gateway-app
```

### 3.3 Health Check

Once both the Mock and Gateway are running, verify they respond:

```powershell
# Windows PowerShell
Invoke-WebRequest -Uri http://127.0.0.1:8082/health    # Mock — {"service":"compatibility-mock","status":"UP"}
Invoke-WebRequest -Uri http://127.0.0.1:8081/actuator/health  # Gateway — {"status":"UP"}

# Linux
curl http://127.0.0.1:8082/health
curl http://127.0.0.1:8081/actuator/health
```

**Configuration reference:**

| Variable | Default | Description |
|---|---|---|
| `MIQROKEY_GATEWAY_PORT` | `8081` | Port the Gateway listens on |
| `MIQROKEY_UPSTREAM_URL` | (empty) | Upstream URL to proxy to; Gateway returns 503 if not set |
| `MIQROKEY_UPSTREAM_CONNECT_TIMEOUT` | `PT5S` | TCP connect timeout |
| `MIQROKEY_UPSTREAM_RESPONSE_TIMEOUT` | `PT10M` | Total response timeout |
| `MIQROKEY_MAX_PROXY_BUFFER_BYTES` | `256KB` | Max buffered data for SSE observation |
| `COMPATIBILITY_MOCK_PORT` | `8082` | Port the Compatibility Mock Server binds to |

## 4. Configuring CC Switch

> **GUI-only fields are ENV_BLOCKED** — the table below shows field names
> documented in CC Switch v3.18 user manual. The actual field names and layout
> may differ on the version installed by the tester. The tester MUST verify
> field names on the actual instance.

### 4.1 Anthropic Provider (for Claude Code)

Create a new Provider in CC Switch:

| Field | Value | Notes |
|---|---|---|
| Provider Type | Anthropic (or Custom/Generic for Anthropic) | CC Switch field name ENV_BLOCKED |
| Name | `MiQroKey Gateway (Anthropic)` | Descriptive name for identification |
| Base URL | `http://localhost:8081` | The Gateway's public entry point; trailing slash behavior may vary by CC Switch version |
| API Key | `sk-miqrokey-…REDACTED000000` | Synthetic test key for G0.4 PoC |
| API Key Header | depends on CC Switch version | May be `x-api-key` or `Authorization: Bearer`; test both |
| Models | depends on CC Switch version | List the models you intend to test (the Gateway's `/v1/models` is not yet implemented — coming in G2.3) |

CC Switch's expected behavior for an Anthropic Provider:
- Sends `POST /v1/messages` to the configured Base URL
- Expects `x-api-key` header or `Authorization: Bearer` with the API Key
- May send `anthropic-version`, `anthropic-beta` headers
- May send `POST /v1/messages/count_tokens` (not yet supported by Gateway — will return 404)

### 4.2 Local Routing / Converted Provider (for OpenAI Chat)

Create a Provider that CC Switch uses for Local Routing (protocol conversion):

| Field | Value | Notes |
|---|---|---|
| Provider Type | depends on CC Switch version | The type that triggers CC Switch to convert Anthropic → OpenAI Chat before forwarding |
| Base URL | `http://localhost:8081` | Same Gateway entry point |
| API Key | `sk-miqrokey-…REDACTED000000` | Same synthetic test key |
| API Key Header | `Authorization: Bearer` | OpenAI convention |
| Protocol | OpenAI Chat Completions | CC Switch converts then sends to `POST /v1/chat/completions` |

CC Switch's expected behavior for Local Routing:
- Accepts Anthropic Messages from the client (Claude Code)
- Converts to OpenAI Chat Completions format
- Sends the converted request to `POST /v1/chat/completions` on the Gateway
- Converts the OpenAI Chat response back to Anthropic Messages for the client

### 4.3 Codex Provider (for OpenAI Responses)

If CC Switch supports Codex provider configuration:

| Field | Value | Notes |
|---|---|---|
| Provider Type | depends on CC Switch version | Codex-specific or generic OpenAI Responses |
| Base URL | `http://localhost:8081` | Same Gateway entry point |
| API Key | `sk-miqrokey-…REDACTED000000` | Same synthetic test key |
| API Key Header | `Authorization: Bearer` | OpenAI convention |
| Protocol | OpenAI Responses | Codex sends to `POST /v1/responses` |

**Alternative (no CC Switch involvement):** Codex CLI may allow direct Base URL
configuration. In that case, configure Codex to point directly at the Gateway:

| Field | Value |
|---|---|
| Base URL / API Endpoint | `http://localhost:8081/v1` |
| API Key | `sk-miqrokey-…REDACTED000000` |

### 4.4 Claude Desktop Provider

If a tester has access to Claude Desktop on Windows/macOS:

| Field | Value | Notes |
|---|---|---|
| Provider Type | depends on CC Switch version | The Claude Desktop provider type in CC Switch |
| Base URL | `http://localhost:8081` | Same Gateway entry point |
| API Key | `sk-miqrokey-…REDACTED000000` | Same synthetic test key |
| Model Mapping | depends on CC Switch version | Map Claude model roles to actual provider models |

**MANUAL_REQUIRED:** Claude Desktop 1.24012.1 is installed (version CONFIRMED)
but its configuration was deliberately not touched in this Goal. All Claude
Desktop instructions are for human testers who will configure CC Switch +
Claude Desktop integration at the GUI.

## 5. Synthetic Test Key

During G0.4 PoC, the Gateway does **not** validate Virtual Keys. It accepts
any key and strips all credential headers before forwarding to the upstream.
Use this key for all CC Switch Provider configurations:

```
sk-miqrokey-…REDACTED000000
```

This key:
- Is purely synthetic — it has no access to any real provider
- Is stripped by the Gateway from all outbound requests
- Will be replaced by real Virtual Key validation in G1.5

## 6. Verifying Gateway-Only (No CC Switch)

Before involving CC Switch, verify the Gateway is proxying correctly.
For all tests below, the Gateway proxies to the Mock at port 8082, so
upstream responses are deterministic synthetic protocol-correct JSON/SSE.

After each test, verify the Mock recorded the request:

```powershell
# Windows
.\scripts\cc-switch-compatibility\check-observations.ps1

# Linux
./scripts/cc-switch-compatibility/check-observations.sh
```

### 6.1 Anthropic Messages

**Windows (PowerShell):**
```powershell
$body = @'
{"model":"claude-sonnet-5-20251001","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}
'@
$response = Invoke-WebRequest -Uri http://127.0.0.1:8081/v1/messages `
  -Method POST `
  -ContentType "application/json" `
  -Headers @{ "x-api-key" = "sk-miqrokey-…REDACTED000000" } `
  -Body $body
$response.StatusCode
```

**Linux (bash):**
```bash
curl -s -w "\nHTTP %{http_code}\n" \
  -X POST http://127.0.0.1:8081/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: sk-miqrokey-…REDACTED000000" \
  -d '{"model":"claude-sonnet-5-20251001","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}'
```

Expected: HTTP 200 with Anthropic protocol JSON. The Mock's `/observations`
shows a recorded request with `"protocol":"ANTHROPIC_MESSAGES"` and
`"forbiddenCredentialHeaderReached":true` (x-api-key detected).

### 6.2 OpenAI Chat Completions

```bash
curl -s -w "\nHTTP %{http_code}\n" \
  -X POST http://127.0.0.1:8081/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-miqrokey-…REDACTED000000" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'
```

Expected: HTTP 200 with OpenAI Chat JSON. Mock observations show
`"protocol":"OPENAI_CHAT_COMPLETIONS"`.

### 6.3 OpenAI Responses

```bash
curl -s -w "\nHTTP %{http_code}\n" \
  -X POST http://127.0.0.1:8081/v1/responses \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-miqrokey-…REDACTED000000" \
  -d '{"model":"gpt-4o","input":"hi","max_output_tokens":1}'
```

Expected: HTTP 200 with OpenAI Responses JSON. Mock observations show
`"protocol":"OPENAI_RESPONSES"`.

### 6.4 Unsupported Path

```bash
curl -s -w "\nHTTP %{http_code}\n" \
  -X POST http://127.0.0.1:8081/v1/unknown \
  -H "Content-Type: application/json" \
  -d '{}'
```
Expected: HTTP 404 with a JSON error body. The Gateway does NOT contact
the Mock for unsupported paths. Mock observations show no new entry for
this request (the Gateway rejects it before forwarding).

## 7. Verifying with CC Switch

### 7.1 Pre-flight Checks

Before running a full matrix scenario:

1. Start the Gateway (section 3).
2. Verify Gateway health check (section 3.3).
3. Verify Gateway-only curl requests succeed (section 6).
4. Configure CC Switch Provider(s) (section 4).
5. Confirm CC Switch shows the Provider as connected/available (GUI behavior
   ENV_BLOCKED — depends on CC Switch version).

### 7.2 What the Tester Should Observe

For each matrix scenario, the tester should:

1. **Does the client send a request?** — Check Gateway logs (enable DEBUG for
   `com.miqroera.miqrokey`). A request ID should appear.
2. **Does the Gateway forward to upstream?** — Check upstream logs or Gateway
   DEBUG logs showing the upstream response status.
3. **Does the client receive a response?** — The client should show model output
   or an error (not hang).
4. **Are credentials stripped?** — The Gateway logs should NEVER show the real
   upstream credentials (they aren't configured). The synthetic test key should
   never appear in upstream request logs.

## 8. Failure Diagnostics

### 8.1 Gateway returns 503

**Cause:** `MIQROKEY_UPSTREAM_URL` is not set or empty.
**Fix:** Set the environment variable and restart.

### 8.2 Gateway returns 404

**Cause:** Request path is not one of `/v1/messages`, `/v1/responses`,
`/v1/chat/completions`.
**Diagnosis:** CC Switch may be sending a different path (e.g.
`/v1/messages/count_tokens`). Check the CC Switch provider configuration.
**Fix:** Verify Base URL does not include a trailing path. The Gateway's
path allowlisting only matches the three exact paths above.

### 8.3 Gateway returns 405

**Cause:** GET or other non-POST method on an allowed path.
**Diagnosis:** CC Switch may be probing with GET `/v1/models`. The `/v1/models`
endpoint is not yet implemented (G2.3).

### 8.4 Upstream connection timeout

**Symptom:** Response takes >5 seconds, then returns an error.
**Cause:** Gateway cannot reach the configured `MIQROKEY_UPSTREAM_URL`.
**Diagnosis:** Verify upstream is running and reachable from the Gateway host.

### 8.5 Client hangs with no response

**Cause:** Upstream is not sending a response (or is sending a response the
client can't parse).
**Diagnosis:**
- Check Gateway DEBUG logs for the upstream response status.
- If the upstream returned data, the issue is in CC Switch's parsing of the
  response.
- CC Switch does protocol conversion; if the upstream response format doesn't
  match CC Switch's expectations, the client may hang or error.

### 8.6 CC Switch reports "invalid API key"

**Cause:** CC Switch may be validating the key format before sending.
**Diagnosis:** The synthetic key `sk-miqrokey-g04-test-*` may not match the
format CC Switch expects for its provider type.
**Fix:** Try a key that matches the expected format (e.g. `sk-ant-` for
Anthropic providers, `sk-` for OpenAI providers) but remember the Gateway
does not validate keys during G0.4.

## 9. Cleanup / Restore

After testing:

1. **Stop the Gateway (Terminal 2):** Press `Ctrl+C` in the terminal running
   `run-gateway.ps1` / `run-gateway.sh`. The Gateway shuts down and releases
   port 8081.

2. **Stop the Mock (Terminal 1):** Press `Ctrl+C` in the terminal running
   `run-mock.ps1` / `run-mock.sh`. The Mock shuts down and releases port 8082.
   The in-memory observation store is discarded.

3. **Remove CC Switch Provider:** Delete the test provider from CC Switch
   settings to avoid accidentally routing real traffic through the test
   Gateway.

4. **Restore default client configuration:** If CC Switch was set as the
   default provider for Claude Code or Codex, switch back to the original
   provider.

5. **Verify restoration:**
   ```powershell
   # Windows
   claude --version  # Should still be 2.1.216
   # Run a simple Claude Code prompt to confirm it uses the original provider
   ```

## 10. What G0.4 Cannot Verify

The following capabilities are out of scope for G0.4 manual testing and will
be verified in later Goals:

| Capability | Target Goal |
|---|---|
| Virtual Key validation (the synthetic key is accepted unconditionally) | G1.5 |
| Real upstream credential injection (Gateway strips but does not inject) | G1.5 |
| `/v1/models` endpoint (model discovery) | G2.3 |
| Route snapshot from database (fixed URL in G0.4) | G2.2 |
| Usage persistence (SSE observer captures but does not persist) | G2.4 |
| `/v1/messages/count_tokens` (not yet implemented) | G2.6 |
