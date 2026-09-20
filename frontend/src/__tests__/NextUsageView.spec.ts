import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { UiTooltip } from '@/ui';
import { createPinia, setActivePinia } from 'pinia';
import NextUsageView from '@/views/next/NextUsageView.vue';
import * as api from '@/api';
import { localTzOffsetMinutes } from '@/utils/datetime';
import type {
  QuotaRuleView,
  UsageCost,
  UsageRecordPage,
  UsageSummary,
} from '@/types/generated-api';

vi.mock('@/api', () => ({
  listMyQuotaRules: vi.fn(),
  listVirtualKeys: vi.fn(),
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
      cost: { upstreamPaid: '0.002000', gatewayObserved: '0.000400' } as unknown as UsageCost,
    },
  ],
  totals: {
    groupKey: '__totals__',
    label: '合计',
    requests: { upstream: 12, coalesced: 2, l1Hit: 4, l2Hit: 1 },
    tokens: { input: 1000, output: 500, cacheRead: 200, cacheCreation: 300 },
    cost: { upstreamPaid: '0.002000', gatewayObserved: '0.000400' } as unknown as UsageCost,
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
      cacheReadInputTokens: 5000,
      totalTokens: 30,
      latencyMs: 512,
      upstreamStatusCode: 200,
      providerRequestId: 'req_abc',
      gatewayRequestId: 'gw-1',
      isComplete: true,
      usageMissing: false,
      virtualKeyId: 'k1',
      clientIp: '203.0.113.7',
      cost: 0.1234,
      priced: true,
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
    mockApi.listVirtualKeys.mockResolvedValue([{ id: 'k1', name: '开发钥匙' } as never]);
    mockApi.usageSummary.mockResolvedValue(summary);
    mockApi.usageRecords.mockResolvedValue(records);
  });

  function mountView() {
    return mount(NextUsageView, { global: { plugins: [createPinia()] } });
  }

  /** PH43: a records endpoint that really paginates — 40 rows over two local days,
   *  so page 1 (size 20) covers 09-03 only and page 2 covers 09-02 only. */
  function twoDayWindow() {
    const rows = Array.from({ length: 40 }, (_, i) => ({
      ...records.items![0]!,
      occurredAt: i < 20 ? '2026-09-03T08:00:00Z' : '2026-09-02T08:00:00Z',
      totalTokens: 10,
      gatewayRequestId: `gw-${i}`,
    }));
    return async (opts?: { page?: number; size?: number }): Promise<UsageRecordPage> => {
      const page = opts?.page ?? 1;
      const size = opts?.size ?? 20;
      return {
        items: rows.slice((page - 1) * size, page * size),
        page,
        size,
        total: rows.length,
      };
    };
  }

  it('shows the dismissible usage-caliber tip and hides it on dismiss', async () => {
    localStorage.clear();
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="usage-caliber-tip"]').exists()).toBe(true);
    await wrapper.find('[data-testid="usage-caliber-dismiss"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="usage-caliber-tip"]').exists()).toBe(false);
    expect(localStorage.getItem('miqrokey.usage-caliber-tip-dismissed')).toBe('1');
  });

  it('renders my quota rules with level and disabled badges', async () => {
    mockApi.listMyQuotaRules.mockResolvedValue([
      quotaRule({ id: 'qr-1', level: 'EXCEEDED', usedPct: 110, used: 1_100_000 }),
      quotaRule({ id: 'qr-2', status: 'DISABLED', level: 'NORMAL', usedPct: 10, used: 100 }),
      quotaRule({
        id: 'qr-3',
        level: 'NEAR_LIMIT',
        metric: 'COST',
        limitValue: 100,
        used: 92.5,
        usedPct: 92.5,
      }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    const rows = wrapper.findAll('[data-testid="my-quota-row"]');
    expect(rows).toHaveLength(3);
    expect(wrapper.text()).toContain('Token 用量 · 每月');
    expect(rows[0]!.text()).toContain('超限');
    expect(rows[1]!.text()).toContain('停用');
    expect(rows[0]!.text()).toContain('限额 1,000,000');
    expect(rows[0]!.text()).toContain('本期用量 1,100,000（110%）');
    // #683: NEAR_LIMIT label and the COST unit render on the self-service panel.
    expect(rows[2]!.text()).toContain('即将超限');
    expect(rows[2]!.text()).toContain('限额 ¥100');
    // #943: none of these rows carries a pricing gap, so none carries the caveat.
    expect(wrapper.find('[data-testid="my-quota-cost-unpriced"]').exists()).toBe(false);
  });

  it('#943: marks a COST quota row as a lower bound when its window could not be fully priced', async () => {
    mockApi.listMyQuotaRules.mockResolvedValue([
      quotaRule({
        id: 'qr-1',
        metric: 'COST',
        limitValue: 1,
        used: 0,
        usedPct: 0,
        level: 'NORMAL',
        pricingStatus: 'UNAVAILABLE',
        unpriced: { unpricedEvents: 2, unavailableEvents: 2 },
      }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    // The row says ¥0（0%） — the caveat is what separates "spent nothing" from
    // "could not be valued", which is exactly how this panel used to mislead.
    const marks = wrapper.findAll('[data-testid="my-quota-cost-unpriced"]');
    expect(marks).toHaveLength(1);
    expect(marks[0]!.element.closest('.next-usage__quota-body')?.textContent).toContain(
      '本期用量 ¥0（0%）',
    );
  });

  it('#1128: the record log shows how a row was attributed', async () => {
    mockApi.usageRecords.mockResolvedValue({
      ...records,
      items: [
        {
          ...records.items![0]!,
          resolutionStatus: 'POLICY_ROUTED',
          claimSource: 'git_remote',
          claimConfidence: 'MEDIUM',
        },
      ],
    });

    const wrapper = mountView();
    await flushPromises();

    const chip = wrapper.find('[data-testid="usage-attribution-chip"]');
    expect(chip.exists()).toBe(true);
    expect(chip.text()).toContain('未归属策略路由');
    const notes = wrapper.findAllComponents(UiTooltip).map((t) => t.props('text'));
    expect(notes.some((note) => note.includes('客户端声明来源：仓库远端，置信度 MEDIUM'))).toBe(
      true,
    );
  });

  it('#1097: the 上游成本 cell carries its own composition bar', async () => {
    mockApi.usageSummary.mockResolvedValue({
      ...summary,
      groups: [
        {
          ...summary.groups![0]!,
          cost: {
            ...summary.groups![0]!.cost,
            upstreamPaidParts: { input: 0.0015, output: 0.0005 },
          } as never,
        },
      ],
    });

    const wrapper = mountView();
    await flushPromises();

    const bar = wrapper.find('[data-testid="cost-split-bar"]');
    expect(bar.exists()).toBe(true);
    // The bar explains 上游成本 — the figure in the cell it sits under, not the
    // observed one (which covers coalesced and cached rows the customer never paid).
    expect(bar.attributes('aria-label')).toBe(
      '上游成本构成：输入 ¥0.0015 · 输出 ¥0.0005 · 缓存读 ¥0.0000 · 缓存写 ¥0.0000（合计 ¥0.0020）',
    );
  });

  it('shows the empty quota hint when no rules exist', async () => {
    mockApi.listMyQuotaRules.mockResolvedValue([]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="my-quota-panel"]').text()).toContain(
      '管理员未为你设置用量限额',
    );
  });

  it('#801: marks the totals cost as not-a-total when a gap exists', async () => {
    mockApi.usageSummary.mockResolvedValue({
      ...summary,
      totals: {
        ...summary.totals,
        pricingStatus: 'PARTIAL',
        unpriced: { unpricedEvents: 617, unavailableEvents: 565 },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();

    const marker = wrapper.find('[data-testid="cost-unpriced"]');
    expect(marker.exists()).toBe(true);
    expect(marker.text()).toContain('未定价');
  });

  it('#801: leaves a fully priced cost unmarked', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="cost-unpriced"]').exists()).toBe(false);
  });

  it('#877: marks the 上游成本 card as not-a-total, like the 合计 row below it', async () => {
    // The card is the first money a user sees, and it sits directly above the 合计 row
    // that already carries this marker. Same figure, so the same claim.
    mockApi.usageSummary.mockResolvedValue({
      ...summary,
      totals: {
        ...summary.totals,
        pricingStatus: 'UNAVAILABLE',
        unpriced: { unpricedEvents: 1, unavailableEvents: 1 },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();

    const card = wrapper.find('[data-testid="stat-cost-unpriced"]');
    expect(card.exists()).toBe(true);
    expect(card.text()).toContain('未定价');
  });

  it('#877: marks a group row whose cost is short, and only that row', async () => {
    mockApi.usageSummary.mockResolvedValue({
      ...summary,
      groups: [
        {
          ...summary.groups![0]!,
          pricingStatus: 'PARTIAL',
          unpriced: { unpricedEvents: 3, unavailableEvents: 1 },
        },
      ],
    } as never);

    const wrapper = mountView();
    await flushPromises();

    const rowMarker = wrapper.find('[data-testid="group-cost-unpriced"]');
    expect(rowMarker.exists()).toBe(true);
    expect(rowMarker.text()).toContain('未定价');
    // The totals priced in full, so their marker must stay away — separate figures, and
    // the API keeps their gaps separate too.
    expect(wrapper.find('[data-testid="cost-unpriced"]').exists()).toBe(false);
  });

  it('#877: leaves a fully priced card and every group row unmarked', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="stat-cost-unpriced"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="group-cost-unpriced"]').exists()).toBe(false);
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
    expect(totals).toContain('¥0.0020');
    expect(totals).toContain('¥0.0004');
  });

  // #775: money renders in CNY — the symbol is decided in one place, and it is
  // asserted on the rendered text rather than on the number alone.
  it('renders every money cell with a single ¥ (no ¥$ and no bare $)', async () => {
    const wrapper = mountView();
    await flushPromises();

    const records = wrapper.find('[data-testid="records-table"]').text();
    expect(records).toContain('¥0.1234');
    expect(records).not.toContain('$');

    const summary = wrapper.find('[data-testid="summary-table"]').text();
    expect(summary).toContain('¥0.0020');
    expect(summary).toContain('¥0.0004');
    expect(summary).not.toContain('$');
    expect(wrapper.find('[data-testid="summary-totals"]').text()).not.toContain('$');
  });

  it('passes the picked time range to summary and records', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-range-7"]').trigger('click');
    await flushPromises();

    expect(mockApi.usageSummary).toHaveBeenLastCalledWith(
      'project',
      expect.any(String),
      expect.any(String),
      // #1050: the reporting endpoints bucket day/month rows by the viewer's
      // local day; the view must forward its own offset, not a fixed one.
      localTzOffsetMinutes(),
    );
    expect(mockApi.usageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({
        page: 1,
        size: 20,
        from: expect.any(String),
        to: expect.any(String),
      }),
    );
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
    // #643: key name and cache-read tokens are visible at a glance
    expect(wrapper.text()).toContain('开发钥匙');
    expect(wrapper.text()).toContain('5,000');
    expect(wrapper.text()).toContain('203.0.113.7');
    expect(wrapper.text()).toContain('512ms');
    expect(wrapper.text()).toContain('共 1 条 · 第 1 / 1 页');
    const next = wrapper.find('[data-testid="records-next"]');
    expect(next.attributes('disabled')).toBeDefined();
  });

  /** Column headers of a rendered UiTable, in column order. */
  function headerTitles(wrapper: ReturnType<typeof mount>, testid: string): string[] {
    return wrapper.findAll(`[data-testid="${testid}"] thead th`).map((th) => th.text());
  }

  /** One cell of a records row, located by the column's own header. */
  function cellOf(
    wrapper: ReturnType<typeof mount>,
    testid: string,
    rowIndex: number,
    title: string,
  ): string {
    const index = headerTitles(wrapper, testid).indexOf(title);
    expect(index).toBeGreaterThanOrEqual(0);
    return wrapper
      .findAll(`[data-testid="${testid}"] tbody tr`)
      [rowIndex]!.findAll('td')
      [index]!.text();
  }

  it('drops the 调整 column while no row on the page carries an adjustment (#773)', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(headerTitles(wrapper, 'records-table')).not.toContain('调整');
    // The rows themselves are untouched — the column went, the table did not.
    expect(wrapper.text()).toContain('deepseek-v4-flash');
    expect(wrapper.findAll('[data-testid="records-table"] tbody tr')).toHaveLength(1);
  });

  it('keeps the 调整 column and its per-row state while a row is adjusted (#773)', async () => {
    mockApi.usageRecords.mockResolvedValue({
      ...records,
      items: [
        { ...records.items![0]!, adjusted: true, netOutputTokens: 25 },
        { ...records.items![0]!, gatewayRequestId: 'gw-2' },
      ],
    });

    const wrapper = mountView();
    await flushPromises();

    // The adjusted row declares itself; the untouched one keeps the placeholder
    // the column always rendered.
    expect(cellOf(wrapper, 'records-table', 0, '调整')).toContain('已调整');
    expect(cellOf(wrapper, 'records-table', 1, '调整')).toBe('—');
  });

  it('shows the empty state when no records exist', async () => {
    mockApi.usageRecords.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 });

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('没有用量记录');
    expect(wrapper.find('[data-testid="records-next"]').exists()).toBe(false);
  });

  it('jumps to a specific page and clamps out-of-range input (#643)', async () => {
    mockApi.usageRecords.mockResolvedValue({ ...records, total: 45 }); // 3 pages of 20
    const wrapper = mountView();
    await flushPromises();

    const input = wrapper.find('[data-testid="records-page-input"]');
    await input.setValue('2');
    await wrapper.find('[data-testid="records-page-go"]').trigger('click');
    await flushPromises();
    expect(mockApi.usageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 2, size: 20 }),
    );

    await input.setValue('99');
    await input.trigger('keydown.enter');
    await flushPromises();
    expect(mockApi.usageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 3, size: 20 }),
    );
  });

  it('applies a custom time window to summary and records (#643)', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-range-custom"]').trigger('click');
    await wrapper.find('[data-testid="usage-custom-from"]').setValue('2026-09-01T08:00');
    await wrapper.find('[data-testid="usage-custom-to"]').setValue('2026-09-10T08:00');
    await wrapper.find('[data-testid="usage-custom-apply"]').trigger('click');
    await flushPromises();

    const expectedFrom = new Date('2026-09-01T08:00').toISOString();
    const expectedTo = new Date('2026-09-10T08:00').toISOString();
    expect(mockApi.usageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: expectedFrom, to: expectedTo, page: 1 }),
    );
    expect(mockApi.usageSummary).toHaveBeenLastCalledWith(
      'project',
      expectedFrom,
      expectedTo,
      localTzOffsetMinutes(),
    );
  });

  // PH37: the panel is labelled 用量趋势 (a *daily* trend) and it aggregates the very
  // rows the 最近记录 table below renders through formatTime — which reads the
  // browser's own timezone. Bucketing on the raw UTC date string splits one local
  // day in two, so the same records get two different dates on one screen.
  it('buckets the daily trend by the same local day the records table shows', async () => {
    const previousTz = process.env.TZ;
    process.env.TZ = 'Asia/Shanghai'; // UTC+8: 16:30Z and 02:00Z are one local day
    try {
      mockApi.usageRecords.mockResolvedValue({
        ...records,
        items: [
          { ...records.items![0]!, occurredAt: '2026-09-03T16:30:00Z', gatewayRequestId: 'gw-a' },
          { ...records.items![0]!, occurredAt: '2026-09-04T02:00:00Z', gatewayRequestId: 'gw-b' },
        ],
        total: 2,
      });

      const wrapper = mountView();
      await flushPromises();

      // The table says both rows happened on 2026-09-04 local time…
      expect(cellOf(wrapper, 'records-table', 0, '时间')).toContain('2026-09-04 00:30');
      expect(cellOf(wrapper, 'records-table', 1, '时间')).toContain('2026-09-04 10:00');

      // …so the chart above it must show one bucket for 09-04 and no 09-03 bucket.
      const chart = wrapper.find('[data-testid="usage-trend-chart"]');
      expect(chart.text()).toContain('09-04');
      expect(chart.text()).not.toContain('09-03');
    } finally {
      if (previousTz === undefined) delete process.env.TZ;
      else process.env.TZ = previousTz;
    }
  });

  // PH43: 用量趋势 answers "what did the selected window do, day by day". It was fed
  // the *table page* instead (records.items — 20 rows), so a day whose rows fell past
  // page 1 vanished from the trend.
  it('builds the daily trend from the whole window, not the current table page (#PH43)', async () => {
    mockApi.usageRecords.mockImplementation(twoDayWindow());
    const wrapper = mountView();
    await flushPromises();

    // The window holds 40 rows over two local days; the first table page holds 20 of
    // them, all on 09-03.
    expect(wrapper.text()).toContain('共 40 条');
    const chart = wrapper.find('[data-testid="usage-trend-chart"]');
    expect(wrapper.findAll('[data-testid="records-table"] tbody tr')).toHaveLength(20);
    expect(chart.text()).toContain('09-03');
    expect(chart.text()).toContain('09-02');
    // The whole window fits in one read, so the trend makes no partial claim.
    expect(wrapper.find('[data-testid="usage-trend-partial"]').exists()).toBe(false);
  });

  it('declares the trend partial when the window is larger than one read (#PH43)', async () => {
    // 300 rows in the window; one records read returns at most 200 (the API cap), so
    // the older days cannot be in the chart — and the panel has to say so.
    const rows = Array.from({ length: 300 }, (_, i) => ({
      ...records.items![0]!,
      occurredAt: '2026-09-03T08:00:00Z',
      totalTokens: 10,
      gatewayRequestId: `gw-${i}`,
    }));
    mockApi.usageRecords.mockImplementation(async (opts) => {
      const size = Math.min(opts?.size ?? 20, 200);
      return { items: rows.slice(0, size), page: 1, size, total: rows.length };
    });

    const wrapper = mountView();
    await flushPromises();

    const note = wrapper.find('[data-testid="usage-trend-partial"]');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain('200');
    expect(note.text()).toContain('300');
  });

  it('keeps the daily trend stable while the records table is paged (#PH43)', async () => {
    mockApi.usageRecords.mockImplementation(twoDayWindow());
    const wrapper = mountView();
    await flushPromises();

    const before = wrapper.find('[data-testid="usage-trend-chart"]').text();

    await wrapper.find('[data-testid="records-next"]').trigger('click');
    await flushPromises();

    // Paging is a view choice on the table half of the panel; it must not rewrite the
    // trend above it. (Today the chart flips from 09-03 to 09-02.)
    expect(wrapper.text()).toContain('第 2 / 2 页');
    expect(wrapper.find('[data-testid="usage-trend-chart"]').text()).toBe(before);
  });

  it('keeps the previous rows while a page change is in flight (#643)', async () => {
    let release: (page: UsageRecordPage) => void = () => {};
    // total > pageSize keeps the next button enabled for the pending click
    mockApi.usageRecords.mockResolvedValue({ ...records, total: 45 });
    const wrapper = mountView();
    await flushPromises();

    mockApi.usageRecords.mockImplementationOnce(
      () =>
        new Promise<UsageRecordPage>((resolve) => {
          release = resolve;
        }),
    );
    await wrapper.find('[data-testid="records-next"]').trigger('click');
    await flushPromises();

    // The old rows stay rendered (no skeleton swap) and the pager shows busy.
    expect(wrapper.text()).toContain('deepseek-v4-flash');
    expect(wrapper.find('[data-testid="records-busy"]').exists()).toBe(true);

    release({ ...records, page: 2, total: 45 });
    await flushPromises();
    expect(wrapper.find('[data-testid="records-busy"]').exists()).toBe(false);
  });
});
