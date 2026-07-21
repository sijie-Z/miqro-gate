package com.miqroera.miqrokey.testing;

/**
 * Synthetic OpenAI Chat Completions API fixtures for transparent proxy contract
 * tests.
 *
 * <p>
 * All content is synthetic — no real prompts, code, tool bodies, or model
 * responses. Fixtures are derived from the public OpenAI Chat Completions API
 * documentation.
 * </p>
 */
public final class ChatFixtures {

    private ChatFixtures() {
        // utility class
    }

    // -----------------------------------------------------------------------
    // Request fixtures
    // -----------------------------------------------------------------------

    /** A minimal non-streaming Chat Completions request. */
    public static final String REQUEST_NON_STREAMING = """
            {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello, world!"}],"max_tokens":512}""";

    /** A streaming Chat Completions request. */
    public static final String REQUEST_STREAMING = """
            {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Tell me a short story."}],"stream":true}""";

    /** A request with tool definitions. */
    public static final String REQUEST_WITH_TOOLS = """
            {"model":"gpt-4o-mini","messages":[{"role":"user","content":"What is the weather in San Francisco?"}],"tools":[{"type":"function","function":{"name":"get_weather","description":"Get the current weather","parameters":{"type":"object","properties":{"location":{"type":"string","description":"The city name"}},"required":["location"]}}}],"tool_choice":"auto"}""";

    /** A follow-up request with a synthetic tool result. */
    public static final String REQUEST_WITH_TOOL_RESULT = """
            {"model":"gpt-4o-mini","messages":[{"role":"user","content":"What is the weather?"},{"role":"assistant","tool_calls":[{"id":"call_syn01","type":"function","function":{"name":"get_weather","arguments":"{\\"location\\":\\"London\\"}"}}]},{"role":"tool","tool_call_id":"call_syn01","content":"Sunny, 22 C"}]}""";

    /** A request containing multi-byte UTF-8 characters. */
    public static final String REQUEST_WITH_UTF8 = """
            {"model":"gpt-4o-mini","messages":[{"role":"user","content":"你好世界 🌍 — Unicode test"}],"max_tokens":512}""";

    /** A request with unknown (future) fields that must be preserved. */
    public static final String REQUEST_WITH_UNKNOWN_FIELDS = """
            {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}],"max_tokens":512,"future_extension":{"nested":true,"value":42}}""";

    // -----------------------------------------------------------------------
    // Non-streaming response fixtures
    // -----------------------------------------------------------------------

    /** A basic non-streaming Chat Completions response. */
    public static final String RESPONSE_BASIC = """
            {"id":"chatcmpl-abc123","object":"chat.completion","created":1720000000,"model":"gpt-4o-mini-2024-07-18","choices":[{"index":0,"message":{"role":"assistant","content":"Hello! How can I help you today?"},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":7,"total_tokens":17,"completion_tokens_details":{"reasoning_tokens":0}}}""";

    /** A response with tool calls. */
    public static final String RESPONSE_TOOL_CALL = """
            {"id":"chatcmpl-def456","object":"chat.completion","created":1720000001,"model":"gpt-4o-mini-2024-07-18","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_xyz789","type":"function","function":{"name":"get_weather","arguments":"{\\"location\\":\\"San Francisco\\"}"}}]},"logprobs":null,"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":150,"completion_tokens":25,"total_tokens":175,"completion_tokens_details":{"reasoning_tokens":0}}}""";

    /** A response with reasoning_content (o-series models). */
    public static final String RESPONSE_REASONING = """
            {"id":"chatcmpl-ghi789","object":"chat.completion","created":1720000002,"model":"o3-mini-2025-01-31","choices":[{"index":0,"message":{"role":"assistant","content":"123 * 456 = 56,088","reasoning_content":"Let me calculate 123 * 456 step by step. 123 * 400 = 49,200. 123 * 50 = 6,150. 123 * 6 = 738. 49,200 + 6,150 + 738 = 56,088."},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":30,"completion_tokens":80,"total_tokens":110,"completion_tokens_details":{"reasoning_tokens":60}}}""";

    /** A response with multiple finish_reason values. */
    public static final String RESPONSE_LENGTH_STOP = """
            {"id":"chatcmpl-stp001","object":"chat.completion","created":1720000003,"model":"gpt-4o-mini-2024-07-18","choices":[{"index":0,"message":{"role":"assistant","content":"Truncated..."},"logprobs":null,"finish_reason":"length"}],"usage":{"prompt_tokens":5,"completion_tokens":20,"total_tokens":25,"completion_tokens_details":{"reasoning_tokens":0}}}""";

    /** A response with unknown (future) fields preserved. */
    public static final String RESPONSE_WITH_UNKNOWN_FIELDS = """
            {"id":"chatcmpl-unk001","object":"chat.completion","created":1720000004,"model":"gpt-4o-mini-2024-07-18","choices":[{"index":0,"message":{"role":"assistant","content":"The answer is 42."},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8,"completion_tokens_details":{"reasoning_tokens":0}},"future_ext":{"enabled":true}}""";

    // -----------------------------------------------------------------------
    // Streaming (SSE) response fixtures
    // -----------------------------------------------------------------------

    private static String sse(String... events) {
        StringBuilder sb = new StringBuilder();
        for (String event : events) {
            sb.append(event).append("\r\n");
        }
        return sb.toString();
    }

    /** A complete SSE streaming Chat Completions response. */
    public static final String RESPONSE_STREAMING_SSE = sse(
            "data: {\"id\":\"chatcmpl-sse01\",\"object\":\"chat.completion.chunk\",\"created\":1720000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse01\",\"object\":\"chat.completion.chunk\",\"created\":1720000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse01\",\"object\":\"chat.completion.chunk\",\"created\":1720000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" from the streaming Chat API!\"},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse01\",\"object\":\"chat.completion.chunk\",\"created\":1720000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{},\"logprobs\":null,\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":7,\"total_tokens\":17,\"completion_tokens_details\":{\"reasoning_tokens\":0}}}",
            "", "data: [DONE]", "");

    /** An SSE streaming response with tool call deltas. */
    public static final String RESPONSE_STREAMING_TOOL_CALL = sse(
            "data: {\"id\":\"chatcmpl-sse02\",\"object\":\"chat.completion.chunk\",\"created\":1720000001,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_tc01\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse02\",\"object\":\"chat.completion.chunk\",\"created\":1720000001,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"location\\\":\\\"\"}}]},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse02\",\"object\":\"chat.completion.chunk\",\"created\":1720000001,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"San Francisco\\\"}\"}}]},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse02\",\"object\":\"chat.completion.chunk\",\"created\":1720000001,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{},\"logprobs\":null,\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":150,\"completion_tokens\":25,\"total_tokens\":175,\"completion_tokens_details\":{\"reasoning_tokens\":0}}}",
            "", "data: [DONE]", "");

    /** An SSE streaming response with reasoning_content delta. */
    public static final String RESPONSE_STREAMING_REASONING = sse(
            "data: {\"id\":\"chatcmpl-sse03\",\"object\":\"chat.completion.chunk\",\"created\":1720000002,\"model\":\"o3-mini-2025-01-31\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"\"},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse03\",\"object\":\"chat.completion.chunk\",\"created\":1720000002,\"model\":\"o3-mini-2025-01-31\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"Let me calculate: 123 * 456\"},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse03\",\"object\":\"chat.completion.chunk\",\"created\":1720000002,\"model\":\"o3-mini-2025-01-31\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"56,088\"},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-sse03\",\"object\":\"chat.completion.chunk\",\"created\":1720000002,\"model\":\"o3-mini-2025-01-31\",\"choices\":[{\"index\":0,\"delta\":{},\"logprobs\":null,\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":35,\"total_tokens\":65,\"completion_tokens_details\":{\"reasoning_tokens\":60}}}",
            "", "data: [DONE]", "");

    /** An SSE response containing multi-byte UTF-8 characters. */
    public static final String RESPONSE_STREAMING_UTF8 = sse(
            "data: {\"id\":\"chatcmpl-utf01\",\"object\":\"chat.completion.chunk\",\"created\":1720000003,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-utf01\",\"object\":\"chat.completion.chunk\",\"created\":1720000003,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好世界 🌍 — Unicode 测试\"},\"logprobs\":null,\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-utf01\",\"object\":\"chat.completion.chunk\",\"created\":1720000003,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{},\"logprobs\":null,\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":10,\"total_tokens\":25,\"completion_tokens_details\":{\"reasoning_tokens\":0}}}",
            "", "data: [DONE]", "");

    /** An SSE response with unknown event fields. */
    public static final String RESPONSE_STREAMING_UNKNOWN_FIELDS = sse(
            "data: {\"id\":\"chatcmpl-unk01\",\"object\":\"chat.completion.chunk\",\"created\":1720000004,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"logprobs\":null,\"finish_reason\":null,\"future_choice_field\":\"val\"}],\"future_chunk_field\":true}",
            "",
            "data: {\"id\":\"chatcmpl-unk01\",\"object\":\"chat.completion.chunk\",\"created\":1720000004,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{},\"logprobs\":null,\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7,\"completion_tokens_details\":{\"reasoning_tokens\":0}}}",
            "", "data: [DONE]", "");

    // -----------------------------------------------------------------------
    // Error response fixtures
    // -----------------------------------------------------------------------

    /** An upstream 400 error response. */
    public static final String RESPONSE_ERROR_400 = """
            {"error":{"message":"Invalid model: 'unknown-model'","type":"invalid_request_error","param":"model","code":null}}""";

    /** An upstream 429 rate-limit error response. */
    public static final String RESPONSE_ERROR_429 = """
            {"error":{"message":"Rate limit exceeded for api_key","type":"rate_limit_error","param":null,"code":"rate_limit_exceeded"}}""";

    /** An upstream 500 internal error response. */
    public static final String RESPONSE_ERROR_500 = """
            {"error":{"message":"The server had an error processing your request.","type":"server_error","param":null,"code":"internal_error"}}""";

    // -----------------------------------------------------------------------
    // Model constants
    // -----------------------------------------------------------------------

    public static final String MODEL_GPT_4O_MINI = "gpt-4o-mini";
    public static final String MODEL_O3_MINI = "o3-mini-2025-01-31";
}
