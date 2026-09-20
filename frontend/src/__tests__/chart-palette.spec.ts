import { describe, expect, it } from 'vitest';
import { CHART_OTHER_COLOR, CHART_PALETTE, COST_SPLIT_COLORS } from '@/lib/chart-palette';

/**
 * The palette is an interface, not a decoration: four charts read the same slots, and
 * the cost split's meaning ("this slice is 缓存写") is carried by which slot it sits
 * in. #1110 swapped two of those slots while reordering the list and nothing noticed
 * — the specs checked labels, not colours. These pin the mapping so the next reorder
 * has to be deliberate.
 */
describe('chart palette', () => {
  it('has four distinct slots, in the documented order', () => {
    expect([...CHART_PALETTE]).toEqual(['#0960bd', '#fa8c16', '#13c2c2', '#2f9e44']);
  });

  it('maps each token dimension to its own slot, in reading order', () => {
    expect(COST_SPLIT_COLORS).toEqual({
      input: CHART_PALETTE[0],
      output: CHART_PALETTE[1],
      cacheRead: CHART_PALETTE[2],
      cacheCreation: CHART_PALETTE[3],
    });
  });

  it('keeps the neutral out of the categorical set', () => {
    expect(CHART_PALETTE).not.toContain(CHART_OTHER_COLOR);
  });
});
