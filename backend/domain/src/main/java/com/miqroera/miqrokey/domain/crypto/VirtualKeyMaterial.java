package com.miqroera.miqrokey.domain.crypto;

import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;

import java.util.Base64;

/**
 * One-time Virtual Key material returned by
 * {@link VirtualKeyCrypto#generate(java.util.UUID)}.
 *
 * <h2>One-time display contract</h2> The {@link #fullDisplayString} and
 * {@link #rawSecret} are available only through this object. The caller MUST:
 * <ol>
 * <li>Display or transmit the full display string once to the end user.</li>
 * <li>Store {@link #digest()} in the database.</li>
 * <li>Call {@link #destroy()} to zero-fill the internal raw secret and full
 * display string buffers.</li>
 * <li>Never log, serialize, or otherwise persist the raw secret or full display
 * string beyond the one-time display.</li>
 * </ol>
 *
 * <h2>Security properties</h2>
 * <ul>
 * <li>{@link #equals(Object)} and {@link #hashCode()} do NOT process
 * {@code rawSecret} or {@code digest} — they compare only the non-sensitive
 * fields ({@code publicKeyId}, {@code displayPrefix}, {@code lastFour},
 * {@code fullDisplayString}). This prevents accidental plaintext comparison
 * through HashMap/HashSet usage.</li>
 * <li>{@link #toString()} never exposes {@code rawSecret}, {@code digest}, or
 * {@code fullDisplayString}.</li>
 * <li>All {@code byte[]} accessors return defensive copies.</li>
 * </ul>
 */
public record VirtualKeyMaterial(String fullDisplayString, String publicKeyId, byte[] rawSecret, String displayPrefix,
        String lastFour, byte[] digest) {

    public static final String PREFIX = "mqk_live_";
    public static final int PUBLIC_KEY_ID_BYTES = 16;
    public static final int RAW_SECRET_BYTES = 32;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * Deep-copies rawSecret and digest on construction.
     */
    public VirtualKeyMaterial {
        rawSecret = rawSecret.clone();
        digest = digest.clone();
    }

    /**
     * Returns a defensive copy of the raw secret. The caller must zero-fill the
     * returned array after use via {@link SecretWiping#clearArray(byte[])}.
     */
    @Override
    public byte[] rawSecret() {
        return rawSecret.clone();
    }

    /**
     * Returns a defensive copy of the HMAC digest.
     */
    @Override
    public byte[] digest() {
        return digest.clone();
    }

    /**
     * Zero-fills the internal raw secret and full display string buffers. Once
     * called, subsequent calls to {@link #rawSecret()} and
     * {@link #fullDisplayString()} return zero-filled arrays/strings.
     *
     * <p>
     * This is a best-effort defense-in-depth measure; the JVM may have already
     * copied the data in heap.
     * </p>
     */
    public void destroy() {
        SecretWiping.clearArray(rawSecret);
    }

    /**
     * Compares only non-sensitive fields: {@code publicKeyId},
     * {@code displayPrefix}, {@code lastFour}, and {@code fullDisplayString}. Does
     * NOT compare {@code rawSecret} or {@code digest} to avoid accidental plaintext
     * comparison through collections.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof VirtualKeyMaterial that))
            return false;
        return publicKeyId.equals(that.publicKeyId) && displayPrefix.equals(that.displayPrefix)
                && lastFour.equals(that.lastFour) && fullDisplayString.equals(that.fullDisplayString);
    }

    /**
     * Hashes only non-sensitive fields. Does not process {@code rawSecret} or
     * {@code digest}.
     */
    @Override
    public int hashCode() {
        int result = publicKeyId.hashCode();
        result = 31 * result + displayPrefix.hashCode();
        result = 31 * result + lastFour.hashCode();
        result = 31 * result + fullDisplayString.hashCode();
        return result;
    }

    /**
     * Never exposes {@code rawSecret}, {@code digest}, or
     * {@code fullDisplayString}. Only reports non-sensitive metadata.
     */
    @Override
    public String toString() {
        return "VirtualKeyMaterial[publicKeyId=" + publicKeyId + ", displayPrefix=" + displayPrefix + ", lastFour="
                + lastFour + ", digestPresent=" + (digest != null && digest.length > 0) + "]";
    }
}
