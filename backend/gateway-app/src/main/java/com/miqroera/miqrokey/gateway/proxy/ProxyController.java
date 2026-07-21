package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;

@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

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

    @PostMapping("/v1/messages")
    public Mono<Void> proxyMessages(ServerWebExchange exchange) {
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

                    // Use the response body flux directly for true streaming.
                    // When the client disconnects, the writeWith subscription
                    // is cancelled, which propagates upstream via WebClient.
                    Flux<DataBuffer> bodyFlux = upstreamResponse.bodyToFlux(DataBuffer.class);

                    Flux<DataBuffer> observedBody = bodyFlux;
                    if (upstreamResponse.headers().contentType()
                            .filter(type -> type.isCompatibleWith(org.springframework.http.MediaType.TEXT_EVENT_STREAM))
                            .isPresent()) {
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
