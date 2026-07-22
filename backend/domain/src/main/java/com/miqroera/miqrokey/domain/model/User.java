package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A user account within a tenant.
 *
 * <p>
 * password_hash is a bytea column and must never be returned by any API. The
 * secret_digest for virtual keys follows the same rule.
 * </p>
 */
public record User(UUID id, UUID tenantId, String username, String displayName, byte[] passwordHash, String role,
        String status, boolean mustChangePassword, int failedLoginCount, Instant lockedUntil, Instant lastLoginAt,
        long version, Instant createdAt, Instant updatedAt) {
    public static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final String ROLE_USER = "USER";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_LOCKED = "LOCKED";
}
