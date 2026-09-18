package com.miqroera.miqrokey.controlplane.dto;

/**
 * Outcome of one price-basis backfill pass (#710 / F21-A).
 *
 * <p>
 * {@code unavailable} is reported separately from {@code complete} on purpose:
 * an operator needs to see how much history is genuinely unreconstructable,
 * rather than having it folded into a success count. Those rows keep NULL
 * prices — a silent zero would read as "free" instead of "price unknown".
 * </p>
 *
 * @param scanned
 *            rows that had never been evaluated and were stamped by this pass
 * @param complete
 *            all four token dimensions priced
 * @param partial
 *            some dimensions priced, others had no price in force
 * @param unavailable
 *            no dimension could be priced — the event predates any price we
 *            hold
 * @param baseCostFilled
 *            rows that already held a price basis but no frozen amount, and
 *            whose amount this pass completed (#771). Counted apart from
 *            {@code scanned} because nothing was re-evaluated for them
 * @param reclassified
 *            rows whose stored verdict the rule in force now disagrees with
 *            (#777). Also separate from {@code scanned}: the verdict was
 *            recomputed from the prices the row already froze, so no decision
 *            was revised — only a label brought up to date
 */
public record UsagePriceBackfillResult(long scanned, long complete, long partial, long unavailable, long baseCostFilled,
        long reclassified) {
}
