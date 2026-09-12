package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.model.McpAccessStatus;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Hard-deadline semantics of the #401 forwarder wrapper. */
@DisplayName("Time-bounded forwarder")
class TimeBoundedForwarderTest {

    private static McpAccessLogEntry entry() {
        return new McpAccessLogEntry(UUID.randomUUID(), GatewayTestKeys.TENANT_ID, UUID.randomUUID(), "weather-mcp",
                UUID.randomUUID(), "drill", "tools/call", "forecast", McpAccessStatus.FORWARDED, 200, "req",
                Instant.now());
    }

    @Test
    @DisplayName("a fast sink is unaffected")
    void fastPath() {
        RecordingForwarder recording = new RecordingForwarder();
        TimeBoundedForwarder bounded = new TimeBoundedForwarder(recording, 1_000);
        bounded.forward(List.of(entry()));
        assertThat(recording.batches()).hasSize(1);
        assertThat(bounded.timeoutStreak()).isZero();
        assertThat(bounded.inCooldown()).isFalse();
    }

    @Test
    @DisplayName("a hanging sink times out at the deadline instead of blocking the caller")
    void deadlineBoundsHang() {
        HangingForwarder hanging = new HangingForwarder();
        TimeBoundedForwarder bounded = new TimeBoundedForwarder(hanging, 100);
        try {
            long start = System.nanoTime();
            bounded.forward(List.of(entry()));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isGreaterThanOrEqualTo(90).isLessThan(5_000);
            assertThat(bounded.timeoutStreak()).isEqualTo(1);
        } finally {
            hanging.release.countDown();
        }
    }

    @Test
    @DisplayName("repeated timeouts trip the cooldown: skips are instant, then the sink is probed again")
    void breakerCooldownThenProbe() throws Exception {
        HangingForwarder hanging = new HangingForwarder();
        TimeBoundedForwarder bounded = new TimeBoundedForwarder(hanging, 50, 200);
        try {
            bounded.forward(List.of(entry()));
            bounded.forward(List.of(entry()));
            bounded.forward(List.of(entry()));
            assertThat(bounded.inCooldown()).isTrue();

            long start = System.nanoTime();
            bounded.forward(List.of(entry())); // skipped, not attempted
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(40);
            // Only the first task ever reached the sink; the rest queued behind it.
            assertThat(hanging.calls()).isEqualTo(1);

            // The sink eventually recovers; after the cooldown the next batch probes it.
            hanging.release.countDown();
            Thread.sleep(150); // the stuck task and the queued ones drain
            Thread.sleep(250); // pass the cooldown
            bounded.forward(List.of(entry()));
            assertThat(bounded.timeoutStreak()).isZero();
            assertThat(bounded.inCooldown()).isFalse();
            assertThat(hanging.calls()).isGreaterThanOrEqualTo(2);
        } finally {
            hanging.release.countDown();
        }
    }

    @Test
    @DisplayName("invalid deadline or cooldown is rejected")
    void guards() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TimeBoundedForwarder(new RecordingForwarder(), 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TimeBoundedForwarder(new RecordingForwarder(), 100, 0));
    }

    /** Records every batch it receives. */
    private static final class RecordingForwarder implements McpAccessLogForwarder {
        private final List<List<McpAccessLogEntry>> batches = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void forward(List<McpAccessLogEntry> batch) {
            batches.add(batch);
        }

        List<List<McpAccessLogEntry>> batches() {
            return batches;
        }
    }

    /** Blocks like a stalled socket write — deliberately ignoring interrupts. */
    private static final class HangingForwarder implements McpAccessLogForwarder {
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "hanging";
        }

        @Override
        public void forward(List<McpAccessLogEntry> batch) {
            calls.incrementAndGet();
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException e) {
                    // like a blocked socket write: not interruptible in practice
                }
            }
        }

        int calls() {
            return calls.get();
        }
    }
}
