package com.miqroera.miqrokey.domain.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.Cost;
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
 * What a cost of zero means (#710).
 *
 * <p>
 * The whole point of the pricing status is that {@code 0} is ambiguous on its
 * own: it can mean "the price really was zero" or "we could not price this at
 * all". These two must never collapse into each other, so they get a test each
 * rather than a comment asking readers to be careful.
 * </p>
 */
@DisplayName("Pricing status makes a zero unambiguous")
class UsageStatsPricingStatusTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final String MODEL = "m";

    @Test
    @DisplayName("a row nothing could price reports zero cost with the gap explaining it")
    void unavailableNeverMapsToZeroCost() {
        // 1M input tokens, no price anywhere — the shape of an event that predates
        // every
        // price row we hold.
        UsageSummary summary = aggregate(
                row(1_000_000L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, new PricingGap(1_000_000L, 0L, 0L, 0L, 1, 1)));

        Cost cost = summary.totals().cost();
        assertThat(cost.upstreamPaid()).isEqualByComparingTo("0");
        // The zero is not the story — the status and the gap are what keep it honest.
        assertThat(summary.totals().pricingStatus()).isEqualTo(PricingStatus.UNAVAILABLE);
        assertThat(summary.totals().unpriced().inputTokens()).as("unpriced usage must be disclosed")
                .isEqualTo(1_000_000L);
        assertThat(summary.totals().unpriced().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("a genuinely free dimension is COMPLETE with a zero cost, not UNAVAILABLE")
    void completeWithZeroPriceRemainsLegitimateZeroCost() {
        // Price is 0 on purpose. Same number as the case above, opposite meaning.
        UsageSummary summary = aggregate(row(1_000_000L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, PricingGap.NONE));

        assertThat(summary.totals().cost().upstreamPaid()).isEqualByComparingTo("0");
        assertThat(summary.totals().pricingStatus()).isEqualTo(PricingStatus.COMPLETE);
        assertThat(summary.totals().unpriced().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a partially priced row counts only its priced dimensions as known cost")
    void partialCountsOnlyPricedDimensions() {
        // Input priced (1.00/M applied to 1M = 1.0), output has tokens but no price.
        UsageSummary summary = aggregate(row(1_000_000L, 500_000L, new BigDecimal("1000000"), BigDecimal.ZERO,
                new PricingGap(0L, 500_000L, 0L, 0L, 1, 0)));

        assertThat(summary.totals().pricingStatus()).isEqualTo(PricingStatus.PARTIAL);
        assertThat(summary.totals().cost().upstreamPaid()).as("known cost covers only the priced dimensions")
                .isEqualByComparingTo("1");
        assertThat(summary.totals().unpriced().outputTokens()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("a fully priced row is COMPLETE and carries no gap")
    void fullyPricedIsComplete() {
        UsageSummary summary = aggregate(
                row(1_000_000L, 0L, new BigDecimal("1000000"), BigDecimal.ZERO, PricingGap.NONE));

        assertThat(summary.totals().pricingStatus()).isEqualTo(PricingStatus.COMPLETE);
        assertThat(summary.totals().cost().upstreamPaid()).isEqualByComparingTo("1");
    }

    // -------------------------------------------------------------------

    private static UsageSummary aggregate(UsageAggRow row) {
        return UsageStatsAggregator.aggregate("model", List.of(row), List.of());
    }

    /**
     * One UPSTREAM row. Cost arguments are the undivided
     * {@code tokens × unit_price} sums the SQL layer produces; the gap is what it
     * could not price.
     */
    private static UsageAggRow row(long input, long output, BigDecimal inputCost, BigDecimal outputCost,
            PricingGap gap) {
        return new UsageAggRow("g", "G", PRODUCT, MODEL, CacheLevel.UPSTREAM, 1,
                new TokenBucket(input, output, null, null, null, null, null, null), inputCost, outputCost,
                BigDecimal.ZERO, BigDecimal.ZERO, gap, UsageStatsAggregator.UsageAggRow.Outcome.NONE);
    }
}
