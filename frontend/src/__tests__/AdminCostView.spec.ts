import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminCostView from '@/views/AdminCostView.vue';
import * as api from '@/api';
import type { UsageSummary } from '@/types/api';

vi.mock('@/api', () => ({
  adminUsageSummary: vi.fn(),
}));

const mockApi = vi.mocked(api);

const projectSummary: UsageSummary = {
  groupBy: 'project',
  groups: [
    {
      groupKey: 'core-ai',
      label: 'core-ai',
      requests: { upstream: 100, coalesced: 0, l1Hit: 0, l2Hit: 0 },
      tokens: { input: 100000, output: 50000, cacheRead: 0, cacheCreation: 0 },
      cost: {
        upstreamPaid: '0.3000',
        gatewayObserved: '0.3000',
        projectAllocated: '0.3000',
        savedByGatewayCache: '0.0000',
      },
    },
    {
      groupKey: 'tools',
      label: 'tools',
      requests: { upstream: 50, coalesced: 0, l1Hit: 0, l2Hit: 0 },
      tokens: { input: 50000, output: 25000, cacheRead: 0, cacheCreation: 0 },
      cost: {
        upstreamPaid: '0.1000',
        gatewayObserved: '0.1000',
        projectAllocated: '0.1000',
        savedByGatewayCache: '0.0000',
      },
    },
  ],
  totals: {
    groupKey: 'total',
    label: '合计',
    requests: { upstream: 150, coalesced: 0, l1Hit: 0, l2Hit: 0 },
    tokens: { input: 150000, output: 75000, cacheRead: 0, cacheCreation: 0 },
    cost: {
      upstreamPaid: '0.4000',
      gatewayObserved: '0.4000',
      projectAllocated: '0.4000',
      savedByGatewayCache: '0.0000',
    },
  },
};

const daySummary: UsageSummary = {
  groupBy: 'day',
  groups: [
    {
      groupKey: '2026-08-27',
      label: '2026-08-27',
      requests: { upstream: 150, coalesced: 0, l1Hit: 0, l2Hit: 0 },
      tokens: { input: 150000, output: 75000, cacheRead: 0, cacheCreation: 0 },
      cost: {
        upstreamPaid: '0.4000',
        gatewayObserved: '0.4000',
        projectAllocated: '0.4000',
        savedByGatewayCache: '0.0000',
      },
    },
  ],
  totals: projectSummary.totals,
};

function mountView() {
  return mount(AdminCostView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('AdminCostView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminUsageSummary.mockResolvedValue(projectSummary);
    vi.stubGlobal('URL', {
      createObjectURL: () => 'blob:test',
      revokeObjectURL: vi.fn(),
    });
  });

  it('loads project and day summaries and renders totals', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.adminUsageSummary).toHaveBeenCalledWith(
      expect.objectContaining({ groupBy: 'project' }),
    );
    expect(mockApi.adminUsageSummary).toHaveBeenCalledWith(
      expect.objectContaining({ groupBy: 'day' }),
    );
    expect(wrapper.find('[data-testid="cost-project-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('¥0.4000');
    expect(wrapper.text()).toContain('core-ai');
    expect(wrapper.text()).toContain('75%'); // 0.3/0.4 share for core-ai
  });

  it('switches to the day table', async () => {
    mockApi.adminUsageSummary.mockResolvedValue(daySummary);

    const wrapper = mountView();
    await flushPromises();

    const dayRadio = wrapper
      .findAll('[data-testid="cost-mode"] .t-radio-button')
      .find((b) => b.text().includes('按天'));
    await dayRadio!.trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="cost-day-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('2026-08-27');
  });

  it('shows an empty state when there is no cost data', async () => {
    mockApi.adminUsageSummary.mockResolvedValue({
      ...projectSummary,
      groups: [],
      totals: {
        groupKey: 'total',
        label: '合计',
        requests: { upstream: 0, coalesced: 0, l1Hit: 0, l2Hit: 0 },
        tokens: { input: 0, output: 0, cacheRead: 0, cacheCreation: 0 },
        cost: {
          upstreamPaid: '0',
          gatewayObserved: '0',
          projectAllocated: '0',
          savedByGatewayCache: '0',
        },
      },
    });

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('该时间窗口内没有成本数据');
  });

  it('exports a CSV of the current view', async () => {
    const wrapper = mountView();
    await flushPromises();

    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await wrapper.find('[data-testid="cost-export"]').trigger('click');

    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(
      (URL as unknown as { revokeObjectURL: ReturnType<typeof vi.fn> }).revokeObjectURL,
    ).toHaveBeenCalled();
    clickSpy.mockRestore();
  });
});
