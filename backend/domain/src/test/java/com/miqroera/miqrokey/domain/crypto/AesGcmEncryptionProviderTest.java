package com.miqroera.miqrokey.domain.crypto;

import com.miqroera.miqrokey.domain.crypto.impl.AesGcmEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AesGcmEncryptionProvider")
class AesGcmEncryptionProviderTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_CREDENTIAL = UUID.randomUUID();

    private KeyRing keyRing;
    private AesGcmEncryptionProvider provider;

    @BeforeEach
    void setUp() {
        byte[] keyV1 = randomBytes(32);
        keyRing = new KeyRing("v1", Map.of("v1", keyV1));
        provider = new AesGcmEncryptionProvider(keyRing);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @Nested
    @DisplayName("basic encrypt/decrypt")
    class BasicCrypto {

        @Test
        @DisplayName("should encrypt and decrypt plaintext")
        void shouldEncryptAndDecrypt() {
            byte[] plaintext = "super-secret-api-key-1234567890".getBytes();

            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);
            byte[] decrypted = provider.decrypt(encrypted, TENANT_ID, CREDENTIAL_ID);

            assertThat(decrypted).isEqualTo(plaintext);
            assertThat(encrypted.ciphertext()).isNotEqualTo(plaintext);
            assertThat(encrypted.keyVersion()).isEqualTo("v1");
            assertThat(encrypted.nonce()).hasSize(12);
        }

        @Test
        @DisplayName("should produce different ciphertext for same plaintext (nonce uniqueness)")
        void shouldProduceUniqueNonces() {
            byte[] plaintext = "same secret".getBytes();

            EncryptedSecret e1 = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);
            EncryptedSecret e2 = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            assertThat(e1.ciphertext()).isNotEqualTo(e2.ciphertext());
            assertThat(e1.nonce()).isNotEqualTo(e2.nonce());
        }

        @Test
        @DisplayName("should encrypt and decrypt empty plaintext")
        void shouldEncryptDecryptEmpty() {
            byte[] plaintext = new byte[0];

            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);
            byte[] decrypted = provider.decrypt(encrypted, TENANT_ID, CREDENTIAL_ID);

            assertThat(decrypted).isEmpty();
        }

        @Test
        @DisplayName("should encrypt and decrypt large plaintext")
        void shouldEncryptDecryptLarge() {
            byte[] plaintext = randomBytes(4096);

            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);
            byte[] decrypted = provider.decrypt(encrypted, TENANT_ID, CREDENTIAL_ID);

            assertThat(decrypted).isEqualTo(plaintext);
        }
    }

    @Nested
    @DisplayName("AAD binding")
    class AadBinding {

        @Test
        @DisplayName("should fail decryption with wrong tenant ID")
        void shouldFailWithWrongTenant() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            assertThatThrownBy(() -> provider.decrypt(encrypted, OTHER_TENANT, CREDENTIAL_ID))
                    .isInstanceOf(CryptoOperationException.class);
        }

        @Test
        @DisplayName("should fail decryption with wrong credential ID")
        void shouldFailWithWrongCredential() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            assertThatThrownBy(() -> provider.decrypt(encrypted, TENANT_ID, OTHER_CREDENTIAL))
                    .isInstanceOf(CryptoOperationException.class);
        }

        @Test
        @DisplayName("should fail decryption with wrong key version in AAD (mismatch)")
        void shouldFailWithWrongKeyVersion() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            // The AAD contains the original key version. Altering the encrypted version
            // field in the record would make lookup fail, but a manual decrypt with
            // the right key and wrong AAD should fail at AEAD level.
            // We verify by constructing the wrong AAD scenario:
            byte[] key = keyRing.activeKey();
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(key, "AES");
                javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128,
                        encrypted.nonce());
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec);
                // Use wrong AAD
                byte[] wrongAad = "wrong-aad-data".getBytes();
                cipher.updateAAD(wrongAad);
                cipher.doFinal(encrypted.ciphertext());
                // If we get here without exception, the test should fail
                throw new AssertionError("Expected AEAD tag mismatch but decryption succeeded");
            } catch (javax.crypto.AEADBadTagException e) {
                // Expected — wrong AAD causes tag mismatch
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("tampering detection")
    class Tampering {

        @Test
        @DisplayName("should fail decryption on tampered ciphertext")
        void shouldDetectTamperedCiphertext() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            byte[] tampered = encrypted.ciphertext().clone();
            // Flip a bit in the middle of the ciphertext
            tampered[tampered.length / 2] ^= 0x01;

            EncryptedSecret tamperedSecret = new EncryptedSecret(tampered, encrypted.nonce(), encrypted.keyVersion());
            assertThatThrownBy(() -> provider.decrypt(tamperedSecret, TENANT_ID, CREDENTIAL_ID))
                    .isInstanceOf(CryptoOperationException.class);
        }

        @Test
        @DisplayName("should fail decryption on tampered nonce")
        void shouldDetectTamperedNonce() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            byte[] tamperedNonce = encrypted.nonce().clone();
            tamperedNonce[0] ^= 0x01;

            EncryptedSecret tamperedSecret = new EncryptedSecret(encrypted.ciphertext(), tamperedNonce,
                    encrypted.keyVersion());
            assertThatThrownBy(() -> provider.decrypt(tamperedSecret, TENANT_ID, CREDENTIAL_ID))
                    .isInstanceOf(CryptoOperationException.class);
        }

        @Test
        @DisplayName("should fail decryption on truncated ciphertext")
        void shouldDetectTruncatedCiphertext() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            byte[] truncated = Arrays.copyOf(encrypted.ciphertext(), encrypted.ciphertext().length - 1);
            EncryptedSecret truncatedSecret = new EncryptedSecret(truncated, encrypted.nonce(), encrypted.keyVersion());
            assertThatThrownBy(() -> provider.decrypt(truncatedSecret, TENANT_ID, CREDENTIAL_ID))
                    .isInstanceOf(CryptoOperationException.class);
        }
    }

    @Nested
    @DisplayName("wrong key handling")
    class WrongKey {

        @Test
        @DisplayName("should fail with unknown key version")
        void shouldFailWithUnknownKeyVersion() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            // Create a tampered version string
            EncryptedSecret wrongVersion = new EncryptedSecret(encrypted.ciphertext(), encrypted.nonce(),
                    "nonexistent-v99");

            assertThatThrownBy(() -> provider.decrypt(wrongVersion, TENANT_ID, CREDENTIAL_ID))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_KEY_002");
        }

        @Test
        @DisplayName("should fail with completely wrong key (different KeyRing)")
        void shouldFailWithWrongKeyRing() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            byte[] differentKey = randomBytes(32);
            var wrongRing = new KeyRing("v1", Map.of("v1", differentKey));
            var wrongProvider = new AesGcmEncryptionProvider(wrongRing);

            assertThatThrownBy(() -> wrongProvider.decrypt(encrypted, TENANT_ID, CREDENTIAL_ID))
                    .isInstanceOf(CryptoOperationException.class);
        }
    }

    @Nested
    @DisplayName("key versioning and rotation")
    class KeyVersioning {

        @Test
        @DisplayName("should encrypt with active version")
        void shouldEncryptWithActiveVersion() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);
            assertThat(encrypted.keyVersion()).isEqualTo("v1");
        }

        @Test
        @DisplayName("should decrypt data encrypted with previous version")
        void shouldDecryptOldVersion() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            // Rotate to v2
            byte[] keyV2 = randomBytes(32);
            provider = new AesGcmEncryptionProvider(keyRing.withNewActiveVersion("v2", keyV2));

            // Should still decrypt v1 ciphertext
            byte[] decrypted = provider.decrypt(encrypted, TENANT_ID, CREDENTIAL_ID);
            assertThat(decrypted).isEqualTo(plaintext);

            // New encryption should use v2
            EncryptedSecret newEncrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);
            assertThat(newEncrypted.keyVersion()).isEqualTo("v2");
        }

        @Test
        @DisplayName("should re-encrypt with active version")
        void shouldReEncrypt() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret v1encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            // Rotate to v2
            byte[] keyV2 = randomBytes(32);
            provider = new AesGcmEncryptionProvider(keyRing.withNewActiveVersion("v2", keyV2));

            // Re-encrypt
            EncryptedSecret reEncrypted = provider.reEncrypt(v1encrypted, TENANT_ID, CREDENTIAL_ID);
            assertThat(reEncrypted.keyVersion()).isEqualTo("v2");

            // Verify re-encrypted data decrypts to original plaintext
            byte[] decrypted = provider.decrypt(reEncrypted, TENANT_ID, CREDENTIAL_ID);
            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("should report active key version")
        void shouldReportActiveKeyVersion() {
            assertThat(provider.activeKeyVersion()).isEqualTo("v1");
        }
    }

    @Nested
    @DisplayName("safety and sanitization")
    class Safety {

        @Test
        @DisplayName("toString should not expose key material")
        void toStringShouldNotExposeKeys() {
            assertThat(provider.toString()).doesNotContain("key").contains("activeVersion=v1");
        }

        @Test
        @DisplayName("EncryptedSecret toString should not expose ciphertext data")
        void encryptedSecretToStringShouldBeSafe() {
            byte[] plaintext = "secret".getBytes();
            EncryptedSecret encrypted = provider.encrypt(plaintext, TENANT_ID, CREDENTIAL_ID);

            String str = encrypted.toString();
            assertThat(str).doesNotContain("secret").contains("ciphertextSize=").contains("nonceSize=");
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class Validation {

        @Test
        @DisplayName("should reject keys of wrong length")
        void shouldRejectWrongKeyLength() {
            byte[] shortKey = randomBytes(16); // 128-bit instead of 256-bit
            var ring = new KeyRing("v1", Map.of("v1", shortKey));
            assertThatThrownBy(() -> new AesGcmEncryptionProvider(ring)).isInstanceOf(CryptoOperationException.class)
                    .hasMessageContaining("CRYPTO_KEY_001");
        }
    }
}
