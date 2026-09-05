package com.miqroera.miqrokey.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12 retry gate decisions (doc 134831): condition selection, attempt cap,
 * disabled default and the non-idempotent POST/PUT/PATCH confirmation gate.
 */
@DisplayName("MCP retry gate")
class McpRetryPolicyTest {

    private McpResiliencePolicy retry(Set<McpResiliencePolicy.RetryCondition> conditions, int max,
            boolean idempotencyConfirmed) {
        return new McpResiliencePolicy(true, max, conditions, idempotencyConfirmed, false, 10, 10, true, 50,
                Set.of(500), false, 3000, 80, 30, 3, 2, true, 0);
    }

    @Test
    @DisplayName("only opted-in failure classes retry")
    void conditionSelection() {
        McpResiliencePolicy fiveXxOnly = retry(Set.of(McpResiliencePolicy.RetryCondition.SERVER_5XX), 1, true);
        assertThat(McpRetryPolicy.shouldRetry(fiveXxOnly, McpRetryPolicy.FailureKind.SERVER_5XX, "GET", 0)).isTrue();
        assertThat(McpRetryPolicy.shouldRetry(fiveXxOnly, McpRetryPolicy.FailureKind.CONNECTION_FAILURE, "GET", 0))
                .isFalse();
        assertThat(McpRetryPolicy.shouldRetry(fiveXxOnly, McpRetryPolicy.FailureKind.TIMEOUT, "GET", 0)).isFalse();

        McpResiliencePolicy all = retry(
                Set.of(McpResiliencePolicy.RetryCondition.SERVER_5XX, McpResiliencePolicy.RetryCondition.TIMEOUT), 1,
                true);
        assertThat(McpRetryPolicy.shouldRetry(all, McpRetryPolicy.FailureKind.TIMEOUT, "GET", 0)).isTrue();
        assertThat(McpRetryPolicy.shouldRetry(all, McpRetryPolicy.FailureKind.CONNECTION_FAILURE, "GET", 0)).isFalse();
    }

    @Test
    @DisplayName("attempt cap and disabled default")
    void attemptCapAndDisabled() {
        McpResiliencePolicy retries = retry(Set.of(McpResiliencePolicy.RetryCondition.SERVER_5XX), 2, true);
        assertThat(McpRetryPolicy.shouldRetry(retries, McpRetryPolicy.FailureKind.SERVER_5XX, "GET", 0)).isTrue();
        assertThat(McpRetryPolicy.shouldRetry(retries, McpRetryPolicy.FailureKind.SERVER_5XX, "GET", 1)).isTrue();
        assertThat(McpRetryPolicy.shouldRetry(retries, McpRetryPolicy.FailureKind.SERVER_5XX, "GET", 2)).isFalse();

        McpResiliencePolicy disabled = McpResiliencePolicy.disabled();
        assertThat(McpRetryPolicy.shouldRetry(disabled, McpRetryPolicy.FailureKind.SERVER_5XX, "GET", 0)).isFalse();
    }

    @Test
    @DisplayName("non-idempotent tool methods require the explicit confirmation")
    void idempotencyGate() {
        McpResiliencePolicy confirmed = retry(Set.of(McpResiliencePolicy.RetryCondition.SERVER_5XX), 1, true);
        McpResiliencePolicy unconfirmed = retry(Set.of(McpResiliencePolicy.RetryCondition.SERVER_5XX), 1, false);

        for (String method : new String[]{"POST", "PUT", "PATCH"}) {
            assertThat(McpRetryPolicy.shouldRetry(confirmed, McpRetryPolicy.FailureKind.SERVER_5XX, method, 0))
                    .isTrue();
            assertThat(McpRetryPolicy.shouldRetry(unconfirmed, McpRetryPolicy.FailureKind.SERVER_5XX, method, 0))
                    .isFalse();
        }
        // GET/HEAD/OPTIONS/DELETE retry without confirmation; null method
        // (non tools/call envelopes) is treated as read-only too.
        for (String method : new String[]{"GET", "HEAD", "OPTIONS", "DELETE", null}) {
            assertThat(McpRetryPolicy.shouldRetry(unconfirmed, McpRetryPolicy.FailureKind.SERVER_5XX, method, 0))
                    .isTrue();
        }
        assertThat(McpRetryPolicy.isIdempotentHttpMethod("GET")).isTrue();
        assertThat(McpRetryPolicy.isIdempotentHttpMethod("post")).isFalse();
        assertThat(McpRetryPolicy.isIdempotentHttpMethod(null)).isTrue();
        assertThat(McpRetryPolicy.isServerError(503)).isTrue();
        assertThat(McpRetryPolicy.isServerError(429)).isFalse();
    }
}
