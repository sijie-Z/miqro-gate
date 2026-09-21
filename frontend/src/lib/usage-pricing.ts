/**
 * Whether a displayed money figure is short of the whole, and why it is (#801/#790).
 *
 * `usage-accounting` §6 makes a promise the API already keeps: when
 * `pricingStatus != COMPLETE`, the known amount is **not** the total. The console
 * showed the number without that caveat, so an incomplete figure read as a total —
 * the exact misreading the pricing-status layer exists to prevent.
 *
 * Deliberately the same shape as the savings marker (#790): explain the number
 * where it is shown, and stay silent when there is nothing to explain.
 *
 * Both markers live here on purpose (#863). They answer different questions, and the
 * API keeps them independent — `pricingStatus` describes the *cost*, while
 * `unpricedHitEvents` describes the *saving* — so any view showing money has to ask
 * both. While only one of them had a shared home, the other was re-implemented per
 * view, and the view that forgot it shipped a lower bound presented as a figure.
 */

export interface PricingGapFields {
  pricingStatus?: string | null;
  unpriced?: {
    unpricedEvents?: number | null;
    unavailableEvents?: number | null;
    unpricedHitEvents?: number | null;
  } | null;
}

/**
 * The bubble text for a summary whose cost could not be fully priced, or null when
 * there is nothing to say.
 *
 * Anything other than an explicit `PARTIAL` / `UNAVAILABLE` yields null: the marker
 * is for a *known* gap, and showing it on a guess would put a caveat on figures
 * that have none.
 */
export function costGapNote(totals?: PricingGapFields | null): string | null {
  const status = totals?.pricingStatus;
  if (status !== 'PARTIAL' && status !== 'UNAVAILABLE') {
    return null;
  }
  const unpriced = Number(totals?.unpriced?.unpricedEvents ?? 0);
  const unavailable = Number(totals?.unpriced?.unavailableEvents ?? 0);
  if (status === 'UNAVAILABLE') {
    return '这些用量在发生时都没有生效价目，金额无从得知——已显示的金额不是总额';
  }
  return `${unpriced} 个事件的用量没有生效价目（其中 ${unavailable} 个完全无法定价），已知金额只含可定价的部分——不是总额`;
}

/**
 * How many cache hits the gateway could not value (#790).
 *
 * A hit whose tokens are real but whose price was not in force when it happened
 * contributes nothing to `savedByGatewayCache`. This count is the only thing that
 * separates "the cache saved almost nothing" from "there was no price to say what it
 * saved" — the two read identically without it.
 */
export function unpricedHitCount(totals?: PricingGapFields | null): number {
  return Number(totals?.unpriced?.unpricedHitEvents ?? 0);
}

/**
 * The bubble text for a saving that is only a floor, or null when the saving could be
 * priced in full.
 *
 * Deliberately **not** folded into {@link costGapNote}: the two make different claims
 * — "the amount shown is not a total" versus "the saving is a lower bound" — and the
 * API keeps them independent on purpose. A group whose usage priced fine but whose
 * *hits* did not reports `pricingStatus = COMPLETE`, so `costGapNote` returns null for
 * exactly the case this one exists to catch. That is why a view showing a saving has
 * to ask this question separately, and why the question lives here rather than in
 * each view (#863).
 */
export function savingsBoundNote(totals?: PricingGapFields | null): string | null {
  const hits = unpricedHitCount(totals);
  if (hits <= 0) {
    return null;
  }
  return `${hits} 次命中在发生时没有生效价目，无法计价——节省额只是下界，不是全部`;
}
