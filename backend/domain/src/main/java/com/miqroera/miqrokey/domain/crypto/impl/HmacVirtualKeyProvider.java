package com.miqroera.miqrokey.domain.crypto.impl;

import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyMaterial;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * HMAC-SHA-256 Virtual Key provider.
 *
 * <h2>Virtual Key format</h2>
 * {@code mqk_live_<base64url-publicKeyId>_<base64url-secret>}
 *
 * <h2>Domain separation</h2> The HMAC message = publicKeyId (UTF-8) ||
 * rawSecret (32 bytes) || tenantId (16 bytes, big-endian). This binds every
 * Virtual Key digest to the owning tenant. A key created for tenant A will
 * never validate against tenant B.
 *
 * <h2>Constant-time multi-version validation</h2> {@link #validateConstantTime}
 * iterates ALL known HMAC key versions without early exit and accumulates the
 * match result. This prevents timing side-channels from revealing which version
 * matched or whether any version matched at all.
 *
 * <h2>Key length</h2> HMAC-SHA-256 accepts arbitrarily long keys. We validate a
 * minimum of 32 bytes (256 bits) as a defense-in-depth constraint.
 */
public final class HmacVirtualKeyProvider implements VirtualKeyCrypto {

    static final String HMAC_ALGORITHM = "HmacSHA256";
    static final int PUBLIC_KEY_ID_BYTES = 16;
    static final int RAW_SECRET_BYTES = 32;
    static final int MIN_HMAC_KEY_BYTES = 32; // 256-bit minimum
    static final int EXPECTED_DIGEST_LENGTH = 32; // SHA-256 output

    private static final String PREFIX = "mqk_live_";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final KeyRing hmacKeyRing;
    private final SecureRandom secureRandom;

    /**
     * Creates a provider backed by the given HMAC key ring. Every key in the ring
     * is validated for minimum length (32 bytes). Key clones obtained during
     * validation are zero-filled immediately.
     *
     * @param hmacKeyRing
     *            the HMAC key ring (keys are deep-copied by KeyRing)
     * @throws CryptoOperationException
     *             if any key is shorter than 32 bytes
     */
    public HmacVirtualKeyProvider(KeyRing hmacKeyRing) {
        this(hmacKeyRing, new SecureRandom());
    }

    HmacVirtualKeyProvider(KeyRing hmacKeyRing, SecureRandom secureRandom) {
        this.hmacKeyRing = hmacKeyRing;
        this.secureRandom = secureRandom;
        for (String version : hmacKeyRing.knownVersions()) {
            byte[] key = hmacKeyRing.keyForVersion(version);
            try {
                if (key.length < MIN_HMAC_KEY_BYTES) {
                    throw new CryptoOperationException("CRYPTO_KEY_003",
                            "HMAC key must be at least " + MIN_HMAC_KEY_BYTES + " bytes, got " + key.length);
                }
            } finally {
                SecretWiping.clearArray(key);
            }
        }
    }

    @Override
    public VirtualKeyMaterial generate(UUID tenantId) {
        byte[] publicKeyBytes = new byte[PUBLIC_KEY_ID_BYTES];
        secureRandom.nextBytes(publicKeyBytes);
        String publicKeyId = URL_ENCODER.encodeToString(publicKeyBytes);

        byte[] rawSecret = new byte[RAW_SECRET_BYTES];
        secureRandom.nextBytes(rawSecret);

        String displayPrefix = publicKeyId.substring(0, Math.min(8, publicKeyId.length()));
        String fullDisplayString = PREFIX + publicKeyId + "_" + URL_ENCODER.encodeToString(rawSecret);
        String lastFour = fullDisplayString.substring(fullDisplayString.length() - 4);

        byte[] hmacKey = hmacKeyRing.activeKey();
        byte[] message = buildMessage(publicKeyId, rawSecret, tenantId);
        try {
            byte[] digest = hmacSha256(hmacKey, message);
            return new VirtualKeyMaterial(fullDisplayString, publicKeyId, rawSecret, displayPrefix, lastFour, digest);
        } finally {
            SecretWiping.clearArray(hmacKey);
            SecretWiping.clearArray(message);
            SecretWiping.clearArray(rawSecret);
        }
    }

    @Override
    public boolean validateConstantTime(String publicKeyId, byte[] rawSecret, byte[] expectedDigest, UUID tenantId) {
        if (publicKeyId == null || publicKeyId.isEmpty()) {
            return false;
        }
        if (rawSecret == null) {
            return false;
        }
        if (expectedDigest == null || expectedDigest.length != EXPECTED_DIGEST_LENGTH) {
            return false;
        }

        byte[] message = buildMessage(publicKeyId, rawSecret, tenantId);
        try {
            // Traverse ALL versions, accumulating result — no early exit.
            boolean matched = false;
            for (String version : hmacKeyRing.knownVersions()) {
                byte[] key = hmacKeyRing.keyForVersion(version);
                if (key == null) {
                    continue;
                }
                byte[] computed = null;
                try {
                    computed = hmacSha256(key, message);
                    // Constant-time OR accumulation
                    if (MessageDigest.isEqual(computed, expectedDigest)) {
                        matched = true;
                    }
                } finally {
                    SecretWiping.clearArray(key);
                    SecretWiping.clearArray(computed);
                }
            }
            return matched;
        } finally {
            SecretWiping.clearArray(message);
        }
    }

    @Override
    public byte[] computeDigest(String publicKeyId, byte[] rawSecret, UUID tenantId) {
        byte[] hmacKey = hmacKeyRing.activeKey();
        byte[] message = buildMessage(publicKeyId, rawSecret, tenantId);
        try {
            return hmacSha256(hmacKey, message);
        } finally {
            SecretWiping.clearArray(hmacKey);
            SecretWiping.clearArray(message);
        }
    }

    @Override
    public String activeKeyVersion() {
        return hmacKeyRing.activeVersion();
    }

    /**
     * Builds the HMAC message for domain separation. Format: publicKeyId (UTF-8) ||
     * rawSecret (32 bytes) || tenantId (16 bytes, big-endian).
     */
    static byte[] buildMessage(String publicKeyId, byte[] rawSecret, UUID tenantId) {
        byte[] pkIdBytes = publicKeyId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(pkIdBytes.length + RAW_SECRET_BYTES + 16);
        bb.put(pkIdBytes);
        if (rawSecret != null) {
            bb.put(rawSecret);
        }
        bb.putLong(tenantId.getMostSignificantBits());
        bb.putLong(tenantId.getLeastSignificantBits());
        return bb.array();
    }

    static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new CryptoOperationException("CRYPTO_HMAC_001", "HMAC-SHA-256 computation failed", e);
        }
    }

    /**
     * Does not expose key material.
     */
    @Override
    public String toString() {
        return "HmacVirtualKeyProvider[activeVersion=" + hmacKeyRing.activeVersion() + "]";
    }
}
