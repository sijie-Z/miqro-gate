/**
 * Agent configuration (CAA Spec v1.1 §3.4): `~/.miqro/context.json` with
 * environment overrides. No secrets are required in the file — the virtual
 * key flows through the proxied traffic; `virtualKey` is only needed when the
 * gateway requires it before any request has been seen (registry sync at
 * boot).
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export type ContextConfig = {
  gatewayBaseUrl: string;
  listenHost: string;
  listenPort: number;
  registryRefreshSeconds: number;
  /** Optional: lets the agent sync the registry before the first request. */
  virtualKey?: string;
  debug: boolean;
};

export const DEFAULT_CONFIG: ContextConfig = {
  gatewayBaseUrl: "",
  listenHost: "127.0.0.1",
  listenPort: 8788,
  registryRefreshSeconds: 300,
  debug: false,
};

export function configPath(env: NodeJS.ProcessEnv = process.env): string {
  return env["MIQRO_CONTEXT_CONFIG"] ?? path.join(os.homedir(), ".miqro", "context.json");
}

export type LoadedConfig = {
  config: ContextConfig;
  source: string;
  warnings: string[];
};

export function loadConfig(env: NodeJS.ProcessEnv = process.env): LoadedConfig {
  const warnings: string[] = [];
  const file = configPath(env);
  let fromFile: Partial<ContextConfig> = {};
  if (fs.existsSync(file)) {
    try {
      fromFile = JSON.parse(fs.readFileSync(file, "utf8")) as Partial<ContextConfig>;
    } catch (e) {
      warnings.push(`config file ${file} is not valid JSON: ${e instanceof Error ? e.message : String(e)}`);
    }
  } else {
    warnings.push(`config file ${file} does not exist (defaults apply)`);
  }

  const config: ContextConfig = {
    ...DEFAULT_CONFIG,
    ...stripUndefined(fromFile),
  };
  const envUrl = env["MIQRO_CONTEXT_GATEWAY_URL"];
  if (envUrl) config.gatewayBaseUrl = envUrl;
  const envPort = env["MIQRO_CONTEXT_PORT"];
  if (envPort) config.listenPort = Number(envPort);
  const envRefresh = env["MIQRO_CONTEXT_REGISTRY_REFRESH_SECONDS"];
  if (envRefresh) config.registryRefreshSeconds = Number(envRefresh);
  const envKey = env["MIQRO_CONTEXT_VIRTUAL_KEY"];
  if (envKey) config.virtualKey = envKey;
  if (env["MIQRO_CONTEXT_DEBUG"] === "1" || env["MIQRO_CONTEXT_DEBUG"] === "true") config.debug = true;

  if (!config.gatewayBaseUrl) {
    warnings.push("gatewayBaseUrl is empty — set it in the config file or MIQRO_CONTEXT_GATEWAY_URL");
  } else {
    try {
      const u = new URL(config.gatewayBaseUrl);
      if (u.protocol !== "https:" && u.protocol !== "http:") {
        warnings.push(`gatewayBaseUrl must be http(s): got ${u.protocol}`);
      }
      config.gatewayBaseUrl = u.origin;
    } catch {
      warnings.push(`gatewayBaseUrl is not a valid URL: ${config.gatewayBaseUrl}`);
    }
  }
  if (config.listenHost !== "127.0.0.1" && config.listenHost !== "::1" && config.listenHost !== "localhost") {
    warnings.push(`listenHost ${config.listenHost} is not loopback — the agent must stay local`);
  }
  if (!Number.isInteger(config.listenPort) || config.listenPort < 1 || config.listenPort > 65535) {
    warnings.push(`listenPort invalid: ${config.listenPort}; falling back to ${DEFAULT_CONFIG.listenPort}`);
    config.listenPort = DEFAULT_CONFIG.listenPort;
  }
  return { config, source: file, warnings };
}

function stripUndefined<T extends object>(o: T): Partial<T> {
  const out: Partial<T> = {};
  for (const [k, v] of Object.entries(o)) {
    if (v !== undefined && v !== null) out[k as keyof T] = v as T[keyof T];
  }
  return out;
}

/** Persist a config (used by install when the user opts in). */
export function writeConfig(config: ContextConfig, env: NodeJS.ProcessEnv = process.env): string {
  const file = configPath(env);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
  return file;
}
