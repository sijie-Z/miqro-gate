package com.miqroera.miqrokey.domain.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 fingerprint of an upstream credential secret.
 *
 * <p>
 * Stored in place of the secret so the control plane can detect whether a
 * candidate secret equals the current version and render a masked prefix
 * without ever persisting, logging, or returning the plaintext. SHA-256 is
 * one-way; a fingerprint never reveals the secret.
 * </p>
 */
public final class CredentialFingerprint {

    private CredentialFingerprint() {
    }

    /** SHA-256 digest of the UTF-8 encoding of {@code secret}. */
    public static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Lowercase hex of the first {@code prefixLength} bytes, for display only. The
     * full fingerprint is stored; only this short prefix ever leaves the control
     * plane.
     */
    public static String hexPrefix(byte[] fingerprint, int prefixLength) {
        if (fingerprint == null) {
            return "";
        }
        int len = Math.min(prefixLength, fingerprint.length);
        return HexFormat.of().formatHex(fingerprint, 0, len);
    }
}
