package com.miqroera.miqrokey.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counter is wired into the real {@code WebHttpHandlerBuilder} topology:
 * the {@code ExceptionHandlingWebHandler} sits <em>outside</em> the filter
 * chain, so a request whose response is produced by an exception handler
 * completes the inner {@code Mono} with an error signal — {@code doOnSuccess}
 * never runs.
 */
class GatewayMetricsFilterErrorPathTest {

    private static HttpHandler handlerWith(GatewayMetricsFilter filter) {
        WebHandler terminal = exchange -> Mono.error(new IllegalStateException("handler exploded"));
        return WebHttpHandlerBuilder.webHandler(terminal).filter(filter).exceptionHandler((exchange, ex) -> {
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return exchange.getResponse().setComplete();
        }).build();
    }

    @Test
    @DisplayName("a request that really answered 500 is counted as server_error")
    void errorTerminatedRequestIsCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetricsFilter filter = new GatewayMetricsFilter(registry);
        MockServerHttpResponse response = new MockServerHttpResponse();

        handlerWith(filter).handle(MockServerHttpRequest.get("/v1/chat/completions").build(), response).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Counter counter = registry.find("miqrokey_gateway_requests_total").tag("status_class", "server_error")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("control: a normally completed request is still counted")
    void successRequestIsCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetricsFilter filter = new GatewayMetricsFilter(registry);
        MockServerHttpResponse response = new MockServerHttpResponse();

        WebHandler terminal = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        WebHttpHandlerBuilder.webHandler(terminal).filter(filter).build()
                .handle(MockServerHttpRequest.get("/v1/models").build(), response).block();

        Counter counter = registry.find("miqrokey_gateway_requests_total").tag("status_class", "success").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
