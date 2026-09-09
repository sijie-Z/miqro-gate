import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminUsageView from '@/views/next/NextAdminUsageView.vue';
import * as api from '@/api';
import type { UsageCost, UsageRecordPage, UsageSummary } from '@/types/generated-api';

vi.mock('@/api', () => ({
  adminUsageSummary: vi.fn(),
  adminUsageRecords: vi.fn(),
}));

const mockApi = vi.mocked(api);

const summary: UsageSummary = {
  groupBy: 'project',
  groups: [],
  totals: {
    groupKey: '__totals__',
    label: '合计',
    requests: { upstream: 14, coalesced: 1, l1Hit: 2, l2Hit: 0 },
    tokens: { input: 20_000, output: 5_000, cacheRead: 300, cacheCreation: 800 },
    cost: { upstreamPaid: '¥0.0300', gatewayObserved: '0.000100' } as unknown as UsageCost,
  },
};

function recordRow(
  i: number,
  overrides: Record<string, unknown> = {},
): NonNullable<UsageRecordPage['items']>[number] {
  return {
    occurredAt: `2026-09-03T08:0${i}:00Z`,
    modelId: 'deepseek-v4-flash',
    cacheLevel: i === 1 ? 'UPSTREAM' : 'L1_HIT',
    inputTokens: 512,
    outputTokens: 128,
    totalTokens: 640,
    latencyMs: i === 1 ? 800 : 120,
    upstreamStatusCode: i === 1 ? null : 200,
    providerRequestId: i === 1 ? null : `req_${i}`,
    gatewayRequestId: `gw-${i}`,
    isComplete: i !== 1,
    usageMissing: i === 1,
    virtualKeyId: 'k1',
    ...overrides,
  } as NonNullable<UsageRecordPage['items']>[number];
}

const page: UsageRecordPage = {
  items: Array.from({ length: 20 }, (_, i) => recordRow(i)),
  page: 1,
  size: 20,
  total: 45,
};

describe('NextAdminUsageView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.adminUsageSummary.mockResolvedValue(summary);
    mockApi.adminUsageRecords.mockResolvedValue(page);
  });

  function mountView() {
    return mount(NextAdminUsageView, { global: { plugins: [createPinia()] } });
  }

  it('passes a picked time range to the summary and records APIs', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="admin-usage-range-30"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUsageSummary).toHaveBeenLastCalledWith(
      expect.objectContaining({ groupBy: 'project', from: expect.any(String), to: expect.any(String) }),
    );
    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, size: 20, from: expect.any(String), to: expect.any(String) }),
    );
  });

  it('renders the tenant summary strip and records with usage badges', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="usage-summary"]').text()).toContain('14');
    expect(wrapper.find('[data-testid="usage-summary"]').text()).toContain('20,000');
    expect(wrapper.find('[data-testid="usage-records-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('deepseek-v4-flash');
    expect(wrapper.text()).toContain('L1 hit');
    expect(wrapper.text()).toContain('ok');
    expect(wrapper.text()).toContain('missing');
    expect(wrapper.text()).toContain('gw-1');
  });

  it('paginates to the next page and disables prev on the first page', async () => {
    const wrapper = mountView();
    await flushPromises();

    const prev = wrapper.find('[data-testid="usage-prev"]');
    expect(prev.attributes('disabled')).toBeDefined();
    await wrapper.find('[data-testid="usage-next"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith({
      modelId: undefined,
      projectId: undefined,
      page: 2,
      size: 20,
    });
    expect(wrapper.find('[data-testid="usage-prev"]').attributes('disabled')).toBeUndefined();
  });

  it('passes filter inputs to the query', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-project-id"]').setValue('p1');
    await wrapper.find('[data-testid="usage-model-id"]').setValue('deepseek-v4-flash');
    await wrapper.find('[data-testid="usage-query"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith({
      modelId: 'deepseek-v4-flash',
      projectId: 'p1',
      page: 1,
      size: 20,
    });
  });
});
