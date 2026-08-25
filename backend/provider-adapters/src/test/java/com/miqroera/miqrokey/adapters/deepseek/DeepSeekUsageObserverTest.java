package com.miqroera.miqrokey.adapters.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObservation;
import com.miqroera.miqrokey.spi.UsageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeepSeekUsageObserver (G3.1)")
class DeepSeekUsageObserverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("parses OpenAI-compatible usage with DeepSeek cache fields")
    void parsesOpenAiShapeWithCacheFields() {
        String json = "{\"id\":\"chatcmpl-ds\",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"total_tokens\":120,\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20}}";

        Optional<UsageObservation> observation = DeepSeekUsageObserver.parseResponse(MAPPER,
                json.getBytes(StandardCharsets.UTF_8));

        assertThat(observation).isPresent();
        UsageObservation o = observation.get();
        assertThat(o.inputTokens()).isEqualTo(100L);
        assertThat(o.outputTokens()).isEqualTo(20L);
        assertThat(o.cacheReadInputTokens()).isEqualTo(80L);
        assertThat(o.cacheCreationInputTokens()).isEqualTo(20L);
        assertThat(o.source()).isEqualTo(UsageSource.PROVIDER_RESPONSE);
        assertThat(o.confidence()).isEqualTo(1.0);
        assertThat(o.modelId()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("parses Anthropic Messages usage shape")
    void parsesAnthropicShape() {
        String json = "{\"usage\":{\"input_tokens\":25,\"output_tokens\":7,"
                + "\"cache_creation_input_tokens\":15,\"cache_read_input_tokens\":300,"
                + "\"cache_read_creation_input_tokens\":0,\"cache_read_creation_output_tokens\":0}}";

        UsageObservation o = DeepSeekUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8)).get();

        assertThat(o.inputTokens()).isEqualTo(25L);
        assertThat(o.outputTokens()).isEqualTo(7L);
        assertThat(o.cacheReadInputTokens()).isEqualTo(300L);
        assertThat(o.cacheCreationInputTokens()).isEqualTo(15L);
    }

    @Test
    @DisplayName("prefers standard cache names over DeepSeek-specific ones when both present")
    void prefersStandardCacheNames() {
        String json = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
                + "\"cache_creation_input_tokens\":30,\"cache_read_input_tokens\":40,"
                + "\"prompt_cache_hit_tokens\":999,\"prompt_cache_miss_tokens\":999}}";

        UsageObservation o = DeepSeekUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8)).get();

        assertThat(o.cacheReadInputTokens()).isEqualTo(40L);
        assertThat(o.cacheCreationInputTokens()).isEqualTo(30L);
    }

    @Test
    @DisplayName("reads message.usage when the root has no usage (Anthropic entry)")
    void readsMessageUsageFallback() {
        String json = "{\"type\":\"message\",\"id\":\"msg_01\",\"message\":{\"id\":\"msg_01\","
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":2}}}";

        UsageObservation o = DeepSeekUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8)).get();

        assertThat(o.inputTokens()).isEqualTo(11L);
        assertThat(o.outputTokens()).isEqualTo(2L);
    }

    @Test
    @DisplayName("takes model id from usage.model when present")
    void readsModelId() {
        String json = "{\"usage\":{\"model\":\"deepseek-chat\",\"prompt_tokens\":3,\"completion_tokens\":1}}";

        UsageObservation o = DeepSeekUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8)).get();

        assertThat(o.modelId()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("returns empty for missing, malformed or empty usage objects")
    void emptyOnMissingOrMalformed() {
        assertThat(DeepSeekUsageObserver.parseResponse(MAPPER, "{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(DeepSeekUsageObserver.parseResponse(MAPPER,
                "{\"message\":{\"id\":\"m\"}}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(DeepSeekUsageObserver.parseResponse(MAPPER, "not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(DeepSeekUsageObserver.parseResponse(MAPPER,
                "{\"usage\":{\"unknown_field\":1}}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(DeepSeekUsageObserver.parseResponse(MAPPER,
                "{\"usage\":\"not-an-object\"}".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    @DisplayName("onUsage records exactly the latest observation")
    void recordsLatestObservation() {
        DeepSeekUsageObserver observer = new DeepSeekUsageObserver(context());

        assertThat(observer.lastObservation()).isEmpty();

        observer.onUsage(observation(1L, 2L));
        observer.onUsage(observation(3L, 4L));

        assertThat(observer.lastObservation()).isPresent();
        assertThat(observer.lastObservation().get().inputTokens()).isEqualTo(3L);
        assertThat(observer.lastObservation().get().outputTokens()).isEqualTo(4L);
    }

    private static UsageContext context() {
        return new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE, Instant.now());
    }

    private static UsageObservation observation(long input, long output) {
        return new UsageObservation("deepseek-chat", input, output, null, null, null, null, null,
                UsageSource.PROVIDER_RESPONSE, 1.0);
    }
}
