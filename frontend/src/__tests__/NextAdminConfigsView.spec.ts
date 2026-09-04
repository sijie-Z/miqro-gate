import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminConfigsView from '@/views/next/NextAdminConfigsView.vue';
import * as api from '@/api';

vi.mock('@/api', () => ({
  adminListConfigs: vi.fn(),
  adminPutConfig: vi.fn(),
  adminDeleteConfig: vi.fn(),
}));
const mockApi = vi.mocked(api);

const entry = {
  id: 'c1',
  groupName: 'gateway',
  key: 'cache_enabled',
  value: 'true',
  description: '是否开启缓存',
  version: 1,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
};

describe('NextAdminConfigsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListConfigs.mockResolvedValue([
      entry,
      {
        ...entry,
        id: 'c2',
        groupName: 'alerts',
        key: 'evaluation_interval_ms',
        value: '300000',
        description: '',
      },
    ]);
  });
  function mountView() {
    return mount(NextAdminConfigsView, { global: { plugins: [createPinia()] } });
  }
  it('renders configs and group filter', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="configs-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('cache_enabled');
    await wrapper
      .findAll('.next-configs__seg')
      .find((el) => el.text() === 'alerts')!
      .trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('evaluation_interval_ms');
    expect(wrapper.text()).not.toContain('cache_enabled');
  });
  it('creates a config entry through the dialog', async () => {
    mockApi.adminPutConfig.mockResolvedValue(entry);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="config-create-open"]').trigger('click');
    const setInput = (testid: string, value: string) => {
      const el = document.querySelector(`[data-testid="${testid}"]`) as HTMLInputElement;
      if (el.tagName === 'TEXTAREA') {
        const setter = Object.getOwnPropertyDescriptor(
          window.HTMLTextAreaElement.prototype,
          'value',
        )?.set;
        setter?.call(el, value);
      } else {
        const setter = Object.getOwnPropertyDescriptor(
          window.HTMLInputElement.prototype,
          'value',
        )?.set;
        setter?.call(el, value);
      }
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    setInput('config-group', 'gateway');
    setInput('config-key', 'retries');
    setInput('config-value', '2');
    await flushPromises();
    (document.querySelector('[data-testid="config-save"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.adminPutConfig).toHaveBeenCalledWith({
      group: 'gateway',
      key: 'retries',
      value: '2',
      description: undefined,
    });
  });
  it('deletes a config through the gate', async () => {
    mockApi.adminDeleteConfig.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="config-delete"]').trigger('click');
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '删除' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();
    expect(mockApi.adminDeleteConfig).toHaveBeenCalledWith('gateway', 'cache_enabled');
  });
});
