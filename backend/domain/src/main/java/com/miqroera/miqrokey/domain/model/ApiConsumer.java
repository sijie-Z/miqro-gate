package com.miqroera.miqrokey.domain.model;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * External-system API consumer (ADR-0010): an API identity for the platform to
 * query billing data through {@code /api/v1/billing/**}. The API key plaintext
 * is shown once at creation; only a SHA-256 digest is persisted, mirroring the
 * Virtual Key secret hygiene rules.
 *
 * @param keyDigest
 *            SHA-256 over the full {@code mqk_api_…} key bytes
 * @param keyPrefix
 *            first 8 hex chars of the key (display only)
 */
public record ApiConsumer(UUID id, UUID tenantId, String name, byte[] keyDigest, String keyPrefix, String status,
        long version, Instant createdAt, Instant updatedAt) {

    public ApiConsumer {
        if (id == null || tenantId == null) {
            throw new IllegalArgumentException("id and tenantId are required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (keyDigest == null || keyDigest.length != 32) {
            throw new IllegalArgumentException("keyDigest must be SHA-256");
        }
        keyDigest = keyDigest.clone();
    }

    @Override
    public byte[] keyDigest() {
        return keyDigest.clone();
    }

    /** Generates a fresh API key and its digest. */
    public static GeneratedKey generateKey() {
        byte[] random = new byte[16];
        new java.security.SecureRandom().nextBytes(random);
        String secret = HexFormat.of().formatHex(random);
        String prefix = secret.substring(0, 8);
        String plaintext = "mqk_api_" + prefix + "_" + secret;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new GeneratedKey(plaintext, digest, prefix);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Validates a presented key against this consumer's digest. */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return MessageDigest.isEqual(digest, keyDigest());
        } catch (Exception e) {
            return false;
        }
    }

    public record GeneratedKey(String plaintext, byte[] digest, String prefix) {
        public GeneratedKey {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }
}
