package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A project is the core entity for authorization, usage, and cost attribution.
 */
public record Project(UUID id, UUID tenantId, String code, String name, String description, String costCenter,
        String status, long version, Instant createdAt, Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
}
