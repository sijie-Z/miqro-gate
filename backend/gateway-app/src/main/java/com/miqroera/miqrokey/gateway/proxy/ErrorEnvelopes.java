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

    static String body(AuthFailureException e, String path) {
        String message = e.getMessage().replace("\\", "\\\\").replace("\"", "\\\"");
        boolean isAnthropic = "/v1/messages".equals(path);
        return isAnthropic
                ? "{\"type\":\"error\",\"error\":{\"type\":\"" + e.code() + "\",\"message\":\"" + message + "\"}}"
                : "{\"error\":{\"type\":\"" + e.code() + "\",\"message\":\"" + message + "\"}}";
    }
}
