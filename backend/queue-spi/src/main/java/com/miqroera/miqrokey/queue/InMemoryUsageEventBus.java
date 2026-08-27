package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStartedEvent;
import com.miqroera.miqrokey.domain.usage.UsageEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory usage event bus. Used when the gateway runs WITHOUT database
 * persistence (tests, PoC mode) and as the default fallback bean. Events are
 * drained into a bounded in-memory record list that tests can assert on;
 * saturation drops with a metric, never blocks the hot path.
 */
public final class InMemoryUsageEventBus implements UsageEventBus {

    private final BlockingQueue<Object> queue;
    private final List<UsageEvent> usageEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<CacheHitEvent> hitEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<RequestStartedEvent> startedEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<RequestCompletedEvent> completedEvents = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong totalPublished = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private final AtomicLong flushCount = new AtomicLong();

    public InMemoryUsageEventBus(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public void publish(UsageEvent event) {
        offer(event);
    }

    @Override
    public void publish(CacheHitEvent event) {
        offer(event);
    }

    @Override
    public void publish(RequestStartedEvent event) {
        offer(event);
    }

    @Override
    public void publish(RequestCompletedEvent event) {
        offer(event);
    }

    private void offer(Object event) {
        if (!queue.offer(event)) {
            totalDropped.incrementAndGet();
        } else {
            totalPublished.incrementAndGet();
        }
    }

    @Override
    public void flush() {
        List<Object> drained = new ArrayList<>();
        queue.drainTo(drained);
        for (Object item : drained) {
            if (item instanceof UsageEvent ue) {
                usageEvents.add(ue);
            } else if (item instanceof CacheHitEvent he) {
                hitEvents.add(he);
            } else if (item instanceof RequestStartedEvent se) {
                startedEvents.add(se);
            } else if (item instanceof RequestCompletedEvent ce) {
                completedEvents.add(ce);
            }
        }
        flushCount.incrementAndGet();
    }

    /** Published usage events since the last flush (test assertions). */
    public List<UsageEvent> usageEvents() {
        flush();
        return List.copyOf(usageEvents);
    }

    /** Published hit events since the last flush (test assertions). */
    public List<CacheHitEvent> hitEvents() {
        flush();
        return List.copyOf(hitEvents);
    }

    /** Published lifecycle starts since the last flush (test assertions). */
    public List<RequestStartedEvent> startedEvents() {
        flush();
        return List.copyOf(startedEvents);
    }

    /** Published lifecycle completions since the last flush (test assertions). */
    public List<RequestCompletedEvent> completedEvents() {
        flush();
        return List.copyOf(completedEvents);
    }

    public void clear() {
        usageEvents.clear();
        hitEvents.clear();
        startedEvents.clear();
        completedEvents.clear();
        queue.clear();
    }

    @Override
    public QueueMetrics metrics() {
        return new QueueMetrics(queue.size(), totalPublished.get(),
                usageEvents.size() + hitEvents.size() + startedEvents.size() + completedEvents.size(),
                totalDropped.get(), flushCount.get(), java.time.Duration.ZERO, null);
    }
}
