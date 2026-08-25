package com.miqroera.miqrokey.gateway.config;

import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.queue.InMemoryUsageEventBus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueueMetricsBinder} gauge contract: queue depth, published/persisted/
 * dropped totals, flush count, and last flush duration are exposed on a
 * {@link SimpleMeterRegistry} with no labels — no user ids, keys, or request
 * content ever becomes a tag.
 */
@DisplayName("Usage queue metrics binder")
class QueueMetricsBinderTest {

    @Test
    @DisplayName("gauges track queued/published/persisted/dropped/flush across publish and flush")
    void gaugesTrackBusState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InMemoryUsageEventBus bus = new InMemoryUsageEventBus(10);
        new QueueMetricsBinder(bus, registry);

        bus.publish(usageEvent());
        bus.publish(usageEvent());
        bus.publish(usageEvent());

        assertThat(gauge(registry, "miqrokey.usage.queue.queued")).isEqualTo(3.0);
        assertThat(gauge(registry, "miqrokey.usage.queue.published.total")).isEqualTo(3.0);
        assertThat(gauge(registry, "miqrokey.usage.queue.persisted.total")).isZero();

        bus.flush();

        assertThat(gauge(registry, "miqrokey.usage.queue.queued")).isZero();
        assertThat(gauge(registry, "miqrokey.usage.queue.persisted.total")).isEqualTo(3.0);
        assertThat(gauge(registry, "miqrokey.usage.queue.dropped.total")).isZero();
        assertThat(gauge(registry, "miqrokey.usage.queue.flush.count")).isEqualTo(1.0);
        assertThat(gauge(registry, "miqrokey.usage.queue.flush.last.duration.seconds")).isZero();
    }

    @Test
    @DisplayName("saturation counts as dropped, not published")
    void saturationCountsDropped() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InMemoryUsageEventBus bus = new InMemoryUsageEventBus(2);
        new QueueMetricsBinder(bus, registry);

        bus.publish(usageEvent());
        bus.publish(usageEvent());
        bus.publish(usageEvent()); // capacity 2 -> dropped

        assertThat(gauge(registry, "miqrokey.usage.queue.queued")).isEqualTo(2.0);
        assertThat(gauge(registry, "miqrokey.usage.queue.published.total")).isEqualTo(2.0);
        assertThat(gauge(registry, "miqrokey.usage.queue.dropped.total")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("gauges carry no labels")
    void gaugesCarryNoLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new QueueMetricsBinder(new InMemoryUsageEventBus(10), registry);

        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags()).isEmpty();
        }
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private static UsageEvent usageEvent() {
        return new UsageEvent(UUID.randomUUID(), UUID.randomUUID(), "provider-req-1", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "model-x", CacheLevel.UPSTREAM,
                TokenBucket.EMPTY, 42L, 200, null, true, false, "gw-usage", Instant.now());
    }
}
