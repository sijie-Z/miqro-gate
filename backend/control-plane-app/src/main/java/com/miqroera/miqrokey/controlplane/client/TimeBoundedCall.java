package com.miqroera.miqrokey.controlplane.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Wall-clock bound for an outbound call (PH57 — the same shape #1226 fixed for
 * alert webhook delivery): {@code HttpRequest.timeout(..)} bounds only the wait
 * for the response <em>headers</em>. A peer that answers {@code 200} with a
 * {@code Content-Length} and then stops writing leaves the body read blocked
 * with no bound at all, however small the configured timeout is — and on a
 * shared scheduler thread that drags unrelated work down with it.
 *
 * <p>
 * Both entry points here bound the <em>whole</em> call — connect, write, read
 * the headers, read the body — by a single budget, and surface expiry as an
 * {@link HttpTimeoutException} so the callers' existing “响应超时” branches keep
 * working.
 * </p>
 */
public final class TimeBoundedCall {

    /**
     * Body reads of {@link #run} happen here rather than on the caller's thread.
     * Virtual threads so an abandoned call is cheap, and — unlike a platform socket
     * read — an interrupt from {@link Future#cancel(boolean)} closes the connection
     * instead of leaving the thread parked forever.
     */
    private static final ExecutorService READERS = Executors
            .newThreadPerTaskExecutor(Thread.ofVirtual().name("outbound-call-", 0).factory());

    private TimeBoundedCall() {
    }

    /**
     * Sends {@code request} and returns the response once its <em>body</em> has
     * been read, waiting at most {@code budget} for the exchange to finish.
     *
     * @throws HttpTimeoutException
     *             if the peer did not finish within {@code budget}
     */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, Duration budget,
            HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        var future = client.sendAsync(request, handler);
        try {
            return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException(
                    "Request timed out after " + budget.toMillis() + " ms: the peer did not finish its response body");
        } catch (ExecutionException e) {
            // Unwrap, so a header-phase timeout still arrives as the JDK's own
            // HttpTimeoutException and any transport failure as its IOException.
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Outbound request failed", cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Runs {@code operation} on its own interruptible thread and returns within
     * {@code budget}. For callers that keep reading the response stream themselves
     * — {@link #send} cannot bound a body it has already handed over.
     *
     * @param onTimeout
     *            builds the caller's own failure for an expired budget
     */
    public static <T> T run(Duration budget, String description, Callable<T> operation,
            Function<Duration, RuntimeException> onTimeout) throws InterruptedException {
        Future<T> task = READERS.submit(operation);
        try {
            return task.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw onTimeout.apply(budget);
        } catch (InterruptedException e) {
            task.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(description + " failed", cause);
        }
    }
}
