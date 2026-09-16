package com.miqroera.miqrokey.gateway.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway-side context-limit pre-check configuration (#553).
 *
 * <p>
 * The gateway is a transparent proxy: it does not tokenize the conversation and
 * it never re-writes the request body. The pre-check therefore measures the
 * buffered request body itself and rejects the request <em>before</em> any
 * upstream connection is attempted, turning a slow, opaque provider 4xx into an
 * immediate, protocol-compatible gateway error.
 * </p>
 *
 * <p>
 * The default threshold is deliberately generous — it is a safety valve against
 * runaway contexts, not a quota. It is an upper bound on the context character
 * count: the measurement covers the whole serialized body (JSON structure, tool
 * schemas and inline base64 payloads included), so it over-counts rather than
 * under-counts. A threshold above
 * {@code miqrokey.gateway.upstream.max-proxy-buffer} never fires — the buffer
 * bound rejects first with {@code payload_too_large}. See
 * configuration-reference §5.
 * </p>
 *
 * <p>
 * The limit is global (one value per gateway process). Per-Virtual-Key limits
 * would require a database column and a route-snapshot field, and are a
 * follow-up to #553.
 * </p>
 *
 * @param enabled whether the pre-check runs at all; {@code null} means true
 * @param thresholdChars body character budget; {@code null} or non-positive
 * means {@value #DEFAULT_THRESHOLD_CHARS}
 */
@ConfigurationProperties(prefix = "miqrokey.gateway.context-limit")
public record ContextLimitProperties(Boolean enabled, Integer thresholdChars) {

    /** Applied when {@code threshold-chars} is absent or non-positive. */
    public static final int DEFAULT_THRESHOLD_CHARS = 200_000;

    public ContextLimitProperties {
        enabled = enabled == null || enabled;
        thresholdChars = thresholdChars == null || thresholdChars <= 0 ? DEFAULT_THRESHOLD_CHARS : thresholdChars;
    }
}
