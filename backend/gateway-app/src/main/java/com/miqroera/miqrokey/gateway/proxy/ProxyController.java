package com.miqroera.miqrokey.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.cache.CachedResponse;
import com.miqroera.miqrokey.cache.GatewayResponseCache;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import com.miqroera.miqrokey.domain.model.McpCircuitBreaker;
import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStartedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStatus;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import com.miqroera.miqrokey.gateway.retention.RetentionSidecar;
import com.miqroera.miqrokey.gateway.observability.GatewayTtfbMetrics;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.adapters.catalog.ProviderCatalog;
import com.miqroera.miqrokey.adapters.registry.BuiltInAdapterRegistry;
import com.miqroera.miqrokey.spi.InboundRequest;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import com.miqroera.miqrokey.spi.RouteContext;
import com.miqroera.miqrokey.spi.TargetRequest;

import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import com.miqroera.miqrokey.gateway.vkey.QuotaGate;
import com.miqroera.miqrokey.gateway.vkey.VirtualKeyResolver;
import com.miqroera.miqrokey.queue.RequestCoalescer;
import com.miqroera.miqrokey.queue.UsageEventBus;
import io.netty.handler.timeout.ReadTimeoutException;
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
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.netty.http.client.PrematureCloseException;
import reactor.util.retry.Retry;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * <li><b>Record lifecycle</b>: every request that reaches upstream publishes a
 * {@link RequestStartedEvent} (the {@code IN_FLIGHT} row) and finalizes exactly
 * once with a {@link RequestCompletedEvent} — including client cancellation and
 * upstream failure. Cache hits and auth failures never open a lifecycle
 * record.</li>
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
    private final ProxyTargetProperties properties;
    private final UpstreamTargetValidator upstreamTargetValidator;
    private final Scheduler credentialDecryptScheduler;
    private final BuiltInAdapterRegistry adapterRegistry;
    private final ProviderCatalog providerCatalog;
    private final RetentionSidecar retentionSidecar;
    private final ClientAddressResolver clientAddressResolver;
    /** TTFB metric hook (#486): observation per attempt that sees a first byte. */
    private final GatewayTtfbMetrics ttfbMetrics;
    /**
     * Context-limit pre-check (#553): rejects oversized bodies before the upstream
     * call.
     */
    private final ContextLimitGuard contextLimitGuard;
    /**
     * LLM-side circuit breaker (#741, default off): per (product × credential)
     * fast-fail while an upstream keeps failing.
     */
    private final LlmCircuitBreakerRegistry circuitBreaker;
    private final UpstreamErrorClassifier upstreamErrorClassifier;
    /**
     * Content-filter shadow (#740, default off): local-vocabulary observation of
     * the request/reply bytes — counts and a content-free log line only.
     */
    private final ContentFilterShadow contentFilterShadow;

    public ProxyController(VirtualKeyResolver keyResolver, CredentialInjector credentialInjector,
            GatewayResponseCache responseCache, ObjectProvider<RequestCoalescer> coalescerProvider,
            ObjectProvider<Duration> coalescerWaitTimeoutProvider, UsageEventBus usageEventBus,
            CacheKeyFactory cacheKeyFactory, SseReplayEngine sseReplayEngine, WebClient proxyWebClient, Clock clock,
            ObjectMapper objectMapper, ProxyTargetProperties properties,
            UpstreamTargetValidator upstreamTargetValidator, Scheduler credentialDecryptScheduler,
            BuiltInAdapterRegistry adapterRegistry, ProviderCatalog providerCatalog, RetentionSidecar retentionSidecar,
            GatewayTtfbMetrics ttfbMetrics, ClientAddressResolver clientAddressResolver,
            ContextLimitGuard contextLimitGuard, LlmCircuitBreakerRegistry circuitBreaker,
            UpstreamErrorClassifier upstreamErrorClassifier, ContentFilterShadow contentFilterShadow) {
        this.retentionSidecar = retentionSidecar;
        this.clientAddressResolver = clientAddressResolver;
        this.ttfbMetrics = ttfbMetrics;
        this.contextLimitGuard = contextLimitGuard;
        this.circuitBreaker = circuitBreaker;
        this.upstreamErrorClassifier = upstreamErrorClassifier;
        this.contentFilterShadow = contentFilterShadow;
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
        this.properties = properties;
        this.maxProxyBufferBytes = Math.toIntExact(properties.maxProxyBuffer().toBytes());
        this.upstreamTargetValidator = upstreamTargetValidator;
        this.credentialDecryptScheduler = credentialDecryptScheduler;
        this.adapterRegistry = adapterRegistry;
        this.providerCatalog = providerCatalog;
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
            QuotaGate.requireNotExceeded(ctx); // #684: 429 before any body work
            return handleAuthenticated(exchange, ctx, requestId, startMillis);
        } catch (AuthFailureException e) {
            return writeError(exchange, e);
        }
    }

    private Mono<Void> handleAuthenticated(ServerWebExchange exchange, AuthContext ctx, String requestId,
            long startMillis) {
        return bufferBody(exchange).flatMap(body -> {
            // #1388: one absolute upstream deadline per request, opened as soon as
            // the body is in hand and never reset. Every step below that can wait
            // on a bounded scheduler lane — the L2 cache read, the credential
            // decrypt, the SSRF DNS check — shares this single budget, so the
            // documented overall cutoff ("整体硬截止（自请求体到手起计时，含排队等待与前置跳，不重置）",
            // docs/configuration-reference.md) also covers the time a request
            // spends *queued* for a lane. Arming the cutoff after those hops, as
            // before, let one wedged lane park LLM traffic with no deadline
            // running at all.
            long upstreamStartNanos = System.nanoTime();
            // Compliance retention side-channel (ADR-0014, default off):
            // best-effort, never affects the forwarded outcome.
            retentionSidecar.capture(exchange.getRequest().getPath().value(), body, ctx, requestId);
            // #740 shadow v1 (ADR-0027, default off): local-vocabulary observation
            // of the buffered request body — counts and a content-free log line only.
            // Read-only: the bytes forwarded below are exactly `body`.
            contentFilterShadow.observeInput(body);
            JsonNode root = parseQuietly(body);
            String modelName = root != null && root.has("model") && root.get("model").isTextual()
                    ? root.get("model").asText()
                    : null;
            boolean hasToolFields = root != null && (root.has("tools") || root.has("tool_choice"));
            boolean streaming = root != null && root.has("stream") && root.get("stream").asBoolean(false);

            java.util.Set<String> allowed = ctx.models();
            if ("POLICY_ROUTED".equals(ctx.context().resolutionStatus())) {
                // #647: unattributed requests run under the tenant policy's
                // dedicated credential — never a project grant. Model scope =
                // policy scope (empty = the product's ACTIVE upstream catalog),
                // intersected with the key's own allowance (plan Q2).
                RouteSnapshot.UnattributedPolicyRecord policy = ctx.snapshot().unattributedPolicy(ctx.tenantId());
                java.util.Set<String> scope = policy != null && !policy.models().isEmpty()
                        ? policy.models()
                        : ctx.snapshot().upstreamModels(ctx.binding().productId());
                allowed = allowed.stream().filter(scope::contains).collect(java.util.stream.Collectors.toSet());
            } else {
                // ADR-0018: the request's binding decides the grant (multi-project keys).
                java.util.Set<String> grantModels = ctx.snapshot().grantModels(ctx.binding().grantId());
                if (grantModels != null) {
                    // Grant is the authorization authority: shrinking the grant's
                    // model scope must revoke the model for every existing key of
                    // the project (same semantics as /v1/models).
                    allowed = allowed.stream().filter(grantModels::contains)
                            .collect(java.util.stream.Collectors.toSet());
                }
            }
            if (modelName != null && !allowed.contains(modelName)) {
                return writeError(exchange, new AuthFailureException(HttpStatus.FORBIDDEN, "model_not_allowed",
                        "Model '" + modelName + "' is not allowed for this virtual key"));
            }

            // #553: context-limit pre-check. Runs after authentication and model
            // authorization (a caller never learns the size verdict for a resource
            // it may not use) and before the cache lookup and the upstream call, so
            // an oversized context can never reach a provider. Read-only: the
            // accepted body is forwarded byte-identically.
            AuthFailureException contextLimit = contextLimitGuard.check(body, exchange.getRequest().getURI().getPath(),
                    requestId);
            if (contextLimit != null) {
                return writeError(exchange, contextLimit);
            }

            boolean cacheable = CacheEligibility.isCacheable(ctx,
                    exchange.getRequest().getHeaders().getFirst(CacheEligibility.CACHEABLE_HEADER), body,
                    hasToolFields);
            CacheKey cacheKey = cacheable
                    ? cacheKeyFactory.compute(ctx, modelName, body, wireProtocolOf(exchange))
                    : null;

            // #444: the cache lookup is blocking I/O (L2 hits PostgreSQL) — it
            // must never run on the event loop. Reads go through the bounded
            // scheduler; the cached-replay path continues on its thread.
            // #1388: the lane wait counts against the upstream deadline, so a
            // wedged L2 read cannot hold a cacheable request open forever. The
            // timer covers the lookup alone and dies with it — the replay or the
            // forward below subscribes its own attempt budget.
            Mono<Void> pipeline = cacheKey != null
                    ? Mono.fromCallable(() -> responseCache.get(ctx.tenantId(), cacheKey))
                            .subscribeOn(credentialDecryptScheduler).timeout(remainingOf(upstreamStartNanos))
                            .flatMap(lookup -> {
                                if (lookup.response().isPresent()) {
                                    publishCacheHit(lookup.level(), ctx, cacheKey, requestId);
                                    return sseReplayEngine.replay(lookup.response().get(), exchange.getResponse(),
                                            requestId, hitLevelName(lookup.level()));
                                }
                                return forward(exchange, ctx, body, modelName, cacheKey, requestId, startMillis,
                                        streaming, upstreamStartNanos);
                            })
                    : forward(exchange, ctx, body, modelName, cacheKey, requestId, startMillis, streaming,
                            upstreamStartNanos);

            // #1000: every upstream failure has to end in an envelope here instead
            // of escaping to the container's default 500 document. The clauses stay
            // deliberately typed (no catch-all) so that control-plane errors keep
            // their own mapping; a premature close after the status line matched
            // none of them and leaked as a 500.
            return pipeline.onErrorResume(AuthFailureException.class, e -> writeError(exchange, e))
                    .onErrorResume(WebClientRequestException.class,
                            e -> writeError(exchange,
                                    new AuthFailureException(HttpStatus.BAD_GATEWAY, "upstream_unavailable",
                                            "Upstream provider is unreachable")))
                    .onErrorResume(PrematureCloseException.class, e -> upstreamClosedBeforeFirstByte(exchange, e))
                    // #1375: the same leak, one operator further out. The two
                    // gateway-owned deadlines (responseTimeout at the overall
                    // level, streamIdleTimeout on the observed body) are plain
                    // Flux/Mono timeouts: they emit
                    // java.util.concurrent.TimeoutException, which is neither a
                    // WebClientRequestException nor a PrematureCloseException, so
                    // it matched none of the clauses above and a gateway-side
                    // deadline rendered as the container's 500 document.
                    .onErrorResume(TimeoutException.class, e -> upstreamDeadlineExceeded(exchange, e))
                    // And the fourth deadline, which is the gateway's too: the
                    // upstream first-byte timeout is reactor-netty's own
                    // ReadTimeoutException (io.netty.handler.timeout — no ancestor
                    // in common with the JDK TimeoutException above). It reaches
                    // this chain in two shapes: while the response head is still
                    // outstanding the netty failure is wrapped in a
                    // WebClientRequestException and takes the 502 clause above,
                    // but once the head has been read it surfaces unwrapped and,
                    // with no clause of its own, escaped as the container's 500
                    // document. It is also the deadline that fires first by
                    // default: it is armed between reads, so at the shipped
                    // PT120S it preempts both gateway-owned caps (PT5M idle,
                    // PT10M overall).
                    .onErrorResume(ReadTimeoutException.class, e -> upstreamDeadlineExceeded(exchange, e));
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
            CacheKey cacheKey, String requestId, long startMillis, boolean streaming, long upstreamStartNanos) {
        RequestCoalescer coalescer = cacheKey != null ? coalescerProvider.getIfAvailable() : null;
        Mono<CachedResponse> work = doForward(exchange, ctx, body, modelName, cacheKey, requestId, startMillis,
                streaming, upstreamStartNanos);
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
            if (!cached.isComplete()) {
                // The leader completed with a no-cache marker: a non-2xx reply, an
                // oversized body, or one that references tool calls — none of which
                // the gateway stores. The marker carries an empty body, so replaying
                // it would answer this client with an empty 200/4xx/5xx that the
                // leader never received, and its own call would never reach the
                // upstream. Do our own call instead, as the SPI contract promises.
                log.debug("Coalescer leader had nothing replayable (requestId={}); falling back to own upstream call",
                        requestId);
                return doForward(exchange, ctx, body, modelName, cacheKey, requestId, startMillis, streaming,
                        upstreamStartNanos).then();
            }
            publishCoalescedUsage(ctx, modelName, cached, cacheKey, requestId,
                    clientAddressResolver.resolve(exchange.getRequest()));
            return sseReplayEngine.replay(cached, exchange.getResponse(), requestId, "coalesced");
        }).onErrorResume(e -> {
            log.debug("Coalescer wait failed (requestId={}); falling back to own upstream call: {}", requestId,
                    e.getMessage());
            return doForward(exchange, ctx, body, modelName, cacheKey, requestId, startMillis, streaming,
                    upstreamStartNanos).then();
        });
    }

    /**
     * The full forward: credential injection, byte-exact request emission, and
     * response streaming with bounded usage/cache observation. Publishes the
     * request lifecycle — {@link RequestStartedEvent} just before the upstream
     * call, then {@link RequestCompletedEvent} exactly once on any terminal signal,
     * so a client disconnect or upstream failure finalizes the record too.
     * Completes with the observed {@link CachedResponse} (or a no-cache marker)
     * once the response has been fully written; usage events themselves are only
     * emitted for fully completed requests.
     *
     * <p>
     * G2.5 network bounds: connection deadline (10s) and first-byte deadline (120s)
     * live on the {@link HttpClient}; the stream-idle timeout (5min, reset per
     * chunk) is applied per attempt on the observed body; the overall deadline
     * ({@link ProxyTargetProperties#responseTimeout()}) is enforced as two
     * non-overlapping windows measured from a single origin taken in
     * {@link #handleAuthenticated} before the first blocking hop — one over the
     * credential resolution below (its queue wait included, #1388), one over the
     * attempts themselves. A Reactor {@code timeout} disarms its timer as soon as
     * its own source emits, so the windows cannot both fire for one request and the
     * total stays inside the configured budget. A connection-phase failure is
     * retried at most once, only before the first byte, never on timeouts, and
     * always with the same credential.
     * </p>
     */
    private Mono<CachedResponse> doForward(ServerWebExchange exchange, AuthContext ctx, byte[] body, String modelName,
            CacheKey cacheKey, String requestId, long startMillis, boolean streaming, long upstreamStartNanos) {
        // #1388: the budget is read when this Mono is subscribed, not when it is
        // assembled — the coalescer keeps the returned Mono for waiters, and each
        // subscription is a separate upstream call.
        return Mono.defer(() -> resolveCredential(ctx, requestId).timeout(remainingOf(upstreamStartNanos))
                .flatMap(cred -> forwardWithResolvedCredential(exchange, ctx, body, cred, modelName, cacheKey,
                        requestId, startMillis, streaming, remainingOf(upstreamStartNanos))));
    }

    /**
     * Resolves the credential for this request and runs the G2.6 SSRF guard over
     * its target. Both halves are blocking (AES decrypt, DNS) and hop to the
     * credential-decrypt scheduler, so the caller must bound the returned Mono:
     * with a saturated scheduler the lane wait, not just the work, is what has to
     * be capped (#1388). Fails only before any upstream attempt, so the caller may
     * map these errors without touching lifecycle or breaker accounting.
     */
    private Mono<CredentialInjector.InjectedCredential> resolveCredential(AuthContext ctx, String requestId) {
        return credentialInjector.resolve(ctx).flatMap(cred -> {
            if (cred.baseUrl() == null || cred.baseUrl().isBlank()) {
                return Mono.error(new AuthFailureException(HttpStatus.BAD_GATEWAY, "route_unavailable",
                        "Upstream base URL is not configured for this credential"));
            }
            // G2.6 SSRF guard: the blocking DNS check must not run on the event
            // loop, so hop to the credential-decrypt scheduler. Rejection
            // surfaces as a generic route_unavailable; the reason never names
            // the target.
            return Mono.just(cred).publishOn(credentialDecryptScheduler).map(c -> {
                UpstreamTargetValidator.Result target = upstreamTargetValidator.validate(c.baseUrl());
                if (!target.allowed()) {
                    log.warn("Upstream target rejected: requestId={}, reason={}", requestId, target.reason());
                    throw new AuthFailureException(HttpStatus.BAD_GATEWAY, "route_unavailable",
                            "Upstream target is not allowed");
                }
                return c;
            });
        });
    }

    /**
     * The slice of the request's overall upstream deadline that is still unspent,
     * measured from {@code upstreamStartNanos}. Never zero or negative: a request
     * that reaches a stage after the budget is gone fails on its next signal
     * instead of arming an invalid timer.
     */
    private Duration remainingOf(long upstreamStartNanos) {
        Duration left = properties.responseTimeout().minusNanos(System.nanoTime() - upstreamStartNanos);
        return left.isZero() || left.isNegative() ? Duration.ofNanos(1L) : left;
    }

    private Mono<CachedResponse> forwardWithResolvedCredential(ServerWebExchange exchange, AuthContext ctx, byte[] body,
            CredentialInjector.InjectedCredential cred, String modelName, CacheKey cacheKey, String requestId,
            long startMillis, boolean streaming, Duration attemptBudget) {
        // G3.x relay wiring: resolve the target through the product adapter so a
        // per-protocol base URL applies (/v1/messages may target the provider's
        // Anthropic entry while chat targets its OpenAI one); products without
        // an adapter keep the credential's single base and a verbatim splice.
        String wireProtocol = wireProtocolOf(exchange).name();
        ResolvedTarget target = resolveTarget(exchange, ctx, cred, wireProtocol);
        URI upstreamUri = target.uri();
        HttpHeaders filteredHeaders;
        if (target.headers() != null) {
            // Adapter headers still pass the gateway's single sanitizer: the
            // adapter-side strip set only removes credential headers, so
            // hop-by-hop/Host/Content-Length (rebuilt by the client) must be
            // dropped here or the upstream sees a stale Host (DeepSeek WAF 418).
            HttpHeaders adapterHeaders = new HttpHeaders();
            target.headers().forEach(adapterHeaders::set);
            filteredHeaders = HeaderFilters.filterInboundHeaders(adapterHeaders);
        } else {
            filteredHeaders = HeaderFilters.filterInboundHeaders(exchange.getRequest().getHeaders());
        }
        filteredHeaders.set(cred.headerName(), cred.headerValue());

        // #741 LLM-side circuit breaker (default off): a rejected call fails
        // fast and never opens a lifecycle row — the same accounting rule as
        // every other gateway rejection; the upstream is never contacted.
        if (circuitBreaker.beforeCall(ctx.productId(), ctx.binding().credentialId(),
                requestId) == McpCircuitBreaker.Decision.REJECTED) {
            return Mono.error(new AuthFailureException(HttpStatus.SERVICE_UNAVAILABLE, "circuit_open",
                    "Upstream failure rate is too high; calls are rejected until it recovers"));
        }

        // Lifecycle start: only requests that actually reach upstream open a
        // record (auth failures and cache hits emit no lifecycle row). The
        // credential is resolved once before any attempt — a retry reuses
        // the same credential (no cross-credential failover).
        Instant startedAt = clock.instant();
        publishLifecycleStart(ctx, modelName, requestId, startedAt, streaming, wireProtocol);

        // Per-attempt state: each attempt (initial + at most one retry) gets
        // a fresh recorder/observer so a failed attempt never contaminates
        // the successful one. The terminal doFinally reads the latest.
        AtomicReference<UpstreamAttempt> attemptRef = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();

        return Mono.defer(() -> {
            attempts.incrementAndGet();
            UpstreamAttempt attempt = new UpstreamAttempt(requestId, startMillis, clock, objectMapper,
                    maxProxyBufferBytes, ttfbMetrics::record);
            attemptRef.set(attempt);
            return callUpstreamOnce(exchange, ctx, cred, body, upstreamUri, filteredHeaders, cacheKey, modelName,
                    requestId, startMillis, streaming, attempt);
        }).retryWhen(Retry.max(1).filter(error -> retryableConnectionFailure(error, attemptRef.get()))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                // #1388: only the slice of the request deadline that is left —
                // the time already spent waiting for a scheduler lane was charged
                // upstream of this method, so the two windows do not sum past the
                // configured budget. Terminal classification is unchanged: a timer
                // here still cancels the in-flight attempt, which is what the
                // doFinally below reads to skip breaker accounting.
                .timeout(attemptBudget).doOnError(error -> {
                    UpstreamAttempt attempt = attemptRef.get();
                    if (attempt != null) {
                        attempt.upstreamError.set(error);
                    }
                }).doFinally(signal -> {
                    UpstreamAttempt attempt = attemptRef.get();
                    if (attempt == null) {
                        return;
                    }
                    // Reactor Netty can report a client disconnect as
                    // ON_COMPLETE on the server write side when every buffered
                    // byte was already flushed: the channel's terminate
                    // completes the outbound instead of cancelling it. The
                    // observed stream's own terminal signal is authoritative
                    // for the client-cancel case — cancelling it is what
                    // closes the upstream connection. An upstream failure must
                    // not count as a client cancel, so require no error.
                    boolean clientCancelled = signal == SignalType.CANCEL
                            || (attempt.ttfb.terminalSignal() == SignalType.CANCEL
                                    && attempt.upstreamError.get() == null);
                    TokenBucket tokens = attempt.observedTokens.get() != null
                            ? attempt.observedTokens.get()
                            : latestObservation(attempt.usageObserver);
                    // #741: the single terminal point feeds the LLM breaker —
                    // one outcome per gateway request (retried attempts are not
                    // separately counted), client cancels skipped.
                    if (!clientCancelled) {
                        circuitBreaker.afterCall(ctx.productId(), ctx.binding().credentialId(),
                                attempt.httpStatus.get(), attempt.upstreamError.get() != null,
                                clock.millis() - startMillis);
                    }
                    // #623: lifecycle events still need the id when the stream
                    // ended without the response-completion block (client
                    // cancels); the usage event path resolves it earlier.
                    effectiveProviderRequestId(attempt);
                    publishLifecycleComplete(ctx, modelName, requestId, startedAt, streaming, wireProtocol, signal,
                            attempt.httpStatus.get(), attempt.providerRequestId.get(), attempt.upstreamError.get(),
                            attempt.ttfb, tokens, clientCancelled, attempts.get() - 1);
                });
    }

    /**
     * One upstream attempt: credential-injected byte-exact request emission and
     * response streaming with bounded usage/cache observation. The stream-idle
     * timeout is applied on the observed body (reset on every chunk). Never
     * publishes lifecycle events — the caller's doFinally owns the single terminal
     * record. Usage events are only emitted for fully written requests.
     */
    private Mono<CachedResponse> callUpstreamOnce(ServerWebExchange exchange, AuthContext ctx,
            CredentialInjector.InjectedCredential cred, byte[] body, URI upstreamUri, HttpHeaders filteredHeaders,
            CacheKey cacheKey, String modelName, String requestId, long startMillis, boolean streaming,
            UpstreamAttempt attempt) {
        ServerHttpResponse clientResponse = exchange.getResponse();
        return webClient.post().uri(upstreamUri).headers(h -> h.addAll(filteredHeaders))
                .body(BodyInserters.fromDataBuffers(Flux.just(exchange.getResponse().bufferFactory().wrap(body))))
                .exchangeToMono(upstreamResponse -> {
                    int status = upstreamResponse.statusCode().value();
                    attempt.httpStatus.set(status);
                    log.debug("Upstream response: requestId={}, status={}", requestId, status);
                    clientResponse.setStatusCode(HttpStatusCode.valueOf(status));

                    HttpHeaders outHeaders = HeaderFilters
                            .filterResponseHeaders(upstreamResponse.headers().asHttpHeaders());
                    clientResponse.getHeaders().addAll(outHeaders);
                    clientResponse.getHeaders().set(SseReplayEngine.X_MIQROKEY_REQUEST_ID, requestId);
                    if (cacheKey != null) {
                        clientResponse.getHeaders().set(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
                    }
                    String upstreamRequestId = pickProviderRequestId(upstreamResponse);
                    attempt.providerRequestId.set(upstreamRequestId);

                    boolean isSse = upstreamResponse.headers().contentType()
                            .filter(type -> type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)).isPresent();

                    Flux<DataBuffer> observed = upstreamResponse.bodyToFlux(DataBuffer.class)
                            .doOnNext(attempt.collector::append);
                    if (isSse) {
                        observed = attempt.usageObserver.wrap(observed);
                    }
                    observed = attempt.ttfb.wrap(observed);
                    observed = observed.timeout(properties.streamIdleTimeout())
                            .doOnComplete(() -> attempt.ttfb.recordCompletion(SignalType.ON_COMPLETE))
                            .doOnCancel(() -> attempt.ttfb.recordCompletion(SignalType.CANCEL))
                            .doOnError(error -> attempt.ttfb.recordCompletion(SignalType.ON_ERROR));

                    return clientResponse.writeWith(observed).then(Mono.fromSupplier(() -> {
                        // The stream was fully written to the client.
                        TokenBucket tokens = latestObservation(attempt.usageObserver);
                        if (!isSse && tokens.isEmpty()) {
                            // Non-streaming JSON: usage lives in the response
                            // body, not SSE events. Only counts are extracted —
                            // the body is never retained or persisted.
                            tokens = SseUsageObserver.parseUsageJson(objectMapper, attempt.collector.bytes());
                        }
                        attempt.observedTokens.set(tokens);
                        boolean successful = status >= 200 && status < 300;
                        if (!successful) {
                            // ADR-0024 option B (#770): observation only — count the
                            // SHAPE of the upstream failure and log the class. The
                            // request was not retried, not rewritten, and the response
                            // reaches the client byte-for-byte as received.
                            upstreamErrorClassifier.observe(status, attempt.collector.bytes(),
                                    attempt.collector.overflow());
                        }
                        long latencyMs = clock.millis() - startMillis;
                        publishUsageEvent(ctx, modelName, cacheKey, tokens, status, effectiveProviderRequestId(attempt),
                                requestId, latencyMs, true, successful && tokens.isEmpty(),
                                clientAddressResolver.resolve(exchange.getRequest()));
                        // Retention (ADR-0014 增补): the reply is fully written —
                        // capture its text on the compliance side channel
                        // (best-effort; disabled unless the tenant opted in).
                        retentionSidecar.captureOutput(exchange.getRequest().getURI().getPath(),
                                attempt.collector.bytes(), isSse, ctx, requestId, attempt.collector.overflow());
                        // #740 shadow v1 (ADR-0027, default off): observe the fully
                        // written reply bytes (same bounded collector, same overflow
                        // rule) — observation only; the client already received the
                        // response byte-for-byte.
                        contentFilterShadow.observeOutput(attempt.collector.bytes(), attempt.collector.overflow());

                        CachedResponse cached = null;
                        boolean cacheableResponse = cacheKey != null && successful && !attempt.collector.overflow()
                                && !attempt.collector.containsToolCall();
                        if (cacheableResponse) {
                            String contentType = outHeaders.getFirst(HttpHeaders.CONTENT_TYPE);
                            cached = new CachedResponse(status, contentType, outHeaders.asMultiValueMap(),
                                    attempt.collector.bytes(), tokens, true);
                            // #444: the fill is best-effort and blocking I/O — run it
                            // on the bounded scheduler, never on the response-writing
                            // event loop; a failed fill only logs (the client already
                            // has its response).
                            CachedResponse toStore = cached;
                            Mono.fromRunnable(() -> responseCache.put(cacheKey, ctx.tenantId(), ctx.key().keyId(),
                                    ctx.projectId(), ctx.productId(), modelName, toStore))
                                    .subscribeOn(credentialDecryptScheduler).subscribe(null,
                                            error -> log.warn("aigw.cache.put_failed: {}", error.getMessage()));
                        }
                        return cached != null
                                ? cached
                                : new CachedResponse(status, null, new HttpHeaders().asMultiValueMap(), new byte[0],
                                        TokenBucket.EMPTY, false);
                    }));
                });
    }

    /**
     * Per-attempt observation state. A fresh instance is created for every attempt
     * (initial call and at most one retry) so a failed attempt never leaks
     * observations into the successful one.
     */
    private static final class UpstreamAttempt {
        final TtfbRecorder ttfb;
        final SseUsageObserver usageObserver;
        final BodyCollector collector;
        final AtomicReference<Integer> httpStatus = new AtomicReference<>();
        final AtomicReference<String> providerRequestId = new AtomicReference<>();
        final AtomicReference<Throwable> upstreamError = new AtomicReference<>();
        final AtomicReference<TokenBucket> observedTokens = new AtomicReference<>();

        UpstreamAttempt(String requestId, long startMillis, Clock clock, ObjectMapper objectMapper,
                int maxProxyBufferBytes, LongConsumer firstByteListener) {
            this.ttfb = new TtfbRecorder(requestId, startMillis, clock, firstByteListener);
            this.usageObserver = new SseUsageObserver(objectMapper, maxProxyBufferBytes);
            this.collector = new BodyCollector(maxProxyBufferBytes);
        }
    }

    /**
     * G2.5 retry rule: at most one retry (Retry.max(1) in {@link #doForward}), only
     * for a connection-phase failure — no first byte was observed and the failure
     * is not a timeout. A streaming response that already started is never retried;
     * timeouts follow the deadline semantics instead of retrying. The credential is
     * resolved once for all attempts (no cross-credential failover).
     */
    private static boolean retryableConnectionFailure(Throwable error, UpstreamAttempt attempt) {
        if (attempt == null || attempt.ttfb.firstByteMillisRaw() > 0) {
            return false;
        }
        if (!(error instanceof WebClientRequestException)) {
            return false;
        }
        return !isTimeout(error);
    }

    // -------------------------------------------------------------------
    // Observation → usage facts
    // -------------------------------------------------------------------

    private void publishUsageEvent(AuthContext ctx, String modelName, CacheKey cacheKey, TokenBucket tokens, int status,
            String providerRequestId, String requestId, long latencyMs, boolean complete, boolean usageMissing,
            String clientIp) {
        if (modelName == null) {
            // usage_event.model_id is NOT NULL: a transparently forwarded body
            // without a usable "model" field (unparseable JSON, or a protocol
            // that carries the model in the path) has no billable fact to
            // record. The lifecycle records still capture the request; a null
            // model here used to abort — and endlessly re-enqueue — the whole
            // usage write batch.
            log.warn("Usage event skipped: no model name in request (requestId={}, status={})", requestId, status);
            return;
        }
        try {
            usageEventBus.publish(new UsageEvent(UUID.randomUUID(), ctx.tenantId(), providerRequestId,
                    ctx.key().keyId(), ctx.projectId(), ctx.productId(), ctx.binding().credentialId(), modelName,
                    CacheLevel.UPSTREAM, tokens, latencyMs, status, cacheKey != null ? cacheKey.sha256() : null,
                    complete, usageMissing, requestId, clock.instant(), clientIp, attributionOf(ctx)));
        } catch (RuntimeException e) {
            log.warn("Failed to publish usage event (requestId={}): {}", requestId, e.getMessage());
        }
    }

    private void publishCoalescedUsage(AuthContext ctx, String modelName, CachedResponse cached, CacheKey cacheKey,
            String requestId, String clientIp) {
        if (modelName == null) {
            log.warn("Coalesced usage event skipped: no model name in request (requestId={})", requestId);
            return;
        }
        try {
            usageEventBus.publish(new UsageEvent(UUID.randomUUID(), ctx.tenantId(), null, ctx.key().keyId(),
                    ctx.projectId(), ctx.productId(), ctx.binding().credentialId(), modelName, CacheLevel.COALESCED,
                    cached.usage(), null, null, cacheKey != null ? cacheKey.sha256() : null, true,
                    cached.usage().isEmpty(), requestId, clock.instant(), clientIp, attributionOf(ctx)));
        } catch (RuntimeException e) {
            log.warn("Failed to publish coalesced usage event (requestId={}): {}", requestId, e.getMessage());
        }
    }

    /** CAA attribution snapshot for the usage row; null without context. */
    private static UsageEvent.ContextAttribution attributionOf(AuthContext ctx) {
        var c = ctx.context();
        return c == null
                ? null
                : new UsageEvent.ContextAttribution(c.sessionId(), c.activityId(), c.claimedProjectId(),
                        c.resolutionStatus(), c.resolutionCandidates(), c.claimSource(), c.claimConfidence(),
                        ctx.binding().projectTag());
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

    // -------------------------------------------------------------------
    // Request lifecycle → durable records
    // -------------------------------------------------------------------

    /**
     * Opens the lifecycle record ({@code IN_FLIGHT}). Only called for requests that
     * actually reach upstream. The instant is the partition key — the completion
     * must carry the same value.
     */
    private void publishLifecycleStart(AuthContext ctx, String modelName, String requestId, Instant startedAt,
            boolean streaming, String wireProtocol) {
        try {
            usageEventBus.publish(new RequestStartedEvent(UUID.randomUUID(), startedAt, requestId, ctx.tenantId(),
                    ctx.key().userId(), ctx.projectId(), ctx.key().keyId(), ctx.snapshot().providerId(ctx.productId()),
                    ctx.productId(), ctx.binding().credentialId(), wireProtocol, modelName, streaming));
        } catch (RuntimeException e) {
            log.warn("Failed to publish lifecycle start (requestId={}): {}", requestId, e.getMessage());
        }
    }

    /**
     * Finalizes the lifecycle record exactly once (the bus writer's guarded upsert
     * only transitions {@code IN_FLIGHT} rows). Fires on every terminal signal —
     * completion, upstream error, or client cancel. Never carries request or
     * response content.
     */
    private void publishLifecycleComplete(AuthContext ctx, String modelName, String requestId, Instant startedAt,
            boolean streaming, String wireProtocol, SignalType signal, Integer status, String upstreamRequestId,
            Throwable upstreamError, TtfbRecorder ttfb, TokenBucket tokens, boolean clientCancelled, int retryCount) {
        try {
            boolean firstByteSeen = ttfb.ttfbMillis() > 0;
            boolean streamCompleted = signal == SignalType.ON_COMPLETE;
            RequestStatus lifecycleStatus = lifecycleStatus(status, clientCancelled, upstreamError, firstByteSeen);
            Instant completedAt = clock.instant();
            Long ttfbMs = firstByteSeen ? ttfb.ttfbMillis() : null;
            Instant firstByteAt = firstByteSeen ? Instant.ofEpochMilli(ttfb.firstByteEpochMillis()) : null;
            usageEventBus.publish(new RequestCompletedEvent(UUID.randomUUID(), startedAt, requestId, ctx.tenantId(),
                    ctx.key().userId(), ctx.projectId(), ctx.key().keyId(), ctx.snapshot().providerId(ctx.productId()),
                    ctx.productId(), ctx.binding().credentialId(), wireProtocol, modelName, streaming,
                    upstreamRequestId, firstByteAt, completedAt, Duration.between(startedAt, completedAt).toMillis(),
                    ttfbMs, status, lifecycleStatus, clientCancelled, firstByteSeen && !streamCompleted, tokens,
                    lifecycleStatus == RequestStatus.SUCCEEDED && tokens.isEmpty(), retryCount));
        } catch (RuntimeException e) {
            log.warn("Failed to publish lifecycle completion (requestId={}): {}", requestId, e.getMessage());
        }
    }

    private static RequestStatus lifecycleStatus(Integer httpStatus, boolean clientCancelled, Throwable upstreamError,
            boolean firstByteSeen) {
        // A client cancel wins over a status that was already observed: the
        // stream was cut short even if the upstream had started responding.
        if (clientCancelled) {
            return RequestStatus.CLIENT_CANCELLED;
        }
        // An upstream failure also wins over an already-observed status: a 200
        // status line followed by a mid-stream failure (stream-idle timeout,
        // overall deadline, read error) is an interrupted stream, not a
        // success. Status-only outcomes (a fully received body) are the only
        // SUCCEEDED/UPSTREAM_REJECTED paths.
        if (upstreamError != null) {
            if (isTimeout(upstreamError)) {
                return firstByteSeen ? RequestStatus.STREAM_INTERRUPTED : RequestStatus.TIMEOUT_BEFORE_FIRST_BYTE;
            }
            return firstByteSeen ? RequestStatus.STREAM_INTERRUPTED : RequestStatus.UPSTREAM_UNAVAILABLE;
        }
        if (httpStatus != null) {
            return httpStatus >= 200 && httpStatus < 300 ? RequestStatus.SUCCEEDED : RequestStatus.UPSTREAM_REJECTED;
        }
        return RequestStatus.UPSTREAM_UNAVAILABLE;
    }

    /**
     * True when the error chain contains a timeout: reactor-netty's first-byte
     * deadline ({@link io.netty.handler.timeout.ReadTimeoutException}), reactor's
     * stream-idle/overall {@code Flux/Mono.timeout} (JDK
     * {@link java.util.concurrent.TimeoutException}), or a JDK timeout.
     */
    private static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException
                    || t instanceof io.netty.handler.timeout.ReadTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Maps the proxied path to the wire protocol family. */
    private static ProtocolFamily wireProtocolOf(ServerWebExchange exchange) {
        return switch (exchange.getRequest().getURI().getPath()) {
            case "/v1/messages" -> ProtocolFamily.ANTHROPIC_MESSAGES;
            case "/v1/responses" -> ProtocolFamily.OPENAI_RESPONSES;
            case "/v1/chat/completions" -> ProtocolFamily.OPENAI_CHAT_COMPLETIONS;
            default -> ProtocolFamily.OPENAI_COMPATIBLE;
        };
    }

    /**
     * Collapses the usage frames observed for one response into a single bucket.
     * Provider counters are cumulative within a response, so each later frame
     * supersedes the earlier values per field — never sums them
     * ({@link TokenBucket#overlay}).
     */
    private TokenBucket latestObservation(SseUsageObserver observer) {
        TokenBucket latest = TokenBucket.EMPTY;
        for (SseUsageObserver.UsageObservation obs : observer.getObservations()) {
            latest = latest.overlay(new TokenBucket(obs.inputTokens(), obs.outputTokens(),
                    obs.cacheCreationInputTokens(), obs.cacheReadInputTokens(), obs.promptTokens(),
                    obs.completionTokens(), obs.totalTokens(), obs.reasoningTokens()));
        }
        return latest;
    }

    /**
     * The provider's request id (dedup anchor for usage writes): OpenAI exposes
     * {@code x-request-id}, Anthropic {@code request-id}. Truncated to the column
     * width; null when absent. When both headers are missing, the terminal stage
     * falls back to the response-body id ({@link UpstreamRequestIdExtractor},
     * #623).
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

    /**
     * Headers first ({@link #pickProviderRequestId}); when both are absent the
     * observed response prefix is scanned for the body {@code "id"} (#623 —
     * DeepSeek and other OpenAI-compatible providers only carry the id in the
     * body). The first resolution wins for the whole attempt: the usage event is
     * published at response-completion, before the terminal lifecycle record, so it
     * must not depend on the later doFinally stage.
     */
    private static String effectiveProviderRequestId(UpstreamAttempt attempt) {
        String id = attempt.providerRequestId.get();
        if (id == null) {
            id = UpstreamRequestIdExtractor.fromBodyPrefix(attempt.collector.bytes());
            attempt.providerRequestId.set(id);
        }
        return id;
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

    /**
     * Maps an upstream that closed the connection before delivering a complete
     * response onto the same {@code upstream_unavailable} envelope as an
     * unreachable provider.
     *
     * <p>
     * reactor-netty reports a close after the upstream status line — but before any
     * body byte — as {@link PrematureCloseException}, which extends
     * {@link java.io.IOException} and is therefore neither a
     * {@link WebClientRequestException} (the mapping above) nor a timeout: without
     * this clause it escapes the controller and the container renders its own 500
     * error document, which is not the protocol envelope clients parse. Nothing has
     * been relayed downstream at that point, so the envelope can still be written.
     * </p>
     *
     * <p>
     * Once a body byte has been relayed the response is committed and no error
     * document can follow it — the truncated framing is then the client's only
     * signal, so the failure is propagated unchanged.
     * </p>
     */
    private Mono<Void> upstreamClosedBeforeFirstByte(ServerWebExchange exchange, PrematureCloseException error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        return writeError(exchange, new AuthFailureException(HttpStatus.BAD_GATEWAY, "upstream_unavailable",
                "Upstream provider closed the connection before sending a response body"));
    }

    /**
     * A deadline the gateway set for the upstream expired: the overall hard
     * deadline ({@code miqrokey.gateway.upstream.response-timeout}), the
     * stream-idle deadline ({@code miqrokey.gateway.upstream.stream-idle-timeout}),
     * or the first-byte deadline
     * ({@code miqrokey.gateway.upstream.first-byte-timeout}) — the last one when
     * the upstream announced a status but never sent a body byte.
     *
     * <p>
     * The parameter is {@link Throwable} because the three deadlines do not share a
     * type: the two gateway-owned ones are plain {@code Mono}/{@code Flux} timeouts
     * ({@link TimeoutException}) while the first-byte one is reactor-netty's
     * {@code io.netty.handler.timeout.ReadTimeoutException}, whose nearest common
     * ancestor with the JDK type is {@code Throwable}. Their handling is the same,
     * so they share it.
     * </p>
     *
     * <p>
     * Nothing has been relayed downstream in any of the three cases, so the
     * response is still uncommitted and the protocol envelope can be written; the
     * deadline then maps exactly like its transport siblings — the upstream did not
     * produce a usable response in time. Once a body byte has been relayed the
     * response is committed and any UTF-8 envelope would corrupt the stream already
     * on the wire, so the deadline is propagated unchanged, for the same reason as
     * {@link #upstreamClosedBeforeFirstByte}.
     * </p>
     */
    private Mono<Void> upstreamDeadlineExceeded(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        return writeError(exchange, new AuthFailureException(HttpStatus.BAD_GATEWAY, "upstream_unavailable",
                "Upstream provider did not respond before the gateway deadline"));
    }

    /**
     * Writes a protocol error envelope. The provider's response head may already
     * have been copied onto this response (see {@code callUpstreamOnce}), so the
     * entity headers describing a body that never arrived have to go first: a
     * {@code Content-Length} of the provider's making would claim a size the
     * envelope does not have, leaving the client to wait for bytes no one will send
     * (#1416), and a {@code Content-Encoding} would have it decode plain UTF-8
     * JSON.
     */
    private Mono<Void> writeError(ServerWebExchange exchange, AuthFailureException e) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(e.status()));
        response.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
        response.getHeaders().remove(HttpHeaders.CONTENT_ENCODING);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (e.retryAfterSeconds() != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        byte[] bytes = ErrorEnvelopes.body(e, exchange.getRequest().getURI().getPath())
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /**
     * Resolves the upstream target through the product's adapter (G3.x relay
     * wiring): the adapter applies the per-protocol base URL and the documented
     * path normalization ({@code /v1} stripping on /v3- or /v4-suffixed bases,
     * Anthropic paths kept verbatim). Products without a registered adapter keep
     * the legacy verbatim splice.
     */
    private ResolvedTarget resolveTarget(ServerWebExchange exchange, AuthContext ctx,
            CredentialInjector.InjectedCredential cred, String wireProtocol) {
        String productCode = ctx.snapshot().productCode(ctx.binding().productId());
        if (productCode != null && providerCatalog.findById(productCode).isPresent()) {
            ProviderProductAdapter adapter = adapterRegistry.findById(productCode).orElse(null);
            if (adapter != null) {
                ProtocolFamily family = ProtocolFamily.valueOf(wireProtocol);
                RouteSnapshot.CredentialRecord credential = ctx.snapshot().credential(ctx.binding().credentialId());
                URI baseUrl = credential != null ? credential.baseUrl(family.name()) : null;
                // RouteContext enforces https (SPI-level SSRF boundary); plain-http
                // upstreams (local mocks, allowed-cidrs private deployments) keep
                // the legacy verbatim splice below.
                if (baseUrl != null && "https".equalsIgnoreCase(baseUrl.getScheme())) {
                    try {
                        var request = exchange.getRequest();
                        RouteContext route = new RouteContext(ctx.key().tenantId(), ctx.binding().productId(),
                                ctx.binding().projectId(), family, baseUrl);
                        InboundRequest inbound = new InboundRequest(request.getMethod().name(),
                                request.getURI().getPath(), decodeQuery(request.getURI().getRawQuery()),
                                request.getHeaders().asMultiValueMap());
                        TargetRequest target = adapter.resolve(route, inbound);
                        StringBuilder sb = new StringBuilder(target.origin().toString());
                        sb.append(target.path());
                        if (target.query() != null && !target.query().isEmpty()) {
                            sb.append('?').append(target.query());
                        }
                        return new ResolvedTarget(URI.create(sb.toString()), target.headers());
                    } catch (RuntimeException e) {
                        // A broken adapter must never take the relay down: fall
                        // back to the legacy splice and leave a breadcrumb.
                        log.warn("Adapter target resolution failed (product={}): {}; using single base", productCode,
                                e.getMessage());
                    }
                }
            }
        }
        return new ResolvedTarget(buildUpstreamUri(exchange, cred.baseUrl()), null);
    }

    /**
     * Parses a raw query string into the decoded multi-map InboundRequest expects.
     */
    private static java.util.Map<String, java.util.List<String>> decodeQuery(String rawQuery) {
        java.util.Map<String, java.util.List<String>> query = new java.util.LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : null;
            query.computeIfAbsent(
                    org.springframework.web.util.UriUtils.decode(key, java.nio.charset.StandardCharsets.UTF_8),
                    k -> new java.util.ArrayList<>())
                    .add(value == null
                            ? null
                            : org.springframework.web.util.UriUtils.decode(value,
                                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return query;
    }

    /** Adapter-resolved target: the upstream URI plus the final header set. */
    record ResolvedTarget(URI uri, java.util.Map<String, String> headers) {
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
