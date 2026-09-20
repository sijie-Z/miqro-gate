/**
 * The console's categorical chart palette.
 *
 * One shared order, so the same category gets the same colour in every chart. The
 * order is fixed and never cycled: a seventh series folds into 「其他」 rather than
 * re-using slot 1.
 *
 * Validated against the light chart surface (`#ffffff`) with the design-system
 * palette checker (#1097). The list this replaced was inlined in the donut and the
 * usage page and did **not** pass for four slots: its second colour (`#69c0ff`) sat
 * at ΔE 10.7 from the teal beside it — below the checker's hard floor of 15, i.e.
 * two touching segments that full-colour readers struggle to tell apart — and
 * outside the lightness band. The teal and orange still measure under 3:1 contrast
 * against white, so any mark using them must carry visible labels; every caller
 * here prints the amounts as text as well.
 */
export const CHART_PALETTE = [
  '#0960bd',
  '#13c2c2',
  '#fa8c16',
  '#722ed1',
  '#8c8c8c',
  '#d9d9d9',
] as const;

/** The four token dimensions a cost figure is split into, in fixed order. */
export const COST_SPLIT_COLORS = {
  input: CHART_PALETTE[0],
  output: CHART_PALETTE[1],
  cacheRead: CHART_PALETTE[2],
  cacheCreation: CHART_PALETTE[3],
} as const;
