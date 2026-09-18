import { describe, expect, it } from 'vitest';
import {
  CCSWITCH_APP_LABEL,
  ccSwitchImportLink,
  claudeEnvSnippet,
  claudeSettingsPath,
  claudeSettingsSnippet,
  codexAuthJsonModeTomlSnippet,
  codexAuthJsonSnippet,
  codexAuthPath,
  codexConfigPath,
  codexTomlSnippet,
  defaultAppForPurpose,
  openaiCompatSnippet,
} from '@/lib/ccswitch';

describe('ccswitch helpers', () => {
  it('builds a Claude provider-import deep link with endpoint, key and model', () => {
    const link = ccSwitchImportLink({
      secret: 'mqk_live_secret',
      keyName: 'demo-key',
      baseUrl: 'https://gw.example.com/',
      model: 'deepseek-v4-flash',
    });
    expect(link.startsWith('ccswitch://v1/import?')).toBe(true);
    const params = new URL(`http://x/?${link.split('?')[1]}`).searchParams;
    expect(params.get('resource')).toBe('provider');
    expect(params.get('app')).toBe('claude');
    expect(params.get('name')).toBe('MiQroKey · demo-key');
    // Trailing slashes are stripped so clients can append /v1/messages safely.
    expect(params.get('endpoint')).toBe('https://gw.example.com');
    expect(params.get('homepage')).toBe('https://gw.example.com');
    expect(params.get('apiKey')).toBe('mqk_live_secret');
    expect(params.get('model')).toBe('deepseek-v4-flash');
    expect(params.get('notes')).toContain('仅可使用该密钥已授权的模型');
  });

  it('builds a Codex deep link whose endpoint carries /v1 (#839)', () => {
    const link = ccSwitchImportLink({
      secret: 'k',
      keyName: 'n',
      baseUrl: 'https://gw.example.com/',
      app: 'codex',
    });
    const params = new URL(`http://x/?${link.split('?')[1]}`).searchParams;
    expect(params.get('app')).toBe('codex');
    // CC Switch writes base_url = endpoint verbatim and its Codex template is
    // fixed to wire_api="responses"; Codex appends /responses, so the endpoint
    // must already include /v1 to hit the gateway's /v1/responses pass-through.
    expect(params.get('endpoint')).toBe('https://gw.example.com/v1');
    expect(params.get('model')).toBeNull();
  });

  it('defaults the import target from the declarative purpose (#839)', () => {
    expect(defaultAppForPurpose('CODEX')).toBe('codex');
    expect(defaultAppForPurpose('CLAUDE_CODE')).toBe('claude');
    expect(defaultAppForPurpose('CLAUDE_DESKTOP')).toBe('claude');
    expect(defaultAppForPurpose(undefined)).toBe('claude');
    expect(CCSWITCH_APP_LABEL.codex).toBe('Codex');
    expect(CCSWITCH_APP_LABEL.claude).toBe('Claude Code');
  });

  it('renders the Claude Code env snippet against the gateway base URL', () => {
    const snippet = claudeEnvSnippet('mqk_live_s', 'https://gw.example.com/');
    expect(snippet).toContain('export ANTHROPIC_BASE_URL="https://gw.example.com"');
    expect(snippet).toContain('export ANTHROPIC_AUTH_TOKEN="mqk_live_s"');
    expect(snippet).toContain('CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1');
  });

  it('renders Windows CMD and PowerShell flavors of the env snippet', () => {
    const cmd = claudeEnvSnippet('k', 'https://gw.example.com', 'cmd');
    expect(cmd).toContain('set ANTHROPIC_BASE_URL=https://gw.example.com');
    expect(cmd).toContain('set ANTHROPIC_AUTH_TOKEN=k');
    const ps = claudeEnvSnippet('k', 'https://gw.example.com', 'powershell');
    expect(ps).toContain('$env:ANTHROPIC_BASE_URL="https://gw.example.com"');
    expect(ps).toContain('$env:ANTHROPIC_AUTH_TOKEN="k"');
  });

  it('renders a settings.json fragment parseable as JSON', () => {
    const text = claudeSettingsSnippet('mqk_live_s', 'http://localhost:8081');
    const parsed = JSON.parse(text) as { env: Record<string, string> };
    expect(parsed.env.ANTHROPIC_BASE_URL).toBe('http://localhost:8081');
    expect(parsed.env.ANTHROPIC_AUTH_TOKEN).toBe('mqk_live_s');
  });
  it('renders the Codex TOML with chat wire api and env key hint', () => {
    const toml = codexTomlSnippet('https://gw.example.com/', 'deepseek-flash');
    expect(toml).toContain('base_url = "https://gw.example.com/v1"');
    expect(toml).toContain('wire_api = "chat"');
    expect(toml).toContain('env_key = "MIQROKEY_API_KEY"');
  });

  it('never embeds a credential in the Codex snippet (#821)', () => {
    // The credential is not even a parameter: a value in the TOML comment would
    // be grepped, indexed, backed up and attached to tickets along with the file.
    const toml = codexTomlSnippet('https://gw.example.com/', 'deepseek-flash');
    expect(toml).toContain('MIQROKEY_API_KEY=<粘贴你保存的密钥；不要写进本文件>');
    expect(toml).not.toMatch(/MIQROKEY_API_KEY=mqk_/);
  });

  it('renders the Codex auth.json-mode TOML (no env_key) and the auth.json body (#839)', () => {
    const toml = codexAuthJsonModeTomlSnippet('https://gw.example.com/', 'deepseek-v4-flash');
    expect(toml).toContain('base_url = "https://gw.example.com/v1"');
    expect(toml).toContain('wire_api = "chat"');
    expect(toml).toContain('requires_openai_auth = true');
    // No active env_key line (the optional hint comment may still mention it).
    expect(toml.split('\n').some((line) => line.startsWith('env_key'))).toBe(false);
    const auth = JSON.parse(codexAuthJsonSnippet()) as { OPENAI_API_KEY: string };
    expect(auth.OPENAI_API_KEY).toContain('密钥');
    expect(auth.OPENAI_API_KEY).not.toMatch(/mqk_/);
  });

  it('renders per-platform config file paths (#839)', () => {
    expect(claudeSettingsPath('posix')).toBe('~/.claude/settings.json');
    expect(claudeSettingsPath('powershell')).toBe('%USERPROFILE%\\.claude\\settings.json');
    expect(codexConfigPath('posix')).toBe('~/.codex/config.toml');
    expect(codexConfigPath('cmd')).toBe('%USERPROFILE%\\.codex\\config.toml');
    expect(codexAuthPath('posix')).toBe('~/.codex/auth.json');
    expect(codexAuthPath('cmd')).toBe('%USERPROFILE%\\.codex\\auth.json');
  });

  it('renders the generic OpenAI-compatible base URL / key / curl block', () => {
    const text = openaiCompatSnippet('key-1', 'http://localhost:8081', 'deepseek-chat');
    expect(text).toContain('Base URL: http://localhost:8081/v1');
    expect(text).toContain('API Key:  key-1');
    expect(text).toContain('curl http://localhost:8081/v1/chat/completions');
  });
});
