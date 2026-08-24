package com.miqroera.miqrokey.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.miqroera.miqrokey.domain.cache.CacheKey;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * L1 in-memory cache (Caffeine). Sits in front of the L2 provider; on a miss it
 * asks L2 and populates itself. Expiry is time-based
 * ({@code miqrokey.cache.l1.ttl}, default 300s).
 *
 * <p>
 * The L1 only ever holds {@link CachedResponse} — never prompts, never model
 * content beyond the cached response bytes themselves.
 * </p>
 */
public final class CaffeineCacheProvider implements GatewayResponseCache {

    private final GatewayResponseCache delegate;
    private final Cache<CacheKey, CachedResponse> cache;

    public CaffeineCacheProvider(GatewayResponseCache delegate, Duration ttl) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(ttl).build();
    }

    @Override
    public Lookup get(UUID tenantId, CacheKey key) {
        CachedResponse hit = cache.getIfPresent(key);
        if (hit != null) {
            return new Lookup(Optional.of(hit), LookupLevel.L1_HIT);
        }
        Lookup l2 = delegate.get(tenantId, key);
        if (l2.response().isPresent()) {
            cache.put(key, l2.response().get());
        }
        return l2;
    }

    @Override
    public void put(CacheKey key, UUID tenantId, UUID virtualKeyId, UUID projectId, UUID productId, String modelId,
            CachedResponse response) {
        cache.put(key, response);
        delegate.put(key, tenantId, virtualKeyId, projectId, productId, modelId, response);
    }

    @Override
    public void invalidateProject(UUID tenantId, UUID projectId) {
        cache.invalidateAll();
        delegate.invalidateProject(tenantId, projectId);
    }

    @Override
    public long l1Size() {
        return cache.estimatedSize();
    }
}
