/**
 * `miqro-context status` — ask the running agent for its recent decisions.
 */
import { loadConfig } from "../config.js";

export async function statusCommand(): Promise<void> {
  const { config } = loadConfig();
  const url = `http://${config.listenHost}:${config.listenPort}/__miqro/status`;
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(3_000) });
    if (!res.ok) {
      process.stderr.write(`agent responded HTTP ${res.status}\n`);
      process.exitCode = 1;
      return;
    }
    const body = (await res.json()) as {
      uptimeSeconds: number;
      gatewayBaseUrl: string;
      sessions: number;
      registry: { entries: number; fetchedAt: number; error?: string };
      recent: Array<Record<string, unknown>>;
    };
    process.stdout.write(
      `agent:    up ${body.uptimeSeconds}s, gateway ${body.gatewayBaseUrl || "(unset)"}\n` +
        `sessions: ${body.sessions}\n` +
        `registry: ${body.registry.entries} mapping(s)` +
        `${body.registry.fetchedAt ? `, synced ${new Date(body.registry.fetchedAt).toISOString()}` : ", never synced"}` +
        `${body.registry.error ? ` (${body.registry.error})` : ""}\n` +
        `recent decisions:\n`,
    );
    for (const d of body.recent) {
      process.stdout.write(
        `  ${new Date(Number(d["at"])).toISOString()}  ${String(d["status"]).padEnd(11)} ` +
          `${d["projectTag"] ? `→ ${String(d["projectTag"])}` : "→ (no project)"} ` +
          `[${String(d["claimSource"])}/${String(d["claimConfidence"])}` +
          `${d["inherited"] ? ", inherited" : ""}, ${String(d["evidenceCount"])} ev]\n`,
      );
    }
    if (body.recent.length === 0) process.stdout.write("  (none yet)\n");
  } catch (e) {
    process.stderr.write(
      `agent is not reachable at ${url} (${e instanceof Error ? e.message : String(e)})\n` +
        `start it with: miqro-context run\n`,
    );
    process.exitCode = 1;
  }
}
