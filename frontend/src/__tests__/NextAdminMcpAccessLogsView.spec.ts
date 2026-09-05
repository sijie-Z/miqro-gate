import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminMcpAccessLogsView from '@/views/next/NextAdminMcpAccessLogsView.vue';
import * as api from '@/api';

vi.mock('@/api', () => ({ listMcpAccessLogs: vi.fn() }));
const mockApi = vi.mocked(api);

const rows = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    serviceName: 'weather-mcp',
    consumerName: 'drill-allowed',
    rpcMethod: 'tools/call',
    toolName: 'forecast',
    status: 'FORWARDED',
    httpStatus: 200,
    gatewayRequestId: 'req-0001',
    occurredAt: '2026-09-05T08:00:00Z',
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    serviceName: 'weather-mcp',
    consumerName: 'drill-outside',
    rpcMethod: 'tools/list',
    toolName: null,
    status: 'SERVICE_DENIED',
    httpStatus: 403,
    gatewayRequestId: 'req-0002',
    occurredAt: '2026-09-05T07:59:00Z',
  },
];

describe('NextAdminMcpAccessLogsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listMcpAccessLogs.mockResolvedValue(rows);
  });
  function mountView() {
    return mount(NextAdminMcpAccessLogsView, { global: { plugins: [createPinia()] } });
  }
  it('renders log rows with outcome badges and metadata', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="mcp-logs-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('weather-mcp');
    expect(wrapper.text()).toContain('drill-allowed');
    expect(wrapper.text()).toContain('已转发');
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
    });
  });
  it('reset clears filters and reloads', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="mcp-logs-service-filter"]').setValue('weather-mcp');
    await wrapper.find('[data-testid="mcp-logs-reset"]').trigger('click');
    await flushPromises();
    expect(mockApi.listMcpAccessLogs).toHaveBeenLastCalledWith({});
    const input = wrapper.find('[data-testid="mcp-logs-service-filter"]').element as HTMLInputElement;
    expect(input.value).toBe('');
  });
});
