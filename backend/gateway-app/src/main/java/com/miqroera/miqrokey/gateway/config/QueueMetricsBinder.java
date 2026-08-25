package com.miqroera.miqrokey.gateway.config;

import com.miqroera.miqrokey.queue.UsageEventBus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes usage-queue health as Micrometer gauges: depth, published/persisted/
 * dropped totals, flush count, and last flush duration. Registered in-process
 * for the future /actuator/prometheus scrape on the management network (see
 * configuration-reference §8). Gauges carry no labels — no user ids, keys, or
 * request content ever becomes a tag.
 */
@Component
public final class QueueMetricsBinder {

    public QueueMetricsBinder(UsageEventBus usageEventBus, MeterRegistry registry) {
        Gauge.builder("miqrokey.usage.queue.queued", usageEventBus, bus -> bus.metrics().queuedCount())
                .register(registry);
        Gauge.builder("miqrokey.usage.queue.published.total", usageEventBus, bus -> bus.metrics().totalPublished())
                .register(registry);
        Gauge.builder("miqrokey.usage.queue.persisted.total", usageEventBus, bus -> bus.metrics().totalPersisted())
                .register(registry);
        Gauge.builder("miqrokey.usage.queue.dropped.total", usageEventBus, bus -> bus.metrics().totalDropped())
                .register(registry);
        Gauge.builder("miqrokey.usage.queue.flush.count", usageEventBus, bus -> bus.metrics().flushCount())
                .register(registry);
        Gauge.builder("miqrokey.usage.queue.flush.last.duration.seconds", usageEventBus,
                bus -> bus.metrics().lastFlushDuration().toMillis() / 1000.0).register(registry);
    }
}
