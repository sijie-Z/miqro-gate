/**
 * `miqro-context doctor` — self-check (CAA Spec v1.1 §3.4, C12):
 * config · node · git · gateway reachability · virtual key validity ·
 * registry sync · local port. Every check is read-only.
 *
 * The degraded story is "Agent Failure / Direct Gateway Degraded" (R8): when
 * the agent is down, point ANTHROPIC_BASE_URL straight at the gateway — a
 * single-bound key keeps working (SOLE_BINDING); a multi-bound key without
 * context fails closed with CONTEXT_REQUIRED until the agent is back.
 */
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { loadConfig } from "../config.js";
import { Registry } from "../resolver/registry.js";

const run = promisify(execFile);

type Check = { name: string; ok: boolean | "warn"; detail: string };

export async function doctorCommand(): Promise<void> {
  const checks: Check[] = [];
  const { config, source, warnings } = loadConfig();

  checks.push({
    name: "node",
    ok: Number(process.versions.node.split(".")[0]) >= 20,
    detail: `v${process.versions.node} (need >= 20)`,
  });

  checks.push({
    name: "config",
    ok: warnings.length === 0 ? true : config.gatewayBaseUrl ? "warn" : false,
    detail: `${source}${warnings.length ? ` — ${warnings.join("; ")}` : ""}`,
  });

  try {
    const { stdout } = await run("git", ["--version"], { timeout: 3_000, windowsHide: true });
    checks.push({ name: "git", ok: true, detail: stdout.trim() });
  } catch {
    checks.push({
      name: "git",
      ok: "warn",
      detail: "git not found — tool_path/system_cwd evidence degrades to MEDIUM directory names",
    });
  }

  // Local agent reachability.
  try {
    const res = await fetch(`http://${config.listenHost}:${config.listenPort}/__miqro/status`, {
      signal: AbortSignal.timeout(2_000),
    });
    checks.push({ name: "agent", ok: res.ok, detail: res.ok ? "running" : `HTTP ${res.status}` });
  } catch {
    checks.push({ name: "agent", ok: "warn", detail: "not running (start with: miqro-context run)" });
  }

  // Gateway reachability + key validity + registry sync.
  const key = config.virtualKey;
  if (config.gatewayBaseUrl) {
    try {
      const res = await fetch(new URL("/v1/models", config.gatewayBaseUrl), {
        headers: key ? { authorization: `Bearer ${key}` } : {},
        signal: AbortSignal.timeout(5_000),
      });
      const reachable = res.status !== 0;
      const keyOk = key ? res.status === 200 : "warn";
      checks.push({
        name: "gateway",
        ok: reachable,
        detail: `${config.gatewayBaseUrl} → HTTP ${res.status}`,
      });
      checks.push({
        name: "virtual-key",
        ok: keyOk,
        detail: key
          ? res.status === 200
            ? "accepted by the gateway (/v1/models 200)"
            : `gateway answered HTTP ${res.status} for /v1/models — check MIQRO_CONTEXT_VIRTUAL_KEY`
          : "not configured; the agent learns it from proxied traffic (set MIQRO_CONTEXT_VIRTUAL_KEY for boot-time sync)",
      });
    } catch (e) {
      checks.push({
        name: "gateway",
        ok: false,
        detail: `${config.gatewayBaseUrl} unreachable: ${e instanceof Error ? e.message : String(e)}`,
      });
    }

    if (key) {
      const registry = new Registry();
      const result = await registry.sync(config.gatewayBaseUrl, key);
      checks.push({
        name: "registry",
        ok: result.ok,
        detail: result.ok
          ? `${result.count} repo mapping(s)`
          : `sync failed: ${result.error ?? "unknown"} (admin must register repos: POST /admin/projects/{id}/repositories)`,
      });
    }
  } else {
    checks.push({ name: "gateway", ok: false, detail: "gatewayBaseUrl not configured" });
  }

  let failed = 0;
  for (const c of checks) {
    const mark = c.ok === true ? "PASS" : c.ok === "warn" ? "WARN" : "FAIL";
    if (c.ok === false) failed += 1;
    process.stdout.write(`[${mark}] ${c.name.padEnd(12)} ${c.detail}\n`);
  }
  process.stdout.write(
    failed === 0
      ? "\ndoctor: no failures" +
          (checks.some((c) => c.ok === "warn") ? " (warnings above are informational)" : "") +
          "\n"
      : `\ndoctor: ${failed} failure(s)\n`,
  );
  if (failed > 0) process.exitCode = 1;
}
