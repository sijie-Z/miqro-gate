/**
 * `miqro-context install` — print the Claude Code setup steps. Deliberately
 * NON-invasive: it never edits your Claude Code settings; it shows exactly
 * what to set and optionally writes the agent's own config file.
 */
import { loadConfig, writeConfig, type ContextConfig } from "../config.js";

export async function installCommand(): Promise<void> {
  const { config, source, warnings } = loadConfig();
  for (const w of warnings) process.stderr.write(`note: ${w}\n`);

  const gateway = config.gatewayBaseUrl || "https://<your-gateway>";
  const agentUrl = `http://${config.listenHost}:${config.listenPort}`;

  process.stdout.write(
    `miqro-context setup
===================

1. Register your repositories on the server (admin, once per repo):

     POST /api/v1/admin/projects/{projectId}/repositories  { "repoKey": "github.com/<owner>/<repo>" }

   The agent learns repo → project mappings from the gateway
   (GET /v1/context-registry, authenticated with your virtual key).

2. Point Claude Code at the local agent — keep your virtual key as the token:

     ANTHROPIC_BASE_URL=${agentUrl}
     ANTHROPIC_AUTH_TOKEN=<your mqk_live_... virtual key>

   (Claude Code settings.json "env" block, or your shell profile.)

3. Start the agent and verify:

     miqro-context run        # foreground; Ctrl+C stops it
     miqro-context doctor     # config · gateway · key · registry · git
     miqro-context status     # recent attribution decisions

4. Smoke test (any client):

     curl -s ${agentUrl}/v1/models -H "Authorization: Bearer <virtual key>"

Degraded mode (Agent Failure / Direct Gateway Degraded):
  If the agent is not running, point ANTHROPIC_BASE_URL straight at the
  gateway (${gateway}). A single-bound key keeps working (SOLE_BINDING);
  a multi-bound key without context fails closed (400 CONTEXT_REQUIRED)
  until the agent is back. The agent is not a security boundary — the
  gateway always validates identity and bindings.

Config file: ${source}
  { "gatewayBaseUrl": "${gateway}", "listenPort": ${config.listenPort} }
`,
  );

  if (!config.gatewayBaseUrl) {
    process.stdout.write(
      "gatewayBaseUrl is not set yet — rerun install after creating the config file,\n" +
        "or set MIQRO_CONTEXT_GATEWAY_URL.\n",
    );
  }
  void writeConfigIfRequested(config);
}

async function writeConfigIfRequested(config: ContextConfig): Promise<void> {
  if (!process.argv.includes("--write-config")) return;
  if (!config.gatewayBaseUrl) {
    process.stderr.write("--write-config skipped: gatewayBaseUrl is empty\n");
    return;
  }
  const file = writeConfig(config);
  process.stdout.write(`\nwrote ${file}\n`);
}
