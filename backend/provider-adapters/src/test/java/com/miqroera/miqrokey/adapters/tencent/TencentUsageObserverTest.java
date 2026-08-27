package com.miqroera.miqrokey.adapters.tencent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObservation;
import com.miqroera.miqrokey.spi.UsageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TencentUsageObserver (G3.2)")
class TencentUsageObserverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("parses the OpenAI-compatible shape with cache hit/miss fields")
    void parsesOpenAiCompatibleShape() {
        String json = "{\"model\":\"glm-5\",\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":800,"
                + "\"total_tokens\":2000,\"prompt_cache_hit_tokens\":500,\"prompt_cache_miss_tokens\":700,"
                + "\"unknown_future_field\":true}}";

        UsageObservation observation = TencentUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8))
                .orElseThrow();

        assertThat(observation.modelId()).isEqualTo("glm-5");
        assertThat(observation.inputTokens()).isEqualTo(1200);
        assertThat(observation.outputTokens()).isEqualTo(800);
        assertThat(observation.cacheReadInputTokens()).isEqualTo(500);
        assertThat(observation.cacheCreationInputTokens()).isEqualTo(700);
        assertThat(observation.source()).isEqualTo(UsageSource.PROVIDER_RESPONSE);
    }

    @Test
    @DisplayName("parses the Anthropic Messages shape with cache read/creation fields")
    void parsesAnthropicShape() {
        String json = "{\"message\":{\"usage\":{\"input_tokens\":600,\"output_tokens\":400,"
                + "\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":100,\"model\":\"kimi-k2.5\"}}}";

        UsageObservation observation = TencentUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8))
                .orElseThrow();

        assertThat(observation.modelId()).isEqualTo("kimi-k2.5");
        assertThat(observation.inputTokens()).isEqualTo(600);
        assertThat(observation.outputTokens()).isEqualTo(400);
        assertThat(observation.cacheReadInputTokens()).isEqualTo(200);
        assertThat(observation.cacheCreationInputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("prefers root usage and falls back to message.usage")
    void prefersRootUsage() {
        String json = "{\"usage\":{\"input_tokens\":50,\"output_tokens\":10},"
                + "\"message\":{\"usage\":{\"input_tokens\":999,\"output_tokens\":999}}}";

        UsageObservation observation = TencentUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8))
                .orElseThrow();

        assertThat(observation.inputTokens()).isEqualTo(50);
        assertThat(observation.outputTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("tolerates empty, non-JSON and unknown-only bodies")
    void toleratesEmptyAndMalformedBodies() {
        assertThat(TencentUsageObserver.parseResponse(MAPPER, "{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(TencentUsageObserver.parseResponse(MAPPER, "not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(TencentUsageObserver.parseResponse(MAPPER,
                "{\"usage\":{\"unknown_future_field\":true}}".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    @DisplayName("observer is bound to its context and stores the last observation")
    void observerBoundToContextAndStoresLast() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());
        TencentUsageObserver observer = new TencentUsageObserver(context);

        assertThat(observer.context()).isSameAs(context);
        assertThat(observer.lastObservation()).isEmpty();

        UsageObservation first = new UsageObservation("glm-5", 10L, 5L, null, null, null, null, null,
                UsageSource.PROVIDER_RESPONSE, 1.0);
        UsageObservation second = new UsageObservation("glm-5", 20L, 9L, null, null, null, null, null,
                UsageSource.PROVIDER_RESPONSE, 1.0);
        observer.onUsage(first);
        observer.onUsage(second);

        assertThat(observer.lastObservation()).contains(second);
    }
}
