import { describe, expect, it } from 'vitest';
import { readFileSync, globSync } from 'node:fs';
import {
  CHART_OTHER_COLOR,
  CHART_PALETTE,
  CHART_SUCCESS_COLOR,
  CHART_TONE_COLORS,
  COST_SPLIT_COLORS,
} from '@/lib/chart-palette';

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

  it('draws the success fill with the design system token, not an ad-hoc green', () => {
    // #1126: the green that had been copied into five views measured ΔE 0.4 protan
    // against the warning gold — identical to a red-green colourblind reader. Pinned
    // to the `--ui-success-fg` value so the chart green and the badge green cannot
    // drift apart again; the module docstring carries the measurements.
    expect(CHART_SUCCESS_COLOR).toBe('#0d6a3d');
  });

  it('keeps every status fill on the shared map, with the success slot on that green', () => {
    expect(CHART_TONE_COLORS).toEqual({
      success: CHART_SUCCESS_COLOR,
      warning: '#d48806',
      danger: '#cf1322',
      info: CHART_PALETTE[0],
      neutral: CHART_OTHER_COLOR,
    });
  });

  it('keeps the retired green out of every view and component', () => {
    // The five copies were reached for by hand, so the realistic regression is another
    // hand-reach. This is a denylist and says so: it catches the spelling, not the
    // colour — a freshly invented green at the same hue would pass it. `chart-palette.ts`
    // itself is excluded because that is where the retirement is documented, in prose;
    // the exclusion is separator-safe because globSync hands back backslashes on Windows
    // and a plain `endsWith('lib/chart-palette.ts')` silently matched nothing.
    const sources = globSync('src/**/*.{css,vue,ts}').filter(
      (file) =>
        !file.includes('__tests__') &&
        !/types[\\/]generated/.test(file) &&
        !/lib[\\/]chart-palette\.ts$/.test(file),
    );
    expect(sources.length).toBeGreaterThan(0);

    const hits = sources.filter((file) => /#389e0d/i.test(readFileSync(file, 'utf-8')));
    expect(hits).toEqual([]);
  });
});
