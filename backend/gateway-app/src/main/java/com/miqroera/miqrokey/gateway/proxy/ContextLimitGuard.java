package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Request pre-check for oversized contexts (#553).
 *
 * <p>
 * Rejects a request whose buffered body exceeds the configured character budget
 * <em>before</em> any upstream connection is attempted. The check is a pure
 * read of the already-buffered bytes: it never parses, re-serializes, reorders
 * or truncates the request, and the body that is forwarded for an accepted
 * request is byte-identical to what arrived.
 * </p>
 *
 * <p>
 * The measurement is an upper bound on the conversation size — the whole
 * serialized body is counted, so JSON structure, tool schemas and any inline
 * base64 content are included. That is deliberate: over-counting can only make
 * the gateway reject slightly earlier than the provider would, while
 * under-counting would let the gateway promise a request it cannot deliver.
 * </p>
 *
 * <p>
 * On a hit, the request is logged with the request id, the path, the measured
 * size and the threshold. The body itself is never logged (project rule: no
 * prompt, code or tool-body content in logs).
 * </p>
 */
@Component
public class ContextLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(ContextLimitGuard.class);

    private final ContextLimitProperties properties;
    private final Counter rejected;

    public ContextLimitGuard(ContextLimitProperties properties, MeterRegistry registry) {
        this.properties = properties;
        // Zero-labelled by construction: the metric records that the guard fired,
        // never who fired it or with what content (configuration-reference §8
        // forbids user / key / model / body values as metric labels).
        this.rejected = Counter.builder("miqrokey_gateway_context_limit_rejected_total")
                .description("Requests rejected by the gateway context-limit pre-check").register(registry);
    }

    /**
     * @param body the buffered request body
     * @param path the inbound request path ({@code /v1/messages} etc.), used for
     * the log line and — downstream — for the protocol-compatible envelope
     * @param requestId the gateway request id
     * @return the rejection to write, or {@code null} when the request may
     * proceed to the upstream
     */
    public AuthFailureException check(byte[] body, String path, String requestId) {
        if (!properties.enabled()) {
            return null;
        }
        int chars = characters(body);
        int limit = properties.thresholdChars();
        if (chars <= limit) {
            return null;
        }
        rejected.increment();
        log.warn("context_limit_exceeded: requestId={}, path={}, bodyChars={}, thresholdChars={}", requestId, path,
                chars, limit);
        return new AuthFailureException(HttpStatus.PAYLOAD_TOO_LARGE, "context_limit_exceeded",
                "Request body exceeds the gateway context limit: " + chars + " characters (limit " + limit + ")");
    }

    /**
     * Number of Unicode code points in a UTF-8 byte sequence.
     *
     * <p>
     * Allocation-free — this runs on the hot path. A byte that is not a
     * continuation byte ({@code 10xxxxxx}) starts a new code point, so the count
     * is exact for well-formed UTF-8. Bodies with invalid encoding still yield a
     * stable, monotonically increasing count, which is all the guard needs.
     * </p>
     */
    static int characters(byte[] body) {
        int count = 0;
        for (byte b : body) {
            if ((b & 0xC0) != 0x80) {
                count++;
            }
        }
        return count;
    }
}
