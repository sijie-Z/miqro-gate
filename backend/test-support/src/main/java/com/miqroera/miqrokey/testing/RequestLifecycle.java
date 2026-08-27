package com.miqroera.miqrokey.testing;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Monotonic terminal state and cancellation signal for one observed upstream
 * exchange inside {@link AnthropicMockProvider}.
 *
 * <pre>
 *   RUNNING ──┬──► COMPLETED   (response finishes before the channel closes)
 *             └──► CANCELLED   (channel closes / write is interrupted first)
 * </pre>
 *
 * <p>
 * Once a terminal state is entered it is never overwritten. Both the Netty
 * close-future listener and the response-{@code doFinally} callback delegate to
 * the same transition methods, so there is no duplicated CAS logic.
 * </p>
 */
final class RequestLifecycle {

    private final AtomicReference<AnthropicMockProvider.TerminationState> termination = new AtomicReference<>(
            AnthropicMockProvider.TerminationState.RUNNING);
    private final Sinks.One<Void> cancellationSink = Sinks.one();

    /**
     * Transitions to {@link AnthropicMockProvider.TerminationState#COMPLETED} only
     * if still RUNNING. If already CANCELLED the call is silently ignored.
     */
    void markCompleted() {
        termination.compareAndSet(AnthropicMockProvider.TerminationState.RUNNING,
                AnthropicMockProvider.TerminationState.COMPLETED);
    }

    /**
     * Transitions to {@link AnthropicMockProvider.TerminationState#CANCELLED} only
     * if still RUNNING, and emits the cancellation signal. If already COMPLETED the
     * call is silently ignored.
     */
    void markCancelled() {
        if (termination.compareAndSet(AnthropicMockProvider.TerminationState.RUNNING,
                AnthropicMockProvider.TerminationState.CANCELLED)) {
            cancellationSink.tryEmitEmpty();
        }
    }

    /**
     * Delegates to {@link #markCompleted()} or {@link #markCancelled()} based on
     * the Reactor signal type. Called from {@code doFinally}.
     */
    void finalize(SignalType signal) {
        if (signal == SignalType.ON_COMPLETE) {
            markCompleted();
        } else {
            markCancelled();
        }
    }

    /**
     * Returns the current termination state.
     */
    AnthropicMockProvider.TerminationState terminationState() {
        return termination.get();
    }

    /**
     * Returns a Mono that completes when {@link #markCancelled()} succeeds.
     */
    Mono<Void> cancellationSignal() {
        return cancellationSink.asMono();
    }
}
