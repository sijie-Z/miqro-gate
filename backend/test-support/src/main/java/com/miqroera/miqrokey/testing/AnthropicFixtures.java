package com.miqroera.miqrokey.testing;

/**
 * Synthetic Anthropic Messages API fixtures for transparent proxy contract
 * tests.
 *
 * <p>
 * All content is synthetic — no real prompts, code, tool bodies, or model
 * responses. Fixtures are derived from the public Anthropic Messages API
 * documentation.
 * </p>
 */
public final class AnthropicFixtures {

    private AnthropicFixtures() {
        // utility class
    }

    // -----------------------------------------------------------------------
    // Request fixtures
    // -----------------------------------------------------------------------

    /** A minimal non-streaming Anthropic Messages request. */
    public static final String REQUEST_NON_STREAMING = """
            {"model":"claude-sonnet-5-20250915","max_tokens":1024,"messages":[{"role":"user","content":"Hello, world!"}]}""";

    /** A streaming Anthropic Messages request. */
    public static final String REQUEST_STREAMING = """
            {"model":"claude-sonnet-5-20250915","max_tokens":1024,"stream":true,"messages":[{"role":"user","content":"Tell me a short story."}]}""";

    /** A request with tool definitions. */
    public static final String REQUEST_WITH_TOOLS = """
            {"model":"claude-sonnet-5-20250915","max_tokens":1024,"messages":[{"role":"user","content":"What is the weather?"}],"tools":[{"name":"get_weather","description":"Get the current weather","input_schema":{"type":"object","properties":{"location":{"type":"string","description":"The city name"}},"required":["location"]}}]}""";

    /** A request with prompt cache control. */
    public static final String REQUEST_WITH_CACHE = """
            {"model":"claude-sonnet-5-20250915","max_tokens":1024,"system":[{"type":"text","text":"You are a helpful assistant.","cache_control":{"type":"ephemeral"}}],"messages":[{"role":"user","content":"Hello!"}]}""";

    /** A request containing multi-byte UTF-8 characters. */
    public static final String REQUEST_WITH_UTF8 = """
            {"model":"claude-sonnet-5-20250915","max_tokens":1024,"messages":[{"role":"user","content":"你好世界 🌍 — Unicode test"}]}""";

    /** A follow-up request carrying a synthetic tool_result block. */
    public static final String REQUEST_WITH_TOOL_RESULT = """
            {"model":"claude-sonnet-5-20250915","max_tokens":1024,"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01Synthetic","content":"Synthetic result: 21 C"}]}]}""";

    // -----------------------------------------------------------------------
    // Non-streaming response fixtures (compact single-line JSON)
    // -----------------------------------------------------------------------

    /** A basic non-streaming text response (compact JSON). */
    public static final String RESPONSE_BASIC = """
            {"id":"msg_01AbcDefGhijKlMnOp","type":"message","role":"assistant","content":[{"type":"text","text":"Hello! How can I help you today?"}],"model":"claude-sonnet-5-20250915","stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":7,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}""";

    /** A non-streaming tool-use response (compact JSON). */
    public static final String RESPONSE_TOOL_USE = """
            {"id":"msg_02XyzAbcDefGhijKlMn","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01AbcDefGhijKlMnOp","name":"get_weather","input":{"location":"San Francisco"}}],"model":"claude-sonnet-5-20250915","stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":200,"output_tokens":35,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}""";

    /** A non-streaming response with thinking blocks (compact JSON). */
    public static final String RESPONSE_THINKING = """
            {"id":"msg_03ThinkAbcDefGhijKlMn","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"I need to understand the user's question carefully.","signature":"EqcEDBh..."},{"type":"text","text":"Based on my analysis, the answer is 42."}],"model":"claude-sonnet-5-20250915","stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":50,"output_tokens":45,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}""";

    /** A non-streaming response with prompt cache usage fields (compact JSON). */
    public static final String RESPONSE_CACHE_USAGE = """
            {"id":"msg_04CacheAbcDefGhijKlMn","type":"message","role":"assistant","content":[{"type":"text","text":"I've cached your system prompt for efficiency."}],"model":"claude-sonnet-5-20250915","stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":12,"cache_creation_input_tokens":150,"cache_read_input_tokens":300}}""";

    // -----------------------------------------------------------------------
    // Streaming (SSE) response fixtures — built programmatically to ensure
    // correct CRLF bytes. Do NOT use Java text blocks with literal \r.
    // -----------------------------------------------------------------------

    private static String sse(String... events) {
        StringBuilder sb = new StringBuilder();
        for (String event : events) {
            sb.append(event).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * A complete SSE streaming response with usage in the final message_delta
     * event.
     */
    public static final String RESPONSE_STREAMING_SSE = sse("event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_sse01\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5-20250915\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}",
            "", "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "", "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}",
            "", "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" from the streaming API!\"}}",
            "", "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":0}", "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":10,\"output_tokens\":8,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}",
            "", "event: message_stop", "data: {\"type\":\"message_stop\"}", "");

    /** An SSE streaming response with tool_use blocks. */
    public static final String RESPONSE_STREAMING_TOOL_USE = sse("event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_sse02\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5-20250915\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":200,\"output_tokens\":0}}}",
            "", "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_sse01\",\"name\":\"get_weather\",\"input\":{}}}",
            "", "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"location\\\":\\\"\"}}",
            "", "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"San Francisco\\\"}\"}}",
            "", "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":0}", "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":200,\"output_tokens\":35,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}",
            "", "event: message_stop", "data: {\"type\":\"message_stop\"}", "");

    /** An SSE streaming response with thinking blocks. */
    public static final String RESPONSE_STREAMING_THINKING = sse("event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_sse03\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5-20250915\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":50,\"output_tokens\":0}}}",
            "", "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
            "", "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me analyze this carefully.\"}}",
            "", "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":0}", "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "", "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"The answer is 42.\"}}",
            "", "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":1}", "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":50,\"output_tokens\":45,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}",
            "", "event: message_stop", "data: {\"type\":\"message_stop\"}", "");

    /** An SSE response with cache usage fields in the final event. */
    public static final String RESPONSE_STREAMING_CACHE_USAGE = sse("event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_sse04\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5-20250915\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}",
            "", "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "", "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Using cached system prompt.\"}}",
            "", "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":0}", "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":12,\"cache_creation_input_tokens\":150,\"cache_read_input_tokens\":300}}",
            "", "event: message_stop", "data: {\"type\":\"message_stop\"}", "");

    /** An SSE response containing multi-byte UTF-8 characters. */
    public static final String RESPONSE_STREAMING_UTF8 = sse("event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_utf01\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5-20250915\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":15,\"output_tokens\":0}}}",
            "", "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "", "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好世界 🌍 — 这是一个 Unicode 测试文本。\"}}",
            "", "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":0}", "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":15,\"output_tokens\":25,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}",
            "", "event: message_stop", "data: {\"type\":\"message_stop\"}", "");

    // -----------------------------------------------------------------------
    // Error response fixtures
    // -----------------------------------------------------------------------

    /** An upstream 400 error response (compact JSON). */
    public static final String RESPONSE_ERROR_400 = """
            {"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: field required"}}""";

    /** An upstream 429 rate-limit error response (compact JSON). */
    public static final String RESPONSE_ERROR_429 = """
            {"type":"error","error":{"type":"rate_limit_error","message":"Rate limit exceeded."}}""";

    /** An upstream 500 internal error response (compact JSON). */
    public static final String RESPONSE_ERROR_500 = """
            {"type":"error","error":{"type":"api_error","message":"Internal server error"}}""";

    // -----------------------------------------------------------------------
    // Header constants
    // -----------------------------------------------------------------------

    public static final String HEADER_ANTHROPIC_VERSION = "anthropic-version";
    public static final String ANTHROPIC_VERSION_VALUE = "2023-06-01";
    public static final String HEADER_ANTHROPIC_BETA = "anthropic-beta";
    public static final String ANTHROPIC_BETA_VALUE = "prompt-caching-2024-07-31";
}
