import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextCostView from '@/views/next/NextCostView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type {} from '@/types/api';
import type { BudgetView, UsageSummary } from '@/types/generated-api';

vi.mock('@/api', () => ({
  adminUsageSummary: vi.fn(),
  adminBudgets: vi.fn(),
  listProjects: vi.fn(),
  putProjectBudget: vi.fn(),
  deleteProjectBudget: vi.fn(),
}));

const mockApi = vi.mocked(api);

const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
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

const summary = (project: boolean): UsageSummary => ({
  groupBy: project ? 'project' : 'day',
  groups: project
    ? [
        {
          groupKey: 'p1',
          label: 'Core AI',
          requests: { upstream: 100, coalesced: 2, l1Hit: 5, l2Hit: 0 },
          tokens: { input: 100_000, output: 40_000, cacheRead: 0, cacheCreation: 0 },
          cost: {
            upstreamPaid: '1.000000',
            projectAllocated: '1.500000',
            gatewayObserved: '0.010000',
          },
        },
        {
          groupKey: 'p2',
          label: 'QA 回归',
          requests: { upstream: 50, coalesced: 0, l1Hit: 0, l2Hit: 0 },
          tokens: { input: 20_000, output: 8_000, cacheRead: 0, cacheCreation: 0 },
          cost: {
            upstreamPaid: '0.200000',
            projectAllocated: '0.300000',
            gatewayObserved: '0.002000',
          },
        },
      ]
    : [
        {
          groupKey: '2026-09-03',
          label: '2026-09-03',
          requests: { upstream: 150, coalesced: 2, l1Hit: 5, l2Hit: 0 },
          tokens: { input: 120_000, output: 48_000, cacheRead: 0, cacheCreation: 0 },
          cost: {
            upstreamPaid: '1.200000',
            projectAllocated: '1.800000',
            gatewayObserved: '0.012000',
          },
        },
      ],
  totals: {
    groupKey: '__totals__',
    label: '合计',
    requests: { upstream: 150, coalesced: 2, l1Hit: 5, l2Hit: 0 },
    tokens: { input: 120_000, output: 48_000, cacheRead: 0, cacheCreation: 0 },
    cost: {
      upstreamPaid: '1.200000',
      projectAllocated: '1.800000',
      savedByGatewayCache: '0.040000',
      gatewayObserved: '0.012000',
    },
  },
});

const budget = (overrides: Partial<BudgetView> = {}): BudgetView => ({
  projectId: 'p1',
  projectCode: 'CORE',
  projectName: 'Core AI',
  month: '2026-09',
  amount: '1000',
  currency: 'CNY',
  alertThresholdPct: '80',
  status: 'ACTIVE',
  spent: '900',
  spentPct: '90',
  level: 'WARNING',
  ...overrides,
});

describe('NextCostView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    document.body.innerHTML = '';
    mockApi.adminUsageSummary.mockImplementation(async (q: { groupBy?: string }) =>
      q.groupBy === 'day' ? summary(false) : summary(true),
    );
    mockApi.adminBudgets.mockResolvedValue([budget()]);
    mockApi.listProjects.mockResolvedValue([
      {
        id: 'p1',
        code: 'CORE',
        name: 'Core AI',
        status: 'ACTIVE',
        projectTag: 'core-ai',
        createdAt: '2026-08-01T00:00:00Z',
      },
      {
        id: 'p2',
        code: 'QA',
        name: 'QA 回归',
        status: 'ACTIVE',
        projectTag: 'qa',
        createdAt: '2026-08-01T00:00:00Z',
      },
    ]);
  });

  function mountView() {
    return mount(NextCostView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders cost stats, project table with shares and budget rows', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="cost-stats"]').text()).toContain('¥1.8000');
    expect(wrapper.find('[data-testid="cost-stats"]').text()).toContain('缓存节省');
    expect(wrapper.find('[data-testid="cost-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Core AI');
    expect(wrapper.text()).toContain('83.3%'); // 1.5 / 1.8
    expect(wrapper.find('[data-testid="budget-summary"]').text()).toContain('¥1000.0000');
    expect(wrapper.find('[data-testid="budget-row"]').text()).toContain('预警');
  });

  it('switches to the day table', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="cost-mode-day"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="cost-day-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('2026-09-03');
  });

  it('saves a new budget through the dialog', async () => {
    mockApi.putProjectBudget.mockResolvedValue(budget({ amount: '500' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="budget-create-open"]').trigger('click');
    await flushPromises();
    // Dialog teleports to body.
    const projectButton = Array.from(
      document.querySelectorAll('.stub-option'),
    ) as HTMLButtonElement[];
    const pick = projectButton.find((b) => b.textContent?.includes('QA 回归'));
    expect(pick).toBeTruthy();
    pick!.click();
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
    setInput('budget-amount', '500');
    setInput('budget-threshold', '70');
    await flushPromises();
    (document.querySelector('[data-testid="budget-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.putProjectBudget).toHaveBeenCalledWith('p2', {
      month: expect.stringMatching(/^\d{4}-\d{2}$/),
      amount: 500,
      alertThresholdPct: 70,
    });
  });

  it('deletes a budget through the confirmation gate', async () => {
    mockApi.deleteProjectBudget.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="budget-delete"]').trigger('click');
    await flushPromises();

    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '删除' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.deleteProjectBudget).toHaveBeenCalledWith('p1', '2026-09');
  });
});
