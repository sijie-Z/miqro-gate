package com.miqroera.miqrokey.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.Map;

/**
 * Data-plane request counter (G6.1). The only dimension is the status class
 * (2xx/3xx/4xx/5xx), stored as five low-cardinality counters — user, key, model
 * and vendor identifiers are forbidden as metric labels
 * (configuration-reference §8 high-cardinality rule). Metrics work without the
 * monitoring profile; the scrape endpoint appears only under it.
 */
@Component
public class GatewayMetricsFilter implements WebFilter {

    private final Map<StatusClass, Counter> counters = new EnumMap<>(StatusClass.class);

    public GatewayMetricsFilter(MeterRegistry registry) {
        for (StatusClass statusClass : StatusClass.values()) {
            counters.put(statusClass,
                    Counter.builder("miqrokey_gateway_requests_total")
                            .description("Inbound gateway requests by status class")
                            .tag("status_class", statusClass.name().toLowerCase()).register(registry));
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).doOnSuccess(v -> count(exchange));
    }

    private void count(ServerWebExchange exchange) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        StatusClass statusClass = status != null ? StatusClass.of(status.value()) : StatusClass.OTHER;
        counters.get(statusClass).increment();
    }

    private enum StatusClass {
        INFO, SUCCESS, REDIRECT, CLIENT_ERROR, SERVER_ERROR, OTHER;

        static StatusClass of(int code) {
            return switch (code / 100) {
                case 1 -> INFO;
                case 2 -> SUCCESS;
                case 3 -> REDIRECT;
                case 4 -> CLIENT_ERROR;
                case 5 -> SERVER_ERROR;
                default -> OTHER;
            };
        }
    }
}
