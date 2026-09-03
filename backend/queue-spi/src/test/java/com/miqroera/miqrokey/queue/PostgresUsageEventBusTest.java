package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStartedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStatus;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostgresUsageEventBus} reliability contract (no database): failure
 * re-enqueue (no silent loss), saturation drop accounting, bounded per-flush
 * drain, and the in-flight overlap guard on the scheduling thread.
 */
@DisplayName("Postgres usage event bus reliability")
class PostgresUsageEventBusTest {

    private static final Clock CLOCK = Clock.systemUTC();

    private Scheduler scheduler;

    @AfterEach
    void disposeScheduler() {
        if (scheduler != null) {
            scheduler.dispose();
        }
    }

    @Test
    @DisplayName("a failed flush re-enqueues every drained event; the next flush persists them")
    void failedFlushReenqueuesForNextFlush() {
        RecordingWriter writer = new RecordingWriter();
        PostgresUsageEventBus bus = new PostgresUsageEventBus(100, 100, writer, Schedulers.immediate(), CLOCK,
                SaturationMode.DROP, Duration.ofSeconds(5));
        UsageEvent usage = usageEvent("u1");
        CacheHitEvent hit = hitEvent();
        RequestStartedEvent start = startedEvent();
        RequestCompletedEvent completion = completedEvent();
        bus.publish(usage);
        bus.publish(hit);
        bus.publish(start);
        bus.publish(completion);
        writer.failNextCall = true;

        bus.flush();
        assertThat(writer.callCount.get()).isEqualTo(1);
        assertThat(writer.lastFailure).isNotNull();
        // Nothing lost: a retried flush writes the same batch again.
        bus.flush();
        assertThat(writer.callCount.get()).isEqualTo(2);
        assertThat(writer.lastUsage).containsExactly(usage);
        assertThat(writer.lastHits).containsExactly(hit);
        assertThat(writer.lastStarts).containsExactly(start);
        assertThat(writer.lastCompletions).containsExactly(completion);
        UsageEventBus.QueueMetrics m = bus.metrics();
        assertThat(m.queuedCount()).isZero();
        assertThat(m.totalPersisted()).isEqualTo(4);
        assertThat(m.totalDropped()).isZero();
    }

    @Test
    @DisplayName("a saturated queue drops offers and counts them; never blocks the publisher")
    void saturationDropsAndCounts() {
        RecordingWriter writer = new RecordingWriter();
        PostgresUsageEventBus bus = new PostgresUsageEventBus(2, 100, writer, Schedulers.immediate(), CLOCK,
                SaturationMode.DROP, Duration.ofSeconds(5));
        for (int i = 0; i < 5; i++) {
            bus.publish(usageEvent("u" + i));
        }
        UsageEventBus.QueueMetrics m = bus.metrics();
        assertThat(m.queuedCount()).isEqualTo(2);
        assertThat(m.totalDropped()).isEqualTo(3);
        // totalPublished counts accepted offers only (capacity 2).
        assertThat(m.totalPublished()).isEqualTo(2);
        bus.flush();
        assertThat(bus.metrics().totalPersisted()).isEqualTo(2);
    }

    @Test
    @DisplayName("flush drains at most flush-threshold events; the rest stay queued")
    void flushRespectsThreshold() {
        RecordingWriter writer = new RecordingWriter();
        PostgresUsageEventBus bus = new PostgresUsageEventBus(10, 2, writer, Schedulers.immediate(), CLOCK,
                SaturationMode.DROP, Duration.ofSeconds(5));
        bus.publish(usageEvent("a"));
        bus.publish(usageEvent("b"));
        bus.publish(usageEvent("c"));

        bus.flush();
        assertThat(writer.lastUsage).hasSize(2);
        assertThat(bus.metrics().queuedCount()).isEqualTo(1);

        bus.flush();
        assertThat(writer.lastUsage).hasSize(1);
        assertThat(bus.metrics().queuedCount()).isZero();
    }

    @Test
    @DisplayName("overlapping scheduled flushes are skipped: a flush in flight is never doubled")
    void inFlightGuardSkipsOverlappingFlushes() throws Exception {
        BlockingWriter writer = new BlockingWriter();
        scheduler = Schedulers.newSingle("writer-test");
        PostgresUsageEventBus bus = new PostgresUsageEventBus(10, 10, writer, scheduler, CLOCK, SaturationMode.DROP,
                Duration.ofSeconds(5));
        bus.publish(usageEvent("u1"));

        bus.scheduledFlush();
        assertThat(writer.entered.await(5, TimeUnit.SECONDS)).isTrue();
        bus.scheduledFlush(); // in-flight: must not schedule a second task
        writer.release.countDown();
        assertThat(writer.done.await(5, TimeUnit.SECONDS)).isTrue();
        awaitFlushCount(bus, 1);

        assertThat(writer.callCount.get()).isEqualTo(1);
        assertThat(bus.metrics().totalPersisted()).isEqualTo(1);
    }

    @Test
    @DisplayName("empty flush is a no-op (no writer call, no metrics movement)")
    void emptyFlushIsNoop() {
        RecordingWriter writer = new RecordingWriter();
        PostgresUsageEventBus bus = new PostgresUsageEventBus(10, 10, writer, Schedulers.immediate(), CLOCK,
                SaturationMode.DROP, Duration.ofSeconds(5));
        bus.flush();
        assertThat(writer.callCount.get()).isZero();
    }

    @Test
    @DisplayName("WRITE_THROUGH saturation persists the single event through the writer executor")
    void writeThroughPersistsOnSaturation() {
        RecordingWriter writer = new RecordingWriter();
        PostgresUsageEventBus bus = new PostgresUsageEventBus(1, 100, writer, Schedulers.immediate(), CLOCK,
                SaturationMode.WRITE_THROUGH, Duration.ofSeconds(5));
        bus.publish(usageEvent("queued")); // fills the only slot
        UsageEvent spill = usageEvent("spill");

        bus.publish(spill); // queue full -> emergency single-event write

        assertThat(bus.metrics().totalDropped()).isZero();
        // Only the write-through event is persisted so far; the queued one waits
        // for the next regular flush.
        assertThat(bus.metrics().totalPersisted()).isEqualTo(1);
        assertThat(bus.metrics().queuedCount()).isEqualTo(1);
        assertThat(writer.lastUsage).containsExactly(spill);

        bus.flush();
        assertThat(bus.metrics().totalPersisted()).isEqualTo(2);
        assertThat(bus.metrics().queuedCount()).isZero();
    }

    @Test
    @DisplayName("WRITE_THROUGH falls back to a counted drop when the emergency write fails")
    void writeThroughFailureCountsDrop() {
        RecordingWriter writer = new RecordingWriter();
        PostgresUsageEventBus bus = new PostgresUsageEventBus(1, 100, writer, Schedulers.immediate(), CLOCK,
                SaturationMode.WRITE_THROUGH, Duration.ofSeconds(5));
        bus.publish(usageEvent("queued"));
        writer.failNextCall = true;

        bus.publish(usageEvent("spill"));

        UsageEventBus.QueueMetrics m = bus.metrics();
        assertThat(m.totalDropped()).isEqualTo(1);
        assertThat(m.totalPersisted()).isZero(); // nothing has been persisted yet
    }

    @Test
    @DisplayName("WRITE_THROUGH times out instead of stalling the publisher forever")
    void writeThroughTimesOut() throws Exception {
        BlockingWriter writer = new BlockingWriter();
        scheduler = Schedulers.newSingle("writer-test");
        PostgresUsageEventBus bus = new PostgresUsageEventBus(1, 100, writer, scheduler, CLOCK,
                SaturationMode.WRITE_THROUGH, Duration.ofMillis(50));
        bus.publish(usageEvent("queued"));

        long started = System.nanoTime();
        bus.publish(usageEvent("spill")); // blocks on the writer latch -> times out
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // The bounded stall returned while the writer was still blocked.
        assertThat(elapsedMs).isLessThan(2_000);
        assertThat(bus.metrics().totalDropped()).isEqualTo(1);
        assertThat(bus.metrics().totalPersisted()).isZero();

        writer.release.countDown();
        writer.done.await(5, TimeUnit.SECONDS);
    }

    /** Polls until the flush counter reaches the target (writer thread lag). */
    private static void awaitFlushCount(PostgresUsageEventBus bus, long target) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bus.metrics().flushCount() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(bus.metrics().flushCount()).isEqualTo(target);
    }

    // -------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------

    private static UsageEvent usageEvent(String providerRequestId) {
        return new UsageEvent(UUID.randomUUID(), UUID.randomUUID(), providerRequestId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "model-x", CacheLevel.UPSTREAM,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), 42L, 200, null, true, false, "gw-1",
                CLOCK.instant());
    }

    private static CacheHitEvent hitEvent() {
        return new CacheHitEvent(UUID.randomUUID(), UUID.randomUUID(), new byte[]{1, 2}, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), CacheLevel.L1_HIT, "gw-1", CLOCK.instant());
    }

    private static RequestStartedEvent startedEvent() {
        return new RequestStartedEvent(UUID.randomUUID(), CLOCK.instant(), "gw-1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ANTHROPIC_MESSAGES", "model-x", false);
    }

    private static RequestCompletedEvent completedEvent() {
        return new RequestCompletedEvent(UUID.randomUUID(), CLOCK.instant(), "gw-1", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "ANTHROPIC_MESSAGES", "model-x", false, "up-1", CLOCK.instant(), CLOCK.instant(),
                100L, 40L, 200, RequestStatus.SUCCEEDED, false, false,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), false, 0);
    }

    private static final class RecordingWriter implements UsageEventWriter {

        final AtomicInteger callCount = new AtomicInteger();
        volatile boolean failNextCall;
        volatile RuntimeException lastFailure;
        volatile List<UsageEvent> lastUsage = List.of();
        volatile List<CacheHitEvent> lastHits = List.of();
        volatile List<RequestStartedEvent> lastStarts = List.of();
        volatile List<RequestCompletedEvent> lastCompletions = List.of();

        @Override
        public void writeBatch(List<UsageEvent> usageEvents, List<CacheHitEvent> hitEvents,
                List<RequestStartedEvent> startedEvents, List<RequestCompletedEvent> completedEvents) {
            callCount.incrementAndGet();
            if (failNextCall) {
                failNextCall = false;
                lastFailure = new IllegalStateException("simulated database outage");
                throw lastFailure;
            }
            lastUsage = List.copyOf(usageEvents);
            lastHits = List.copyOf(hitEvents);
            lastStarts = List.copyOf(startedEvents);
            lastCompletions = List.copyOf(completedEvents);
        }
    }

    private static final class BlockingWriter implements UsageEventWriter {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public void writeBatch(List<UsageEvent> usageEvents, List<CacheHitEvent> hitEvents,
                List<RequestStartedEvent> startedEvents, List<RequestCompletedEvent> completedEvents) {
            callCount.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.countDown();
        }
    }
}
