package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.model.McpAccessStatus;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Bounded-queue semantics of the F15 access-log sink: batch drain on flush,
 * drop + count on saturation, and requeue-and-retry of a failed batch (writes
 * are idempotent, so retries are safe).
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
