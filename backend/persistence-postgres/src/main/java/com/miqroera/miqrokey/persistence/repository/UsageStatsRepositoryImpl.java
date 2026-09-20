package com.miqroera.miqrokey.persistence.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.usage.AdjustedUsageRow;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import com.miqroera.miqrokey.domain.usage.LifecycleInfo;
import com.miqroera.miqrokey.domain.usage.RowPriceBasis;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side implementation for tiered usage statistics.
 *
 * <p>
 * Grouping happens in SQL; token/cost math happens in the pure
 * {@link UsageStatsAggregator}. Cache-hit rows are folded per cache key in SQL
 * and merged to per (group, product, model) rows in Java, carrying the cached
 * response's usage as a hit-weighted mean so the aggregator can value the
 * tokens the gateway saved.
 * </p>
 *
 * <p>
 * Never selects prompt, code, or model content — only counts and metadata.
 * </p>
 */
@Repository
@Transactional(readOnly = true)
public class UsageStatsRepositoryImpl implements UsageStatsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public UsageStatsRepositoryImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private record GroupSpec(String select, String join, String groupBy) {
    }

    /**
     * Day bucket ({@code YYYY-MM-DD}) for a {@code timestamptz} column, in the
     * caller's local day (#1050).
     *
     * <p>
     * The bucket is computed from the instant plus the caller's fixed offset from
     * UTC ({@code tzOffsetMinutes}) and never from the session's {@code TimeZone}.
     * The unqualified form, {@code CAST(occurred_at AS DATE)}, resolved through the
     * session, so the same row landed in a different bucket after an operator
     * changed the server's timezone: no code change, no warning, no audit trail,
     * and every tenant's daily series shifted at once. A bucket boundary is a
     * property of the data and the requested offset, not of whoever happens to be
     * connected — the same rule the hourly path below states. The offset form also
     * keeps the API honest to the console: the request log prints local timestamps,
     * so the day bucket has to be the local day those timestamps belong to.
     * </p>
     *
     * <p>
     * Package-private so the timezone regression test can run the production
     * expression itself instead of a copy that could drift from it.
     * </p>
     */
    static String dayBucket(String column, int tzOffsetMinutes) {
        return "to_char((" + column + " AT TIME ZONE 'UTC') + interval '" + tzOffsetMinutes + " minute', 'YYYY-MM-DD')";
    }

    /**
     * Month bucket ({@code YYYY-MM}) for a {@code timestamptz} column, in the
     * caller's local month (#1050). Same rules as {@link #dayBucket(String, int)}.
     */
    static String monthBucket(String column, int tzOffsetMinutes) {
        return "to_char((" + column + " AT TIME ZONE 'UTC') + interval '" + tzOffsetMinutes + " minute', 'YYYY-MM')";
    }

    private static GroupSpec daySpec(String column, int tzOffsetMinutes) {
        String bucket = dayBucket(column, tzOffsetMinutes);
        return new GroupSpec(bucket + " AS group_key, " + bucket + " AS label", "", bucket);
    }

    private static GroupSpec monthSpec(String column, int tzOffsetMinutes) {
        String bucket = monthBucket(column, tzOffsetMinutes);
        return new GroupSpec(bucket + " AS group_key, " + bucket + " AS label", "", bucket);
    }

    /**
     * The offset is interpolated into the bucket expression, not bound: it sits
     * inside GROUP BY, and PostgreSQL treats two bind placeholders as different
     * expression nodes even when they carry the same value — grouping by the
     * expression would fail with "column must appear in the GROUP BY clause". An
     * {@code int} cannot carry SQL; the range check keeps the value a timezone.
     */
    private static int checkedOffset(int tzOffsetMinutes) {
        if (tzOffsetMinutes < -18 * 60 || tzOffsetMinutes > 18 * 60) {
            throw new IllegalArgumentException("tzOffsetMinutes must be between -1080 and 1080");
        }
        return tzOffsetMinutes;
    }

    /**
     * Lifecycle enrichment join (#758): fact rows carry the gateway request id; the
     * lifecycle trail ({@code request_usage_records}) is unique per
     * {@code (started_at, gateway_request_id)}. The window condition on
     * {@code started_at} keeps PostgreSQL partition pruning effective — without it
     * every partition would be probed for each fact row.
     */
    private static final String LIFECYCLE_JOIN = """
            LEFT JOIN request_usage_records rur
                   ON rur.tenant_id = ue.tenant_id
                  AND rur.gateway_request_id = ue.gateway_request_id
                  AND rur.started_at >= :from AND rur.started_at < :to""";

    /**
     * Terminal statuses that count as a gateway-side failure for the success rate
     * (#758). A client cancellation is deliberately NOT in this set — the product
     * walking away is not a failure — and is excluded from the rate on both sides
     * instead.
     */
    private static final String FAILED_STATUS_SQL = "'UPSTREAM_REJECTED', 'UPSTREAM_UNAVAILABLE',"
            + " 'TIMEOUT_BEFORE_FIRST_BYTE', 'STREAM_INTERRUPTED', 'AUTH_REJECTED', 'MODEL_NOT_ALLOWED',"
            + " 'USAGE_PARSE_FAILED'";

    private GroupSpec spec(GroupBy groupBy, int tzOffsetMinutes) {
        return switch (groupBy) {
            case PROJECT -> new GroupSpec("ue.project_id AS group_key, p.name AS label",
                    "JOIN projects p ON p.id = ue.project_id AND p.tenant_id = ue.tenant_id", "ue.project_id, p.name");
            case VIRTUAL_KEY ->
                new GroupSpec("ue.virtual_key_id AS group_key, COALESCE(vk.name, vk.last_four) AS label",
                        "JOIN virtual_keys vk ON vk.id = ue.virtual_key_id AND vk.tenant_id = ue.tenant_id",
                        "ue.virtual_key_id, COALESCE(vk.name, vk.last_four)");
            case CACHE_LEVEL ->
                new GroupSpec("ue.cache_level AS group_key, ue.cache_level AS label", "", "ue.cache_level");
            case DAY -> daySpec("ue.occurred_at", tzOffsetMinutes);
            case USER -> new GroupSpec("vk.user_id AS group_key, u.username AS label",
                    "JOIN virtual_keys vk ON vk.id = ue.virtual_key_id AND vk.tenant_id = ue.tenant_id"
                            + " JOIN users u ON u.id = vk.user_id AND u.tenant_id = ue.tenant_id",
                    "vk.user_id, u.username");
            case TEAM -> new GroupSpec("tm.team_id AS group_key, t.name AS label",
                    "JOIN virtual_keys vk ON vk.id = ue.virtual_key_id AND vk.tenant_id = ue.tenant_id"
                            + " JOIN team_memberships tm ON tm.user_id = vk.user_id AND tm.tenant_id = ue.tenant_id"
                            + " JOIN teams t ON t.id = tm.team_id AND t.tenant_id = ue.tenant_id",
                    "tm.team_id, t.name");
            case MODEL -> new GroupSpec("ue.model_id AS group_key, ue.model_id AS label", "", "ue.model_id");
            case PRODUCT -> new GroupSpec(
                    "ue.provider_product_id AS group_key,"
                            + " COALESCE(pp.display_name, pp.product_code, ue.provider_product_id::text) AS label",
                    "LEFT JOIN provider_products pp ON pp.id = ue.provider_product_id", "ue.provider_product_id,"
                            + " COALESCE(pp.display_name, pp.product_code, ue.provider_product_id::text)");
            case MONTH -> monthSpec("ue.occurred_at", tzOffsetMinutes);
        };
    }

    /**
     * Group spec over {@code cache_hit_event h} joined to {@code cache_entry e}.
     * The CACHE_LEVEL split (L1_HIT / L2_HIT) is done in Java, so the SQL groups
     * everything under a constant key.
     */
    private GroupSpec hitsSpec(GroupBy groupBy, int tzOffsetMinutes) {
        return switch (groupBy) {
            case PROJECT -> new GroupSpec("h.project_id AS group_key, p.name AS label",
                    "JOIN projects p ON p.id = h.project_id AND p.tenant_id = h.tenant_id", "h.project_id, p.name");
            case VIRTUAL_KEY -> new GroupSpec("h.virtual_key_id AS group_key, COALESCE(vk.name, vk.last_four) AS label",
                    "JOIN virtual_keys vk ON vk.id = h.virtual_key_id AND vk.tenant_id = h.tenant_id",
                    "h.virtual_key_id, COALESCE(vk.name, vk.last_four)");
            case CACHE_LEVEL -> new GroupSpec("'HIT' AS group_key, 'HIT' AS label", "", "'HIT'");
            case DAY -> daySpec("h.occurred_at", tzOffsetMinutes);
            case USER -> new GroupSpec("vk.user_id AS group_key, u.username AS label",
                    "JOIN virtual_keys vk ON vk.id = h.virtual_key_id AND vk.tenant_id = h.tenant_id"
                            + " JOIN users u ON u.id = vk.user_id AND u.tenant_id = h.tenant_id",
                    "vk.user_id, u.username");
            case TEAM -> new GroupSpec("tm.team_id AS group_key, t.name AS label",
                    "JOIN virtual_keys vk ON vk.id = h.virtual_key_id AND vk.tenant_id = h.tenant_id"
                            + " JOIN team_memberships tm ON tm.user_id = vk.user_id AND tm.tenant_id = h.tenant_id"
                            + " JOIN teams t ON t.id = tm.team_id AND t.tenant_id = h.tenant_id",
                    "tm.team_id, t.name");
            case MODEL -> new GroupSpec("e.model_id AS group_key, e.model_id AS label", "", "e.model_id");
            case PRODUCT -> new GroupSpec(
                    "e.provider_product_id AS group_key,"
                            + " COALESCE(pp.display_name, pp.product_code, e.provider_product_id::text) AS label",
                    "LEFT JOIN provider_products pp ON pp.id = e.provider_product_id", "e.provider_product_id,"
                            + " COALESCE(pp.display_name, pp.product_code, e.provider_product_id::text)");
            case MONTH -> monthSpec("h.occurred_at", tzOffsetMinutes);
        };
    }

    /**
     * Builds the dynamic WHERE clause and parameters for the optional G4.1
     * dimensions. {@code alias} is the filterable base table alias ({@code ue} for
     * {@code usage_event}, {@code h} for {@code cache_hit_event}); the
     * user/subscription dimensions join through {@code virtual_keys} ({@code vk})
     * and {@code upstream_credentials} ({@code cr}).
     */
    private static final class WhereBuilder {

        private final UsageFilter filter;
        private final List<String> conditions = new ArrayList<>();
        private final MapSqlParameterSource params = new MapSqlParameterSource();
        private final StringBuilder joins = new StringBuilder();

        WhereBuilder(UsageFilter filter, String alias) {
            this.filter = filter;
            conditions.add(alias + ".tenant_id = :tenantId");
            params.addValue("tenantId", filter.tenantId());
            if (filter.virtualKeyIds() != null) {
                conditions.add(alias + ".virtual_key_id IN (:virtualKeyIds)");
                params.addValue("virtualKeyIds", filter.virtualKeyIds());
            }
            if (filter.projectId() != null) {
                conditions.add(alias + ".project_id = :projectId");
                params.addValue("projectId", filter.projectId());
            }
            if (filter.userId() != null || filter.subscriptionId() != null) {
                // vkf is referenced by the subscription branch too; join once
                // for either filter (audit fix).
                joins.append(" JOIN virtual_keys vkf ON vkf.id = ").append(alias)
                        .append(".virtual_key_id AND vkf.tenant_id = ").append(alias).append(".tenant_id");
                if (filter.userId() != null) {
                    conditions.add("vkf.user_id = :userId");
                    params.addValue("userId", filter.userId());
                }
            }
            if (filter.subscriptionId() != null) {
                joins.append(" JOIN upstream_credentials crf ON crf.id = vkf.upstream_credential_id"
                        + " AND crf.tenant_id = vkf.tenant_id");
                conditions.add("crf.subscription_id = :subscriptionId");
                params.addValue("subscriptionId", filter.subscriptionId());
            }
            if (filter.teamId() != null) {
                // Team is an attribution view through the member's keys — an
                // EXISTS keeps it composable with the other filters and works
                // unchanged for both usage_event and cache_hit_event aliases.
                conditions.add("EXISTS (SELECT 1 FROM virtual_keys vkt"
                        + " JOIN team_memberships tmt ON tmt.user_id = vkt.user_id AND tmt.tenant_id = " + alias
                        + ".tenant_id" + " WHERE vkt.id = " + alias + ".virtual_key_id AND vkt.tenant_id = " + alias
                        + ".tenant_id" + " AND tmt.team_id = :teamId)");
                params.addValue("teamId", filter.teamId());
            }
            conditions.add(alias + ".occurred_at >= :from AND " + alias + ".occurred_at < :to");
            params.addValue("from", Timestamp.from(filter.from()));
            params.addValue("to", Timestamp.from(filter.to()));
        }

        /** Extra filterable columns that only exist on {@code usage_event}. */
        WhereBuilder usageEventColumns() {
            if (filter.credentialId() != null) {
                conditions.add("ue.credential_id = :credentialId");
                params.addValue("credentialId", filter.credentialId());
            }
            if (filter.providerProductId() != null) {
                conditions.add("ue.provider_product_id = :providerProductId");
                params.addValue("providerProductId", filter.providerProductId());
            }
            if (filter.modelId() != null) {
                conditions.add("ue.model_id = :modelId");
                params.addValue("modelId", filter.modelId());
            }
            if (filter.clientIp() != null) {
                // #605: abuse forensics — "what did this address do through us".
                conditions.add("ue.client_ip = :clientIp");
                params.addValue("clientIp", filter.clientIp());
            }
            return this;
        }

        /**
         * Cache-hit filterable columns live on the joined {@code cache_entry}
         * ({@code e}).
         */
        WhereBuilder cacheHitColumns() {
            if (filter.providerProductId() != null) {
                conditions.add("e.provider_product_id = :providerProductId");
                params.addValue("providerProductId", filter.providerProductId());
            }
            if (filter.modelId() != null) {
                conditions.add("e.model_id = :modelId");
                params.addValue("modelId", filter.modelId());
            }
            return this;
        }

        String where() {
            return " WHERE " + String.join(" AND ", conditions);
        }

        String joins() {
            return joins.toString();
        }

        MapSqlParameterSource params() {
            return params;
        }
    }

    /**
     * Contributes nothing to <b>known</b> cost when a dimension is unpriced.
     *
     * <p>
     * That is not the same as the usage being free. The tokens that could not be
     * priced are reported separately (see {@link #unpricedColumns}) and the group
     * carries a pricing status, so a reader can always tell which of the two a zero
     * means.
     * </p>
     */
    private static String zeroIfUnpriced(String priceExpr) {
        return "COALESCE(" + priceExpr + ", 0)";
    }

    /**
     * Extra SELECT columns describing what could <b>not</b> be priced (#710).
     *
     * <p>
     * A dimension counts as unpriced only when the row actually carries tokens for
     * it: an event with no cache tokens is fully priced even when no cache price
     * exists, because that dimension never entered the calculation.
     * </p>
     *
     * <p>
     * These exist so that a zero is always explainable. Without them a group whose
     * price basis is missing reports a cost of 0 with nothing saying whether that
     * means "free" or "we cannot price this" — the ambiguity AWS avoids by typing
     * its zero-cost line items ({@code LineItemType = Discounted Usage}).
     * </p>
     */
    private static String unpricedColumns(String inputTokens, String inputPrice, String outputTokens,
            String outputPrice, String cacheReadTokens, String cacheReadPrice, String cacheCreationTokens,
            String cacheCreationPrice) {
        String unpricedInput = "(COALESCE(" + inputTokens + ", 0) > 0 AND " + inputPrice + " IS NULL)";
        String unpricedOutput = "(COALESCE(" + outputTokens + ", 0) > 0 AND " + outputPrice + " IS NULL)";
        String unpricedCacheRead = "(COALESCE(" + cacheReadTokens + ", 0) > 0 AND " + cacheReadPrice + " IS NULL)";
        String unpricedCacheCreation = "(COALESCE(" + cacheCreationTokens + ", 0) > 0 AND " + cacheCreationPrice
                + " IS NULL)";
        String anyUnpriced = "(" + unpricedInput + " OR " + unpricedOutput + " OR " + unpricedCacheRead + " OR "
                + unpricedCacheCreation + ")";
        String anyPriced = "((COALESCE(" + inputTokens + ", 0) > 0 AND " + inputPrice + " IS NOT NULL)"
                + " OR (COALESCE(" + outputTokens + ", 0) > 0 AND " + outputPrice + " IS NOT NULL)" + " OR (COALESCE("
                + cacheReadTokens + ", 0) > 0 AND " + cacheReadPrice + " IS NOT NULL)" + " OR (COALESCE("
                + cacheCreationTokens + ", 0) > 0 AND " + cacheCreationPrice + " IS NOT NULL))";
        return """
                       , COALESCE(SUM(CASE WHEN %s THEN COALESCE(%s, 0) ELSE 0 END), 0) AS unpriced_input_tokens
                       , COALESCE(SUM(CASE WHEN %s THEN COALESCE(%s, 0) ELSE 0 END), 0) AS unpriced_output_tokens
                       , COALESCE(SUM(CASE WHEN %s THEN COALESCE(%s, 0) ELSE 0 END), 0) AS unpriced_cache_read_tokens
                       , COALESCE(SUM(CASE WHEN %s THEN COALESCE(%s, 0) ELSE 0 END), 0)
                           AS unpriced_cache_creation_tokens
                       , COUNT(*) FILTER (WHERE %s) AS unpriced_events
                       , COUNT(*) FILTER (WHERE %s AND NOT %s) AS unavailable_events
                """.formatted(unpricedInput, inputTokens, unpricedOutput, outputTokens, unpricedCacheRead,
                cacheReadTokens, unpricedCacheCreation, cacheCreationTokens, anyUnpriced, anyUnpriced, anyPriced);
    }

    /**
     * Which token reading an aggregation returns (#1002).
     *
     * <p>
     * Not a caller-supplied flag: the two readings answer two different questions,
     * and which one a call site needs is a property of the call site, not of the
     * request. So each reading gets its own method instead of a boolean.
     * </p>
     */
    private enum TokenBasis {
        /** Exactly what the gateway counted — the only basis a quota may enforce on. */
        OBSERVED,
        /** Observed facts plus the booking ledger (#709): the financial reading. */
        ADJUSTED
    }

    /**
     * The booking-ledger term (#709); empty for an observed-only reading (#1002).
     */
    private static String adjustmentDelta(String column, TokenBasis basis) {
        return basis == TokenBasis.ADJUSTED ? " + COALESCE(SUM(adj." + column + "), 0)" : "";
    }

    @Override
    public List<UsageStatsAggregator.UsageAggRow> aggregateUsage(GroupBy groupBy, UsageFilter filter) {
        return aggregateUsage(groupBy, filter, 0);
    }

    @Override
    public List<UsageStatsAggregator.UsageAggRow> aggregateUsage(GroupBy groupBy, UsageFilter filter,
            int tzOffsetMinutes) {
        return aggregateUsage(groupBy, filter, TokenBasis.ADJUSTED, tzOffsetMinutes);
    }

    @Override
    public List<UsageStatsAggregator.UsageAggRow> aggregateObservedUsage(GroupBy groupBy, UsageFilter filter) {
        return aggregateUsage(groupBy, filter, TokenBasis.OBSERVED, 0);
    }

    private List<UsageStatsAggregator.UsageAggRow> aggregateUsage(GroupBy groupBy, UsageFilter filter, TokenBasis basis,
            int tzOffsetMinutes) {
        GroupSpec spec = spec(groupBy, checkedOffset(tzOffsetMinutes));
        WhereBuilder wb = new WhereBuilder(filter, "ue").usageEventColumns();
        // Per-row price basis (#710): a group's cost is the SUM of each row valued at
        // its own
        // price, because rows in one group can legitimately carry different frozen
        // prices.
        // Pricing the aggregated token count (what this used to do) cannot express
        // that, and
        // made history move whenever the price table changed.
        String inputTokens = "COALESCE(ue.input_tokens, ue.prompt_tokens)";
        String outputTokens = "COALESCE(ue.output_tokens, ue.completion_tokens)";
        String cacheReadTokens = "ue.cache_read_input_tokens";
        String cacheCreationTokens = "ue.cache_creation_input_tokens";
        String inputPrice = PriceSnapshotSql.frozenOrAsOf("ue.price_input", PriceTokenType.INPUT,
                "ue.provider_product_id", "ue.model_id", "ue.occurred_at");
        String outputPrice = PriceSnapshotSql.frozenOrAsOf("ue.price_output", PriceTokenType.OUTPUT,
                "ue.provider_product_id", "ue.model_id", "ue.occurred_at");
        String cacheReadPrice = PriceSnapshotSql.frozenOrAsOf("ue.price_cache_read", PriceTokenType.CACHE_READ,
                "ue.provider_product_id", "ue.model_id", "ue.occurred_at");
        String cacheCreationPrice = PriceSnapshotSql.frozenOrAsOf("ue.price_cache_creation",
                PriceTokenType.CACHE_CREATION, "ue.provider_product_id", "ue.model_id", "ue.occurred_at");
        // Only the adjusted reading needs the ledger; the observed one must not read
        // usage_adjustments at all (#1002).
        String adjustmentJoin = basis == TokenBasis.ADJUSTED ? UsageAdjustmentSql.ADJUSTMENT_LATERAL : "";
        String sql = """
                SELECT %s, ue.provider_product_id AS product_id, ue.model_id, ue.cache_level AS cache_level,
                       -- requests counts observed calls; an adjustment corrects a call's usage, it
                       -- does not add a call, so it must not move this number.
                       COUNT(*) AS requests,
                       -- Token basis (#1002). ADJUSTED = observed count plus the deltas booked
                       -- against it, the financial/reporting reading (#709). OBSERVED = exactly
                       -- what the gateway counted, the only basis quota enforcement may use. The
                       -- two readings differ by the suffix appended to each SUM below.
                       COALESCE(SUM(COALESCE(ue.input_tokens, ue.prompt_tokens)), 0)%s AS input_tokens,
                       COALESCE(SUM(COALESCE(ue.output_tokens, ue.completion_tokens)), 0)%s AS output_tokens,
                       COALESCE(SUM(ue.cache_read_input_tokens), 0)%s AS cache_read_tokens,
                       COALESCE(SUM(ue.cache_creation_input_tokens), 0)%s AS cache_creation_tokens,
                       -- Costs are returned un-divided (tokens x unit_price); the aggregator does
                       -- the /PER_MILLION with the same MathContext it always used, so this switch
                       -- does not perturb rounding.
                       COALESCE(SUM(COALESCE(COALESCE(ue.input_tokens, ue.prompt_tokens), 0) * %s), 0)
                           AS input_cost,
                       COALESCE(SUM(COALESCE(COALESCE(ue.output_tokens, ue.completion_tokens), 0) * %s), 0)
                           AS output_cost,
                       COALESCE(SUM(COALESCE(ue.cache_read_input_tokens, 0) * %s), 0) AS cache_read_cost,
                       COALESCE(SUM(COALESCE(ue.cache_creation_input_tokens, 0) * %s), 0) AS cache_creation_cost
                %s,
                       -- Lifecycle outcomes (#758): terminal status and timing come from the
                       -- per-request trail; rows without one (coalesced) contribute zeros.
                       SUM(CASE WHEN rur.request_status IN (%s) THEN 1 ELSE 0 END) AS failed_requests,
                       SUM(CASE WHEN rur.request_status = 'CLIENT_CANCELLED' THEN 1 ELSE 0 END) AS cancelled_requests,
                       COALESCE(SUM(rur.duration_ms), 0) AS duration_sum_ms,
                       COUNT(rur.duration_ms) AS duration_count,
                       COALESCE(SUM(rur.time_to_first_byte_ms), 0) AS ttfb_sum_ms,
                       COUNT(rur.time_to_first_byte_ms) AS ttfb_count
                FROM usage_event ue
                %s%s%s
                %s
                %s
                GROUP BY %s, ue.provider_product_id, ue.model_id, ue.cache_level
                """.formatted(spec.select(), adjustmentDelta("input_delta", basis),
                adjustmentDelta("output_delta", basis), adjustmentDelta("cache_read_delta", basis),
                adjustmentDelta("cache_creation_delta", basis), zeroIfUnpriced(inputPrice), zeroIfUnpriced(outputPrice),
                zeroIfUnpriced(cacheReadPrice), zeroIfUnpriced(cacheCreationPrice),
                unpricedColumns(inputTokens, inputPrice, outputTokens, outputPrice, cacheReadTokens, cacheReadPrice,
                        cacheCreationTokens, cacheCreationPrice),
                FAILED_STATUS_SQL, spec.join(), wb.joins(), adjustmentJoin, LIFECYCLE_JOIN, wb.where(), spec.groupBy());
        MapSqlParameterSource params = wb.params();
        List<UsageStatsAggregator.UsageAggRow> rows = new ArrayList<>();
        jdbc.query(sql, params, rs -> {
            String groupKey = rs.getString("group_key");
            String label = rs.getString("label");
            UUID productId = (UUID) rs.getObject("product_id");
            String modelId = rs.getString("model_id");
            CacheLevel level = CacheLevel.valueOf(rs.getString("cache_level"));
            long requests = rs.getLong("requests");
            TokenBucket tokens = new TokenBucket(rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                    rs.getLong("cache_creation_tokens"), rs.getLong("cache_read_tokens"), null, null, null, null);
            var gap = new UsageStatsAggregator.PricingGap(rs.getLong("unpriced_input_tokens"),
                    rs.getLong("unpriced_output_tokens"), rs.getLong("unpriced_cache_read_tokens"),
                    rs.getLong("unpriced_cache_creation_tokens"), rs.getLong("unpriced_events"),
                    rs.getLong("unavailable_events"), 0L);
            UsageStatsAggregator.UsageAggRow.Outcome outcome = new UsageStatsAggregator.UsageAggRow.Outcome(
                    rs.getLong("failed_requests"), rs.getLong("cancelled_requests"), rs.getLong("duration_sum_ms"),
                    rs.getLong("duration_count"), rs.getLong("ttfb_sum_ms"), rs.getLong("ttfb_count"));
            rows.add(new UsageStatsAggregator.UsageAggRow(groupKey, label, productId, modelId, level, requests, tokens,
                    rs.getBigDecimal("input_cost"), rs.getBigDecimal("output_cost"),
                    rs.getBigDecimal("cache_read_cost"), rs.getBigDecimal("cache_creation_cost"), gap, outcome));
        });
        return rows;
    }

    @Override
    public List<UsageStatsAggregator.HitAggRow> aggregateHits(GroupBy groupBy, UsageFilter filter) {
        return aggregateHits(groupBy, filter, 0);
    }

    @Override
    public List<UsageStatsAggregator.HitAggRow> aggregateHits(GroupBy groupBy, UsageFilter filter,
            int tzOffsetMinutes) {
        GroupSpec spec = hitsSpec(groupBy, checkedOffset(tzOffsetMinutes));
        WhereBuilder wb = new WhereBuilder(filter, "h").cacheHitColumns();
        // Hits are valued from the price in force at the hit (#710), not the current
        // price —
        // otherwise "what the cache saved" silently changes every time a price is
        // edited.
        //
        // Grouping folds hits of one cache key across time, so a single instant has to
        // stand
        // for the whole group: the LATEST hit is used. That is an approximation (a
        // group
        // straddling a price change is valued entirely at the later price); it is
        // stated on
        // HitAggRow, and it is still far better than tracking the current table.
        String priceInput = PriceSnapshotSql.asOfUnitPrice(PriceTokenType.INPUT, "x.product_id", "x.model_id",
                "x.price_at");
        String priceOutput = PriceSnapshotSql.asOfUnitPrice(PriceTokenType.OUTPUT, "x.product_id", "x.model_id",
                "x.price_at");
        String priceCacheRead = PriceSnapshotSql.asOfUnitPrice(PriceTokenType.CACHE_READ, "x.product_id", "x.model_id",
                "x.price_at");
        String priceCacheCreation = PriceSnapshotSql.asOfUnitPrice(PriceTokenType.CACHE_CREATION, "x.product_id",
                "x.model_id", "x.price_at");
        String sql = """
                SELECT x.*,
                       %s AS price_input,
                       %s AS price_output,
                       %s AS price_cache_read,
                       %s AS price_cache_creation
                  FROM (
                    SELECT %s, e.provider_product_id AS product_id, e.model_id, e.cache_key, e.meta_json,
                           SUM(CASE WHEN h.level = 'L1_HIT' THEN 1 ELSE 0 END) AS l1,
                           SUM(CASE WHEN h.level = 'L2_HIT' THEN 1 ELSE 0 END) AS l2,
                           MAX(h.occurred_at) AS price_at
                    FROM cache_hit_event h
                    JOIN cache_entry e ON e.tenant_id = h.tenant_id AND e.cache_key = h.cache_key
                    %s%s
                    %s
                    GROUP BY %s, e.provider_product_id, e.model_id, e.cache_key, e.meta_json
                  ) x
                """.formatted(priceInput, priceOutput, priceCacheRead, priceCacheCreation, spec.select(), spec.join(),
                wb.joins(), wb.where(), spec.groupBy());
        MapSqlParameterSource params = wb.params();

        // Fold per-cache-key rows into per (group, product, model) rows.
        Map<String, HitAccumulator> acc = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            String groupKey = rs.getString("group_key");
            String label = rs.getString("label");
            UUID productId = (UUID) rs.getObject("product_id");
            String modelId = rs.getString("model_id");
            long l1 = rs.getLong("l1");
            long l2 = rs.getLong("l2");
            TokenBucket cached = parseUsage(rs.getString("meta_json"));
            long hits = l1 + l2;
            BigDecimal rowPriceInput = rs.getBigDecimal("price_input");
            BigDecimal rowPriceOutput = rs.getBigDecimal("price_output");
            BigDecimal rowPriceCacheRead = rs.getBigDecimal("price_cache_read");
            BigDecimal rowPriceCacheCreation = rs.getBigDecimal("price_cache_creation");

            if (groupBy == GroupBy.CACHE_LEVEL) {
                if (l1 > 0) {
                    acc.computeIfAbsent(key("L1_HIT", productId, modelId),
                            k -> new HitAccumulator("L1_HIT", "L1_HIT", productId, modelId)).add(l1, 0, cached, hits,
                                    rs.getBigDecimal("price_input"), rs.getBigDecimal("price_output"),
                                    rs.getBigDecimal("price_cache_read"), rs.getBigDecimal("price_cache_creation"));
                }
                if (l2 > 0) {
                    acc.computeIfAbsent(key("L2_HIT", productId, modelId),
                            k -> new HitAccumulator("L2_HIT", "L2_HIT", productId, modelId)).add(0, l2, cached, hits,
                                    rs.getBigDecimal("price_input"), rs.getBigDecimal("price_output"),
                                    rs.getBigDecimal("price_cache_read"), rs.getBigDecimal("price_cache_creation"));
                }
            } else {
                acc.computeIfAbsent(key(groupKey, productId, modelId),
                        k -> new HitAccumulator(groupKey, label, productId, modelId)).add(l1, l2, cached, hits,
                                rowPriceInput, rowPriceOutput, rowPriceCacheRead, rowPriceCacheCreation);
            }
        });

        List<UsageStatsAggregator.HitAggRow> rows = new ArrayList<>(acc.size());
        for (HitAccumulator a : acc.values()) {
            rows.add(a.toRow());
        }
        return rows;
    }

    @Override
    public List<UsageStatsRepository.HourlyUsageRow> aggregateHourly(UsageStatsRepository.HourlyDimension dimension,
            UsageFilter filter, int tzOffsetMinutes) {
        WhereBuilder wb = new WhereBuilder(filter, "ue").usageEventColumns();
        String dimSelect;
        String dimJoin;
        String dimGroup;
        switch (dimension) {
            case NONE -> {
                dimSelect = "CAST(NULL AS uuid) AS dimension_id, CAST(NULL AS text) AS dimension_label";
                dimJoin = "";
                dimGroup = "";
            }
            case USER -> {
                dimSelect = "vk.user_id AS dimension_id, u.username AS dimension_label";
                dimJoin = " JOIN virtual_keys vk ON vk.id = ue.virtual_key_id AND vk.tenant_id = ue.tenant_id"
                        + " JOIN users u ON u.id = vk.user_id AND u.tenant_id = ue.tenant_id";
                dimGroup = ", vk.user_id, u.username";
            }
            case TEAM -> {
                dimSelect = "tm.team_id AS dimension_id, t.name AS dimension_label";
                dimJoin = " JOIN virtual_keys vk ON vk.id = ue.virtual_key_id AND vk.tenant_id = ue.tenant_id"
                        + " JOIN team_memberships tm ON tm.user_id = vk.user_id AND tm.tenant_id = ue.tenant_id"
                        + " JOIN teams t ON t.id = tm.team_id AND t.tenant_id = ue.tenant_id";
                dimGroup = ", tm.team_id, t.name";
            }
            default -> throw new IllegalStateException("unhandled hourly dimension: " + dimension);
        }
        // Buckets are aligned to the caller's timezone: shift the instant by the
        // offset, floor to the hour, then shift back so hourStart is the UTC
        // instant of the LOCAL hour boundary (a UTC+8 bucketing of 06:10Z yields
        // 06:00Z == 14:00 local). Epoch arithmetic is session-timezone
        // independent, so the result is deterministic on any server.
        String sql = """
                SELECT %s,
                       to_timestamp(floor((extract(epoch FROM ue.occurred_at) + :tzOffsetMinutes * 60) / 3600.0)
                           * 3600.0 - :tzOffsetMinutes * 60) AS hour_start,
                       ue.project_id AS project_id, p.name AS project_label,
                       COUNT(*) AS requests,
                       COALESCE(SUM(COALESCE(ue.input_tokens, ue.prompt_tokens)), 0) AS input_tokens,
                       COALESCE(SUM(COALESCE(ue.output_tokens, ue.completion_tokens)), 0) AS output_tokens,
                       COALESCE(SUM(ue.cache_read_input_tokens), 0) AS cache_read_tokens,
                       COALESCE(SUM(ue.cache_creation_input_tokens), 0) AS cache_creation_tokens
                FROM usage_event ue
                JOIN projects p ON p.id = ue.project_id AND p.tenant_id = ue.tenant_id
                %s%s
                %s
                GROUP BY hour_start, ue.project_id, p.name%s
                ORDER BY hour_start, project_label%s
                """.formatted(dimSelect, dimJoin, wb.joins(), wb.where(), dimGroup,
                dimension == UsageStatsRepository.HourlyDimension.NONE ? "" : ", dimension_label");
        MapSqlParameterSource params = wb.params().addValue("tzOffsetMinutes", tzOffsetMinutes);
        List<UsageStatsRepository.HourlyUsageRow> rows = new ArrayList<>();
        jdbc.query(sql, params, rs -> {
            rows.add(new UsageStatsRepository.HourlyUsageRow(rs.getTimestamp("hour_start").toInstant(),
                    (UUID) rs.getObject("project_id"), rs.getString("project_label"),
                    (UUID) rs.getObject("dimension_id"), rs.getString("dimension_label"), rs.getLong("requests"),
                    rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getLong("cache_read_tokens"),
                    rs.getLong("cache_creation_tokens")));
        });
        return rows;
    }

    @Override
    public long countRecords(UsageFilter filter) {
        WhereBuilder wb = new WhereBuilder(filter, "ue").usageEventColumns();
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_event ue%s
                %s
                """.formatted(wb.joins(), wb.where()), wb.params(), Long.class);
        return count != null ? count : 0;
    }

    private static final RowMapper<UsageEvent> EVENT_ROW_MAPPER = (rs, rowNum) -> new UsageEvent(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), rs.getString("provider_request_id"),
            (UUID) rs.getObject("virtual_key_id"), (UUID) rs.getObject("project_id"),
            (UUID) rs.getObject("provider_product_id"), (UUID) rs.getObject("credential_id"), rs.getString("model_id"),
            CacheLevel.valueOf(rs.getString("cache_level")),
            new TokenBucket(rs.getObject("input_tokens", Long.class), rs.getObject("output_tokens", Long.class),
                    rs.getObject("cache_creation_input_tokens", Long.class),
                    rs.getObject("cache_read_input_tokens", Long.class), rs.getObject("prompt_tokens", Long.class),
                    rs.getObject("completion_tokens", Long.class), rs.getObject("total_tokens", Long.class),
                    rs.getObject("reasoning_tokens", Long.class)),
            rs.getObject("latency_ms", Long.class), rs.getObject("upstream_status_code", Integer.class),
            rs.getBytes("cache_key"), rs.getBoolean("is_complete"), rs.getBoolean("usage_missing"),
            rs.getString("gateway_request_id"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("client_ip"),
            attributionOf(rs));

    private static final RowMapper<AdjustedUsageRow> ADJUSTED_ROW_MAPPER = (rs, rowNum) -> new AdjustedUsageRow(
            EVENT_ROW_MAPPER.mapRow(rs, rowNum), rs.getObject("net_input_tokens", Long.class),
            rs.getObject("net_output_tokens", Long.class), rs.getObject("net_cache_read_tokens", Long.class),
            rs.getObject("net_cache_creation_tokens", Long.class), rs.getBoolean("adjusted"),
            rs.getString("provider_product_name"), lifecycleOf(rs),
            new RowPriceBasis(rs.getBigDecimal("basis_price_input"), rs.getBigDecimal("basis_price_output"),
                    rs.getBigDecimal("basis_price_cache_read"), rs.getBigDecimal("basis_price_cache_creation")));

    /** Lifecycle columns (#758); all null when the call has no lifecycle row. */
    private static LifecycleInfo lifecycleOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        String wireProtocol = rs.getString("wire_protocol");
        Long timeToFirstByteMs = rs.getObject("time_to_first_byte_ms", Long.class);
        String requestStatus = rs.getString("request_status");
        if (wireProtocol == null && timeToFirstByteMs == null && requestStatus == null) {
            return null;
        }
        return new LifecycleInfo(wireProtocol, timeToFirstByteMs, requestStatus);
    }

    /** CAA attribution columns (V54); all-null rows predate the feature. */
    private static UsageEvent.ContextAttribution attributionOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        String sessionId = rs.getString("session_id");
        Object activityId = rs.getObject("activity_id");
        Object claimedProjectId = rs.getObject("claimed_project_id");
        String resolutionStatus = rs.getString("resolution_status");
        String claimSource = rs.getString("claim_source");
        String claimConfidence = rs.getString("claim_confidence");
        if (sessionId == null && activityId == null && claimedProjectId == null && resolutionStatus == null
                && claimSource == null && claimConfidence == null) {
            return null;
        }
        // bindingTag has no usage_event column (it feeds the evidence table, V55);
        // rows read back from here never carry it.
        return new UsageEvent.ContextAttribution(sessionId, (UUID) activityId, (UUID) claimedProjectId,
                resolutionStatus, claimSource, claimConfidence, null);
    }

    @Override
    public List<AdjustedUsageRow> findRecords(UsageFilter filter, long offset, int limit) {
        WhereBuilder wb = new WhereBuilder(filter, "ue").usageEventColumns();
        MapSqlParameterSource params = wb.params().addValue("offset", offset).addValue("limit", limit);
        String netInput = UsageAdjustmentSql.netInput();
        String netOutput = UsageAdjustmentSql.netOutput();
        String netCacheRead = UsageAdjustmentSql.netCacheRead();
        String netCacheCreation = UsageAdjustmentSql.netCacheCreation();
        // Per-row price basis (#710): the same expression the aggregates price with,
        // so a detail row and the group it belongs to cannot disagree. Aliased apart
        // from ue.* — the raw ue.price_* columns are already in the projection and a
        // duplicate name would silently win. COALESCE short-circuits, so the as-of
        // subquery only runs for rows the backfill has not stamped yet.
        String basisInput = PriceSnapshotSql.frozenOrAsOf("ue.price_input", PriceTokenType.INPUT,
                "ue.provider_product_id", "ue.model_id", "ue.occurred_at");
        String basisOutput = PriceSnapshotSql.frozenOrAsOf("ue.price_output", PriceTokenType.OUTPUT,
                "ue.provider_product_id", "ue.model_id", "ue.occurred_at");
        String basisCacheRead = PriceSnapshotSql.frozenOrAsOf("ue.price_cache_read", PriceTokenType.CACHE_READ,
                "ue.provider_product_id", "ue.model_id", "ue.occurred_at");
        String basisCacheCreation = PriceSnapshotSql.frozenOrAsOf("ue.price_cache_creation",
                PriceTokenType.CACHE_CREATION, "ue.provider_product_id", "ue.model_id", "ue.occurred_at");
        return jdbc.query(
                """
                        SELECT ue.*,
                               %s AS net_input_tokens,
                               %s AS net_output_tokens,
                               %s AS net_cache_read_tokens,
                               %s AS net_cache_creation_tokens,
                               %s AS adjusted,
                               %s AS basis_price_input,
                               %s AS basis_price_output,
                               %s AS basis_price_cache_read,
                               %s AS basis_price_cache_creation,
                               -- Enrichment (#758): the provider product's display name for the
                               -- 供应商 column, plus the lifecycle trail for 首字/协议/终态.
                               COALESCE(pp.display_name, pp.product_code) AS provider_product_name,
                               rur.wire_protocol,
                               rur.time_to_first_byte_ms,
                               rur.request_status
                          FROM usage_event ue
                          LEFT JOIN provider_products pp ON pp.id = ue.provider_product_id%s%s
                        %s
                        %s
                        ORDER BY ue.occurred_at DESC
                        LIMIT :limit OFFSET :offset
                        """.formatted(netInput, netOutput, netCacheRead, netCacheCreation,
                        UsageAdjustmentSql.ADJUSTED_FLAG, basisInput, basisOutput, basisCacheRead, basisCacheCreation,
                        wb.joins(), UsageAdjustmentSql.ADJUSTMENT_LATERAL, LIFECYCLE_JOIN, wb.where()),
                params, ADJUSTED_ROW_MAPPER);
    }

    private TokenBucket parseUsage(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return TokenBucket.EMPTY;
        }
        try {
            JsonNode usage = objectMapper.readTree(metaJson).path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return TokenBucket.EMPTY;
            }
            return new TokenBucket(longOrNull(usage, "inputTokens"), longOrNull(usage, "outputTokens"),
                    longOrNull(usage, "cacheCreationInputTokens"), longOrNull(usage, "cacheReadInputTokens"),
                    longOrNull(usage, "promptTokens"), longOrNull(usage, "completionTokens"),
                    longOrNull(usage, "totalTokens"), longOrNull(usage, "reasoningTokens"));
        } catch (Exception e) {
            return TokenBucket.EMPTY;
        }
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.canConvertToLong() ? v.asLong() : null;
    }

    private static String key(String groupKey, UUID productId, String modelId) {
        return groupKey + "|" + productId + "|" + modelId;
    }

    /**
     * Accumulates per (group, product, model) hit counts plus a hit-weighted mean
     * of the cached responses' token usage.
     */
    private static final class HitAccumulator {
        private final String groupKey;
        private final String label;
        private final UUID productId;
        private final String modelId;
        private long l1;
        private long l2;
        private long weightedInput;
        private long weightedOutput;
        private long weightedCacheRead;
        private long weightedCacheCreation;
        private long totalHits;
        /**
         * Hits whose tokens carried no price in force — the saving is a lower bound
         * (#790).
         */
        private long unpricedHits;
        // Undivided sums of (cached tokens x hit count x unit price). Kept undivided so
        // the
        // only rounding happens in the aggregator, with the same MathContext as before.
        private BigDecimal inputCost = BigDecimal.ZERO;
        private BigDecimal outputCost = BigDecimal.ZERO;
        private BigDecimal cacheReadCost = BigDecimal.ZERO;
        private BigDecimal cacheCreationCost = BigDecimal.ZERO;

        private HitAccumulator(String groupKey, String label, UUID productId, String modelId) {
            this.groupKey = groupKey;
            this.label = label;
            this.productId = productId;
            this.modelId = modelId;
        }

        void add(long addL1, long addL2, TokenBucket cached, long hits, BigDecimal priceInput, BigDecimal priceOutput,
                BigDecimal priceCacheRead, BigDecimal priceCacheCreation) {
            l1 += addL1;
            l2 += addL2;
            if (hits <= 0) {
                return;
            }
            long input = cached != null && cached.inputTokens() != null
                    ? cached.inputTokens()
                    : (cached != null && cached.promptTokens() != null ? cached.promptTokens() : 0);
            long output = cached != null && cached.outputTokens() != null
                    ? cached.outputTokens()
                    : (cached != null && cached.completionTokens() != null ? cached.completionTokens() : 0);
            long cacheRead = cached != null && cached.cacheReadInputTokens() != null
                    ? cached.cacheReadInputTokens()
                    : 0;
            long cacheCreation = cached != null && cached.cacheCreationInputTokens() != null
                    ? cached.cacheCreationInputTokens()
                    : 0;
            weightedInput += input * hits;
            weightedOutput += output * hits;
            weightedCacheRead += cacheRead * hits;
            weightedCacheCreation += cacheCreation * hits;
            totalHits += hits;
            inputCost = inputCost.add(weighted(input, hits, priceInput));
            outputCost = outputCost.add(weighted(output, hits, priceOutput));
            cacheReadCost = cacheReadCost.add(weighted(cacheRead, hits, priceCacheRead));
            cacheCreationCost = cacheCreationCost.add(weighted(cacheCreation, hits, priceCacheCreation));
            if (unpriced(input, hits, priceInput) || unpriced(output, hits, priceOutput)
                    || unpriced(cacheRead, hits, priceCacheRead) || unpriced(cacheCreation, hits, priceCacheCreation)) {
                unpricedHits += hits;
            }
        }

        /**
         * {@code tokens x hits x unitPrice}, undivided; a null price contributes
         * nothing.
         *
         * <p>
         * "Nothing" is the honest answer for this sum and the wrong answer for the
         * report: the tokens are real and only the price is missing, so the caller also
         * counts the hit as unpriced (#790). Otherwise a hit we could not value is
         * indistinguishable from a hit that saved nothing.
         * </p>
         */
        private static BigDecimal weighted(long tokens, long hits, BigDecimal unitPrice) {
            return unitPrice == null ? BigDecimal.ZERO : BigDecimal.valueOf(tokens * hits).multiply(unitPrice);
        }

        /** A dimension that carried tokens while no price was in force for it. */
        private static boolean unpriced(long tokens, long hits, BigDecimal unitPrice) {
            return tokens > 0 && hits > 0 && unitPrice == null;
        }

        UsageStatsAggregator.HitAggRow toRow() {
            long hits = Math.max(totalHits, 1);
            TokenBucket mean = new TokenBucket(weightedInput / hits, weightedOutput / hits,
                    weightedCacheCreation / hits, weightedCacheRead / hits, null, null, null, null);
            return new UsageStatsAggregator.HitAggRow(groupKey, label, productId, modelId, l1, l2, mean, inputCost,
                    outputCost, cacheReadCost, cacheCreationCost, unpricedHits);
        }
    }
}
