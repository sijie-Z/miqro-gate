import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import UsageView from '@/views/UsageView.vue';
import * as api from '@/api';
import type { QuotaRuleView, UsageSummary, UsageRecordPage } from '@/types/api';

vi.mock('@/api', () => ({
  usageSummary: vi.fn(),
  usageRecords: vi.fn(),
  listMyQuotaRules: vi.fn(),
}));

const mockApi = vi.mocked(api);

const emptySummary = (): UsageSummary => ({
  groupBy: 'day',
  groups: [],
  totals: {
    groupKey: 'total',
    label: '合计',
    requests: { upstream: 0, coalesced: 0, l1Hit: 0, l2Hit: 0 },
    tokens: { input: 0, output: 0, cacheRead: 0, cacheCreation: 0 },
    cost: { upstreamPaid: '0', gatewayObserved: '0' },
  },
});

const emptyRecords = (): UsageRecordPage => ({ items: [], page: 1, size: 20, total: 0 });

const quotaRule = (overrides: Partial<QuotaRuleView> = {}): QuotaRuleView => ({
  id: '0190-0000-0000-0000-0000000000e1',
  scopeType: 'USER',
  scopeId: '0190-0000-0000-0000-0000000000d1',
  scopeName: '张三',
  scopeTag: 'zhangsan',
  metric: 'TOKENS',
  period: 'MONTHLY',
  limitValue: 100000,
  warnPercent: 80,
  status: 'ACTIVE',
  used: 90000,
  usedPct: 90,
  level: 'WARNING',
  windowFrom: '2026-09-01T00:00:00Z',
  windowTo: '2026-10-01T00:00:00Z',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  version: 0,
  ...overrides,
});

function mountView() {
  return mount(UsageView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('UsageView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.usageSummary.mockResolvedValue(emptySummary());
    mockApi.usageRecords.mockResolvedValue(emptyRecords());
    mockApi.listMyQuotaRules.mockResolvedValue([]);
  });

  it('renders the empty quota state when the admin set no rules', async () => {
    const wrapper = mountView();
    await flushPromises();

    const panel = wrapper.find('[data-testid="my-quota-panel"]');
    expect(panel.exists()).toBe(true);
    expect(panel.text()).toContain('我的配额');
    expect(panel.text()).toContain('暂无配额规则');
  });

  it('shows the caller rules with dimension, watermark bar and level badge', async () => {
    mockApi.listMyQuotaRules.mockResolvedValue([
      quotaRule(),
      quotaRule({
        id: '0190-0000-0000-0000-0000000000f1',
        metric: 'REQUESTS',
        period: 'DAILY',
        limitValue: 100,
        used: 120,
        usedPct: 120,
        level: 'EXCEEDED',
      }),
      quotaRule({ id: '0190-0000-0000-0000-0000000000a1', status: 'DISABLED' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    const rows = wrapper.findAll('[data-testid="my-quota-row"]');
    expect(rows).toHaveLength(3);
    expect(wrapper.text()).toContain('Token 用量 · 每月');
    expect(wrapper.text()).toContain('请求次数 · 每日');
    expect(wrapper.text()).toContain('限额 100,000 · 本期用量 90,000（90%）');
    expect(wrapper.text()).toContain('预警');
    expect(wrapper.text()).toContain('超限');
    expect(wrapper.text()).toContain('停用');
    expect(mockApi.listMyQuotaRules).toHaveBeenCalledTimes(1);
  });
});
