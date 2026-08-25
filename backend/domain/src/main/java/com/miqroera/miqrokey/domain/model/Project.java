package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A project is the core entity for authorization, usage, and cost attribution.
 *
 * <p>
 * {@code projectTag} is the routing label embedded in Virtual Keys
 * ({@code mqk_live_...<secret>.<projectTag>}, ADR-0008). It is nullable until
 * an administrator assigns it; a project without a tag cannot host routable
 * keys. The tag is unique per tenant and restricted to
 * {@code [A-Za-z0-9_-]{1,64}}.
 * </p>
 */
public record Project(UUID id, UUID tenantId, String code, String name, String description, String costCenter,
        ProjectStatus status, String projectTag, long version, Instant createdAt, Instant updatedAt) {
}
