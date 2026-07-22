package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant (customer organization) in the MiQroKey Gateway.
 *
 * <p>
 * First version supports a single seeded tenant. The tenant_id column is
 * present in all tenant-scoped tables from day one to avoid a painful
 * multi-tenant migration later.
 * </p>
 */
public record Tenant(UUID id, String code, String name, TenantStatus status, long version, Instant createdAt,
        Instant updatedAt) {
}
