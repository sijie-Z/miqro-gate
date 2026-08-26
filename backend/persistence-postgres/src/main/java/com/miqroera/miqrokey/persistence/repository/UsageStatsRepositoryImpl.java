package com.miqroera.miqrokey.persistence.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    private GroupSpec spec(GroupBy groupBy) {
        return switch (groupBy) {
            case PROJECT -> new GroupSpec("ue.project_id AS group_key, p.name AS label",
                    "JOIN projects p ON p.id = ue.project_id AND p.tenant_id = ue.tenant_id", "ue.project_id, p.name");
            case VIRTUAL_KEY ->
                new GroupSpec("ue.virtual_key_id AS group_key, COALESCE(vk.name, vk.last_four) AS label",
                        "JOIN virtual_keys vk ON vk.id = ue.virtual_key_id AND vk.tenant_id = ue.tenant_id",
                        "ue.virtual_key_id, COALESCE(vk.name, vk.last_four)");
            case CACHE_LEVEL ->
                new GroupSpec("ue.cache_level AS group_key, ue.cache_level AS label", "", "ue.cache_level");
            case DAY ->
                new GroupSpec("CAST(ue.occurred_at AS DATE) AS group_key, CAST(ue.occurred_at AS DATE) AS label", "",
                        "CAST(ue.occurred_at AS DATE)");
        };
    }

    /**
     * Group spec over {@code cache_hit_event h} joined to {@code cache_entry e}.
     * The CACHE_LEVEL split (L1_HIT / L2_HIT) is done in Java, so the SQL groups
     * everything under a constant key.
     */
    private GroupSpec hitsSpec(GroupBy groupBy) {
        return switch (groupBy) {
            case PROJECT -> new GroupSpec("h.project_id AS group_key, p.name AS label",
                    "JOIN projects p ON p.id = h.project_id AND p.tenant_id = h.tenant_id", "h.project_id, p.name");
            case VIRTUAL_KEY -> new GroupSpec("h.virtual_key_id AS group_key, COALESCE(vk.name, vk.last_four) AS label",
                    "JOIN virtual_keys vk ON vk.id = h.virtual_key_id AND vk.tenant_id = h.tenant_id",
                    "h.virtual_key_id, COALESCE(vk.name, vk.last_four)");
            case CACHE_LEVEL -> new GroupSpec("'HIT' AS group_key, 'HIT' AS label", "", "'HIT'");
            case DAY -> new GroupSpec("CAST(h.occurred_at AS DATE) AS group_key, CAST(h.occurred_at AS DATE) AS label",
                    "", "CAST(h.occurred_at AS DATE)");
        };
    }

    /**
     * Builds the dynamic WHERE clause and parameters for the optional G4.1
     * dimensions. {@code alias} is the filterable base table alias ({@code ue} for
     * {@code usage_event}, {@code h} for {@code cache_hit_event}); the
     * user/subscription dimensions join through {@code virtual_keys} ({@code vk})
     * and {@code credentials} ({@code cr}).
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
                joins.append(" JOIN credentials crf ON crf.id = vkf.credential_id AND crf.tenant_id = vkf.tenant_id");
                conditions.add("crf.subscription_id = :subscriptionId");
                params.addValue("subscriptionId", filter.subscriptionId());
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

    @Override
    public List<UsageStatsAggregator.UsageAggRow> aggregateUsage(GroupBy groupBy, UsageFilter filter) {
        GroupSpec spec = spec(groupBy);
        WhereBuilder wb = new WhereBuilder(filter, "ue").usageEventColumns();
        String sql = """
                SELECT %s, ue.provider_product_id AS product_id, ue.model_id, ue.cache_level AS cache_level,
                       COUNT(*) AS requests,
                       COALESCE(SUM(COALESCE(ue.input_tokens, ue.prompt_tokens)), 0) AS input_tokens,
                       COALESCE(SUM(COALESCE(ue.output_tokens, ue.completion_tokens)), 0) AS output_tokens,
                       COALESCE(SUM(ue.cache_read_input_tokens), 0) AS cache_read_tokens,
                       COALESCE(SUM(ue.cache_creation_input_tokens), 0) AS cache_creation_tokens
                FROM usage_event ue
                %s%s
                %s
                GROUP BY %s, ue.provider_product_id, ue.model_id, ue.cache_level
                """.formatted(spec.select(), spec.join(), wb.joins(), wb.where(), spec.groupBy());
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
            rows.add(
                    new UsageStatsAggregator.UsageAggRow(groupKey, label, productId, modelId, level, requests, tokens));
        });
        return rows;
    }

    @Override
    public List<UsageStatsAggregator.HitAggRow> aggregateHits(GroupBy groupBy, UsageFilter filter) {
        GroupSpec spec = hitsSpec(groupBy);
        WhereBuilder wb = new WhereBuilder(filter, "h").cacheHitColumns();
        String sql = """
                SELECT %s, e.provider_product_id AS product_id, e.model_id, e.cache_key, e.meta_json,
                       SUM(CASE WHEN h.level = 'L1_HIT' THEN 1 ELSE 0 END) AS l1,
                       SUM(CASE WHEN h.level = 'L2_HIT' THEN 1 ELSE 0 END) AS l2
                FROM cache_hit_event h
                JOIN cache_entry e ON e.tenant_id = h.tenant_id AND e.cache_key = h.cache_key
                %s%s
                %s
                GROUP BY %s, e.provider_product_id, e.model_id, e.cache_key, e.meta_json
                """.formatted(spec.select(), spec.join(), wb.joins(), wb.where(), spec.groupBy());
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

            if (groupBy == GroupBy.CACHE_LEVEL) {
                if (l1 > 0) {
                    acc.computeIfAbsent(key("L1_HIT", productId, modelId),
                            k -> new HitAccumulator("L1_HIT", "L1_HIT", productId, modelId)).add(l1, 0, cached, hits);
                }
                if (l2 > 0) {
                    acc.computeIfAbsent(key("L2_HIT", productId, modelId),
                            k -> new HitAccumulator("L2_HIT", "L2_HIT", productId, modelId)).add(0, l2, cached, hits);
                }
            } else {
                acc.computeIfAbsent(key(groupKey, productId, modelId),
                        k -> new HitAccumulator(groupKey, label, productId, modelId)).add(l1, l2, cached, hits);
            }
        });

        List<UsageStatsAggregator.HitAggRow> rows = new ArrayList<>(acc.size());
        for (HitAccumulator a : acc.values()) {
            rows.add(a.toRow());
        }
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
            rs.getString("gateway_request_id"), rs.getTimestamp("occurred_at").toInstant());

    @Override
    public List<UsageEvent> findRecords(UsageFilter filter, long offset, int limit) {
        WhereBuilder wb = new WhereBuilder(filter, "ue").usageEventColumns();
        MapSqlParameterSource params = wb.params().addValue("offset", offset).addValue("limit", limit);
        return jdbc.query("""
                SELECT ue.* FROM usage_event ue%s
                %s
                ORDER BY ue.occurred_at DESC
                LIMIT :limit OFFSET :offset
                """.formatted(wb.joins(), wb.where()), params, EVENT_ROW_MAPPER);
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

        private HitAccumulator(String groupKey, String label, UUID productId, String modelId) {
            this.groupKey = groupKey;
            this.label = label;
            this.productId = productId;
            this.modelId = modelId;
        }

        void add(long addL1, long addL2, TokenBucket cached, long hits) {
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
        }

        UsageStatsAggregator.HitAggRow toRow() {
            long hits = Math.max(totalHits, 1);
            TokenBucket mean = new TokenBucket(weightedInput / hits, weightedOutput / hits,
                    weightedCacheCreation / hits, weightedCacheRead / hits, null, null, null, null);
            return new UsageStatsAggregator.HitAggRow(groupKey, label, productId, modelId, l1, l2, mean);
        }
    }
}
