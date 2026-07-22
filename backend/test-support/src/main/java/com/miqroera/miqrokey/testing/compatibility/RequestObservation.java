package com.miqroera.miqrokey.testing.compatibility;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of metadata for a single HTTP request that reached the
 * compatibility mock.
 *
 * <h3>Retention contract</h3>
 * <p>
 * This record deliberately exposes <strong>no</strong> fields for request-body
 * bytes, prompts, tools, code, model output, cookies, API-key / token / header
 * values, or a general headers map. The API shape makes it impossible to store
 * any of those values.
 * </p>
 *
 * @param timestamp
 *            UTC wall-clock instant when the request was observed
 * @param requestId
 *            synthetic identifier unique within the current store lifetime
 * @param httpMethod
 *            HTTP method (e.g. {@code POST})
 * @param rawUri
 *            exact raw URI with query string, never decoded or normalised
 * @param protocol
 *            expected protocol inferred from the request path
 * @param contentType
 *            normalized media type (e.g. {@code "application/json"}), never the
 *            raw header value; empty string if absent or invalid
 * @param streamingRequest
 *            {@code true} if the request asked for a streaming response
 * @param forbiddenCredentialHeaderReached
 *            {@code true} if any header whose name is in the mock&rsquo;s
 *            forbidden-credential set appeared in this request
 */
public record RequestObservation(Instant timestamp, String requestId, String httpMethod, String rawUri,
        Protocol protocol, String contentType, boolean streamingRequest, boolean forbiddenCredentialHeaderReached) {

    /**
     * Compact canonical constructor with null-guards for required fields.
     */
    public RequestObservation {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(httpMethod, "httpMethod must not be null");
        Objects.requireNonNull(rawUri, "rawUri must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null (use empty string for absent)");
    }

    /**
     * Convenience factory for tests — uses {@link Instant#now()} as the timestamp.
     */
    public static RequestObservation of(String requestId, String httpMethod, String rawUri, Protocol protocol,
            String contentType, boolean streamingRequest, boolean forbiddenCredentialHeaderReached) {
        return new RequestObservation(Instant.now(), requestId, httpMethod, rawUri, protocol, contentType,
                streamingRequest, forbiddenCredentialHeaderReached);
    }
}
