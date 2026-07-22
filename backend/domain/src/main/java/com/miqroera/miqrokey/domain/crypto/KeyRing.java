package com.miqroera.miqrokey.domain.crypto;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable key ring holding named key versions for encryption and HMAC
 * operations.
 *
 * <p>
 * <strong>Security property:</strong> Every {@code byte[]} key passed into the
 * constructor or {@link #withNewActiveVersion(String, byte[])} is defensively
 * deep-copied. The caller may zero-fill the source array immediately after
 * construction without affecting the material inside the ring.
 * </p>
 *
 * <p>
 * <strong>Key access:</strong> {@link #activeKey()} and
 * {@link #keyForVersion(String)} each return a fresh clone. Callers are
 * responsible for zero-filling the returned clone after use via
 * {@link com.miqroera.miqrokey.domain.crypto.impl.SecretWiping#clearArray(byte[])}.
 * </p>
 *
 * <p>
 * <strong>Rotation:</strong> Use {@link #withNewActiveVersion(String, byte[])}
 * to create a new ring that retains all previous keys while promoting the
 * supplied key as the active version. The original ring is unchanged.
 * </p>
 */
public final class KeyRing {

    private final String activeVersion;
    private final Map<String, byte[]> keysByVersion;

    /**
     * Creates a new KeyRing. Every byte array in {@code keysByVersion} is
     * deep-copied; mutating the original map or its arrays after construction has
     * no effect on this instance.
     *
     * @param activeVersion
     *            the version used for new operations
     * @param keysByVersion
     *            map of version → key material (will be deep-copied)
     * @throws NullPointerException
     *             if activeVersion is null
     * @throws IllegalArgumentException
     *             if activeVersion is not in the map, or any key is null/empty
     */
    public KeyRing(String activeVersion, Map<String, byte[]> keysByVersion) {
        this.activeVersion = Objects.requireNonNull(activeVersion, "activeVersion");
        Map<String, byte[]> copied = new HashMap<>(keysByVersion.size());
        for (var entry : keysByVersion.entrySet()) {
            byte[] value = entry.getValue();
            if (value == null || value.length == 0) {
                throw new IllegalArgumentException("Key for version '" + entry.getKey() + "' is null or empty");
            }
            copied.put(entry.getKey(), value.clone());
        }
        this.keysByVersion = Collections.unmodifiableMap(copied);
        if (!this.keysByVersion.containsKey(activeVersion)) {
            throw new IllegalArgumentException("activeVersion '" + activeVersion + "' not found in key ring");
        }
    }

    /**
     * Returns the version identifier used for new encryption and digest
     * computation.
     */
    public String activeVersion() {
        return activeVersion;
    }

    /**
     * Returns a fresh clone of the active key. The caller must zero-fill the
     * returned array after use.
     */
    public byte[] activeKey() {
        return keysByVersion.get(activeVersion).clone();
    }

    /**
     * Returns a fresh clone of the key for the given version, or {@code null} if
     * the version is unknown. The caller must zero-fill the returned array after
     * use.
     */
    public byte[] keyForVersion(String version) {
        byte[] key = keysByVersion.get(version);
        if (key == null) {
            return null;
        }
        return key.clone();
    }

    public boolean hasVersion(String version) {
        return keysByVersion.containsKey(version);
    }

    /**
     * Returns an unmodifiable view of all known version identifiers.
     */
    public Set<String> knownVersions() {
        return keysByVersion.keySet();
    }

    /**
     * Creates a new KeyRing that retains all existing versions and adds (or
     * replaces) the given {@code newActiveVersion} as the active version. The
     * supplied {@code newKey} is deep-copied. The original ring is unchanged.
     *
     * @param newActiveVersion
     *            version identifier for the new active key
     * @param newKey
     *            the new key material (will be deep-copied)
     * @return a new KeyRing with the additional version
     */
    public KeyRing withNewActiveVersion(String newActiveVersion, byte[] newKey) {
        var newMap = new HashMap<>(keysByVersion);
        newMap.put(newActiveVersion, newKey.clone());
        return new KeyRing(newActiveVersion, Collections.unmodifiableMap(newMap));
    }

    /**
     * Does not expose any key material. Only reports the active version identifier
     * and the number of known versions.
     */
    @Override
    public String toString() {
        return "KeyRing[activeVersion=" + activeVersion + ", versionCount=" + keysByVersion.size() + "]";
    }
}
