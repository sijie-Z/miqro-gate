package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An MCP server registered for agent tool access (P3.4, {@code mcp_services}
 * V20) modeled after the Tencent AI gateway MCP management: online/offline is a
 * manual switch that health checking never overrides; the health probe reports
 * UNKNOWN/HEALTHY/UNHEALTHY driven by fail/recover thresholds.
 *
 * <p>
 * Upstream backend authentication (#320, Tencent raw 03): {@code VISITOR} (no
 * upstream credential) or {@code API_KEY} (the gateway attaches
 * {@code Authorization: Bearer <secret>} upstream). The ciphertext lives in
 * dedicated columns and is deliberately NOT part of this record — the admin
 * read surfaces serialize it directly, and the secret must stay write-only.
 * </p>
 *
 * <p>
 * {@code upstreamTimeoutMs} (I20, Tencent raw 03 "超时时间", default 60000 ms) is
 * the data plane's per-attempt upstream budget and the circuit breaker's
 * slow-call baseline (doc 134859).
 * </p>
 */
public record McpService(UUID id, UUID tenantId, String name, String description, String endpoint, String transport,
        String status, String healthStatus, Instant healthCheckedAt, int consecutiveFailures, int consecutiveSuccesses,
        int checkIntervalSeconds, int checkTimeoutSeconds, int failThreshold, int recoverThreshold, String checkPath,
        long version, UUID createdBy, Instant createdAt, Instant updatedAt, String backendAuthMode,
        Instant backendSecretUpdatedAt, int upstreamTimeoutMs) {

    /** Tencent doc 135906 backend config default ({@code 超时时间} 60000 ms). */
    public static final int DEFAULT_UPSTREAM_TIMEOUT_MS = 60000;
    public static final int MIN_UPSTREAM_TIMEOUT_MS = 1000;
    public static final int MAX_UPSTREAM_TIMEOUT_MS = 600000;

    /** Backwards-compatible constructor: no upstream backend credential. */
    public McpService(UUID id, UUID tenantId, String name, String description, String endpoint, String transport,
            String status, String healthStatus, Instant healthCheckedAt, int consecutiveFailures,
            int consecutiveSuccesses, int checkIntervalSeconds, int checkTimeoutSeconds, int failThreshold,
            int recoverThreshold, String checkPath, long version, UUID createdBy, Instant createdAt,
            Instant updatedAt) {
        this(id, tenantId, name, description, endpoint, transport, status, healthStatus, healthCheckedAt,
                consecutiveFailures, consecutiveSuccesses, checkIntervalSeconds, checkTimeoutSeconds, failThreshold,
                recoverThreshold, checkPath, version, createdBy, createdAt, updatedAt, "VISITOR", null,
                DEFAULT_UPSTREAM_TIMEOUT_MS);
    }

    public McpService {
        if (id == null || tenantId == null || name == null || name.isBlank() || endpoint == null
                || endpoint.isBlank()) {
            throw new IllegalArgumentException("id/tenantId/name/endpoint are required");
        }
        if (transport == null || !(transport.equals("STREAMABLE_HTTP") || transport.equals("SSE"))) {
            throw new IllegalArgumentException("transport must be STREAMABLE_HTTP or SSE");
        }
        if (status == null || !(status.equals("ONLINE") || status.equals("OFFLINE"))) {
            throw new IllegalArgumentException("status must be ONLINE or OFFLINE");
        }
        if (healthStatus == null || !(healthStatus.equals("UNKNOWN") || healthStatus.equals("HEALTHY")
                || healthStatus.equals("UNHEALTHY"))) {
            throw new IllegalArgumentException("healthStatus must be UNKNOWN, HEALTHY or UNHEALTHY");
        }
        if (failThreshold < 1 || recoverThreshold < 1 || checkIntervalSeconds < 1 || checkTimeoutSeconds < 1) {
            throw new IllegalArgumentException("thresholds and check intervals must be positive");
        }
        if (backendAuthMode == null || !(backendAuthMode.equals("VISITOR") || backendAuthMode.equals("API_KEY"))) {
            throw new IllegalArgumentException("backendAuthMode must be VISITOR or API_KEY");
        }
        if (upstreamTimeoutMs < MIN_UPSTREAM_TIMEOUT_MS || upstreamTimeoutMs > MAX_UPSTREAM_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "upstreamTimeoutMs must be " + MIN_UPSTREAM_TIMEOUT_MS + ".." + MAX_UPSTREAM_TIMEOUT_MS);
        }
    }
}
