import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type DOMWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextPlansView from '@/views/next/NextPlansView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type { ProviderProductView } from '@/types/api';
import type { SubscriptionView, SeatView, UsageSummary } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listSubscriptions: vi.fn(),
  listProviderProducts: vi.fn(),
  createSubscription: vi.fn(),
  listSeats: vi.fn(),
  createSeat: vi.fn(),
  updateSeat: vi.fn(),
  adminUsageSummary: vi.fn(),
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

const product: ProviderProductView = {
  id: '0190-0000-0000-0021',
  providerSlug: 'deepseek',
  providerName: 'DeepSeek',
  productCode: 'deepseek-payg-api',
  displayName: 'DeepSeek PAYG',
  billingMode: 'PAYG',
  protocols: '["messages"]',
  baseUrlHost: 'api.deepseek.com',
  implementationStatus: 'VERIFIED',
  balanceAuthority: 'OFFICIAL_API',
};

const subscription = (overrides: Partial<SubscriptionView> = {}): SubscriptionView => ({
  id: '0190-0000-0000-0031',
  providerProductId: product.id,
  productName: 'DeepSeek PAYG',
  name: 'Main',
  billingMode: 'FIXED_SUBSCRIPTION',
  planScope: 'TEAM',
  subscriptionPrice: 100,
  currency: 'USD',
  quotaTotal: 5_000_000,
  quotaUnit: 'TOKENS',
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

const seat = (overrides: Partial<SeatView> = {}): SeatView => ({
  id: '0190-0000-0000-0041',
  subscriptionId: subscription().id,
  assignedUserId: 'u1',
  username: 'alice',
  displayName: 'Alice',
  seatStatus: 'ASSIGNED',
  createdAt: '2026-08-01T00:00:00Z',
  // Deliberately not 0: the release call has to forward *this* seat's version, and a
  // fixture value that matches a hardcoded default would not tell the two apart (#1133).
  version: 3,
  ...overrides,
});

/**
 * #1234: 配额列接真实窗口用量。系统时间冻在同一个周一 2026-09-21T13:47:00Z
 * （窗口口径的手算冻结在 quota-window-usage.spec.ts），mock 按「订阅 × from」
 * 返回不同的输入/输出对——表格里的数字必须来自这张表，不是渲染结果自证。
 */
const FROZEN_MONDAY = new Date('2026-09-21T13:47:00Z');

const USAGE_PAIRS: Record<string, Record<string, [number, number]>> = {
  '0190-0000-0000-0031': {
    '2026-09-21T08:47:00Z': [1_200, 34], // → 1.2k
    '2026-09-21T00:00:00Z': [22_000, 222], // → 22.2k
    '2026-09-01T00:00:00Z': [333_000, 333], // → 333.3k
  },
  s2: {
    '2026-09-21T08:47:00Z': [600, 66], // → 666
    '2026-09-21T00:00:00Z': [0, 0], // a genuine zero
    '2026-09-01T00:00:00Z': [5_000, 500], // → 5.5k
  },
};

/** The quota column's per-window reads, keyed on the call's own parameters. */
function mockQuotaUsage() {
  mockApi.adminUsageSummary.mockImplementation(async (q) => {
    if (!q.subscriptionId) throw new Error('quota usage must be read per subscription');
    const pair = USAGE_PAIRS[q.subscriptionId]?.[q.from ?? ''];
    if (!pair) throw new Error(`unexpected quota window call: ${q.subscriptionId} ${q.from}`);
    return {
      groupBy: q.groupBy,
      groups: [],
      totals: {
        groupKey: '__totals__',
        label: '合计',
        tokens: { input: pair[0], output: pair[1] },
      },
    } as unknown as UsageSummary;
  });
}

/** The table's data rows (UiTable draws one tr.ui-table__row per subscription). */
function tableRows(wrapper: { findAll: (s: string) => DOMWrapper<Element>[] }) {
  return wrapper.findAll('[data-testid="subscriptions-table"] tbody tr.ui-table__row');
}

/** A quota cell's window segments as [label, value] pairs (structure, not text). */
function quotaSegsOf(scope: { findAll: (s: string) => DOMWrapper<Element>[] }): [string, string][] {
  return scope
    .findAll('[data-testid="plans-quota-seg"]')
    .map((el): [string, string] => [
      el.find('.next-plans__usage-label').text(),
      el.find('.next-plans__usage-value').text(),
    ]);
}

describe('NextPlansView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    document.body.innerHTML = '';
    mockApi.listSubscriptions.mockResolvedValue([
      subscription(),
      subscription({ id: 's2', name: 'Staging', status: 'DISABLED', quotaTotal: undefined }),
    ]);
    mockApi.listProviderProducts.mockResolvedValue([product]);
    mockApi.listSeats.mockResolvedValue([seat()]);
  });

  function mountView() {
    return mount(NextPlansView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders subscriptions with plan labels, real window usage and statuses', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(FROZEN_MONDAY);
    try {
      mockQuotaUsage();
      const wrapper = mountView();
      await flushPromises();

      expect(wrapper.find('[data-testid="subscriptions-table"]').exists()).toBe(true);
      expect(wrapper.text()).toContain('DeepSeek PAYG');
      expect(wrapper.text()).toContain('团队套餐');
      expect(wrapper.text()).toContain('100 USD');
      // 列名如实改称窗口用量；数字来自 mock 的 (订阅 × from) 表。
      expect(wrapper.text()).toContain('窗口用量');
      const rows = tableRows(wrapper);
      expect(quotaSegsOf(rows[0]!)).toEqual([
        ['5 小时', '1.2k'],
        ['本周', '22.2k'],
        ['本月', '333.3k'],
      ]);
      // 无 quotaTotal 的行同样画真实已用（本周是真 0）。
      expect(quotaSegsOf(rows[1]!)).toEqual([
        ['5 小时', '666'],
        ['本周', '0'],
        ['本月', '5.5k'],
      ]);
      // 旧断言 34% / 未配置配额 由上面两行真实数字取代：比例条与百分比已经退场。
      expect(wrapper.find('[data-testid="subscriptions-table"]').text()).not.toContain('%');
      expect(wrapper.text()).toContain('正常');
      expect(wrapper.text()).toContain('停用');
      // 行尾额度如实相称「方案总额度」，不是任何窗口的分母。
      expect(rows[0]!.text()).toContain('方案总额度：5.0M Token');
      expect(rows[1]!.text()).toContain('方案总额度：未配置');
    } finally {
      vi.useRealTimers();
    }
  });

  it('#1234: one subscription’s failed window read breaks only its row; retry re-reads just that subscription', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(FROZEN_MONDAY);
    try {
      mockQuotaUsage();
      const healthy = mockApi.adminUsageSummary.getMockImplementation();
      mockApi.adminUsageSummary.mockImplementation(async (q) => {
        if (q.subscriptionId === 's2') {
          throw new (await import('@/api/http')).ApiError({
            type: 'about:blank',
            status: 500,
            code: 'INTERNAL',
            detail: '窗口读取失败',
            requestId: 'req-quota',
            title: 'Error',
          });
        }
        return healthy!(q);
      });

      const wrapper = mountView();
      await flushPromises();

      const rows = tableRows(wrapper);
      // The healthy row kept its numbers…
      expect(quotaSegsOf(rows[0]!)).toEqual([
        ['5 小时', '1.2k'],
        ['本周', '22.2k'],
        ['本月', '333.3k'],
      ]);
      // …and the failing one reports the failure where its numbers would go.
      const error = rows[1]!.find('[data-testid="plans-quota-row-error"]');
      expect(error.exists()).toBe(true);
      expect(error.text()).toContain('窗口读取失败');
      expect(rows[1]!.find('[data-testid="plans-quota-retry"]').exists()).toBe(true);
      expect(quotaSegsOf(rows[1]!)).toEqual([]);

      // The reads recover; the retry re-sends only this row's three windows.
      mockApi.adminUsageSummary.mockImplementation(healthy!);
      await rows[1]!.find('[data-testid="plans-quota-retry"]').trigger('click');
      await flushPromises();

      const rowsAfter = tableRows(wrapper);
      expect(rowsAfter[1]!.find('[data-testid="plans-quota-row-error"]').exists()).toBe(false);
      expect(quotaSegsOf(rowsAfter[1]!)).toEqual([
        ['5 小时', '666'],
        ['本周', '0'],
        ['本月', '5.5k'],
      ]);
      // Isolation, counted: the first subscription was read once (3 windows),
      // the second twice (3 + 3).
      const callsTo = (id: string) =>
        mockApi.adminUsageSummary.mock.calls.filter((c) => c[0].subscriptionId === id);
      expect(callsTo('0190-0000-0000-0031')).toHaveLength(3);
      expect(callsTo('s2')).toHaveLength(6);
    } finally {
      vi.useRealTimers();
    }
  });

  it('#1234: a 200 whose totals carry no token pair is a failed read for that row only, never a 0', async () => {
    // The shape can break without the request failing. Drawing "0" there would
    // claim the window was empty when nothing readable came back at all.
    vi.useFakeTimers();
    vi.setSystemTime(FROZEN_MONDAY);
    try {
      mockQuotaUsage();
      const healthy = mockApi.adminUsageSummary.getMockImplementation();
      mockApi.adminUsageSummary.mockImplementation(async (q) => {
        if (q.subscriptionId === 's2') return healthy!(q);
        return { groupBy: q.groupBy, groups: [], totals: {} } as unknown as UsageSummary;
      });

      const wrapper = mountView();
      await flushPromises();

      const rows = tableRows(wrapper);
      expect(rows[0]!.find('[data-testid="plans-quota-row-error"]').exists()).toBe(true);
      expect(quotaSegsOf(rows[0]!)).toEqual([]);
      // The sibling row is untouched by the broken shape.
      expect(quotaSegsOf(rows[1]!)).toEqual([
        ['5 小时', '666'],
        ['本周', '0'],
        ['本月', '5.5k'],
      ]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('creates a subscription and reloads', async () => {
    mockApi.createSubscription.mockResolvedValue(subscription({ id: 's9' }));
    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listSubscriptions as ReturnType<typeof vi.fn>).mock.calls.length;

    await wrapper.find('[data-testid="subscription-create-open"]').trigger('click');
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('DeepSeek PAYG'))!
      .trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="subscription-create-name"]').setValue('Coding Plan');
    await wrapper.find('[data-testid="subscription-create-price"]').setValue('200');
    await wrapper.find('[data-testid="subscription-create-quota"]').setValue('1000000');
    await flushPromises();
    await wrapper.find('[data-testid="subscription-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createSubscription).toHaveBeenCalledWith({
      providerProductId: product.id,
      name: 'Coding Plan',
      billingMode: 'FIXED_SUBSCRIPTION',
      planScope: 'PERSONAL',
      subscriptionPrice: 200,
      currency: 'USD',
      quotaTotal: 1000000,
      quotaUnit: undefined,
    });
    expect(
      (mockApi.listSubscriptions as ReturnType<typeof vi.fn>).mock.calls.length,
    ).toBeGreaterThan(callsBefore);
  });

  it('assigns and releases seats through the drawer and gate', async () => {
    mockApi.createSeat.mockResolvedValue({} as never);
    mockApi.updateSeat.mockResolvedValue({} as never);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="subscription-seats-open"]').trigger('click');
    await flushPromises();
    expect(document.querySelector('[data-testid="seats-drawer"]')).toBeTruthy();
    expect(document.body.textContent).toContain('alice');

    // Assign (drawer teleports to body — drive the real DOM)
    const assignInput = document.querySelector(
      '[data-testid="seat-assign-user"]',
    ) as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(assignInput, 'bob');
    assignInput.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    (document.querySelector('[data-testid="seat-create"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.createSeat).toHaveBeenCalledWith('0190-0000-0000-0031', {
      displayName: undefined,
      assignedUserId: 'bob',
    });

    // Release with confirmation gate
    (document.querySelector('[data-testid="seat-release"]') as HTMLButtonElement).click();
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '释放' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();
    expect(mockApi.updateSeat).toHaveBeenCalledWith('0190-0000-0000-0031', '0190-0000-0000-0041', {
      status: 'AVAILABLE',
      version: 3,
    });
  });

  it('#PH35: a second click while the assign request is in flight does not create a second seat', async () => {
    // The server mints a fresh seat id per call and the only unique index on
    // plan_seats is partial on external_seat_ref (which this form never sends),
    // so two POSTs = two permanent seat rows.
    let releaseCreate: (value: unknown) => void = () => {};
    mockApi.createSeat.mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseCreate = resolve;
        }) as never,
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="subscription-seats-open"]').trigger('click');
    await flushPromises();

    const assignInput = document.querySelector(
      '[data-testid="seat-assign-user"]',
    ) as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(assignInput, 'bob');
    assignInput.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();

    const assignButton = document.querySelector('[data-testid="seat-create"]') as HTMLButtonElement;
    assignButton.click();
    await flushPromises();
    // Second click lands while the first POST is still unanswered.
    assignButton.click();
    await flushPromises();

    expect(mockApi.createSeat).toHaveBeenCalledTimes(1);

    releaseCreate({});
    await flushPromises();
  });

  it('#1160: a failed seat read is visible with retry, not 「还没有席位」', async () => {
    // The seat read had no catch at all: a rejection escaped as an unhandled
    // rejection, the table kept drawing its empty state, and nothing on screen
    // said the plan's seats could not be read.
    mockApi.listSeats.mockRejectedValue(
      new (await import('@/api/http')).ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '数据库不可用',
        requestId: 'req-seats',
        title: 'Error',
      }),
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="subscription-seats-open"]').trigger('click');
    await flushPromises();

    const drawer = document.querySelector('[data-testid="seats-drawer"]');
    expect(drawer, 'seats drawer should render').toBeTruthy();
    // 「还没有席位」 is a claim about the plan — the read that would know failed.
    expect(drawer!.textContent).not.toContain('还没有席位');
    const failed = document.querySelector('[data-testid="table-load-failed"]');
    expect(failed, 'the failed read must be visible').toBeTruthy();
    expect(failed!.textContent).toContain('数据库不可用');
    const retry = document.querySelector('[data-testid="table-load-retry"]') as HTMLButtonElement;
    expect(retry, 'a retry entry must exist').toBeTruthy();

    // Retry goes through the same loader and renders the seats.
    mockApi.listSeats.mockResolvedValue([seat()]);
    retry.click();
    await flushPromises();

    expect(mockApi.listSeats).toHaveBeenCalledTimes(2);
    expect(document.querySelector('[data-testid="table-load-failed"]')).toBeNull();
    expect(document.querySelector('[data-testid="seats-table"]')!.textContent).toContain('已分配');
  });
});
