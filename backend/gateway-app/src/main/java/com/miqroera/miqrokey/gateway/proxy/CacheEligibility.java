package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.vkey.AuthContext;

/**
 * Cache engagement decision (ADR-0008): the gateway cache is DISABLED by
 * default. A request is cacheable only when ALL of:
 * <ul>
 * <li>the virtual key's {@code cachePolicy} is {@code ENABLED},</li>
 * <li>the client explicitly declares cacheability via
 * {@code X-MiQroKey-Cacheable: 1},</li>
 * <li>the body does not reference tools (tool definitions change output),</li>
 * <li>the request has a non-empty body.</li>
 * </ul>
 *
 * <p>
 * Cached responses are served byte-identically (SSE included); tool-call
 * responses are never cached.
 * </p>
 */
public final class CacheEligibility {

    /** Explicit client opt-in header. */
    public static final String CACHEABLE_HEADER = "X-MiQroKey-Cacheable";

    private CacheEligibility() {
    }

    public static boolean isCacheable(AuthContext ctx, String cacheableHeaderValue, byte[] body,
            boolean hasToolFields) {
        return "ENABLED".equals(ctx.key().cachePolicy()) && "1".equals(cacheableHeaderValue) && !hasToolFields
                && body != null && body.length > 0;
    }
}
