# CC Switch Manual Compatibility Package

This directory documents the MiQroKey Gateway's compatibility with
[CC Switch](https://github.com/farion1231/cc-switch) and provides
everything a tester needs to manually validate the end-to-end path
before the management portal is available.

## Quick Start for Testers

1. **Terminal 1 — Start the Mock:** `./scripts/cc-switch-compatibility/run-mock.ps1` (Windows) or `./scripts/cc-switch-compatibility/run-mock.sh` (Linux). The Mock listens on `http://127.0.0.1:8082`.
2. **Terminal 2 — Start the Gateway:** `./scripts/cc-switch-compatibility/run-gateway.ps1` (Windows) or `./scripts/cc-switch-compatibility/run-gateway.sh` (Linux). The Gateway listens on `http://127.0.0.1:8081` and proxies to the Mock.
3. Configure CC Switch using **Base URL `http://127.0.0.1:8081`** and the synthetic Key (see section below).
4. Run the scenario from the relevant checklist.
5. Check observations at the Mock port: `./scripts/cc-switch-compatibility/check-observations.ps1` or `./scripts/cc-switch-compatibility/check-observations.sh`.
6. Record your findings in the checklist.
7. Stop both processes with Ctrl+C (Mock first, then Gateway).

## Documents

| Document | Purpose |
|---|---|
| [manual-verification-guide.md](manual-verification-guide.md) | Step-by-step: start Mock/Gateway, configure CC Switch, verify paths |
| [version-evidence.md](version-evidence.md) | Known tool versions with evidence status (ENV_BLOCKED for unconfirmed) |
| [config-field-reference.md](config-field-reference.md) | CC Switch config field names, Base URL shapes, expected paths |

## Matrices and Checklists

| Matrix | Client | Protocol |
|---|---|---|
| [matrix-claude-code-anthropic.md](matrix-claude-code-anthropic.md) | Claude Code | Anthropic Messages (direct) |
| [matrix-claude-code-openai-routing.md](matrix-claude-code-openai-routing.md) | Claude Code + CC Switch Local Routing | OpenAI Chat Completions |
| [matrix-codex-responses.md](matrix-codex-responses.md) | Codex | OpenAI Responses |
| [matrix-claude-desktop.md](matrix-claude-desktop.md) | Claude Desktop | Direct / Model Mapping |

## Gateway Protocols

The Gateway exposes three transparent proxy entrypoints:

| Path | Protocol | CC Switch Use |
|---|---|---|
| `POST /v1/messages` | Anthropic Messages | Claude Code direct Anthropic Provider |
| `POST /v1/responses` | OpenAI Responses | Codex Responses |
| `POST /v1/chat/completions` | OpenAI Chat Completions | CC Switch Local Routing (after conversion) |

The Gateway preserves all protocol semantics; CC Switch owns protocol
conversion and model mapping. The Gateway's role is transparent forwarding
with credential stripping and header filtering.

## Synthetic Key Scheme

During G0.4 compatibility testing, the Gateway does not validate keys.
Use this synthetic key for CC Switch Provider configuration:

```
sk-miqrokey-…REDACTED000000
```

The Gateway strips all credential headers (authorization, x-api-key, api-key)
before forwarding to the upstream. For G0.4 PoC, the Gateway does not inject
real upstream credentials — this will be added in G1.5.
