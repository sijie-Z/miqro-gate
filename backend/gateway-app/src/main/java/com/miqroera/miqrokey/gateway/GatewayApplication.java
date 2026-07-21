package com.miqroera.miqrokey.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MiQroKey Inference Gateway — WebFlux-based transparent proxy.
 *
 * <p>
 * This is the hot-path data plane. It MUST NOT perform blocking JDBC, file I/O,
 * or synchronous HTTP calls on the Reactor event loop. All route/cache
 * configuration is loaded from versioned read-only snapshots at startup and
 * refreshed via events.
 * </p>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
