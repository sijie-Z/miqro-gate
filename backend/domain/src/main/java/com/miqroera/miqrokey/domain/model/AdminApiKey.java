package com.miqroera.miqrokey.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Open admin API key (ADR-0015): a machine credential for the programmable
 * admin surface. Only the SHA-256 digest is persisted; the plaintext is shown
 * exactly once at issue time. Expiry and revocation are supported; revocation
 * is immediate (checked per request against the store).
 *
 * <p>
 * {@code capabilities} is the F60 batch 3 scope: {@code null} means full access
 * (backwards compatible), an empty list denies everything, otherwise the key is
 * limited to the listed capability codes (see {@code AdminApiKeyCapabilities}).
 * </p>
 */
public record AdminApiKey(UUID id, UUID tenantId, String name, byte[] keyDigest, String keyPrefix, UUID createdBy,
        Instant expiresAt, Instant revokedAt, Instant createdAt, List<String> capabilities) {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Distinct from the external-consumer prefix {@code mqk_api_}. */
    private static final String PREFIX = "mqk_admin_";

    /** One-time issue result: plaintext plus the stored digest/prefix. */
    public record GeneratedKey(String plaintext, byte[] digest, String prefix) {
    }

    /** Active now (issued, not revoked, not past expiry). */
    public boolean active() {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(Instant.now());
    }

    /** Scope check: no scope means full access; otherwise membership decides. */
    public boolean allows(String capability) {
        return capabilities == null || capabilities.contains(capability);
    }

    public static GeneratedKey generateKey() {
        byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        String hex = toHex(raw);
        String prefix = PREFIX + hex.substring(0, 8);
        String plaintext = prefix + hex.substring(8);
        return new GeneratedKey(plaintext, sha256(plaintext), prefix);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
