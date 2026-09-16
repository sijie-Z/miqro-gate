import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextRoiView from '@/views/next/NextRoiView.vue';
import * as api from '@/api';
import type { RoiReportView } from '@/types/generated-api';

vi.mock('@/api', () => ({ getRoiReport: vi.fn() }));

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
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.getRoiReport.mockResolvedValue(report);
  });

  function mountView() {
    return mount(NextRoiView, { global: { plugins: [createPinia()] } });
  }

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
});
