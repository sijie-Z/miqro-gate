package com.miqroera.miqrokey.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L2 PostgreSQL cache ({@code cache_entry}). Stores raw response bytes and
 * replays them byte-identically; hit counters accumulate across overwrites.
 *
 * <p>
 * Runs on the gateway's bounded scheduler — never on the Reactor event loop
 * (blocking JDBC).
 * </p>
 */
public final class PostgresCacheProvider implements GatewayResponseCache {

    private static final Logger log = LoggerFactory.getLogger(PostgresCacheProvider.class);

    private static final TypeReference<Map<String, List<String>>> HEADER_MAP_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final java.time.Duration ttl;

    public PostgresCacheProvider(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, java.time.Duration ttl) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public Lookup get(UUID tenantId, CacheKey key) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("cacheKey", key.sha256());
        Row row = jdbc.query("""
                SELECT status_code, content_type, response_headers, body, meta_json
                FROM cache_entry
                WHERE tenant_id = :tenantId AND cache_key = :cacheKey
                  AND (expires_at IS NULL OR expires_at > now())
                LIMIT 1
                """, params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new Row(rs.getInt("status_code"), rs.getString("content_type"), rs.getString("response_headers"),
                    rs.getBytes("body"), rs.getString("meta_json"));
        });
        if (row == null) {
            return Lookup.miss();
        }
        try {
            Map<String, List<String>> headers = objectMapper.readValue(row.responseHeaders(), HEADER_MAP_TYPE);
            TokenBucket usage = parseUsage(row.metaJson());
            CachedResponse response = new CachedResponse(row.statusCode(), row.contentType(), headers, row.body(),
                    usage, true);
            return new Lookup(Optional.of(response), LookupLevel.L2_HIT);
        } catch (Exception e) {
            log.warn("Corrupt cache entry for key {}; treating as miss: {}", key.hex(), e.getMessage());
            return Lookup.miss();
        }
    }

    @Override
    public void put(CacheKey key, UUID tenantId, UUID virtualKeyId, UUID projectId, UUID productId, String modelId,
            CachedResponse response) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("complete", response.isComplete());
            if (response.usage() != null && !response.usage().isEmpty()) {
                meta.put("usage", response.usage());
            }
            var params = new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                    .addValue("cacheKey", key.sha256()).addValue("virtualKeyId", virtualKeyId)
                    .addValue("projectId", projectId).addValue("productId", productId).addValue("modelId", modelId)
                    .addValue("statusCode", response.statusCode()).addValue("contentType", response.contentType())
                    .addValue("responseHeaders", objectMapper.writeValueAsString(response.headers()))
                    .addValue("body", response.body()).addValue("meta", objectMapper.writeValueAsString(meta))
                    .addValue("expiresAt", java.sql.Timestamp.from(java.time.Instant.now().plus(ttl)));
            jdbc.update("""
                    INSERT INTO cache_entry (id, tenant_id, cache_key, virtual_key_id, project_id,
                        provider_product_id, model_id, status_code, content_type, response_headers,
                        body, meta_json, expires_at, created_at, updated_at)
                    VALUES (:id, :tenantId, :cacheKey, :virtualKeyId, :projectId, :productId, :modelId,
                        :statusCode, :contentType, :responseHeaders, :body, :meta, :expiresAt, now(), now())
                    ON CONFLICT (tenant_id, cache_key) DO UPDATE SET
                        status_code = EXCLUDED.status_code,
                        content_type = EXCLUDED.content_type,
                        response_headers = EXCLUDED.response_headers,
                        body = EXCLUDED.body,
                        meta_json = EXCLUDED.meta_json,
                        expires_at = EXCLUDED.expires_at,
                        updated_at = now()
                    """, params); // hit_count is NOT reset on overwrite
        } catch (Exception e) {
            log.warn("Cache write failed for key {}: {}", key.hex(), e.getMessage());
        }
    }

    @Override
    public void invalidateProject(UUID tenantId, UUID projectId) {
        jdbc.update("DELETE FROM cache_entry WHERE tenant_id = :tenantId AND project_id = :projectId",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("projectId", projectId));
    }

    @Override
    public long l1Size() {
        return 0;
    }

    private TokenBucket parseUsage(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return TokenBucket.EMPTY;
        }
        try {
            JsonNode usage = objectMapper.readTree(metaJson).path("usage");
            if (usage.isMissingNode() || !usage.isObject()) {
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
        return v != null && v.isNumber() ? v.asLong() : null;
    }

    private record Row(int statusCode, String contentType, String responseHeaders, byte[] body, String metaJson) {
    }
}
