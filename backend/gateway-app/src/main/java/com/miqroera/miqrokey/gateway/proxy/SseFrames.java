package com.miqroera.miqrokey.gateway.proxy;

import java.nio.charset.StandardCharsets;

/**
 * Minimal server-sent-events framing (issue #356, I11): the inbound MCP SSE
 * transport is hand-encoded so the gateway keeps full control of the stream
 * (endpoint announcement, relayed responses, gateway errors, keep-alive
 * comments). Multi-line payloads are split into one {@code data:} line each,
 * per the SSE wire format.
 */
final class SseFrames {

    private SseFrames() {
    }

    /**
     * {@code event: <name>} + one {@code data:} line per payload line + blank line.
     */
    static byte[] event(String event, byte[] payload) {
        return event(event, new String(payload == null ? new byte[0] : payload, StandardCharsets.UTF_8));
    }

    static byte[] event(String event, String payload) {
        StringBuilder frame = new StringBuilder();
        frame.append("event: ").append(event).append('\n');
        String body = payload == null ? "" : payload;
        // SSE data fields must not contain raw newlines: one data: line per line.
        for (String line : body.split("\n", -1)) {
            frame.append("data: ").append(line.replace("\r", "")).append('\n');
        }
        frame.append('\n');
        return frame.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Keep-alive comment frame (ignored by SSE clients, keeps intermediaries open).
     */
    static byte[] comment(String text) {
        return (": " + text + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
