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
 * The measurement never under-counts: the whole serialized body is counted, so
 * JSON structure, tool schemas and any inline base64 content are included, and
 * a body that is not well-formed UTF-8 is measured in bytes rather than code
 * points (see {@link #characters(byte[])}). That is deliberate: over-counting
 * can only make the gateway reject slightly earlier than the provider would,
 * while under-counting would let the gateway promise a request it cannot
 * deliver.
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
     * @param body
     *            the buffered request body
     * @param path
     *            the inbound request path ({@code /v1/messages} etc.), used for the
     *            log line and — downstream — for the protocol-compatible envelope
     * @param requestId
     *            the gateway request id
     * @return the rejection to write, or {@code null} when the request may proceed
     *         to the upstream
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
     * Size of a request body in characters, never under-counted.
     *
     * <p>
     * For well-formed UTF-8 this is the exact Unicode code point count. A body that
     * is <em>not</em> well-formed UTF-8 — a stray continuation byte, a truncated
     * sequence, an overlong encoding, a surrogate — falls back to its byte length.
     * That fallback is deliberately pessimistic: a lenient decoder emits at most
     * one replacement character per byte, so the byte length is an upper bound on
     * the character count of <em>any</em> decoder, and the measured value can never
     * be smaller than what the provider will see. A malformed body is not valid
     * JSON and would be rejected upstream anyway; the fallback only keeps a broken
     * client from walking past the threshold.
     * </p>
     *
     * <p>
     * Allocation-free — this runs on the hot path on the already-buffered bytes,
     * single pass, no decoding.
     * </p>
     */
    static int characters(byte[] body) {
        int codePoints = strictCodePointCount(body);
        return codePoints >= 0 ? codePoints : body.length;
    }

    /**
     * Code points in a strictly well-formed UTF-8 sequence, or {@code -1} when the
     * sequence is malformed. Mirrors the well-formedness rules of
     * {@code StandardCharsets.UTF_8.newDecoder()} with
     * {@link java.nio.charset.CodingErrorAction#REPORT}: overlong encodings
     * ({@code C0}, {@code C1}, {@code E0 80}, {@code F0 80}), UTF-16 surrogates
     * ({@code ED A0}–{@code ED BF}) and code points above {@code U+10FFFF}
     * ({@code F4 90}–{@code F4 BF}, {@code F5}–{@code FF}) are all rejected.
     */
    private static int strictCodePointCount(byte[] body) {
        int count = 0;
        int i = 0;
        while (i < body.length) {
            int lead = body[i] & 0xFF;
            if (lead < 0x80) {
                count++;
                i++;
                continue;
            }
            int trailing;
            int low = 0x80;
            int high = 0xBF;
            if (lead >= 0xC2 && lead <= 0xDF) {
                trailing = 1;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                trailing = 2;
                if (lead == 0xE0) {
                    low = 0xA0;
                } else if (lead == 0xED) {
                    high = 0x9F;
                }
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                trailing = 3;
                if (lead == 0xF0) {
                    low = 0x90;
                } else if (lead == 0xF4) {
                    high = 0x8F;
                }
            } else {
                return -1;
            }
            if (i + trailing >= body.length) {
                return -1;
            }
            int second = body[i + 1] & 0xFF;
            if (second < low || second > high) {
                return -1;
            }
            for (int k = 2; k <= trailing; k++) {
                int next = body[i + k] & 0xFF;
                if (next < 0x80 || next > 0xBF) {
                    return -1;
                }
            }
            count++;
            i += trailing + 1;
        }
        return count;
    }
}
