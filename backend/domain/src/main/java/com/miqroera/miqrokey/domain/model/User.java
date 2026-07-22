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
public record User(UUID id, UUID tenantId, String username, String displayName, byte[] passwordHash, UserRole role,
        UserStatus status, boolean mustChangePassword, int failedLoginCount, Instant lockedUntil, Instant lastLoginAt,
        long version, Instant createdAt, Instant updatedAt) {

    public User {
        passwordHash = passwordHash.clone();
    }

    @Override
    public byte[] passwordHash() {
        return passwordHash.clone();
    }
}
