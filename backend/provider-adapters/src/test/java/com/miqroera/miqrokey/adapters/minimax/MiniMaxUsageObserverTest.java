package com.miqroera.miqrokey.adapters.minimax;

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

@DisplayName("MiniMaxUsageObserver (G3.4)")
class MiniMaxUsageObserverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("parses the OpenAI-compatible shape with root model id")
    void parsesOpenAiCompatibleShape() {
        String json = "{\"model\":\"MiniMax-M3\",\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":800,"
                + "\"total_tokens\":2000,\"prompt_cache_hit_tokens\":500,\"prompt_cache_miss_tokens\":700}}";

        UsageObservation observation = MiniMaxUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8))
                .orElseThrow();

        assertThat(observation.modelId()).isEqualTo("MiniMax-M3");
        assertThat(observation.inputTokens()).isEqualTo(1200);
        assertThat(observation.outputTokens()).isEqualTo(800);
        assertThat(observation.cacheReadInputTokens()).isEqualTo(500);
        assertThat(observation.cacheCreationInputTokens()).isEqualTo(700);
        assertThat(observation.source()).isEqualTo(UsageSource.PROVIDER_RESPONSE);
    }

    @Test
    @DisplayName("parses the OpenAI-standard cached_tokens shape")
    void parsesCachedTokensShape() {
        String json = "{\"model\":\"MiniMax-M3\",\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":300,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":250},\"total_tokens\":1200}}";

        UsageObservation observation = MiniMaxUsageObserver.parseResponse(MAPPER, json.getBytes(StandardCharsets.UTF_8))
                .orElseThrow();

        assertThat(observation.inputTokens()).isEqualTo(900);
        assertThat(observation.outputTokens()).isEqualTo(300);
        assertThat(observation.cacheReadInputTokens()).isEqualTo(250);
        assertThat(observation.cacheCreationInputTokens()).isNull();
    }

    @Test
    @DisplayName("tolerates empty and non-JSON bodies")
    void toleratesEmptyAndMalformedBodies() {
        assertThat(MiniMaxUsageObserver.parseResponse(MAPPER, "{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(MiniMaxUsageObserver.parseResponse(MAPPER, "not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    @DisplayName("observer is bound to its context and stores the last observation")
    void observerBoundToContextAndStoresLast() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());
        MiniMaxUsageObserver observer = new MiniMaxUsageObserver(context);

        assertThat(observer.context()).isSameAs(context);
        assertThat(observer.lastObservation()).isEmpty();

        UsageObservation first = new UsageObservation("MiniMax-M3", 10L, 5L, null, null, null, null, null,
                UsageSource.PROVIDER_RESPONSE, 1.0);
        observer.onUsage(first);

        assertThat(observer.lastObservation()).contains(first);
    }
}
