package com.miqroera.miqrokey.cache;

import com.miqroera.miqrokey.domain.usage.TokenBucket;

import java.util.List;
import java.util.Map;

/**
 * Immutable cached response: raw bytes plus the headers needed for exact
 * replay. SSE responses are stored and replayed byte-identically.
 *
 * <p>
 * {@code usage} records the ORIGINAL request's observed usage (stored in
 * cache_entry.meta_json) so the statistics service can value "saved by gateway
 * cache" at the same price table.
 * </p>
 */
public record CachedResponse(int statusCode, String contentType, Map<String, List<String>> headers, byte[] body,
        TokenBucket usage, boolean isComplete) {

    public CachedResponse {
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
