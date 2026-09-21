package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An internal service registered for gateway integration (P3.2,
 * {@code services} V18): platform components, MCP endpoints or other internal
 * services. Base URLs are admin-configured; the https/no-userinfo rule mirrors
 * the upstream target validator.
 *
 * <p>
 * Runtime governance (#326, mirror of the mcp_services V20 model):
 * {@code ACTIVE} services are health-probed on their own interval (2xx on
 * {@code baseUrl + checkPath} counts healthy; fail/recover thresholds drive
 * {@code UNKNOWN/HEALTHY/UNHEALTHY}); {@code DISABLED} rows are never probed so
 * a manual stop is never overridden.
 * </p>
 */
public record InternalService(UUID id, UUID tenantId, String name, String kind, String description, String baseUrl,
        String status, long version, UUID createdBy, Instant createdAt, Instant updatedAt, String healthStatus,
        Instant healthCheckedAt, int consecutiveFailures, int consecutiveSuccesses, int checkIntervalSeconds,
        int checkTimeoutSeconds, int failThreshold, int recoverThreshold, String checkPath) {

    /** Backwards-compatible constructor: fresh service, never probed yet. */
    public InternalService(UUID id, UUID tenantId, String name, String kind, String description, String baseUrl,
            String status, long version, UUID createdBy, Instant createdAt, Instant updatedAt) {
        this(id, tenantId, name, kind, description, baseUrl, status, version, createdBy, createdAt, updatedAt,
                "UNKNOWN", null, 0, 0, 30, 5, 3, 1, "/health");
    }

    public InternalService {
        if (id == null || tenantId == null || name == null || name.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("id/tenantId/name/baseUrl are required");
        }
        if (kind == null || !(kind.equals("HTTP") || kind.equals("MCP") || kind.equals("OTHER"))) {
            throw new IllegalArgumentException("kind must be HTTP, MCP or OTHER");
        }
        if (status == null || !(status.equals("ACTIVE") || status.equals("DISABLED"))) {
            throw new IllegalArgumentException("status must be ACTIVE or DISABLED");
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
