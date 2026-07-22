package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable version of an upstream credential. This is the only table that
 * contains the encrypted secret (AES-256-GCM ciphertext + nonce).
 */
public record UpstreamCredentialVersion(UUID id, UUID credentialId, byte[] encryptedSecret, byte[] nonce,
        String encryptionKeyVersion, byte[] secretFingerprint, String status, Instant validFrom, Instant retiredAt,
        Instant createdAt) {
    public static final String STATUS_PENDING_VALIDATION = "PENDING_VALIDATION";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DRAINING = "DRAINING";
    public static final String STATUS_RETIRED = "RETIRED";
    public static final String STATUS_INVALID = "INVALID";
}
