package com.miqroera.miqrokey.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic unit tests for the {@link RequestLifecycle} monotonic
 * termination state machine — no sockets, no threads, no delays.
 */
@DisplayName("RequestLifecycle state machine")
class RequestLifecycleTest {

    @Test
    @DisplayName("markCancelled then markCompleted: final state remains CANCELLED")
    void markCancelledThenMarkCompletedStaysCancelled() {
        RequestLifecycle lc = new RequestLifecycle();

        lc.markCancelled();
        lc.markCompleted();

        assertThat(lc.terminationState()).isEqualTo(AnthropicMockProvider.TerminationState.CANCELLED);
    }

    @Test
    @DisplayName("markCompleted then markCancelled: final state remains COMPLETED")
    void markCompletedThenMarkCancelledStaysCompleted() {
        RequestLifecycle lc = new RequestLifecycle();

        lc.markCompleted();
        lc.markCancelled();

        assertThat(lc.terminationState()).isEqualTo(AnthropicMockProvider.TerminationState.COMPLETED);
    }

    @Test
    @DisplayName("subscribe then markCancelled: the returned signal completes")
    void subscribeThenMarkCancelledSignalCompletes() {
        RequestLifecycle lc = new RequestLifecycle();

        AtomicBoolean completed = new AtomicBoolean(false);
        lc.cancellationSignal().doFinally(s -> completed.set(true)).subscribe();
        lc.markCancelled();

        assertThat(completed.get()).as("cancellation signal must complete when markCancelled is called").isTrue();
    }

    @Test
    @DisplayName("subscribe then markCompleted: the signal never completes")
    void subscribeThenMarkCompletedSignalDoesNotComplete() {
        RequestLifecycle lc = new RequestLifecycle();

        AtomicBoolean completed = new AtomicBoolean(false);
        lc.cancellationSignal().doFinally(s -> completed.set(true)).subscribe();

        lc.markCompleted();

        assertThat(completed.get()).as("cancellation signal must not complete when only markCompleted is called")
                .isFalse();
    }

    @Test
    @DisplayName("repeated markCancelled is idempotent")
    void repeatedMarkCancelledIsIdempotent() {
        RequestLifecycle lc = new RequestLifecycle();

        lc.markCancelled();
        lc.markCancelled();
        lc.markCancelled();

        assertThat(lc.terminationState()).isEqualTo(AnthropicMockProvider.TerminationState.CANCELLED);
    }

    @Test
    @DisplayName("repeated markCompleted is idempotent")
    void repeatedMarkCompletedIsIdempotent() {
        RequestLifecycle lc = new RequestLifecycle();

        lc.markCompleted();
        lc.markCompleted();

        assertThat(lc.terminationState()).isEqualTo(AnthropicMockProvider.TerminationState.COMPLETED);
    }

    @Test
    @DisplayName("finalize ON_COMPLETE calls markCompleted")
    void finalizeOnCompleteCallsMarkCompleted() {
        RequestLifecycle lc = new RequestLifecycle();

        lc.finalize(SignalType.ON_COMPLETE);

        assertThat(lc.terminationState()).isEqualTo(AnthropicMockProvider.TerminationState.COMPLETED);
    }

    @Test
    @DisplayName("finalize ON_ERROR calls markCancelled")
    void finalizeOnErrorCallsMarkCancelled() {
        RequestLifecycle lc = new RequestLifecycle();

        lc.finalize(SignalType.ON_ERROR);

        assertThat(lc.terminationState()).isEqualTo(AnthropicMockProvider.TerminationState.CANCELLED);
    }

    @Test
    @DisplayName("finalize CANCEL calls markCancelled")
    void finalizeCancelCallsMarkCancelled() {
        RequestLifecycle lc = new RequestLifecycle();

        lc.finalize(SignalType.CANCEL);

        assertThat(lc.terminationState()).isEqualTo(AnthropicMockProvider.TerminationState.CANCELLED);
    }

    @Test
    @DisplayName("initial state is RUNNING")
    void initialStateIsRunning() {
        RequestLifecycle lc = new RequestLifecycle();

        assertThat(lc.terminationState()).isEqualTo(AnthropicMockProvider.TerminationState.RUNNING);
    }
}
