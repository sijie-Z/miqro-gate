/**
 * The console's categorical chart palette.
 *
 * One shared order, so the same category gets the same colour in every chart. The
 * order is fixed and never cycled: a series past the last slot folds into 「其他」
 * (see {@link CHART_OTHER_COLOR}), never back to slot 1.
 *
 * These four are the ones validated against the light chart surface (`#ffffff`)
 * with the design-system palette checker (#1097):
 *
 *   worst adjacent pair ΔE 27.6 normal vision / 18.7 protan — both well clear of the
 *   checker's 15 / 8 floors; all four inside the lightness band and above the chroma
 *   floor.
 *
 * The teal and orange measure under 3:1 contrast against white (2.15 / 2.32), so any
 * mark using them must carry visible labels; every caller here prints the amounts as
 * text as well.
 *
 * The list this replaced was inlined in the donut and the usage page and did **not**
 * pass for four slots: its second colour (`#69c0ff`) sat at ΔE 10.7 from the teal
 * beside it — two touching segments that full-colour readers struggle to tell apart —
 * and outside the lightness band. Consolidating those two copies onto this module is
 * tracked in #1112; this PR only adds the new mark.
 */
export const CHART_PALETTE = ['#0960bd', '#13c2c2', '#fa8c16', '#722ed1'] as const;

/**
 * The neutral for 「其他」/「unknown」 buckets, deliberately **outside** the
 * categorical set: a grey carries no identity, which is exactly what a residual
 * bucket should say. It is not a palette slot — the checker rejects it as one (zero
 * chroma, and `#d9d9d9` additionally sits outside the lightness band), and reusing a
 * hue for "everything else" would imply the bucket means something specific.
 */
export const CHART_OTHER_COLOR = '#8c8c8c';

/** The four token dimensions a cost figure is split into, in fixed order. */
export const COST_SPLIT_COLORS = {
  input: CHART_PALETTE[0],
  output: CHART_PALETTE[1],
  cacheRead: CHART_PALETTE[2],
  cacheCreation: CHART_PALETTE[3],
} as const;
