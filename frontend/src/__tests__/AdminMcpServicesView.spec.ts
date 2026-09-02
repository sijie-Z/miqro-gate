import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminMcpServicesView from '@/views/AdminMcpServicesView.vue';
import * as api from '@/api';
import type { ApiConsumerView, McpAccessView, McpServiceView } from '@/types/api';

vi.mock('@/api', () => ({
  adminListMcpServices: vi.fn(),
  adminCreateMcpService: vi.fn(),
  adminSetMcpStatus: vi.fn(),
  adminUpdateMcpHealthConfig: vi.fn(),
  adminListMcpTools: vi.fn(),
  adminCreateMcpTool: vi.fn(),
  adminSetMcpToolStatus: vi.fn(),
  getMcpServiceAccess: vi.fn(),
  setMcpAccessMode: vi.fn(),
  setMcpAccessGrants: vi.fn(),
  clearMcpAccessGrants: vi.fn(),
  listApiConsumers: vi.fn(),
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

const PopupStub = defineComponent({
  name: 'TPopup',
  inheritAttrs: false,
  setup(_, { slots, expose }) {
    expose({
      update: () => {},
      getOverlay: () => null,
      getOverlayState: () => ({ hover: false }),
      getPopper: () => null,
      close: () => {},
    });
    return () => h('div', { class: 't-popup-stub' }, [slots.default?.(), slots.content?.()]);
  },
});

const consumer = (overrides: Partial<ApiConsumerView> = {}): ApiConsumerView => ({
  id: 'c1',
  name: 'platform-billing',
  keyPrefix: 'mqka_8f2a',
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

const accessView = (overrides: Partial<McpAccessView> = {}): McpAccessView => ({
  serviceId: '0190-0000-0000-0000-0000000000f2',
  serviceName: 'erp-mcp',
  mode: 'NONE',
  serverConsumers: [],
  tools: [{ toolId: 't1', toolName: 'query_order', mode: null, consumers: [] }],
  ...overrides,
});

function mountView() {
  return mount(AdminMcpServicesView, {
    global: { plugins: [TDesign, createPinia()], stubs: { TPopup: PopupStub } },
  });
}

describe('AdminMcpServicesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListMcpServices.mockResolvedValue([]);
    mockApi.adminListMcpTools.mockResolvedValue([]);
    mockApi.getMcpServiceAccess.mockResolvedValue(accessView());
    mockApi.setMcpAccessMode.mockResolvedValue(accessView());
    mockApi.setMcpAccessGrants.mockResolvedValue(accessView());
    mockApi.clearMcpAccessGrants.mockResolvedValue(accessView());
    mockApi.listApiConsumers.mockResolvedValue([consumer()]);
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

  it('lists tools and disables one from the tools dialog', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpTools.mockResolvedValue([
      {
        id: 't1',
        mcpServiceId: '0190-0000-0000-0000-0000000000f2',
        toolName: 'query_order',
        description: '查询订单',
        method: 'GET',
        path: '/orders/{id}',
        status: 'ENABLED',
        createdAt: '2026-09-01T00:00:00Z',
      },
    ]);
    mockApi.adminSetMcpToolStatus.mockResolvedValue({
      id: 't1',
      mcpServiceId: '0190-0000-0000-0000-0000000000f2',
      toolName: 'query_order',
      description: '查询订单',
      method: 'GET',
      path: '/orders/{id}',
      status: 'DISABLED',
      createdAt: '2026-09-01T00:00:00Z',
    });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-tools"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="mcp-tool-list"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('query_order');
    expect(wrapper.text()).toContain('/orders/{id}');

    await wrapper.find('[data-testid="mcp-tool-disable"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminSetMcpToolStatus).toHaveBeenCalledWith(
      '0190-0000-0000-0000-0000000000f2',
      't1',
      'DISABLED',
    );
  });

  it('creates a tool from the tools dialog', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpTools.mockResolvedValue([]);
    mockApi.adminCreateMcpTool.mockResolvedValue({
      id: 't1',
      mcpServiceId: '0190-0000-0000-0000-0000000000f2',
      toolName: 'create_order',
      description: undefined,
      method: 'POST',
      path: '/orders',
      status: 'ENABLED',
      createdAt: '2026-09-01T00:00:00Z',
    });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-tools"]').trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="mcp-tool-create-open"]').trigger('click');
    await wrapper.find('[data-testid="mcp-tool-name"] input').setValue('create_order');
    await wrapper.find('[data-testid="mcp-tool-path"] input').setValue('/orders');
    await wrapper.find('[data-testid="mcp-tool-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminCreateMcpTool).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000f2', {
      toolName: 'create_order',
      description: undefined,
      method: 'GET',
      path: '/orders',
    });
  });

  it('opens access control showing the mode, notice and tool rows', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-access"]').trigger('click');
    await flushPromises();

    expect(mockApi.getMcpServiceAccess).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000f2');
    expect(wrapper.text()).toContain('全部开放');
    expect(wrapper.text()).toContain('query_order');
    expect(wrapper.text()).toContain('继承服务级规则');
  });

  it('saves the server whitelist from the loaded list', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.getMcpServiceAccess.mockResolvedValue(
      accessView({ mode: 'ALLOW', serverConsumers: [{ id: 'c1', name: 'platform-billing' }] }),
    );

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-access"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('白名单');
    await wrapper.find('[data-testid="mcp-access-server-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.setMcpAccessGrants).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000f2', {
      mode: 'ALLOW',
      consumerIds: ['c1'],
    });
  });

  it('saves a tool override on an open server', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-access"]').trigger('click');
    await flushPromises();

    const allowOption = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('仅名单内可调用'));
    expect(allowOption, 'tool mode option should render').toBeTruthy();
    await allowOption!.trigger('click');
    await flushPromises();

    const consumerOption = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('platform-billing'));
    expect(consumerOption, 'consumer option should render').toBeTruthy();
    await consumerOption!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="mcp-access-tool-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.setMcpAccessGrants).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000f2', {
      toolId: 't1',
      mode: 'ALLOW',
      consumerIds: ['c1'],
    });
  });
});
