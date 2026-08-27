package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.UserSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link UserSession} entities.
 *
 * <p>
 * All lookups use the token digest only — raw session tokens never enter this
 * layer. The caller is responsible for hashing the cookie token before calling
 * {@link #findByTokenDigest}.
 * </p>
 */
public interface UserSessionRepository {

    Optional<UserSession> findById(UUID id);

    Optional<UserSession> findByTokenDigest(byte[] tokenDigest);

    List<UserSession> findActiveByUserId(UUID userId, Instant now);

    UserSession insert(UserSession session);

    /**
     * Update last_seen_at and/or revoked_at without optimistic locking. Session
     * touch and revocation are not contention-sensitive operations.
     */
    void touch(UUID id, Instant lastSeenAt);

    void revoke(UUID id, Instant revokedAt);

    void revokeAllByUserId(UUID userId, Instant revokedAt);

    int deleteExpired(Instant now);
}
