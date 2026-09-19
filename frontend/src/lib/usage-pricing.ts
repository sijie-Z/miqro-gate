/**
 * Whether a displayed cost is short of a total, and why it is (#801).
 *
 * `usage-accounting` §6 makes a promise the API already keeps: when
 * `pricingStatus != COMPLETE`, the known amount is **not** the total. The console
 * showed the number without that caveat, so an incomplete figure read as a total —
 * the exact misreading the pricing-status layer exists to prevent.
 *
 * Deliberately the same shape as the savings marker (#790): explain the number
 * where it is shown, and stay silent when there is nothing to explain.
 */

export interface PricingGapFields {
  pricingStatus?: string | null;
  unpriced?: {
    unpricedEvents?: number | null;
    unavailableEvents?: number | null;
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
