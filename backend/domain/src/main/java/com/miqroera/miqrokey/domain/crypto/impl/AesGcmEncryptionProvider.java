package com.miqroera.miqrokey.domain.crypto.impl;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.KeyRing;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * AES-256-GCM implementation of {@link KeyEncryptionProvider}.
 *
 * <h2>Algorithm</h2> AES-256 in Galois/Counter Mode with 96-bit random nonce
 * and 128-bit authentication tag. Every encryption produces a unique,
 * independent nonce from {@link SecureRandom}.
 *
 * <h2>Additional Authenticated Data (AAD)</h2> AAD = tenantId (16 bytes) +
 * credentialId (16 bytes) + keyVersion (UTF-8). This binds every ciphertext to
 * a specific tenant, credential, and encryption key version. Decryption with
 * wrong AAD triggers an AEAD tag mismatch.
 *
 * <h2>Key lifecycle</h2> {@link #encrypt} uses the active key version.
 * {@link #decrypt} looks up the version stored in
 * {@link EncryptedSecret#keyVersion()}. The {@link #reEncrypt} convenience
 * decrypts with the original version then re-encrypts with the active version.
 *
 * <h2>Array ownership</h2> Callers own the plaintext they pass into
 * {@link #encrypt} and the byte[] returned by {@link #decrypt}. The provider
 * clears internal key clones promptly; callers must zero-fill decrypted
 * plaintext after use.
 */
public final class AesGcmEncryptionProvider implements KeyEncryptionProvider {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits — recommended for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128-bit authentication tag
    private static final int AES_KEY_LENGTH = 32; // 256 bits

    private final KeyRing keyRing;
    private final SecureRandom secureRandom;

    /**
     * Creates a provider backed by the given key ring. Every key in the ring is
     * validated for correct AES-256 length (32 bytes). Key clones obtained during
     * validation are zero-filled immediately.
     *
     * @param keyRing
     *            the key ring (keys are deep-copied by KeyRing)
     * @throws CryptoOperationException
     *             if any key has wrong length
     */
    public AesGcmEncryptionProvider(KeyRing keyRing) {
        this(keyRing, new SecureRandom());
    }

    AesGcmEncryptionProvider(KeyRing keyRing, SecureRandom secureRandom) {
        this.keyRing = keyRing;
        this.secureRandom = secureRandom;
        for (String version : keyRing.knownVersions()) {
            byte[] key = keyRing.keyForVersion(version);
            try {
                if (key.length != AES_KEY_LENGTH) {
                    throw new CryptoOperationException("CRYPTO_KEY_001",
                            "AES key must be " + AES_KEY_LENGTH + " bytes, got " + key.length);
                }
            } finally {
                SecretWiping.clearArray(key);
            }
        }
    }

    @Override
    public EncryptedSecret encrypt(byte[] plaintext, UUID tenantId, UUID credentialId) {
        byte[] key = keyRing.activeKey();
        try {
            byte[] nonce = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(nonce);

            byte[] aad = buildAad(tenantId, credentialId, keyRing.activeVersion());
            byte[] ciphertext = aesGcmEncrypt(key, nonce, plaintext, aad);

            return new EncryptedSecret(ciphertext, nonce, keyRing.activeVersion());
        } finally {
            SecretWiping.clearArray(key);
        }
    }

    @Override
    public byte[] decrypt(EncryptedSecret encrypted, UUID tenantId, UUID credentialId) {
        byte[] key = keyRing.keyForVersion(encrypted.keyVersion());
        if (key == null) {
            throw new CryptoOperationException("CRYPTO_KEY_002",
                    "Unknown encryption key version. Known versions: " + keyRing.knownVersions().size());
        }
        try {
            byte[] aad = buildAad(tenantId, credentialId, encrypted.keyVersion());
            return aesGcmDecrypt(key, encrypted.nonce(), encrypted.ciphertext(), aad);
        } finally {
            SecretWiping.clearArray(key);
        }
    }

    @Override
    public EncryptedSecret reEncrypt(EncryptedSecret encrypted, UUID tenantId, UUID credentialId) {
        byte[] plaintext = decrypt(encrypted, tenantId, credentialId);
        try {
            return encrypt(plaintext, tenantId, credentialId);
        } finally {
            SecretWiping.clearArray(plaintext);
        }
    }

    @Override
    public String activeKeyVersion() {
        return keyRing.activeVersion();
    }

    private static byte[] buildAad(UUID tenantId, UUID credentialId, String keyVersion) {
        byte[] tenantBytes = uuidToBytes(tenantId);
        byte[] credBytes = uuidToBytes(credentialId);
        byte[] versionBytes = keyVersion.getBytes(StandardCharsets.UTF_8);
        // AAD = tenantId (16) + credentialId (16) + keyVersion (variable)
        return ByteBuffer.allocate(tenantBytes.length + credBytes.length + versionBytes.length).put(tenantBytes)
                .put(credBytes).put(versionBytes).array();
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    private static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new CryptoOperationException("CRYPTO_ENCRYPT_001", "AES-256-GCM encryption failed", e);
        }
    }

    private static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new CryptoOperationException("CRYPTO_DECRYPT_001", "AES-256-GCM decryption failed", e);
        }
    }

    /**
     * Does not expose key material.
     */
    @Override
    public String toString() {
        return "AesGcmEncryptionProvider[activeVersion=" + keyRing.activeVersion() + "]";
    }
}
