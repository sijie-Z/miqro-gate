package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Mock Provider direct test")
class MockProviderDirectTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();
    private static WebClient directClient;

    @BeforeAll
    static void setUp() {
        directClient = WebClient.create(mockProvider.getBaseUrl());
    }

    @AfterAll
    static void tearDown() {
        mockProvider.close();
    }

    @AfterEach
    void resetMock() {
        mockProvider.reset();
    }

    @Test
    @DisplayName("mock provider should return non-streaming body")
    void shouldReturnNonStreamingBody() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

        String body = directClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING)
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));

        assertThat(body).isNotNull();
        assertThat(body).contains("msg_01AbcDefGhijKlMnOp");
        assertThat(body).contains("Hello! How can I help you today?");
        assertThat(body).contains("\"usage\"");
    }

    @Test
    @DisplayName("mock provider should return SSE streaming body")
    void shouldReturnSseStreamingBody() {
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                        .body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

        String rawBody = directClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));

        assertThat(rawBody).isNotNull();
        // The raw SSE body should contain event: and data: lines with CRLF
        assertThat(rawBody).contains("event: message_start");
        assertThat(rawBody).contains("event: message_stop");
        assertThat(rawBody).contains("\"text_delta\"");
        assertThat(rawBody).contains("\"usage\"");
    }

    @Test
    @DisplayName("mock provider should capture request")
    void shouldCaptureRequest() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

        directClient.post().uri("/v1/messages").header("Authorization", "Bearer test-key")
                .header("anthropic-version", "2023-06-01").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).retrieve()
                .bodyToMono(String.class).block(Duration.ofSeconds(5));

        var captured = mockProvider.getCapturedRequests();
        assertThat(captured).hasSize(1);
        var req = captured.get(0);
        assertThat(req.method).isEqualTo("POST");
        assertThat(req.body).contains("claude-sonnet-5-20250915");
        assertThat(req.header("Authorization")).isEqualTo("Bearer test-key");
    }
}
