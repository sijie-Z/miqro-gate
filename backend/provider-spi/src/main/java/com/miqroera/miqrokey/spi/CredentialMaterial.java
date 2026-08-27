package com.miqroera.miqrokey.spi;

import java.util.Arrays;
import java.util.UUID;

/**
 * A decrypted upstream credential version, in memory only, ready for header
 * injection. The plaintext bytes are wiped by {@link #destroy()} once the
 * request is sent; {@code toString()} never reveals the secret.
 *
 * <p>
 * Callers must always wrap the request exchange in a {@code try/finally {
 * material.destroy(); }}.
 *
 * @param upstreamCredentialVersionId
 *            credential version these bytes belong to
 * @param secret
 *            plaintext secret bytes (UTF-8)
 */
public record CredentialMaterial(UUID upstreamCredentialVersionId, byte[] secret) {

    public CredentialMaterial {
        if (upstreamCredentialVersionId == null) {
            throw new IllegalArgumentException("upstreamCredentialVersionId must not be null");
        }
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret must not be null or empty");
        }
        secret = Arrays.copyOf(secret, secret.length);
    }

    /** Clears the secret bytes in place. Safe to call multiple times. */
    public void destroy() {
        Arrays.fill(secret, (byte) 0);
    }

    @Override
    public String toString() {
        return "CredentialMaterial{upstreamCredentialVersionId=" + upstreamCredentialVersionId + ", secret=[REDACTED]}";
    }
}
