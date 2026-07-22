package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A logical credential slot. Does NOT store the plaintext or ciphertext of the
 * upstream API key — that lives in UpstreamCredentialVersion.
 */
public record UpstreamCredential(UUID id, UUID subscriptionId, UUID seatId, String credentialName,
        byte[] secretFingerprint, String status, UUID activeVersionId, Instant lastValidatedAt,
        String lastValidationError, long version, Instant createdAt, Instant updatedAt) {
    public static final String STATUS_PENDING_VALIDATION = "PENDING_VALIDATION";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DRAINING = "DRAINING";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_INVALID = "INVALID";
}
