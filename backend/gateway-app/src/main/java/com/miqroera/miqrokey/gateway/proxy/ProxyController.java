package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * Transparent reactive proxy for Anthropic Messages, OpenAI Responses, and
 * OpenAI Chat Completions.
 *
 * <p>
 * All three protocols share a single proxy kernel. Only POST requests to the
 * three supported paths are forwarded; unsupported paths or methods receive a
 * stable error without contacting the upstream provider.
 * </p>
 *
 * <p>
 * The proxy kernel preserves exact request/response bytes, raw query encoding
 * and ordering, upstream status codes and headers, SSE event ordering,
 * backpressure, UTF-8 chunk safety, and downstream cancellation. Credentials,
 * hop-by-hop headers, Connection-nominated headers, framing headers, and forged
 * {@code X-MiQroKey-*} tracking headers are stripped.
 * </p>
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private static final Set<String> ALLOWED_PATHS = Set.of("/v1/messages", "/v1/responses", "/v1/chat/completions");

    // Anthropic-compatible error format (wraps in {"type":"error","error":{...}})
    private static final byte[] ANTHROPIC_METHOD_NOT_ALLOWED_BODY = """
            {"type":"error","error":{"type":"method_not_allowed","message":"Only POST is supported on this path"}}"""
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] ANTHROPIC_UNSUPPORTED_PATH_BODY = """
            {"type":"error","error":{"type":"unsupported_path","message":"Only /v1/messages, /v1/responses, and /v1/chat/completions are supported"}}"""
            .getBytes(StandardCharsets.UTF_8);

    // OpenAI-compatible error format (both Responses and Chat use {"error":{...}})
    private static final byte[] OPENAI_METHOD_NOT_ALLOWED_BODY = """
            {"error":{"type":"method_not_allowed","message":"Only POST is supported on this path"}}"""
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] OPENAI_UNSUPPORTED_PATH_BODY = """
            {"error":{"type":"unsupported_path","message":"Only /v1/messages, /v1/responses, and /v1/chat/completions are supported"}}"""
            .getBytes(StandardCharsets.UTF_8);

    private final WebClient webClient;
    private final URI upstreamBaseUri;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final int maxProxyBufferBytes;

    public ProxyController(WebClient proxyWebClient, ProxyTargetProperties properties, Clock clock,
            ObjectMapper objectMapper) {
        this.webClient = proxyWebClient;
        this.upstreamBaseUri = properties.url() != null ? URI.create(properties.url()) : null;
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
     * endpoints.
     *
     * <p>
     * Returns 405 for wrong methods on allowed paths and 404 for unknown paths.
     * Error bodies are protocol-compatible: Anthropic-style for
     * {@code /v1/messages} and OpenAI-style for {@code /v1/responses} and
     * {@code /v1/chat/completions}. Unknown paths use a stable generic JSON
     * envelope. Neither case contacts the upstream provider.
     * </p>
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

    /**
     * Shared transparent proxy pipeline used by all three supported protocols.
     *
     * <p>
     * The pipeline:
     * <ol>
     * <li>Validates the upstream URL is configured.</li>
     * <li>Creates per-request observability primitives (TTFB, SSE usage).</li>
     * <li>Builds the upstream URI preserving raw path and query.</li>
     * <li>Filters inbound headers (credentials, hop-by-hop, tracking).</li>
     * <li>Proxies the request body to upstream via {@link WebClient}.</li>
     * <li>Streams the response body back to the client through bounded observation
     * layers.</li>
     * <li>Propagates cancellation upstream.</li>
     * </ol>
     * </p>
     */
    private Mono<Void> proxyRequest(ServerWebExchange exchange) {
        if (upstreamBaseUri == null) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return response.setComplete();
        }

        String requestId = UUID.randomUUID().toString();
        long startMillis = clock.millis();
        TtfbRecorder ttfbRecorder = new TtfbRecorder(requestId, startMillis, clock);
        SseUsageObserver usageObserver = new SseUsageObserver(objectMapper, maxProxyBufferBytes);

        URI upstreamUri = buildUpstreamUri(exchange);
        HttpHeaders filteredHeaders = HeaderFilters.filterInboundHeaders(exchange.getRequest().getHeaders());
        ServerHttpResponse clientResponse = exchange.getResponse();

        return webClient.post().uri(upstreamUri).headers(h -> h.addAll(filteredHeaders))
                .body(BodyInserters.fromDataBuffers(exchange.getRequest().getBody()))
                .exchangeToMono(upstreamResponse -> {
                    int status = upstreamResponse.statusCode().value();
                    log.debug("Upstream response: requestId={}, status={}", requestId, status);
                    clientResponse.setStatusCode(HttpStatusCode.valueOf(status));

                    HttpHeaders responseHeaders = HeaderFilters
                            .filterResponseHeaders(upstreamResponse.headers().asHttpHeaders());
                    clientResponse.getHeaders().addAll(responseHeaders);

                    Flux<DataBuffer> bodyFlux = upstreamResponse.bodyToFlux(DataBuffer.class);

                    Flux<DataBuffer> observedBody = bodyFlux;
                    if (upstreamResponse.headers().contentType()
                            .filter(type -> type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)).isPresent()) {
                        observedBody = usageObserver.wrap(observedBody);
                    }
                    observedBody = ttfbRecorder.wrap(observedBody);

                    observedBody = observedBody
                            .doOnComplete(
                                    () -> ttfbRecorder.recordCompletion(reactor.core.publisher.SignalType.ON_COMPLETE))
                            .doOnCancel(() -> ttfbRecorder.recordCompletion(reactor.core.publisher.SignalType.CANCEL))
                            .doOnError(
                                    error -> ttfbRecorder.recordCompletion(reactor.core.publisher.SignalType.ON_ERROR));

                    return clientResponse.writeWith(observedBody);
                });
    }

    private URI buildUpstreamUri(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        StringBuilder sb = new StringBuilder(upstreamBaseUri.toString());
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
}
