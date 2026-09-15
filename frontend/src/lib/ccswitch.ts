/**
 * CC Switch integration helpers (pure, unit-testable).
 *
 * One-click import uses CC Switch's deep-link protocol v1
 * (docs/user-manual/zh/5-faq/5.3-deeplink.md in farion1231/cc-switch):
 * `ccswitch://v1/import?resource=provider&app=claude&...` pre-fills the
 * provider import dialog so the user never re-types the gateway URL or key.
 */

export const CCSWITCH_APP_CLAUDE = 'claude';

/** Trailing slashes would double up when clients append `/v1/messages`. */
function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '');
}

/**
 * Build the provider-import deep link. `model` seeds the default model so
 * Claude Code immediately requests a granted model id.
 */
export function ccSwitchImportLink(
  secret: string,
  keyName: string,
  baseUrl: string,
  model?: string,
): string {
  const endpoint = normalizeBaseUrl(baseUrl);
  const params = new URLSearchParams({
    resource: 'provider',
    app: CCSWITCH_APP_CLAUDE,
    name: `MiQroKey · ${keyName}`,
    endpoint,
    homepage: endpoint,
    apiKey: secret,
  });
  if (model) {
    params.set('model', model);
  }
  return `ccswitch://v1/import?${params.toString()}`;
}

/** Terminal flavors for the copy-ready env block. */
export type ShellFlavor = 'posix' | 'cmd' | 'powershell';

export const SHELL_FLAVOR_LABEL: Record<ShellFlavor, string> = {
  posix: 'macOS / Linux',
  cmd: 'Windows CMD',
  powershell: 'PowerShell',
};

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
