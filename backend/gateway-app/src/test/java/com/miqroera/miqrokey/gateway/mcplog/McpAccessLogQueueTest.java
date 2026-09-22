package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.model.McpAccessStatus;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Bounded-queue semantics of the F15 access-log sink: batch drain on flush,
 * drop + count on saturation, requeue-and-retry of a transiently failed batch
 * (writes are idempotent, so retries are safe) and row-by-row isolation of a
 * batch that can never succeed as a whole (#1346).
 */
@DisplayName("MCP access log queue")
class McpAccessLogQueueTest {

    private static McpAccessLogEntry entry(int n) {
        return new McpAccessLogEntry(UUID.randomUUID(), GatewayTestKeys.TENANT_ID, UUID.randomUUID(), "weather-mcp",
                UUID.randomUUID(), "drill-" + n, "tools/call", "forecast", McpAccessStatus.FORWARDED, 200, "req-" + n,
                Instant.now());
    }

    @Test
    @DisplayName("flushing drains every queued entry into one writer batch")
    void flushDrainsAll() {
        CapturingWriter writer = new CapturingWriter();
        try (McpAccessLogQueue queue = new McpAccessLogQueue(64, 10_000, writer)) {
            McpAccessLogEntry first = entry(1);
            queue.record(first);
            queue.record(entry(2));
            queue.record(entry(3));
            queue.flushNow();

            assertThat(writer.batches()).hasSize(1);
            assertThat(writer.batches().get(0)).hasSize(3).contains(first);

            queue.flushNow();
            assertThat(writer.batches()).hasSize(1); // nothing left to flush
        }
    }

    @Test
    @DisplayName("close() drains buffered entries instead of losing them (#451)")
    void closeDrainsBufferedEntries() {
        CapturingWriter writer = new CapturingWriter();
        McpAccessLogQueue queue = new McpAccessLogQueue(64, 60_000, writer);
        queue.record(entry(1));
        queue.record(entry(2));

        queue.close();

        assertThat(writer.batches().stream().flatMap(List::stream).toList()).hasSize(2);
    }

    @Test
    @DisplayName("saturation drops entries and counts them")
    void saturationDropsAndCounts() {
        CapturingWriter writer = new CapturingWriter();
        try (McpAccessLogQueue queue = new McpAccessLogQueue(4, 10_000, writer)) {
            for (int i = 0; i < 6; i++) {
                queue.record(entry(i));
            }
            assertThat(queue.droppedCount()).isEqualTo(2);
            queue.flushNow();
            assertThat(writer.batches().get(0)).hasSize(4);
        }
    }

    @Test
    @DisplayName("a failed batch is requeued and retried without drops")
    void failedBatchIsRequeued() {
        FlakyWriter writer = new FlakyWriter(1);
        try (McpAccessLogQueue queue = new McpAccessLogQueue(64, 10_000, writer)) {
            queue.record(entry(1));
            queue.record(entry(2));
            queue.flushNow();
            assertThat(writer.failures()).isEqualTo(1);
            assertThat(writer.batches()).isEmpty();
            assertThat(queue.droppedCount()).isZero();

            queue.flushNow();
            assertThat(writer.failures()).isEqualTo(1);
            assertThat(writer.batches()).hasSize(1);
            assertThat(writer.batches().get(0)).hasSize(2);
            assertThat(queue.droppedCount()).isZero();
        }
    }

    @Test
    @DisplayName("one unwritable row no longer takes its healthy neighbours down with it (#1346)")
    void deterministicFailureIsIsolatedRowByRow() {
        PoisonRowWriter writer = new PoisonRowWriter("req-1", "22001");
        CapturingForwarder forwarder = new CapturingForwarder(false);
        try (McpAccessLogQueue queue = new McpAccessLogQueue(64, 10_000, writer, List.of(forwarder))) {
            queue.record(entry(1)); // the poison row
            queue.record(entry(2)); // healthy, same batch
            queue.flushNow();

            assertThat(writer.batches()).hasSize(1);
            assertThat(writer.batches().get(0)).extracting(McpAccessLogEntry::gatewayRequestId)
                    .containsExactly("req-2");
            assertThat(queue.unwritableCount()).isEqualTo(1);
            assertThat(queue.droppedCount()).isZero();

            // I19: only the durably written rows are forwarded.
            assertThat(forwarder.batches()).hasSize(1);
            assertThat(forwarder.batches().get(0)).extracting(McpAccessLogEntry::gatewayRequestId)
                    .containsExactly("req-2");

            // The pipeline is still alive: later entries are written on the next flush.
            queue.record(entry(3));
            queue.flushNow();
            assertThat(writer.batches()).hasSize(2);
            assertThat(writer.batches().get(1)).extracting(McpAccessLogEntry::gatewayRequestId)
                    .containsExactly("req-3");
            assertThat(queue.unwritableCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a connection failure is still transient: requeued, never abandoned (#1346)")
    void transientSqlFailureIsStillRequeued() {
        PoisonRowWriter writer = new PoisonRowWriter("req-1", "08006");
        try (McpAccessLogQueue queue = new McpAccessLogQueue(64, 10_000, writer)) {
            queue.record(entry(1));
            queue.record(entry(2));
            queue.flushNow();

            // Nothing written, nothing abandoned — the batch is intact for the retry.
            assertThat(writer.batches()).isEmpty();
            assertThat(queue.unwritableCount()).isZero();
            assertThat(queue.droppedCount()).isZero();

            writer.heal();
            queue.flushNow();
            // The whole batch is retried as one — healing the outage loses nothing.
            assertThat(writer.batches()).hasSize(1);
            assertThat(writer.batches().get(0)).hasSize(2);
            assertThat(queue.unwritableCount()).isZero();
            assertThat(queue.droppedCount()).isZero();
        }
    }

    @Test
    @DisplayName("null entries are ignored, invalid config is rejected")
    void guards() {
        CapturingWriter writer = new CapturingWriter();
        try (McpAccessLogQueue queue = new McpAccessLogQueue(4, 10_000, writer)) {
            queue.record(null);
            queue.flushNow();
            assertThat(writer.batches()).isEmpty();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new McpAccessLogQueue(0, 10_000, writer));
        assertThatIllegalArgumentException().isThrownBy(() -> new McpAccessLogQueue(4, 0, writer));
    }

    @Test
    @DisplayName("forwarders receive exactly the durably written batches (I19)")
    void forwardersSeeWrittenBatchesOnly() {
        CapturingWriter writer = new CapturingWriter();
        CapturingForwarder forwarder = new CapturingForwarder(false);
        try (McpAccessLogQueue queue = new McpAccessLogQueue(64, 10_000, writer, List.of(forwarder))) {
            queue.record(entry(1));
            queue.record(entry(2));
            queue.flushNow();
            assertThat(forwarder.batches()).hasSize(1);
            assertThat(forwarder.batches().get(0)).hasSize(2);

            queue.flushNow(); // nothing queued: no extra forwarding
            assertThat(forwarder.batches()).hasSize(1);
        }

        // A failed write is requeued and NOT forwarded; its retry forwards once.
        FlakyWriter flaky = new FlakyWriter(1);
        CapturingForwarder retryForwarder = new CapturingForwarder(false);
        try (McpAccessLogQueue queue = new McpAccessLogQueue(64, 10_000, flaky, List.of(retryForwarder))) {
            queue.record(entry(3));
            queue.flushNow();
            assertThat(retryForwarder.batches()).isEmpty();
            queue.flushNow();
            assertThat(retryForwarder.batches()).hasSize(1);
            assertThat(flaky.batches()).hasSize(1);
        }
    }

    @Test
    @DisplayName("a throwing forwarder never breaks the flush or the audit rows (I19)")
    void throwingForwarderIsIsolated() {
        CapturingWriter writer = new CapturingWriter();
        CapturingForwarder after = new CapturingForwarder(false);
        try (McpAccessLogQueue queue = new McpAccessLogQueue(64, 10_000, writer,
                List.of(new CapturingForwarder(true), after))) {
            queue.record(entry(1));
            queue.flushNow();
            assertThat(writer.batches()).hasSize(1);
            assertThat(after.batches()).hasSize(1);
            assertThat(queue.droppedCount()).isZero();
        }
    }

    @Test
    @DisplayName("a hanging forwarder no longer blocks the flush pipeline (#401)")
    void hangingForwarderIsIsolated() {
        CapturingWriter writer = new CapturingWriter();
        CapturingForwarder after = new CapturingForwarder(false);
        HangingForwarder hanging = new HangingForwarder();
        try (McpAccessLogQueue queue = new McpAccessLogQueue(64, 10_000, writer,
                List.of(new TimeBoundedForwarder(hanging, 100), after))) {
            queue.record(entry(1));
            queue.flushNow(); // the hanging sink burns its deadline, nothing else stalls
            queue.record(entry(2));
            queue.flushNow();

            assertThat(writer.batches()).hasSize(2);
            assertThat(after.batches()).hasSize(2);
            assertThat(queue.droppedCount()).isZero();
        } finally {
            hanging.release.countDown();
        }
    }

    /** Blocks like a stalled socket write — deliberately ignoring interrupts. */
    private static final class HangingForwarder implements McpAccessLogForwarder {
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String name() {
            return "hanging";
        }

        @Override
        public void forward(List<McpAccessLogEntry> batch) {
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException e) {
                    // like a blocked socket write: not interruptible in practice
                }
            }
        }
    }

    /** Captures every forwarded batch; optionally throws to prove isolation. */
    private static final class CapturingForwarder implements McpAccessLogForwarder {
        private final List<List<McpAccessLogEntry>> batches = new CopyOnWriteArrayList<>();
        private final boolean throwing;

        CapturingForwarder(boolean throwing) {
            this.throwing = throwing;
        }

        @Override
        public String name() {
            return "capturing";
        }

        List<List<McpAccessLogEntry>> batches() {
            return batches;
        }

        @Override
        public void forward(List<McpAccessLogEntry> batch) {
            if (throwing) {
                throw new IllegalStateException("simulated sink failure");
            }
            batches.add(new ArrayList<>(batch));
        }
    }

    /** Records every flushed batch and the entries inside it. */
    private static final class CapturingWriter implements McpAccessLogWriter {
        private final List<List<McpAccessLogEntry>> batches = new CopyOnWriteArrayList<>();

        List<List<McpAccessLogEntry>> batches() {
            return batches;
        }

        @Override
        public void writeBatch(List<McpAccessLogEntry> entries) {
            batches.add(new ArrayList<>(entries));
        }
    }

    /**
     * Fails for as long as the given entry is in the batch (and until
     * {@link #heal()}), with the exception shape a real driver failure reaches the
     * queue as: Spring's {@link UncategorizedSQLException} wrapping the driver's
     * {@link SQLException} carrying {@code sqlState} — exactly the chain seen for
     * SQLSTATE 22001 on an oversize value.
     */
    private static final class PoisonRowWriter implements McpAccessLogWriter {
        private final List<List<McpAccessLogEntry>> batches = new CopyOnWriteArrayList<>();
        private final String poisonRequestId;
        private final String sqlState;
        private boolean healed;

        PoisonRowWriter(String poisonRequestId, String sqlState) {
            this.poisonRequestId = poisonRequestId;
            this.sqlState = sqlState;
        }

        void heal() {
            healed = true;
        }

        List<List<McpAccessLogEntry>> batches() {
            return batches;
        }

        @Override
        public void writeBatch(List<McpAccessLogEntry> entries) {
            boolean poisoned = !healed && entries.stream().anyMatch(e -> poisonRequestId.equals(e.gatewayRequestId()));
            if (poisoned) {
                throw new UncategorizedSQLException("PreparedStatementCallback", "INSERT INTO mcp_access_log ...",
                        new SQLException("ERROR: simulated database rejection", sqlState));
            }
            batches.add(new ArrayList<>(entries));
        }
    }

    /** Fails the first {@code failures} batches, then succeeds. */
    private static final class FlakyWriter implements McpAccessLogWriter {
        private final List<List<McpAccessLogEntry>> batches = new CopyOnWriteArrayList<>();
        private final int failures;
        private int failureCount;

        FlakyWriter(int failures) {
            this.failures = failures;
        }

        int failures() {
            return failureCount;
        }

        List<List<McpAccessLogEntry>> batches() {
            return batches;
        }

        @Override
        public void writeBatch(List<McpAccessLogEntry> entries) {
            if (failureCount < failures) {
                failureCount++;
                throw new IllegalStateException("simulated writer failure");
            }
            batches.add(new ArrayList<>(entries));
        }
    }
}
