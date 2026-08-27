package com.miqroera.miqrokey.testing;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Reactor Netty-based mock upstream provider for Anthropic Messages API.
 *
 * <h3>Request lifecycle</h3>
 * <p>
 * Each observed exchange is represented by a {@link RequestLifecycle} that
 * holds a monotonic {@link TerminationState} and a cancellation signal.
 * {@link #cancellationSignal()} and {@link #handleRequest} both obtain the
 * <em>same</em> instance via {@link #ensureLifecycle()}, so the caller always
 * waits on the correct sink.
 * </p>
 *
 * <pre>
 *   RUNNING ──┬──► COMPLETED   (response finishes before the channel closes)
 *             └──► CANCELLED   (channel closes / write is interrupted first)
 * </pre>
 *
 * <p>
 * Once a terminal state is entered it is never overwritten. Late callbacks from
 * a previous exchange cannot affect the current lifecycle because
 * {@link #configure} installs a new instance.
 * </p>
 */
public class AnthropicMockProvider implements AutoCloseable {

    public enum TerminationState {
        RUNNING, COMPLETED, CANCELLED
    }

    private final DisposableServer server;
    private final List<CapturedRequest> capturedRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<ResponseConfig> responseConfig = new AtomicReference<>();
    private final AtomicReference<RequestLifecycle> lifecycle = new AtomicReference<>();
    private final AtomicBoolean disconnectNext = new AtomicBoolean(false);
    private final AtomicBoolean disconnectAll = new AtomicBoolean(false);

    public AnthropicMockProvider() {
        this.server = HttpServer.create().port(0).handle(this::handleRequest).bindNow();
    }

    public int getPort() {
        return server.port();
    }

    public String getBaseUrl() {
        return "http://localhost:" + getPort();
    }

    // -------------------------------------------------------------------
    // Public test-facing API
    // -------------------------------------------------------------------

    public void configure(ResponseConfig config) {
        this.responseConfig.set(config);
        this.lifecycle.set(null);
    }

    public List<CapturedRequest> getCapturedRequests() {
        return Collections.unmodifiableList(new ArrayList<>(capturedRequests));
    }

    public boolean wasUpstreamCancelled() {
        RequestLifecycle lc = lifecycle.get();
        return lc != null && lc.terminationState() == TerminationState.CANCELLED;
    }

    /**
     * Simulates a connection-phase failure for the next request only: the accepted
     * channel is closed immediately, before any response is sent. The gateway
     * observes an EOF before the first response byte, which is exactly the failure
     * G2.5 retries once.
     */
    public void disconnectNextRequest() {
        disconnectNext.set(true);
    }

    /**
     * Simulates a persistent outage: every request's channel is closed immediately,
     * until {@link #reset()}.
     */
    public void disconnectAllRequests() {
        disconnectAll.set(true);
    }

    public void reset() {
        capturedRequests.clear();
        lifecycle.set(null);
        responseConfig.set(null);
        disconnectNext.set(false);
        disconnectAll.set(false);
    }

    public Mono<Void> cancellationSignal() {
        return ensureLifecycle().cancellationSignal();
    }

    @Override
    public void close() {
        if (!server.isDisposed()) {
            server.disposeNow();
        }
    }

    // -------------------------------------------------------------------
    // Request handling
    // -------------------------------------------------------------------

    private Mono<Void> handleRequest(HttpServerRequest req, HttpServerResponse res) {
        if (disconnectAll.get() || disconnectNext.compareAndSet(true, false)) {
            req.withConnection(conn -> conn.channel().close());
            return Mono.never();
        }

        CapturedRequest captured = new CapturedRequest();
        captured.method = req.method().name();
        captured.path = req.uri();
        captured.headers = new ArrayList<>();
        req.requestHeaders().forEach(e -> captured.headers.add(Map.entry(e.getKey(), e.getValue())));

        RequestLifecycle lc = ensureLifecycle();

        req.withConnection(conn -> conn.channel().closeFuture().addListener(ignored -> lc.markCancelled()));

        return req.receive().aggregate().asByteArray().defaultIfEmpty(new byte[0]).doOnNext(body -> {
            captured.bodyBytes = body.clone();
            captured.body = new String(body, StandardCharsets.UTF_8);
            capturedRequests.add(captured);
        }).then(Mono.defer(() -> sendResponse(res, lc)));
    }

    // -------------------------------------------------------------------
    // Response sending
    // -------------------------------------------------------------------

    private Mono<Void> sendResponse(HttpServerResponse res, RequestLifecycle lc) {
        ResponseConfig config = responseConfig.get();
        if (config == null) {
            res.status(500);
            return finalizeResponse(res.sendString(Mono.just("{\"error\":\"No mock response configured\"}")).then(),
                    lc);
        }

        res.status(config.statusCode);
        if (config.contentType != null) {
            res.header("Content-Type", config.contentType);
        }
        config.responseHeaders.forEach(entry -> res.header(entry.getKey(), entry.getValue()));

        if (config.bodySupplier == null) {
            return finalizeResponse(res.sendHeaders().then(), lc);
        }

        Mono<Void> response;
        if (config.streaming) {
            response = sendStreamingResponse(res, config);
        } else {
            String body = config.bodySupplier.get();
            response = res.sendString(Mono.just(body)).then();
        }
        if (config.responseDelay != null && !config.responseDelay.isZero()) {
            // Simulates a slow upstream: the response headers (and body) only
            // arrive after the delay — used to exercise the first-byte timeout.
            response = Mono.delay(config.responseDelay).then(response);
        }
        return finalizeResponse(response, lc);
    }

    /**
     * Delegates to {@link RequestLifecycle#finalize(SignalType)} so both successful
     * completion and write failures are observed through the same CAS logic.
     */
    private static Mono<Void> finalizeResponse(Mono<Void> response, RequestLifecycle lc) {
        return response.doFinally(lc::finalize);
    }

    private Mono<Void> sendStreamingResponse(HttpServerResponse res, ResponseConfig config) {
        String fullBody = config.bodySupplier.get();

        if (config.utf8SplitChunks) {
            return sendUtf8SplitChunks(res, fullBody, config.chunkDelay);
        }

        if ((config.chunkDelay == null || config.chunkDelay.isZero()) && config.haltAfterLines == 0) {
            // Single write of the raw body: contract tests assert the stream
            // byte-for-byte, and a line-rebuild of a body ending in "\n" would
            // append a phantom trailing newline.
            return res.sendString(Mono.just(fullBody)).then();
        }

        Flux<String> lines = Flux.fromArray(fullBody.split("\n", -1)).map(line -> line + "\n");
        if (config.haltAfterLines > 0) {
            // Sends the first N lines then stalls forever — exercises the
            // gateway's stream-idle timeout without completing the response.
            lines = lines.take(config.haltAfterLines).concatWith(Flux.never());
        }

        if (config.chunkDelay != null && !config.chunkDelay.isZero()) {
            lines = lines.delayElements(config.chunkDelay);
        }
        return res.sendString(lines).then();
    }

    private Mono<Void> sendUtf8SplitChunks(HttpServerResponse res, String body, Duration delay) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        List<byte[]> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < bytes.length) {
            int chunkSize = Math.min(7, bytes.length - pos);
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(bytes, pos, chunk, 0, chunkSize);
            chunks.add(chunk);
            pos += chunkSize;
        }

        Flux<ByteBuf> byteBufFlux = Flux.fromIterable(chunks).map(Unpooled::wrappedBuffer);
        if (delay != null && !delay.isZero()) {
            byteBufFlux = byteBufFlux.delayElements(delay);
        }

        return res.send(byteBufFlux).then();
    }

    // -------------------------------------------------------------------
    // Lifecycle management
    // -------------------------------------------------------------------

    private RequestLifecycle ensureLifecycle() {
        RequestLifecycle existing = lifecycle.get();
        if (existing != null) {
            return existing;
        }
        RequestLifecycle created = new RequestLifecycle();
        if (lifecycle.compareAndSet(null, created)) {
            return created;
        }
        return lifecycle.get();
    }

    // -------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------

    public static class CapturedRequest {
        public String method;
        public String path;
        public List<Map.Entry<String, String>> headers;
        public String body;
        public byte[] bodyBytes;

        public String header(String name) {
            return headers.stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst()
                    .orElse(null);
        }

        public List<String> headers(String name) {
            return headers.stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).toList();
        }
    }

    public static class ResponseConfig {
        final int statusCode;
        final String contentType;
        final List<Map.Entry<String, String>> responseHeaders;
        final BodySupplier bodySupplier;
        final boolean streaming;
        final Duration chunkDelay;
        final boolean utf8SplitChunks;
        final Duration responseDelay;
        final int haltAfterLines;

        private ResponseConfig(Builder builder) {
            this.statusCode = builder.statusCode;
            this.contentType = builder.contentType;
            this.responseHeaders = List.copyOf(builder.responseHeaders);
            this.bodySupplier = builder.bodySupplier;
            this.streaming = builder.streaming;
            this.chunkDelay = builder.chunkDelay;
            this.utf8SplitChunks = builder.utf8SplitChunks;
            this.responseDelay = builder.responseDelay;
            this.haltAfterLines = builder.haltAfterLines;
        }

        public static Builder builder() {
            return new Builder();
        }

        @FunctionalInterface
        public interface BodySupplier {
            String get();
        }

        public static class Builder {
            int statusCode = 200;
            String contentType;
            final List<Map.Entry<String, String>> responseHeaders = new ArrayList<>();
            BodySupplier bodySupplier;
            boolean streaming;
            Duration chunkDelay;
            boolean utf8SplitChunks;
            Duration responseDelay;
            int haltAfterLines;

            public Builder statusCode(int statusCode) {
                this.statusCode = statusCode;
                return this;
            }

            public Builder contentType(String contentType) {
                this.contentType = contentType;
                return this;
            }

            public Builder header(String name, String value) {
                this.responseHeaders.add(Map.entry(name, value));
                return this;
            }

            public Builder body(String body) {
                this.bodySupplier = () -> body;
                return this;
            }

            public Builder bodySupplier(BodySupplier supplier) {
                this.bodySupplier = supplier;
                return this;
            }

            public Builder streaming(boolean streaming) {
                this.streaming = streaming;
                return this;
            }

            public Builder chunkDelay(Duration delay) {
                this.chunkDelay = delay;
                return this;
            }

            public Builder utf8SplitChunks(boolean split) {
                this.utf8SplitChunks = split;
                return this;
            }

            /** Delays the entire response (headers included) by this duration. */
            public Builder responseDelay(Duration delay) {
                this.responseDelay = delay;
                return this;
            }

            /** Streaming only: send the first N lines, then stall forever. */
            public Builder haltAfterLines(int lines) {
                this.haltAfterLines = lines;
                return this;
            }

            public ResponseConfig build() {
                if (streaming && contentType == null) {
                    this.contentType = "text/event-stream";
                }
                return new ResponseConfig(this);
            }
        }
    }
}
