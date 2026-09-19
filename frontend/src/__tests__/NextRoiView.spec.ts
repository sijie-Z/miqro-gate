import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextRoiView from '@/views/next/NextRoiView.vue';
import * as api from '@/api';
import type { RoiReportView, UsageGroup } from '@/types/generated-api';

vi.mock('@/api', () => ({ getRoiReport: vi.fn(), adminUsageSummary: vi.fn() }));

const mockApi = vi.mocked(api);

const report: RoiReportView = {
  from: '2026-08-01T00:00:00.000Z',
  to: '2026-08-31T00:00:00.000Z',
  totals: {
    upstreamRequests: 12,
    coalescedRequests: 3,
    l1Hits: 5,
    l2Hits: 3,
    savedCost: 1.2345,
    paidCost: 2.5,
    savedPct: 33.05,
    hitRatePct: 40.1,
    pricingStatus: 'COMPLETE',
  },
  byDay: [
    {
      date: '2026-08-01',
      upstreamRequests: 10,
      hitRequests: 6,
      hitRatePct: 37.5,
      paidCost: 0.12,
      savedCost: 0.05,
    },
    {
      date: '2026-08-02',
      upstreamRequests: 8,
      hitRequests: 8,
      hitRatePct: 50,
      paidCost: 0.1,
      savedCost: 0.09,
    },
  ],
};

function lastCall(): [string, string] {
  const calls = (mockApi.getRoiReport as ReturnType<typeof vi.fn>).mock.calls;
  return calls[calls.length - 1] as [string, string];
}

describe('NextRoiView', () => {
  const keyGroups: UsageGroup[] = [
    {
      groupKey: 'k1',
      label: 'claude-code-main',
      requests: { upstream: 4, coalesced: 0, l1Hit: 5, l2Hit: 1 },
      cost: { upstreamPaid: 1.2, savedByGatewayCache: 0.8 },
      pricingStatus: 'COMPLETE',
    },
    {
      groupKey: 'k2',
      label: 'codex-tools',
      requests: { upstream: 20, coalesced: 2, l1Hit: 0, l2Hit: 0 },
      cost: { upstreamPaid: 9.5, savedByGatewayCache: 0 },
      pricingStatus: 'COMPLETE',
    },
  ];

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.getRoiReport.mockResolvedValue(report);
    mockApi.adminUsageSummary.mockResolvedValue({ groupBy: 'VIRTUAL_KEY', groups: keyGroups });
  });

  function mountView() {
    return mount(NextRoiView, { global: { plugins: [createPinia()] } });
  }

  it('caveats the money and shows a dash for the discount when nothing was priced (#858)', async () => {
    mockApi.getRoiReport.mockResolvedValue({
      ...report,
      totals: {
        ...report.totals,
        savedCost: 0,
        paidCost: 0,
        savedPct: null,
        pricingStatus: 'UNAVAILABLE',
        unpriced: { unavailableEvents: 1 },
      },
    } as unknown as RoiReportView);
    const wrapper = mountView();
    await flushPromises();

    const chip = wrapper.find('[data-testid="roi-cost-caveat"]');
    expect(chip.exists()).toBe(true);
    expect(chip.text()).toBe('未定价');
    // 0/0 is undefined; "0.00%" here would read as "caching saved nothing" on the
    // page whose purpose is deciding whether caching pays.
    expect(wrapper.text()).toContain('等效折扣');
    expect(wrapper.text()).toContain('—');
    wrapper.unmount();
  });

  it('#932: an empty window shows — for every hit rate, not 0.00%', async () => {
    // Nothing was served, so there is no rate to report. "0.00%" there reads as "the
    // cache never hit" on the page that decides caching — and the same card already says
    // "—" for the discount in this exact situation (#858). Two shares, one rule.
    mockApi.getRoiReport.mockResolvedValue({
      ...report,
      totals: {
        ...report.totals,
        upstreamRequests: 0,
        coalescedRequests: 0,
        l1Hits: 0,
        l2Hits: 0,
        hitRatePct: null,
        paidCost: 0,
        savedCost: 0,
        savedPct: null,
        pricingStatus: 'COMPLETE',
      },
    } as unknown as RoiReportView);
    const wrapper = mountView();
    await flushPromises();

    const cards = wrapper.findAll('.next-roi__card');
    const cardText = cards.map((c) => c.text()).join(' | ');
    const card = (label: string) => {
      const hit = cards.find((c) => c.text().includes(label));
      return hit ? hit.text() : '';
    };

    expect(card('网关缓存命中率')).toContain('—');
    expect(card('L1 命中')).toContain('—');
    expect(card('L2 命中')).toContain('—');
    // No rate anywhere on the strip is asserted — that is the whole point.
    expect(cardText).not.toContain('%');

    // ...while the *known* zeros stay numbers: 0 requests is a fact, not an unknown.
    expect(card('总请求次数')).toContain('0');
    expect(cardText).toContain('¥0.0000');

    // The composition panel below is the same 0/0 with the same window — it must not
    // keep the claim the cards just gave up.
    const comp = wrapper.find('[data-testid="roi-composition"]');
    expect(comp.text()).toContain('—');
    expect(comp.text()).not.toContain('0.00%');
    wrapper.unmount();
  });

  it('leaves the money unmarked and keeps a real discount when everything was priced', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="roi-cost-caveat"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('33.05%');
    wrapper.unmount();
  });

  it('marks the saving as a lower bound when hits went unpriced, cost complete (#863)', async () => {
    // The shape the API really sends for this case (pinned by the ROI integration
    // test): the usage row priced fine, the hits did not. The cost is COMPLETE, so
    // nothing warns about the amount — and the saving reads ¥0.0000, i.e. "caching
    // saved nothing", on the page whose copy says the data decides the strategy.
    mockApi.getRoiReport.mockResolvedValue({
      ...report,
      totals: {
        ...report.totals,
        savedCost: 0,
        paidCost: 0.006,
        savedPct: 0,
        pricingStatus: 'COMPLETE',
        unpriced: { unpricedHitEvents: 2 },
      },
    } as unknown as RoiReportView);
    const wrapper = mountView();
    await flushPromises();

    const marker = wrapper.find('[data-testid="roi-savings-bound"]');
    expect(marker.exists()).toBe(true);
    expect(marker.text()).toBe('下界');
    // ...and the number it qualifies is still the definite-looking zero.
    expect(wrapper.text()).toContain('¥0.0000');
    // The cost priced in full, so the other marker must stay away.
    expect(wrapper.find('[data-testid="roi-cost-caveat"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('leaves the saving unmarked when every hit could be valued', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="roi-savings-bound"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('shows both markers without saying the same thing twice (#863)', async () => {
    mockApi.getRoiReport.mockResolvedValue({
      ...report,
      totals: {
        ...report.totals,
        pricingStatus: 'PARTIAL',
        unpriced: { unpricedEvents: 3, unavailableEvents: 1, unpricedHitEvents: 4 },
      },
    } as unknown as RoiReportView);
    const wrapper = mountView();
    await flushPromises();

    // Two different assertions, so both stand: the amount is not a total, and the
    // saving is a floor.
    expect(wrapper.find('[data-testid="roi-cost-caveat"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="roi-savings-bound"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('未定价');
    expect(wrapper.text()).toContain('下界');
    wrapper.unmount();
  });

  it('renders the stat strip (value + sub-metric) and per-day rows', async () => {
    const wrapper = mountView();
    await flushPromises();

    const roi = wrapper.find('[data-testid="roi-report"]');
    expect(roi.text()).toContain('总请求次数');
    expect(roi.text()).toContain('23'); // served = 12 + 3 + 5 + 3
    expect(roi.text()).toContain('L1+L2 命中');
    expect(roi.text()).toContain('网关缓存命中率');
    expect(roi.text()).toContain('¥1.2345');
    expect(roi.text()).toContain('¥2.5000');
    expect(roi.text()).toContain('33.05%');
    expect(roi.text()).toContain('40.10%');
    expect(wrapper.text()).toContain('10 / 6');
    expect(wrapper.text()).toContain('¥0.0500');
    expect(wrapper.find('[data-testid="roi-range"]').exists()).toBe(true);
  });

  it('#661: composes hits and misses into the hit-composition panel', async () => {
    const wrapper = mountView();
    await flushPromises();

    const panel = wrapper.find('[data-testid="roi-composition"]');
    expect(panel.exists()).toBe(true);
    const rows = panel.findAll('.next-roi__comp-row');
    expect(rows).toHaveLength(4);
    // Largest share first: upstream misses 12/23 = 52.17%.
    expect(rows[0]!.text()).toContain('上游未命中');
    expect(panel.text()).toContain('52.17%');
    expect(panel.text()).toContain('L1 命中');
    expect(panel.text()).toContain('合并命中');
  });

  it('switches the window and reloads with a 7-day range', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="roi-window-7"]').trigger('click');
    await flushPromises();

    const [fromRaw, toRaw] = lastCall();
    const from = new Date(fromRaw);
    const to = new Date(toRaw);
    expect((to.getTime() - from.getTime()) / (24 * 3600 * 1000)).toBeCloseTo(7, 0);
  });

  it('#661: calendar windows query from local midnight (today / week / month)', async () => {
    const wrapper = mountView();
    await flushPromises();
    const now = new Date();

    await wrapper.find('[data-testid="roi-window-today"]').trigger('click');
    await flushPromises();
    let from = new Date(lastCall()[0]);
    expect(from.getHours()).toBe(0);
    expect(from.getMinutes()).toBe(0);
    expect(from.getDate()).toBe(now.getDate());

    await wrapper.find('[data-testid="roi-window-week"]').trigger('click');
    await flushPromises();
    from = new Date(lastCall()[0]);
    expect(from.getDay()).toBe(1); // Monday start
    expect(from.getHours()).toBe(0);

    await wrapper.find('[data-testid="roi-window-month"]').trigger('click');
    await flushPromises();
    from = new Date(lastCall()[0]);
    expect(from.getDate()).toBe(1);
    expect(from.getHours()).toBe(0);
  });

  it('surfaces a load error alert', async () => {
    mockApi.getRoiReport.mockRejectedValue(new Error('上游不可达'));
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('上游不可达');
  });

  it('#863: the stats tab is the default and the toolbar window drives both tabs', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="roi-report"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="roi-config"]').exists()).toBe(false);
    expect(mockApi.adminUsageSummary).toHaveBeenCalledWith(
      expect.objectContaining({ groupBy: 'VIRTUAL_KEY' }),
    );
  });

  it('#863: the config tab lists per-key cache activity, busiest first', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="roi-tab-config"]').trigger('click');
    const panel = wrapper.find('[data-testid="roi-config"]');
    expect(panel.exists()).toBe(true);

    const rows = panel.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    // codex-tools has 22 served requests vs 10 — it ranks first.
    expect(rows[0]!.text()).toContain('codex-tools');
    expect(rows[0]!.text()).toContain('¥9.5000');
    expect(rows[1]!.text()).toContain('claude-code-main');
    expect(rows[1]!.text()).toContain('60.00%'); // 6 of 10 served
    expect(rows[1]!.text()).toContain('¥0.8000');
    expect(panel.text()).toContain('去「我的密钥」管理缓存开关');
  });

  it('#863: config tab explains the opt-in path when the window has no keys', async () => {
    mockApi.adminUsageSummary.mockResolvedValue({ groupBy: 'VIRTUAL_KEY', groups: [] });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="roi-tab-config"]').trigger('click');
    const panel = wrapper.find('[data-testid="roi-config"]');
    expect(panel.text()).toContain('该窗口内没有按密钥的用量记录');
    expect(panel.text()).toContain('X-MiqroKey-Cacheable');
  });

  it('#863: a per-key aggregation failure never takes the stats tab down', async () => {
    mockApi.adminUsageSummary.mockRejectedValue(new Error('聚合超时'));
    const wrapper = mountView();
    await flushPromises();

    // Stats still render…
    expect(wrapper.find('[data-testid="roi-report"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('聚合超时');

    // …and the config tab surfaces the degradation instead of pretending zero.
    await wrapper.find('[data-testid="roi-tab-config"]').trigger('click');
    expect(wrapper.find('[data-testid="roi-config-error"]').text()).toContain('聚合超时');
  });
});
