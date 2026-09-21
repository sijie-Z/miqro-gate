import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminMcpAccessLogsView from '@/views/next/NextAdminMcpAccessLogsView.vue';
import * as api from '@/api';
import type { McpAccessLogEntry } from '@/types/generated-api';

vi.mock('@/api', () => ({ listMcpAccessLogs: vi.fn() }));
const mockApi = vi.mocked(api);

const rows: McpAccessLogEntry[] = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    serviceName: 'weather-mcp',
    consumerName: 'drill-allowed',
    rpcMethod: 'tools/call',
    toolName: 'forecast',
    status: 'FORWARDED',
    httpStatus: 200,
    sessionId: 'sess-log-1',
    ttfbMs: 42,
    gatewayRequestId: 'req-0001',
    occurredAt: '2026-09-05T08:00:00Z',
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    serviceName: 'weather-mcp',
    consumerName: 'drill-outside',
    rpcMethod: 'tools/list',
    toolName: null as unknown as string,
    status: 'SERVICE_DENIED',
    httpStatus: 403,
    gatewayRequestId: 'req-0002',
    occurredAt: '2026-09-05T07:59:00Z',
  },
];

/** radix-based UiSelect renders through a portal; the stub keeps options clickable. */
const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit }) {
    return {
      props,
      pick: (value: unknown) => {
        emit('update:modelValue', value);
        emit('change', value);
      },
    };
  },
  template: `
    <div class="ui-select-stub">
      <button
        v-for="opt in props.options"
        :key="opt.value"
        :data-testid="'mcp-opt-' + opt.value"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>`,
});

describe('NextAdminMcpAccessLogsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listMcpAccessLogs.mockResolvedValue(rows);
  });
  function mountView() {
    return mount(NextAdminMcpAccessLogsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }
  it('renders log rows with outcome badges and metadata', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="mcp-logs-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('weather-mcp');
    expect(wrapper.text()).toContain('drill-allowed');
    expect(wrapper.text()).toContain('已转发');
    expect(wrapper.text()).toContain('sess-log-1');
    expect(wrapper.text()).toContain('42 ms');
    expect(wrapper.text()).toContain('服务被拒');
    expect(wrapper.text()).toContain('tools/call');
    expect(wrapper.text()).toContain('200');
    expect(wrapper.text()).toContain('共 2 条');
    expect(mockApi.listMcpAccessLogs).toHaveBeenCalledTimes(1);
  });
  it('filters by service and consumer', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-logs-service-filter"]').setValue('weather-mcp');
    await wrapper.find('[data-testid="mcp-logs-consumer-filter"]').setValue('drill-allowed');
    await wrapper.find('[data-testid="mcp-logs-query"]').trigger('click');
    await flushPromises();
    expect(mockApi.listMcpAccessLogs).toHaveBeenLastCalledWith({
      service: 'weather-mcp',
      consumer: 'drill-allowed',
      limit: 200,
    });
  });
  it('reset clears filters and reloads', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-logs-service-filter"]').setValue('weather-mcp');
    await wrapper.find('[data-testid="mcp-logs-reset"]').trigger('click');
    await flushPromises();
    expect(mockApi.listMcpAccessLogs).toHaveBeenLastCalledWith({ limit: 200 });
    const input = wrapper.find('[data-testid="mcp-logs-service-filter"]')
      .element as HTMLInputElement;
    expect(input.value).toBe('');
  });
  it('renders time-range and limit controls', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="mcp-logs-from"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="mcp-logs-to"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="mcp-logs-limit"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="mcp-logs-range-7"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="mcp-logs-range-30"]').exists()).toBe(true);
  });
  it('sends an ISO time range and the chosen limit with the query', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-logs-from"]').setValue('2026-09-01T08:00');
    await wrapper.find('[data-testid="mcp-logs-to"]').setValue('2026-09-02T08:00');
    await wrapper.find('[data-testid="mcp-opt-500"]').trigger('click');
    await wrapper.find('[data-testid="mcp-logs-query"]').trigger('click');
    await flushPromises();
    expect(mockApi.listMcpAccessLogs).toHaveBeenLastCalledWith({
      from: new Date('2026-09-01T08:00').toISOString(),
      to: new Date('2026-09-02T08:00').toISOString(),
      limit: 500,
    });
  });
  it('the 7-day quick range fills the window and queries', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-logs-range-7"]').trigger('click');
    await flushPromises();
    const calls = mockApi.listMcpAccessLogs.mock.calls;
    const last = calls[calls.length - 1]?.[0];
    expect(last?.limit).toBe(200);
    const from = new Date(last?.from as string).getTime();
    const to = new Date(last?.to as string).getTime();
    expect(Math.abs(to - Date.now())).toBeLessThan(60_000);
    expect(Math.abs(to - from - 7 * 24 * 3600 * 1000)).toBeLessThan(60_000);
  });
  it('reset clears the range and restores the default limit', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-logs-from"]').setValue('2026-09-01T08:00');
    await wrapper.find('[data-testid="mcp-opt-1000"]').trigger('click');
    await wrapper.find('[data-testid="mcp-logs-reset"]').trigger('click');
    await flushPromises();
    expect(mockApi.listMcpAccessLogs).toHaveBeenLastCalledWith({ limit: 200 });
    const from = wrapper.find('[data-testid="mcp-logs-from"]').element as HTMLInputElement;
    expect(from.value).toBe('');
  });

  it('keeps the newer quick window when a slower older one answers last (#1231)', async () => {
    const wrapper = mountView();
    await flushPromises();

    // Second phase: two quick ranges in flight at once — 近 30 天 is issued
    // first, 近 7 天 (the user's final pick) answers first.
    const pending: Array<{ resolve: (v: McpAccessLogEntry[]) => void }> = [];
    mockApi.listMcpAccessLogs.mockImplementation(
      () =>
        new Promise<McpAccessLogEntry[]>((resolve) => {
          pending.push({ resolve });
        }),
    );

    await wrapper.find('[data-testid="mcp-logs-range-30"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="mcp-logs-range-7"]').trigger('click');
    await flushPromises();
    expect(pending).toHaveLength(2);

    const row = (marker: string): McpAccessLogEntry => ({
      ...rows[0]!,
      consumerName: marker,
      gatewayRequestId: `req-${marker}`,
    });

    // The 近7天 answer lands first — it is the window the user selected last.
    pending[1]!.resolve([row('SEVEN-DAY-MARKER')]);
    await flushPromises();
    expect(wrapper.text()).toContain('SEVEN-DAY-MARKER');

    // The stale 近30天 answer lands second and must NOT overwrite the table.
    pending[0]!.resolve([row('THIRTY-DAY-MARKER')]);
    await flushPromises();
    expect(wrapper.text(), 'stale 30-day rows overwrote the newer 7-day window').toContain(
      'SEVEN-DAY-MARKER',
    );
    expect(wrapper.text()).not.toContain('THIRTY-DAY-MARKER');
  });

  it('aggregates the window into the KPI band (#554)', async () => {
    mockApi.listMcpAccessLogs.mockResolvedValue(rows);
    const wrapper = mount(NextAdminMcpAccessLogsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
    await flushPromises();

    const band = wrapper.find('[data-testid="mcp-logs-summary"]');
    expect(band.exists()).toBe(true);
    expect(band.find('[data-testid="mcp-logs-total"]').text()).toBe('2');
    expect(band.text()).toContain('已转发');
    expect(band.text()).toContain('被拒');
    // failed = 0 of (forwarded 1 + failed 0) → 0.0%
    expect(band.find('[data-testid="mcp-logs-failure-rate"]').text()).toBe('0.0%');
  });

  it('opens the per-call detail drawer with the metadata timeline (#554)', async () => {
    mockApi.listMcpAccessLogs.mockResolvedValue(rows);
    const wrapper = mount(NextAdminMcpAccessLogsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
    await flushPromises();

    await wrapper.find('[data-testid="mcp-logs-table"] tbody tr').trigger('click');
    await flushPromises();

    const drawer = document.querySelector('[data-testid="mcp-logs-detail-drawer"]');
    expect(drawer, 'detail drawer should render').toBeTruthy();
    const timeline = document.querySelector('[data-testid="mcp-logs-timeline"]');
    expect(timeline!.textContent).toContain('受理');
    expect(timeline!.textContent).toContain('上游首包');
    expect(timeline!.textContent).toContain('+42 ms');
    expect(timeline!.textContent).toContain('结论');
    expect(drawer!.textContent).toContain('weather-mcp');
    expect(drawer!.textContent).toContain('drill-allowed');
    expect(drawer!.textContent).toContain('req-0001');
  });
});
