package com.miqroera.miqrokey.domain.cache;

import java.util.Arrays;

/**
 * Cache key = hex SHA-256 of the normalized request identity. Computed by the
 * gateway (see CacheKeyFactory) and stored as bytea in cache_entry.
 *
 * <p>
 * The key is deliberately a digest, never the request body or any prompt
 * content.
 * </p>
 */
public record CacheKey(byte[] sha256) {

    public CacheKey {
        sha256 = sha256.clone();
        if (sha256.length != 32) {
            throw new IllegalArgumentException("cache key must be SHA-256 (32 bytes), got " + sha256.length);
        }
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    public String hex() {
        return java.util.HexFormat.of().formatHex(sha256);
    }

    public static CacheKey from(byte[] sha256) {
        return new CacheKey(sha256);
    }

    @Override
    public String toString() {
        return "CacheKey[" + hex() + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CacheKey that))
            return false;
        return Arrays.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(sha256);
    }
}
