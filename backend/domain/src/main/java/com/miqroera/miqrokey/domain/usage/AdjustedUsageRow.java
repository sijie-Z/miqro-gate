package com.miqroera.miqrokey.domain.usage;

/**
 * One usage event together with its adjusted (net) token counts — the financial
 * reading of the row, used by the usage detail list (#709 / backlog F20).
 *
 * <p>
 * {@code observed} stays exactly the fact the gateway recorded; the net counts
 * are that fact plus every adjustment booked against it. Both are carried so a
 * reader can always tell the two apart, and {@code adjusted} says whether
 * anything was corrected at all — a row whose net happens to equal its observed
 * counts is not necessarily unadjusted.
 * </p>
 *
 * <p>
 * This is the reporting reading only. Quota enforcement deliberately keeps
 * reading {@code observed} usage: a financial correction must not retroactively
 * rewrite what the runtime already decided.
 * </p>
 *
 * @param netInputTokens
 *            normalized the same way the aggregates normalize it
 *            ({@code COALESCE(input_tokens, prompt_tokens)}), plus the booked
 *            deltas. Null when the row has neither an observed nor an adjusted
 *            value — "no data" is not the same as zero tokens
 * @param providerProductName
 *            display name of the provider product the row routed to (#758)
 * @param lifecycle
 *            lifecycle facts joined from {@code request_usage_records}
 *            (protocol / first-byte time / terminal status); null when the call
 *            has no lifecycle row (coalesced requests never do)
 * @param priceBasis
 *            the unit prices this row is costed with — its own frozen prices,
 *            else the prices in force at its {@code occurred_at} (#710).
 *            Carried on the row, not looked up by the reader, so a price
 *            published later cannot change what this row costs
 */
public record AdjustedUsageRow(UsageEvent observed, Long netInputTokens, Long netOutputTokens,
        Long netCacheReadInputTokens, Long netCacheCreationInputTokens, boolean adjusted, String providerProductName,
        LifecycleInfo lifecycle, RowPriceBasis priceBasis) {

    public AdjustedUsageRow {
        if (observed == null) {
            throw new IllegalArgumentException("observed must not be null");
        }
        // A missing basis and a basis with no known price say the same thing, so
        // normalize instead of making every reader null-check.
        priceBasis = priceBasis == null ? RowPriceBasis.UNKNOWN : priceBasis;
    }
}
