package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An MCP server registered for agent tool access (P3.4, {@code mcp_services}
 * V20) modeled after the Tencent AI gateway MCP management: online/offline is a
 * manual switch that health checking never overrides; the health probe reports
 * UNKNOWN/HEALTHY/UNHEALTHY driven by fail/recover thresholds.
 */
public record McpService(UUID id, UUID tenantId, String name, String description, String endpoint, String transport,
        String status, String healthStatus, Instant healthCheckedAt, int consecutiveFailures, int consecutiveSuccesses,
        int checkIntervalSeconds, int checkTimeoutSeconds, int failThreshold, int recoverThreshold, String checkPath,
        long version, UUID createdBy, Instant createdAt, Instant updatedAt) {

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
    }
}
