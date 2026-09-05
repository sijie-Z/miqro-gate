package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

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
    /** Per-attempt upstream budget (Tencent default 60s). */
    private static final Duration MCP_TIMEOUT = Duration.ofSeconds(60);

    private final RouteSnapshotProvider routeSnapshotProvider;
    private final WebClient proxyWebClient;
    private final ObjectMapper objectMapper;
    private final McpAccessLogSink accessLogSink;
    private final McpCircuitBreakerRegistry circuitRegistry;

    public McpProxyController(RouteSnapshotProvider routeSnapshotProvider, WebClient proxyWebClient,
            ObjectMapper objectMapper, McpAccessLogSink accessLogSink, Clock clock) {
        this.routeSnapshotProvider = routeSnapshotProvider;
        this.proxyWebClient = proxyWebClient;
        this.objectMapper = objectMapper;
        this.accessLogSink = accessLogSink;
        this.circuitRegistry = new McpCircuitBreakerRegistry(clock);
    }

    @PostMapping("/mcpservers/{serviceName}/mcp")
    public Mono<Void> invoke(ServerWebExchange exchange, @PathVariable String serviceName) {
        RouteSnapshot snapshot = routeSnapshotProvider.current();
        RouteSnapshot.ConsumerRecord consumer = authenticate(exchange.getRequest(), snapshot);
        if (consumer == null) {
            return error(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "invalid_api_key", "Unknown API key");
        }
        RouteSnapshot.McpServerRecord service = snapshot.mcpService(serviceName);
        if (service == null) {
            return error(exchange.getResponse(), HttpStatus.NOT_FOUND, "mcp_service_not_found", "Unknown MCP service");
        }
        String gatewayRequestId = UUID.randomUUID().toString();
        return exchange.getRequest().getBody().collectList().map(McpProxyController::concatBuffers)
                .flatMap(body -> authorizeAndForward(exchange, consumer, service, body, gatewayRequestId));
    }

    private Mono<Void> authorizeAndForward(ServerWebExchange exchange, RouteSnapshot.ConsumerRecord consumer,
            RouteSnapshot.McpServerRecord service, byte[] body, String gatewayRequestId) {
        CallContext context = new CallContext(consumer, service, gatewayRequestId);
        try {
            JsonNode envelope = objectMapper.readTree(body);
            String rpcMethod = textOrNull(envelope.path("method"));
            String toolName = "tools/call".equals(rpcMethod) ? textOrNull(envelope.path("params").path("name")) : null;
            McpAclMode serverMode = parseMode(service.aclMode());
            if (!McpAccessPolicy.isAllowed(serverMode, service.serverConsumerIds(), null, List.of(), consumer.id())) {
                record(context, rpcMethod, toolName, McpAccessStatus.SERVICE_DENIED, 403);
                return error(exchange.getResponse(), HttpStatus.FORBIDDEN, "mcp_access_denied",
                        "Consumer is not allowed to call this MCP service");
            }
            RouteSnapshot.McpToolRecord tool = null;
            if (toolName != null && !toolName.isBlank()) {
                tool = service.tool(toolName);
                if (tool == null || !"ENABLED".equals(tool.status())) {
                    record(context, rpcMethod, toolName, McpAccessStatus.TOOL_UNAVAILABLE, 403);
                    return error(exchange.getResponse(), HttpStatus.FORBIDDEN, "mcp_tool_unavailable",
                            "Tool is unknown or disabled: " + toolName);
                }
                McpAclMode overrideMode = parseMode(tool.overrideMode());
                if (overrideMode != null && !McpAccessPolicy.isAllowed(serverMode, service.serverConsumerIds(),
                        overrideMode, tool.toolConsumerIds(), consumer.id())) {
                    record(context, rpcMethod, toolName, McpAccessStatus.TOOL_DENIED, 403);
                    return error(exchange.getResponse(), HttpStatus.FORBIDDEN, "mcp_access_denied",
                            "Consumer is not allowed to call tool: " + toolName);
                }
            }
            log.info("aigw.mcp.call requestId={} service={} consumer={} rpcMethod={} tool={}", gatewayRequestId,
                    service.name(), consumer.name(), rpcMethod == null ? "-" : rpcMethod,
                    toolName == null ? "-" : toolName);
            McpResiliencePolicy policy = service.resilience() == null
                    ? McpResiliencePolicy.disabled()
                    : service.resilience();
            String toolHttpMethod = tool == null ? null : tool.method();
            return forward(exchange, service.endpoint(), body, context, rpcMethod, toolName, toolHttpMethod, policy);
        } catch (Exception e) {
            log.warn("aigw.mcp.invalid envelope service={}: {}", service.name(), e.getMessage());
            record(context, null, null, McpAccessStatus.INVALID_ENVELOPE, 400);
            return error(exchange.getResponse(), HttpStatus.BAD_REQUEST, "invalid_jsonrpc", "Invalid JSON-RPC body");
        }
    }

    private Mono<Void> forward(ServerWebExchange exchange, String endpoint, byte[] body, CallContext context,
            String rpcMethod, String toolName, String toolHttpMethod, McpResiliencePolicy policy) {
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
        String bucket = toolName != null ? toolName : (rpcMethod == null ? "envelope" : rpcMethod);
        McpCircuitBreaker breaker = policy.breakerEnabled()
                ? circuitRegistry.get(context.service.id(), bucket, policy).breaker()
                : null;
        return attempt(exchange, endpoint, body, headers, context, rpcMethod, toolName, toolHttpMethod, policy, breaker,
                0, new boolean[1]);
    }

    /**
     * One upstream attempt with retry/breaker orchestration. Retries only happen
     * before any response byte reached the caller; a retryable 5xx or a
     * pre-response transport failure re-enters with {@code attempt + 1} while
     * {@code McpRetryPolicy.shouldRetry} allows it.
     */
    private Mono<Void> attempt(ServerWebExchange exchange, String endpoint, byte[] body, HttpHeaders headers,
            CallContext context, String rpcMethod, String toolName, String toolHttpMethod, McpResiliencePolicy policy,
            McpCircuitBreaker breaker, int attempt, boolean[] rowRecorded) {
        if (breaker != null) {
            McpCircuitBreaker.Decision decision = breaker.beforeCall();
            if (decision == McpCircuitBreaker.Decision.REJECTED) {
                record(context, rpcMethod, toolName, McpAccessStatus.CIRCUIT_OPEN, 503);
                rowRecorded[0] = true;
                log.info("aigw.mcp.circuit_open requestId={} service={} bucket={}", context.gatewayRequestId,
                        context.service.name(), toolName == null ? rpcMethod : toolName);
                return error(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE, "circuit_open",
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
                        return resp.releaseBody()
                                .then(Mono.defer(() -> attempt(exchange, endpoint, body, headers, context, rpcMethod,
                                        toolName, toolHttpMethod, policy, breaker, attempt + 1, rowRecorded)));
                    }
                    record(context, rpcMethod, toolName, McpAccessStatus.FORWARDED, status);
                    rowRecorded[0] = true;
                    if (breaker != null) {
                        breaker.afterCall(!policy.breakerErrorStatusCodes().contains(status), ttfbMs);
                    }
                    clientResponse.setStatusCode(resp.statusCode());
                    HttpHeaders out = HeaderFilters.filterResponseHeaders(resp.headers().asHttpHeaders());
                    clientResponse.getHeaders().addAll(out);
                    return clientResponse
                            .writeWith(resp.bodyToFlux(byte[].class).map(b -> clientResponse.bufferFactory().wrap(b)))
                            .then();
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
                        return attempt(exchange, endpoint, body, headers, context, rpcMethod, toolName, toolHttpMethod,
                                policy, breaker, attempt + 1, rowRecorded);
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
        accessLogSink.record(new McpAccessLogEntry(UUID.randomUUID(), context.service.tenantId(), context.service.id(),
                context.service.name(), context.consumer.id(), context.consumer.name(), rpcMethod, toolName, status,
                httpStatus, context.gatewayRequestId, Instant.now()));
    }

    private static final class CallContext {
        private final RouteSnapshot.ConsumerRecord consumer;
        private final RouteSnapshot.McpServerRecord service;
        private final String gatewayRequestId;

        private CallContext(RouteSnapshot.ConsumerRecord consumer, RouteSnapshot.McpServerRecord service,
                String gatewayRequestId) {
            this.consumer = consumer;
            this.service = service;
            this.gatewayRequestId = gatewayRequestId;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static RouteSnapshot.ConsumerRecord authenticate(ServerHttpRequest request, RouteSnapshot snapshot) {
        String token = extractCredential(request);
        if (token == null || token.isEmpty()) {
            return null;
        }
        return snapshot.consumerByDigest(sha256(token));
    }

    private static String extractCredential(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                && auth.length() > BEARER_PREFIX.length()) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        String apiKey = request.getHeaders().getFirst("x-api-key");
        return apiKey == null ? null : apiKey.trim();
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
        byte[] bytes = ("{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes))).then();
    }
}
