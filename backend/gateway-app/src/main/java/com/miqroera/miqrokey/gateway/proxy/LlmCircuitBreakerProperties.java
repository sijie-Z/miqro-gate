package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.domain.model.CircuitBreakerPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * LLM-side circuit breaker configuration (#741).
 *
 * <p>
 * The model-forwarding path previously had no breaker: a product/credential in
 * a sustained failure loop kept receiving every request (one pre-first-byte
 * retry plus a full timeout each). The MCP tool-call path solved the same
 * problem years-of-code earlier (F13, {@code McpCircuitBreakerRegistry}); this
 * reuses that state machine verbatim through {@link CircuitBreakerPolicy} and
 * keeps the defaults identical.
 * </p>
 *
 * <p>
 * <b>Default off.</b> When {@code enabled} is false the breaker is never
 * consulted and every counter stays zero — behavior is exactly the pre-#741
 * code. Thresholds, status set and probe counts follow the Tencent-tuned F13
 * defaults. The error trigger is always armed (the slow-call trigger is the
 * optional extra, off by default); this keeps the shared state machine's "at
 * least one trigger enabled" invariant structurally true.
 * </p>
 */
@ConfigurationProperties(prefix = "miqrokey.gateway.circuit-breaker")
public record LlmCircuitBreakerProperties(Boolean enabled, Integer windowSeconds, Integer minRequests,
        Integer errorRatio, Set<Integer> errorStatusCodes, Boolean slowEnabled, Integer slowCallMs, Integer slowRatio,
        Integer openSeconds, Integer probeCount, Integer probeSuccess) implements CircuitBreakerPolicy {

    /** Same defaults as {@code McpResiliencePolicy} (Tencent F13 tuning). */
    public static final int DEFAULT_WINDOW_SECONDS = 10;
    public static final int DEFAULT_MIN_REQUESTS = 10;
    public static final int DEFAULT_ERROR_RATIO = 50;
    /**
     * Upstream statuses counted as errors: server faults and bad-gateway shapes.
     */
    public static final Set<Integer> DEFAULT_ERROR_STATUS_CODES = Set.of(500, 502, 503, 504);
    public static final int DEFAULT_SLOW_CALL_MS = 3000;
    public static final int DEFAULT_SLOW_RATIO = 80;
    public static final int DEFAULT_OPEN_SECONDS = 30;
    public static final int DEFAULT_PROBE_COUNT = 3;
    public static final int DEFAULT_PROBE_SUCCESS = 2;

    public LlmCircuitBreakerProperties {
        enabled = enabled != null && enabled;
        windowSeconds = positiveOr(windowSeconds, DEFAULT_WINDOW_SECONDS);
        minRequests = positiveOr(minRequests, DEFAULT_MIN_REQUESTS);
        errorRatio = boundedPercent(errorRatio, DEFAULT_ERROR_RATIO);
        errorStatusCodes = errorStatusCodes == null || errorStatusCodes.isEmpty()
                ? DEFAULT_ERROR_STATUS_CODES
                : Set.copyOf(errorStatusCodes);
        slowEnabled = slowEnabled != null && slowEnabled;
        slowCallMs = positiveOr(slowCallMs, DEFAULT_SLOW_CALL_MS);
        slowRatio = boundedPercent(slowRatio, DEFAULT_SLOW_RATIO);
        openSeconds = positiveOr(openSeconds, DEFAULT_OPEN_SECONDS);
        probeCount = positiveOr(probeCount, DEFAULT_PROBE_COUNT);
        probeSuccess = positiveOr(probeSuccess, DEFAULT_PROBE_SUCCESS);
    }

    private static int positiveOr(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static int boundedPercent(Integer value, int fallback) {
        return value == null || value < 1 || value > 100 ? fallback : value;
    }

    // CircuitBreakerPolicy: the error trigger is structurally always armed.
    @Override
    public boolean breakerEnabled() {
        return enabled;
    }

    @Override
    public int breakerWindowSeconds() {
        return windowSeconds;
    }

    @Override
    public int breakerMinRequests() {
        return minRequests;
    }

    @Override
    public boolean breakerErrorEnabled() {
        return true;
    }

    @Override
    public int breakerErrorRatio() {
        return errorRatio;
    }

    @Override
    public Set<Integer> breakerErrorStatusCodes() {
        return errorStatusCodes;
    }

    @Override
    public boolean breakerSlowEnabled() {
        return slowEnabled;
    }

    @Override
    public int breakerSlowCallMs() {
        return slowCallMs;
    }

    @Override
    public int breakerSlowRatio() {
        return slowRatio;
    }

    @Override
    public int breakerOpenSeconds() {
        return openSeconds;
    }

    @Override
    public int breakerProbeCount() {
        return probeCount;
    }

    @Override
    public int breakerProbeSuccess() {
        return probeSuccess;
    }
}
