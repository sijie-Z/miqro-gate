package com.miqroera.miqrokey.cache;

import com.miqroera.miqrokey.domain.cache.CacheKey;

import java.util.Optional;
import java.util.UUID;

/**
 * Two-level response cache. The gateway reads through L1 (in-memory) then L2
 * (PostgreSQL); a hit at any level is replayed byte-identically. Both levels
 * are tenant-scoped: the cache key is only unique within a tenant.
 *
 * <p>
 * The cache is DISABLED by default (ADR-0008): it is only engaged for virtual
 * keys with {@code cachePolicy=ENABLED} AND an explicit opt-in request header,
 * and only when the gateway's cache subsystem is enabled.
 * </p>
 */
public interface GatewayResponseCache {

    /** Outcome of a cache lookup. */
    enum LookupLevel {
        L1_HIT, L2_HIT, MISS
    }

    /** Lookup result carrying the level so callers can record hit events. */
    record Lookup(Optional<CachedResponse> response, LookupLevel level) {

        public static Lookup miss() {
            return new Lookup(Optional.empty(), LookupLevel.MISS);
        }
    }

    /**
     * Looks up a cached response (L1 first, then L2).
     *
     * @param tenantId
     *            owning tenant
     * @param key
     *            normalized request digest
     * @return the cached response if present and not expired
     */
    Lookup get(UUID tenantId, CacheKey key);

    /**
     * Stores a response. Updates hit counters on existing entries (L2) and
     * refreshes L1.
     */
    void put(CacheKey key, UUID tenantId, UUID virtualKeyId, UUID projectId, UUID productId, String modelId,
            CachedResponse response);

    /**
     * Removes all cached entries of a project (admin operation, audited on the
     * control plane).
     */
    void invalidateProject(UUID tenantId, UUID projectId);

    /**
     * Reports the current in-memory entry count (metrics).
     */
    long l1Size();
}
