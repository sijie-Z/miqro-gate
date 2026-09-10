package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.crypto.ConsumerJwtVerifier;
import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.model.McpAccessPolicy;
import com.miqroera.miqrokey.domain.model.McpAccessStatus;
import com.miqroera.miqrokey.domain.model.McpAclMode;
import com.miqroera.miqrokey.domain.model.McpCircuitBreaker;
import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;
import com.miqroera.miqrokey.domain.model.McpRetryPolicy;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.mcplog.McpAccessLogSink;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * MCP invocation proxy (F01, Tencent AI gateway doc 135906 wiring shape):
 *
 * <pre>
 *   POST /mcpservers/{serviceName}/mcp
 *   Authorization: Bearer &lt;consumer api key&gt;
 *   { "jsonrpc":"2.0", "method":"tools/call", "params":{ "name":"…", "arguments":{} }, "id":1 }
 * </pre>
 *
 * <p>
 * All-reactive pipeline: authenticate the caller as an API consumer (SHA-256
 * digest scan over the route snapshot — the same digest the control plane
 * stores, so no secret material ever moves), resolve the ONLINE MCP service by
 * name, apply the two-level access control ({@link McpAccessPolicy}) — server
 * mode for every method plus, for {@code tools/call}, the per-tool override and
 * tool enablement — then stream the JSON-RPC envelope upstream verbatim. Only
 * envelope method/tool names feed the decision; arguments and responses never
 * enter logs or logic (envelope metadata is the documented exception to the
 * body-blind rule).
 * </p>
 *
 * <p>
 * Sessions are stateless passthrough: upstream {@code mcp-session-id} response
 * headers flow back and caller {@code Session-Id} request headers flow through
 * untouched (distributed MCP session caching is a separate follow-up).
 * </p>
 *
 * <p>
 * Resilience (F12/F13, per-service {@code mcp_resilience_policy} V30, both
 * default OFF): retries apply only to failures observed before the first
 * upstream response byte reaches the caller (5xx / connection failure / timeout
 * per policy conditions; non-idempotent POST/PUT/PATCH tool calls need the
 * explicit idempotency confirmation); the circuit breaker fail-fasts with 503
 * {@code circuit_open} while OPEN and probes in HALF_OPEN.
 * </p>
 *
 * <p>
 * Every request with a resolvable identity (consumer authenticated AND service
 * resolved) writes one metadata row to {@code mcp_access_log} via
 * {@link McpAccessLogSink} (F15): outcome FORWARDED / *_DENIED /
 * TOOL_UNAVAILABLE / INVALID_ENVELOPE / UPSTREAM_FAILURE / CIRCUIT_OPEN plus
 * the client-facing or upstream HTTP status. Pre-resolution failures (401/404)
 * carry no trustworthy identity and are not logged. Sink calls are
 * fire-and-forget and never block the pipeline.
 * </p>
 */
@RestController
public class McpProxyController {

    private static final Logger log = LoggerFactory.getLogger(McpProxyController.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CONSUMER_KEY_PREFIX = "mqk_api_";
    /** Stateless RS256 verifier (JDK-native; no key material of its own). */
    private static final ConsumerJwtVerifier JWT_VERIFIER = new ConsumerJwtVerifier();
    /** Per-attempt upstream budget (Tencent default 60s). */
    private static final Duration MCP_TIMEOUT = Duration.ofSeconds(60);
    /** SSE keep-alive comment cadence (issue #356). */
    private static final Duration SSE_KEEP_ALIVE = Duration.ofSeconds(15);

    private final RouteSnapshotProvider routeSnapshotProvider;
    private final WebClient proxyWebClient;
    private final ObjectMapper objectMapper;
    private final McpAccessLogSink accessLogSink;
    private final McpCircuitBreakerRegistry circuitRegistry;
    private final ObjectProvider<com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider> keyEncryptionProvider;
    private final Scheduler credentialDecryptScheduler;
    private final McpSseSessionRegistry sseSessions;
    private final Clock clock;

    public McpProxyController(RouteSnapshotProvider routeSnapshotProvider, WebClient proxyWebClient,
            ObjectMapper objectMapper, McpAccessLogSink accessLogSink, Clock clock,
            ObjectProvider<com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider> keyEncryptionProvider,
            Scheduler credentialDecryptScheduler, McpSseSessionRegistry sseSessions) {
        this.routeSnapshotProvider = routeSnapshotProvider;
        this.proxyWebClient = proxyWebClient;
        this.objectMapper = objectMapper;
        this.accessLogSink = accessLogSink;
        this.circuitRegistry = new McpCircuitBreakerRegistry(clock);
        this.keyEncryptionProvider = keyEncryptionProvider;
        this.credentialDecryptScheduler = credentialDecryptScheduler;
        this.sseSessions = sseSessions;
        this.clock = clock;
    }

    @PostMapping("/mcpservers/{serviceName}/mcp")
    public Mono<Void> invoke(ServerWebExchange exchange, @PathVariable String serviceName) {
        RouteSnapshot snapshot = routeSnapshotProvider.current();
        RouteSnapshot.ConsumerRecord consumer = authenticate(exchange.getRequest(), snapshot);
        if (consumer == null) {
            return error(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "invalid_api_key", "Unknown API key");
        }
        // Issue #322 expiry: at/after expires_at the credential is silently
        // rejected (same 401 shape as an unknown key — no "used to be valid"
        // oracle), checked against the injected clock.
        if (consumer.expiredAt(clock.instant())) {
            return error(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "invalid_api_key", "Unknown API key");
        }
        // Issue #316 channel scope: the MCP data plane requires mcp:call (null
        // scope = full access). Fail closed before any service/ACL resolution.
        if (!consumer.allows("mcp:call")) {
            return error(exchange.getResponse(), HttpStatus.FORBIDDEN, "consumer_scope_denied",
                    "Consumer is not allowed to call the MCP data plane");
        }
        RouteSnapshot.McpServerRecord service = snapshot.mcpService(serviceName);
        if (service == null) {
            return error(exchange.getResponse(), HttpStatus.NOT_FOUND, "mcp_service_not_found", "Unknown MCP service");
        }
        String gatewayRequestId = UUID.randomUUID().toString();
        String logSessionId = exchange.getRequest().getHeaders().getFirst("Session-Id");
        ResponseTarget target = new ExchangeTarget(exchange);
        return exchange.getRequest().getBody().collectList().map(McpProxyController::concatBuffers).flatMap(
                body -> authorizeAndForward(exchange, consumer, service, body, gatewayRequestId, logSessionId, target));
    }

    /**
     * Inbound MCP SSE transport, stream half (issue #356, I11): same credential /
     * expiry / scope / service checks as {@code /mcp}, then a single-node in-memory
     * session whose stream carries the {@code endpoint} announcement, relayed
     * {@code message} events and gateway {@code error} events. Keep-alive comments
     * hold the connection; idle sessions end via the registry sweep.
     */
    @GetMapping(value = "/mcpservers/{serviceName}/sse")
    public Mono<Void> sse(ServerWebExchange exchange, @PathVariable String serviceName) {
        RouteSnapshot snapshot = routeSnapshotProvider.current();
        RouteSnapshot.ConsumerRecord consumer = authenticate(exchange.getRequest(), snapshot);
        if (consumer == null || consumer.expiredAt(clock.instant())) {
            return error(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "invalid_api_key", "Unknown API key");
        }
        if (!consumer.allows("mcp:call")) {
            return error(exchange.getResponse(), HttpStatus.FORBIDDEN, "consumer_scope_denied",
                    "Consumer is not allowed to call the MCP data plane");
        }
        RouteSnapshot.McpServerRecord service = snapshot.mcpService(serviceName);
        if (service == null) {
            return error(exchange.getResponse(), HttpStatus.NOT_FOUND, "mcp_service_not_found", "Unknown MCP service");
        }
        McpSseSessionRegistry.SseSession session = sseSessions.tryOpen(consumer.id(), service.id(), service.name())
                .orElse(null);
        if (session == null) {
            return error(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE, "session_capacity_exceeded",
                    "MCP SSE session capacity is exhausted");
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        response.getHeaders().setCacheControl("no-store");
        String endpointUrl = "/mcpservers/" + service.name() + "/message?sessionId=" + session.id();
        Flux<byte[]> frames = Flux
                .concat(Flux.just(SseFrames.event("endpoint", endpointUrl)), session.frames().asFlux())
                .publish(shared -> Flux.merge(shared, Flux.interval(SSE_KEEP_ALIVE)
                        .map(tick -> SseFrames.comment("ping")).takeUntilOther(shared.ignoreElements())));
        return response.writeAndFlushWith(frames.map(frame -> Mono.just(response.bufferFactory().wrap(frame))))
                .doFinally(signal -> sseSessions.close(session.id()));
    }

    /**
     * Inbound MCP SSE transport, message half (issue #356): transport-level checks
     * are answered directly (401/403/404), the POST acknowledges with 202 and the
     * JSON-RPC call then runs the same pipeline as {@code /mcp} with its outcome
     * delivered on the session stream.
     */
    @PostMapping("/mcpservers/{serviceName}/message")
    public Mono<Void> message(ServerWebExchange exchange, @PathVariable String serviceName,
            @RequestParam("sessionId") String sessionId) {
        RouteSnapshot snapshot = routeSnapshotProvider.current();
        RouteSnapshot.ConsumerRecord consumer = authenticate(exchange.getRequest(), snapshot);
        if (consumer == null || consumer.expiredAt(clock.instant())) {
            return error(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "invalid_api_key", "Unknown API key");
        }
        if (!consumer.allows("mcp:call")) {
            return error(exchange.getResponse(), HttpStatus.FORBIDDEN, "consumer_scope_denied",
                    "Consumer is not allowed to call the MCP data plane");
        }
        RouteSnapshot.McpServerRecord service = snapshot.mcpService(serviceName);
        if (service == null) {
            return error(exchange.getResponse(), HttpStatus.NOT_FOUND, "mcp_service_not_found", "Unknown MCP service");
        }
        McpSseSessionRegistry.SseSession session = null;
        try {
            session = sseSessions.find(UUID.fromString(sessionId)).orElse(null);
        } catch (IllegalArgumentException e) {
            session = null;
        }
        if (session == null || !session.serviceId().equals(service.id())) {
            return error(exchange.getResponse(), HttpStatus.NOT_FOUND, "unknown_session", "Unknown MCP SSE session");
        }
        if (!session.consumerId().equals(consumer.id())) {
            return error(exchange.getResponse(), HttpStatus.FORBIDDEN, "session_credential_mismatch",
                    "This SSE session belongs to another consumer credential");
        }
        sseSessions.touch(session);
        String gatewayRequestId = UUID.randomUUID().toString();
        ResponseTarget target = new SseTarget(session);
        McpSseSessionRegistry.SseSession boundSession = session;
        return exchange.getRequest().getBody().collectList().map(McpProxyController::concatBuffers).flatMap(body -> {
            // Log-row session correlation (#358): the client's Session-Id header
            // wins; otherwise this inbound SSE session identifies the conversation.
            String headerSessionId = exchange.getRequest().getHeaders().getFirst("Session-Id");
            String logSessionId = headerSessionId != null ? headerSessionId : boundSession.id().toString();
            Mono<Void> dispatch = authorizeAndForward(exchange, consumer, service, body, gatewayRequestId, logSessionId,
                    target).onErrorResume(error -> {
                        log.warn("aigw.mcp.sse.dispatch_failed session={}: {}", boundSession.id(), error.getMessage());
                        return target.errorResponse(HttpStatus.BAD_GATEWAY, "mcp_upstream_failed",
                                "MCP upstream call failed");
                    });
            // Outcomes stream over the session; the POST acknowledges now.
            dispatch.subscribe();
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.ACCEPTED);
            return response.setComplete();
        });
    }

    private Mono<Void> authorizeAndForward(ServerWebExchange exchange, RouteSnapshot.ConsumerRecord consumer,
            RouteSnapshot.McpServerRecord service, byte[] body, String gatewayRequestId, String logSessionId,
            ResponseTarget target) {
        CallContext context = new CallContext(consumer, service, gatewayRequestId, logSessionId);
        try {
            JsonNode envelope = objectMapper.readTree(body);
            String rpcMethod = textOrNull(envelope.path("method"));
            String toolName = "tools/call".equals(rpcMethod) ? textOrNull(envelope.path("params").path("name")) : null;
            McpAclMode serverMode = parseMode(service.aclMode());
            if (!McpAccessPolicy.isAllowed(serverMode, service.serverConsumerIds(), null, List.of(), consumer.id())) {
                record(context, rpcMethod, toolName, McpAccessStatus.SERVICE_DENIED, 403);
                return target.errorResponse(HttpStatus.FORBIDDEN, "mcp_access_denied",
                        "Consumer is not allowed to call this MCP service");
            }
            RouteSnapshot.McpToolRecord tool = null;
            if (toolName != null && !toolName.isBlank()) {
                tool = service.tool(toolName);
                if (tool == null || !"ENABLED".equals(tool.status())) {
                    record(context, rpcMethod, toolName, McpAccessStatus.TOOL_UNAVAILABLE, 403);
                    return target.errorResponse(HttpStatus.FORBIDDEN, "mcp_tool_unavailable",
                            "Tool is unknown or disabled: " + toolName);
                }
                McpAclMode overrideMode = parseMode(tool.overrideMode());
                if (overrideMode != null && !McpAccessPolicy.isAllowed(serverMode, service.serverConsumerIds(),
                        overrideMode, tool.toolConsumerIds(), consumer.id())) {
                    record(context, rpcMethod, toolName, McpAccessStatus.TOOL_DENIED, 403);
                    return target.errorResponse(HttpStatus.FORBIDDEN, "mcp_access_denied",
                            "Consumer is not allowed to call tool: " + toolName);
                }
            }
            log.info("aigw.mcp.call requestId={} service={} consumer={} rpcMethod={} tool={}", gatewayRequestId,
                    service.name(), consumer.name(), rpcMethod == null ? "-" : rpcMethod,
                    toolName == null ? "-" : toolName);
            McpResiliencePolicy servicePolicy = service.resilience() == null
                    ? McpResiliencePolicy.disabled()
                    : service.resilience();
            // Tool-level retry override (#360, I13): the retry fields are replaced
            // for this tool's calls; the breaker stays service-level.
            McpResiliencePolicy policy = tool != null && tool.retry() != null
                    ? servicePolicy.withRetry(tool.retry())
                    : servicePolicy;
            String toolHttpMethod = tool == null ? null : tool.method();
            if (!"API_KEY".equals(service.backendAuthMode())) {
                return forward(exchange, target, service.endpoint(), body, context, rpcMethod, toolName, toolHttpMethod,
                        policy, null);
            }
            // Upstream backend credential (#320, Tencent raw 03): decrypt the
            // snapshot ciphertext off the event loop (first use may load key
            // material from disk) and attach Authorization: Bearer upstream.
            // Unavailable/undecryptable credentials fail closed before any
            // upstream request.
            com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider crypto = keyEncryptionProvider.getIfAvailable();
            com.miqroera.miqrokey.domain.crypto.EncryptedSecret encrypted = service.encryptedBackendSecret();
            if (crypto == null || encrypted == null) {
                record(context, rpcMethod, toolName, McpAccessStatus.UPSTREAM_FAILURE, 502);
                return target.errorResponse(HttpStatus.BAD_GATEWAY, "backend_auth_unavailable",
                        "MCP upstream credential is not available");
            }
            return Mono.fromCallable(() -> {
                byte[] secret = crypto.decrypt(encrypted, service.tenantId(), service.id());
                try {
                    return new String(secret, StandardCharsets.UTF_8);
                } finally {
                    com.miqroera.miqrokey.domain.crypto.impl.SecretWiping.clearArray(secret);
                }
            }).subscribeOn(credentialDecryptScheduler).flatMap(bearer -> forward(exchange, target, service.endpoint(),
                    body, context, rpcMethod, toolName, toolHttpMethod, policy, bearer)).onErrorResume(decryptError -> {
                        log.warn("aigw.mcp.backend_auth_failed service={}: {}", service.name(),
                                decryptError.getMessage());
                        record(context, rpcMethod, toolName, McpAccessStatus.UPSTREAM_FAILURE, 502);
                        return target.errorResponse(HttpStatus.BAD_GATEWAY, "backend_auth_unavailable",
                                "MCP upstream credential could not be decrypted");
                    });
        } catch (Exception e) {
            log.warn("aigw.mcp.invalid envelope service={}: {}", service.name(), e.getMessage());
            record(context, null, null, McpAccessStatus.INVALID_ENVELOPE, 400);
            return target.errorResponse(HttpStatus.BAD_REQUEST, "invalid_jsonrpc", "Invalid JSON-RPC body");
        }
    }

    private Mono<Void> forward(ServerWebExchange exchange, ResponseTarget target, String endpoint, byte[] body,
            CallContext context, String rpcMethod, String toolName, String toolHttpMethod, McpResiliencePolicy policy,
            String upstreamBearer) {
        ServerHttpRequest in = exchange.getRequest();
        HttpHeaders headers = new HttpHeaders();
        MediaType contentType = in.getHeaders().getContentType();
        headers.setContentType(contentType == null ? MediaType.APPLICATION_JSON : contentType);
        String accept = in.getHeaders().getFirst(HttpHeaders.ACCEPT);
        if (accept != null) {
            headers.set(HttpHeaders.ACCEPT, accept);
        }
        String sessionId = in.getHeaders().getFirst("Session-Id");
        if (sessionId != null) {
            headers.set("Session-Id", sessionId);
        }
        if (upstreamBearer != null) {
            // Fixed bearer shape (Tencent raw 03); the consumer credential is
            // consumed at the gateway and never forwarded.
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + upstreamBearer);
        }
        String bucket = toolName != null ? toolName : (rpcMethod == null ? "envelope" : rpcMethod);
        McpCircuitBreaker breaker = policy.breakerEnabled()
                ? circuitRegistry.get(context.service.id(), bucket, policy).breaker()
                : null;
        return attempt(exchange, target, endpoint, body, headers, context, rpcMethod, toolName, toolHttpMethod, policy,
                breaker, 0, new boolean[1]);
    }

    /**
     * One upstream attempt with retry/breaker orchestration. Retries only happen
     * before any response byte reached the caller; a retryable 5xx or a
     * pre-response transport failure re-enters with {@code attempt + 1} while
     * {@code McpRetryPolicy.shouldRetry} allows it.
     */
    private Mono<Void> attempt(ServerWebExchange exchange, ResponseTarget target, String endpoint, byte[] body,
            HttpHeaders headers, CallContext context, String rpcMethod, String toolName, String toolHttpMethod,
            McpResiliencePolicy policy, McpCircuitBreaker breaker, int attempt, boolean[] rowRecorded) {
        if (breaker != null) {
            McpCircuitBreaker.Decision decision = breaker.beforeCall();
            // F13 gate (raw doc 134859): breakerSkipRetry ON (default, recommended)
            // fast-fails the bucket while OPEN / half-open probes are exhausted.
            // With it OFF the breaker only observes — beforeCall still drives the
            // state machine and probe counting, but a REJECTED verdict no longer
            // blocks the call (discouraged mode, explicit and off by default).
            if (decision == McpCircuitBreaker.Decision.REJECTED && policy.breakerSkipRetry()) {
                record(context, rpcMethod, toolName, McpAccessStatus.CIRCUIT_OPEN, 503);
                rowRecorded[0] = true;
                log.info("aigw.mcp.circuit_open requestId={} service={} bucket={}", context.gatewayRequestId,
                        context.service.name(), toolName == null ? rpcMethod : toolName);
                return target.errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "circuit_open",
                        "MCP upstream circuit is open");
            }
        }
        ServerHttpResponse clientResponse = exchange.getResponse();
        long startedNanos = System.nanoTime();
        return proxyWebClient.post().uri(URI.create(endpoint)).headers(h -> h.addAll(headers))
                .body(BodyInserters.fromValue(body))
                // Body consumption happens INSIDE the exchangeToMono callback (the
                // response stream is bound to it): deferring the read to a later
                // flatMap yields an empty body stream.
                .exchangeToMono(resp -> {
                    long ttfbMs = (System.nanoTime() - startedNanos) / 1_000_000;
                    int status = resp.statusCode().value();
                    if (McpRetryPolicy.isServerError(status) && McpRetryPolicy.shouldRetry(policy,
                            McpRetryPolicy.FailureKind.SERVER_5XX, toolHttpMethod, attempt)) {
                        log.info("aigw.mcp.retry requestId={} service={} attempt={} upstreamStatus={}",
                                context.gatewayRequestId, context.service.name(), attempt, status);
                        return resp.releaseBody().then(
                                Mono.defer(() -> attempt(exchange, target, endpoint, body, headers, context, rpcMethod,
                                        toolName, toolHttpMethod, policy, breaker, attempt + 1, rowRecorded)));
                    }
                    record(context, rpcMethod, toolName, McpAccessStatus.FORWARDED, status, ttfbMs);
                    rowRecorded[0] = true;
                    if (breaker != null) {
                        breaker.afterCall(!policy.breakerErrorStatusCodes().contains(status), ttfbMs);
                    }
                    return target.complete(status, resp.headers().asHttpHeaders(), resp.bodyToFlux(byte[].class));
                }).timeout(MCP_TIMEOUT).onErrorResume(error -> {
                    // A retried inner chain is self-contained (its own timeout +
                    // onErrorResume): once a terminal row was recorded anywhere,
                    // this layer must not retry again or double-record.
                    long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
                    McpRetryPolicy.FailureKind kind = classifyFailure(error);
                    if (!rowRecorded[0] && kind != null
                            && McpRetryPolicy.shouldRetry(policy, kind, toolHttpMethod, attempt)) {
                        log.info("aigw.mcp.retry requestId={} service={} attempt={} kind={}", context.gatewayRequestId,
                                context.service.name(), attempt, kind);
                        return attempt(exchange, target, endpoint, body, headers, context, rpcMethod, toolName,
                                toolHttpMethod, policy, breaker, attempt + 1, rowRecorded);
                    }
                    if (breaker != null) {
                        breaker.afterCall(false, elapsedMs);
                    }
                    if (!rowRecorded[0]) {
                        record(context, rpcMethod, toolName, McpAccessStatus.UPSTREAM_FAILURE, null);
                        rowRecorded[0] = true;
                    }
                    return Mono.error(error);
                });
    }

    private static McpRetryPolicy.FailureKind classifyFailure(Throwable error) {
        if (error instanceof TimeoutException) {
            return McpRetryPolicy.FailureKind.TIMEOUT;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getName();
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.NoRouteToHostException) {
                return McpRetryPolicy.FailureKind.CONNECTION_FAILURE;
            }
            if (name.contains("Timeout")) {
                return McpRetryPolicy.FailureKind.TIMEOUT;
            }
        }
        if (error instanceof WebClientRequestException) {
            return McpRetryPolicy.FailureKind.CONNECTION_FAILURE;
        }
        // Any pre-response failure not otherwise classified is connection-class.
        return McpRetryPolicy.FailureKind.CONNECTION_FAILURE;
    }

    private void record(CallContext context, String rpcMethod, String toolName, McpAccessStatus status,
            Integer httpStatus) {
        record(context, rpcMethod, toolName, status, httpStatus, null);
    }

    /**
     * FORWARDED rows carry the upstream first-byte latency (#358); others pass
     * null.
     */
    private void record(CallContext context, String rpcMethod, String toolName, McpAccessStatus status,
            Integer httpStatus, Long ttfbMs) {
        accessLogSink.record(new McpAccessLogEntry(UUID.randomUUID(), context.service.tenantId(), context.service.id(),
                context.service.name(), context.consumer.id(), context.consumer.name(), rpcMethod, toolName, status,
                httpStatus, context.gatewayRequestId, Instant.now(), context.sessionId, ttfbMs));
    }

    private static final class CallContext {
        private final RouteSnapshot.ConsumerRecord consumer;
        private final RouteSnapshot.McpServerRecord service;
        private final String gatewayRequestId;
        /**
         * Log-row session correlation (client Session-Id header or inbound SSE
         * session).
         */
        private final String sessionId;

        private CallContext(RouteSnapshot.ConsumerRecord consumer, RouteSnapshot.McpServerRecord service,
                String gatewayRequestId, String sessionId) {
            this.consumer = consumer;
            this.service = service;
            this.gatewayRequestId = gatewayRequestId;
            this.sessionId = sessionId;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Caller authentication (#340): {@code X-API-Key} is key-only; an
     * {@code Authorization: Bearer} value with the {@code mqk_api_} prefix is a key
     * (digest scan over the snapshot), anything else is treated as a consumer RS256
     * JWT — {@code sub} maps to the consumer by name, the signature verifies
     * against the snapshot PEM, and every failure is a plain null (401, same shape
     * as an unknown key). Downstream expiry/scope/ ACL checks apply identically to
     * both credential types.
     */
    private static RouteSnapshot.ConsumerRecord authenticate(ServerHttpRequest request, RouteSnapshot snapshot) {
        String apiKeyHeader = request.getHeaders().getFirst("x-api-key");
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            return snapshot.consumerByDigest(sha256(apiKeyHeader.trim()));
        }
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                || auth.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        String token = auth.substring(BEARER_PREFIX.length()).trim();
        if (token.startsWith(CONSUMER_KEY_PREFIX)) {
            return snapshot.consumerByDigest(sha256(token));
        }
        String subject = ConsumerJwtVerifier.extractSubject(token);
        if (subject == null) {
            return null;
        }
        RouteSnapshot.ConsumerRecord consumer = snapshot.consumerByName(subject);
        if (consumer == null || consumer.jwtPublicKeyPem() == null) {
            return null;
        }
        return JWT_VERIFIER.verify(token, consumer.jwtPublicKeyPem(), subject) ? consumer : null;
    }

    private static byte[] concatBuffers(List<DataBuffer> buffers) {
        int total = buffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
        byte[] body = new byte[total];
        int offset = 0;
        for (DataBuffer buffer : buffers) {
            int len = buffer.readableByteCount();
            buffer.read(body, offset, len);
            offset += len;
        }
        return body;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static McpAclMode parseMode(String mode) {
        return mode == null || mode.isBlank() ? McpAclMode.NONE : McpAclMode.valueOf(mode);
    }

    private Mono<Void> error(ServerHttpResponse response, HttpStatus status, String type, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(problemJson(type, message)))).then();
    }

    /** Gateway error body shared by the direct and SSE transports. */
    private static byte[] problemJson(String type, String message) {
        return ("{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] join(List<byte[]> chunks) {
        int total = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        byte[] joined = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, joined, offset, chunk.length);
            offset += chunk.length;
        }
        return joined;
    }

    /**
     * Where a call's outcome is delivered: the HTTP exchange (direct POST) or an
     * SSE session stream.
     */
    private interface ResponseTarget {

        /** Delivers the upstream response (status + headers + raw body bytes). */
        Mono<Void> complete(int status, HttpHeaders headers, Flux<byte[]> body);

        /** Delivers a gateway-shaped error with the shared problem JSON. */
        Mono<Void> errorResponse(HttpStatus status, String type, String message);
    }

    /** Direct transport: the current HTTP response (behavior unchanged). */
    private final class ExchangeTarget implements ResponseTarget {

        private final ServerWebExchange exchange;

        private ExchangeTarget(ServerWebExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public Mono<Void> complete(int status, HttpHeaders headers, Flux<byte[]> body) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.valueOf(status));
            response.getHeaders().addAll(HeaderFilters.filterResponseHeaders(headers));
            return response.writeWith(body.map(response.bufferFactory()::wrap)).then();
        }

        @Override
        public Mono<Void> errorResponse(HttpStatus status, String type, String message) {
            return error(exchange.getResponse(), status, type, message);
        }
    }

    /**
     * SSE transport (issue #356): upstream bodies are relayed verbatim as one
     * {@code message} event (streaming upstream SSE responses are aggregated —
     * documented v1 limitation); gateway failures travel as {@code error} events
     * with the same problem JSON as the direct path.
     */
    private final class SseTarget implements ResponseTarget {

        private final McpSseSessionRegistry.SseSession session;

        private SseTarget(McpSseSessionRegistry.SseSession session) {
            this.session = session;
        }

        @Override
        public Mono<Void> complete(int status, HttpHeaders headers, Flux<byte[]> body) {
            return body.collectList().doOnNext(chunks -> session.emit(SseFrames.event("message", join(chunks)))).then();
        }

        @Override
        public Mono<Void> errorResponse(HttpStatus status, String type, String message) {
            session.emit(SseFrames.event("error", problemJson(type, message)));
            return Mono.empty();
        }
    }
}
