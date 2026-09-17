package com.miqroera.miqrokey.domain.usage;

/**
 * The semantic dimension an adjustment corrects (#709 / backlog F20).
 *
 * <p>
 * {@code USAGE} corrections move token counts — the ordinary case, where the
 * tokens we recorded disagree with the provider's bill. {@code COST}
 * corrections move money without moving tokens (price difference, FX, discount,
 * tiered pricing, minimum charge). The {@code COST} vocabulary exists in the
 * V63 table so that adding it later does not require a schema change, but it is
 * deliberately not open for writing yet.
 * </p>
 *
 * <p>
 * The two are mutually exclusive on a single row, enforced by a table CHECK. A
 * row that moved both would be impossible to explain in an audit — "was this a
 * usage fix or a money fix?" — so two rows must be written instead.
 * </p>
 */
public enum AdjustmentType {
    USAGE, COST
}
