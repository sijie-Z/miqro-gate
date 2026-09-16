/**
 * `miqro-context run` — start the local agent in the foreground.
 */
import { ContextAgent } from "../agent.js";
import { loadConfig } from "../config.js";
import { createAgentServer } from "../proxy/server.js";

export async function runCommand(): Promise<void> {
  const { config, source, warnings } = loadConfig();
  for (const w of warnings) process.stderr.write(`warning: ${w}\n`);
  if (!config.gatewayBaseUrl) {
    process.stderr.write("error: gatewayBaseUrl is required (config file or MIQRO_CONTEXT_GATEWAY_URL)\n");
    process.exitCode = 1;
    return;
  }

  const agent = new ContextAgent();
  const server = createAgentServer({
    config,
    agent,
    log: (line) => process.stdout.write(`${line}\n`),
  });

  const syncRegistry = async () => {
    const key = agent.keyForRegistry(config.virtualKey);
    if (!key) return; // no key seen yet; retry after the first request arrives
    const result = await agent.registry.sync(config.gatewayBaseUrl, key);
    process.stdout.write(
      result.ok
        ? `registry synced: ${result.count} repo mapping(s)\n`
        : `registry sync failed: ${result.error ?? "unknown"}\n`,
    );
  };

  server.listen(config.listenPort, config.listenHost, () => {
    process.stdout.write(
      `miqro-context agent listening on http://${config.listenHost}:${config.listenPort}\n` +
        `  gateway:  ${config.gatewayBaseUrl}\n` +
        `  config:   ${source}\n` +
        `  Claude Code: set ANTHROPIC_BASE_URL=http://${config.listenHost}:${config.listenPort} ` +
        `and keep your virtual key as ANTHROPIC_AUTH_TOKEN (see: miqro-context install)\n`,
    );
    void syncRegistry();
    const timer = setInterval(() => void syncRegistry(), Math.max(30, config.registryRefreshSeconds) * 1000);
    timer.unref();
  });

  const shutdown = () => {
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2_000).unref();
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}
