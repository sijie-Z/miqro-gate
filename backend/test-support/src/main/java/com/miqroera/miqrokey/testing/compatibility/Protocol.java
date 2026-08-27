package com.miqroera.miqrokey.testing.compatibility;

/**
 * Protocol classifier for an observed upstream request reaching the
 * compatibility mock.
 *
 * <p>
 * The enum describes the shape of the request&rsquo;s expected protocol, not
 * the actual body content (which the mock never inspects).
 * </p>
 */
public enum Protocol {

    /** Anthropic Messages API ({@code POST /v1/messages}). */
    ANTHROPIC_MESSAGES,

    /** OpenAI Chat Completions API ({@code POST /v1/chat/completions}). */
    OPENAI_CHAT_COMPLETIONS,

    /** OpenAI Responses API ({@code POST /v1/responses}). */
    OPENAI_RESPONSES,

    /**
     * Diagnostic / health-check request that is not a real inference call.
     */
    DIAGNOSTIC,

    /**
     * The request path did not match any recognised protocol.
     */
    UNKNOWN
}
