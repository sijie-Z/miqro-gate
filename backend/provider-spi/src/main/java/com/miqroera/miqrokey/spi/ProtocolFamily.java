package com.miqroera.miqrokey.spi;

/**
 * The wire protocol family a provider product speaks. The Gateway is a
 * transparent multi-protocol proxy: it never converts between these families,
 * it only routes byte-transparently using the rules of the declared family.
 *
 * <p>
 * See {@code docs/provider-adapter-contract.md §2}.
 */
public enum ProtocolFamily {

    /** Anthropic Messages API ({@code /v1/messages}, SSE streaming). */
    ANTHROPIC_MESSAGES,

    /** OpenAI Responses API ({@code /v1/responses}). */
    OPENAI_RESPONSES,

    /** OpenAI Chat Completions API ({@code /v1/chat/completions}). */
    OPENAI_CHAT_COMPLETIONS,

    /**
     * OpenAI-compatible endpoint that is not one of the canonical OpenAI products
     * above (most mainland-China vendors). Requests stay byte-transparent; only the
     * well-known OpenAI shapes are routed.
     */
    OPENAI_COMPATIBLE,

    /** Vendor-native API surface (e.g. TokenHub usage/balance endpoints). */
    VENDOR_NATIVE,
}
