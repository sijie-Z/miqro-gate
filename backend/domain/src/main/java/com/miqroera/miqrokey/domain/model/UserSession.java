package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A server-side authentication session for the Control Plane portal.
 *
 * <p>
 * Only SHA-256 digests of the session token and CSRF secret are stored. Raw
 * session tokens and CSRF secrets never touch the database, logs, or audit
 * records. The raw session token is set as an HttpOnly/Secure/SameSite cookie
 * on the client.
 * </p>
 *
 * <p>
 * Sessions can be individually revoked or bulk-revoked by user (e.g., on
 * password change). Expired sessions are cleaned up periodically.
 * </p>
 */
public record UserSession(UUID id, UUID tenantId, UUID userId, byte[] tokenDigest, byte[] csrfDigest, Instant createdAt,
        Instant lastSeenAt, Instant expiresAt, Instant revokedAt) {

    public UserSession {
        tokenDigest = tokenDigest.clone();
        csrfDigest = csrfDigest.clone();
        if (revokedAt != null && revokedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("revokedAt must be after createdAt");
        }
        if (expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    /**
     * Returns true if this session is currently valid (not revoked, not expired).
     */
    public boolean isValid(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    @Override
    public byte[] tokenDigest() {
        return tokenDigest.clone();
    }

    @Override
    public byte[] csrfDigest() {
        return csrfDigest.clone();
    }
}
