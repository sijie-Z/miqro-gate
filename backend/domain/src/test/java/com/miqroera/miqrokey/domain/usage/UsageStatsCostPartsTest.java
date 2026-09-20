package com.miqroera.miqrokey.domain.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.Cost;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.CostParts;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.HitAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingGap;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingStatus;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cost split adds up to the cost it splits (#1097).
 *
 * <p>
 * The console draws a stacked bar under a money figure and lets the reader
 * decide whether a bill is output-heavy or cache-read-heavy. That is only
 * honest if the segments sum to the figure <em>exactly</em> — a bar that lands
 * a cent off its own number is worse than no bar, because the reader cannot
 * tell a rounding artefact from a discrepancy in the data.
 * </p>
 *
 * <p>
 * The parts are accumulated from the same already-divided per-row amounts as
 * the totals ({@code CostParts.total()} <em>is</em> the row cost), so
 * reconciliation is true by construction. These tests pin that construction: if
 * someone later sums the split from token totals, or divides the parts again,
 * the last assertion in each case fails even though every number still looks
 * plausible.
 * </p>
 */
@DisplayName("Cost split reconciles with the figure it splits")
class UsageStatsCostPartsTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final String MODEL = "m";

    @Test
    @DisplayName("the four parts of an upstream row sum to upstreamPaid, exactly")
    void upstreamPartsAddUpToUpstreamPaid() {
        // Undivided tokens × unit_price, as the SQL layer produces them: /1e6 gives
        // 3 + 1.5 + 0.5 + 0.25 = 5.25 CNY.
        UsageSummary summary = aggregate(row(CacheLevel.UPSTREAM, "3000000", "1500000", "500000", "250000"));

        Cost cost = summary.totals().cost();
        CostParts parts = cost.upstreamPaidParts();
        assertThat(parts.input()).isEqualByComparingTo("3");
        assertThat(parts.output()).isEqualByComparingTo("1.5");
        assertThat(parts.cacheRead()).isEqualByComparingTo("0.5");
        assertThat(parts.cacheCreation()).isEqualByComparingTo("0.25");
        assertThat(parts.total()).as("the split must add up to the figure it splits")
                .isEqualByComparingTo(cost.upstreamPaid());
        assertThat(parts.total()).isEqualByComparingTo("5.25");
        // A lone upstream row is also the whole observed total.
        assertThat(cost.gatewayObservedParts().total()).isEqualByComparingTo(cost.gatewayObserved());
    }

    @Test
    @DisplayName("coalesced spend lands in the observed split only — the two totals cover different rows")
    void coalescedSpendLandsOnlyInTheObservedParts() {
        UsageSummary summary = UsageStatsAggregator.aggregate("model",
                List.of(row(CacheLevel.UPSTREAM, "1000000", "0", "0", "0"),
                        row(CacheLevel.COALESCED, "0", "2000000", "0", "0")),
                List.of());

        Cost cost = summary.totals().cost();
        // Reached the provider: 1.0 CNY of input.
        assertThat(cost.upstreamPaidParts().total()).isEqualByComparingTo(cost.upstreamPaid());
        assertThat(cost.upstreamPaidParts().input()).isEqualByComparingTo("1");
        assertThat(cost.upstreamPaidParts().output()).as("the coalesced row never reached the provider")
                .isEqualByComparingTo("0");
        // Observed: both rows, 1.0 + 2.0.
        assertThat(cost.gatewayObservedParts().total()).isEqualByComparingTo(cost.gatewayObserved());
        assertThat(cost.gatewayObservedParts().output()).isEqualByComparingTo("2");
        assertThat(cost.gatewayObserved()).isEqualByComparingTo("3");
        assertThat(cost.upstreamPaid()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("unpriceable usage adds nothing to either split, and the gap still says so")
    void unpriceableUsageAddsNothingToEitherSplit() {
        UsageSummary summary = aggregate(new UsageAggRow("g", "G", PRODUCT, MODEL, CacheLevel.UPSTREAM, 1,
                new TokenBucket(1_000_000L, 0L, null, null, null, null, null, null), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new PricingGap(1_000_000L, 0L, 0L, 0L, 1, 1, 0L),
                UsageAggRow.Outcome.NONE));

        Cost cost = summary.totals().cost();
        assertThat(cost.upstreamPaidParts().total()).isEqualByComparingTo("0");
        assertThat(cost.gatewayObservedParts().total()).isEqualByComparingTo("0");
        // The zero on the bar is not a claim that nothing was spent — the status is.
        assertThat(summary.totals().pricingStatus()).isEqualTo(PricingStatus.UNAVAILABLE);
        assertThat(summary.totals().unpriced().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("cache savings do not leak into the observed split")
    void cacheHitsAddSavingsWithoutInflatingTheObservedSplit() {
        UsageSummary summary = UsageStatsAggregator.aggregate("model",
                List.of(row(CacheLevel.UPSTREAM, "1000000", "0", "0", "0")),
                List.of(new HitAggRow("g", "G", PRODUCT, MODEL, 3, 0,
                        new TokenBucket(1_000L, 500L, null, null, null, null, null, null), new BigDecimal("4000000"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0)));

        Cost cost = summary.totals().cost();
        assertThat(cost.savedByGatewayCache()).as("4 CNY of original usage served from cache")
                .isEqualByComparingTo("4");
        // The saving is money the customer did NOT pay: it belongs to no split.
        assertThat(cost.gatewayObservedParts().total()).isEqualByComparingTo(cost.gatewayObserved());
        assertThat(cost.gatewayObserved()).isEqualByComparingTo("1");
    }

    // -------------------------------------------------------------------

    private static UsageSummary aggregate(UsageAggRow row) {
        return UsageStatsAggregator.aggregate("model", List.of(row), List.of());
    }

    /**
     * Undivided {@code tokens × unit_price} per dimension, as the SQL layer sums
     * them.
     */
    private static UsageAggRow row(CacheLevel level, String inputCost, String outputCost, String cacheReadCost,
            String cacheCreationCost) {
        return new UsageAggRow("g", "G", PRODUCT, MODEL, level, 1,
                new TokenBucket(1L, 1L, null, null, null, null, null, null), new BigDecimal(inputCost),
                new BigDecimal(outputCost), new BigDecimal(cacheReadCost), new BigDecimal(cacheCreationCost),
                PricingGap.NONE, UsageAggRow.Outcome.NONE);
    }
}
