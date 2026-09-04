import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextRoiView from '@/views/next/NextRoiView.vue';
import * as api from '@/api';
import type { RoiReportView } from '@/types/api';

vi.mock('@/api', () => ({ getRoiReport: vi.fn() }));

const mockApi = vi.mocked(api);

const report: RoiReportView = {
  from: '2026-08-01T00:00:00.000Z',
  to: '2026-08-31T00:00:00.000Z',
  totals: {
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

describe('NextRoiView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.getRoiReport.mockResolvedValue(report);
  });

  function mountView() {
    return mount(NextRoiView, { global: { plugins: [createPinia()] } });
  }

  it('renders total cards and per-day rows with currency formatting', async () => {
    const wrapper = mountView();
    await flushPromises();

    const roi = wrapper.find('[data-testid="roi-report"]');
    expect(roi.text()).toContain('¥1.2345');
    expect(roi.text()).toContain('¥2.5000');
    expect(roi.text()).toContain('33.05%');
    expect(roi.text()).toContain('40.10%');
    expect(wrapper.text()).toContain('10 / 6');
    expect(wrapper.text()).toContain('¥0.0500');
  });

  it('switches the window and reloads with a 7-day range', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="roi-window-7"]').trigger('click');
    await flushPromises();

    const lastCall = (mockApi.getRoiReport as ReturnType<typeof vi.fn>).mock.calls.at(-1) as [
      string,
      string,
    ];
    const from = new Date(lastCall[0]);
    const to = new Date(lastCall[1]);
    expect((to.getTime() - from.getTime()) / (24 * 3600 * 1000)).toBeCloseTo(7, 0);
  });

  it('surfaces a load error alert', async () => {
    mockApi.getRoiReport.mockRejectedValue(new Error('上游不可达'));
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('上游不可达');
  });
});
