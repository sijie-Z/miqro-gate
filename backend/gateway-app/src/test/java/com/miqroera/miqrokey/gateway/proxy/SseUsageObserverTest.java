package com.miqroera.miqrokey.gateway.proxy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SseUsageObserver")
class SseUsageObserverTest {

    @Test
    @DisplayName("retains token counters without retaining or logging event content")
    void retainsOnlyUsageMetadata() {
        String secretContent = "SENSITIVE_MODEL_CONTENT_7e619";
        String sse = "data: {\"type\":\"message_delta\",\"delta\":{\"text\":\"" + secretContent
                + "\"},\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                + "\"cache_creation_input_tokens\":150,\"cache_read_input_tokens\":300}}\r\n\r\n";
        Logger logger = (Logger) LoggerFactory.getLogger(SseUsageObserver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            SseUsageObserver observer = new SseUsageObserver();
            var buffer = new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8));
            observer.wrap(Flux.just(buffer)).blockLast();

            assertThat(observer.getObservations())
                    .containsExactly(new SseUsageObserver.UsageObservation(10L, 5L, 150L, 300L));
            assertThat(observer.getObservations().toString()).doesNotContain(secretContent);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(secretContent));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("handles UTF-8 and multi-line data split at arbitrary byte boundaries")
    void handlesSplitUtf8AndMultiLineData() {
        byte[] bytes = ("data: {\"message\":{\"text\":\"世界\",\"usage\":{\n"
                + "data: \"input_tokens\":12,\"output_tokens\":7}}}\n\n").getBytes(StandardCharsets.UTF_8);
        SseUsageObserver observer = new SseUsageObserver();
        Flux<org.springframework.core.io.buffer.DataBuffer> chunks = Flux.range(0, bytes.length)
                .map(index -> new DefaultDataBufferFactory().wrap(new byte[]{bytes[index]}));

        observer.wrap(chunks).blockLast();

        assertThat(observer.getObservations())
                .containsExactly(new SseUsageObserver.UsageObservation(12L, 7L, null, null));
    }

    @Test
    @DisplayName("does not modify bytes or a non-zero read position")
    void doesNotModifyBuffers() {
        byte[] bytes = "prefix-data: {\"usage\":{\"input_tokens\":10}}\n\n".getBytes(StandardCharsets.UTF_8);
        var buffer = new DefaultDataBufferFactory().wrap(bytes);
        buffer.readPosition("prefix-".length());
        int expectedReadPosition = buffer.readPosition();
        SseUsageObserver observer = new SseUsageObserver();

        var observed = observer.wrap(Flux.just(buffer)).blockFirst();

        assertThat(observed).isSameAs(buffer);
        assertThat(observed.readPosition()).isEqualTo(expectedReadPosition);
        byte[] remaining = new byte[observed.readableByteCount()];
        observed.read(remaining);
        assertThat(remaining).isEqualTo("data: {\"usage\":{\"input_tokens\":10}}\n\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("bounds malformed events without changing the stream")
    void boundsMalformedEvents() {
        byte[] oversized = ("data: " + "x".repeat(65) + "\n\n").getBytes(StandardCharsets.UTF_8);
        var buffer = new DefaultDataBufferFactory().wrap(oversized);
        SseUsageObserver observer = new SseUsageObserver(new com.fasterxml.jackson.databind.ObjectMapper(), 32);

        var observed = observer.wrap(Flux.just(buffer)).blockFirst();

        assertThat(observer.getObservations()).isEmpty();
        byte[] forwarded = new byte[observed.readableByteCount()];
        observed.read(forwarded);
        assertThat(forwarded).isEqualTo(oversized);
    }
}
