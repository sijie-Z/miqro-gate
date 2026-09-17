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
     * One aggregated usage row.
     *
     * <p>
     * The four {@code *Cost} values are the group's <b>sum of per-row products</b>
     * ({@code tokens × unit_price}), deliberately left undivided. Rows in one group
     * can carry different prices — each event keeps the price that was in force
     * when it happened (#710) — so the group's cost cannot be derived from its
     * token totals. The division by {@link #PER_MILLION} still happens here, with
     * the same {@link MathContext} as before, so switching the basis does not
     * perturb rounding.
     * </p>
     */
    public record UsageAggRow(String groupKey, String label, UUID productId, String modelId, CacheLevel cacheLevel,
            long requests, TokenBucket tokens, BigDecimal inputCost, BigDecimal outputCost, BigDecimal cacheReadCost,
            BigDecimal cacheCreationCost) {
    }

    /**
     * Aggregated cache-hit row per group key (one per group/product/model).
     * {@code cachedTokens} is the usage of the ORIGINAL cached response (from
     * cache_entry.meta_json), used to report the hit-weighted mean usage.
     *
     * <p>
     * The four {@code *Cost} values are the group's sum of per-hit products
     * ({@code cached_tokens × hit_count × unit_price}), undivided for the same
     * reason as {@link UsageAggRow}. They are summed by the caller because the
     * price is per hit group, not per (product, model): hits of one cache key can
     * span a price change, so the price cannot be re-derived here from the reported
     * mean usage.
     * </p>
     */
    public record HitAggRow(String groupKey, String label, UUID productId, String modelId, long hitCountL1,
            long hitCountL2, TokenBucket cachedTokens, BigDecimal inputCost, BigDecimal outputCost,
            BigDecimal cacheReadCost, BigDecimal cacheCreationCost) {
    }

    /** Group-level aggregate. */
    public record GroupSummary(String groupKey, String label, Requests requests, Tokens tokens, Cost cost) {
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
     * @return full summary with per-group entries and totals
     */
    public static UsageSummary aggregate(String groupBy, List<UsageAggRow> usageRows, List<HitAggRow> hitRows) {
        Objects.requireNonNull(usageRows, "usageRows");
        Objects.requireNonNull(hitRows, "hitRows");

        Map<String, GroupAccumulator> byGroup = new LinkedHashMap<>();
        for (UsageAggRow row : usageRows) {
            byGroup.computeIfAbsent(row.groupKey(), k -> new GroupAccumulator(k, row.label())).addUsage(row);
        }
        for (HitAggRow row : hitRows) {
            byGroup.computeIfAbsent(row.groupKey(), k -> new GroupAccumulator(k, row.label())).addHit(row);
        }

        GroupAccumulator totals = new GroupAccumulator("TOTALS", "TOTALS");
        List<GroupSummary> groups = new ArrayList<>(byGroup.size());
        for (GroupAccumulator acc : byGroup.values()) {
            groups.add(acc.toSummary());
            totals.mergeFrom(acc);
        }
        groups.sort(Comparator.comparing(GroupSummary::label));

        return new UsageSummary(groupBy, groups, totals.toSummary());
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

        private GroupAccumulator(String groupKey, String label) {
            this.groupKey = groupKey;
            this.label = label;
        }

        /**
         * Adds one aggregated usage row, valued from the row's own cost sums.
         *
         * <p>
         * Deliberately no longer takes the price table: rows in a group can carry
         * different prices — each event froze the one in force when it happened (#710)
         * — so a group's cost cannot be recomputed from its token totals.
         * </p>
         */
        void addUsage(UsageAggRow row) {
            switch (row.cacheLevel()) {
                case UPSTREAM -> upstream += row.requests();
                case COALESCED -> coalesced += row.requests();
                default -> {
                    // L1_HIT/L2_HIT usage rows are not expected; counted in addHit
                }
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

            BigDecimal rowCost = toCost(row.inputCost()).add(toCost(row.outputCost())).add(toCost(row.cacheReadCost()))
                    .add(toCost(row.cacheCreationCost()));
            gatewayObserved = gatewayObserved.add(rowCost);
            if (row.cacheLevel() == CacheLevel.UPSTREAM) {
                upstreamPaid = upstreamPaid.add(rowCost);
            }
        }

        /**
         * Adds one aggregated cache-hit row, valued from the row's own cost sums.
         *
         * <p>
         * Takes no price table for the same reason as {@link #addUsage}: the saved
         * amount is priced per hit group (each at the price in force when those hits
         * happened, #710), which the hit-weighted mean usage reported on the row cannot
         * reconstruct.
         * </p>
         */
        void addHit(HitAggRow row) {
            l1Hit += row.hitCountL1();
            l2Hit += row.hitCountL2();
            if (row.hitCountL1() + row.hitCountL2() == 0) {
                return;
            }
            savedByGatewayCache = savedByGatewayCache.add(toCost(row.inputCost())).add(toCost(row.outputCost()))
                    .add(toCost(row.cacheReadCost())).add(toCost(row.cacheCreationCost()));
        }

        /**
         * Undivided {@code tokens × unit_price} → money. Absent means the row carried
         * no price, which has always been charged as zero ("no price snapshot — 0 cost
         * until priced").
         */
        private static BigDecimal toCost(BigDecimal undivided) {
            return undivided == null ? BigDecimal.ZERO : undivided.divide(PER_MILLION, MC);
        }

        GroupSummary toSummary() {
            return new GroupSummary(groupKey, label, new Requests(upstream, coalesced, l1Hit, l2Hit),
                    new Tokens(inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens),
                    new Cost(upstreamPaid, gatewayObserved, gatewayObserved, savedByGatewayCache));
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
        }
    }

    private static long orZero(Long v) {
        return v == null ? 0 : v;
    }

}
