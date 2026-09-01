import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminConfigsView from '@/views/AdminConfigsView.vue';
import * as api from '@/api';
import type { ConfigEntryView } from '@/types/api';

vi.mock('@/api', () => ({
  adminListConfigs: vi.fn(),
  adminPutConfig: vi.fn(),
  adminDeleteConfig: vi.fn(),
}));

const mockApi = vi.mocked(api);

const entry = (overrides: Partial<ConfigEntryView> = {}): ConfigEntryView => ({
  id: '0190-0000-0000-0000-0000000000f1',
  groupName: 'gateway',
  key: 'max-streams',
  value: '50',
  description: '并发流上限',
  version: 1,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

function mountView() {
  return mount(AdminConfigsView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('AdminConfigsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListConfigs.mockResolvedValue([]);
  });

  it('renders grouped config entries', async () => {
    mockApi.adminListConfigs.mockResolvedValue([
      entry(),
      entry({ id: 'x2', groupName: 'alerts', key: 'evaluation-interval-ms', value: '300000' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="configs-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('gateway');
    expect(wrapper.text()).toContain('max-streams');
    expect(wrapper.text()).toContain('50');
    expect(wrapper.text()).toContain('alerts');
    // Group filter chips appear.
    expect(wrapper.text()).toContain('全部');
  });

  it('creates a config entry', async () => {
    mockApi.adminPutConfig.mockResolvedValue(entry());

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="config-create-open"]').trigger('click');
    await wrapper.find('[data-testid="config-group"] input').setValue('gateway');
    await wrapper.find('[data-testid="config-key"] input').setValue('max-streams');
    await wrapper.find('[data-testid="config-value"] textarea').setValue('50');
    await wrapper.find('[data-testid="config-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminPutConfig).toHaveBeenCalledWith({
      group: 'gateway',
      key: 'max-streams',
      value: '50',
      description: undefined,
    });
  });

  it('updates an existing entry with the value', async () => {
    mockApi.adminListConfigs.mockResolvedValue([entry()]);
    mockApi.adminPutConfig.mockResolvedValue(entry({ value: '100' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="config-edit"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="config-value"] textarea').setValue('100');
    await wrapper.find('[data-testid="config-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminPutConfig).toHaveBeenCalledWith({
      group: 'gateway',
      key: 'max-streams',
      value: '100',
      description: '并发流上限',
    });
  });

  it('deletes an entry after confirming the dialog', async () => {
    mockApi.adminListConfigs.mockResolvedValue([entry()]);
    mockApi.adminDeleteConfig.mockResolvedValue(undefined);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="config-delete"]').trigger('click');
    await flushPromises();

    const confirmButton = Array.from(document.querySelectorAll('.t-dialog__confirm')).find((b) =>
      b.textContent?.includes('删除'),
    );
    expect(confirmButton, 'confirm dialog should render').toBeTruthy();
    (confirmButton as HTMLElement).click();
    await flushPromises();
    await new Promise((r) => setTimeout(r, 400));

    expect(mockApi.adminDeleteConfig).toHaveBeenCalledWith('gateway', 'max-streams');
  });
});
