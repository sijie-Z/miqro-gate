import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminMcpServicesView from '@/views/AdminMcpServicesView.vue';
import * as api from '@/api';
import type { McpServiceView } from '@/types/api';

vi.mock('@/api', () => ({
  adminListMcpServices: vi.fn(),
  adminCreateMcpService: vi.fn(),
  adminSetMcpStatus: vi.fn(),
  adminUpdateMcpHealthConfig: vi.fn(),
}));

const mockApi = vi.mocked(api);

const service = (overrides: Partial<McpServiceView> = {}): McpServiceView => ({
  id: '0190-0000-0000-0000-0000000000f2',
  name: 'erp-mcp',
  description: 'ERP MCP server',
  endpoint: 'https://erp.internal.example',
  transport: 'STREAMABLE_HTTP',
  status: 'ONLINE',
  healthStatus: 'HEALTHY',
  healthCheckedAt: '2026-09-01T00:00:00Z',
  checkIntervalSeconds: 30,
  checkTimeoutSeconds: 5,
  failThreshold: 3,
  recoverThreshold: 1,
  checkPath: '/health',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

function mountView() {
  return mount(AdminMcpServicesView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('AdminMcpServicesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListMcpServices.mockResolvedValue([]);
  });

  it('renders MCP services with health states', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([
      service(),
      service({ id: 'x2', name: 'db-mcp', healthStatus: 'UNHEALTHY', status: 'OFFLINE' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="mcp-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('erp-mcp');
    expect(wrapper.text()).toContain('https://erp.internal.example');
    expect(wrapper.text()).toContain('健康');
    expect(wrapper.text()).toContain('不健康');
    expect(wrapper.text()).toContain('Offline');
  });

  it('registers an MCP service with the endpoint', async () => {
    mockApi.adminCreateMcpService.mockResolvedValue(service());

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-create-open"]').trigger('click');
    await wrapper.find('[data-testid="mcp-create-name"] input').setValue('erp-mcp');
    await wrapper
      .find('[data-testid="mcp-create-endpoint"] input')
      .setValue('https://erp.internal.example');
    await wrapper.find('[data-testid="mcp-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminCreateMcpService).toHaveBeenCalledWith({
      name: 'erp-mcp',
      description: undefined,
      endpoint: 'https://erp.internal.example',
      transport: 'STREAMABLE_HTTP',
    });
  });

  it('takes a service offline after confirming the dialog', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminSetMcpStatus.mockResolvedValue(service({ status: 'OFFLINE' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-offline"]').trigger('click');
    await flushPromises();

    const confirmButton = Array.from(document.querySelectorAll('.t-dialog__confirm')).find((b) =>
      b.textContent?.includes('下线'),
    );
    expect(confirmButton, 'confirm dialog should render').toBeTruthy();
    (confirmButton as HTMLElement).click();
    await flushPromises();
    await new Promise((r) => setTimeout(r, 400));

    expect(mockApi.adminSetMcpStatus).toHaveBeenCalledWith(
      '0190-0000-0000-0000-0000000000f2',
      'OFFLINE',
    );
  });

  it('updates the health check configuration', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminUpdateMcpHealthConfig.mockResolvedValue(
      service({ checkIntervalSeconds: 60, checkPath: '/ready' }),
    );

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-health-config"]').trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="mcp-check-path"] input').setValue('/ready');
    await wrapper.find('[data-testid="mcp-config-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUpdateMcpHealthConfig).toHaveBeenCalledWith(
      '0190-0000-0000-0000-0000000000f2',
      {
        checkIntervalSeconds: 30,
        checkTimeoutSeconds: 5,
        failThreshold: 3,
        recoverThreshold: 1,
        checkPath: '/ready',
      },
    );
  });
});
