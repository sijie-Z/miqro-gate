package com.miqroera.miqrokey.spi;

import java.net.URI;
import java.util.Map;

/**
 * The fully validated upstream target produced by an adapter's {@code resolve}.
 * The gateway forwards to exactly this origin/path/query with exactly these
 * headers; arbitrary URLs are impossible because the origin always derives from
 * the route context's product base URL, never from user input.
 *
 * @param method
 *            HTTP method, uppercase
 * @param origin
 *            upstream origin (https)
 * @param path
 *            path to forward
 * @param query
 *            query string to forward (raw, without leading {@code ?}); empty
 *            when none
 * @param headers
 *            final header map (includes credential injection and stripped
 *            inbound auth headers; lowercase names)
 */
public record TargetRequest(String method, URI origin, String path, String query, Map<String, String> headers) {

    public TargetRequest {
        if (method == null || method.isBlank() || origin == null || path == null || query == null || headers == null) {
            throw new IllegalArgumentException("method/origin/path/query/headers must not be null or blank");
        }
        if (!"https".equalsIgnoreCase(origin.getScheme())) {
            throw new IllegalArgumentException("origin must use https, got " + origin);
        }
        if (origin.getRawUserInfo() != null) {
            throw new IllegalArgumentException("origin must not contain userinfo: " + origin);
        }
        headers = Map.copyOf(headers);
    }
}
