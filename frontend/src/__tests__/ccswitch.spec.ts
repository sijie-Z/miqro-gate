import { describe, expect, it } from 'vitest';
import { ccSwitchImportLink, claudeEnvSnippet, claudeSettingsSnippet } from '@/lib/ccswitch';

describe('ccswitch helpers', () => {
  it('builds a v1 provider-import deep link with endpoint, key and model', () => {
    const link = ccSwitchImportLink(
      'mqk_live_secret',
      'demo-key',
      'https://gw.example.com/',
      'deepseek-v4-flash',
    );
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
  });

  it('omits the model parameter when no model is known', () => {
    const link = ccSwitchImportLink('k', 'n', 'http://localhost:8081');
    expect(link).not.toContain('model=');
  });

  it('renders the Claude Code env snippet against the gateway base URL', () => {
    const snippet = claudeEnvSnippet('mqk_live_s', 'https://gw.example.com/');
    expect(snippet).toContain('export ANTHROPIC_BASE_URL="https://gw.example.com"');
    expect(snippet).toContain('export ANTHROPIC_AUTH_TOKEN="mqk_live_s"');
    expect(snippet).toContain('CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1');
  });

  it('renders a settings.json fragment parseable as JSON', () => {
    const text = claudeSettingsSnippet('mqk_live_s', 'http://localhost:8081');
    const parsed = JSON.parse(text) as { env: Record<string, string> };
    expect(parsed.env.ANTHROPIC_BASE_URL).toBe('http://localhost:8081');
    expect(parsed.env.ANTHROPIC_AUTH_TOKEN).toBe('mqk_live_s');
  });
});
