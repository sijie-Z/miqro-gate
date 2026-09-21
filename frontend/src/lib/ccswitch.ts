/**
 * CC Switch integration helpers (pure, unit-testable).
 *
 * One-click import uses CC Switch's deep-link protocol v1
 * (docs/user-manual/zh/5-faq/5.3-deeplink.md in farion1231/cc-switch):
 * `ccswitch://v1/import?resource=provider&app=…` pre-fills the provider
 * import dialog so the user never re-types the gateway URL or key.
 *
 * The link is one-way: CC Switch never reports back whether the user
 * confirmed the import, so the UI pairs it with guidance + a wake-up
 * heuristic instead of pretending to know the outcome.
 */

/** Target application of the provider import (deep-link `app` parameter). */
export type CcSwitchApp = 'claude' | 'codex';

export const CCSWITCH_APP_LABEL: Record<CcSwitchApp, string> = {
  claude: 'Claude Code',
  codex: 'Codex',
};

/**
 * Default target derived from the key's (declarative) purpose label:
 * CODEX keys land in CC Switch's Codex group, everything else in Claude Code.
 */
export function defaultAppForPurpose(purpose?: string): CcSwitchApp {
  return purpose === 'CODEX' ? 'codex' : 'claude';
}

/** Trailing slashes would double up when clients append `/v1/messages`. */
function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '');
}

export interface CcSwitchImportOptions {
  secret: string;
  keyName: string;
  baseUrl: string;
  model?: string;
  app?: CcSwitchApp;
}

/**
 * Build the provider-import deep link. `model` seeds the default model so the
 * client immediately requests a granted model id.
 *
 * Endpoint shape per app:
 * - claude: the bare gateway base — Claude Code appends `/v1/messages`;
 * - codex: base + `/v1` — CC Switch's codex provider template is fixed to
 *   `wire_api = "responses"` with `base_url = endpoint` as-is, and Codex
 *   appends `/responses`, so the endpoint must be `…/v1` to hit the
 *   gateway's `/v1/responses` pass-through.
 */
export function ccSwitchImportLink(opts: CcSwitchImportOptions): string {
  const app = opts.app ?? 'claude';
  const base = normalizeBaseUrl(opts.baseUrl);
  const params = new URLSearchParams({
    resource: 'provider',
    app,
    name: `MiQroKey · ${opts.keyName}`,
    endpoint: app === 'codex' ? `${base}/v1` : base,
    homepage: base,
    apiKey: opts.secret,
    notes: 'MiQroKey 网关 · 仅可使用该密钥已授权的模型',
  });
  if (opts.model) {
    params.set('model', opts.model);
  }
  return `ccswitch://v1/import?${params.toString()}`;
}

/** Isolated so component tests can stub the navigation. */
export function launchImportLink(link: string): void {
  window.location.href = link;
}

/** Terminal flavors for the copy-ready env block. */
export type ShellFlavor = 'posix' | 'cmd' | 'powershell';

export const SHELL_FLAVOR_LABEL: Record<ShellFlavor, string> = {
  posix: 'macOS / Linux',
  cmd: 'Windows CMD',
  powershell: 'PowerShell',
};

/** `~/.claude/settings.json` (or its %USERPROFILE% twin on Windows). */
export function claudeSettingsPath(flavor: ShellFlavor): string {
  return flavor === 'posix' ? '~/.claude/settings.json' : '%USERPROFILE%\\.claude\\settings.json';
}

/** `~/.codex/config.toml` (or its %USERPROFILE% twin on Windows). */
export function codexConfigPath(flavor: ShellFlavor): string {
  return flavor === 'posix' ? '~/.codex/config.toml' : '%USERPROFILE%\\.codex\\config.toml';
}

/** `~/.codex/auth.json` (or its %USERPROFILE% twin on Windows). */
export function codexAuthPath(flavor: ShellFlavor): string {
  return flavor === 'posix' ? '~/.codex/auth.json' : '%USERPROFILE%\\.codex\\auth.json';
}

/** Shell env block for Claude Code, per terminal flavor (Bearer auth). */
export function claudeEnvSnippet(
  secret: string,
  baseUrl: string,
  flavor: ShellFlavor = 'posix',
): string {
  const base = normalizeBaseUrl(baseUrl);
  switch (flavor) {
    case 'cmd':
      return [
        `set ANTHROPIC_BASE_URL=${base}`,
        `set ANTHROPIC_AUTH_TOKEN=${secret}`,
        'set CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1',
      ].join('\n');
    case 'powershell':
      return [
        `$env:ANTHROPIC_BASE_URL="${base}"`,
        `$env:ANTHROPIC_AUTH_TOKEN="${secret}"`,
        '$env:CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC="1"',
      ].join('\n');
    default:
      return [
        `export ANTHROPIC_BASE_URL="${base}"`,
        `export ANTHROPIC_AUTH_TOKEN="${secret}"`,
        'export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1',
      ].join('\n');
  }
}

/** `~/.claude/settings.json` fragment for the VSCode/JetBrains Claude Code plugin. */
export function claudeSettingsSnippet(secret: string, baseUrl: string): string {
  return JSON.stringify(
    {
      env: {
        ANTHROPIC_BASE_URL: normalizeBaseUrl(baseUrl),
        ANTHROPIC_AUTH_TOKEN: secret,
        CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
      },
    },
    null,
    2,
  );
}

/** Client shapes covered by the「使用密钥」panel (manual setup, no CC Switch). */
export type UsageClient = 'claude' | 'codex' | 'openai';

export const USAGE_CLIENT_LABEL: Record<UsageClient, string> = {
  claude: 'Claude Code',
  codex: 'Codex CLI',
  openai: '通用 OpenAI 兼容',
};

/** Codex credential placements offered by the manual-config section. */
export type CodexAuthMode = 'auth-json' | 'env';

/**
 * Codex CLI `~/.codex/config.toml` fragment. `wire_api = "chat"` matches the
 * gateway's /v1/chat/completions pass-through (works for every chat-capable
 * upstream; switch to "responses" only for Responses-native products).
 *
 * The credential is deliberately NOT a parameter: the snippet ends up in a
 * config file that gets grepped, indexed, backed up and attached to tickets,
 * so a real value here would ride along every time. The env var named by
 * `env_key` is set separately (mirrors scripts/onboarding/miqro-onboard.sh).
 */
export function codexTomlSnippet(baseUrl: string, model: string): string {
  const base = normalizeBaseUrl(baseUrl);
  return [
    `model_provider = "miqrokey"`,
    `model = "${model || '<已授权模型>'}"`,
    '',
    '[model_providers.miqrokey]',
    'name = "MiQroKey"',
    `base_url = "${base}/v1"`,
    'env_key = "MIQROKEY_API_KEY"',
    'wire_api = "chat"',
    '',
    `# 然后设置环境变量（Windows CMD 用 set，PowerShell 用 $env:）：`,
    `#   MIQROKEY_API_KEY=<粘贴你保存的密钥；不要写进本文件>`,
  ].join('\n');
}

/**
 * Codex `config.toml` fragment for the auth.json credential placement:
 * the same provider entry without `env_key`, with `requires_openai_auth`
 * so Codex reads `OPENAI_API_KEY` from `~/.codex/auth.json` instead.
 */
export function codexAuthJsonModeTomlSnippet(baseUrl: string, model: string): string {
  const base = normalizeBaseUrl(baseUrl);
  return [
    `model_provider = "miqrokey"`,
    `model = "${model || '<已授权模型>'}"`,
    '',
    '[model_providers.miqrokey]',
    'name = "MiQroKey"',
    `base_url = "${base}/v1"`,
    'wire_api = "chat"',
    'requires_openai_auth = true',
    '',
    '# 凭据放在 auth.json（见下方文件块）：~/.codex/auth.json',
    '# 或删除本行改用 env_key = "MIQROKEY_API_KEY" + 环境变量。',
  ].join('\n');
}

/** `~/.codex/auth.json` content (Codex reads OPENAI_API_KEY from here). */
export function codexAuthJsonSnippet(): string {
  return JSON.stringify({ OPENAI_API_KEY: '<粘贴你保存的密钥>' }, null, 2);
}

/**
 * Generic OpenAI-compatible client (WorkBuddy 这类自配工具): the tool asks for
 * a Base URL and an API Key — both shown verbatim, plus a smoke-test curl.
 */
export function openaiCompatSnippet(secret: string, baseUrl: string, model: string): string {
  const base = normalizeBaseUrl(baseUrl);
  return [
    `Base URL: ${base}/v1`,
    `API Key:  ${secret}`,
    '',
    '# 连通性自测：',
    `curl ${base}/v1/chat/completions \\`,
    `  -H "Authorization: Bearer ${secret}" -H "Content-Type: application/json" \\`,
    `  -d '{"model":"${model || '<已授权模型>'}","messages":[{"role":"user","content":"hi"}]}'`,
  ].join('\n');
}
