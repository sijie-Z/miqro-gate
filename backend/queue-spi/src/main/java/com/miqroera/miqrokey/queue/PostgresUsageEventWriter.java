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
 * replayed event keeping its id plus the partial unique index on
 * {@code (tenant_id, provider_request_id)} (see {@link UsageEventWriter}).
 * Request lifecycle
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
                    "Usage batch write failed (usage={}, hits={}, starts={}, completions={}); will be retried on next flush",
                    usageEvents.size(), hitEvents.size(), startedEvents.size(), completedEvents.size(), e);
            throw e;
        }
    }

    private void writeUsage(List<UsageEvent> events) {
        List<MapSqlParameterSource> params = new ArrayList<>(events.size());
        List<MapSqlParameterSource> evidenceParams = new ArrayList<>();
        for (UsageEvent e : events) {
            if (e.modelId() == null) {
                // usage_event.model_id is NOT NULL. A single unrepresentable
                // event used to fail the whole batch, which the bus re-enqueues
                // forever — stalling every later usage row behind it. Drop the
                // one event loudly instead; the gateway never emits it for a
                // request that names a model.
                log.warn("Dropping usage event without model_id (id={}, gatewayRequestId={})", e.id(),
                        e.gatewayRequestId());
                continue;
            }
            UsageEvent.ContextAttribution attr = e.attribution();
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
                    .addValue("clientIp", e.clientIp()).addValue("occurredAt", Timestamp.from(e.occurredAt()))
                    .addValue("sessionId", attr != null ? attr.sessionId() : null)
                    .addValue("activityId", attr != null ? attr.activityId() : null)
                    .addValue("claimedProjectId", attr != null ? attr.claimedProjectId() : null)
                    .addValue("resolutionStatus", attr != null ? attr.resolutionStatus() : null)
                    .addValue("claimSource", attr != null ? attr.claimSource() : null)
                    .addValue("claimConfidence", attr != null ? attr.claimConfidence() : null));
            MapSqlParameterSource evidence = evidenceOf(e);
            if (evidence != null) {
                evidenceParams.add(evidence);
            }
        }
        if (params.isEmpty()) {
            return;
        }
        // The conflict target is deliberately unqualified: a replayed event carries
        // the same id, and for rows without an upstream request id (COALESCED hits,
        // where provider_request_id is NULL) the partial unique index does not apply,
        // so the id primary key is the only unique key available to absorb the
        // replay. Naming the partial index here turned a replay into a hard
        // usage_event_pkey violation, which failed the whole batch on every flush.
        jdbc.batchUpdate("""
                INSERT INTO usage_event (id, tenant_id, provider_request_id, virtual_key_id, project_id,
                    provider_product_id, credential_id, model_id, cache_level,
                    input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                    prompt_tokens, completion_tokens, total_tokens, reasoning_tokens,
                    latency_ms, upstream_status_code, cache_key, is_complete, usage_missing,
                    gateway_request_id, client_ip, occurred_at,
                    session_id, activity_id, claimed_project_id, resolution_status, claim_source, claim_confidence)
                VALUES (:id, :tenantId, :providerRequestId, :virtualKeyId, :projectId, :productId, :credentialId,
                    :modelId, :cacheLevel,
                    :inputTokens, :outputTokens, :cacheCreation, :cacheRead,
                    :promptTokens, :completionTokens, :totalTokens, :reasoningTokens,
                    :latencyMs, :upstreamStatusCode, :cacheKey, :isComplete, :usageMissing,
                    :gatewayRequestId, :clientIp, :occurredAt,
                    :sessionId, :activityId, :claimedProjectId, :resolutionStatus, :claimSource, :claimConfidence)
                ON CONFLICT DO NOTHING
                """, params.toArray(new MapSqlParameterSource[0]));
        writeContextEvidence(evidenceParams);
    }

    /**
     * CAA evidence rows (Spec v1.1 §7.2, {@code request_context_evidence}): "why
     * was it attributed this way". Written in the usage transaction, keyed by the
     * usage event id, so a retried flush is a no-op
     * ({@code ON CONFLICT (id) DO NOTHING}) and the rows join.
     *
     * <p>
     * Metadata only — the selector class and its normalized value, never a prompt,
     * path, or body. {@code scope} / {@code observed_at} keep the V55 defaults
     * ({@code turn} / {@code now()}): the gateway cannot observe the client-side
     * scope of an inference.
     * </p>
     */
    private void writeContextEvidence(List<MapSqlParameterSource> params) {
        if (params.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO request_context_evidence (id, tenant_id, request_id, source, value, confidence)
                VALUES (:id, :tenantId, :requestId, :source, :value, :confidence)
                ON CONFLICT (id) DO NOTHING
                """, params.toArray(new MapSqlParameterSource[0]));
    }

    /**
     * Evidence row for one usage event, or null when the ladder used no external
     * selector. {@code source} is the selector class the gateway actually observed,
     * not the client's declared {@code X-Miqro-Claim-Source} (that claim is already
     * kept in {@code usage_event.claim_source}):
     * <ul>
     * <li>{@code RESOLVED_HEADER} → {@code header}, value = the claimed project id
     * the request was validated against;</li>
     * <li>{@code RESOLVED_SUFFIX} → {@code suffix}, value = the tag presented in
     * the key (the binding index is keyed by project tag).</li>
     * </ul>
     * {@code SOLE_BINDING} / {@code POLICY_ROUTED} resolve without any external
     * signal — V55's source vocabulary has no honest value for them, and their
     * explanation is {@code usage_event.resolution_status} itself (Spec §9 C13).
     */
    private static MapSqlParameterSource evidenceOf(UsageEvent e) {
        UsageEvent.ContextAttribution attr = e.attribution();
        if (attr == null) {
            return null;
        }
        String source;
        String value;
        if ("RESOLVED_HEADER".equals(attr.resolutionStatus())) {
            source = "header";
            value = attr.claimedProjectId() != null ? attr.claimedProjectId().toString() : null;
        } else if ("RESOLVED_SUFFIX".equals(attr.resolutionStatus())) {
            source = "suffix";
            value = attr.bindingTag();
        } else {
            return null;
        }
        if (value == null) {
            // value is NOT NULL and carries the whole audit value: never write a
            // half row — and never abort (and endlessly retry) the batch for it.
            log.warn("Dropping context evidence without a value (id={}, status={})", e.id(), attr.resolutionStatus());
            return null;
        }
        return new MapSqlParameterSource().addValue("id", e.id()).addValue("tenantId", e.tenantId())
                .addValue("requestId", e.gatewayRequestId()).addValue("source", source).addValue("value", value)
                .addValue("confidence", attr.claimConfidence() != null ? attr.claimConfidence() : "NONE");
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
                    .addValue("upstreamRequestId", null).addValue("retryCount", 0).addValue("inputTokens", null)
                    .addValue("outputTokens", null).addValue("cacheCreation", null).addValue("cacheRead", null)
                    .addValue("promptTokens", null).addValue("completionTokens", null).addValue("totalTokens", null)
                    .addValue("reasoningTokens", null).addValue("usageMissing", false).addValue("finalizedAt", null));
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
                    :clientCancelled, :partialResponse, :retryCount,
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
                    .addValue("upstreamRequestId", e.upstreamRequestId()).addValue("retryCount", e.retryCount())
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
                    :clientCancelled, :partialResponse, :retryCount,
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
