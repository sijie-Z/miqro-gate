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

public final class AesGcmEncryptionProvider implements KeyEncryptionProvider {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits — recommended for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128-bit authentication tag
    private static final int AES_KEY_LENGTH = 32; // 256 bits

    private final KeyRing keyRing;
    private final SecureRandom secureRandom;

    public AesGcmEncryptionProvider(KeyRing keyRing) {
        this(keyRing, new SecureRandom());
    }

    AesGcmEncryptionProvider(KeyRing keyRing, SecureRandom secureRandom) {
        this.keyRing = keyRing;
        this.secureRandom = secureRandom;
        for (byte[] key : keyRing.knownVersions().stream().map(keyRing::keyForVersion).toList()) {
            if (key.length != AES_KEY_LENGTH) {
                throw new IllegalArgumentException("AES key must be " + AES_KEY_LENGTH + " bytes, got " + key.length);
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
            clearArray(key);
        }
    }

    @Override
    public byte[] decrypt(EncryptedSecret encrypted, UUID tenantId, UUID credentialId) {
        byte[] key = keyRing.keyForVersion(encrypted.keyVersion());
        if (key == null) {
            throw new IllegalArgumentException("Unknown encryption key version: " + encrypted.keyVersion()
                    + ". Known versions: " + keyRing.knownVersions());
        }
        try {
            byte[] aad = buildAad(tenantId, credentialId, encrypted.keyVersion());
            return aesGcmDecrypt(key, encrypted.nonce(), encrypted.ciphertext(), aad);
        } finally {
            clearArray(key);
        }
    }

    @Override
    public EncryptedSecret reEncrypt(EncryptedSecret encrypted, UUID tenantId, UUID credentialId) {
        byte[] plaintext = decrypt(encrypted, tenantId, credentialId);
        try {
            return encrypt(plaintext, tenantId, credentialId);
        } finally {
            clearArray(plaintext);
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
            throw new CryptoOperationException("AES-256-GCM encryption failed", e);
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
            throw new CryptoOperationException("AES-256-GCM decryption failed: " + e.getMessage(), e);
        }
    }

    private static void clearArray(byte[] array) {
        if (array != null) {
            java.util.Arrays.fill(array, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "AesGcmEncryptionProvider[activeVersion=" + keyRing.activeVersion() + "]";
    }
}
