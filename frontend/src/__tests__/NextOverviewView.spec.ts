import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextOverviewView from '@/views/next/NextOverviewView.vue';
import { ApiError } from '@/api/http';
import { CHART_PALETTE } from '@/lib/chart-palette';
import * as api from '@/api';
import type { UsageCost, UsageSummary, VirtualKeyView } from '@/types/generated-api';

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
    user: { username: 'demo2_user', displayName: 'Demo 用户', role: 'USER' },
  }),
}));

const mockApi = vi.mocked(api);

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

describe('NextOverviewView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
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

  it('#1138: a failed approval read empties the feed, not the page', async () => {
    // The issue's own requirement: the approval read may fail on its own without taking
    // the dashboard down with it. It starts on the summary's wave (so it does not wait
    // behind it) but is awaited *inside* the feed, where a failure can only empty that
    // panel. Awaiting it in the page's Promise.all instead — the first draft of this fix
    // — blanked the stat cards, the key grid and the cost donut along with it, which the
    // Playwright baseline caught.
    //
    // #1153: the rejection has to be an ApiError, which is what the real client throws.
    // The page only records loadError for one — `if (error instanceof ApiError)` in
    // load()'s catch — so a plain Error made loadError unreachable and left the two
    // assertions below with nothing to bite on.
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
    // …and only the activity panel went empty.
    expect(wrapper.find('[data-testid="overview-feed"]').text()).toContain('还没有动态记录');
  });
});
