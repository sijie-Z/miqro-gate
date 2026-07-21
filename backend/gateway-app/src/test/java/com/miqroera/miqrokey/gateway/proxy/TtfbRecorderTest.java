package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TtfbRecorder")
class TtfbRecorderTest {

    @Test
    @DisplayName("should record TTFB when first buffer arrives")
    void shouldRecordTtfbWhenFirstBufferArrives() {
        long start = 1_000L;
        TtfbRecorder recorder = new TtfbRecorder("test-1", start,
                Clock.fixed(Instant.ofEpochMilli(1_025L), ZoneOffset.UTC));

        var buffer = new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap("hello".getBytes());

        Flux<org.springframework.core.io.buffer.DataBuffer> wrapped = recorder.wrap(Flux.just(buffer));

        StepVerifier.create(wrapped).expectNextCount(1).verifyComplete();

        assertThat(recorder.ttfbMillis()).isEqualTo(25L);
        assertThat(recorder.firstByteMillisRaw()).isEqualTo(1_025L);
    }

    @Test
    @DisplayName("should return -1 for TTFB before first byte")
    void shouldReturnNegativeBeforeFirstByte() {
        TtfbRecorder recorder = new TtfbRecorder("test-2", 1_000L,
                Clock.fixed(Instant.ofEpochMilli(1_025L), ZoneOffset.UTC));
        assertThat(recorder.ttfbMillis()).isEqualTo(-1);
    }

    @Test
    @DisplayName("should record completion metadata")
    void shouldRecordCompletionMetadata() {
        TtfbRecorder recorder = new TtfbRecorder("test-3", 1_000L,
                Clock.fixed(Instant.ofEpochMilli(1_025L), ZoneOffset.UTC));

        var buffer = new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap("data".getBytes());

        Flux<org.springframework.core.io.buffer.DataBuffer> wrapped = recorder.wrap(Flux.just(buffer));

        StepVerifier.create(wrapped).expectNextCount(1).verifyComplete();

        recorder.recordCompletion(reactor.core.publisher.SignalType.ON_COMPLETE);

        var metadata = recorder.getMetadata();
        assertThat(metadata).containsKey("requestId");
        assertThat(metadata).containsKey("requestStartEpochMillis");
        assertThat(metadata).containsKey("firstByteEpochMillis");
        assertThat(metadata).containsKey("completionEpochMillis");
        assertThat(metadata.get("requestId")).isEqualTo("test-3");
        // Metadata must never contain request or response body content
        metadata.values().forEach(v -> assertThat(v.toString()).doesNotContain("data"));
    }
}
