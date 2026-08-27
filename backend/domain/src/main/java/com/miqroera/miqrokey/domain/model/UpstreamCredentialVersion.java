package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable version of an upstream credential. This is the only table that
 * contains the encrypted secret (AES-256-GCM ciphertext + nonce).
 */
public record UpstreamCredentialVersion(UUID id, UUID tenantId, UUID credentialId, byte[] encryptedSecret, byte[] nonce,
        String encryptionKeyVersion, byte[] secretFingerprint, CredentialVersionStatus status, Instant validFrom,
        Instant retiredAt, Instant createdAt) {

    public UpstreamCredentialVersion {
        encryptedSecret = encryptedSecret.clone();
        nonce = nonce.clone();
        secretFingerprint = secretFingerprint.clone();
    }

    @Override
    public byte[] encryptedSecret() {
        return encryptedSecret.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] secretFingerprint() {
        return secretFingerprint.clone();
    }
}
