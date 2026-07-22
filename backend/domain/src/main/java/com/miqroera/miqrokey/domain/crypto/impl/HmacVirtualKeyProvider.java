package com.miqroera.miqrokey.domain.crypto.impl;

import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyMaterial;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public final class HmacVirtualKeyProvider implements VirtualKeyCrypto {

    static final String HMAC_ALGORITHM = "HmacSHA256";
    static final int PUBLIC_KEY_ID_BYTES = 16;
    static final int RAW_SECRET_BYTES = 32;
    private static final String PREFIX = "mqk_live_";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final KeyRing hmacKeyRing;
    private final SecureRandom secureRandom;

    public HmacVirtualKeyProvider(KeyRing hmacKeyRing) {
        this(hmacKeyRing, new SecureRandom());
    }

    HmacVirtualKeyProvider(KeyRing hmacKeyRing, SecureRandom secureRandom) {
        this.hmacKeyRing = hmacKeyRing;
        this.secureRandom = secureRandom;
    }

    @Override
    public VirtualKeyMaterial generate() {
        byte[] publicKeyBytes = new byte[PUBLIC_KEY_ID_BYTES];
        secureRandom.nextBytes(publicKeyBytes);
        String publicKeyId = URL_ENCODER.encodeToString(publicKeyBytes);

        byte[] rawSecret = new byte[RAW_SECRET_BYTES];
        secureRandom.nextBytes(rawSecret);

        String displayPrefix = publicKeyId.substring(0, Math.min(8, publicKeyId.length()));

        String fullDisplayString = PREFIX + publicKeyId + "_" + URL_ENCODER.encodeToString(rawSecret);
        String lastFour = fullDisplayString.substring(fullDisplayString.length() - 4);

        byte[] digest = hmacSha256(hmacKeyRing.activeKey(), buildMessage(publicKeyId, rawSecret));

        try {
            return new VirtualKeyMaterial(fullDisplayString, publicKeyId, rawSecret, displayPrefix, lastFour, digest);
        } finally {
            clearArray(rawSecret);
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
        if (expectedDigest == null || expectedDigest.length != 32) {
            return false;
        }
        byte[] message = buildMessage(publicKeyId, rawSecret);
        // Try all known HMAC key versions for verification
        for (String version : hmacKeyRing.knownVersions()) {
            byte[] key = hmacKeyRing.keyForVersion(version);
            if (key == null)
                continue;
            try {
                byte[] computed = hmacSha256(key, message);
                if (MessageDigest.isEqual(computed, expectedDigest)) {
                    return true;
                }
            } finally {
                clearArray(key);
            }
        }
        return false;
    }

    @Override
    public byte[] computeDigest(String publicKeyId, byte[] rawSecret, UUID tenantId) {
        return hmacSha256(hmacKeyRing.activeKey(), buildMessage(publicKeyId, rawSecret));
    }

    @Override
    public String activeKeyVersion() {
        return hmacKeyRing.activeVersion();
    }

    static byte[] buildMessage(String publicKeyId, byte[] rawSecret) {
        byte[] pkIdBytes = publicKeyId.getBytes(StandardCharsets.UTF_8);
        if (rawSecret != null) {
            byte[] message = new byte[pkIdBytes.length + rawSecret.length];
            System.arraycopy(pkIdBytes, 0, message, 0, pkIdBytes.length);
            System.arraycopy(rawSecret, 0, message, pkIdBytes.length, rawSecret.length);
            return message;
        }
        return pkIdBytes;
    }

    static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new CryptoOperationException("HMAC-SHA-256 computation failed", e);
        }
    }

    static void clearArray(byte[] array) {
        if (array != null) {
            java.util.Arrays.fill(array, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "HmacVirtualKeyProvider[activeVersion=" + hmacKeyRing.activeVersion() + "]";
    }
}
