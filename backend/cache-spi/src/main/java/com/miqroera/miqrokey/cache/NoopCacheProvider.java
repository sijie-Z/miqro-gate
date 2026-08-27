package com.miqroera.miqrokey.cache;

import com.miqroera.miqrokey.domain.cache.CacheKey;

import java.util.UUID;

/**
 * Null-object cache used when L2 is disabled but L1 is enabled — or when the
 * whole cache subsystem is off. Never throws; always reports a miss.
 */
public final class NoopCacheProvider implements GatewayResponseCache {

    @Override
    public Lookup get(UUID tenantId, CacheKey key) {
        return Lookup.miss();
    }

    @Override
    public void put(CacheKey key, UUID tenantId, UUID virtualKeyId, UUID projectId, UUID productId, String modelId,
            CachedResponse response) {
        // no-op
    }

    @Override
    public void invalidateProject(UUID tenantId, UUID projectId) {
        // no-op
    }

    @Override
    public long l1Size() {
        return 0;
    }
}
