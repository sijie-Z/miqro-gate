package com.miqroera.miqrokey.testing.compatibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic tests for {@link ObservationStore}.
 *
 * <p>
 * No test contains any string that resembles a real API key, token, or
 * credential.
 * </p>
 */
@DisplayName("ObservationStore")
class ObservationStoreTest {

    // -------------------------------------------------------------------
    // Positive-capacity validation
    // -------------------------------------------------------------------

    @Test
    @DisplayName("zero capacity is rejected")
    void zeroCapacityIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ObservationStore(0))
                .withMessageContaining("positive");
    }

    @Test
    @DisplayName("negative capacity is rejected")
    void negativeCapacityIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ObservationStore(-1))
                .withMessageContaining("positive");
    }

    @Test
    @DisplayName("positive capacity is accepted and queryable")
    void positiveCapacityIsAccepted() {
        ObservationStore store = new ObservationStore(10);
        assertThat(store.capacity()).isEqualTo(10);
    }

    // -------------------------------------------------------------------
    // Empty snapshot
    // -------------------------------------------------------------------

    @Test
    @DisplayName("snapshot of fresh store is empty")
    void emptyStoreSnapshotIsEmpty() {
        ObservationStore store = new ObservationStore(5);
        List<RequestObservation> snap = store.snapshot();
        assertThat(snap).isEmpty();
        assertThat(store.size()).isZero();
    }

    // -------------------------------------------------------------------
    // Insertion order
    // -------------------------------------------------------------------

    @Test
    @DisplayName("snapshot preserves insertion order")
    void snapshotPreservesInsertionOrder() {
        ObservationStore store = new ObservationStore(5);

        store.record(obs("req-01"));
        store.record(obs("req-02"));
        store.record(obs("req-03"));

        List<RequestObservation> snap = store.snapshot();
        assertThat(snap).hasSize(3);
        assertThat(snap.get(0).requestId()).isEqualTo("req-01");
        assertThat(snap.get(1).requestId()).isEqualTo("req-02");
        assertThat(snap.get(2).requestId()).isEqualTo("req-03");
    }

    // -------------------------------------------------------------------
    // Oldest-first eviction
    // -------------------------------------------------------------------

    @Test
    @DisplayName("evicts oldest entry when capacity is reached")
    void evictsOldestEntryWhenFull() {
        ObservationStore store = new ObservationStore(3);

        store.record(obs("req-01"));
        store.record(obs("req-02"));
        store.record(obs("req-03"));
        // store is now full; next record must evict req-01
        store.record(obs("req-04"));

        List<RequestObservation> snap = store.snapshot();
        assertThat(snap).hasSize(3);
        assertThat(snap.get(0).requestId()).isEqualTo("req-02");
        assertThat(snap.get(1).requestId()).isEqualTo("req-03");
        assertThat(snap.get(2).requestId()).isEqualTo("req-04");
    }

    @Test
    @DisplayName("evicts oldest for every insert when capacity is one")
    void evictsOldestForCapacityOne() {
        ObservationStore store = new ObservationStore(1);

        store.record(obs("req-01"));
        store.record(obs("req-02"));
        store.record(obs("req-03"));

        List<RequestObservation> snap = store.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).requestId()).isEqualTo("req-03");
    }

    @Test
    @DisplayName("size never exceeds capacity across many inserts")
    void sizeNeverExceedsCapacity() {
        ObservationStore store = new ObservationStore(7);
        for (int i = 0; i < 30; i++) {
            store.record(obs("req-" + i));
            assertThat(store.size()).isLessThanOrEqualTo(7);
        }
    }

    // -------------------------------------------------------------------
    // Exact allowed metadata round-trip
    // -------------------------------------------------------------------

    @Test
    @DisplayName("all allowed fields survive a round-trip")
    void allAllowedFieldsSurviveRoundTrip() {
        ObservationStore store = new ObservationStore(5);
        Instant ts = Instant.parse("2026-07-21T10:15:30.000Z");

        RequestObservation original = new RequestObservation(ts, "id-round-trip", "POST", "/v1/messages?stream=true",
                Protocol.ANTHROPIC_MESSAGES, "application/json", true, true);

        store.record(original);
        List<RequestObservation> snap = store.snapshot();
        assertThat(snap).hasSize(1);

        RequestObservation roundTripped = snap.get(0);
        assertThat(roundTripped.timestamp()).isEqualTo(ts);
        assertThat(roundTripped.requestId()).isEqualTo("id-round-trip");
        assertThat(roundTripped.httpMethod()).isEqualTo("POST");
        assertThat(roundTripped.rawUri()).isEqualTo("/v1/messages?stream=true");
        assertThat(roundTripped.protocol()).isEqualTo(Protocol.ANTHROPIC_MESSAGES);
        assertThat(roundTripped.contentType()).isEqualTo("application/json");
        assertThat(roundTripped.streamingRequest()).isTrue();
        assertThat(roundTripped.forbiddenCredentialHeaderReached()).isTrue();
    }

    @Test
    @DisplayName("different protocols round-trip correctly")
    void differentProtocolsRoundTripCorrectly() {
        ObservationStore store = new ObservationStore(10);

        for (Protocol p : Protocol.values()) {
            store.record(new RequestObservation(Instant.now(), "id-" + p.name(), "POST", "/test", p, "text/plain",
                    false, false));
        }

        List<RequestObservation> snap = store.snapshot();
        assertThat(snap).hasSize(Protocol.values().length);

        List<Protocol> protocols = snap.stream().map(RequestObservation::protocol).toList();
        assertThat(protocols).containsExactly(Protocol.values());
    }

    // -------------------------------------------------------------------
    // Immutable snapshot
    // -------------------------------------------------------------------

    @Test
    @DisplayName("snapshot list is unmodifiable")
    void snapshotListIsUnmodifiable() {
        ObservationStore store = new ObservationStore(3);
        store.record(obs("req-01"));
        store.record(obs("req-02"));

        List<RequestObservation> snap = store.snapshot();

        assertThatThrownBy(() -> snap.add(obs("req-03"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("mutating snapshot does not affect store")
    void mutatingSnapshotDoesNotAffectStore() {
        ObservationStore store = new ObservationStore(3);
        store.record(obs("req-01"));
        store.record(obs("req-02"));

        List<RequestObservation> snap1 = store.snapshot();
        assertThat(snap1).hasSize(2);

        // try to clear the snapshot via its sublist — must fail
        assertThatThrownBy(() -> snap1.subList(0, 1).clear()).isInstanceOf(UnsupportedOperationException.class);

        // store unchanged
        List<RequestObservation> snap2 = store.snapshot();
        assertThat(snap2).hasSize(2);
    }

    @Test
    @DisplayName("concurrent snapshot during writes reflects a consistent point-in-time")
    void snapshotDuringWritesIsConsistent() throws Exception {
        int capacity = 10;
        ObservationStore store = new ObservationStore(capacity);
        int writers = 4;
        int iterationsPerWriter = 50;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        List<String> snapshotSizes = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            for (int w = 0; w < writers; w++) {
                int writerId = w;
                executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterationsPerWriter; i++) {
                        store.record(obs("w" + writerId + "-" + i));
                        if (i % 10 == 0) {
                            snapshotSizes.add(String.valueOf(store.snapshot().size()));
                        }
                    }
                    done.countDown();
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            // Every snapshot size must be <= capacity
            for (String s : snapshotSizes) {
                assertThat(Integer.parseInt(s)).isLessThanOrEqualTo(capacity);
            }
            assertThat(store.size()).isLessThanOrEqualTo(capacity);
        } finally {
            executor.shutdownNow();
        }
    }

    // -------------------------------------------------------------------
    // Concurrent writers never exceed capacity
    // -------------------------------------------------------------------

    @Test
    @DisplayName("concurrent writers never cause the store to exceed capacity")
    void concurrentWritersNeverExceedCapacity() throws Exception {
        int capacity = 8;
        ObservationStore store = new ObservationStore(capacity);
        int writers = 6;
        int iterationsPerWriter = 200;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);

        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            for (int w = 0; w < writers; w++) {
                int writerId = w;
                executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterationsPerWriter; i++) {
                        store.record(obs("w" + writerId + "-" + i));
                        // assert invariant after every single write
                        assertThat(store.size()).isLessThanOrEqualTo(capacity);
                    }
                    done.countDown();
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(store.size()).isLessThanOrEqualTo(capacity);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("concurrent writes produce deterministic oldest-first retention when serialised")
    void concurrentWritesRetainMostRecent() throws Exception {
        int capacity = 3;
        ObservationStore store = new ObservationStore(capacity);

        // Fill then overflow with concurrent writes
        store.record(obs("a-01"));
        store.record(obs("a-02"));
        store.record(obs("a-03"));

        int writers = 4;
        int each = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            IntStream.range(0, writers).forEach(w -> executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < each; i++) {
                    store.record(obs("bw" + w + "-" + i));
                }
                done.countDown();
            }));

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            List<RequestObservation> snap = store.snapshot();
            assertThat(snap).hasSize(capacity);
            // The oldest entries (a-*) are gone
            for (RequestObservation r : snap) {
                assertThat(r.requestId()).doesNotStartWith("a-");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // -------------------------------------------------------------------
    // Clear / reset
    // -------------------------------------------------------------------

    @Test
    @DisplayName("clear empties the store")
    void clearEmptiesStore() {
        ObservationStore store = new ObservationStore(10);
        store.record(obs("req-01"));
        store.record(obs("req-02"));
        store.record(obs("req-03"));

        store.clear();

        assertThat(store.size()).isZero();
        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("after clear the store can be reused")
    void afterClearStoreCanBeReused() {
        ObservationStore store = new ObservationStore(5);

        store.record(obs("batch1-01"));
        store.record(obs("batch1-02"));
        store.clear();

        store.record(obs("batch2-01"));
        store.record(obs("batch2-02"));
        store.record(obs("batch2-03"));

        List<RequestObservation> snap = store.snapshot();
        assertThat(snap).hasSize(3);
        assertThat(snap.get(0).requestId()).isEqualTo("batch2-01");
        assertThat(snap.get(1).requestId()).isEqualTo("batch2-02");
        assertThat(snap.get(2).requestId()).isEqualTo("batch2-03");
    }

    // -------------------------------------------------------------------
    // RequestObservation validation
    // -------------------------------------------------------------------

    @Test
    @DisplayName("RequestObservation rejects null timestamp")
    void requestObservationRejectsNullTimestamp() {
        assertThatThrownBy(() -> new RequestObservation(null, "id", "POST", "/", Protocol.UNKNOWN, "", false, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("RequestObservation rejects null contentType")
    void requestObservationRejectsNullContentType() {
        assertThatThrownBy(
                () -> new RequestObservation(Instant.now(), "id", "POST", "/", Protocol.UNKNOWN, null, false, false))
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Creates a minimal observation with the given request id and neutral defaults
     * for all other fields. Uses synthetic values that are clearly not real
     * credentials.
     */
    private static RequestObservation obs(String requestId) {
        return new RequestObservation(Instant.now(), requestId, "POST", "/v1/mock/" + requestId,
                Protocol.ANTHROPIC_MESSAGES, "application/json", false, false);
    }
}
