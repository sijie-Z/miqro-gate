package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable credential version (api-contract §5): status, encryption key
 * version, and a short fingerprint prefix. Encrypted secret bytes are never
 * exposed.
 */
public record CredentialVersionView(UUID id, String status, String encryptionKeyVersion, String fingerprintPrefix,
        Instant validFrom, Instant retiredAt, Instant createdAt) {
}
