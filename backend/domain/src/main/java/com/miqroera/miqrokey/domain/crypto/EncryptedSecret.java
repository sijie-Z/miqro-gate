package com.miqroera.miqrokey.domain.crypto;

import java.util.Arrays;

/**
 * Immutable value object holding an AES-256-GCM ciphertext together with its
 * encryption metadata.
 *
 * <p>
 * This record is designed for database storage and retrieval. All
 * {@code byte[]} fields are defensively copied on construction and access.
 * </p>
 *
 * <h2>Database column mapping</h2>
 * <ul>
 * <li>{@code ciphertext} →
 * {@code upstream_credential_versions.encrypted_secret} (bytea)</li>
 * <li>{@code nonce} → {@code upstream_credential_versions.nonce} (bytea)</li>
 * <li>{@code keyVersion} →
 * {@code upstream_credential_versions.encryption_key_version} (varchar)</li>
 * </ul>
 *
 * <h2>Security</h2> {@link #toString()} never exposes ciphertext or nonce bytes
 * — only their lengths and the key version identifier.
 */
public record EncryptedSecret(byte[] ciphertext, byte[] nonce, String keyVersion) {

    /**
     * Deep-copies ciphertext and nonce on construction.
     */
    public EncryptedSecret {
        ciphertext = ciphertext.clone();
        nonce = nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof EncryptedSecret that))
            return false;
        return Arrays.equals(ciphertext, that.ciphertext) && Arrays.equals(nonce, that.nonce)
                && keyVersion.equals(that.keyVersion);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(ciphertext);
        result = 31 * result + Arrays.hashCode(nonce);
        result = 31 * result + keyVersion.hashCode();
        return result;
    }

    /**
     * Does not expose ciphertext or nonce byte content. Only reports the key
     * version and array sizes.
     */
    @Override
    public String toString() {
        return "EncryptedSecret[keyVersion=" + keyVersion + ", ciphertextSize=" + ciphertext.length + ", nonceSize="
                + nonce.length + "]";
    }
}
