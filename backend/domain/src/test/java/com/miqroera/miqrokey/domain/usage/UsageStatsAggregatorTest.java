package com.miqroera.miqrokey.domain.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the pure cost/outcome aggregation (#758 additions
 * included): lifecycle outcomes (success rate, average latency / first byte)
 * and the per-row cost estimate behind the records list.
 */
@DisplayName("UsageStatsAggregator")
class UsageStatsAggregatorTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final String MODEL = "claude-3-7-sonnet";

    private static Map<String, BigDecimal> prices(String tokenType, String unitPrice) {
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        prices.put(PRODUCT + ":" + MODEL + ":" + tokenType, new BigDecimal(unitPrice));
        return prices;
    }

    private static UsageStatsAggregator.UsageAggRow row(CacheLevel level, long requests,
            UsageStatsAggregator.UsageAggRow.Outcome outcome) {
        return new UsageStatsAggregator.UsageAggRow("g", "G", PRODUCT, MODEL, level, requests,
                new TokenBucket(1_000L, 500L, null, null, null, null, 1_500L, null), outcome);
    }

    @Nested
    @DisplayName("Lifecycle outcomes (#758)")
    class Outcomes {

        @Test
        @DisplayName("derives succeeded from forwarded minus failed minus cancelled")
        void derivesSucceededCounts() {
            UsageStatsAggregator.UsageAggRow.Outcome outcome = new UsageStatsAggregator.UsageAggRow.Outcome(1, 1,
                    10_000, 4, 3_000, 2);
            UsageStatsAggregator.UsageSummary summary = UsageStatsAggregator.aggregate("model",
                    List.of(row(CacheLevel.UPSTREAM, 4, outcome)), List.of(), Map.of());

            UsageStatsAggregator.Outcomes o = summary.totals().outcomes();
            assertThat(o.succeeded()).isEqualTo(2);
            assertThat(o.failed()).isEqualTo(1);
            assertThat(o.cancelled()).isEqualTo(1);
            assertThat(o.avgDurationMs()).isEqualTo(2_500);
            assertThat(o.avgTtfbMs()).isEqualTo(1_500);
        }

        @Test
        @DisplayName("averages are null when nothing observed them, and rows without a lifecycle count as success")
        void nullAveragesWhenUnobserved() {
            UsageStatsAggregator.UsageSummary summary = UsageStatsAggregator.aggregate("model",
                    List.of(row(CacheLevel.UPSTREAM, 2, UsageStatsAggregator.UsageAggRow.Outcome.NONE),
                            row(CacheLevel.COALESCED, 1, UsageStatsAggregator.UsageAggRow.Outcome.NONE)),
                    List.of(), Map.of());

            UsageStatsAggregator.Outcomes o = summary.totals().outcomes();
            assertThat(o.succeeded()).isEqualTo(3);
            assertThat(o.failed()).isZero();
            assertThat(o.cancelled()).isZero();
            assertThat(o.avgDurationMs()).isNull();
            assertThat(o.avgTtfbMs()).isNull();
        }

        @Test
        @DisplayName("merges outcomes into the totals alongside the token buckets")
        void mergesIntoTotals() {
            UsageStatsAggregator.UsageAggRow.Outcome a = new UsageStatsAggregator.UsageAggRow.Outcome(0, 0, 2_000, 2,
                    1_000, 1);
            UsageStatsAggregator.UsageAggRow.Outcome b = new UsageStatsAggregator.UsageAggRow.Outcome(1, 0, 4_000, 2,
                    5_000, 1);
            UsageStatsAggregator.UsageSummary summary = UsageStatsAggregator.aggregate("model", List.of(
                    new UsageStatsAggregator.UsageAggRow("a", "A", PRODUCT, MODEL, CacheLevel.UPSTREAM, 2, null, a),
                    new UsageStatsAggregator.UsageAggRow("b", "B", PRODUCT, MODEL, CacheLevel.UPSTREAM, 1, null, b)),
                    List.of(), Map.of());

            UsageStatsAggregator.Outcomes totals = summary.totals().outcomes();
            assertThat(totals.succeeded()).isEqualTo(2);
            assertThat(totals.failed()).isEqualTo(1);
            assertThat(totals.avgDurationMs()).isEqualTo(1_500);
            assertThat(totals.avgTtfbMs()).isEqualTo(3_000);
        }
    }

    @Nested
    @DisplayName("Per-row price estimate (#758)")
    class PricedCost {

        @Test
        @DisplayName("sums the per-type rates and reports fully priced")
        void pricesAllTokenTypes() {
            Map<String, BigDecimal> prices = new LinkedHashMap<>();
            prices.put(PRODUCT + ":" + MODEL + ":INPUT", new BigDecimal("1.00"));
            prices.put(PRODUCT + ":" + MODEL + ":OUTPUT", new BigDecimal("2.00"));
            UsageStatsAggregator.PricedCost priced = UsageStatsAggregator.pricedCost(prices, PRODUCT, MODEL, 1_000L,
                    500L, null, null);

            assertThat(priced.priced()).isTrue();
            assertThat(priced.cost()).isEqualByComparingTo("0.002");
        }

        @Test
        @DisplayName("reports unpriced when a non-zero token type has no snapshot")
        void unpricedWhenAnyTypeMissing() {
            Map<String, BigDecimal> prices = prices("INPUT", "1.00");
            UsageStatsAggregator.PricedCost priced = UsageStatsAggregator.pricedCost(prices, PRODUCT, MODEL, 1_000L,
                    500L, null, null);

            assertThat(priced.priced()).isFalse();
            assertThat(priced.cost()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a row with no tokens is trivially priced at zero")
        void zeroTokensIsPricedZero() {
            UsageStatsAggregator.PricedCost priced = UsageStatsAggregator.pricedCost(Map.of(), PRODUCT, MODEL, null,
                    null, null, null);

            assertThat(priced.priced()).isTrue();
            assertThat(priced.cost()).isEqualByComparingTo("0");
        }
    }
}
