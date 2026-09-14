package com.miqroera.miqrokey.gateway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Data-plane time-to-first-byte (TTFB) timer (#486, deployment-and-operations
 * §5 metrics list: 首包延迟). Records one observation per proxied attempt that saw
 * a first upstream byte — retries only happen before any byte arrives, so a
 * request is counted at most once. Deliberately zero-labelled: user, key, model
 * and vendor identifiers are forbidden as metric labels
 * (configuration-reference §8 high-cardinality rule). Registered in every
 * profile; the scrape endpoint appears only under the monitoring profile.
 */
@Component
public class GatewayTtfbMetrics {

    private final Timer ttfb;

    public GatewayTtfbMetrics(MeterRegistry registry) {
        this.ttfb = Timer.builder("miqrokey_gateway_ttfb").description("Time to first upstream response byte")
                .register(registry);
    }

    /** Records one TTFB observation in milliseconds. */
    public void record(long millis) {
        ttfb.record(millis, TimeUnit.MILLISECONDS);
    }
}
