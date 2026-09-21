import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { UiTrendChart } from '@/ui';

describe('UiTrendChart', () => {
  it('renders the area path, line and per-point dots for the given series', () => {
    const wrapper = mount(UiTrendChart, {
      props: {
        points: [
          { label: '09-01', value: 10 },
          { label: '09-02', value: 30 },
          { label: '09-03', value: 20 },
        ],
      },
    });
    expect(wrapper.findAll('path').length).toBe(2); // area + line
    expect(wrapper.findAll('circle').length).toBe(3);
    expect(wrapper.text()).toContain('09-01');
    expect(wrapper.text()).toContain('09-03');
  });

  it('shows the empty state when there are no points', () => {
    const wrapper = mount(UiTrendChart, { props: { points: [] } });
    expect(wrapper.text()).toContain('暂无趋势数据');
    expect(wrapper.find('svg').exists()).toBe(false);
  });

  it('samples the x-axis labels down to the requested count', () => {
    const points = Array.from({ length: 14 }, (_, i) => ({ label: `d${i}`, value: i + 1 }));
    const wrapper = mount(UiTrendChart, { props: { points, xLabelCount: 4 } });
    expect(wrapper.findAll('.ui-trend__x-label').length).toBe(4);
  });

  it('survives an all-zero series (guarded against divide-by-zero)', () => {
    const wrapper = mount(UiTrendChart, {
      props: {
        points: [
          { label: 'a', value: 0 },
          { label: 'b', value: 0 },
        ],
      },
    });
    expect(wrapper.findAll('circle').length).toBe(2);
  });
});
