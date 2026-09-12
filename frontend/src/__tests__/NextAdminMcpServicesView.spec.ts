import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminMcpServicesView from '@/views/next/NextAdminMcpServicesView.vue';
import * as api from '@/api';
import type { McpAccessView, McpServiceView, McpToolView } from '@/types/generated-api';
import { toastState } from '@/ui/toast';

vi.mock('@/api', () => ({
  adminListMcpServices: vi.fn(),
  adminCreateMcpService: vi.fn(),
  adminSetMcpServiceUpstreamTimeout: vi.fn(),
  adminSetMcpStatus: vi.fn(),
  adminUpdateMcpHealthConfig: vi.fn(),
  adminMcpServiceTraffic: vi.fn(),
  adminListMcpTools: vi.fn(),
  adminSyncMcpTools: vi.fn(),
  adminCreateMcpTool: vi.fn(),
  adminSetMcpToolStatus: vi.fn(),
  adminListToolRevisions: vi.fn(),
  getMcpToolRetryPolicy: vi.fn(),
  putMcpToolRetryPolicy: vi.fn(),
  adminActivateToolRevision: vi.fn(),
  adminPublishToolRevision: vi.fn(),
  adminImportMcpTools: vi.fn(),
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
  getMcpServiceResilience: vi.fn(),
  putMcpServiceResilience: vi.fn(),
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
        healthCheckedAt: null as unknown as string,
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
      checkMode: 'HEALTH_PATH',
    });
  });

  it('switches the health probe to JSON-RPC initialize (#387)', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminUpdateMcpHealthConfig.mockResolvedValue(service());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-health-config"]').trigger('click');
    await flushPromises();
    // Probe-shape select renders as stubbed option buttons inside the dialog.
    pickStubOption(document.body, 'JSON-RPC initialize');
    await flushPromises();
    const path = document.querySelector('[data-testid="mcp-check-path"]') as HTMLInputElement;
    expect(path.disabled).toBe(true);

    (document.querySelector('[data-testid="mcp-config-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminUpdateMcpHealthConfig).toHaveBeenCalledWith(
      'm1',
      expect.objectContaining({ checkMode: 'JSONRPC_INITIALIZE' }),
    );
  });

  it('shows the passive real-traffic view with the probe blind-spot hint (#397)', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminMcpServiceTraffic.mockResolvedValue({
      serviceId: 'm1',
      serviceName: 'erp-mcp',
      windowHours: 24,
      totalCalls: 10,
      forwarded: 6,
      denied: 1,
      failed: 3,
      failureRate: 1 / 3,
      lastCallAt: '2026-09-12T01:00:00Z',
      lastFailureAt: '2026-09-12T01:00:00Z',
      topFailingTools: [{ name: 'query_order', failures: 3 }],
    });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-health-config"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminMcpServiceTraffic).toHaveBeenCalledWith('m1', 24);
    const summary = document.querySelector('[data-testid="mcp-traffic-summary"]');
    expect(summary?.textContent).toContain('转发 6');
    expect(summary?.textContent).toContain('33.3%');
    // Probe HEALTHY + real upstream failures → the blind-spot hint renders.
    const hint = document.querySelector('[data-testid="mcp-traffic-hint"]');
    expect(hint?.textContent).toContain('主动探测通过');
    const tools = document.querySelector('[data-testid="mcp-traffic-tools"]');
    expect(tools?.textContent).toContain('query_order');
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
        tools: [
          { toolId: 't1', toolName: 'query_order', mode: null as unknown as 'NONE' | 'ALLOW' | 'DENY', consumers: [] },
        ],
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
        description: null as unknown as string,
        priority: 0,
        pathMode: null as unknown as string,
        pathValue: null as unknown as string,
        hostMode: null as unknown as string,
        hostValue: null as unknown as string,
        methods: null as unknown as string,
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
        matchExpression:
          '路径 PREFIX /api/v2 · Host EXACT mcp-prod.example.com · 方法 GET,POST · 头 X-Tenant-Id EXACT acme',
      },
    ]);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-routes"]').trigger('click');
    await flushPromises();

    const drawer = document.querySelector('[data-testid="mcp-routes-drawer"]');
    expect(drawer, 'routes drawer should render').toBeTruthy();
    expect(document.querySelector('[data-testid="mcp-route-expr-r2"]')?.textContent).toContain(
      '路径 PREFIX /api/v2',
    );
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
      hostMode: undefined, // absent host conditions: hub contract sends omitted keys
      hostValue: undefined, // (server default = match any host)
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
      description: null as unknown as string,
      priority: 1500,
      pathMode: 'PREFIX',
      pathValue: '/api/v2',
      hostMode: null as unknown as string,
      hostValue: null as unknown as string,
      methods: 'GET',
      headerConditions: [],
      status: 'ENABLED',
      version: 1,
      createdAt: '2026-09-02T00:00:00Z',
    };
    mockApi.adminListMcpRouteRules.mockResolvedValue([
      { ...custom, id: 'd1', name: 'default', priority: 0, methods: null as unknown as string, status: 'ENABLED' },
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
  it('opens the resilience drawer with the stored policy', async () => {
    const service = {
      id: 'svc-1',
      name: 'weather-mcp',
      status: 'ONLINE',
    } as McpServiceView;
    mockApi.adminListMcpServices.mockResolvedValue([service]);
    mockApi.getMcpServiceResilience.mockResolvedValue({
      retryEnabled: true,
      retryMax: 2,
      retryConditions: ['SERVER_5XX'],
      idempotencyConfirmed: true,
      breakerEnabled: false,
      breakerWindowSeconds: 10,
      breakerMinRequests: 10,
      breakerErrorEnabled: true,
      breakerErrorRatio: 50,
      breakerErrorStatusCodes: [500, 502, 503, 504],
      breakerSlowEnabled: false,
      breakerSlowCallMs: 3000,
      breakerSlowRatio: 80,
      breakerOpenSeconds: 30,
      breakerProbeCount: 3,
      breakerProbeSuccess: 2,
      breakerSkipRetry: true,
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-resilience"]').trigger('click');
    await flushPromises();
    expect(mockApi.getMcpServiceResilience).toHaveBeenCalledWith('svc-1');
    const drawer = document.querySelector('[data-testid="mcp-resilience-drawer"]');
    expect(drawer?.textContent).toContain('韧性配置 · weather-mcp');
    expect(drawer?.textContent).toContain('已确认后端接口幂等');
  });

  it('saves the edited resilience policy', async () => {
    const service = { id: 'svc-1', name: 'weather-mcp', status: 'ONLINE' } as McpServiceView;
    mockApi.adminListMcpServices.mockResolvedValue([service]);
    mockApi.getMcpServiceResilience.mockResolvedValue({
      retryEnabled: false,
      retryMax: 1,
      retryConditions: [],
      idempotencyConfirmed: false,
      breakerEnabled: false,
      breakerWindowSeconds: 10,
      breakerMinRequests: 10,
      breakerErrorEnabled: true,
      breakerErrorRatio: 50,
      breakerErrorStatusCodes: [500, 502, 503, 504],
      breakerSlowEnabled: false,
      breakerSlowCallMs: 3000,
      breakerSlowRatio: 80,
      breakerOpenSeconds: 30,
      breakerProbeCount: 3,
      breakerProbeSuccess: 2,
      breakerSkipRetry: true,
    });
    mockApi.putMcpServiceResilience.mockResolvedValue({
      retryEnabled: true,
      retryMax: 1,
      retryConditions: ['CONNECTION_FAILURE'],
      idempotencyConfirmed: false,
      breakerEnabled: true,
      breakerWindowSeconds: 10,
      breakerMinRequests: 10,
      breakerErrorEnabled: true,
      breakerErrorRatio: 50,
      breakerErrorStatusCodes: [500, 502, 503, 504],
      breakerSlowEnabled: false,
      breakerSlowCallMs: 3000,
      breakerSlowRatio: 80,
      breakerOpenSeconds: 30,
      breakerProbeCount: 3,
      breakerProbeSuccess: 2,
      breakerSkipRetry: true,
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-resilience"]').trigger('click');
    await flushPromises();
    const drawerEl = document.querySelector('[data-testid="mcp-resilience-drawer"]') as HTMLElement;
    const findInDrawer = (testId: string) => {
      const el = drawerEl.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
      expect(el, testId).toBeTruthy();
      return el;
    };
    (findInDrawer('mcp-res-retry-enabled') as HTMLInputElement).click();
    await nextTick();
    (findInDrawer('mcp-res-idempotent') as HTMLInputElement).click();
    await nextTick();
    const retryMax = findInDrawer('mcp-res-retry-max');
    retryMax.value = '1';
    retryMax.dispatchEvent(new Event('input', { bubbles: true }));
    (findInDrawer('mcp-res-breaker-enabled') as HTMLInputElement).click();
    await nextTick();
    const save = drawerEl.querySelector('[data-testid="mcp-resilience-save"]') as HTMLElement;
    expect(save).toBeTruthy();
    save.click();
    await flushPromises();
    expect(mockApi.putMcpServiceResilience).toHaveBeenCalledTimes(1);
    const body = mockApi.putMcpServiceResilience.mock.calls[0]![1];
    expect(body.retryEnabled).toBe(true);
    expect(body.idempotencyConfirmed).toBe(true);
    expect(body.breakerEnabled).toBe(true);
    expect(body.breakerErrorStatusCodes).toContain(500);
  });

  it('F16: shows revision history and rolls back through the confirm dialog', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpTools.mockResolvedValue([tool()]);
    const rev1 = {
      id: 'r1',
      revision: 1,
      description: '查询订单',
      method: 'GET',
      path: '/orders/{id}',
      createdAt: '2026-09-01T00:00:00Z',
      activatedAt: '2026-09-01T00:00:01Z',
    };
    const rev2 = {
      id: 'r2',
      revision: 2,
      description: '查询订单 v2',
      method: 'POST',
      path: '/orders/v2/{id}',
      createdAt: '2026-09-02T00:00:00Z',
      activatedAt: null as unknown as string,
      changedFields: ['description', 'method', 'path'],
    };
    mockApi.adminListToolRevisions.mockResolvedValue([rev2, rev1]);
    mockApi.adminActivateToolRevision.mockResolvedValue(rev1);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-tools"]').trigger('click');
    await flushPromises();
    (
      document.querySelector('[data-testid="mcp-tool-revisions-open"]') as HTMLButtonElement
    ).click();
    await flushPromises();

    const dialog = document.querySelector('[data-testid="mcp-tool-revisions-dialog"]');
    expect(dialog, 'revisions dialog should open').toBeTruthy();
    expect(dialog!.textContent).toContain('#2');
    expect(dialog!.textContent).toContain('已生效');
    expect(dialog!.textContent).toContain('#1');
    expect(dialog!.textContent).toContain('历史');
    const diff = document.querySelector('[data-testid="mcp-rev-diff-2"]');
    expect(diff?.textContent).toContain('描述');
    expect(diff?.textContent).toContain('方法');
    expect(diff?.textContent).toContain('路径');
    expect(document.querySelector('[data-testid="mcp-rev-baseline-1"]')?.textContent).toContain(
      '初始版本',
    );

    (document.querySelector('[data-testid="mcp-rev-rollback-2"]') as HTMLButtonElement).click();
    await flushPromises();
    const buttons = Array.from(document.body.querySelectorAll('button')).filter(
      (b) => b.textContent?.trim() === '回滚',
    );
    expect(buttons.length).toBeGreaterThan(0);
    buttons[buttons.length - 1]!.click();
    await flushPromises();

    expect(mockApi.adminActivateToolRevision).toHaveBeenCalledWith('m1', 't1', 2);
    expect(toastState.items.some((item) => item.message?.includes('已回滚到修订 #2'))).toBe(true);
  });

  it('F16: publishes an edited revision from the tool row', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpTools.mockResolvedValue([tool()]);
    mockApi.adminPublishToolRevision.mockResolvedValue({
      id: 'r2',
      revision: 2,
      description: '查询订单（已改）',
      method: 'POST',
      path: '/orders/{id}',
      createdAt: '2026-09-08T00:00:00Z',
      activatedAt: '2026-09-08T00:00:01Z',
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-tools"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="mcp-tool-edit-open"]') as HTMLButtonElement).click();
    await flushPromises();
    const dialog = document.querySelector('[data-testid="mcp-tool-edit-dialog"]');
    expect(dialog, 'edit dialog should open').toBeTruthy();
    const setEdit = (testid: string, value: string) => {
      const el = document.querySelector(`[data-testid="${testid}"]`) as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
      setter?.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    setEdit('mcp-tool-edit-path', '/orders/{id}');
    setEdit('mcp-tool-edit-desc', '查询订单（已改）');
    (document.querySelector('[data-testid="mcp-tool-edit-submit"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.adminPublishToolRevision).toHaveBeenCalledWith('m1', 't1', {
      description: '查询订单（已改）',
      method: 'GET',
      path: '/orders/{id}',
    });
  });

  it('F17: imports tools from a pasted OpenAPI document', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpTools.mockResolvedValue([]);
    mockApi.adminImportMcpTools.mockResolvedValue({
      created: [{ toolName: 'list_orders', method: 'GET', path: '/orders' }],
      skipped: [],
      parseSkips: [{ toolName: '', reason: 'HTTP 方法不支持：TRACE' }],
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-tools"]').trigger('click');
    await flushPromises();
    (document.querySelector('[data-testid="mcp-tool-import-open"]') as HTMLButtonElement).click();
    await flushPromises();
    const textarea = document.querySelector(
      '[data-testid="mcp-tool-import-spec"]',
    ) as HTMLTextAreaElement;
    textarea.value = JSON.stringify({
      paths: { '/orders': { get: { operationId: 'listOrders' } } },
    });
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    (document.querySelector('[data-testid="mcp-tool-import-submit"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.adminImportMcpTools).toHaveBeenCalledTimes(1);
    const result = document.querySelector('[data-testid="mcp-tool-import-result"]');
    expect(result, 'import result should render').toBeTruthy();
    expect(result!.textContent).toContain('新建 1');
    expect(result!.textContent).toContain('TRACE');
  });

  it('I13: loads and saves the tool-level retry override', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpTools.mockResolvedValue([tool()]);
    mockApi.getMcpToolRetryPolicy.mockResolvedValue({
      retryEnabled: true,
      retryMax: 2,
      retryConditions: ['SERVER_5XX'],
      idempotencyConfirmed: true,
      version: 3,
    });
    mockApi.putMcpToolRetryPolicy.mockResolvedValue({
      retryEnabled: true,
      retryMax: 2,
      retryConditions: ['SERVER_5XX', 'TIMEOUT'],
      idempotencyConfirmed: false,
      version: 4,
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-tools"]').trigger('click');
    await flushPromises();

    (document.querySelector('[data-testid="mcp-tool-retry-open"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.getMcpToolRetryPolicy).toHaveBeenCalledWith('m1', 't1');
    const dialog = document.querySelector('[data-testid="mcp-tool-retry-dialog"]');
    expect(dialog, 'retry dialog should render').toBeTruthy();
    expect((document.querySelector('[data-testid="mcp-tool-retry-max"]') as HTMLInputElement).value).toBe(
      '2',
    );

    (document.querySelector('[data-testid="mcp-tool-retry-timeout"]') as HTMLInputElement).click();
    await flushPromises();
    (document.querySelector('[data-testid="mcp-tool-retry-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.putMcpToolRetryPolicy).toHaveBeenCalledWith('m1', 't1', {
      retryEnabled: true,
      retryMax: 2,
      retryConditions: ['SERVER_5XX', 'TIMEOUT'],
      idempotencyConfirmed: true,
    });
  });

  it('previews the upstream tools/list sync and applies it on confirm', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminListMcpTools.mockResolvedValue([]);
    const preview = {
      dryRun: true,
      upstreamToolCount: 2,
      added: ['alpha', 'beta'],
      updated: [],
      unchanged: 0,
      absentUpstream: [],
      skipped: [],
    };
    mockApi.adminSyncMcpTools
      .mockResolvedValueOnce(preview)
      .mockResolvedValueOnce({ ...preview, dryRun: false });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-tools"]').trigger('click');
    await flushPromises();

    (document.querySelector('[data-testid="mcp-tool-sync"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.adminSyncMcpTools).toHaveBeenCalledWith('m1', true);
    const report = document.querySelector('[data-testid="mcp-tool-sync-report"]');
    expect(report, 'sync report should render').toBeTruthy();
    expect(report!.textContent).toContain('alpha');
    expect(report!.textContent).toContain('预览');

    (document.querySelector('[data-testid="mcp-tool-sync-apply"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.adminSyncMcpTools).toHaveBeenLastCalledWith('m1', false);
    expect(mockApi.adminListMcpTools).toHaveBeenCalledTimes(2);
    expect(
      document.querySelector('[data-testid="mcp-tool-sync-apply"]'),
      'apply button hides after the preview turns into an applied report',
    ).toBeNull();
  });

  it('edits the upstream budget from the row dialog (I20 follow-up)', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    mockApi.adminSetMcpServiceUpstreamTimeout.mockResolvedValue(service());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-upstream-timeout"]').trigger('click');
    await flushPromises();
    // Dialogs teleport to body.
    const input = document.querySelector('[data-testid="mcp-timeout-input"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(input.value).toBe('60000');

    const setter = Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      'value',
    )?.set;
    setter?.call(input, '20000');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    (document.querySelector('[data-testid="mcp-timeout-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminSetMcpServiceUpstreamTimeout).toHaveBeenCalledWith('m1', 20000);
  });

  it('rejects an out-of-range budget locally (I20 follow-up)', async () => {
    mockApi.adminListMcpServices.mockResolvedValue([service()]);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="mcp-upstream-timeout"]').trigger('click');
    await flushPromises();
    const input = document.querySelector('[data-testid="mcp-timeout-input"]') as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      'value',
    )?.set;
    setter?.call(input, '500');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    (document.querySelector('[data-testid="mcp-timeout-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminSetMcpServiceUpstreamTimeout).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('1000–600000');
  });
});
