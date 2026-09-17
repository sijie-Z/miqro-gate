package com.miqroera.miqrokey.domain.usage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure cost/usage aggregation for the tiered statistics model.
 *
 * <p>
 * This class performs NO I/O — the caller supplies aggregated rows and a price
 * lookup, and receives a ready-to-serialize summary. All cost math is
 * deterministic and unit-testable without a database.
 * </p>
 *
 * <h2>Cost accounting (口径)</h2>
 * <ul>
 * <li>{@code upstreamPaid} — cost of requests that actually reached the
 * provider ({@code UPSTREAM} rows only). This is what the customer owes.</li>
 * <li>{@code gatewayObserved} — cost of ALL usage events with tokens
 * ({@code UPSTREAM} + {@code COALESCED}).</li>
 * <li>{@code projectAllocated} — v1 simplification: equals
 * {@code gatewayObserved} (分摊 to the binding project of each request).</li>
 * <li>{@code savedByGatewayCache} — tokens served from cache (hit count × the
 * cached response's original usage) valued at the same price table.</li>
 * </ul>
 *
 * <h2>Token mapping</h2> Protocol-agnostic: input = primary input tokens
 * (Anthropic {@code input} or OpenAI {@code prompt}), output = primary output
 * tokens (Anthropic {@code output} or OpenAI {@code completion}). Cache
 * breakpoints map to {@code CACHE_READ} / {@code CACHE_CREATION} rates.
 *
 * <h2>Pricing</h2> {@code unitPrice} is per 1,000,000 tokens; cost is
 * {@code tokens × unitPrice / 1e6}. Prices are keyed
 * {@code productId:modelId:TOKEN_TYPE}.
 */
public final class UsageStatsAggregator {

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    private static final MathContext MC = MathContext.DECIMAL128;

    private UsageStatsAggregator() {
    }

    /**
     * Aggregated usage-event row per group key (one per
     * group/product/model/cache-level combination).
     */
    public record UsageAggRow(String groupKey, String label, UUID productId, String modelId, CacheLevel cacheLevel,
            long requests, TokenBucket tokens, Outcome outcome) {

        /**
         * Per-row lifecycle outcome aggregates joined from
         * {@code request_usage_records} (#758). Rows without a lifecycle join
         * (coalesced requests never have one) report all zeros.
         */
        public record Outcome(long failed, long cancelled, long durationSumMs, long durationCount, long ttfbSumMs,
                long ttfbCount) {

            public static final Outcome NONE = new Outcome(0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Aggregated cache-hit row per group key (one per group/product/model).
     * {@code cachedTokens} is the usage of the ORIGINAL cached response (from
     * cache_entry.meta_json), used to value saved tokens.
     */
    public record HitAggRow(String groupKey, String label, UUID productId, String modelId, long hitCountL1,
            long hitCountL2, TokenBucket cachedTokens) {
    }

    /** Group-level aggregate. */
    public record GroupSummary(String groupKey, String label, Requests requests, Tokens tokens, Cost cost,
            Outcomes outcomes) {
    }

    /**
     * Lifecycle outcomes of a group (#758): success rate and latency come from the
     * {@code request_usage_records} lifecycle join, so they cover the
     * forwarded/coalesced calls only — cache hits carry no lifecycle. A client
     * cancellation is neither a success nor a failure (excluded from both sides);
     * averaged fields are null when nothing observed them.
     */
    public record Outcomes(long succeeded, long failed, long cancelled, Long avgDurationMs, Long avgTtfbMs) {
    }

    public record Requests(long upstream, long coalesced, long l1Hit, long l2Hit) {
        public long total() {
            return upstream + coalesced + l1Hit + l2Hit;
        }
    }

    public record Tokens(long input, long output, long cacheRead, long cacheCreation) {
        public long total() {
            return input + output + cacheRead + cacheCreation;
        }
    }

    public record Cost(BigDecimal upstreamPaid, BigDecimal gatewayObserved, BigDecimal projectAllocated,
            BigDecimal savedByGatewayCache) {
    }

    public record UsageSummary(String groupBy, List<GroupSummary> groups, GroupSummary totals) {
    }

    /**
     * Aggregates the given rows.
     *
     * @param groupBy
     *            dimension name (project | virtualKey | cacheLevel | day | user |
     *            model | month)
     * @param usageRows
     *            aggregated usage-event rows
     * @param hitRows
     *            aggregated cache-hit rows
     * @param prices
     *            price lookup, keyed {@code productId:modelId:TOKEN_TYPE}
     * @return full summary with per-group entries and totals
     */
    public static UsageSummary aggregate(String groupBy, List<UsageAggRow> usageRows, List<HitAggRow> hitRows,
            Map<String, BigDecimal> prices) {
        Objects.requireNonNull(usageRows, "usageRows");
        Objects.requireNonNull(hitRows, "hitRows");
        Objects.requireNonNull(prices, "prices");

        Map<String, GroupAccumulator> byGroup = new LinkedHashMap<>();
        for (UsageAggRow row : usageRows) {
            byGroup.computeIfAbsent(row.groupKey(), k -> new GroupAccumulator(k, row.label())).addUsage(row, prices);
        }
        for (HitAggRow row : hitRows) {
            byGroup.computeIfAbsent(row.groupKey(), k -> new GroupAccumulator(k, row.label())).addHit(row, prices);
        }

        GroupAccumulator totals = new GroupAccumulator("TOTALS", "TOTALS");
        List<GroupSummary> groups = new ArrayList<>(byGroup.size());
        for (GroupAccumulator acc : byGroup.values()) {
            groups.add(acc.toSummary(prices));
            totals.mergeFrom(acc);
        }
        groups.sort(Comparator.comparing(GroupSummary::label));

        return new UsageSummary(groupBy, groups, totals.toSummary(prices));
    }

    private static final class GroupAccumulator {
        private final String groupKey;
        private final String label;
        private long upstream;
        private long coalesced;
        private long l1Hit;
        private long l2Hit;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheCreationTokens;
        private BigDecimal upstreamPaid = BigDecimal.ZERO;
        private BigDecimal gatewayObserved = BigDecimal.ZERO;
        private BigDecimal savedByGatewayCache = BigDecimal.ZERO;
        private long failedRequests;
        private long cancelledRequests;
        private long durationSumMs;
        private long durationCount;
        private long ttfbSumMs;
        private long ttfbCount;

        private GroupAccumulator(String groupKey, String label) {
            this.groupKey = groupKey;
            this.label = label;
        }

        void addUsage(UsageAggRow row, Map<String, BigDecimal> prices) {
            switch (row.cacheLevel()) {
                case UPSTREAM -> upstream += row.requests();
                case COALESCED -> coalesced += row.requests();
                default -> {
                    // L1_HIT/L2_HIT usage rows are not expected; counted in addHit
                }
            }
            if (row.outcome() != null) {
                failedRequests += row.outcome().failed();
                cancelledRequests += row.outcome().cancelled();
                durationSumMs += row.outcome().durationSumMs();
                durationCount += row.outcome().durationCount();
                ttfbSumMs += row.outcome().ttfbSumMs();
                ttfbCount += row.outcome().ttfbCount();
            }
            TokenBucket t = row.tokens();
            if (t == null || t.isEmpty()) {
                return;
            }
            long input = orZero(t.inputTokens() != null ? t.inputTokens() : t.promptTokens());
            long output = orZero(t.outputTokens() != null ? t.outputTokens() : t.completionTokens());
            long cacheRead = orZero(t.cacheReadInputTokens());
            long cacheCreation = orZero(t.cacheCreationInputTokens());
            inputTokens += input;
            outputTokens += output;
            cacheReadTokens += cacheRead;
            cacheCreationTokens += cacheCreation;

            BigDecimal rowCost = pricedOrZero(prices, row.productId(), row.modelId(), PriceTokenType.INPUT, input)
                    .add(pricedOrZero(prices, row.productId(), row.modelId(), PriceTokenType.OUTPUT, output))
                    .add(pricedOrZero(prices, row.productId(), row.modelId(), PriceTokenType.CACHE_READ, cacheRead))
                    .add(pricedOrZero(prices, row.productId(), row.modelId(), PriceTokenType.CACHE_CREATION,
                            cacheCreation));
            gatewayObserved = gatewayObserved.add(rowCost);
            if (row.cacheLevel() == CacheLevel.UPSTREAM) {
                upstreamPaid = upstreamPaid.add(rowCost);
            }
        }

        void addHit(HitAggRow row, Map<String, BigDecimal> prices) {
            l1Hit += row.hitCountL1();
            l2Hit += row.hitCountL2();
            long hits = row.hitCountL1() + row.hitCountL2();
            if (hits == 0) {
                return;
            }
            TokenBucket t = row.cachedTokens();
            if (t == null || t.isEmpty()) {
                return;
            }
            long input = orZero(t.inputTokens() != null ? t.inputTokens() : t.promptTokens());
            long output = orZero(t.outputTokens() != null ? t.outputTokens() : t.completionTokens());
            long cacheRead = orZero(t.cacheReadInputTokens());
            long cacheCreation = orZero(t.cacheCreationInputTokens());
            BigDecimal saved = pricedOrZero(prices, row.productId(), row.modelId(), PriceTokenType.INPUT, input * hits)
                    .add(pricedOrZero(prices, row.productId(), row.modelId(), PriceTokenType.OUTPUT, output * hits))
                    .add(pricedOrZero(prices, row.productId(), row.modelId(), PriceTokenType.CACHE_READ,
                            cacheRead * hits))
                    .add(pricedOrZero(prices, row.productId(), row.modelId(), PriceTokenType.CACHE_CREATION,
                            cacheCreation * hits));
            savedByGatewayCache = savedByGatewayCache.add(saved);
        }

        GroupSummary toSummary(Map<String, BigDecimal> prices) {
            // Success rate reads the forwarded/coalesced calls only; cache hits
            // (l1Hit/l2Hit) carry no lifecycle. A cancelled client is on
            // neither side of the rate — walking away is not a gateway failure.
            long forwarded = upstream + coalesced;
            long succeeded = Math.max(0, forwarded - failedRequests - cancelledRequests);
            Long avgDurationMs = durationCount > 0 ? durationSumMs / durationCount : null;
            Long avgTtfbMs = ttfbCount > 0 ? ttfbSumMs / ttfbCount : null;
            return new GroupSummary(groupKey, label, new Requests(upstream, coalesced, l1Hit, l2Hit),
                    new Tokens(inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens),
                    new Cost(upstreamPaid, gatewayObserved, gatewayObserved, savedByGatewayCache),
                    new Outcomes(succeeded, failedRequests, cancelledRequests, avgDurationMs, avgTtfbMs));
        }

        void mergeFrom(GroupAccumulator other) {
            upstream += other.upstream;
            coalesced += other.coalesced;
            l1Hit += other.l1Hit;
            l2Hit += other.l2Hit;
            inputTokens += other.inputTokens;
            outputTokens += other.outputTokens;
            cacheReadTokens += other.cacheReadTokens;
            cacheCreationTokens += other.cacheCreationTokens;
            upstreamPaid = upstreamPaid.add(other.upstreamPaid);
            gatewayObserved = gatewayObserved.add(other.gatewayObserved);
            savedByGatewayCache = savedByGatewayCache.add(other.savedByGatewayCache);
            failedRequests += other.failedRequests;
            cancelledRequests += other.cancelledRequests;
            durationSumMs += other.durationSumMs;
            durationCount += other.durationCount;
            ttfbSumMs += other.ttfbSumMs;
            ttfbCount += other.ttfbCount;
        }
    }

    /**
     * A per-row cost estimate plus whether every non-zero token type was priced.
     */
    public record PricedCost(BigDecimal cost, boolean priced) {
    }

    /**
     * Prices one usage row with the same table and math as the aggregates:
     * {@code tokens × unitPrice / 1e6} per token type, summed. {@code priced} is
     * false when any non-zero token type has no snapshot — the caller shows 未定价
     * rather than a misleading 0 (#758).
     */
    public static PricedCost pricedCost(Map<String, BigDecimal> prices, UUID productId, String modelId, Long input,
            Long output, Long cacheRead, Long cacheCreation) {
        long in = orZero(input);
        long out = orZero(output);
        long read = orZero(cacheRead);
        long creation = orZero(cacheCreation);
        boolean priced = (in == 0 || hasPrice(prices, productId, modelId, PriceTokenType.INPUT))
                && (out == 0 || hasPrice(prices, productId, modelId, PriceTokenType.OUTPUT))
                && (read == 0 || hasPrice(prices, productId, modelId, PriceTokenType.CACHE_READ))
                && (creation == 0 || hasPrice(prices, productId, modelId, PriceTokenType.CACHE_CREATION));
        if (!priced) {
            return new PricedCost(BigDecimal.ZERO, false);
        }
        BigDecimal cost = pricedOrZero(prices, productId, modelId, PriceTokenType.INPUT, in)
                .add(pricedOrZero(prices, productId, modelId, PriceTokenType.OUTPUT, out))
                .add(pricedOrZero(prices, productId, modelId, PriceTokenType.CACHE_READ, read))
                .add(pricedOrZero(prices, productId, modelId, PriceTokenType.CACHE_CREATION, creation));
        return new PricedCost(cost, true);
    }

    private static boolean hasPrice(Map<String, BigDecimal> prices, UUID productId, String modelId,
            PriceTokenType type) {
        return prices.containsKey(priceKey(productId, modelId, type));
    }

    private static BigDecimal pricedOrZero(Map<String, BigDecimal> prices, UUID productId, String modelId,
            PriceTokenType type, long tokens) {
        if (tokens == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal unitPrice = prices.get(priceKey(productId, modelId, type));
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens).multiply(unitPrice, MC).divide(PER_MILLION, MC);
    }

    private static long orZero(Long v) {
        return v == null ? 0 : v;
    }

    private static String priceKey(UUID productId, String modelId, PriceTokenType type) {
        return productId + ":" + modelId + ":" + type.name();
    }
}
