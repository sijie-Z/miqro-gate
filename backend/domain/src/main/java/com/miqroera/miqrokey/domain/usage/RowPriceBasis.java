package com.miqroera.miqrokey.domain.usage;

import java.math.BigDecimal;

/**
 * The unit prices one usage row is priced with (#710 / F21-A).
 *
 * <p>
 * The basis is <b>the row's own frozen price, falling back to the price in
 * force at that row's {@code occurred_at}</b> — the single rule stated in
 * {@code docs/usage-accounting.md} §6. The repository materialises it as
 * {@code COALESCE(usage_event.price_*, <as-of price_snapshot>)} via
 * {@code PriceSnapshotSql}, which is what keeps the detail list and the
 * aggregates from drifting apart: both read the same expression, so a price
 * published later cannot move either.
 * </p>
 *
 * <p>
 * A {@code null} component means "no price was known for that token type" — a
 * different fact from a zero unit price, and the two must not be conflated
 * (docs/usage-accounting.md §6.2). A row with a null price for a dimension it
 * actually used is reported as 未定价 rather than valued at 0.
 * </p>
 *
 * @param input
 *            price per million input tokens, null when no price was known
 * @param output
 *            price per million output tokens, null when no price was known
 * @param cacheRead
 *            price per million cache-read tokens, null when no price was known
 * @param cacheCreation
 *            price per million cache-creation tokens, null when no price was
 *            known
 */
public record RowPriceBasis(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheCreation) {

    /** A row for which no price was known on any token type. */
    public static final RowPriceBasis UNKNOWN = new RowPriceBasis(null, null, null, null);

    /** Unit price for one token type, or null when this row has no price for it. */
    public BigDecimal unitPrice(PriceTokenType type) {
        return switch (type) {
            case INPUT -> input;
            case OUTPUT -> output;
            case CACHE_READ -> cacheRead;
            case CACHE_CREATION -> cacheCreation;
        };
    }
}
