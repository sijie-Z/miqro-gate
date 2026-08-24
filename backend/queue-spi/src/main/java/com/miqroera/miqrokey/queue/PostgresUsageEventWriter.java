package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC batch writer. One transaction per batch; idempotency comes from the
 * partial unique indexes (see {@link UsageEventWriter}).
 *
 * <p>
 * Never runs on the Reactor event loop — the bus flush task owns it.
 * </p>
 */
public final class PostgresUsageEventWriter implements UsageEventWriter {

    private static final Logger log = LoggerFactory.getLogger(PostgresUsageEventWriter.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public PostgresUsageEventWriter(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void writeBatch(List<UsageEvent> usageEvents, List<CacheHitEvent> hitEvents) {
        if (usageEvents.isEmpty() && hitEvents.isEmpty()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (!usageEvents.isEmpty()) {
                    writeUsage(usageEvents);
                }
                if (!hitEvents.isEmpty()) {
                    writeHits(hitEvents);
                }
            });
        } catch (Exception e) {
            // Idempotent inserts: a failed batch can be retried safely.
            log.warn("Usage batch write failed (usage={}, hits={}); will be retried on next flush: {}",
                    usageEvents.size(), hitEvents.size(), e.getMessage());
            throw e;
        }
    }

    private void writeUsage(List<UsageEvent> events) {
        List<MapSqlParameterSource> params = new ArrayList<>(events.size());
        for (UsageEvent e : events) {
            params.add(new MapSqlParameterSource().addValue("id", e.id()).addValue("tenantId", e.tenantId())
                    .addValue("providerRequestId", e.providerRequestId()).addValue("virtualKeyId", e.virtualKeyId())
                    .addValue("projectId", e.projectId()).addValue("productId", e.providerProductId())
                    .addValue("credentialId", e.credentialId()).addValue("modelId", e.modelId())
                    .addValue("cacheLevel", e.cacheLevel().name())
                    .addValue("inputTokens", e.tokens() != null ? e.tokens().inputTokens() : null)
                    .addValue("outputTokens", e.tokens() != null ? e.tokens().outputTokens() : null)
                    .addValue("cacheCreation", e.tokens() != null ? e.tokens().cacheCreationInputTokens() : null)
                    .addValue("cacheRead", e.tokens() != null ? e.tokens().cacheReadInputTokens() : null)
                    .addValue("promptTokens", e.tokens() != null ? e.tokens().promptTokens() : null)
                    .addValue("completionTokens", e.tokens() != null ? e.tokens().completionTokens() : null)
                    .addValue("totalTokens", e.tokens() != null ? e.tokens().totalTokens() : null)
                    .addValue("reasoningTokens", e.tokens() != null ? e.tokens().reasoningTokens() : null)
                    .addValue("latencyMs", e.latencyMs()).addValue("upstreamStatusCode", e.upstreamStatusCode())
                    .addValue("cacheKey", e.cacheKey()).addValue("isComplete", e.isComplete())
                    .addValue("usageMissing", e.usageMissing()).addValue("gatewayRequestId", e.gatewayRequestId())
                    .addValue("occurredAt", Timestamp.from(e.occurredAt())));
        }
        jdbc.batchUpdate("""
                INSERT INTO usage_event (id, tenant_id, provider_request_id, virtual_key_id, project_id,
                    provider_product_id, credential_id, model_id, cache_level,
                    input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                    prompt_tokens, completion_tokens, total_tokens, reasoning_tokens,
                    latency_ms, upstream_status_code, cache_key, is_complete, usage_missing,
                    gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :providerRequestId, :virtualKeyId, :projectId, :productId, :credentialId,
                    :modelId, :cacheLevel,
                    :inputTokens, :outputTokens, :cacheCreation, :cacheRead,
                    :promptTokens, :completionTokens, :totalTokens, :reasoningTokens,
                    :latencyMs, :upstreamStatusCode, :cacheKey, :isComplete, :usageMissing,
                    :gatewayRequestId, :occurredAt)
                ON CONFLICT (tenant_id, provider_request_id) WHERE provider_request_id IS NOT NULL DO NOTHING
                """, params.toArray(new MapSqlParameterSource[0]));
    }

    private void writeHits(List<CacheHitEvent> events) {
        List<MapSqlParameterSource> insertParams = new ArrayList<>(events.size());
        List<MapSqlParameterSource> counterParams = new ArrayList<>(events.size());
        for (CacheHitEvent e : events) {
            insertParams.add(
                    new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenantId", e.tenantId())
                            .addValue("cacheKey", e.cacheKey()).addValue("virtualKeyId", e.virtualKeyId())
                            .addValue("projectId", e.projectId()).addValue("productId", e.providerProductId())
                            .addValue("level", e.level().name()).addValue("gatewayRequestId", e.gatewayRequestId())
                            .addValue("occurredAt", Timestamp.from(e.occurredAt())));
            boolean l1 = e.level() == com.miqroera.miqrokey.domain.usage.CacheLevel.L1_HIT;
            counterParams.add(new MapSqlParameterSource().addValue("tenantId", e.tenantId())
                    .addValue("cacheKey", e.cacheKey()).addValue("l1", l1).addValue("l2", !l1));
        }
        jdbc.batchUpdate("""
                INSERT INTO cache_hit_event (id, tenant_id, cache_key, virtual_key_id, project_id,
                    provider_product_id, level, occurred_at, gateway_request_id)
                VALUES (:id, :tenantId, :cacheKey, :virtualKeyId, :projectId, :productId, :level, :occurredAt,
                    :gatewayRequestId)
                ON CONFLICT (tenant_id, cache_key, level, occurred_at) DO NOTHING
                """, insertParams.toArray(new MapSqlParameterSource[0]));
        jdbc.batchUpdate("""
                UPDATE cache_entry SET
                    hit_count_l1 = hit_count_l1 + CASE WHEN :l1 THEN 1 ELSE 0 END,
                    hit_count_l2 = hit_count_l2 + CASE WHEN :l2 THEN 1 ELSE 0 END
                WHERE tenant_id = :tenantId AND cache_key = :cacheKey
                """, counterParams.toArray(new MapSqlParameterSource[0]));
    }
}
