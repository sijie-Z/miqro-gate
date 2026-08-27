package com.miqroera.miqrokey.gateway;

import com.miqroera.miqrokey.gateway.config.GatewayFeatureConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * MiQroKey Inference Gateway — WebFlux-based transparent proxy.
 *
 * <p>
 * This is the hot-path data plane. It MUST NOT perform blocking JDBC, file I/O,
 * or synchronous HTTP calls on the Reactor event loop. All
 * route/cache/credential state is loaded from versioned read-only snapshots and
 * refreshed on a schedule; usage facts are written through a bounded event bus.
 * </p>
 *
 * <p>
 * Feature modules (route-snapshot, cache-spi, queue-spi) are wired explicitly
 * via {@link GatewayFeatureConfig} so their beans are never component-scanned
 * into the control plane, and so persistence-backed beans activate only under
 * {@code miqrokey.gateway.persistence.enabled=true}.
 * </p>
 */
@SpringBootApplication
@Import(GatewayFeatureConfig.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
