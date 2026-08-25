package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStartedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStatus;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
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
 * partial unique indexes (see {@link UsageEventWriter}). Request lifecycle
 * records are written with a guarded upsert: starts insert {@code IN_FLIGHT}
 * rows ({@code ON CONFLICT DO NOTHING}), completions update only
 * {@code IN_FLIGHT} rows — a finalized record is never rewritten and a retried
 * flush never double-finalizes.
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
    public void writeBatch(List<UsageEvent> usageEvents, List<CacheHitEvent> hitEvents,
            List<RequestStartedEvent> startedEvents, List<RequestCompletedEvent> completedEvents) {
        if (usageEvents.isEmpty() && hitEvents.isEmpty() && startedEvents.isEmpty() && completedEvents.isEmpty()) {
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
                if (!startedEvents.isEmpty()) {
                    writeStarted(startedEvents);
                }
                if (!completedEvents.isEmpty()) {
                    writeCompleted(completedEvents);
                }
            });
        } catch (Exception e) {
            // Idempotent writes: a failed batch can be retried safely.
            log.warn(
                    "Usage batch write failed (usage={}, hits={}, starts={}, completions={}); will be retried on next flush: {}",
                    usageEvents.size(), hitEvents.size(), startedEvents.size(), completedEvents.size(), e.getMessage());
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

    /**
     * Opens lifecycle records as {@code IN_FLIGHT}. Retried flushes are no-ops
     * ({@code ON CONFLICT (started_at, gateway_request_id) DO NOTHING}).
     */
    private void writeStarted(List<RequestStartedEvent> events) {
        List<MapSqlParameterSource> params = new ArrayList<>(events.size());
        for (RequestStartedEvent e : events) {
            params.add(startParams(e.id(), e.startedAt(), e.gatewayRequestId(), e.tenantId(), e.userId(), e.projectId(),
                    e.virtualKeyId(), e.providerId(), e.providerProductId(), e.credentialId(), e.wireProtocol(),
                    e.modelId(), e.streaming(), RequestStatus.IN_FLIGHT.name()).addValue("firstByteAt", null)
                    .addValue("completedAt", null).addValue("durationMs", null).addValue("ttfbMs", null)
                    .addValue("httpStatus", null).addValue("clientCancelled", false).addValue("partialResponse", false)
                    .addValue("upstreamRequestId", null).addValue("inputTokens", null).addValue("outputTokens", null)
                    .addValue("cacheCreation", null).addValue("cacheRead", null).addValue("promptTokens", null)
                    .addValue("completionTokens", null).addValue("totalTokens", null).addValue("reasoningTokens", null)
                    .addValue("usageMissing", false).addValue("finalizedAt", null));
        }
        jdbc.batchUpdate("""
                INSERT INTO request_usage_records (started_at, id, gateway_request_id, tenant_id, user_id,
                    project_id, virtual_key_id, provider_id, provider_product_id, credential_id, model_id,
                    wire_protocol, streaming, request_status,
                    upstream_request_id, first_byte_at, completed_at, duration_ms, time_to_first_byte_ms,
                    http_status, client_cancelled, partial_response, retry_count,
                    input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                    prompt_tokens, completion_tokens, total_tokens, reasoning_tokens,
                    usage_missing, finalized_at)
                VALUES (:startedAt, :id, :gatewayRequestId, :tenantId, :userId, :projectId, :virtualKeyId,
                    :providerId, :productId, :credentialId, :modelId, :wireProtocol, :streaming, :status,
                    :upstreamRequestId, :firstByteAt, :completedAt, :durationMs, :ttfbMs, :httpStatus,
                    :clientCancelled, :partialResponse, 0,
                    :inputTokens, :outputTokens, :cacheCreation, :cacheRead, :promptTokens, :completionTokens,
                    :totalTokens, :reasoningTokens, :usageMissing, :finalizedAt)
                ON CONFLICT (started_at, gateway_request_id) DO NOTHING
                """, params.toArray(new MapSqlParameterSource[0]));
    }

    /**
     * Finalizes lifecycle records exactly once. The guarded upsert only transitions
     * {@code IN_FLIGHT} rows: a second completion (retried flush) is a no-op, and a
     * completed record's business fields are never rewritten. When the start row
     * was never persisted, the completion inserts a standalone final row (the event
     * carries the full start snapshot).
     */
    private void writeCompleted(List<RequestCompletedEvent> events) {
        List<MapSqlParameterSource> params = new ArrayList<>(events.size());
        for (RequestCompletedEvent e : events) {
            TokenBucket tokens = e.tokens();
            params.add(startParams(e.id(), e.startedAt(), e.gatewayRequestId(), e.tenantId(), e.userId(), e.projectId(),
                    e.virtualKeyId(), e.providerId(), e.providerProductId(), e.credentialId(), e.wireProtocol(),
                    e.modelId(), e.streaming(), e.status().name())
                    .addValue("firstByteAt", timestampOrNull(e.firstByteAt()))
                    .addValue("completedAt", timestampOrNull(e.completedAt())).addValue("durationMs", e.durationMs())
                    .addValue("ttfbMs", e.timeToFirstByteMs()).addValue("httpStatus", e.httpStatus())
                    .addValue("clientCancelled", e.clientCancelled()).addValue("partialResponse", e.partialResponse())
                    .addValue("upstreamRequestId", e.upstreamRequestId())
                    .addValue("inputTokens", tokens != null ? tokens.inputTokens() : null)
                    .addValue("outputTokens", tokens != null ? tokens.outputTokens() : null)
                    .addValue("cacheCreation", tokens != null ? tokens.cacheCreationInputTokens() : null)
                    .addValue("cacheRead", tokens != null ? tokens.cacheReadInputTokens() : null)
                    .addValue("promptTokens", tokens != null ? tokens.promptTokens() : null)
                    .addValue("completionTokens", tokens != null ? tokens.completionTokens() : null)
                    .addValue("totalTokens", tokens != null ? tokens.totalTokens() : null)
                    .addValue("reasoningTokens", tokens != null ? tokens.reasoningTokens() : null)
                    .addValue("usageMissing", e.usageMissing())
                    .addValue("finalizedAt", timestampOrNull(e.completedAt())));
        }
        jdbc.batchUpdate("""
                INSERT INTO request_usage_records (started_at, id, gateway_request_id, tenant_id, user_id,
                    project_id, virtual_key_id, provider_id, provider_product_id, credential_id, model_id,
                    wire_protocol, streaming, request_status,
                    upstream_request_id, first_byte_at, completed_at, duration_ms, time_to_first_byte_ms,
                    http_status, client_cancelled, partial_response, retry_count,
                    input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                    prompt_tokens, completion_tokens, total_tokens, reasoning_tokens,
                    usage_missing, finalized_at)
                VALUES (:startedAt, :id, :gatewayRequestId, :tenantId, :userId, :projectId, :virtualKeyId,
                    :providerId, :productId, :credentialId, :modelId, :wireProtocol, :streaming, :status,
                    :upstreamRequestId, :firstByteAt, :completedAt, :durationMs, :ttfbMs, :httpStatus,
                    :clientCancelled, :partialResponse, 0,
                    :inputTokens, :outputTokens, :cacheCreation, :cacheRead, :promptTokens, :completionTokens,
                    :totalTokens, :reasoningTokens, :usageMissing, :finalizedAt)
                ON CONFLICT (started_at, gateway_request_id) DO UPDATE SET
                    upstream_request_id = EXCLUDED.upstream_request_id,
                    first_byte_at = EXCLUDED.first_byte_at,
                    completed_at = EXCLUDED.completed_at,
                    duration_ms = EXCLUDED.duration_ms,
                    time_to_first_byte_ms = EXCLUDED.time_to_first_byte_ms,
                    http_status = EXCLUDED.http_status,
                    request_status = EXCLUDED.request_status,
                    client_cancelled = EXCLUDED.client_cancelled,
                    partial_response = EXCLUDED.partial_response,
                    retry_count = EXCLUDED.retry_count,
                    input_tokens = EXCLUDED.input_tokens,
                    output_tokens = EXCLUDED.output_tokens,
                    cache_creation_input_tokens = EXCLUDED.cache_creation_input_tokens,
                    cache_read_input_tokens = EXCLUDED.cache_read_input_tokens,
                    prompt_tokens = EXCLUDED.prompt_tokens,
                    completion_tokens = EXCLUDED.completion_tokens,
                    total_tokens = EXCLUDED.total_tokens,
                    reasoning_tokens = EXCLUDED.reasoning_tokens,
                    usage_missing = EXCLUDED.usage_missing,
                    finalized_at = EXCLUDED.finalized_at
                WHERE request_usage_records.request_status = 'IN_FLIGHT'
                """, params.toArray(new MapSqlParameterSource[0]));
    }

    private static MapSqlParameterSource startParams(UUID id, java.time.Instant startedAt, String gatewayRequestId,
            UUID tenantId, UUID userId, UUID projectId, UUID virtualKeyId, UUID providerId, UUID productId,
            UUID credentialId, String wireProtocol, String modelId, boolean streaming, String status) {
        return new MapSqlParameterSource().addValue("startedAt", Timestamp.from(startedAt)).addValue("id", id)
                .addValue("gatewayRequestId", gatewayRequestId).addValue("tenantId", tenantId)
                .addValue("userId", userId).addValue("projectId", projectId).addValue("virtualKeyId", virtualKeyId)
                .addValue("providerId", providerId).addValue("productId", productId)
                .addValue("credentialId", credentialId).addValue("modelId", modelId)
                .addValue("wireProtocol", wireProtocol).addValue("streaming", streaming).addValue("status", status);
    }

    private static Timestamp timestampOrNull(java.time.Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
