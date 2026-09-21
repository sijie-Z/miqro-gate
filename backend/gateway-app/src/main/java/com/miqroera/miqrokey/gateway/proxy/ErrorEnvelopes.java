package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;

/**
 * Single definition of the gateway's error envelope so every endpoint — proxy
 * hot path and control endpoints alike — fails with the same shape:
 * OpenAI-compatible {@code {"error":{"type":...,"message":...}}} with the
 * Anthropic variant for {@code /v1/messages}.
 */
final class ErrorEnvelopes {

    private ErrorEnvelopes() {
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String body(AuthFailureException e, String path) {
        // The message may embed client-supplied model names: escape quotes,
        // backslashes and control characters so the envelope stays valid JSON
        // (G6 audit fix; the C0 completion landed in #866).
        String message = escape(e.getMessage());
        String type = escape(e.code());
        boolean isAnthropic = "/v1/messages".equals(path);
        return isAnthropic
                ? "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}"
                : "{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    /**
     * JSON-string escaping for envelope values (#866, following #447/#449 for the
     * control-plane twin). RFC 8259 section 7 requires <em>every</em> character in
     * U+0000-U+001F to be escaped inside a JSON string; a client can smuggle any of
     * them into an echoed model or tool name with a unicode escape, so the short
     * forms for newline, carriage return and tab are not enough — the rest are
     * emitted as six-character unicode escapes. Without this the whole envelope is
     * unparseable at the client.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
