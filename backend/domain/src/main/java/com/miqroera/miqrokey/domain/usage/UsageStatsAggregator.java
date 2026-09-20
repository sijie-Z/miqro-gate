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
 * This class performs NO I/O — the caller supplies aggregated rows (each
 * already carrying the cost its own frozen prices produce) and receives a
 * ready-to-serialize summary. All cost math is deterministic and unit-testable
 * without a database.
 * </p>
 *
 * <p>
 * It no longer takes a price table: a group's rows can hold different prices,
 * so its cost cannot be recomputed from the group's token totals. What it does
 * instead is keep "could not be priced" visible — see {@link PricingStatus} and
 * {@link PricingGap}.
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
 * cached response's original usage), valued at the price in force when those
 * hits happened.</li>
 * </ul>
 *
 * <h2>Token mapping</h2> Protocol-agnostic: input = primary input tokens
 * (Anthropic {@code input} or OpenAI {@code prompt}), output = primary output
 * tokens (Anthropic {@code output} or OpenAI {@code completion}). Cache
 * breakpoints map to {@code CACHE_READ} / {@code CACHE_CREATION} rates.
 *
 * <h2>Pricing</h2> {@code unitPrice} is per 1,000,000 tokens; cost is
 * {@code tokens × unitPrice / 1e6} (see {@link #dividePerMillion}). Each row is
 * valued at the price in force when it happened, which the SQL layer resolves.
 */
public final class UsageStatsAggregator {

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * Undivided {@code tokens × unit_price} → money, at the one rounding every cost
     * path uses.
     *
     * <p>
     * Public so the price-basis backfill divides identically. Two copies of this
     * arithmetic would eventually disagree in the last digits, and a stored
     * {@code base_cost_amount} that disagrees with the amount a summary computes is
     * worse than either alone.
     * </p>
     */
    public static BigDecimal dividePerMillion(BigDecimal undivided) {
        return undivided == null ? BigDecimal.ZERO : undivided.divide(PER_MILLION, MC);
    }

    private UsageStatsAggregator() {
    }

    /**
     * How completely a group's usage could be priced (#710).
     *
     * <p>
     * This is the field that makes a cost of zero unambiguous. {@code COMPLETE}
     * with a zero cost means the price genuinely was zero; {@code UNAVAILABLE} with
     * a zero cost means nothing could be priced at all. Collapsing the two is how
     * "unknown" starts reading as "free".
     * </p>
     */
    public enum PricingStatus {
        /** Every token dimension that took part in the calculation had a price. */
        COMPLETE,
        /** Some dimensions priced, at least one not. */
        PARTIAL,
        /** Nothing could be priced — no price was in force when the usage happened. */
        UNAVAILABLE
    }

    /**
     * Tokens a group could <b>not</b> price, plus how many events that touched
     * (#710).
     *
     * <p>
     * Reported alongside the known cost rather than folded into it: an amount that
     * omits unpriced usage is not a total, and presenting it as one would overstate
     * how much of the window is actually accounted for. Token counts are per
     * dimension because that is where the gaps are — in the reference window the
     * shortfall sat almost entirely in {@code cache_read}.
     * </p>
     */
    public record PricingGap(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens,
            long unpricedEvents, long unavailableEvents, long unpricedHitEvents) {

        public static final PricingGap NONE = new PricingGap(0, 0, 0, 0, 0, 0, 0);

        public PricingGap plus(PricingGap other) {
            return new PricingGap(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                    cacheReadTokens + other.cacheReadTokens, cacheCreationTokens + other.cacheCreationTokens,
                    unpricedEvents + other.unpricedEvents, unavailableEvents + other.unavailableEvents,
                    unpricedHitEvents + other.unpricedHitEvents);
        }

        /**
         * True when the group's <b>cost</b> could not be fully priced — the condition
         * {@link PricingStatus} describes (#766).
         *
         * <p>
         * Named separately from {@link #isEmpty()} because the two answer different
         * questions: a group whose usage is fully priced but whose <em>cache hits</em>
         * were not still has a complete cost. Folding the hit gap into the cost's
         * status would report a cost figure as incomplete for a reason that never
         * touched it.
         * </p>
         */
        public boolean hasCostGap() {
            return unpricedEvents > 0;
        }

        /**
         * True when nothing in the group could be priced — cost {@code or} savings.
         *
         * <p>
         * {@code unpricedHitEvents} counts the hits that {@code savedByGatewayCache}
         * could not value (#790): a hit whose tokens are real but whose price was not
         * in force when it happened contributes 0 to the saving. The saving is then a
         * <b>lower bound</b>, and this is the count that says so.
         * </p>
         */
        public boolean isEmpty() {
            return unpricedEvents == 0 && unpricedHitEvents == 0;
        }
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
            BigDecimal cacheCreationCost, PricingGap pricingGap, Outcome outcome) {

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
            BigDecimal cacheReadCost, BigDecimal cacheCreationCost, long unpricedHits) {
    }

    /** Group-level aggregate. */
    public record GroupSummary(String groupKey, String label, Requests requests, Tokens tokens, Cost cost,
            PricingStatus pricingStatus, PricingGap unpriced, Outcomes outcomes) {
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
        private PricingGap unpriced = PricingGap.NONE;
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

            unpriced = unpriced.plus(row.pricingGap());
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
            // A hit we could not price contributes nothing above, so the saving is a
            // lower bound. Count it, or the reader cannot tell "the cache saved almost
            // nothing" from "we had no price to say what it saved" (#790).
            unpriced = unpriced.plus(new PricingGap(0, 0, 0, 0, 0, 0, row.unpricedHits()));
        }

        /**
         * Undivided {@code tokens × unit_price} → money. Absent means the row carried
         * no price, which contributes nothing to <b>known</b> cost — the tokens are
         * reported as unpriced instead, see {@link PricingGap}.
         */
        private static BigDecimal toCost(BigDecimal undivided) {
            return dividePerMillion(undivided);
        }

        GroupSummary toSummary() {
            return new GroupSummary(groupKey, label, new Requests(upstream, coalesced, l1Hit, l2Hit),
                    new Tokens(inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens),
                    new Cost(upstreamPaid, gatewayObserved, gatewayObserved, savedByGatewayCache), pricingStatus(),
                    unpriced, outcomes());
        }

        /**
         * Whether {@link #upstreamPaid} / {@link #gatewayObserved} are totals or only
         * partial.
         *
         * <p>
         * Only usage rows are judged here; cache-hit rows are valued separately and are
         * not yet covered by the gap counter (follow-up).
         * </p>
         */
        private PricingStatus pricingStatus() {
            // The cost's status, and only the cost's: an unpriced *hit* leaves this
            // figure complete and makes the saving a lower bound instead (#790).
            if (!unpriced.hasCostGap()) {
                return PricingStatus.COMPLETE;
            }
            long counted = upstream + coalesced;
            return counted > 0 && unpriced.unavailableEvents() >= counted
                    ? PricingStatus.UNAVAILABLE
                    : PricingStatus.PARTIAL;
        }

        /**
         * Lifecycle outcomes from the {@code request_usage_records} join (#758).
         *
         * <p>
         * The success rate reads the forwarded/coalesced calls only — cache hits carry
         * no lifecycle — and a client cancellation sits on neither side of it: walking
         * away is not a gateway failure.
         * </p>
         */
        private Outcomes outcomes() {
            long forwarded = upstream + coalesced;
            long succeeded = Math.max(0, forwarded - failedRequests - cancelledRequests);
            Long avgDurationMs = durationCount > 0 ? durationSumMs / durationCount : null;
            Long avgTtfbMs = ttfbCount > 0 ? ttfbSumMs / ttfbCount : null;
            return new Outcomes(succeeded, failedRequests, cancelledRequests, avgDurationMs, avgTtfbMs);
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
            unpriced = unpriced.plus(other.unpriced);
            failedRequests += other.failedRequests;
            cancelledRequests += other.cancelledRequests;
            durationSumMs += other.durationSumMs;
            durationCount += other.durationCount;
            ttfbSumMs += other.ttfbSumMs;
            ttfbCount += other.ttfbCount;
        }
    }

    /**
     * A per-row cost estimate plus whether the row is fully priced for display.
     *
     * <p>
     * <b>One rule, and it is token-aware:</b> a dimension gates the flag only when
     * the row carries tokens for it. Cache rates are therefore not excluded
     * wholesale — the earlier "input/output only" form was written to stop rows
     * that never touched a cache dimension from being flagged, but that is already
     * handled by the token test. Excluding cache dimensions outright also hid the
     * genuine case (cache tokens present, no cache rate), which the summary reports
     * as unpriced; excluding it here would have made the detail row and the
     * aggregate disagree.
     * </p>
     */
    public record PricedCost(BigDecimal cost, boolean priced) {
    }

    /**
     * Prices one usage row with the same math as the aggregates:
     * {@code tokens × unitPrice / 1e6} per token type, summed. {@code priced} is
     * false when a dimension the row actually used has no price — the caller shows
     * 未定价 rather than passing the sum off as a total (#758).
     *
     * <p>
     * {@code cost} is the sum of the dimensions that <em>could</em> be priced, so an
     * incomplete row still carries the known part of its amount and agrees with the
     * group sum that contains it; it is a lower bound, not a total. Returning zero
     * for such a row made the detail endpoint report 0 for usage the report valued
     * at 0.002 (#710).
     * </p>
     *
     * <p>
     * The prices come from the row's own {@link RowPriceBasis}, never from a table
     * looked up now: that is what keeps a detail row's cost from moving when the
     * price list changes (#710).
     * </p>
     */
    public static PricedCost pricedCost(RowPriceBasis basis, Long input, Long output, Long cacheRead,
            Long cacheCreation) {
        long in = orZero(input);
        long out = orZero(output);
        long read = orZero(cacheRead);
        long creation = orZero(cacheCreation);
        boolean priced = (in == 0 || basis.unitPrice(PriceTokenType.INPUT) != null)
                && (out == 0 || basis.unitPrice(PriceTokenType.OUTPUT) != null)
                && (read == 0 || basis.unitPrice(PriceTokenType.CACHE_READ) != null)
                && (creation == 0 || basis.unitPrice(PriceTokenType.CACHE_CREATION) != null);
        // The priced dimensions are summed even when the row is incomplete: that sum is
        // the known part of the amount (docs/usage-accounting.md §6.1, PARTIAL), and it
        // is what the group sums book for this same row through
        // GroupAccumulator.addUsage. Returning zero here instead made a row's detail cost
        // disagree with the report that contains it.
        BigDecimal cost = pricedOrZero(basis, PriceTokenType.INPUT, in)
                .add(pricedOrZero(basis, PriceTokenType.OUTPUT, out))
                .add(pricedOrZero(basis, PriceTokenType.CACHE_READ, read))
                .add(pricedOrZero(basis, PriceTokenType.CACHE_CREATION, creation));
        return new PricedCost(cost, priced);
    }

    private static BigDecimal pricedOrZero(RowPriceBasis basis, PriceTokenType type, long tokens) {
        if (tokens == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal unitPrice = basis.unitPrice(type);
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens).multiply(unitPrice, MC).divide(PER_MILLION, MC);
    }

    private static long orZero(Long v) {
        return v == null ? 0 : v;
    }

}
