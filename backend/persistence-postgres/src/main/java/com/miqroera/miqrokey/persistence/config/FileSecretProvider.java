package com.miqroera.miqrokey.persistence.config;

import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FileSecretProvider {

    private FileSecretProvider() {
    }

    public static KeyRing loadKeyRing(String activeVersion, Map<String, String> versionPaths, int expectedLength,
            String keyTypeLabel) {
        if (!versionPaths.containsKey(activeVersion)) {
            throw new CryptoOperationException("CRYPTO_CONFIG_001", keyTypeLabel + ": active version '" + activeVersion
                    + "' not in configured versions: " + versionPaths.keySet());
        }

        Map<String, byte[]> keysByVersion = new LinkedHashMap<>();
        for (var entry : versionPaths.entrySet()) {
            String version = entry.getKey();
            Path path = Path.of(entry.getValue());
            byte[] keyMaterial = loadAndValidateFile(path, expectedLength, keyTypeLabel + " [" + version + "]");
            keysByVersion.put(version, keyMaterial);
        }

        // KeyRing deep-copies all values; we zero our references after construction
        KeyRing ring = new KeyRing(activeVersion, keysByVersion);
        for (byte[] material : keysByVersion.values()) {
            Arrays.fill(material, (byte) 0);
        }
        return ring;
    }

    static byte[] loadAndValidateFile(Path path, int expectedLength, String label) {
        if (!Files.exists(path)) {
            throw new CryptoOperationException("CRYPTO_CONFIG_002",
                    label + ": key file not found: " + path.toAbsolutePath());
        }

        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new CryptoOperationException("CRYPTO_CONFIG_003",
                    label + ": not a regular file (symlinks not allowed): " + path.toAbsolutePath());
        }

        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new CryptoOperationException("CRYPTO_CONFIG_004",
                    label + ": failed to read key file: " + path.toAbsolutePath(), e);
        }

        checkPermissions(path, label);

        byte[] keyMaterial = decodeMaybeBase64(raw, label);

        int minLen = expectedLength > 0 ? expectedLength : 32;
        if (expectedLength > 0 && keyMaterial.length != expectedLength) {
            Arrays.fill(keyMaterial, (byte) 0);
            Arrays.fill(raw, (byte) 0);
            throw new CryptoOperationException("CRYPTO_CONFIG_005", label + ": key must be exactly " + expectedLength
                    + " bytes, got " + keyMaterial.length + " (raw file: " + raw.length + " bytes)");
        }
        if (expectedLength <= 0 && keyMaterial.length < minLen) {
            Arrays.fill(keyMaterial, (byte) 0);
            Arrays.fill(raw, (byte) 0);
            throw new CryptoOperationException("CRYPTO_CONFIG_005",
                    label + ": key must be at least " + minLen + " bytes, got " + keyMaterial.length);
        }

        if (isAllZero(keyMaterial)) {
            Arrays.fill(keyMaterial, (byte) 0);
            throw new CryptoOperationException("CRYPTO_CONFIG_006", label + ": key is all zeros - refused");
        }

        if (isAllSameByte(keyMaterial)) {
            Arrays.fill(keyMaterial, (byte) 0);
            throw new CryptoOperationException("CRYPTO_CONFIG_007",
                    label + ": key is all same byte - refused as sample/weak key");
        }

        Arrays.fill(raw, (byte) 0);
        return keyMaterial;
    }

    /**
     * Validates that a key file only has owner-read permissions (0400) on POSIX
     * systems, and is at least readable on all platforms.
     *
     * <p>
     * <strong>Production behavior (POSIX):</strong> Permission inspection is strict
     * by default. If POSIX file attributes are supported, the file must have
     * exactly {@code OWNER_READ} and no other permissions. Any other permission bit
     * ({@code OWNER_WRITE}, {@code OWNER_EXECUTE}, {@code GROUP_*},
     * {@code OTHERS_*}) causes immediate startup failure with
     * {@code CRYPTO_CONFIG_008}.
     * </p>
     *
     * <p>
     * If POSIX attribute inspection itself fails (e.g. I/O error, security manager
     * denial, unsupported file system on a POSIX host), the check fails safe:
     * startup is refused rather than silently bypassing validation.
     * </p>
     *
     * <p>
     * <strong>Non-POSIX (Windows):</strong> Only a readability check is performed.
     * The process owner must be able to read the key file.
     * </p>
     */
    static void checkPermissions(Path path, String label) {
        if (isPosix()) {
            try {
                var perms = Files.getPosixFilePermissions(path);
                boolean hasOnlyOwnerRead = perms.size() == 1 && perms.contains(PosixFilePermission.OWNER_READ);
                if (!hasOnlyOwnerRead) {
                    throw new CryptoOperationException("CRYPTO_CONFIG_008", label
                            + ": key file has overly permissive permissions (must be 0400): " + path.toAbsolutePath());
                }
            } catch (CryptoOperationException e) {
                throw e;
            } catch (Exception e) {
                // POSIX supported but inspection failed — fail safe
                throw new CryptoOperationException("CRYPTO_CONFIG_008",
                        label + ": failed to inspect key file permissions: " + path.toAbsolutePath(), e);
            }
        } else {
            // Non-POSIX (Windows): just ensure the file is readable by the process
            if (!Files.isReadable(path)) {
                throw new CryptoOperationException("CRYPTO_CONFIG_009",
                        label + ": key file is not readable: " + path.toAbsolutePath());
            }
        }
    }

    private static byte[] decodeMaybeBase64(byte[] raw, String label) {
        boolean looksLikeBase64 = true;
        for (byte b : raw) {
            if (b == '\n' || b == '\r' || b == ' ') {
                continue;
            }
            if (b < 0x20 || b > 0x7E) {
                looksLikeBase64 = false;
                break;
            }
        }
        if (looksLikeBase64 && raw.length > 32) {
            try {
                String trimmed = new String(raw).trim();
                return Base64.getDecoder().decode(trimmed);
            } catch (IllegalArgumentException e) {
                return raw.clone();
            }
        }
        return raw.clone();
    }

    private static boolean isAllZero(byte[] array) {
        for (byte b : array) {
            if (b != 0)
                return false;
        }
        return true;
    }

    private static boolean isAllSameByte(byte[] array) {
        if (array.length == 0)
            return true;
        byte first = array[0];
        for (int i = 1; i < array.length; i++) {
            if (array[i] != first)
                return false;
        }
        return true;
    }

    private static boolean isPosix() {
        try {
            return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifies that the AES master key material and HMAC key material are distinct
     * across all version combinations.
     *
     * <p>
     * Loads key material from every configured encryption version file and every
     * HMAC version file, then compares every (encryption-version, HMAC-version)
     * pair in constant time using {@link MessageDigest#isEqual(byte[], byte[])}. If
     * any pair contains identical bytes, startup fails with
     * {@code CRYPTO_CONFIG_011}.
     * </p>
     *
     * <p>
     * All temporary byte arrays are zero-filled before this method returns, whether
     * it succeeds or throws.
     * </p>
     *
     * @param encVersionPaths
     *            encryption version → file path map
     * @param hmacVersionPaths
     *            HMAC version → file path map
     * @throws CryptoOperationException
     *             if any encryption key material equals any HMAC key material
     */
    public static void verifyKeyMaterialSeparation(Map<String, String> encVersionPaths,
            Map<String, String> hmacVersionPaths) {
        if (encVersionPaths == null || encVersionPaths.isEmpty() || hmacVersionPaths == null
                || hmacVersionPaths.isEmpty()) {
            return;
        }

        List<LoadedKey> encMaterial = new ArrayList<>();
        List<LoadedKey> hmacMaterial = new ArrayList<>();

        try {
            for (var entry : encVersionPaths.entrySet()) {
                byte[] material = loadAndValidateFile(Path.of(entry.getValue()), 32,
                        "AES master key [" + entry.getKey() + "]");
                encMaterial.add(new LoadedKey(entry.getKey(), material));
            }
            for (var entry : hmacVersionPaths.entrySet()) {
                byte[] material = loadAndValidateFile(Path.of(entry.getValue()), -1,
                        "HMAC key [" + entry.getKey() + "]");
                hmacMaterial.add(new LoadedKey(entry.getKey(), material));
            }

            for (LoadedKey enc : encMaterial) {
                for (LoadedKey hmac : hmacMaterial) {
                    if (enc.material.length == hmac.material.length
                            && MessageDigest.isEqual(enc.material, hmac.material)) {
                        throw new CryptoOperationException("CRYPTO_CONFIG_011",
                                "Master and HMAC keys must use different material. " + "Encryption version "
                                        + enc.version + " and HMAC version " + hmac.version
                                        + " contain identical bytes.");
                    }
                }
            }
        } finally {
            for (LoadedKey k : encMaterial) {
                Arrays.fill(k.material, (byte) 0);
            }
            for (LoadedKey k : hmacMaterial) {
                Arrays.fill(k.material, (byte) 0);
            }
        }
    }

    /**
     * Internal holder for a loaded key during separation verification.
     */
    private static final class LoadedKey {
        final String version;
        final byte[] material;

        LoadedKey(String version, byte[] material) {
            this.version = version;
            this.material = material;
        }
    }
}
