import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminServicesView from '@/views/AdminServicesView.vue';
import * as api from '@/api';
import type { InternalServiceView } from '@/types/api';

vi.mock('@/api', () => ({
  adminListServices: vi.fn(),
  adminCreateService: vi.fn(),
  adminDisableService: vi.fn(),
}));

const mockApi = vi.mocked(api);

const service = (overrides: Partial<InternalServiceView> = {}): InternalServiceView => ({
  id: '0190-0000-0000-0000-0000000000e1',
  name: 'platform-api',
  kind: 'HTTP',
  description: 'Platform backend',
  baseUrl: 'https://platform.internal.example',
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

function mountView() {
  return mount(AdminServicesView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('AdminServicesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListServices.mockResolvedValue([]);
  });

  it('renders the service registry with kinds and statuses', async () => {
    mockApi.adminListServices.mockResolvedValue([
      service(),
      service({ id: 'x2', name: 'mcp-gateway', kind: 'MCP', status: 'DISABLED' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="services-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('platform-api');
    expect(wrapper.text()).toContain('https://platform.internal.example');
    expect(wrapper.text()).toContain('MCP');
    expect(wrapper.text()).toContain('Active');
    expect(wrapper.text()).toContain('Disabled');
  });

  it('registers a service with name, kind and url', async () => {
    mockApi.adminCreateService.mockResolvedValue(service());

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="service-create-open"]').trigger('click');
    await wrapper.find('[data-testid="service-create-name"] input').setValue('platform-api');
    await wrapper
      .find('[data-testid="service-create-url"] input')
      .setValue('https://platform.internal.example');
    await wrapper.find('[data-testid="service-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminCreateService).toHaveBeenCalledWith({
      name: 'platform-api',
      kind: 'HTTP',
      description: undefined,
      baseUrl: 'https://platform.internal.example',
    });
  });

  it('disables a service after confirming the dialog', async () => {
    mockApi.adminListServices.mockResolvedValue([service()]);
    mockApi.adminDisableService.mockResolvedValue(service({ status: 'DISABLED' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="service-disable"]').trigger('click');
    await flushPromises();

    const confirmButton = Array.from(document.querySelectorAll('.t-dialog__confirm')).find((b) =>
      b.textContent?.includes('禁用'),
    );
    expect(confirmButton, 'confirm dialog should render').toBeTruthy();
    (confirmButton as HTMLElement).click();
    await flushPromises();
    await new Promise((r) => setTimeout(r, 400));

    expect(mockApi.adminDisableService).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000e1');
  });
});
