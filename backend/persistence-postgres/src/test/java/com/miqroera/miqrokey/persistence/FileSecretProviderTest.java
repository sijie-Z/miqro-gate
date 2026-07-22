package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException;
import com.miqroera.miqrokey.persistence.config.FileSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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

    /**
     * Sets POSIX file permissions to 0400 (owner-read only) if the platform
     * supports it. On Windows this is a no-op (UnsupportedOperationException is
     * caught).
     */
    private static void ensureStrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem — skip
        } catch (Exception e) {
            // Unexpected — skip (test files in temp dir should be settable)
        }
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
            ensureStrictPermissions(keyFile);

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
            ensureStrictPermissions(keyFile);

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
            ensureStrictPermissions(emptyFile);

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
            ensureStrictPermissions(zeroFile);

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
            ensureStrictPermissions(patternFile);

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
            ensureStrictPermissions(fileV1);
            ensureStrictPermissions(fileV2);

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
            ensureStrictPermissions(keyFile);

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
            ensureStrictPermissions(keyFile);

            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), -1, "HMAC"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_005");
        }
    }

    @Nested
    @DisplayName("POSIX permissions (strict by default)")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    class PosixPermissions {

        @Test
        @DisplayName("should accept key file with exactly 0400 permissions")
        void shouldAcceptStrictOwnerReadOnly() throws Exception {
            byte[] key = randomBytes(32);
            Path keyFile = tempDir.resolve("strict-0400.key");
            Files.write(keyFile, key);
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("r--------"));

            // Should not throw — 0400 is the required permission
            KeyRing ring = FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), 32, "AES");
            assertThat(ring.activeVersion()).isEqualTo("v1");
        }

        @Test
        @DisplayName("should reject key file with 0644 (group/other readable)")
        void shouldRejectGroupOtherReadable() throws Exception {
            byte[] key = randomBytes(32);
            Path keyFile = tempDir.resolve("world-readable.key");
            Files.write(keyFile, key);
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r--r--"));

            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_008");
        }

        @Test
        @DisplayName("should reject key file with 0600 (owner writable)")
        void shouldRejectOwnerWritable() throws Exception {
            byte[] key = randomBytes(32);
            Path keyFile = tempDir.resolve("owner-rw.key");
            Files.write(keyFile, key);
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));

            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_008");
        }

        @Test
        @DisplayName("should reject key file with 0777 (fully open)")
        void shouldRejectFullyOpen() throws Exception {
            byte[] key = randomBytes(32);
            Path keyFile = tempDir.resolve("open.key");
            Files.write(keyFile, key);
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rwxrwxrwx"));

            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_008");
        }

        @Test
        @DisplayName("should reject key file with 0500 (owner read+execute)")
        void shouldRejectWithExecuteBit() throws Exception {
            byte[] key = randomBytes(32);
            Path keyFile = tempDir.resolve("executable.key");
            Files.write(keyFile, key);
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("r-x------"));

            assertThatThrownBy(() -> FileSecretProvider.loadKeyRing("v1", Map.of("v1", keyFile.toString()), 32, "AES"))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_008");
        }
    }

    @Nested
    @DisplayName("key material separation (byte-content comparison)")
    class KeyMaterialSeparation {

        @Test
        @DisplayName("should reject when encryption and HMAC files contain identical bytes")
        void shouldRejectIdenticalMaterialInDifferentFiles() throws Exception {
            byte[] sameKey = randomBytes(32);
            Path encFile = tempDir.resolve("enc.key");
            Path hmacFile = tempDir.resolve("hmac.key");
            Files.write(encFile, sameKey);
            Files.write(hmacFile, sameKey); // same bytes, different file
            ensureStrictPermissions(encFile);
            ensureStrictPermissions(hmacFile);

            assertThatThrownBy(() -> FileSecretProvider.verifyKeyMaterialSeparation(Map.of("v1", encFile.toString()),
                    Map.of("v1", hmacFile.toString()))).isInstanceOf(CryptoOperationException.class)
                    .hasMessageContaining("CRYPTO_CONFIG_011").hasMessageContaining("identical bytes");
        }

        @Test
        @DisplayName("should accept when encryption and HMAC files contain different bytes")
        void shouldAcceptDifferentMaterial() throws Exception {
            byte[] encKey = randomBytes(32);
            byte[] hmacKey = randomBytes(32);
            // Ensure they are actually different
            while (java.security.MessageDigest.isEqual(encKey, hmacKey)) {
                hmacKey = randomBytes(32);
            }
            Path encFile = tempDir.resolve("enc-diff.key");
            Path hmacFile = tempDir.resolve("hmac-diff.key");
            Files.write(encFile, encKey);
            Files.write(hmacFile, hmacKey);
            ensureStrictPermissions(encFile);
            ensureStrictPermissions(hmacFile);

            // Should not throw
            FileSecretProvider.verifyKeyMaterialSeparation(Map.of("v1", encFile.toString()),
                    Map.of("v1", hmacFile.toString()));
        }

        @Test
        @DisplayName("should reject identical material across multi-version combinations")
        void shouldRejectCrossVersionIdenticalMaterial() throws Exception {
            byte[] shared = randomBytes(32);
            byte[] uniqueEnc = randomBytes(32);
            byte[] uniqueHmac = randomBytes(64);
            Path encV1 = tempDir.resolve("enc-v1.key");
            Path encV2 = tempDir.resolve("enc-v2.key");
            Path hmacV1 = tempDir.resolve("hmac-v1.key");
            Path hmacV2 = tempDir.resolve("hmac-v2.key");
            Files.write(encV1, uniqueEnc);
            Files.write(encV2, shared); // enc v2 == hmac v1 (same bytes)
            Files.write(hmacV1, shared);
            Files.write(hmacV2, uniqueHmac);
            ensureStrictPermissions(encV1);
            ensureStrictPermissions(encV2);
            ensureStrictPermissions(hmacV1);
            ensureStrictPermissions(hmacV2);

            assertThatThrownBy(() -> FileSecretProvider.verifyKeyMaterialSeparation(
                    Map.of("v1", encV1.toString(), "v2", encV2.toString()),
                    Map.of("v1", hmacV1.toString(), "v2", hmacV2.toString())))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_CONFIG_011")
                    .hasMessageContaining("identical bytes");
        }

        @Test
        @DisplayName("should accept different material across multi-version configurations")
        void shouldAcceptMultiVersionDifferentMaterial() throws Exception {
            byte[] encV1 = randomBytes(32);
            byte[] encV2 = randomBytes(32);
            byte[] hmacV1 = randomBytes(64);
            byte[] hmacV2 = randomBytes(48);
            Path e1 = tempDir.resolve("e1.key");
            Path e2 = tempDir.resolve("e2.key");
            Path h1 = tempDir.resolve("h1.key");
            Path h2 = tempDir.resolve("h2.key");
            Files.write(e1, encV1);
            Files.write(e2, encV2);
            Files.write(h1, hmacV1);
            Files.write(h2, hmacV2);
            ensureStrictPermissions(e1);
            ensureStrictPermissions(e2);
            ensureStrictPermissions(h1);
            ensureStrictPermissions(h2);

            // Should not throw
            FileSecretProvider.verifyKeyMaterialSeparation(Map.of("v1", e1.toString(), "v2", e2.toString()),
                    Map.of("v1", h1.toString(), "v2", h2.toString()));
        }

        @Test
        @DisplayName("should accept when no versions are configured (empty maps)")
        void shouldAcceptEmptyMaps() {
            // Should not throw with empty maps
            FileSecretProvider.verifyKeyMaterialSeparation(Map.of(), Map.of());
        }
    }
}
