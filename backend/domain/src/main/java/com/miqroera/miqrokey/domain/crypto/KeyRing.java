package com.miqroera.miqrokey.domain.crypto;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class KeyRing {

    private final String activeVersion;
    private final Map<String, byte[]> keysByVersion;

    public KeyRing(String activeVersion, Map<String, byte[]> keysByVersion) {
        this.activeVersion = Objects.requireNonNull(activeVersion, "activeVersion");
        this.keysByVersion = Map.copyOf(keysByVersion);
        if (!keysByVersion.containsKey(activeVersion)) {
            throw new IllegalArgumentException("activeVersion '" + activeVersion + "' not found in key ring");
        }
        for (var entry : keysByVersion.entrySet()) {
            if (entry.getValue() == null || entry.getValue().length == 0) {
                throw new IllegalArgumentException("Key for version '" + entry.getKey() + "' is null or empty");
            }
        }
    }

    public String activeVersion() {
        return activeVersion;
    }

    public byte[] activeKey() {
        return keysByVersion.get(activeVersion).clone();
    }

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

    public Set<String> knownVersions() {
        return Collections.unmodifiableSet(keysByVersion.keySet());
    }

    public KeyRing withNewActiveVersion(String newActiveVersion, byte[] newKey) {
        var newMap = new java.util.HashMap<>(keysByVersion);
        newMap.put(newActiveVersion, newKey.clone());
        return new KeyRing(newActiveVersion, newMap);
    }

    @Override
    public String toString() {
        return "KeyRing[activeVersion=" + activeVersion + ", versionCount=" + keysByVersion.size() + "]";
    }
}
