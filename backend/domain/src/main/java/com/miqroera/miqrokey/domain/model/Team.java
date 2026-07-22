package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A team is a grouping mechanism for users. It does not carry authorization or
 * cost attribution — those belong to projects and grants.
 */
public record Team(UUID id, UUID tenantId, String name, String description, String status, long version,
        Instant createdAt, Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
}
