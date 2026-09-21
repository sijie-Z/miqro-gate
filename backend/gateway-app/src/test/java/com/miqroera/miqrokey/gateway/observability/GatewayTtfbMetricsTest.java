package com.miqroera.miqrokey.gateway.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GatewayTtfbMetrics")
class GatewayTtfbMetricsTest {

    @Test
    @DisplayName("records a zero-labelled TTFB timer")
    void recordsTtfbTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayTtfbMetrics metrics = new GatewayTtfbMetrics(registry);

        metrics.record(120);
        metrics.record(80);

        var timer = registry.find("miqrokey_gateway_ttfb").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200.0);
        assertThat(timer.max(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
    }
}
