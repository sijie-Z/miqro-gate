import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import CcSwitchImport from '@/components/CcSwitchImport.vue';
import { launchImportLink } from '@/lib/ccswitch';
import { toastState } from '@/ui/toast';

vi.mock('@/lib/ccswitch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/ccswitch')>();
  return { ...actual, launchImportLink: vi.fn() };
});

const mockLaunch = vi.mocked(launchImportLink);

let wrapper: VueWrapper | null = null;

function mountPanel(props: Record<string, unknown> = {}) {
  wrapper = mount(CcSwitchImport, {
    props: {
      secret: '',
      keyName: 'demo-key',
      baseUrl: 'https://gw.example.com',
      importTestId: 'imp',
      ...props,
    },
  });
  return wrapper;
}

const writeText = vi.fn().mockResolvedValue(undefined);

beforeEach(() => {
  vi.clearAllMocks();
  toastState.items.splice(0);
  writeText.mockClear();
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  vi.useRealTimers();
});

describe('CcSwitchImport (#839)', () => {
  it('defaults to the claude target and switches the hint with the app', async () => {
    const w = mountPanel();
    expect(w.find('[data-testid="imp"]').text()).toContain('导入到 CC Switch');
    expect(w.find('[data-testid="ccswitch-import-app-hint"]').text()).toContain('Claude Code');

    await w.find('[data-testid="imp-app-codex"]').setValue('codex');
    expect(w.find('[data-testid="ccswitch-import-app-hint"]').text()).toContain('Codex');
  });

  it('honours the defaultApp prop', () => {
    const w = mountPanel({ defaultApp: 'codex' });
    const codexRadio = w.find('[data-testid="imp-app-codex"]').element as HTMLInputElement;
    expect(codexRadio.checked).toBe(true);
  });

  it('keeps the import disabled until a plaintext key is present', async () => {
    const w = mountPanel();
    const button = w.find('[data-testid="imp"]');
    expect(button.attributes('disabled')).toBeDefined();
    expect(w.find('[data-testid="imp-copy-link"]').attributes('disabled')).toBeDefined();

    await w.setProps({ secret: 'mqk_live_x' });
    expect(w.find('[data-testid="imp"]').attributes('disabled')).toBeUndefined();
  });

  it('launches the deep link and announces the wake-up once the page blurs', async () => {
    const w = mountPanel({ secret: 'mqk_live_x' });
    await w.find('[data-testid="imp"]').trigger('click');

    expect(mockLaunch).toHaveBeenCalledTimes(1);
    const link = mockLaunch.mock.calls[0]![0];
    expect(link.startsWith('ccswitch://v1/import?')).toBe(true);
    expect(link).toContain('app=claude');
    expect(link).toContain('apiKey=mqk_live_x');
    // Cooldown: the button immediately reflects "sent" and cannot be re-fired.
    expect(w.find('[data-testid="imp"]').attributes('disabled')).toBeDefined();
    expect(w.find('[data-testid="imp"]').text()).toContain('已发送');
    expect(w.find('[data-testid="imp-feedback"]').text()).toContain('正在唤起');

    window.dispatchEvent(new Event('blur'));
    await w.vm.$nextTick();
    const feedback = w.find('[data-testid="imp-feedback"]').text();
    expect(feedback).toContain('已唤起 CC Switch');
    expect(feedback).toContain('导入确认');
    expect(feedback).toContain('MiQroKey · demo-key');
  });

  it('falls back to the not-detected guidance when nothing steals focus (#839)', async () => {
    vi.useFakeTimers();
    const w = mountPanel({ secret: 'mqk_live_x' });
    await w.find('[data-testid="imp"]').trigger('click');

    await vi.advanceTimersByTimeAsync(2600);
    const feedback = w.find('[data-testid="imp-feedback"]').text();
    expect(feedback).toContain('未检测到 CC Switch 被唤起');
    expect(feedback).toContain('未安装');
    expect(feedback).toContain('复制导入链接');
  });

  it('re-enables the button as a resend after the cooldown', async () => {
    vi.useFakeTimers();
    const w = mountPanel({ secret: 'mqk_live_x' });
    await w.find('[data-testid="imp"]').trigger('click');
    expect(w.find('[data-testid="imp"]').attributes('disabled')).toBeDefined();

    await vi.advanceTimersByTimeAsync(4100);
    const button = w.find('[data-testid="imp"]');
    expect(button.attributes('disabled')).toBeUndefined();
    expect(button.text()).toContain('重新发送');
  });

  it('sends the codex link with the /v1 endpoint when Codex is selected', async () => {
    const w = mountPanel({ secret: 'mqk_live_x', defaultApp: 'codex' });
    await w.find('[data-testid="imp"]').trigger('click');
    const link = mockLaunch.mock.calls[0]![0];
    expect(link).toContain('app=codex');
    expect(decodeURIComponent(link)).toContain('endpoint=https://gw.example.com/v1');
  });

  it('copies the import link once a key is present', async () => {
    const w = mountPanel({ secret: 'mqk_live_x' });
    await w.find('[data-testid="imp-copy-link"]').trigger('click');
    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText.mock.calls[0]![0]).toContain('ccswitch://v1/import?');
    expect(toastState.items.at(-1)?.message).toBe('导入链接已复制');

    // Without a key both actions are disabled up-front (no dead-end click).
    await w.setProps({ secret: '' });
    expect(w.find('[data-testid="imp-copy-link"]').attributes('disabled')).toBeDefined();
  });

  it('renders the paste input only when showSecretInput is on', () => {
    const withInput = mountPanel();
    expect(withInput.find('[data-testid="imp-secret-input"]').exists()).toBe(true);
    withInput.unmount();

    wrapper = mountPanel({ secret: 'mqk_live_x', showSecretInput: false });
    expect(wrapper.find('[data-testid="imp-secret-input"]').exists()).toBe(false);
  });
});
