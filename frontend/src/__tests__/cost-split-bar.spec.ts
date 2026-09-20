import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import CostSplitBar from '@/components/CostSplitBar.vue';

/**
 * #1097: the composition bar under a money figure. What has to hold is not the
 * drawing but the claims: the segments are shares of the split, the bubble names
 * every dimension (colour alone never carries identity), and a missing split says so
 * instead of printing four zeroes.
 */
describe('CostSplitBar', () => {
  function render(props: Record<string, unknown>) {
    return mount(CostSplitBar, {
      props: {
        label: '分摊成本',
        total: 0,
        input: 0,
        output: 0,
        cacheRead: 0,
        cacheCreation: 0,
        ...props,
      },
    });
  }

  const widthOf = (style: string | undefined) =>
    Number(/width:\s*([\d.]+)%/.exec(style ?? '')?.[1]);

  it('sizes each segment as a share of the split, in fixed order', async () => {
    const wrapper = render({
      total: 1.5,
      input: 0.9,
      output: 0.4,
      cacheRead: 0.1,
      cacheCreation: 0.1,
    });

    const segments = wrapper.findAll('.cost-split__seg');
    expect(segments.map((s) => s.attributes('data-testid'))).toEqual([
      'cost-split-input',
      'cost-split-output',
      'cost-split-cacheRead',
      'cost-split-cacheCreation',
    ]);
    expect(widthOf(segments[0]!.attributes('style'))).toBeCloseTo(60, 5);
    expect(widthOf(segments[1]!.attributes('style'))).toBeCloseTo(26.67, 1);
    expect(widthOf(segments[2]!.attributes('style'))).toBeCloseTo(6.67, 1);
  });

  it('spells the amounts out, so identity never rests on colour', async () => {
    const wrapper = render({
      total: 1.5,
      input: 0.9,
      output: 0.4,
      cacheRead: 0.1,
      cacheCreation: 0.1,
    });

    expect(wrapper.find('[data-testid="cost-split-bar"]').attributes('aria-label')).toBe(
      '分摊成本构成：输入 ¥0.9000 · 输出 ¥0.4000 · 缓存读 ¥0.1000 · 缓存写 ¥0.1000（合计 ¥1.5000）',
    );
  });

  it('draws no segment for a zero dimension but still names it', async () => {
    // "Did we spend nothing on cache reads?" is a question the bubble has to answer,
    // while a 2px gap around an empty segment would read as a missing category.
    const wrapper = render({ total: 1, input: 1, output: 0, cacheRead: 0, cacheCreation: 0 });

    expect(wrapper.findAll('.cost-split__seg')).toHaveLength(1);
    expect(wrapper.find('[data-testid="cost-split-bar"]').attributes('aria-label')).toContain(
      '缓存读 ¥0.0000',
    );
  });

  it('says the split is unavailable rather than claiming four zeroes', async () => {
    const wrapper = render({ total: 1.5 });

    expect(wrapper.findAll('.cost-split__seg')).toHaveLength(0);
    expect(wrapper.find('.cost-split__empty').exists()).toBe(true);
    expect(wrapper.find('[data-testid="cost-split-bar"]').attributes('aria-label')).toBe(
      '分摊成本构成：暂无可用明细（合计 ¥1.5000）',
    );
  });

  it('treats a zero dimension split (nothing priced) as nothing to draw', async () => {
    const wrapper = render({ total: 0 });

    expect(wrapper.findAll('.cost-split__seg')).toHaveLength(0);
    expect(wrapper.find('[data-testid="cost-split-bar"]').attributes('aria-label')).toContain(
      '暂无可用明细',
    );
  });
});
