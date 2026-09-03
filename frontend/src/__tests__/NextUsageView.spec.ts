import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextUsageView from '@/views/next/NextUsageView.vue';
import * as api from '@/api';
import type { QuotaRuleView, UsageRecordPage, UsageSummary } from '@/types/api';

vi.mock('@/api', () => ({
  listMyQuotaRules: vi.fn(),
  usageSummary: vi.fn(),
  usageRecords: vi.fn(),
}));

const mockApi = vi.mocked(api);

const quotaRule = (overrides: Partial<QuotaRuleView> = {}): QuotaRuleView => ({
  id: 'qr-1',
  scopeType: 'USER',
  scopeId: 'u1',
  metric: 'TOKENS',
  period: 'MONTHLY',
  limitValue: 1_000_000,
  warnPercent: 80,
  status: 'ACTIVE',
  used: 950_000,
  usedPct: 95,
  level: 'WARNING',
  windowFrom: '2026-09-01T00:00:00Z',
  windowTo: '2026-09-30T23:59:59Z',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  version: 1,
  ...overrides,
});

const summary: UsageSummary = {
  groupBy: 'project',
  groups: [
    {
      groupKey: 'p1',
      label: 'Core AI',
      requests: { upstream: 12, coalesced: 2, l1Hit: 4, l2Hit: 1 },
      tokens: { input: 1000, output: 500, cacheRead: 200, cacheCreation: 300 },
      cost: { upstreamPaid: '0.002000', gatewayObserved: '0.000400' },
    },
  ],
  totals: {
    groupKey: '__totals__',
    label: '合计',
    requests: { upstream: 12, coalesced: 2, l1Hit: 4, l2Hit: 1 },
    tokens: { input: 1000, output: 500, cacheRead: 200, cacheCreation: 300 },
    cost: { upstreamPaid: '0.002000', gatewayObserved: '0.000400' },
  },
};

const records: UsageRecordPage = {
  items: [
    {
      occurredAt: '2026-09-03T08:00:00Z',
      modelId: 'deepseek-v4-flash',
      cacheLevel: 'UPSTREAM',
      inputTokens: 10,
      outputTokens: 20,
      totalTokens: 30,
      latencyMs: 512,
      upstreamStatusCode: 200,
      providerRequestId: 'req_abc',
      gatewayRequestId: 'gw-1',
      isComplete: true,
      usageMissing: false,
      virtualKeyId: 'k1',
    },
  ],
  page: 1,
  size: 20,
  total: 1,
};

describe('NextUsageView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listMyQuotaRules.mockResolvedValue([]);
    mockApi.usageSummary.mockResolvedValue(summary);
    mockApi.usageRecords.mockResolvedValue(records);
  });

  function mountView() {
    return mount(NextUsageView, { global: { plugins: [createPinia()] } });
  }

  it('renders my quota rules with level and disabled badges', async () => {
    mockApi.listMyQuotaRules.mockResolvedValue([
      quotaRule({ id: 'qr-1', level: 'EXCEEDED', usedPct: 110, used: 1_100_000 }),
      quotaRule({ id: 'qr-2', status: 'DISABLED', level: 'NORMAL', usedPct: 10, used: 100 }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    const rows = wrapper.findAll('[data-testid="my-quota-row"]');
    expect(rows).toHaveLength(2);
    expect(wrapper.text()).toContain('Token 用量 · 每月');
    expect(rows[0].text()).toContain('超限');
    expect(rows[1].text()).toContain('停用');
    expect(rows[0].text()).toContain('限额 1,000,000');
    expect(rows[0].text()).toContain('本期用量 1,100,000（110%）');
  });

  it('shows the empty quota hint when no rules exist', async () => {
    mockApi.listMyQuotaRules.mockResolvedValue([]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="my-quota-panel"]').text()).toContain(
      '管理员未为你设置用量限额',
    );
  });

  it('renders the dimension summary table, totals row and requests math', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.usageSummary).toHaveBeenCalledWith('project');
    expect(wrapper.find('[data-testid="summary-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Core AI');
    expect(wrapper.text()).toContain('19'); // 12 + 2 + 4 + 1
    const totals = wrapper.find('[data-testid="summary-totals"]').text();
    expect(totals).toContain('合计');
    expect(totals).toContain('19'); // all requests incl. cache hits, matches the 请求 column
    expect(totals).toContain('$0.0020');
    expect(totals).toContain('$0.0004');
  });

  it('renders usage distribution bars for the top groups', async () => {
    const wrapper = mountView();
    await flushPromises();

    const chart = wrapper.find('[data-testid="usage-chart"]');
    expect(chart.exists()).toBe(true);
    expect(chart.text()).toContain('用量分布');
    expect(chart.text()).toContain('Core AI');
  });

  it('lists records with model, latency and pager states', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="records-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('deepseek-v4-flash');
    expect(wrapper.text()).toContain('512ms');
    expect(wrapper.text()).toContain('共 1 条 · 第 1 / 1 页');
    const next = wrapper.find('[data-testid="records-next"]');
    expect(next.attributes('disabled')).toBeDefined();
  });

  it('shows the empty state when no records exist', async () => {
    mockApi.usageRecords.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 });

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('没有用量记录');
    expect(wrapper.find('[data-testid="records-next"]').exists()).toBe(false);
  });
});
