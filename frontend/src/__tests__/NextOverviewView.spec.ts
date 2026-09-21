import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type DOMWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextOverviewView from '@/views/next/NextOverviewView.vue';
import { ApiError } from '@/api/http';
import { CHART_PALETTE } from '@/lib/chart-palette';
import * as api from '@/api';
import type {
  SubscriptionView,
  UsageCost,
  UsageSummary,
  VirtualKeyView,
} from '@/types/generated-api';

vi.mock('@/api', () => ({
  listVirtualKeys: vi.fn(),
  usageSummary: vi.fn(),
  adminUsageSummary: vi.fn(),
  listSubscriptions: vi.fn(),
  listMyModelApprovals: vi.fn(),
  auditEvents: vi.fn(),
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { username: 'demo2_user', displayName: 'Demo 用户', role: authState.role },
  }),
}));

const mockApi = vi.mocked(api);

/**
 * The role the mocked auth store reports. `vi.hoisted` keeps the state object
 * reachable from the hoisted `vi.mock` factory above; tests flip it before
 * mounting to exercise the admin branch (auditEvents) of the feed loader.
 */
const authState = vi.hoisted(() => ({ role: 'USER' }));

const key = (overrides: Partial<VirtualKeyView> = {}): VirtualKeyView => ({
  id: '0190-0001',
  name: 'claude-code-main',
  purpose: 'CLAUDE_CODE',
  status: 'ACTIVE',
  displayPrefix: 'mqk_live_abcdefghijklmnopqrstuv',
  lastFour: '8f2a',
  display: 'mqk_live_…8f2a',
  modelIds: ['claude-3-7-sonnet'],
  projectId: 'p1',
  projectTag: 'core-ai',
  cachePolicy: 'DISABLED',
  baseUrl: 'https://gateway.test.internal',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

const summary: UsageSummary = {
  groupBy: 'project',
  groups: [
    {
      groupKey: 'p1',
      label: 'Core AI',
      requests: { upstream: 12, coalesced: 2, l1Hit: 4, l2Hit: 1 },
      tokens: { input: 1_200_000, output: 400_000, cacheRead: 20_000, cacheCreation: 40_000 },
      cost: { upstreamPaid: '3.200000', gatewayObserved: '0.010000' } as unknown as UsageCost,
    },
    {
      groupKey: 'p2',
      label: 'Agent Lab',
      requests: { upstream: 5, coalesced: 0, l1Hit: 0, l2Hit: 0 },
      tokens: { input: 100_000, output: 30_000, cacheRead: 0, cacheCreation: 5_000 },
      cost: { upstreamPaid: '0.400000', gatewayObserved: '0.002000' } as unknown as UsageCost,
    },
  ],
  totals: {
    groupKey: '__totals__',
    label: '合计',
    requests: { upstream: 17, coalesced: 2, l1Hit: 4, l2Hit: 1 },
    tokens: { input: 1_300_000, output: 430_000, cacheRead: 20_000, cacheCreation: 45_000 },
    cost: { upstreamPaid: '3.600000', gatewayObserved: '0.012000' } as unknown as UsageCost,
  },
};

/**
 * #1234: 额度账本的窗口读取。系统时间在用到它的用例里冻在同一个周一
 * 2026-09-21T13:47:00Z（窗口口径的手算冻结在 quota-window-usage.spec.ts），
 * mock 按「订阅 × from」返回不同的输入/输出对——页面上的数字必须来自这张表，
 * 断言不许从渲染结果自证。
 */
const LEDGER_PAIRS: Record<string, Record<string, [number, number]>> = {
  'sub-a': {
    '2026-09-21T08:47:00Z': [1_200, 34], // → 1.2k
    '2026-09-21T00:00:00Z': [22_000, 222], // → 22.2k
    '2026-09-01T00:00:00Z': [333_000, 333], // → 333.3k
  },
  'sub-b': {
    '2026-09-21T08:47:00Z': [600, 66], // → 666
    '2026-09-21T00:00:00Z': [0, 0], // a genuine zero
    '2026-09-01T00:00:00Z': [5_000, 500], // → 5.5k
  },
};

/** The frozen Monday all ledger tests read their windows at. */
const FROZEN_MONDAY = new Date('2026-09-21T13:47:00Z');

/** The admin summary read the page makes for itself (no subscription filter). */
function pageSummary() {
  return { groupBy: 'project', groups: [], totals: summary.totals } as unknown as UsageSummary;
}

/**
 * The ledger's per-window reads, keyed on the call's own parameters: an unexpected
 * (subscriptionId, from) pair rejects, so a view that asks for the wrong window
 * cannot silently pass.
 */
function mockLedgerUsage() {
  mockApi.adminUsageSummary.mockImplementation(async (q) => {
    if (!q.subscriptionId) return pageSummary();
    const pair = LEDGER_PAIRS[q.subscriptionId]?.[q.from ?? ''];
    if (!pair) throw new Error(`unexpected ledger window call: ${q.subscriptionId} ${q.from}`);
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

const subscription = (overrides: Partial<SubscriptionView> = {}): SubscriptionView => ({
  id: 'sub-a',
  providerProductId: '0190-0000-0000-0021',
  productName: 'DeepSeek PAYG',
  name: 'Main Plan',
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

describe('NextOverviewView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    authState.role = 'USER';
    mockApi.listVirtualKeys.mockResolvedValue([
      key(),
      key({ id: '0190-0009', name: 'codex-extra', status: 'ROTATING' }),
    ]);
    mockApi.usageSummary.mockResolvedValue(summary);
    mockApi.listMyModelApprovals.mockResolvedValue([]);
    mockApi.auditEvents.mockResolvedValue([]);
  });

  function mountView() {
    return mount(NextOverviewView, { global: { plugins: [createPinia()] } });
  }

  it('renders the stat band with request/token/cost aggregates', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.usageSummary).toHaveBeenCalledWith('project');
    const stats = wrapper.find('[data-testid="overview-stats"]');
    expect(stats.text()).toContain('虚拟密钥');
    expect(stats.text()).toContain('2');
    expect(stats.text()).toContain('本月请求');
    expect(stats.text()).toContain('本月 Token');
    expect(stats.text()).toContain('1.7M'); // 1.2M+0.4M+0.1M+0.03M
    expect(stats.text()).toContain('¥3.60');
  });

  it('#1104: a failed load shows unknown on the stat band, not a confident zero', async () => {
    mockApi.listVirtualKeys.mockRejectedValue(
      new (await import('@/api/http')).ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '数据库不可用',
        requestId: 'req-500',
        title: 'Error',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    const stats = wrapper.find('[data-testid="overview-stats"]');
    // "¥0.00 本月成本" would be a claim about the tenant, not about this request.
    expect(stats.text()).not.toContain('¥0.00');
    expect(stats.text()).toContain('—');
    // The chip's label carries the reason as its title (the hint is not printed text).
    expect(stats.find('[title="加载失败"]').exists()).toBe(true);
  });

  it('marks the cost as short of a total when unpriced usage is reported (#849)', async () => {
    mockApi.usageSummary.mockResolvedValue({
      ...summary,
      totals: {
        ...summary.totals,
        pricingStatus: 'UNAVAILABLE',
        unpriced: { inputTokens: 11, outputTokens: 7, unpricedEvents: 1, unavailableEvents: 1 },
      },
    } as unknown as UsageSummary);
    const wrapper = mountView();
    await flushPromises();

    const chip = wrapper.find('[data-testid="overview-cost-caveat"]');
    expect(chip.exists()).toBe(true);
    expect(chip.text()).toBe('未定价');
  });

  it('leaves the cost unmarked when every event was priced', async () => {
    mockApi.usageSummary.mockResolvedValue({
      ...summary,
      totals: { ...summary.totals, pricingStatus: 'COMPLETE' },
    } as unknown as UsageSummary);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="overview-cost-caveat"]').exists()).toBe(false);
  });

  it("takes the cost from the server's totals, not a sum of the groups", async () => {
    // The groups and the total deliberately disagree: a client-side sum would
    // report 3.60 while the authoritative figure is 9.99.
    mockApi.usageSummary.mockResolvedValue({
      ...summary,
      totals: { ...summary.totals, cost: { upstreamPaid: '9.990000' } },
    } as unknown as UsageSummary);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="overview-stats"]').text()).toContain('¥9.99');
  });

  /** PH43: ten projects at ¥10 each, so the ring can only name as many as the palette
   *  has slots and the drawn bars are a strict subset of the window. */
  function tenProjects() {
    return Array.from({ length: 10 }, (_, i) => ({
      groupKey: `p${i}`,
      label: `项目 ${i}`,
      requests: { upstream: 1, coalesced: 0, l1Hit: 0, l2Hit: 0 },
      tokens: { input: 1_000, output: 500, cacheRead: 0, cacheCreation: 0 },
      cost: { upstreamPaid: '10.000000', gatewayObserved: '0.000000' } as unknown as UsageCost,
    }));
  }
  const tenProjectSummary = (): UsageSummary =>
    ({
      groupBy: 'project',
      groups: tenProjects(),
      totals: { ...summary.totals, cost: { upstreamPaid: '100.000000' } },
    }) as unknown as UsageSummary;

  // PH43: 成本分布's 合计 sits three cards below 本月成本 and claims to be a total too.
  // It was summed from the bars it draws (top 8 after dropping sub-cent rows), so from
  // a 9th project on, the same window reported two different costs on one screen.
  it('reports 成本分布 合计 from the server total, not the drawn bars (#PH43)', async () => {
    mockApi.usageSummary.mockResolvedValue(tenProjectSummary());

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="overview-stats"]').text()).toContain('¥100.00');
    expect(wrapper.find('[data-testid="overview-cost"]').text()).toContain('合计 ¥100.00');
  });

  // PH43: same root cause, visible in the legend: 其他 stopped at the 8th project, so
  // the shares described a ¥80 ring while the page's cost was ¥100.
  it('gives 成本分布 a 其他 slice that covers every unnamed project (#PH43)', async () => {
    mockApi.usageSummary.mockResolvedValue(tenProjectSummary());

    const wrapper = mountView();
    await flushPromises();

    const panel = wrapper.find('[data-testid="overview-cost"]').text();
    // 100 − one ¥10 project per palette slot, not just the ranks past the first few:
    // the number follows the palette (#1112) instead of a literal, so a slot added or
    // removed cannot leave 其他 describing the wrong remainder.
    expect(panel).toContain(`¥${(100 - CHART_PALETTE.length * 10).toFixed(2)}`);
    expect(panel).not.toContain('¥30.00');
  });

  it('renders usage bars and the recent keys panel with Chinese statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="overview-usage"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('用量分布（按项目）');
    expect(wrapper.text()).toContain('Core AI');
    expect(wrapper.find('[data-testid="overview-keys"]').text()).toContain('claude-code-main');
    expect(wrapper.find('[data-testid="overview-keys"]').text()).toContain('轮换中');
  });

  // PH37: the dates on this page are calendar days. The 虚拟密钥 tile shows when a key
  // was created; slicing the UTC string dated anything created in the first hours of a
  // local day yesterday.
  it('dates a key in 虚拟密钥 by the local calendar day, not the UTC one', async () => {
    const previousTz = process.env.TZ;
    process.env.TZ = 'Asia/Shanghai'; // UTC+8: 16:30Z is 00:30 the *next* local day
    try {
      mockApi.listVirtualKeys.mockResolvedValue([
        key({ createdAt: '2026-08-01T16:30:00Z' }), // → 2026-08-02 00:30 local
      ]);

      const wrapper = mountView();
      await flushPromises();

      const tile = wrapper.find('[data-testid="overview-keys"]').text();
      expect(tile).toContain('2026-08-02');
      expect(tile).not.toContain('2026-08-01');
    } finally {
      if (previousTz === undefined) delete process.env.TZ;
      else process.env.TZ = previousTz;
    }
  });

  // PH37: 最新动态 mixes "N 天前" with an absolute day once an entry is 30 days old.
  // Both forms have to read the viewer's clock, not UTC.
  it('prints the absolute 最新动态 day on the local calendar', async () => {
    const previousTz = process.env.TZ;
    process.env.TZ = 'Asia/Shanghai';
    const now = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-20T00:00:00Z'));
    try {
      mockApi.listVirtualKeys.mockResolvedValue([
        // 66 days before the frozen now — past the relative forms.
        key({ createdAt: '2026-07-15T16:30:00Z' }), // → 2026-07-16 00:30 local
      ]);

      const wrapper = mountView();
      await flushPromises();

      const feed = wrapper.find('[data-testid="overview-feed"]').text();
      expect(feed).toContain('2026-07-16');
      expect(feed).not.toContain('2026-07-15');
    } finally {
      now.mockRestore();
      if (previousTz === undefined) delete process.env.TZ;
      else process.env.TZ = previousTz;
    }
  });

  it('shows the empty hint when there is no usage yet', async () => {
    mockApi.usageSummary.mockResolvedValue({
      groupBy: 'project',
      groups: [],
      totals: summary.totals,
    });

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('还没有用量记录');
    expect(wrapper.find('[data-testid="overview-ledger"]').exists()).toBe(false); // non-admin
  });

  it('#1138: one mount must not fetch the same key list twice', async () => {
    // The cards read the key list and the feed read it again for the same mount — two
    // requests for one page's worth of data. The call count *is* the assertion: the
    // rendered output is identical either way, which is why this went unnoticed.
    mountView();
    await flushPromises();

    expect(mockApi.listVirtualKeys).toHaveBeenCalledTimes(1);
  });

  it('#1138: the model-approval read must not wait behind the usage summary', async () => {
    // Pin the summary wave in the air: the approval read shares nothing with it, so it
    // has to be in flight regardless. Asserting on the *call* rather than on rendered
    // text is what tells a real serial chain apart from render ordering — a text
    // assertion would pass either way once the promise resolves.
    mockApi.usageSummary.mockReturnValue(new Promise(() => {}));

    mountView();
    await flushPromises();

    expect(mockApi.listMyModelApprovals).toHaveBeenCalled();
  });

  it('#1138: a failed approval read fails the feed panel only, not the page', async () => {
    // The issue's own requirement: the approval read may fail on its own without taking
    // the dashboard down with it. It starts on the summary's wave (so it does not wait
    // behind it) but is awaited *inside* the feed, where a failure can only affect that
    // panel. Awaiting it in the page's Promise.all instead — the first draft of this fix
    // — blanked the stat cards, the key grid and the cost donut along with it, which the
    // Playwright baseline caught.
    //
    // #1153: the rejection has to be an ApiError, which is what the real client throws.
    // The page only records loadError for one — `if (error instanceof ApiError)` in
    // load()'s catch — so a plain Error made loadError unreachable and left the two
    // assertions below with nothing to bite on.
    //
    // #1160: "affects only that panel" used to mean the panel went *empty* and said
    // 「还没有动态记录。」 — a claim about the data drawn from a read that failed. The
    // isolation is the contract; the empty-state claim was the bug.
    mockApi.listMyModelApprovals.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '审批读取失败',
        requestId: 'req-approvals',
        title: 'Error',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    // The page still rendered its own data. The card hint is the `title` *attribute*
    // (`:title="card.hint"` in the template), not text — an assertion on `.text()` here
    // could never fail, which is how it read as coverage for as long as it existed.
    const cardHints = wrapper
      .findAll('[data-testid="overview-stats"] .next-overview__stat-chip-label')
      .map((el) => el.attributes('title'));
    expect(cardHints).not.toContain('加载失败');
    // …no page-level banner appeared (the same fact, stated directly)…
    expect(wrapper.find('[data-testid="overview-load-error"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="overview-keys"]').text()).toContain('claude-code-main');
    // …and only the activity panel reports the failure, without claiming emptiness.
    const feed = wrapper.find('[data-testid="overview-feed"]');
    expect(feed.text()).not.toContain('还没有动态记录');
    expect(feed.find('[data-testid="overview-feed-error"]').exists()).toBe(true);
  });

  it('#1160: an admin feed read failure is visible with retry, the page intact', async () => {
    // The admin branch reads the feed through `api.auditEvents` — a request whose
    // failure the page-level banner could never show, because `loadFeed` swallowed it.
    authState.role = 'SYSTEM_ADMIN';
    mockApi.adminUsageSummary.mockResolvedValue({
      groupBy: 'project',
      groups: [],
      totals: summary.totals,
    } as unknown as UsageSummary);
    mockApi.listSubscriptions.mockResolvedValue([]);
    mockApi.auditEvents.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '审计读取失败',
        requestId: 'req-feed',
        title: 'Error',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    // The main load succeeded and stayed that way (#1138's isolation preserved).
    expect(wrapper.find('[data-testid="overview-load-error"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="overview-stats"]').exists()).toBe(true);

    // The feed failure is stated where the data would have been drawn.
    const feed = wrapper.find('[data-testid="overview-feed"]');
    expect(feed.text()).not.toContain('还没有动态记录');
    const error = feed.find('[data-testid="overview-feed-error"]');
    expect(error.exists()).toBe(true);
    expect(error.text()).toContain('审计读取失败');
    const retry = feed.find('[data-testid="overview-feed-retry"]');
    expect(retry.exists()).toBe(true);

    // Retry re-reads through the same loader and the panel recovers.
    mockApi.auditEvents.mockResolvedValue([
      {
        id: 'a1',
        action: 'KEY_CREATED',
        createdAt: '2026-09-01T00:00:00Z',
      },
    ] as never);
    await retry.trigger('click');
    await flushPromises();

    expect(mockApi.auditEvents).toHaveBeenCalledTimes(2);
    expect(feed.find('[data-testid="overview-feed-error"]').exists()).toBe(false);
    expect(feed.findAll('.next-overview__feed-row')).toHaveLength(1);
  });

  it('#1160: the retry window does not re-draw the empty-state claim', async () => {
    // Clearing the error is not "loaded": between the retry click and its
    // response the panel used to fall through to 「还没有动态记录。」 again —
    // the same claim about the data the issue is about, this time for the
    // duration of a slow (or hung) re-read.
    authState.role = 'SYSTEM_ADMIN';
    mockApi.adminUsageSummary.mockResolvedValue({
      groupBy: 'project',
      groups: [],
      totals: summary.totals,
    } as unknown as UsageSummary);
    mockApi.listSubscriptions.mockResolvedValue([]);
    mockApi.auditEvents.mockRejectedValueOnce(
      new ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '审计读取失败',
        requestId: 'req-feed',
        title: 'Error',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    const feed = wrapper.find('[data-testid="overview-feed"]');
    expect(feed.find('[data-testid="overview-feed-error"]').exists()).toBe(true);

    // The re-read hangs: pin it in the air and inspect the panel.
    mockApi.auditEvents.mockImplementation(() => new Promise(() => {}));
    await feed.find('[data-testid="overview-feed-retry"]').trigger('click');
    await flushPromises();

    expect(feed.text()).not.toContain('还没有动态记录');
    expect(feed.text()).toContain('加载中');
  });

  // ---- #1234: 额度账本接真实用量（不再是 0.34 比例的演示填充） ----

  /** A ledger row's window segments as [label, value] pairs (structure, not text). */
  function ledgerSegs(row: DOMWrapper<Element>): [string, string][] {
    return row
      .findAll('[data-testid="overview-ledger-seg"]')
      .map((el): [string, string] => [
        el.find('.next-overview__ledger-seg-label').text(),
        el.find('.next-overview__ledger-seg-value').text(),
      ]);
  }

  function mountLedgerAdmin(subs: SubscriptionView[]) {
    authState.role = 'SYSTEM_ADMIN';
    mockApi.listSubscriptions.mockResolvedValue(subs);
    mockLedgerUsage();
    return mountView();
  }

  it('#1234: the ledger draws each subscription’s real per-window usage from the gateway’s own totals', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(FROZEN_MONDAY);
    try {
      const wrapper = mountLedgerAdmin([
        subscription(),
        subscription({
          id: 'sub-b',
          name: 'Backup Plan',
          quotaTotal: undefined,
          quotaUnit: undefined,
        }),
      ]);
      await flushPromises();

      const panel = wrapper.find('[data-testid="overview-ledger"]');
      // 副标题必须说清口径：这些是网关侧统计的输入+输出 Token。
      expect(panel.text()).toContain('网关侧统计');
      expect(panel.text()).toContain('输入+输出 Token');

      const rows = wrapper.findAll('[data-testid="overview-ledger-row"]');
      expect(rows).toHaveLength(2);
      // 数字来自 mock 的 (订阅 × from) 表，不是渲染结果自证。
      expect(ledgerSegs(rows[0]!)).toEqual([
        ['5 小时', '1.2k'],
        ['本周', '22.2k'],
        ['本月', '333.3k'],
      ]);
      // 无 quotaTotal 的行同样画真实已用；本周是真 0。
      expect(ledgerSegs(rows[1]!)).toEqual([
        ['5 小时', '666'],
        ['本周', '0'],
        ['本月', '5.5k'],
      ]);

      // 行尾的 quota_total 如实相称「方案总额度」，不是三个窗口的分母。
      expect(rows[0]!.text()).toContain('方案总额度：5.0M Token');
      expect(rows[1]!.text()).toContain('方案总额度：未配置');
      // 比例条、百分比、warn/danger 填充与「未配置滚动额度」一起退场。
      expect(panel.text()).not.toContain('%');
      expect(panel.text()).not.toContain('未配置滚动额度');
      expect(panel.findAll('.next-overview__ledger-fill')).toHaveLength(0);

      // 每订阅恰好 3 次读取，窗口 from/to 就是共享口径算出来的那三个。
      const ledgerCalls = mockApi.adminUsageSummary.mock.calls
        .map((c) => c[0])
        .filter((q) => q.subscriptionId);
      expect(ledgerCalls).toHaveLength(6);
      expect(ledgerCalls).toContainEqual({
        subscriptionId: 'sub-a',
        from: '2026-09-21T08:47:00Z',
        to: '2026-09-21T13:47:00Z',
      });
      expect(ledgerCalls).toContainEqual({
        subscriptionId: 'sub-b',
        from: '2026-09-01T00:00:00Z',
        to: '2026-09-21T13:47:00Z',
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('#1234: a window with a genuine zero is drawn as 0 with no error — not as a failed read', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(FROZEN_MONDAY);
    try {
      const wrapper = mountLedgerAdmin([subscription({ id: 'sub-b', name: 'Backup Plan' })]);
      await flushPromises();

      const row = wrapper.find('[data-testid="overview-ledger-row"]');
      expect(row.find('[data-testid="overview-ledger-row-error"]').exists()).toBe(false);
      expect(ledgerSegs(row)).toEqual([
        ['5 小时', '666'],
        ['本周', '0'],
        ['本月', '5.5k'],
      ]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('#1234: one subscription’s failed window read breaks only its row; retry re-reads just that row', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(FROZEN_MONDAY);
    try {
      authState.role = 'SYSTEM_ADMIN';
      mockApi.listSubscriptions.mockResolvedValue([
        subscription(),
        subscription({ id: 'sub-b', name: 'Backup Plan' }),
      ]);
      mockLedgerUsage();
      const healthy = mockApi.adminUsageSummary.getMockImplementation();
      mockApi.adminUsageSummary.mockImplementation(async (q) => {
        if (q.subscriptionId === 'sub-b') {
          throw new ApiError({
            type: 'about:blank',
            status: 500,
            code: 'INTERNAL',
            detail: '窗口读取失败',
            requestId: 'req-ledger',
            title: 'Error',
          });
        }
        return healthy!(q);
      });

      const wrapper = mountView();
      await flushPromises();

      const rows = wrapper.findAll('[data-testid="overview-ledger-row"]');
      // The healthy row kept its numbers…
      expect(ledgerSegs(rows[0]!)).toEqual([
        ['5 小时', '1.2k'],
        ['本周', '22.2k'],
        ['本月', '333.3k'],
      ]);
      // …and the failing one reports the failure where its numbers would go.
      const error = rows[1]!.find('[data-testid="overview-ledger-row-error"]');
      expect(error.exists()).toBe(true);
      expect(error.text()).toContain('窗口读取失败');
      expect(rows[1]!.find('[data-testid="overview-ledger-retry"]').exists()).toBe(true);
      expect(ledgerSegs(rows[1]!)).toEqual([]);

      // The reads recover; the retry re-sends only this row's three windows.
      mockApi.adminUsageSummary.mockImplementation(healthy!);
      await rows[1]!.find('[data-testid="overview-ledger-retry"]').trigger('click');
      await flushPromises();

      const rowsAfter = wrapper.findAll('[data-testid="overview-ledger-row"]');
      expect(rowsAfter[1]!.find('[data-testid="overview-ledger-row-error"]').exists()).toBe(false);
      expect(ledgerSegs(rowsAfter[1]!)).toEqual([
        ['5 小时', '666'],
        ['本周', '0'],
        ['本月', '5.5k'],
      ]);
      // Isolation, counted: sub-a was read once (3 windows), sub-b twice (3 + 3).
      const callsTo = (id: string) =>
        mockApi.adminUsageSummary.mock.calls.filter((c) => c[0].subscriptionId === id);
      expect(callsTo('sub-a')).toHaveLength(3);
      expect(callsTo('sub-b')).toHaveLength(6);
    } finally {
      vi.useRealTimers();
    }
  });

  it('#1234: a 200 whose totals carry no token pair is a failed read, never a 0', async () => {
    // The shape can break without the request failing. Drawing "0" there would
    // claim the window was empty when nothing readable came back at all.
    vi.useFakeTimers();
    vi.setSystemTime(FROZEN_MONDAY);
    try {
      authState.role = 'SYSTEM_ADMIN';
      mockApi.listSubscriptions.mockResolvedValue([subscription()]);
      mockApi.adminUsageSummary.mockImplementation(async (q) => {
        if (!q.subscriptionId) return pageSummary();
        return { groupBy: q.groupBy, groups: [], totals: {} } as unknown as UsageSummary;
      });

      const wrapper = mountView();
      await flushPromises();

      const row = wrapper.find('[data-testid="overview-ledger-row"]');
      expect(row.find('[data-testid="overview-ledger-row-error"]').exists()).toBe(true);
      expect(ledgerSegs(row)).toEqual([]);
      expect(row.find('[data-testid="overview-ledger-retry"]').exists()).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('#1234: while the window reads are in flight the row shows a loading placeholder, not a 0', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(FROZEN_MONDAY);
    try {
      authState.role = 'SYSTEM_ADMIN';
      mockApi.listSubscriptions.mockResolvedValue([subscription()]);
      mockApi.adminUsageSummary.mockImplementation(async (q) => {
        if (!q.subscriptionId) return pageSummary();
        return new Promise<UsageSummary>(() => {}); // pinned in the air
      });

      const wrapper = mountView();
      await flushPromises();

      const row = wrapper.find('[data-testid="overview-ledger-row"]');
      expect(row.text()).toContain('加载中');
      expect(ledgerSegs(row)).toEqual([]);
    } finally {
      vi.useRealTimers();
    }
  });
});
