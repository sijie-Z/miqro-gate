package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.usage.PriceTokenType;

/**
 * The one definition of "what price was in force for this row" (#710 / F21-A).
 *
 * <p>
 * Three places need it — the repository's point lookup, the historical
 * backfill, and the cost aggregates — so it lives here rather than being
 * re-typed per caller. The tie-break ({@code effective_from DESC, id DESC}) is
 * the same rule {@code PriceSnapshotRepositoryImpl} applies; getting it wrong
 * in one place is exactly how a replay stops being reproducible.
 * </p>
 *
 * <p>
 * The round trip is guarded by tests that compare this helper's verdict against
 * the repository's for the same rows — the test, not a comment, is what keeps
 * the two honest.
 * </p>
 */
public final class PriceSnapshotSql {

    private PriceSnapshotSql() {
    }

    /**
     * Scalar subquery: the unit price in force for one token dimension at a given
     * instant.
     *
     * <p>
     * Returns at most one row, and {@code NULL} when no price was in force yet —
     * callers must treat that as "unpriced", never as zero.
     * </p>
     */
    public static String asOfUnitPrice(PriceTokenType type, String productExpr, String modelExpr, String atExpr) {
        return "(SELECT ps.unit_price FROM price_snapshot ps" + " WHERE ps.provider_product_id = " + productExpr
                + " AND ps.model_id = " + modelExpr + " AND ps.token_type = '" + type.name() + "'"
                + " AND ps.effective_from <= " + atExpr + " ORDER BY ps.effective_from DESC, ps.id DESC LIMIT 1)";
    }

    /**
     * The price to charge for one token dimension: the row's own frozen price when
     * it has one, otherwise the price in force at that row's instant.
     *
     * <p>
     * The frozen column is preferred because it is immune to <em>both</em> later
     * price additions and edits of historical price rows, whereas the as-of lookup
     * only survives the former. Un-backfilled rows therefore fall back to a value
     * that is correct today but could still move if someone rewrote history — which
     * is why the backfill runs before the reads are switched over.
     * </p>
     */
    public static String frozenOrAsOf(String frozenPriceColumn, PriceTokenType type, String productExpr,
            String modelExpr, String atExpr) {
        return "COALESCE(" + frozenPriceColumn + ", " + asOfUnitPrice(type, productExpr, modelExpr, atExpr) + ")";
    }
}
