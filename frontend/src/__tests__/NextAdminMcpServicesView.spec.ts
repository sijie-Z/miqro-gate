import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminMcpServicesView from '@/views/next/NextAdminMcpServicesView.vue';
import * as api from '@/api';
import type { McpAccessView, McpServiceView, McpToolView } from '@/types/api';

vi.mock('@/api', () => ({
  adminListMcpServices: vi.fn(),
  adminCreateMcpService: vi.fn(),
  adminSetMcpStatus: vi.fn(),
  adminUpdateMcpHealthConfig: vi.fn(),
  adminListMcpTools: vi.fn(),
  adminCreateMcpTool: vi.fn(),
  adminSetMcpToolStatus: vi.fn(),
  getMcpServiceAccess: vi.fn(),
  listApiConsumers: vi.fn(),
  setMcpAccessMode: vi.fn(),
  setMcpAccessGrants: vi.fn(),
  clearMcpAccessGrants: vi.fn(),
  adminListMcpRouteRules: vi.fn(),
  adminCreateMcpRouteRule: vi.fn(),
  adminUpdateMcpRouteRule: vi.fn(),
  adminSetMcpRouteStatus: vi.fn(),
  adminDeleteMcpRouteRule: vi.fn(),
}));

const mockApi = vi.mocked(api);

const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
    placeholder: { type: String, default: '' },
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit }) {
    function pick(value: unknown) {
      emit('update:modelValue', value);
      emit('change', value);
    }
    return { pick, props };
  },
  template: `
    <div class="ui-select-stub">
      <label v-if="props.label">{{ props.label }}</label>
      <button
        v-for="opt in props.options"
        :key="opt.value"
        type="button"
        class="stub-option"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>
  `,
});

const service = (overrides: Partial<McpServiceView> = {}): McpServiceView => ({
  id: 'm1',
  name: 'erp-mcp',
  description: 'ERP 查询服务',
  endpoint: 'https://erp.internal.example',
  transport: 'STREAMABLE_HTTP',
  status: 'ONLINE',
  healthStatus: 'HEALTHY',
  healthCheckedAt: '2026-09-02T00:00:00Z',
  checkIntervalSeconds: 30,
  checkTimeoutSeconds: 5,
  failThreshold: 3,
  recoverThreshold: 1,
  checkPath: '/health',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

const tool = (overrides: Partial<McpToolView> = {}): McpToolView => ({
  id: 't1',
  mcpServiceId: 'm1',
  toolName: 'query_order',
  description: '查询订单',
  method: 'GET',
  path: '/orders/{id}',
  status: 'ENABLED',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

const consumers = [
  {
    id: 'c1',
    name: 'billing-sync',
    keyPrefix: 'mk_bil_',
    status: 'ACTIVE',
    createdAt: '2026-08-01T00:00:00Z',
  },
  {
    id: 'c2',
    name: 'analytics-etl',
    keyPrefix: 'mk_ana_',
    status: 'ACTIVE',
    createdAt: '2026-08-02T00:00:00Z',
  },
];

const accessView = (overrides: Partial<McpAccessView> = {}): McpAccessView => ({
  serviceId: 'm1',
  serviceName: 'erp-mcp',
  mode: 'NONE',
  serverConsumers: [],
  tools: [],
  ...overrides,
});

describe('NextAdminMcpServicesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListMcpServices.mockResolvedValue([]);
    mockApi.listApiConsumers.mockResolvedValue(consumers);
    mockApi.adminListMcpRouteRules.mockResolvedValue([]);
  });

  function mountView() {
    return mount(NextAdminMcpServicesView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  function pickStubOption(container: HTMLElement | Element, label: string) {
    const options = Array.from(container.querySelectorAll('.stub-option')) as HTMLButtonElement[];
    const option = options.find((o) => o.textContent?.trim() === label);
    expect(option, `option ${label} should exist`).toBeTruthy();
    option!.click();
  }

  it('renders MCP services with Chinese status/health labels', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([
      service(),
      service({
        id: 'm2',
        name: 'search-mcp',
        status: 'OFFLINE',
        healthStatus: 'UNHEALTHY',
        healthCheckedAt: null,
      }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="mcp-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('erp-mcp');
    expect(wrapper.text()).toContain('STREAMABLE_HTTP');
    expect(wrapper.text()).toContain('在线');
    expect(wrapper.text()).toContain('健康');
    expect(wrapper.text()).toContain('已下线');
    expect(wrapper.text()).toContain('不健康');
  });

  it('registers an SSE service', async () => {
    mockApi.adminCreateMcpService.mockResolvedValue(service());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-create-open"]').trigger('click');
    await wrapper.find('[data-testid="mcp-create-name"]').setValue('events-mcp');
    pickStubOption(wrapper.element, 'SSE');
    await wrapper
      .find('[data-testid="mcp-create-endpoint"]')
      .setValue('https://events.internal.example');
    await wrapper.find('[data-testid="mcp-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminCreateMcpService).toHaveBeenCalledWith({
      name: 'events-mcp',
      description: undefined,
      endpoint: 'https://events.internal.example',
      transport: 'SSE',
    });
  });

  it('takes a service offline through the confirm gate', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminSetMcpStatus.mockResolvedValue(service({ status: 'OFFLINE' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-offline"]').trigger('click');
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '下线' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm, 'confirm dialog should render').toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.adminSetMcpStatus).toHaveBeenCalledWith('m1', 'OFFLINE');
  });

  it('saves the health-check configuration with numeric values', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminUpdateMcpHealthConfig.mockResolvedValue(service());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-health-config"]').trigger('click');
    await flushPromises();
    const setInput = (testid: string, value: string) => {
      const el = document.querySelector(`[data-testid="${testid}"]`) as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    setInput('mcp-check-interval', '60');
    setInput('mcp-check-timeout', '10');
    setInput('mcp-check-fail', '5');
    setInput('mcp-check-recover', '2');
    setInput('mcp-check-path', '/readyz');
    await flushPromises();
    (document.querySelector('[data-testid="mcp-config-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminUpdateMcpHealthConfig).toHaveBeenCalledWith('m1', {
      checkIntervalSeconds: 60,
      checkTimeoutSeconds: 10,
      failThreshold: 5,
      recoverThreshold: 2,
      checkPath: '/readyz',
    });
  });

  it('lists tools and creates one with the chosen method', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpTools.mockResolvedValue([tool()]);
    mockApi.adminCreateMcpTool.mockResolvedValue(
      tool({ toolName: 'list_orders', path: '/orders' }),
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-tools"]').trigger('click');
    await flushPromises();
    const list = document.querySelector('[data-testid="mcp-tool-list"]');
    expect(list, 'tool list should render').toBeTruthy();
    expect(list!.textContent).toContain('query_order');
    expect(list!.textContent).toContain('GET /orders/{id}');
    expect(list!.textContent).toContain('已启用');

    (document.querySelector('[data-testid="mcp-tool-create-open"]') as HTMLButtonElement).click();
    await flushPromises();
    const setInput = (testid: string, value: string) => {
      const el = document.querySelector(`[data-testid="${testid}"]`) as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    setInput('mcp-tool-name', 'list_orders');
    setInput('mcp-tool-path', '/orders');
    const methodStub = document.querySelectorAll('.ui-select-stub')[0] as HTMLElement;
    pickStubOption(methodStub, 'POST');
    await flushPromises();
    (document.querySelector('[data-testid="mcp-tool-create-submit"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminCreateMcpTool).toHaveBeenCalledWith('m1', {
      toolName: 'list_orders',
      description: undefined,
      method: 'POST',
      path: '/orders',
    });
  });

  it('saves a server allowlist with checked consumers', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.getMcpServiceAccess.mockResolvedValue(
      accessView({
        mode: 'ALLOW',
        serverConsumers: [{ id: 'c1', name: 'billing-sync' }],
        tools: [],
      }),
    );
    mockApi.setMcpAccessGrants.mockResolvedValue(accessView({ mode: 'ALLOW' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-access"]').trigger('click');
    await flushPromises();
    const dialog = document.querySelector('[data-testid="mcp-access-dialog"]');
    expect(dialog, 'access dialog should render').toBeTruthy();
    expect(dialog!.textContent).toContain('白名单：仅名单内的 API 消费者可调用该服务。');

    // c1 is pre-seeded from the server view; add c2 via the checkbox list.
    const checkboxes = Array.from(
      document.querySelectorAll('[data-testid="mcp-access-server-list"] input'),
    ) as HTMLInputElement[];
    expect(checkboxes).toHaveLength(2);
    expect(checkboxes.find((c) => c.value === 'c1')!.checked).toBe(true);
    const c2 = checkboxes.find((c) => c.value === 'c2')!;
    c2.checked = true;
    c2.dispatchEvent(new Event('change', { bubbles: true }));
    await flushPromises();
    (document.querySelector('[data-testid="mcp-access-server-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.setMcpAccessGrants).toHaveBeenCalledWith('m1', {
      mode: 'ALLOW',
      consumerIds: ['c1', 'c2'],
    });
  });

  it('saves a per-tool DENY override while the server stays open', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.getMcpServiceAccess.mockResolvedValue(
      accessView({
        mode: 'NONE',
        serverConsumers: [],
        tools: [{ toolId: 't1', toolName: 'query_order', mode: null, consumers: [] }],
      }),
    );
    mockApi.setMcpAccessGrants.mockResolvedValue(accessView({ mode: 'NONE' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-access"]').trigger('click');
    await flushPromises();
    const dialog = document.querySelector('[data-testid="mcp-access-dialog"]');
    expect(dialog!.textContent).toContain('全部开放：任何调用方均可访问');

    const toolRow = document.querySelector('[data-tool-id="t1"]') as HTMLElement;
    expect(toolRow, 'tool row should render').toBeTruthy();
    // Switch the per-tool mode to 名单内禁止 (DENY).
    const segs = Array.from(toolRow.querySelectorAll('.next-mcp__seg')) as HTMLButtonElement[];
    const deny = segs.find((b) => b.textContent?.trim() === '名单内禁止')!;
    deny.click();
    await flushPromises();
    const checks = Array.from(
      toolRow.querySelectorAll('[data-testid="mcp-tool-consumer"]'),
    ) as HTMLInputElement[];
    expect(checks).toHaveLength(2);
    const c1 = checks.find((c) => c.value === 'c1')!;
    c1.checked = true;
    c1.dispatchEvent(new Event('change', { bubbles: true }));
    await flushPromises();
    (toolRow.querySelector('[data-testid="mcp-access-tool-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.setMcpAccessGrants).toHaveBeenCalledWith('m1', {
      toolId: 't1',
      mode: 'DENY',
      consumerIds: ['c1'],
    });
  });

  it('opens the route rules drawer with the immutable default rule', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpRouteRules.mockResolvedValue([
      {
        id: 'd1',
        mcpServiceId: 'm1',
        name: 'default',
        description: null,
        priority: 0,
        pathMode: null,
        pathValue: null,
        hostMode: null,
        hostValue: null,
        methods: null,
        headerConditions: [],
        status: 'ENABLED',
        version: 0,
        createdAt: '2026-09-01T00:00:00Z',
      },
      {
        id: 'r2',
        mcpServiceId: 'm1',
        name: 'gray-v2',
        description: '灰度 v2',
        priority: 1500,
        pathMode: 'PREFIX',
        pathValue: '/api/v2',
        hostMode: 'EXACT',
        hostValue: 'mcp-prod.example.com',
        methods: 'GET,POST',
        headerConditions: [{ name: 'X-Tenant-Id', mode: 'EXACT', value: 'acme' }],
        status: 'ENABLED',
        version: 1,
        createdAt: '2026-09-02T00:00:00Z',
      },
    ]);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-routes"]').trigger('click');
    await flushPromises();

    const drawer = document.querySelector('[data-testid="mcp-routes-drawer"]');
    expect(drawer, 'routes drawer should render').toBeTruthy();
    expect(drawer!.textContent).toContain('系统默认');
    expect(drawer!.textContent).toContain('gray-v2');
    expect(drawer!.textContent).toContain('GET / POST');
    expect(drawer!.textContent).toContain('全部请求（兜底）');
    // The default row carries no actions; the custom row does.
    const defaultRow = document.querySelector('[data-rule-id="d1"]') as HTMLElement;
    const customRow = document.querySelector('[data-rule-id="r2"]') as HTMLElement;
    expect(defaultRow.querySelector('[data-testid="mcp-route-edit"]')).toBeNull();
    expect(customRow.querySelector('[data-testid="mcp-route-edit"]')).toBeTruthy();
  });

  it('creates a route rule with path/method/header conditions', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminCreateMcpRouteRule.mockResolvedValue({
      id: 'r3',
      mcpServiceId: 'm1',
      name: 'canary',
      priority: 1000,
      pathMode: 'EXACT',
      pathValue: '/api',
      methods: 'POST',
      headerConditions: [{ name: 'X-Tenant-Id', mode: 'EXACT', value: 'acme' }],
      status: 'ENABLED',
      version: 0,
      createdAt: '2026-09-03T00:00:00Z',
    });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-routes"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="mcp-route-create-open"]') as HTMLButtonElement).click();
    await flushPromises();

    const setInput = (testid: string, value: string) => {
      const el = document.querySelector(`[data-testid="${testid}"]`) as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    setInput('mcp-route-name', 'canary');
    // Path: 精确 + value.
    const pathSeg = document.querySelector('[data-testid="mcp-route-path-mode"]') as HTMLElement;
    const exact = Array.from(pathSeg.querySelectorAll('button')).find(
      (b) => b.textContent === '精确',
    )!;
    exact.click();
    await flushPromises();
    setInput('mcp-route-path-value', '/api');
    // Methods: pick POST only.
    const post = Array.from(document.querySelectorAll('[data-testid="mcp-route-method"]')).find(
      (c) => (c as HTMLInputElement).value === 'POST',
    ) as HTMLInputElement;
    post.checked = true;
    post.dispatchEvent(new Event('change', { bubbles: true }));
    // One header condition.
    (document.querySelector('[data-testid="mcp-route-header-add"]') as HTMLButtonElement).click();
    await flushPromises();
    setInput('mcp-route-header-name', 'X-Tenant-Id');
    setInput('mcp-route-header-value', 'acme');
    await flushPromises();
    (document.querySelector('[data-testid="mcp-route-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminCreateMcpRouteRule).toHaveBeenCalledWith('m1', {
      name: 'canary',
      description: undefined,
      priority: 1000,
      pathMode: 'EXACT',
      pathValue: '/api',
      hostMode: null,
      hostValue: null,
      methods: ['POST'],
      headers: [{ name: 'X-Tenant-Id', mode: 'EXACT', value: 'acme' }],
    });
  });

  it('disables and deletes a custom rule through gates', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    const custom = {
      id: 'r2',
      mcpServiceId: 'm1',
      name: 'gray-v2',
      description: null,
      priority: 1500,
      pathMode: 'PREFIX',
      pathValue: '/api/v2',
      hostMode: null,
      hostValue: null,
      methods: 'GET',
      headerConditions: [],
      status: 'ENABLED',
      version: 1,
      createdAt: '2026-09-02T00:00:00Z',
    };
    mockApi.adminListMcpRouteRules.mockResolvedValue([
      { ...custom, id: 'd1', name: 'default', priority: 0, methods: null, status: 'ENABLED' },
      custom,
    ]);
    mockApi.adminSetMcpRouteStatus.mockResolvedValue({ ...custom, status: 'DISABLED' });
    mockApi.adminDeleteMcpRouteRule.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-routes"]').trigger('click');
    await flushPromises();
    const customRow = document.querySelector('[data-rule-id="r2"]') as HTMLElement;
    const disable = Array.from(customRow.querySelectorAll('button')).find(
      (b) => b.textContent === '停用',
    ) as HTMLButtonElement;
    disable.click();
    await flushPromises();
    expect(mockApi.adminSetMcpRouteStatus).toHaveBeenCalledWith('m1', 'r2', 'DISABLED');

    (customRow.querySelector('[data-testid="mcp-route-delete"]') as HTMLButtonElement).click();
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '删除' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm, 'delete gate should render').toBeTruthy();
    confirm!.click();
    await flushPromises();
    expect(mockApi.adminDeleteMcpRouteRule).toHaveBeenCalledWith('m1', 'r2');
  });
});
