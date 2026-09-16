package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the body-prefix provider request id fallback (#623): the
 * gateway only saw {@code x-request-id}/{@code request-id} headers before, so
 * DeepSeek-style providers (id in the body) always landed null in
 * {@code usage_event.provider_request_id}.
 */
@DisplayName("UpstreamRequestIdExtractor (#623)")
class UpstreamRequestIdExtractorTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("reads the top-level id from an OpenAI chat completion body")
    void openAiBody() {
        String body = "{\"id\":\"chatcmpl-abc123\",\"object\":\"chat.completion\",\"choices\":[]}";
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8(body))).isEqualTo("chatcmpl-abc123");
    }

    @Test
    @DisplayName("reads the id from an Anthropic messages body")
    void anthropicBody() {
        String body = "{\"id\":\"msg_01ABCDEF\",\"type\":\"message\",\"role\":\"assistant\"}";
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8(body))).isEqualTo("msg_01ABCDEF");
    }

    @Test
    @DisplayName("reads the id from the first SSE chunk (data: prefix included)")
    void sseFirstChunk() {
        String chunk = "data: {\"id\":\"chunk-42\",\"object\":\"chat.completion.chunk\",\"choices\":[]}\n\n";
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8(chunk))).isEqualTo("chunk-42");
    }

    @Test
    @DisplayName("tolerates whitespace around the separator")
    void whitespaceVariant() {
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8("{\"id\" :\t \"sp-1\",\"x\":1}"))).isEqualTo("sp-1");
    }

    @Test
    @DisplayName("the first id wins — nested tool-call ids never shadow it")
    void topLevelWins() {
        String body = "{\"id\":\"top-1\",\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call_9\"}]}}]}";
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8(body))).isEqualTo("top-1");
    }

    @Test
    @DisplayName("keys merely containing id never match")
    void requestIdKeyDoesNotMatch() {
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8("{\"request_id\":\"r-1\"}"))).isNull();
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8("{\"tool_call_id\":\"t-1\"}"))).isNull();
    }

    @Test
    @DisplayName("null / empty / no-id bodies yield null")
    void absentId() {
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(null)).isNull();
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(new byte[0])).isNull();
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8("{\"object\":\"list\",\"data\":[]}"))).isNull();
    }

    @Test
    @DisplayName("an unterminated value (prefix cut mid-id) yields null, never a partial id")
    void truncatedPrefix() {
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8("{\"id\":\"chatcmpl-cut"))).isNull();
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8("{\"id\":\""))).isNull();
    }

    @Test
    @DisplayName("overlong (>128) and empty values are skipped")
    void boundsAndEmpty() {
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8("{\"id\":\"\"}"))).isNull();
        String overlong = "{\"id\":\"" + "x".repeat(129) + "\"}";
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8(overlong))).isNull();
        String maxLen = "{\"id\":\"" + "y".repeat(128) + "\"}";
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8(maxLen))).isEqualTo("y".repeat(128));
    }

    @Test
    @DisplayName("an escaped or control-character value is skipped, later ids still considered")
    void escapedValueSkipped() {
        // The first candidate contains a backslash (never a real provider id);
        // scanning continues and finds the later valid id.
        String body = "{\"id\":\"bad\\\\esc\",\"nested\":{\"id\":\"good-2\"}}";
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8(body))).isEqualTo("good-2");
        String withNewline = "{\"id\":\"bad\\nval\",\"id\":\"ok-3\"}";
        assertThat(UpstreamRequestIdExtractor.fromBodyPrefix(utf8(withNewline))).isEqualTo("ok-3");
    }
}
