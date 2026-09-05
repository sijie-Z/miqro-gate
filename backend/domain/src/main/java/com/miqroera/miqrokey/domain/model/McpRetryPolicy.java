package com.miqroera.miqrokey.domain.model;

import com.miqroera.miqrokey.domain.model.McpResiliencePolicy.RetryCondition;

import java.util.Objects;

/**
 * F12 retry gate decisions (Tencent doc 134831): pure functions over the
 * per-service retry policy and one upstream attempt result. The data plane
 * applies retries ONLY before the first response byte has been forwarded to the
 * caller, and only for the failure classes the policy opts into (SERVER_5XX /
 * CONNECTION_FAILURE / TIMEOUT).
 *
 * <p>
 * The non-idempotency guard follows the doc: for {@code tools/call} on a tool
 * whose HTTP method is POST/PUT/PATCH, retries are only allowed when the
 * operator explicitly confirmed the backend is idempotent. Other envelope
 * methods (initialize/tools/list/…) are treated as read-only and retryable.
 * </p>
 */
public final class McpRetryPolicy {

    private McpRetryPolicy() {
    }

    /** The failure classes a retry may be attempted for. */
    public enum FailureKind {
        SERVER_5XX, CONNECTION_FAILURE, TIMEOUT
    }

    /**
     * Whether a retry may be attempted for this failure.
     *
     * @param policy
     *            the enabled policy (callers check {@code retryEnabled} first)
     * @param failureKind
     *            what failed
     * @param toolHttpMethod
     *            the tool's registered HTTP method (null when the envelope is not a
     *            tools/call with a known tool)
     * @param attemptsUsed
     *            attempts already made (0-based)
     */
    public static boolean shouldRetry(McpResiliencePolicy policy, FailureKind failureKind, String toolHttpMethod,
            int attemptsUsed) {
        if (!policy.retryEnabled() || attemptsUsed >= policy.retryMax()) {
            return false;
        }
        RetryCondition condition = switch (failureKind) {
            case SERVER_5XX -> RetryCondition.SERVER_5XX;
            case CONNECTION_FAILURE -> RetryCondition.CONNECTION_FAILURE;
            case TIMEOUT -> RetryCondition.TIMEOUT;
        };
        if (!policy.retryConditions().contains(condition)) {
            return false;
        }
        if (toolHttpMethod != null && !isIdempotentHttpMethod(toolHttpMethod) && !policy.idempotencyConfirmed()) {
            // POST/PUT/PATCH tool call without the explicit idempotency
            // confirmation: retrying could duplicate a write.
            return false;
        }
        return true;
    }

    /** GET/HEAD/OPTIONS/DELETE are treated as idempotent (doc 134831). */
    public static boolean isIdempotentHttpMethod(String httpMethod) {
        if (httpMethod == null) {
            return true;
        }
        return switch (httpMethod.toUpperCase()) {
            case "GET", "HEAD", "OPTIONS", "DELETE" -> true;
            default -> false;
        };
    }

    /** Statuses that count as a {@link FailureKind#SERVER_5XX} retry trigger. */
    public static boolean isServerError(int status) {
        return status >= 500 && status <= 599;
    }

    /** Equality helper for tests and registry keying. */
    public static boolean samePolicy(McpResiliencePolicy a, McpResiliencePolicy b) {
        return Objects.equals(a, b);
    }
}
