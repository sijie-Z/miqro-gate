/**
 * The console's categorical chart palette.
 *
 * One shared order, so the same category gets the same colour in every chart. The
 * order is fixed and never cycled: a series past the last slot folds into 「其他」
 * (see {@link CHART_OTHER_COLOR}), never back to slot 1.
 *
 * Validated against the **card surface** (`#ffffff`, `--ui-card`) with the
 * design-system palette checker:
 *
 *   worst adjacent pair ΔE 17.2 normal vision / 16.6 deutan / 13.9 tritan — clear of
 *   the 15 / 8 floors; all four inside the lightness band and above the chroma floor.
 *   The checker measures `[i, i+1]` pairs only, so the ring's wrap-around pair was
 *   measured separately: slot 4 ↔ slot 1 is green ↔ blue, ΔE 29.2.
 *   Contrast: teal 2.21 and orange 2.38 against white — under 3:1, so any mark using
 *   them must carry visible labels; every caller here prints the amounts as text.
 *
 * Order matters (adjacency is what the checker measures). Blue stays first because
 * the cost bar's natural reading order is input → output → cache, and with orange
 * pinned second the remaining two hues only separate if teal and green are adjacent
 * (orange next to green measures ΔE 4.5 protan — a hard fail).
 *
 * The product has a second green for savings — {@link CHART_SUCCESS_COLOR}, ΔE 16.2
 * from slot 4 — and slot 4 is itself the theme's 绿 preset (`preferences/index.ts`),
 * so green carries meaning outside these charts and the two had better stay visibly
 * apart. Green is the only hue left in the product's family that clears the adjacency
 * floors (gold measures ΔE 2.8 protan against green; purple is banned below), and every
 * caller prints the amounts as text, so identity never rests on the colour alone.
 *
 * No purple: `frontend-design.md` §4.1 allows it only in the supplier chip palette,
 * and `aesthetic.spec.ts` enforces that. The first version of this module shipped
 * `#722ed1` in slot 4 — the old guard scanned CSS only and matched a fixed hex list,
 * so it read as compliant. The guard now scans component sources and judges the hue
 * of every ordinary colour spelling, which is what caught it.
 *
 * The palette this replaced was inlined in four places (the overview page, the usage
 * page, the donut and one trend chart) and did not pass for four slots: its second
 * colour (`#69c0ff`) sat at ΔE 10.7 from the teal beside it — two touching segments
 * that full-colour readers struggle to tell apart — and outside the lightness band.
 */
export const CHART_PALETTE = ['#0960bd', '#fa8c16', '#13c2c2', '#2f9e44'] as const;

/**
 * The neutral for 「其他」/「unknown」 buckets, deliberately **outside** the
 * categorical set: a grey carries no identity, which is exactly what a residual
 * bucket should say. It is not a palette slot — the checker rejects zero-chroma
 * entries as categorical colours — and reusing a hue for "everything else" would
 * imply the bucket means something specific.
 *
 * It is also what a caller gets for any segment past the last slot, so a chart taking
 * more categories than there are hues must either fold them into one 「其他」 or pass
 * explicit colours: two uncoloured segments would otherwise share this grey and read
 * as one band.
 */
export const CHART_OTHER_COLOR = '#8c8c8c';

/**
 * The success/savings green, as a chart **fill** — the same value as the
 * `--ui-success-fg` token the status badges and toasts already draw with, so "green"
 * reads the same in status chrome and in a chart.
 *
 * It replaces the ad-hoc `#389e0d`, which had been copied into five places across four
 * views. That hex measures **ΔE 0.4 protan** against the warning gold `#d48806` — the
 * same colour to a red-green colourblind reader. The two meet in two places, in
 * different ways: the reconciliation ring is fixed-order `MATCHED → PARTIAL` drawn
 * gap-less, so the bands genuinely touch; the ROI page sorts its hit buckets by value,
 * so the two can become neighbouring rows of a list whose whole job is comparing a row
 * against its neighbours — 16px apart in separate labelled tracks, which is why this
 * is the weaker of the two cases but still a case.
 *
 * Re-measured with the design-system checker (light, surface `#ffffff`):
 *
 *   ROI hit trio (this green, slot 1, the gold) — worst all-pairs ΔE **17.2 protan**
 *     at the gold ↔ green pair (was 0.4), 21.6 normal vision.
 *   Reconciliation ring (this green, gold, `#cf1322`, `#ff7875`) — worst adjacent
 *     ΔE 14.3 deutan, 19.4 normal. The wrap-around pair (light red ↔ this green) is
 *     16.4 protan.
 *   Success ↔ failure (this green, `#cf1322`) — ΔE 6.4 protan, inside the checker's
 *     6–8 band. That band asks for a second channel; these callers supply one by
 *     naming every segment in a legend beside the mark rather than on it, and the hex
 *     it replaced failed this same pair outright (5.8 deutan), so this narrows the gap
 *     rather than closing it.
 *
 * Contrast 6.67:1 on the card surface, against 3.46:1 for the old green. That is also
 * why this green and not the brighter `--miqrokey-success` (`#00b96b`): both clear the
 * CVD floors, but that one measures 2.58:1 and would need relief it does not get.
 */
export const CHART_SUCCESS_COLOR = '#0d6a3d';

/**
 * Fill colours for **status/verdict** segments — what a segment *means* rather than
 * which series it is. Three views had each written this map out by hand (the exports
 * page had exactly these five keys), which is how one unsafe green survived in five
 * copies; the success slot is the one that had to be corrected.
 *
 * `warning` is also a theme preset (`preferences/index.ts`), so it is pinned to the
 * same hex the preset uses rather than re-tuned here.
 */
export const CHART_TONE_COLORS = {
  success: CHART_SUCCESS_COLOR,
  warning: '#d48806',
  danger: '#cf1322',
  info: CHART_PALETTE[0],
  neutral: CHART_OTHER_COLOR,
} as const;

/** The four token dimensions a cost figure is split into, in fixed order. */
export const COST_SPLIT_COLORS = {
  input: CHART_PALETTE[0],
  output: CHART_PALETTE[1],
  cacheRead: CHART_PALETTE[2],
  cacheCreation: CHART_PALETTE[3],
} as const;
