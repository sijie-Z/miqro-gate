import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminRoiView from '@/views/AdminRoiView.vue';
import * as api from '@/api';
import type { RoiReportView } from '@/types/api';

vi.mock('@/api', () => ({
  getRoiReport: vi.fn(),
}));

const mockApi = vi.mocked(api);

const report: RoiReportView = {
  from: '2026-08-03T00:00:00Z',
  to: '2026-09-02T00:00:00Z',
  totals: {
    upstreamRequests: 100,
    coalescedRequests: 0,
    l1Hits: 20,
    l2Hits: 30,
    hitRatePct: 33.33,
    paidCost: 0.006,
    savedCost: 0.003,
    savedPct: 33.33,
  },
  byDay: [
    {
      date: '2026-09-02',
      upstreamRequests: 100,
      hitRequests: 50,
      hitRatePct: 33.33,
      paidCost: 0.006,
      savedCost: 0.003,
    },
  ],
};

function mountView() {
  return mount(AdminRoiView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('AdminRoiView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.getRoiReport.mockResolvedValue(report);
  });

  it('renders ROI cards and the daily table', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="roi-report"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('¥0.0030');
    expect(wrapper.text()).toContain('¥0.0060');
    expect(wrapper.text()).toContain('33.33%');
    const table = wrapper.find('[data-testid="roi-daily-table"]');
    expect(table.exists()).toBe(true);
    expect(table.text()).toContain('2026-09-02');
  });

  it('reloads with the selected window', async () => {
    const wrapper = mountView();
    await flushPromises();

    const buttons = wrapper.findAll('.t-radio-button');
    const week = buttons.find((b) => b.text().includes('近 7 天'));
    expect(week, '7-day window should render').toBeTruthy();
    await week!.trigger('click');
    await flushPromises();

    expect(mockApi.getRoiReport).toHaveBeenCalledTimes(2);
    const fromArg = mockApi.getRoiReport.mock.calls[1][0] as string;
    const toArg = mockApi.getRoiReport.mock.calls[1][1] as string;
    const windowMs = new Date(toArg).getTime() - new Date(fromArg).getTime();
    expect(windowMs).toBe(7 * 24 * 3600 * 1000);
  });

  it('exports the daily series as CSV with a BOM', async () => {
    const createObjectURL = vi.fn(() => 'blob:roi');
    const revokeObjectURL = vi.fn();
    const click = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });

    const wrapper = mountView();
    await flushPromises();

    // Spy after mount — Vue itself creates elements during render.
    const createElement = vi.spyOn(document, 'createElement').mockReturnValue({
      click,
      setAttribute: vi.fn(),
      href: '',
      download: '',
    } as unknown as HTMLAnchorElement);
    await wrapper.find('[data-testid="roi-export"]').trigger('click');

    expect(createElement).toHaveBeenCalledWith('a');
    expect(click).toHaveBeenCalledTimes(1);
  });
});
