package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An internal service registered for gateway integration (P3.2,
 * {@code services} V18): platform components, MCP endpoints or other internal
 * services. Base URLs are admin-configured; the https/no-userinfo rule mirrors
 * the upstream target validator.
 */
public record InternalService(UUID id, UUID tenantId, String name, String kind, String description, String baseUrl,
        String status, long version, UUID createdBy, Instant createdAt, Instant updatedAt) {

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
    }
}
