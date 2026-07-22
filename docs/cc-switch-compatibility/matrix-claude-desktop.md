# Matrix: Claude Desktop → Direct / Model Mapping

> **Client:** Claude Desktop (version ENV_BLOCKED)
> **CC Switch mode:** Claude Desktop Provider — Direct or Model Mapping
> **Gateway paths:** `POST /v1/messages` (direct) or `POST /v1/chat/completions` (mapping)
> **Upstream protocol:** Depends on CC Switch mode (see below)
>
> **MANUAL_REQUIRED:** Claude Desktop 1.24012.1 is installed (version
> CONFIRMED) but its configuration was deliberately not touched in this
> Goal. All scenarios in this matrix require a human tester with:
> - Claude Desktop running (Windows or macOS; Linux not supported for
>   Claude Desktop third-party providers in Phase 0)
> - CC Switch configured for Claude Desktop integration (MANUAL_REQUIRED)
> - Administrative access to edit Claude Desktop configuration (on macOS,
>   the config file location may require elevated privileges)

## Test Configuration

| Parameter | Value |
|---|---|
| Gateway Base URL | `http://127.0.0.1:8081` |
| Synthetic Key | `sk-miqrokey-g04-test-0000000000000000000000000000000000000000000000` |
| CC Switch Mode | Claude Desktop integration (GUI field name ENV_BLOCKED) |
| CC Switch Provider Type | Depends on mode — see below |
| Upstream | Real Anthropic API or compatible endpoint |
| Gateway Mode | Transparent |

## CC Switch Claude Desktop Modes

CC Switch v3.18 documentation describes two Claude Desktop integration
modes. Both are ENV_BLOCKED on the development host.

### Direct Mode

CC Switch acts as an Anthropic-compatible provider for Claude Desktop.
Claude Desktop sends Anthropic Messages directly; CC Switch forwards to
the configured provider without protocol conversion.

| Aspect | Behavior |
|---|---|
| Protocol | Anthropic Messages (transparent through CC Switch) |
| Gateway path | `POST /v1/messages` |
| Model mapping | Optional — Claude Desktop model names pass through |
| Auth header | `x-api-key` (ENV_BLOCKED) |

### Model Mapping Mode

CC Switch maps Claude Desktop's model role (Sonnet/Opus/Haiku) to a
configured provider model, potentially with protocol conversion.

| Aspect | Behavior |
|---|---|
| Protocol | May involve conversion (Anthropic → OpenAI or other) |
| Gateway path | `POST /v1/chat/completions` (if converted to OpenAI Chat) |
| Model mapping | CC Switch maps Claude roles to provider models |
| Auth header | `Authorization: Bearer` (ENV_BLOCKED) |

## Prerequisites

1. [ ] **Terminal 1:** Mock running on `http://127.0.0.1:8082` (`run-mock.ps1` / `run-mock.sh`)
2. [ ] **Terminal 2:** Gateway running on `http://127.0.0.1:8081` (`run-gateway.ps1` / `run-gateway.sh`)
3. [ ] Both health checks passing (see [manual-verification-guide.md](manual-verification-guide.md) section 3.3)
4. [ ] Claude Desktop installed and running on Windows or macOS
5. [ ] CC Switch configured for Claude Desktop integration
6. [ ] Claude Desktop restarted after CC Switch configuration change
7. [ ] Tester can observe Claude Desktop UI behavior

## Status Key

| Status | Meaning |
|---|---|
| `NOT_TESTED` | Scenario not yet attempted |
| `PASS` | Expected behavior observed |
| `FAIL` | Unexpected behavior; details in Notes column |
| `ENV_BLOCKED` | Cannot test — missing tool, access, or configuration |
| `PENDING_IMPLEMENTATION` | Gateway feature not yet built |

---

## Configuration Verification

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 1 | Claude Desktop starts and shows provider as available | `MANUAL_REQUIRED` | Claude Desktop UI shows the configured provider in settings or model selector. No "invalid API key" or "connection failed" warning. | MANUAL_REQUIRED: Claude Desktop 1.24012.1 is installed but configuration was not touched; a human tester must configure and verify. |
| 2 | `/v1/models` discovery (Direct mode) | `PENDING_IMPLEMENTATION` | If CC Switch probes `GET /v1/models`, Gateway returns 404 (endpoint not yet built — G2.3). CC Switch may fall back to manually configured model list. | Tester: check if Claude Desktop shows models or an error. |
| 3 | Restart persistence: Claude Desktop remembers provider after restart | `ENV_BLOCKED` | After quitting and restarting Claude Desktop, the CC Switch provider is still configured and working. | |

## Basic Messaging (Direct Mode)

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 4 | Simple chat: "Hello, what can you help me with?" | `ENV_BLOCKED` | Claude Desktop sends `POST /v1/messages` (via CC Switch). Gateway forwards to upstream. Claude Desktop displays response. | |
| 5 | Multi-turn chat (5 exchanges) | `ENV_BLOCKED` | Each exchange succeeds. Conversation context preserved. Gateway treats each turn independently. | |
| 6 | Long message: paste multi-paragraph text | `ENV_BLOCKED` | Claude Desktop sends large body. Gateway forwards without truncation. Response displayed correctly. | |
| 7 | Special characters: emoji, math symbols, code blocks | `ENV_BLOCKED` | UTF-8 preserved. Code blocks rendered correctly in Claude Desktop. | |

## Streaming (Direct Mode)

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 8 | Stream: "Write a haiku about programming" | `ENV_BLOCKED` | Claude Desktop displays streaming text (words appear incrementally). Gateway forwards SSE events. | |

## Tool Use (Direct Mode)

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 9 | File reading tool: if Claude Desktop has file access enabled | `ENV_BLOCKED` | Gateway forwards tool definitions and results unchanged. Claude Desktop executes tools and returns results. | |
| 10 | Web search or other built-in tool | `ENV_BLOCKED` | Tool flow works through full chain. Gateway preserves tool_use/tool_result messages. | |

## Model Mapping Mode

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 11 | Model mapping: Claude Desktop shows mapped models | `ENV_BLOCKED` | If CC Switch is in Model Mapping mode, Claude Desktop may show provider model names (e.g. "gpt-4o") instead of Claude names. CC Switch documentation describes this as expected behavior. | |
| 12 | Chat via Model Mapping mode | `ENV_BLOCKED` | If CC Switch converts protocol, Gateway sees `POST /v1/chat/completions`. Response flows back through CC Switch reverse-conversion. | |

## Error Handling

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 13 | Gateway is stopped mid-chat | `ENV_BLOCKED` | Claude Desktop shows connection error. Does not crash or hang indefinitely. After Gateway restarts, new chats should work. | |
| 14 | Upstream returns error (e.g. rate limit) | `ENV_BLOCKED` | Claude Desktop displays error message. CC Switch may add context to the error. Gateway forwards upstream error unchanged. | |

## Credential Security

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 15 | Verify synthetic key stripped | `ENV_BLOCKED` | Gateway strips credential headers. Upstream never sees the synthetic key. | |

## Claude Desktop-Specific Behaviors

| # | Scenario | Status | Expected Observation | Actual / Notes |
|---|---|---|---|---|
| 16 | Claude Desktop project context (if applicable) | `ENV_BLOCKED` | Claude Desktop project files and instructions are included in the request. Gateway forwards project context unchanged. | |
| 17 | Attachment / image upload (if applicable) | `ENV_BLOCKED` | Claude Desktop sends image data. Gateway forwards binary/image content unchanged. Upstream processes image and returns response. | |
| 18 | Artifact / code rendering (if applicable) | `ENV_BLOCKED` | Claude Desktop renders code blocks and artifacts. Rendered content is not generated by Gateway — it's upstream response content. | |

---

## macOS Tester Steps

> Claude Desktop third-party providers are supported on macOS. These steps
> are for testers with macOS access.

1. Verify Claude Desktop is installed and running (check version in
   Claude Desktop → Settings/About).

2. Configure CC Switch Claude Desktop integration:
   - Follow CC Switch v3.18 [Claude Desktop guide](https://github.com/farion1231/cc-switch/blob/main/docs/user-manual/zh/2-providers/2.6-claude-desktop.md)
   - Set Base URL to `http://127.0.0.1:8081`
   - Set API Key to the synthetic test key
   - Choose Direct or Model Mapping mode

3. **Restart Claude Desktop** — CC Switch configuration changes typically
   require a full quit and restart of Claude Desktop.

4. Verify Claude Desktop shows the provider and models.

5. Run scenarios 4–18 (scenarios 1–3 are configuration verification).

6. For each scenario:
   a. Start a new chat or use existing chat.
   b. Enter the prompt.
   c. Observe Claude Desktop behavior (text, streaming, tools, UI).
   d. Check Gateway logs for requests from Claude Desktop (via CC Switch).
   e. Record PASS/FAIL/ENV_BLOCKED with notes.

7. Test error scenarios (13–14) last, as they may require Gateway restart.

## Windows Tester Steps

1. Same as macOS steps above.
2. Claude Desktop third-party providers are supported on Windows.
3. Configuration steps follow CC Switch documentation.
4. Restart Claude Desktop after CC Switch configuration changes.

## Troubleshooting

### Claude Desktop shows "no models available" or "configuration error"

**Cause:** CC Switch may be probing `GET /v1/models`, which the Gateway
does not yet support (G2.3).
**Diagnosis:** Check Gateway logs. If there's a `GET /v1/models` request
returning 404, this is expected.
**Workaround:** Some CC Switch versions allow manually entering model names.
Enter the models your upstream supports.

### Claude Desktop hangs on startup or shows spinner indefinitely

**Cause:** CC Switch may be waiting for a response from the Gateway.
**Diagnosis:** Check Gateway logs. If no request arrives, the issue is
between Claude Desktop and CC Switch. If a request arrives but upstream
doesn't respond, check upstream connectivity.

### Claude Desktop says "invalid API key"

**Cause:** CC Switch may validate the key format before forwarding. The
synthetic key `sk-miqrokey-g04-test-*` may not match expected format.
**Workaround:** Use a key that matches the expected format (e.g.
`sk-ant-api03-...` for Anthropic providers) but remember the Gateway
does not validate keys during G0.4.

### Claude Desktop configuration file location

Claude Desktop configuration for third-party providers is typically at:
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
  or a CC Switch-managed location
- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json` or CC
  Switch-managed location

Exact paths depend on CC Switch version and Claude Desktop version.
Consult CC Switch documentation.

## Completion Criteria

- All configuration verification scenarios (1–3) completed — even if result
  is ENV_BLOCKED, the reason is documented.
- Basic messaging and streaming scenarios pass if testable.
- Tool use scenarios pass if Claude Desktop has tool access enabled.
- Any ENV_BLOCKED has a clear reason documented (missing tool, no access).
- Any FAIL has a clear diagnosis.

---

*Tester name:* _______________
*Date:* _______________
*Claude Desktop version:* _______________
*OS (Windows/macOS):* _______________
*CC Switch version:* _______________
*CC Switch mode (Direct / Model Mapping):* _______________
*Gateway commit SHA:* _______________
*Upstream endpoint used:* _______________
