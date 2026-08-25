package com.miqroera.miqrokey.spi;

import java.util.List;
import java.util.Map;

/**
 * Response of a {@link ProviderClient} exchange. The body is bounded to the
 * configured control-plane response limit; error bodies are kept raw for the
 * adapter to interpret (sanitization happens at the API boundary).
 *
 * @param statusCode
 *            HTTP status code
 * @param headers
 *            response headers (lowercase names, values immutable)
 * @param body
 *            response body bytes; may be empty
 */
public record ProviderResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {

    public ProviderResponse {
        if (headers == null || body == null) {
            throw new IllegalArgumentException("headers/body must not be null");
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

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
