package com.miqroera.miqrokey.domain.vkey;

/**
 * Result of parsing a presented Virtual Key string.
 *
 * <p>
 * Contains only the non-sensitive routing fields plus the raw secret bytes; the
 * raw secret must be zero-filled by the caller after use (see
 * {@link com.miqroera.miqrokey.domain.crypto.impl.SecretWiping#clearArray}).
 * </p>
 *
 * <h2>Invariants</h2> A parsed key has a valid base64url public key ID (16
 * bytes), a valid base64url secret (32 bytes), and a valid label. Invalid keys
 * produce {@code invalid()} rather than exceptions so the gateway can answer
 * with the uniform {@code VIRTUAL_KEY_INVALID} error without distinguishing
 * failure causes.
 */
public record VirtualKeyParseResult(String publicKeyId, byte[] rawSecret, String projectTag, boolean valid) {

    /**
     * Deep-copies the raw secret on construction.
     */
    public VirtualKeyParseResult {
        if (rawSecret != null) {
            rawSecret = rawSecret.clone();
        }
    }

    @Override
    public byte[] rawSecret() {
        return rawSecret.clone();
    }

    public static VirtualKeyParseResult invalid() {
        return new VirtualKeyParseResult(null, null, null, false);
    }

    /**
     * Does not expose the raw secret.
     */
    @Override
    public String toString() {
        return "VirtualKeyParseResult[valid=" + valid + ", publicKeyId=" + publicKeyId + ", projectTag=" + projectTag
                + ", rawSecretPresent=" + (rawSecret != null && rawSecret.length > 0) + "]";
    }

    /**
     * Compares only non-sensitive fields ({@code publicKeyId}, {@code projectTag},
     * {@code valid}). Never compares {@code rawSecret} to avoid accidental
     * plaintext comparison through collections.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof VirtualKeyParseResult that))
            return false;
        return valid == that.valid && java.util.Objects.equals(publicKeyId, that.publicKeyId)
                && java.util.Objects.equals(projectTag, that.projectTag);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(publicKeyId, projectTag, valid);
    }
}
