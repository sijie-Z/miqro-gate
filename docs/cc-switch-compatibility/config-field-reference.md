# CC Switch Configuration Field Reference

> **MANUAL_REQUIRED disclaimer:** CC Switch 3.18.0 is installed and its version
> is CONFIRMED (see [version-evidence.md](version-evidence.md)). However, CC
> Switch configuration was deliberately not touched in this Goal — actual UI
> field names, layout, dropdown values, and validation rules require a human
> tester to open the CC Switch GUI and document. Field names below are derived
> from the CC Switch v3.18 user manual documentation. The tester MUST verify
> field names on the actual CC Switch instance and update this document.

## 1. MiQroKey Gateway Base URL Shapes

The Gateway exposes a single public entry point. All three protocols share
the same Base URL; the Gateway routes by request path, not by URL prefix.

### 1.1 Single Entry Point (recommended for CC Switch)

```
http://<gateway-host>:8081
https://<gateway-host>            (production, with TLS termination)
```

CC Switch sends requests to this Base URL. The Gateway internally appends
the request path to the configured upstream URL.

### 1.2 Path-Specific URLs (if CC Switch requires full URL)

Some CC Switch Provider types may require a full URL rather than a
Base URL + path convention. In that case, use:

| Protocol | Full URL |
|---|---|
| Anthropic Messages | `http://<gateway-host>:8081/v1/messages` |
| OpenAI Chat Completions | `http://<gateway-host>:8081/v1/chat/completions` |
| OpenAI Responses | `http://<gateway-host>:8081/v1/responses` |

**Warning:** If CC Switch appends its own path suffix to the configured URL,
using the full URL would result in a double-path (e.g.
`/v1/messages/v1/messages`), which returns 404. Use the single entry point
unless you confirm CC Switch does not append paths.

### 1.3 Trailing Slash Behavior

The Gateway strips a trailing slash from the configured upstream URL before
appending the request path:

```
http://127.0.0.1:8081/     → appends /v1/messages → http://<upstream>/v1/messages
http://127.0.0.1:8081      → appends /v1/messages → http://<upstream>/v1/messages
```

Both forms are equivalent at the Gateway. CC Switch's handling of trailing
slashes on its Provider Base URL field is ENV_BLOCKED — test both.

## 2. Expected POST Paths

The Gateway accepts exactly three paths:

| Path | Protocol | Method | Auth Headers Accepted |
|---|---|---|---|
| `/v1/messages` | Anthropic Messages | POST | `x-api-key`, `Authorization: Bearer`, `api-key` |
| `/v1/responses` | OpenAI Responses | POST | `Authorization: Bearer`, `api-key` |
| `/v1/chat/completions` | OpenAI Chat Completions | POST | `Authorization: Bearer`, `api-key` |

**Not yet implemented (will return 404 or 405):**
- `GET /v1/models` — G2.3
- `POST /v1/messages/count_tokens` — G2.6
- Any other `/v1/**` path — 404

## 3. Credential Header Formats

The Gateway accepts Virtual Keys in three header positions. CC Switch may
use different headers depending on provider type:

| CC Switch Provider Type | Typical Header | Key Format Expected by CC Switch |
|---|---|---|
| Anthropic (direct) | `x-api-key: <key>` | CC Switch may expect `sk-ant-` prefix or arbitrary format |
| Anthropic (direct) | `api-key: <key>` | Alternative; depends on CC Switch version |
| OpenAI-compatible | `Authorization: Bearer <key>` | CC Switch may expect `sk-` prefix or arbitrary format |

**Multiple credentials:** If a request contains multiple credential headers
with different values, the Gateway rejects it. This is a security measure;
the tester should ensure CC Switch sends only one credential header.

**Credential stripping:** The Gateway removes ALL credential headers from
requests before forwarding to the upstream. The synthetic test key is never
sent to the configured upstream.

## 4. CC Switch Provider Configuration Fields

### 4.1 Common Fields (all provider types)

These fields appear in most CC Switch Provider configuration screens.
Exact names are ENV_BLOCKED.

| Probable Field Name | Description | MiQroKey Value |
|---|---|---|
| Provider Name / Name | Display name in CC Switch | `MiQroKey Gateway` or protocol-specific name |
| Provider Type / Type | Dropdown of supported provider types | See section 4.2 |
| Base URL / API Endpoint / URL | Where CC Switch sends requests | `http://127.0.0.1:8081` (or production URL) |
| API Key / Key / Token | The credential CC Switch sends | `sk-miqrokey-…REDACTED000000` |
| API Key Header / Auth Type | How CC Switch sends the key | Depends on provider type — see section 4.2 |
| Models / Model List | Models to expose to the client | Enter models your upstream supports |

### 4.2 Provider-Type-Specific Fields

> All GUI-specific fields (dropdown values, radio buttons, toggles) are
> ENV_BLOCKED. The tester must verify on the actual CC Switch instance.

#### Anthropic Provider

| Probable Field | Expected Setting | Notes |
|---|---|---|
| Base URL | `http://127.0.0.1:8081` | CC Switch appends `/v1/messages` |
| Auth Header | `x-api-key` | CC Switch uses Anthropic convention |
| Anthropic Version | depends on CC Switch | CC Switch may auto-set `2023-06-01` |
| Beta Headers | depends on CC Switch | CC Switch may send `anthropic-beta` headers for specific features |

CC Switch sends to Gateway:
- `POST /v1/messages` with Anthropic Messages JSON body
- Headers: `x-api-key`, `anthropic-version`, optionally `anthropic-beta`

#### Local Routing Provider (Anthropic → OpenAI Chat)

| Probable Field | Expected Setting | Notes |
|---|---|---|
| Base URL | `http://127.0.0.1:8081` | CC Switch appends `/v1/chat/completions` |
| Auth Header | `Authorization: Bearer` | CC Switch uses OpenAI convention |
| Routing Mode | depends on CC Switch | The setting that triggers protocol conversion |

CC Switch sends to Gateway (after converting Anthropic → OpenAI):
- `POST /v1/chat/completions` with OpenAI Chat JSON body
- Headers: `Authorization: Bearer <key>`

#### Codex / OpenAI Responses Provider

| Probable Field | Expected Setting | Notes |
|---|---|---|
| Base URL | `http://127.0.0.1:8081` | CC Switch appends `/v1/responses` |
| Auth Header | `Authorization: Bearer` | OpenAI convention |
| Responses API Version | depends on CC Switch | CC Switch may set response format options |

CC Switch sends to Gateway:
- `POST /v1/responses` with OpenAI Responses JSON body
- Headers: `Authorization: Bearer <key>`

#### Claude Desktop Provider

| Probable Field | Expected Setting | Notes |
|---|---|---|
| Base URL | `http://127.0.0.1:8081` | Same entry point |
| Auth Header | depends on CC Switch | Anthropic convention for direct, OpenAI for mapping |
| Model Mapping | depends on CC Switch | Maps Claude model names to provider model names |
| Direct / Mapping mode | depends on CC Switch | Controls whether CC Switch converts protocols |

## 5. Model Mapping (CC Switch Responsibility)

CC Switch owns model mapping — the Gateway never translates model names.
When CC Switch performs Local Routing (protocol conversion), it also maps
model IDs:

```
Claude Code requests "claude-sonnet-5-20251001"
  → CC Switch maps to "gpt-4o" (or whatever the upstream model is)
  → Gateway receives {"model": "gpt-4o", ...}
  → Gateway forwards to upstream with model "gpt-4o"
```

The Gateway's model validation (G2.3) will verify the model against the
Virtual Key's authorized model list. During G0.4 PoC, no model validation
is performed.

## 6. Header Propagation

### 6.1 Headers the Gateway Strips (always removed)

| Header | Reason |
|---|---|
| `authorization` | Virtual Key credential — must not reach upstream |
| `x-api-key` | Virtual Key credential — must not reach upstream |
| `api-key` | Virtual Key credential — must not reach upstream |
| `connection` | Hop-by-hop — per RFC 7540 |
| `keep-alive` | Hop-by-hop — per RFC 7540 |
| `transfer-encoding` | Hop-by-hop — per RFC 7540 |
| `te` | Hop-by-hop — per RFC 7540 |
| `trailer` | Hop-by-hop — per RFC 7540 |
| `upgrade` | Hop-by-hop — per RFC 7540 |
| `proxy-authenticate` | Hop-by-hop — per RFC 7540 |
| `proxy-authorization` | Hop-by-hop — per RFC 7540 |
| `host` | Reconstructed by Gateway |
| `content-length` | Reconstructed by WebClient |
| `x-miqrokey-*` | Internal tracking headers — must not be spoofed by client |

### 6.2 Headers the Gateway Preserves (forwarded unchanged)

All headers not listed above are forwarded unchanged, including:
- `anthropic-version`, `anthropic-beta` (Anthropic protocol)
- `content-type`, `accept`, `accept-encoding` (content negotiation)
- `cache-control` (prompt caching)
- `x-request-id`, `x-stainless-*` (vendor tracing)
- Custom headers from CC Switch or the client

## 7. Quick Configuration Cheat Sheet

### For tester: "I want to verify Claude Code → Gateway → Anthropic upstream"

```
CC Switch Provider:
  Type: Anthropic
  Base URL: http://127.0.0.1:8081
  API Key: sk-miqrokey-…REDACTED000000
  Auth: x-api-key (if CC Switch asks)

Claude Code:
  Select the MiQroKey provider in Claude Code settings

Expected flow:
  Claude Code → POST /v1/messages + x-api-key → Gateway
  → Gateway strips x-api-key → upstream
  → upstream response → Gateway → Claude Code
```

### For tester: "I want to verify Claude Code → CC Switch routing → Gateway → upstream"

```
CC Switch Local Routing:
  Type: routing/conversion provider type (ENV_BLOCKED)
  Base URL: http://127.0.0.1:8081
  API Key: sk-miqrokey-…REDACTED000000
  Auth: Authorization: Bearer

Claude Code:
  Select the routing provider in Claude Code settings

Expected flow:
  Claude Code → Anthropic Messages (to CC Switch)
  → CC Switch converts to OpenAI Chat
  → POST /v1/chat/completions + Bearer → Gateway
  → Gateway strips Bearer → upstream
  → upstream response → Gateway → CC Switch
  → CC Switch converts back to Anthropic Messages → Claude Code
```
