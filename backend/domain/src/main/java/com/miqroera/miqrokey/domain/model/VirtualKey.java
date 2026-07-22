package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A Virtual Key that end users present to the Gateway. One key is fixedly bound
 * to one user, one project, one provider product (via grant), one upstream
 * credential, and one purpose.
 *
 * <p>
 * No plaintext key is stored. Only the public_key_id (for lookup) and
 * secret_digest (HMAC-SHA-256) are persisted. The full key is displayed exactly
 * once at creation time.
 * </p>
 */
public record VirtualKey(UUID id, UUID tenantId, String publicKeyId, byte[] secretDigest, String displayPrefix,
        String lastFour, UUID userId, UUID projectId, UUID grantId, UUID upstreamCredentialId,
        VirtualKeyPurpose purpose, String name, VirtualKeyStatus status, Instant createdAt, Instant lastUsedAt,
        Instant revokedAt, UUID replacedByKeyId, long version) {

    public VirtualKey {
        secretDigest = secretDigest.clone();
    }

    @Override
    public byte[] secretDigest() {
        return secretDigest.clone();
    }
}
