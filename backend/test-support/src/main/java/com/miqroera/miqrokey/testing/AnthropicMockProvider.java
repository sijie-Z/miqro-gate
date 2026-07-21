package com.miqroera.miqrokey.testing;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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
 * <p>
 * Starts a real HTTP server on a random port. Configure response behavior
 * before each test using {@link #configure(ResponseConfig)}. Captures all
 * received requests for later assertion. Detects client cancellation via
 * connection dispose signals.
 * </p>
 *
 * <p>
 * Always call {@link #close()} (or use try-with-resources) to release the port.
 * </p>
 */
public class AnthropicMockProvider implements AutoCloseable {

    private final DisposableServer server;
    private final List<CapturedRequest> capturedRequests = new CopyOnWriteArrayList<>();
    private final AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
    private final AtomicBoolean responseCompleted = new AtomicBoolean(false);
    private final AtomicReference<ResponseConfig> responseConfig = new AtomicReference<>();
    private final AtomicReference<Sinks.One<Void>> cancellationSignal = new AtomicReference<>(Sinks.one());

    public AnthropicMockProvider() {
        this.server = HttpServer.create().port(0).handle(this::handleRequest).bindNow();
    }

    /**
     * Returns the dynamically allocated port.
     */
    public int getPort() {
        return server.port();
    }

    /**
     * Returns the base URL of this mock provider ({@code http://localhost:<port>}).
     */
    public String getBaseUrl() {
        return "http://localhost:" + getPort();
    }

    /**
     * Configures the response that subsequent requests will receive.
     */
    public void configure(ResponseConfig config) {
        this.responseConfig.set(config);
        this.upstreamCancelled.set(false);
        this.responseCompleted.set(false);
        this.cancellationSignal.set(Sinks.one());
    }

    /**
     * Returns all captured requests received since the last {@link #reset()}.
     */
    public List<CapturedRequest> getCapturedRequests() {
        return Collections.unmodifiableList(new ArrayList<>(capturedRequests));
    }

    /**
     * Returns true if the client (gateway) disconnected before the response
     * completed.
     */
    public boolean wasUpstreamCancelled() {
        return upstreamCancelled.get() && !responseCompleted.get();
    }

    /**
     * Clears captured requests and cancellation state.
     */
    public void reset() {
        capturedRequests.clear();
        upstreamCancelled.set(false);
        responseCompleted.set(false);
        responseConfig.set(null);
        cancellationSignal.set(Sinks.one());
    }

    /**
     * Completes when the gateway closes the upstream connection before the
     * configured response finishes.
     */
    public Mono<Void> cancellationSignal() {
        return cancellationSignal.get().asMono();
    }

    @Override
    public void close() {
        if (!server.isDisposed()) {
            server.disposeNow();
        }
    }

    private Mono<Void> handleRequest(HttpServerRequest req, HttpServerResponse res) {
        CapturedRequest captured = new CapturedRequest();
        captured.method = req.method().name();
        captured.path = req.uri();
        captured.headers = new ArrayList<>();
        req.requestHeaders().forEach(e -> captured.headers.add(Map.entry(e.getKey(), e.getValue())));

        req.withConnection(conn -> {
            // Use channel close future for reliable cancellation detection.
            // onDispose fires on pool return; closeFuture fires on actual TCP close.
            conn.channel().closeFuture().addListener(f -> {
                if (!responseCompleted.get()) {
                    upstreamCancelled.set(true);
                    cancellationSignal.get().tryEmitEmpty();
                }
            });
        });

        return req.receive().aggregate().asByteArray().defaultIfEmpty(new byte[0]).doOnNext(body -> {
            captured.bodyBytes = body.clone();
            captured.body = new String(body, StandardCharsets.UTF_8);
            capturedRequests.add(captured);
        }).then(Mono.defer(() -> sendResponse(res)));
    }

    private Mono<Void> sendResponse(HttpServerResponse res) {
        ResponseConfig config = responseConfig.get();
        if (config == null) {
            res.status(500);
            return res.sendString(Mono.just("{\"error\":\"No mock response configured\"}")).then();
        }

        res.status(config.statusCode);
        if (config.contentType != null) {
            res.header("Content-Type", config.contentType);
        }
        config.responseHeaders.forEach(entry -> res.header(entry.getKey(), entry.getValue()));

        if (config.bodySupplier == null) {
            return markCompleted(res.sendHeaders().then());
        }

        if (config.streaming) {
            return markCompleted(sendStreamingResponse(res, config));
        } else {
            String body = config.bodySupplier.get();
            return markCompleted(res.sendString(Mono.just(body)).then());
        }
    }

    private Mono<Void> markCompleted(Mono<Void> response) {
        return response.doOnSuccess(ignored -> responseCompleted.set(true));
    }

    private Mono<Void> sendStreamingResponse(HttpServerResponse res, ResponseConfig config) {
        String fullBody = config.bodySupplier.get();

        if (config.utf8SplitChunks) {
            return sendUtf8SplitChunks(res, fullBody, config.chunkDelay);
        }

        if (config.chunkDelay != null && !config.chunkDelay.isZero()) {
            Flux<String> lines = Flux.fromArray(fullBody.split("\n", -1)).map(line -> line + "\n")
                    .delayElements(config.chunkDelay);
            return res.sendString(lines).then();
        }

        return res.sendString(Mono.just(fullBody)).then();
    }

    /**
     * Sends the body split across chunks, deliberately breaking multi-byte UTF-8
     * characters across chunk boundaries.
     */
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

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /**
     * A captured upstream request received by this mock provider.
     */
    public static class CapturedRequest {
        /** HTTP method (e.g. "POST"). */
        public String method;
        /** Full request path including query string. */
        public String path;
        /** All request headers (may include duplicates for multi-valued headers). */
        public List<Map.Entry<String, String>> headers;
        /** Aggregated request body as a UTF-8 string. */
        public String body;
        /** Exact aggregated request bytes. */
        public byte[] bodyBytes;

        /** Returns the first header value for the given name, or null. */
        public String header(String name) {
            return headers.stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst()
                    .orElse(null);
        }

        /** Returns all header values for the given name. */
        public List<String> headers(String name) {
            return headers.stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).toList();
        }
    }

    /**
     * Configuration for a mock response.
     */
    public static class ResponseConfig {
        final int statusCode;
        final String contentType;
        final List<Map.Entry<String, String>> responseHeaders;
        final BodySupplier bodySupplier;
        final boolean streaming;
        final Duration chunkDelay;
        final boolean utf8SplitChunks;

        private ResponseConfig(Builder builder) {
            this.statusCode = builder.statusCode;
            this.contentType = builder.contentType;
            this.responseHeaders = List.copyOf(builder.responseHeaders);
            this.bodySupplier = builder.bodySupplier;
            this.streaming = builder.streaming;
            this.chunkDelay = builder.chunkDelay;
            this.utf8SplitChunks = builder.utf8SplitChunks;
        }

        public static Builder builder() {
            return new Builder();
        }

        /**
         * Functional interface for lazy body generation.
         */
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

            public ResponseConfig build() {
                if (streaming) {
                    if (contentType == null) {
                        this.contentType = "text/event-stream";
                    }
                }
                return new ResponseConfig(this);
            }
        }
    }
}
