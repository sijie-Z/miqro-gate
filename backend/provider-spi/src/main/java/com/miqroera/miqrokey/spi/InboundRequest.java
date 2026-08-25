package com.miqroera.miqrokey.spi;

import java.util.List;
import java.util.Map;

/**
 * Immutable view of the inbound request as seen by an adapter's
 * {@link ProviderProductAdapter#resolve resolve} step. Header names are
 * case-insensitively normalized to lowercase; values are immutable lists.
 *
 * @param method
 *            HTTP method, uppercase
 * @param path
 *            decoded request path (no query string)
 * @param query
 *            query parameters, decoded, key → values (immutable)
 * @param headers
 *            all request headers except hop-by-hop and system-internal ones
 */
public record InboundRequest(String method, String path, Map<String, List<String>> query,
        Map<String, List<String>> headers) {

    public InboundRequest {
        if (method == null || method.isBlank() || path == null || headers == null || query == null) {
            throw new IllegalArgumentException("method/path/query/headers must not be null or blank");
        }
        query = Map.copyOf(query);
        headers = Map.copyOf(headers);
        for (List<String> values : query.values()) {
            List.copyOf(values);
        }
        for (List<String> values : headers.values()) {
            List.copyOf(values);
        }
    }
}
