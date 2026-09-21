package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE wire framing for the inbound MCP SSE transport (#356): event frames,
 * multi-line payload splitting and keep-alive comments.
 */
@DisplayName("SseFrames")
class SseFramesTest {

    @Test
    @DisplayName("a single-line payload becomes one data field")
    void singleLineFrame() {
        assertThat(text(SseFrames.event("endpoint", "/mcpservers/open-demo/message?sessionId=s1")))
                .isEqualTo("event: endpoint\ndata: /mcpservers/open-demo/message?sessionId=s1\n\n");
    }

    @Test
    @DisplayName("a multi-line payload splits into one data field per line")
    void multiLineFrame() {
        assertThat(text(SseFrames.event("message", "{\"a\":1,\n\"b\":2}")))
                .isEqualTo("event: message\ndata: {\"a\":1,\ndata: \"b\":2}\n\n");
    }

    @Test
    @DisplayName("carriage returns are stripped so frames stay well-formed")
    void carriageReturnsStripped() {
        assertThat(text(SseFrames.event("message", "line1\r\nline2")))
                .isEqualTo("event: message\ndata: line1\ndata: line2\n\n");
    }

    @Test
    @DisplayName("comments frame as a colon line and are client-invisible")
    void commentFrame() {
        assertThat(text(SseFrames.comment("ping"))).isEqualTo(": ping\n\n");
    }

    private static String text(byte[] frame) {
        return new String(frame, StandardCharsets.UTF_8);
    }
}
