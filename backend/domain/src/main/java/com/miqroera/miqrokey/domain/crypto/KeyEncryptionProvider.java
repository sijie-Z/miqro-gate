package com.miqroera.miqrokey.domain.crypto;

import java.util.UUID;

/**
 * Authenticated encryption for upstream credential secrets.
 *
 * <h2>AAD binding</h2> Every ciphertext is bound to a specific tenant,
 * credential, and encryption key version via Additional Authenticated Data
 * (AAD). Decryption with a mismatched tenant, credential, or version produces
 * an AEAD tag mismatch.
 *
 * <h2>Array ownership</h2> The caller owns the plaintext passed to
 * {@link #encrypt} and the byte[] returned by {@link #decrypt}. The callers are
 * responsible for zero-filling decrypted plaintext via
 * {@link com.miqroera.miqrokey.domain.crypto.impl.SecretWiping#clearArray(byte[])}
 * after use.
 *
 * <h2>Key rotation</h2> {@link #encrypt} always uses the active key version.
 * {@link #decrypt} looks up the version stored in
 * {@link EncryptedSecret#keyVersion()}, so old ciphertexts remain readable
 * after rotation. Use {@link #reEncrypt} to re-encrypt a ciphertext with the
 * current active version.
 *
 * <h2>Nonce</h2> Every encryption produces a fresh, independent random nonce.
 * The nonce is stored alongside the ciphertext in {@link EncryptedSecret}.
 */
public interface KeyEncryptionProvider {

    /**
     * Encrypts plaintext with the active key version. The returned
     * {@link EncryptedSecret} carries the ciphertext, nonce, and key version
     * identifier.
     *
     * @param plaintext
     *            the secret to encrypt
     * @param tenantId
     *            bound tenant (part of AAD)
     * @param credentialId
     *            bound credential (part of AAD)
     * @return encapsulated ciphertext with metadata
     */
    EncryptedSecret encrypt(byte[] plaintext, UUID tenantId, UUID credentialId);

    /**
     * Decrypts an {@link EncryptedSecret}. The key version is read from the secret;
     * the tenant and credential must match the original AAD.
     *
     * <p>
     * The caller must zero-fill the returned plaintext after use.
     * </p>
     *
     * @param encrypted
     *            the ciphertext to decrypt
     * @param tenantId
     *            expected tenant (must match original AAD)
     * @param credentialId
     *            expected credential (must match original AAD)
     * @return the decrypted plaintext (caller-owned, must be cleared)
     * @throws com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException
     *             if decryption or AEAD validation fails
     */
    byte[] decrypt(EncryptedSecret encrypted, UUID tenantId, UUID credentialId);

    /**
     * Convenience method that decrypts with the original key version and
     * immediately re-encrypts with the current active version.
     *
     * @param encrypted
     *            the ciphertext to re-encrypt
     * @param tenantId
     *            bound tenant
     * @param credentialId
     *            bound credential
     * @return new ciphertext under the active key version
     */
    EncryptedSecret reEncrypt(EncryptedSecret encrypted, UUID tenantId, UUID credentialId);

    /**
     * Returns the identifier of the key version used for new encryption.
     */
    String activeKeyVersion();
}
