package com.miqroera.miqrokey.adapters.moonshot;

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

@DisplayName("MoonshotKimiUsageObserver (G3.5)")
class MoonshotKimiUsageObserverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("parses the OpenAI-compatible shape with root model id")
    void parsesOpenAiCompatibleShape() {
        String json = "{\"model\":\"kimi-k2.7\",\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":800,"
                + "\"total_tokens\":2000,\"prompt_cache_hit_tokens\":500,\"prompt_cache_miss_tokens\":700}}";

        UsageObservation observation = MoonshotKimiUsageObserver
                .parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(observation.modelId()).isEqualTo("kimi-k2.7");
        assertThat(observation.inputTokens()).isEqualTo(1200);
        assertThat(observation.outputTokens()).isEqualTo(800);
        assertThat(observation.cacheReadInputTokens()).isEqualTo(500);
        assertThat(observation.cacheCreationInputTokens()).isEqualTo(700);
        assertThat(observation.source()).isEqualTo(UsageSource.PROVIDER_RESPONSE);
    }

    @Test
    @DisplayName("parses the Anthropic Messages shape")
    void parsesAnthropicShape() {
        String json = "{\"message\":{\"usage\":{\"input_tokens\":600,\"output_tokens\":400,"
                + "\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":100,\"model\":\"kimi-k2.7\"}}}";

        UsageObservation observation = MoonshotKimiUsageObserver
                .parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(observation.modelId()).isEqualTo("kimi-k2.7");
        assertThat(observation.inputTokens()).isEqualTo(600);
        assertThat(observation.outputTokens()).isEqualTo(400);
        assertThat(observation.cacheReadInputTokens()).isEqualTo(200);
        assertThat(observation.cacheCreationInputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("tolerates empty and non-JSON bodies")
    void toleratesEmptyAndMalformedBodies() {
        assertThat(MoonshotKimiUsageObserver.parseResponse(MAPPER, "{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(MoonshotKimiUsageObserver.parseResponse(MAPPER, "not json".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    @DisplayName("observer is bound to its context and stores the last observation")
    void observerBoundToContextAndStoresLast() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());
        MoonshotKimiUsageObserver observer = new MoonshotKimiUsageObserver(context);

        assertThat(observer.context()).isSameAs(context);
        assertThat(observer.lastObservation()).isEmpty();

        UsageObservation first = new UsageObservation("kimi-k2.7", 10L, 5L, null, null, null, null, null,
                UsageSource.PROVIDER_RESPONSE, 1.0);
        observer.onUsage(first);

        assertThat(observer.lastObservation()).contains(first);
    }
}
