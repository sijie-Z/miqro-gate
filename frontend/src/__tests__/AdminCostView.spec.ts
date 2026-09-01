import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminCostView from '@/views/AdminCostView.vue';
import * as api from '@/api';
import type { BudgetView, UsageSummary } from '@/types/api';

vi.mock('@/api', () => ({
  adminUsageSummary: vi.fn(),
  adminBudgets: vi.fn(),
  putProjectBudget: vi.fn(),
  deleteProjectBudget: vi.fn(),
  listProjects: vi.fn(),
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

const budget = (overrides: Partial<BudgetView> = {}): BudgetView => ({
  projectId: '0190-0000-0000-0000-0000000000b1',
  projectCode: 'CORE',
  projectName: 'Core AI',
  month: '2026-09',
  amount: '100',
  currency: 'CNY',
  alertThresholdPct: '80',
  status: 'ACTIVE',
  spent: '0',
  spentPct: '0',
  level: 'NORMAL',
  ...overrides,
});

describe('AdminCostView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminUsageSummary.mockResolvedValue(projectSummary);
    mockApi.adminBudgets.mockResolvedValue([]);
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
    // Cache savings card (L1/L2 hit counters + saved cost).
    expect(wrapper.text()).toContain('缓存节省');
    expect(wrapper.text()).toContain('¥0.0000');
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

  it('renders monthly budgets with spend watermarks and levels', async () => {
    mockApi.adminBudgets.mockResolvedValue([
      budget({
        projectId: 'p1',
        projectCode: 'CORE',
        projectName: 'Core AI',
        spent: '95',
        spentPct: '95',
        level: 'WARNING',
      }),
      budget({
        projectId: 'p2',
        projectCode: 'TOOLS',
        projectName: 'Tools',
        spent: '120',
        spentPct: '120',
        level: 'EXCEEDED',
      }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.adminBudgets).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-\d{2}$/));
    expect(wrapper.find('[data-testid="cost-budget-panel"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Core AI');
    expect(wrapper.text()).toContain('预警');
    expect(wrapper.text()).toContain('超限');
    expect(wrapper.text()).toContain('95.0%');
    // Summary row: total 200, spent 215 -> 107.5%.
    expect(wrapper.text()).toContain('107.5%');
  });

  it('saves an edited budget with the project preselected', async () => {
    // Editing path: the project is preselected, so no dropdown interaction is
    // needed (option popup rendering is TDesign's concern, covered by e2e).
    mockApi.adminBudgets.mockResolvedValue([budget()]);
    mockApi.putProjectBudget.mockResolvedValue(budget());

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="budget-edit"]').trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="budget-amount"] input').setValue('5000');
    await wrapper.find('[data-testid="budget-threshold"] input').setValue('90');
    await wrapper.find('[data-testid="budget-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putProjectBudget).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000b1', {
      month: expect.stringMatching(/^\d{4}-\d{2}$/) as never,
      amount: 5000,
      alertThresholdPct: 90,
    });
    expect(mockApi.adminBudgets).toHaveBeenCalled();
  });

  it('deletes a budget after confirming the dialog', async () => {
    mockApi.adminBudgets.mockResolvedValue([budget()]);
    mockApi.deleteProjectBudget.mockResolvedValue(undefined);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="budget-delete"]').trigger('click');
    await flushPromises();

    const confirmButton = Array.from(document.querySelectorAll('.t-dialog__confirm')).find((b) =>
      b.textContent?.includes('删除'),
    );
    expect(confirmButton, 'confirm dialog should render').toBeTruthy();
    (confirmButton as HTMLElement).click();
    await flushPromises();
    await new Promise((r) => setTimeout(r, 400));

    expect(mockApi.deleteProjectBudget).toHaveBeenCalledWith(
      '0190-0000-0000-0000-0000000000b1',
      '2026-09',
    );
  });
});
