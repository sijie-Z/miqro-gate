package com.miqroera.miqrokey.gateway.proxy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Observation-only classification of upstream error bodies (ADR-0024 option B,
 * issue #770).
 *
 * <p>
 * The observation tier exists to answer one question from real traffic before
 * anything is built on top of it: are "thinking signature"-shaped rejections a
 * stable, repeating pattern for a given key, or occasional noise? So this
 * component <strong>counts and logs the shape</strong> of a non-2xx upstream
 * body — it never retries, never rewrites the request, never changes the
 * response. The request bytes on the wire are exactly what they were before.
 * </p>
 *
 * <p>
 * Reading, not storing: the body is inspect-only, capped at
 * {@link #MAX_CLASSIFY_BYTES}, and never logged, persisted, or attached to an
 * event — same line the usage parser already holds. A body the buffer had to
 * truncate is not classified at all (a partial match could label a request from
 * a fragment).
 * </p>
 *
 * <p>
 * The pattern list is deliberately small and documented: unmatched bodies count
 * as {@code UNCLASSIFIED}, so the long tail stays visible and the list can be
 * tuned from counted evidence instead of guesswork. Classes are a bounded enum,
 * which is why they are safe as a metric tag (bounded label values, per the
 * high-cardinality rule in {@code GatewayMetricsFilter}); the HTTP status is
 * only ever a log field, never a tag.
 * </p>
 */
@Component
public class UpstreamErrorClassifier {

    /** Error bodies are small; anything beyond this prefix is not classified. */
    static final int MAX_CLASSIFY_BYTES = 8 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamErrorClassifier.class);

    /** Bounded class vocabulary (ADR-0024 §3-B), safe as a metric tag. */
    public enum ErrorClass {
        SIGNATURE_INVALID, THINKING_BLOCK_MISMATCH, MISSING_SIGNATURE, BUDGET_INVALID, UNCLASSIFIED
    }

    private final MeterRegistry registry;

    public UpstreamErrorClassifier(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Classify a non-2xx upstream body and count it. Returns the class so callers
     * (and tests) can assert on it.
     */
    public ErrorClass observe(int status, byte[] body, boolean truncated) {
        ErrorClass errorClass = classify(body, truncated);
        Counter.builder("miqrokey_gateway_upstream_error_class_total").tag("class", errorClass.name())
                .description("Upstream non-2xx responses by body shape (ADR-0024 option B, observation only)")
                .register(registry).increment();
        // Status lives in the log line, not in a tag: an upstream could answer any
        // integer code, and tags must stay bounded.
        LOG.info("upstream error observed: status={} class={}", status, errorClass);
        return errorClass;
    }

    /**
     * Substring match over the decoded prefix. Ordered most-specific first; the
     * phrases are the ones the upstream protocol uses in practice ("Invalid
     * `signature` in `thinking` block", "Expected `thinking` or
     * `redacted_thinking`, but found `tool_use`", budget complaints mentioning
     * {@code budget_tokens}).
     */
    static ErrorClass classify(byte[] body, boolean truncated) {
        if (body == null || body.length == 0 || truncated) {
            return ErrorClass.UNCLASSIFIED;
        }
        String text = new String(body, 0, Math.min(body.length, MAX_CLASSIFY_BYTES), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if (text.contains("invalid `signature`") || text.contains("invalid signature")) {
            return ErrorClass.SIGNATURE_INVALID;
        }
        if (text.contains("budget_tokens")) {
            return ErrorClass.BUDGET_INVALID;
        }
        if (text.contains("expected `thinking`") || text.contains("expected thinking")) {
            return ErrorClass.THINKING_BLOCK_MISMATCH;
        }
        if (text.contains("signature") && (text.contains("required") || text.contains("missing"))) {
            return ErrorClass.MISSING_SIGNATURE;
        }
        return ErrorClass.UNCLASSIFIED;
    }
}
