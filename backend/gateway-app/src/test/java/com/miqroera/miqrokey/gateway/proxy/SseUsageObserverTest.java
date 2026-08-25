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

            assertThat(observer.getObservations()).containsExactly(
                    new SseUsageObserver.UsageObservation(10L, 5L, 150L, 300L, null, null, null, null));
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
                .containsExactly(new SseUsageObserver.UsageObservation(12L, 7L, null, null, null, null, null, null));
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

    @Test
    @DisplayName("extracts Anthropic message_start usage from message.usage")
    void extractsAnthropicMessageUsage() {
        String sse = "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg01\",\"usage\":{\"input_tokens\":25,\"output_tokens\":0}}}\r\n\r\n";
        SseUsageObserver observer = new SseUsageObserver();
        var buffer = new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8));
        observer.wrap(Flux.just(buffer)).blockLast();

        assertThat(observer.getObservations())
                .containsExactly(new SseUsageObserver.UsageObservation(25L, 0L, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("extracts OpenAI Responses usage from response.usage")
    void extractsResponsesUsage() {
        String sse = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp01\",\"usage\":{\"input_tokens\":30,\"output_tokens\":15,\"total_tokens\":45,\"output_tokens_details\":{\"reasoning_tokens\":10}}}}\r\n\r\n";
        SseUsageObserver observer = new SseUsageObserver();
        var buffer = new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8));
        observer.wrap(Flux.just(buffer)).blockLast();

        assertThat(observer.getObservations())
                .containsExactly(new SseUsageObserver.UsageObservation(30L, 15L, null, null, null, null, 45L, 10L));
    }

    @Test
    @DisplayName("extracts OpenAI Chat usage with prompt/completion tokens")
    void extractsChatUsage() {
        String sse = "data: {\"id\":\"chatcmpl-01\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8,\"total_tokens\":28}}\r\n\r\n";
        SseUsageObserver observer = new SseUsageObserver();
        var buffer = new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8));
        observer.wrap(Flux.just(buffer)).blockLast();

        assertThat(observer.getObservations())
                .containsExactly(new SseUsageObserver.UsageObservation(null, null, null, null, 20L, 8L, 28L, null));
    }

    @Test
    @DisplayName("extracts Chat reasoning tokens from completion_tokens_details")
    void extractsChatReasoningTokens() {
        String sse = "data: {\"id\":\"chatcmpl-02\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":80,\"total_tokens\":110,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":60}}}\r\n\r\n";
        SseUsageObserver observer = new SseUsageObserver();
        var buffer = new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8));
        observer.wrap(Flux.just(buffer)).blockLast();

        assertThat(observer.getObservations())
                .containsExactly(new SseUsageObserver.UsageObservation(null, null, null, null, 30L, 80L, 110L, 60L));
    }

    @Test
    @DisplayName("bounds retained observations to the configured maximum")
    void boundsObservationsToMaximum() {
        String event = "data: {\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}\r\n\r\n";
        SseUsageObserver observer = new SseUsageObserver(new com.fasterxml.jackson.databind.ObjectMapper(),
                SseUsageObserver.DEFAULT_MAX_EVENT_BYTES, 3);
        var buffer = new DefaultDataBufferFactory()
                .wrap((event + event + event + event + event).getBytes(StandardCharsets.UTF_8));
        observer.wrap(Flux.just(buffer)).blockLast();

        // Only the first 3 observations are retained; streaming transparency preserved.
        assertThat(observer.getObservations()).hasSize(3);
    }

    @Test
    @DisplayName("prefers output_tokens_details over completion_tokens_details when both present")
    void prefersOutputTokensDetailsWhenBothPresent() {
        String sse = "data: {\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                + "\"output_tokens_details\":{\"reasoning_tokens\":99},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":1}}}\r\n\r\n";
        SseUsageObserver observer = new SseUsageObserver();
        var buffer = new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8));
        observer.wrap(Flux.just(buffer)).blockLast();

        assertThat(observer.getObservations()).hasSize(1);
        assertThat(observer.getObservations().get(0).reasoningTokens()).isEqualTo(99L);
    }

    @Test
    @DisplayName("maps DeepSeek prompt_cache_hit/miss tokens to cache read/creation")
    void mapsDeepSeekCacheFields() {
        String sse = "data: {\"id\":\"chatcmpl-ds01\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,"
                + "\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20}}\r\n\r\n";
        SseUsageObserver observer = new SseUsageObserver();
        var buffer = new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8));
        observer.wrap(Flux.just(buffer)).blockLast();

        assertThat(observer.getObservations())
                .containsExactly(new SseUsageObserver.UsageObservation(null, null, 20L, 80L, 100L, 20L, 120L, null));
    }

    @Test
    @DisplayName("prefers standard cache names over DeepSeek-specific ones when both present")
    void prefersStandardCacheNamesWhenBothPresent() {
        String sse = "data: {\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                + "\"cache_creation_input_tokens\":30,\"cache_read_input_tokens\":40,"
                + "\"prompt_cache_hit_tokens\":999,\"prompt_cache_miss_tokens\":999}}\r\n\r\n";
        SseUsageObserver observer = new SseUsageObserver();
        var buffer = new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8));
        observer.wrap(Flux.just(buffer)).blockLast();

        assertThat(observer.getObservations())
                .containsExactly(new SseUsageObserver.UsageObservation(10L, 5L, 30L, 40L, null, null, null, null));
    }
}
