import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import UiDonut from '@/ui/Donut.vue';

describe('UiDonut', () => {
  it('renders a conic gradient with one stop per segment and the centre text', () => {
    const wrapper = mount(UiDonut, {
      props: {
        segments: [
          { label: 'A', value: 60 },
          { label: 'B', value: 30 },
          { label: 'C', value: 10, color: '#123456' },
        ],
        centerText: '¥20.90',
      },
    });
    const el = wrapper.get('.ui-donut');
    const bg = (el.element as HTMLElement).style.background;
    expect(bg).toContain('conic-gradient');
    // jsdom 30 serializes the gradient's colours the way browsers do: reading
    // the background back yields rgb(...) triples, not the authored hexes.
    expect(bg).toContain('rgb(9, 96, 189)'); // CHART_PALETTE[0]
    expect(bg).toContain('rgb(250, 140, 22)'); // CHART_PALETTE[1]
    expect(bg).toContain('rgb(18, 52, 86)'); // explicit colour wins
    expect(bg).toContain('60.00%');
    expect(wrapper.get('.ui-donut__center').text()).toBe('¥20.90');
  });

  it('falls back to a flat muted ring without segments', () => {
    const wrapper = mount(UiDonut, { props: { segments: [] } });
    expect((wrapper.get('.ui-donut').element as HTMLElement).style.background).toContain(
      'var(--ui-muted)',
    );
  });
});
