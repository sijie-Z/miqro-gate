package com.miqroera.miqrokey.persistence.config;

import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
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

    static void checkPermissions(Path path, String label) {
        try {
            if (isPosix()) {
                var perms = Files.getPosixFilePermissions(path);
                if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                    throw new CryptoOperationException("CRYPTO_CONFIG_008",
                            label + ": key file has overly permissive permissions (should be 0400): "
                                    + path.toAbsolutePath());
                }
                if (perms.contains(PosixFilePermission.OWNER_WRITE) || perms.contains(PosixFilePermission.GROUP_WRITE)
                        || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new CryptoOperationException("CRYPTO_CONFIG_008", label
                            + ": key file has write permissions (should be read-only 0400): " + path.toAbsolutePath());
                }
            } else {
                if (!Files.isReadable(path)) {
                    throw new CryptoOperationException("CRYPTO_CONFIG_009",
                            label + ": key file is not readable: " + path.toAbsolutePath());
                }
            }
        } catch (CryptoOperationException e) {
            throw e;
        } catch (Exception e) {
            // If we cannot determine permissions, warn but do not fail for test/dev.
            // In production, the file system should enforce ACLs.
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
}
