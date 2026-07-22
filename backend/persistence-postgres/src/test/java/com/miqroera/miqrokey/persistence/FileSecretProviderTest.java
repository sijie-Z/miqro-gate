package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException;
import com.miqroera.miqrokey.persistence.config.FileSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FileSecretProvider")
class FileSecretProviderTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("single file loading")
    class SingleFile {

        @Test
        @DisplayName("should load raw 32-byte key from file")
        void shouldLoadRawKey() throws Exception {
            byte[] key = randomBytes(32);
            Path keyFile = tempDir.resolve("master.key");
            Files.write(keyFile, key);

            KeyRing ring = FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), 32, "AES");

            assertThat(ring.activeVersion()).isEqualTo("v1");
            byte[] loaded = ring.activeKey();
            assertThat(loaded).isEqualTo(key);
            java.util.Arrays.fill(loaded, (byte) 0);
        }

        @Test
        @DisplayName("should load base64-encoded key from file")
        void shouldLoadBase64Key() throws Exception {
            byte[] key = randomBytes(32);
            String b64 = Base64.getEncoder().encodeToString(key);
            Path keyFile = tempDir.resolve("b64.key");
            Files.writeString(keyFile, b64);

            KeyRing ring = FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), 32, "AES");

            byte[] loaded = ring.activeKey();
            assertThat(loaded).isEqualTo(key);
            java.util.Arrays.fill(loaded, (byte) 0);
        }

        @Test
        @DisplayName("should reject missing file")
        void shouldRejectMissingFile() {
            Path nonexistent = tempDir.resolve("does-not-exist.key");

            assertThatThrownBy(
                    () -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", nonexistent.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_002");
        }

        @Test
        @DisplayName("should reject empty file (wrong length)")
        void shouldRejectEmptyFile() throws Exception {
            Path emptyFile = tempDir.resolve("empty.key");
            Files.write(emptyFile, new byte[0]);

            assertThatThrownBy(
                    () -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", emptyFile.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_005");
        }

        @Test
        @DisplayName("should reject all-zero key")
        void shouldRejectAllZeroKey() throws Exception {
            byte[] zeros = new byte[32];
            Path zeroFile = tempDir.resolve("zero.key");
            Files.write(zeroFile, zeros);

            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", zeroFile.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_006");
        }

        @Test
        @DisplayName("should reject all-same-byte key (demo/sample key)")
        void shouldRejectAllSameByteKey() throws Exception {
            byte[] pattern = new byte[32];
            java.util.Arrays.fill(pattern, (byte) 0x42);
            Path patternFile = tempDir.resolve("pattern.key");
            Files.write(patternFile, pattern);

            assertThatThrownBy(
                    () -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", patternFile.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_007");
        }

        @Test
        @DisplayName("should reject non-regular file (directory)")
        void shouldRejectDirectory() {
            Path dir = tempDir.resolve("a-directory");
            // Directory already unusable as a key path

            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", dir.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_002");
        }
    }

    @Nested
    @DisplayName("multi-version loading")
    class MultiVersion {

        @Test
        @DisplayName("should load multiple versions from different files")
        void shouldLoadMultiVersion() throws Exception {
            byte[] keyV1 = randomBytes(32);
            byte[] keyV2 = randomBytes(32);
            Path fileV1 = tempDir.resolve("enc-v1.key");
            Path fileV2 = tempDir.resolve("enc-v2.key");
            Files.write(fileV1, keyV1);
            Files.write(fileV2, keyV2);

            KeyRing ring = FileSecretProvider.loadKeyRing("v2",
                    Map.of("v1", fileV1.toString(), "v2", fileV2.toString()), 32, "AES");

            assertThat(ring.activeVersion()).isEqualTo("v2");
            assertThat(ring.knownVersions()).containsExactlyInAnyOrder("v1", "v2");

            byte[] loadedV1 = ring.keyForVersion("v1");
            assertThat(loadedV1).isEqualTo(keyV1);
            java.util.Arrays.fill(loadedV1, (byte) 0);

            byte[] loadedV2 = ring.keyForVersion("v2");
            assertThat(loadedV2).isEqualTo(keyV2);
            java.util.Arrays.fill(loadedV2, (byte) 0);
        }

        @Test
        @DisplayName("should reject when active version not in version map")
        void shouldRejectMissingActiveVersion() {
            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v99",
                    Map.of("v1", tempDir.resolve("k1.key").toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_001");
        }
    }

    @Nested
    @DisplayName("HMAC key (no fixed length)")
    class HmacKeys {

        @Test
        @DisplayName("should accept HMAC keys of 32+ bytes")
        void shouldAcceptLongHmacKey() throws Exception {
            byte[] key = randomBytes(64);
            Path keyFile = tempDir.resolve("hmac-long.key");
            Files.write(keyFile, key);

            KeyRing ring = FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), -1, "HMAC");

            byte[] loaded = ring.activeKey();
            assertThat(loaded).hasSize(64);
            assertThat(loaded).isEqualTo(key);
            java.util.Arrays.fill(loaded, (byte) 0);
        }

        @Test
        @DisplayName("should reject HMAC keys shorter than 32 bytes with min length check")
        void shouldRejectShortHmacKey() throws Exception {
            byte[] shortKey = randomBytes(16);
            Path keyFile = tempDir.resolve("hmac-short.key");
            Files.write(keyFile, shortKey);

            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), -1, "HMAC"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_005");
        }
    }
}
