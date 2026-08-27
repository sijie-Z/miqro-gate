package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A logical credential slot. Does NOT store the plaintext or ciphertext of the
 * upstream API key — that lives in UpstreamCredentialVersion.
 */
public record UpstreamCredential(UUID id, UUID tenantId, UUID subscriptionId, UUID seatId, String credentialName,
        byte[] secretFingerprint, CredentialStatus status, UUID activeVersionId, Instant lastValidatedAt,
        String lastValidationError, long version, Instant createdAt, Instant updatedAt) {

    public UpstreamCredential {
        if (secretFingerprint != null) {
            secretFingerprint = secretFingerprint.clone();
        }
    }

    @Override
    public byte[] secretFingerprint() {
        return secretFingerprint != null ? secretFingerprint.clone() : null;
    }
}
