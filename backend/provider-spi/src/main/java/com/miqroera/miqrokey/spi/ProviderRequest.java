package com.miqroera.miqrokey.spi;

import java.util.List;
import java.util.Map;

/**
 * A request the adapter sends to the provider through the gateway's
 * {@link ProviderClient} (used by control-plane operations: credential
 * validation, model catalog fetch, plan status). Bodies are small JSON
 * payloads; inference traffic never goes through this type.
 *
 * @param method
 *            HTTP method, uppercase
 * @param path
 *            path relative to the product base URL, starting with {@code /}
 * @param query
 *            query string without leading {@code ?}; empty when none
 * @param headers
 *            header map (lowercase names, values immutable)
 * @param body
 *            request body bytes; may be empty
 */
public record ProviderRequest(String method, String path, String query, Map<String, List<String>> headers,
        byte[] body) {

    public ProviderRequest {
        if (method == null || method.isBlank() || path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("method and absolute path are required");
        }
        if (query == null || headers == null || body == null) {
            throw new IllegalArgumentException("query/headers/body must not be null");
        }
        headers = Map.copyOf(headers);
        for (List<String> values : headers.values()) {
            List.copyOf(values);
        }
        body = body.clone();
    }

    /** @return a copy of the body; callers must not mutate the stored copy */
    public byte[] body() {
        return body.clone();
    }

    /** A GET request with no query and no headers. */
    public static ProviderRequest get(String path) {
        return new ProviderRequest("GET", path, "", Map.of(), new byte[0]);
    }

    /** A GET request with a query string (no leading {@code ?}). */
    public static ProviderRequest get(String path, String query) {
        return new ProviderRequest("GET", path, query, Map.of(), new byte[0]);
    }
}
