package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.cache.CachedResponse;
import com.miqroera.miqrokey.cache.GatewayResponseCache;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import com.miqroera.miqrokey.gateway.vkey.VirtualKeyResolver;
import com.miqroera.miqrokey.queue.RequestCoalescer;
import com.miqroera.miqrokey.queue.UsageEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Transparent reactive proxy for Anthropic Messages, OpenAI Responses, and
 * OpenAI Chat Completions — the gateway hot path.
 *
 * <p>
 * Pipeline per request:
 * <ol>
 * <li><b>Authenticate</b> the presented virtual key (label-routing format,
 * constant-time HMAC, snapshot binding) — uniform 401/404/403 failures.</li>
 * <li><b>Buffer</b> the request body (bounded; 413 over the limit) and
 * pre-check the requested model against the key's allowed set (403).</li>
 * <li><b>Cache</b> (opt-in per key + explicit header, ADR-0008): on hit, replay
 * byte-identically and emit a {@link CacheHitEvent}; on miss, forward.</li>
 * <li><b>Forward</b>: resolve the upstream credential (decrypted off the event
 * loop), inject the real credential header, preserve exact request bytes and
 * raw query, stream the response back untouched.</li>
 * <li><b>Observe</b> usage from SSE events (bounded, content never retained),
 * emit {@code UPSTREAM} usage events on completion, and store cacheable
 * responses for byte-identical replay.</li>
 * </ol>
 *
 * <p>
 * Credentials, hop-by-hop headers, and forged {@code X-MiQroKey-*} tracking
 * headers are stripped from the forwarded request; the upstream credential is
 * injected by the gateway.
 * </p>
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private static final Set<String> ALLOWED_PATHS = Set.of("/v1/messages", "/v1/responses", "/v1/chat/completions");

    private static final byte[] ANTHROPIC_METHOD_NOT_ALLOWED_BODY = """
            {"type":"error","error":{"type":"method_not_allowed","message":"Only POST is supported on this path"}}"""
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] ANTHROPIC_UNSUPPORTED_PATH_BODY = """
            {"type":"error","error":{"type":"unsupported_path","message":"Only /v1/messages, /v1/responses, and /v1/chat/completions are supported"}}"""
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] OPENAI_METHOD_NOT_ALLOWED_BODY = """
            {"error":{"type":"method_not_allowed","message":"Only POST is supported on this path"}}"""
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] OPENAI_UNSUPPORTED_PATH_BODY = """
            {"error":{"type":"unsupported_path","message":"Only /v1/messages, /v1/responses, and /v1/chat/completions are supported"}}"""
            .getBytes(StandardCharsets.UTF_8);

    private final VirtualKeyResolver keyResolver;
    private final CredentialInjector credentialInjector;
    private final GatewayResponseCache responseCache;
    private final ObjectProvider<RequestCoalescer> coalescerProvider;
    private final ObjectProvider<Duration> coalescerWaitTimeoutProvider;
    private final UsageEventBus usageEventBus;
    private final CacheKeyFactory cacheKeyFactory;
    private final SseReplayEngine sseReplayEngine;
    private final WebClient webClient;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final int maxProxyBufferBytes;

    public ProxyController(VirtualKeyResolver keyResolver, CredentialInjector credentialInjector,
            GatewayResponseCache responseCache, ObjectProvider<RequestCoalescer> coalescerProvider,
            ObjectProvider<Duration> coalescerWaitTimeoutProvider, UsageEventBus usageEventBus,
            CacheKeyFactory cacheKeyFactory, SseReplayEngine sseReplayEngine, WebClient proxyWebClient, Clock clock,
            ObjectMapper objectMapper, ProxyTargetProperties properties) {
        this.keyResolver = keyResolver;
        this.credentialInjector = credentialInjector;
        this.responseCache = responseCache;
        this.coalescerProvider = coalescerProvider;
        this.coalescerWaitTimeoutProvider = coalescerWaitTimeoutProvider;
        this.usageEventBus = usageEventBus;
        this.cacheKeyFactory = cacheKeyFactory;
        this.sseReplayEngine = sseReplayEngine;
        this.webClient = proxyWebClient;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.maxProxyBufferBytes = Math.toIntExact(properties.maxProxyBuffer().toBytes());
    }

    // -------------------------------------------------------------------
    // Allowed endpoints — delegate to the shared proxy kernel
    // -------------------------------------------------------------------

    @PostMapping("/v1/messages")
    public Mono<Void> proxyMessages(ServerWebExchange exchange) {
        return proxyRequest(exchange);
    }

    @PostMapping("/v1/responses")
    public Mono<Void> proxyResponses(ServerWebExchange exchange) {
        return proxyRequest(exchange);
    }

    @PostMapping("/v1/chat/completions")
    public Mono<Void> proxyChat(ServerWebExchange exchange) {
        return proxyRequest(exchange);
    }

    // -------------------------------------------------------------------
    // Catch-all — reject unsupported /v1/** paths and methods
    // -------------------------------------------------------------------

    /**
     * Rejects any {@code /v1/**} request that does not match the three allowed POST
     * endpoints: 405 for wrong methods on allowed paths, 404 for unknown paths,
     * protocol-compatible bodies. Never contacts the upstream provider.
     */
    @RequestMapping("/v1/**")
    public Mono<Void> rejectUnsupported(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        String path = exchange.getRequest().getURI().getPath();

        if (ALLOWED_PATHS.contains(path)) {
            response.setStatusCode(HttpStatus.METHOD_NOT_ALLOWED);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            boolean isAnthropic = "/v1/messages".equals(path);
            byte[] body = isAnthropic ? ANTHROPIC_METHOD_NOT_ALLOWED_BODY : OPENAI_METHOD_NOT_ALLOWED_BODY;
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        }

        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(OPENAI_UNSUPPORTED_PATH_BODY)));
    }

    // -------------------------------------------------------------------
    // Shared reactive proxy kernel
    // -------------------------------------------------------------------

    private Mono<Void> proxyRequest(ServerWebExchange exchange) {
        String requestId = UUID.randomUUID().toString();
        long startMillis = clock.millis();
        try {
            AuthContext ctx = keyResolver.resolve(exchange.getRequest());
            return handleAuthenticated(exchange, ctx, requestId, startMillis);
        } catch (AuthFailureException e) {
            return writeError(exchange, e);
        }
    }

    private Mono<Void> handleAuthenticated(ServerWebExchange exchange, AuthContext ctx, String requestId,
            long startMillis) {
        return bufferBody(exchange).flatMap(body -> {
            JsonNode root = parseQuietly(body);
            String modelName = root != null && root.has("model") && root.get("model").isTextual()
                    ? root.get("model").asText()
                    : null;
            boolean hasToolFields = root != null && (root.has("tools") || root.has("tool_choice"));

            if (modelName != null && !ctx.models().contains(modelName)) {
                return writeError(exchange, new AuthFailureException(HttpStatus.FORBIDDEN, "model_not_allowed",
                        "Model '" + modelName + "' is not allowed for this virtual key"));
            }

            boolean cacheable = CacheEligibility.isCacheable(ctx,
                    exchange.getRequest().getHeaders().getFirst(CacheEligibility.CACHEABLE_HEADER), body,
                    hasToolFields);
            CacheKey cacheKey = cacheable ? cacheKeyFactory.compute(ctx, modelName, body) : null;

            if (cacheKey != null) {
                GatewayResponseCache.Lookup lookup = responseCache.get(ctx.tenantId(), cacheKey);
                if (lookup.response().isPresent()) {
                    publishCacheHit(lookup.level(), ctx, cacheKey, requestId);
                    return sseReplayEngine.replay(lookup.response().get(), exchange.getResponse(), requestId,
                            hitLevelName(lookup.level()));
                }
            }

            return forward(exchange, ctx, body, modelName, cacheKey, requestId, startMillis)
                    .onErrorResume(AuthFailureException.class, e -> writeError(exchange, e))
                    .onErrorResume(WebClientRequestException.class,
                            e -> writeError(exchange, new AuthFailureException(HttpStatus.BAD_GATEWAY,
                                    "upstream_unavailable", "Upstream provider is unreachable")));
        }).onErrorResume(DataBufferLimitException.class,
                e -> writeError(exchange, new AuthFailureException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large",
                        "Request body exceeds the gateway buffer limit")));
    }

    private Mono<byte[]> bufferBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), maxProxyBufferBytes).map(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            DataBufferUtils.release(buffer);
            return bytes;
        });
    }

    /**
     * Forwards the request, or joins a coalescer flight when engaged (ADR-0008).
     * The leader's work is shared with waiters; a waiter that times out or whose
     * leader failed falls back to its own upstream call.
     */
    private Mono<Void> forward(ServerWebExchange exchange, AuthContext ctx, byte[] body, String modelName,
            CacheKey cacheKey, String requestId, long startMillis) {
        RequestCoalescer coalescer = cacheKey != null ? coalescerProvider.getIfAvailable() : null;
        Mono<CachedResponse> work = doForward(exchange, ctx, body, modelName, cacheKey, requestId, startMillis);
        if (coalescer == null) {
            return work.then();
        }
        Duration wait = coalescerWaitTimeoutProvider.getIfAvailable(() -> Duration.ofSeconds(2));
        RequestCoalescer.Flight flight = coalescer.join(cacheKey, work, wait);
        if (flight.leader()) {
            return flight.shared().then();
        }
        // Waiter: replay the leader's response byte-identically, or fall back.
        return flight.shared().flatMap(cached -> {
            publishCoalescedUsage(ctx, cached, cacheKey, requestId);
            return sseReplayEngine.replay(cached, exchange.getResponse(), requestId, "coalesced");
        }).onErrorResume(e -> {
            log.debug("Coalescer wait failed (requestId={}); falling back to own upstream call: {}", requestId,
                    e.getMessage());
            return doForward(exchange, ctx, body, modelName, cacheKey, requestId, startMillis).then();
        });
    }

    /**
     * The full forward: credential injection, byte-exact request emission, and
     * response streaming with bounded usage/cache observation. Completes with the
     * observed {@link CachedResponse} (or a no-cache marker) once the response has
     * been fully written; cancels (client disconnect) never emit usage.
     */
    private Mono<CachedResponse> doForward(ServerWebExchange exchange, AuthContext ctx, byte[] body, String modelName,
            CacheKey cacheKey, String requestId, long startMillis) {
        ServerHttpResponse clientResponse = exchange.getResponse();
        return credentialInjector.resolve(ctx).flatMap(cred -> {
            if (cred.baseUrl() == null || cred.baseUrl().isBlank()) {
                return Mono.error(new AuthFailureException(HttpStatus.BAD_GATEWAY, "route_unavailable",
                        "Upstream base URL is not configured for this credential"));
            }
            URI upstreamUri = buildUpstreamUri(exchange, cred.baseUrl());
            HttpHeaders filteredHeaders = HeaderFilters.filterInboundHeaders(exchange.getRequest().getHeaders());
            filteredHeaders.set(cred.headerName(), cred.headerValue());

            TtfbRecorder ttfb = new TtfbRecorder(requestId, startMillis, clock);
            SseUsageObserver usageObserver = new SseUsageObserver(objectMapper, maxProxyBufferBytes);

            return webClient.post().uri(upstreamUri).headers(h -> h.addAll(filteredHeaders))
                    .body(BodyInserters.fromDataBuffers(Flux.just(exchange.getResponse().bufferFactory().wrap(body))))
                    .exchangeToMono(upstreamResponse -> {
                        int status = upstreamResponse.statusCode().value();
                        log.debug("Upstream response: requestId={}, status={}", requestId, status);
                        clientResponse.setStatusCode(HttpStatusCode.valueOf(status));

                        HttpHeaders outHeaders = HeaderFilters
                                .filterResponseHeaders(upstreamResponse.headers().asHttpHeaders());
                        clientResponse.getHeaders().addAll(outHeaders);
                        clientResponse.getHeaders().set(SseReplayEngine.X_MIQROKEY_REQUEST_ID, requestId);
                        if (cacheKey != null) {
                            clientResponse.getHeaders().set(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
                        }
                        String providerRequestId = pickProviderRequestId(upstreamResponse);

                        boolean isSse = upstreamResponse.headers().contentType()
                                .filter(type -> type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)).isPresent();
                        BodyCollector collector = new BodyCollector(maxProxyBufferBytes);

                        Flux<DataBuffer> observed = upstreamResponse.bodyToFlux(DataBuffer.class)
                                .doOnNext(collector::append);
                        if (isSse) {
                            observed = usageObserver.wrap(observed);
                        }
                        observed = ttfb.wrap(observed);
                        observed = observed
                                .doOnComplete(
                                        () -> ttfb.recordCompletion(reactor.core.publisher.SignalType.ON_COMPLETE))
                                .doOnCancel(() -> ttfb.recordCompletion(reactor.core.publisher.SignalType.CANCEL))
                                .doOnError(error -> ttfb.recordCompletion(reactor.core.publisher.SignalType.ON_ERROR));

                        return clientResponse.writeWith(observed).then(Mono.fromSupplier(() -> {
                            // The stream was fully written to the client.
                            TokenBucket tokens = mergeObservations(usageObserver);
                            boolean successful = status >= 200 && status < 300;
                            long latencyMs = clock.millis() - startMillis;
                            publishUsageEvent(ctx, modelName, cacheKey, tokens, status, providerRequestId, requestId,
                                    latencyMs, true, successful && tokens.isEmpty());

                            CachedResponse cached = null;
                            boolean cacheableResponse = cacheKey != null && successful && !collector.overflow()
                                    && !collector.containsToolCall();
                            if (cacheableResponse) {
                                String contentType = outHeaders.getFirst(HttpHeaders.CONTENT_TYPE);
                                cached = new CachedResponse(status, contentType, outHeaders, collector.bytes(), tokens,
                                        true);
                                responseCache.put(cacheKey, ctx.tenantId(), ctx.key().keyId(), ctx.projectId(),
                                        ctx.productId(), modelName, cached);
                            }
                            return cached != null
                                    ? cached
                                    : new CachedResponse(status, null, new HttpHeaders(), new byte[0],
                                            TokenBucket.EMPTY, false);
                        }));
                    });
        });
    }

    // -------------------------------------------------------------------
    // Observation → usage facts
    // -------------------------------------------------------------------

    private void publishUsageEvent(AuthContext ctx, String modelName, CacheKey cacheKey, TokenBucket tokens, int status,
            String providerRequestId, String requestId, long latencyMs, boolean complete, boolean usageMissing) {
        try {
            usageEventBus.publish(new UsageEvent(UUID.randomUUID(), ctx.tenantId(), providerRequestId,
                    ctx.key().keyId(), ctx.projectId(), ctx.productId(), ctx.binding().credentialId(), modelName,
                    CacheLevel.UPSTREAM, tokens, latencyMs, status, cacheKey != null ? cacheKey.sha256() : null,
                    complete, usageMissing, requestId, clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Failed to publish usage event (requestId={}): {}", requestId, e.getMessage());
        }
    }

    private void publishCoalescedUsage(AuthContext ctx, CachedResponse cached, CacheKey cacheKey, String requestId) {
        try {
            usageEventBus.publish(new UsageEvent(UUID.randomUUID(), ctx.tenantId(), null, ctx.key().keyId(),
                    ctx.projectId(), ctx.productId(), ctx.binding().credentialId(), null, CacheLevel.COALESCED,
                    cached.usage(), null, null, cacheKey != null ? cacheKey.sha256() : null, true,
                    cached.usage().isEmpty(), requestId, clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Failed to publish coalesced usage event (requestId={}): {}", requestId, e.getMessage());
        }
    }

    private void publishCacheHit(GatewayResponseCache.LookupLevel level, AuthContext ctx, CacheKey cacheKey,
            String requestId) {
        try {
            CacheLevel hitLevel = level == GatewayResponseCache.LookupLevel.L1_HIT
                    ? CacheLevel.L1_HIT
                    : CacheLevel.L2_HIT;
            usageEventBus.publish(new CacheHitEvent(UUID.randomUUID(), ctx.tenantId(), cacheKey.sha256(),
                    ctx.key().keyId(), ctx.projectId(), ctx.productId(), hitLevel, requestId, clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Failed to publish cache hit event (requestId={}): {}", requestId, e.getMessage());
        }
    }

    private static String hitLevelName(GatewayResponseCache.LookupLevel level) {
        return level == GatewayResponseCache.LookupLevel.L1_HIT ? "L1" : "L2";
    }

    private TokenBucket mergeObservations(SseUsageObserver observer) {
        TokenBucket merged = TokenBucket.EMPTY;
        for (SseUsageObserver.UsageObservation obs : observer.getObservations()) {
            merged = merged.merge(new TokenBucket(obs.inputTokens(), obs.outputTokens(), obs.cacheCreationInputTokens(),
                    obs.cacheReadInputTokens(), obs.promptTokens(), obs.completionTokens(), obs.totalTokens(),
                    obs.reasoningTokens()));
        }
        return merged;
    }

    /**
     * The provider's request id (dedup anchor for usage writes): OpenAI exposes
     * {@code x-request-id}, Anthropic {@code request-id}. Truncated to the column
     * width; null when absent.
     */
    private static String pickProviderRequestId(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        String id = response.headers().asHttpHeaders().getFirst("x-request-id");
        if (id == null) {
            id = response.headers().asHttpHeaders().getFirst("request-id");
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        return id.length() > 128 ? id.substring(0, 128) : id;
    }

    private JsonNode parseQuietly(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------
    // Error envelopes (protocol-compatible)
    // -------------------------------------------------------------------

    private Mono<Void> writeError(ServerWebExchange exchange, AuthFailureException e) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(e.status()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ErrorEnvelopes.body(e, exchange.getRequest().getURI().getPath())
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private URI buildUpstreamUri(ServerWebExchange exchange, String baseUrl) {
        var request = exchange.getRequest();
        StringBuilder sb = new StringBuilder(baseUrl);
        if (sb.charAt(sb.length() - 1) == '/') {
            sb.setLength(sb.length() - 1);
        }
        sb.append(request.getURI().getRawPath());
        String rawQuery = request.getURI().getRawQuery();
        if (rawQuery != null) {
            sb.append('?').append(rawQuery);
        }
        return URI.create(sb.toString());
    }

    // -------------------------------------------------------------------
    // Bounded response body collector (cache staging)
    // -------------------------------------------------------------------

    /**
     * Copies the response bytes into a bounded staging buffer without touching the
     * buffers forwarded to the client. Overflow (bodies larger than the gateway
     * buffer limit) marks the response uncacheable but never affects streaming.
     */
    private static final class BodyCollector {

        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean overflow;

        BodyCollector(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        void append(DataBuffer dataBuffer) {
            if (overflow) {
                return;
            }
            int readable = dataBuffer.readableByteCount();
            if (buffer.size() + readable > maxBytes) {
                overflow = true;
                buffer.reset();
                return;
            }
            byte[] bytes = new byte[readable];
            int position = dataBuffer.readPosition();
            dataBuffer.read(bytes);
            dataBuffer.readPosition(position);
            buffer.write(bytes, 0, readable);
        }

        boolean overflow() {
            return overflow;
        }

        byte[] bytes() {
            return buffer.toByteArray();
        }

        /**
         * Heuristic tool-call detection on the raw bytes: a response referencing
         * {@code tool_calls} (OpenAI) or {@code tool_use} (Anthropic) is never cached.
         * False positives only skip caching.
         */
        boolean containsToolCall() {
            if (overflow) {
                return true;
            }
            byte[] bytes = buffer.toByteArray();
            return containsAscii(bytes, "\"tool_calls\"") || containsAscii(bytes, "\"tool_use\"");
        }

        private static boolean containsAscii(byte[] haystack, String needle) {
            byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
            outer: for (int i = 0; i + target.length <= haystack.length; i++) {
                for (int j = 0; j < target.length; j++) {
                    if (haystack[i + j] != target[j]) {
                        continue outer;
                    }
                }
                return true;
            }
            return false;
        }
    }
}
