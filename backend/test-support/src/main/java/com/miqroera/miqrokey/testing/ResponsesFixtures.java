package com.miqroera.miqrokey.testing;

/**
 * Synthetic OpenAI Responses API fixtures for transparent proxy contract tests.
 *
 * <p>
 * All content is synthetic — no real prompts, code, tool bodies, or model
 * responses. Fixtures are derived from the public OpenAI Responses API
 * documentation.
 * </p>
 */
public final class ResponsesFixtures {

    private ResponsesFixtures() {
        // utility class
    }

    // -----------------------------------------------------------------------
    // Request fixtures
    // -----------------------------------------------------------------------

    /** A minimal non-streaming Responses request. */
    public static final String REQUEST_NON_STREAMING = """
            {"model":"gpt-4o-mini","input":"Hello, world!","max_output_tokens":512}""";

    /** A streaming Responses request. */
    public static final String REQUEST_STREAMING = """
            {"model":"gpt-4o-mini","input":"Tell me a short story.","stream":true}""";

    /** A request with tools (function definitions). */
    public static final String REQUEST_WITH_TOOLS = """
            {"model":"gpt-4o-mini","input":"What is the weather in San Francisco?","tools":[{"type":"function","name":"get_weather","description":"Get current weather","parameters":{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}}]}""";

    /** A request with reasoning enabled. */
    public static final String REQUEST_WITH_REASONING = """
            {"model":"gpt-4o-mini","input":"Solve: 123 * 456 = ?","reasoning":{"effort":"medium"},"max_output_tokens":1024}""";

    /** A request containing multi-byte UTF-8 characters. */
    public static final String REQUEST_WITH_UTF8 = """
            {"model":"gpt-4o-mini","input":"你好世界 🌍 — Unicode test"}""";

    /** A request with unknown (future) fields that must be preserved. */
    public static final String REQUEST_WITH_UNKNOWN_FIELDS = """
            {"model":"gpt-4o-mini","input":"Hello","max_output_tokens":512,"future_field":{"nested":true,"value":42},"experimental_flag":"beta"}""";

    /** A follow-up request submitting synthetic function call output. */
    public static final String REQUEST_FUNCTION_CALL_OUTPUT = """
            {"model":"gpt-4o-mini","input":[{"type":"function_call_output","call_id":"call_syn01","output":"Sunny, 22 C"},{"type":"function_call_output","call_id":"call_syn02","output":"Humidity 65%"}]}""";

    // -----------------------------------------------------------------------
    // Non-streaming response fixtures
    // -----------------------------------------------------------------------

    /** A basic non-streaming text response. */
    public static final String RESPONSE_BASIC = """
            {"id":"resp_abc123","object":"response","created_at":1720000000,"status":"completed","model":"gpt-4o-mini-2024-07-18","output":[{"id":"msg_abc123","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Hello! How can I help you today?","annotations":[]}]}],"usage":{"input_tokens":10,"output_tokens":7,"total_tokens":17,"output_tokens_details":{"reasoning_tokens":0}}}""";

    /** A response with function calls. */
    public static final String RESPONSE_FUNCTION_CALL = """
            {"id":"resp_def456","object":"response","created_at":1720000001,"status":"completed","model":"gpt-4o-mini-2024-07-18","output":[{"id":"fc_abc123","type":"function_call","status":"completed","call_id":"call_xyz789","name":"get_weather","arguments":"{\\"location\\":\\"San Francisco\\"}"}],"usage":{"input_tokens":150,"output_tokens":25,"total_tokens":175,"output_tokens_details":{"reasoning_tokens":0}}}""";

    /** A response with reasoning content. */
    public static final String RESPONSE_REASONING = """
            {"id":"resp_ghi789","object":"response","created_at":1720000002,"status":"completed","model":"gpt-4o-mini-2024-07-18","output":[{"id":"rs_abc","type":"reasoning","status":"completed","summary":[{"type":"summary_text","text":"Let me calculate: 123 * 456 = 123 * 400 + 123 * 56 = 49200 + 6888 = 56088"}]},{"id":"msg_def","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"123 * 456 = 56,088","annotations":[]}]}],"usage":{"input_tokens":30,"output_tokens":80,"total_tokens":110,"output_tokens_details":{"reasoning_tokens":60}}}""";

    /** A response with unknown (future) fields preserved. */
    public static final String RESPONSE_WITH_UNKNOWN_FIELDS = """
            {"id":"resp_unk001","object":"response","created_at":1720000003,"status":"completed","model":"gpt-4o-mini-2024-07-18","output":[{"id":"msg_unk","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"The answer is 42.","annotations":[]}]}],"usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8,"output_tokens_details":{"reasoning_tokens":0}},"future_response_field":"preserved"}""";

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

    /** A complete SSE streaming response with usage. */
    public static final String RESPONSE_STREAMING_SSE = sse("event: response.created",
            "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_sse01\",\"object\":\"response\",\"created_at\":1720000000,\"status\":\"in_progress\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[]}}",
            "", "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_sse01\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hello\"}",
            "", "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_sse01\",\"output_index\":0,\"content_index\":0,\"delta\":\" from the streaming Responses API!\"}",
            "", "event: response.output_text.done",
            "data: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_sse01\",\"output_index\":0,\"content_index\":0,\"text\":\"Hello from the streaming Responses API!\"}",
            "", "event: response.content_part.done",
            "data: {\"type\":\"response.content_part.done\",\"item_id\":\"msg_sse01\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"Hello from the streaming Responses API!\"}}",
            "", "event: response.completed",
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_sse01\",\"object\":\"response\",\"created_at\":1720000000,\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[{\"id\":\"msg_sse01\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello from the streaming Responses API!\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":10,\"output_tokens\":7,\"total_tokens\":17,\"output_tokens_details\":{\"reasoning_tokens\":0}}}}",
            "");

    /** An SSE streaming response with function call output. */
    public static final String RESPONSE_STREAMING_FUNCTION_CALL = sse("event: response.created",
            "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_sse02\",\"object\":\"response\",\"created_at\":1720000001,\"status\":\"in_progress\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[]}}",
            "", "event: response.function_call_arguments.delta",
            "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_01\",\"output_index\":0,\"delta\":\"{\\\"location\\\":\\\"\"}",
            "", "event: response.function_call_arguments.delta",
            "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_01\",\"output_index\":0,\"delta\":\"San Francisco\\\"}\"}",
            "", "event: response.function_call_arguments.done",
            "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_01\",\"output_index\":0,\"name\":\"get_weather\",\"arguments\":\"{\\\"location\\\":\\\"San Francisco\\\"}\"}",
            "", "event: response.completed",
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_sse02\",\"object\":\"response\",\"created_at\":1720000001,\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[{\"id\":\"fc_01\",\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"call_xyz\",\"name\":\"get_weather\",\"arguments\":\"{\\\"location\\\":\\\"San Francisco\\\"}\"}],\"usage\":{\"input_tokens\":150,\"output_tokens\":25,\"total_tokens\":175,\"output_tokens_details\":{\"reasoning_tokens\":0}}}}",
            "");

    /** An SSE streaming response with reasoning items. */
    public static final String RESPONSE_STREAMING_REASONING = sse("event: response.created",
            "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_sse03\",\"object\":\"response\",\"created_at\":1720000002,\"status\":\"in_progress\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[]}}",
            "", "event: response.reasoning_summary_part.added",
            "data: {\"type\":\"response.reasoning_summary_part.added\",\"item_id\":\"rs_sse03\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"Reasoning step 1\"}}",
            "", "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_sse03\",\"output_index\":1,\"content_index\":0,\"delta\":\"56,088\"}",
            "", "event: response.completed",
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_sse03\",\"object\":\"response\",\"created_at\":1720000002,\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[{\"id\":\"rs_sse03\",\"type\":\"reasoning\",\"status\":\"completed\"},{\"id\":\"msg_sse03\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"56,088\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":30,\"output_tokens\":20,\"total_tokens\":50,\"output_tokens_details\":{\"reasoning_tokens\":80}}}}",
            "");

    /** An SSE response containing multi-byte UTF-8 characters. */
    public static final String RESPONSE_STREAMING_UTF8 = sse("event: response.created",
            "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_utf01\",\"object\":\"response\",\"created_at\":1720000003,\"status\":\"in_progress\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[]}}",
            "", "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_utf01\",\"output_index\":0,\"content_index\":0,\"delta\":\"你好世界 🌍 — Unicode 测试通过\"}",
            "", "event: response.completed",
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_utf01\",\"object\":\"response\",\"created_at\":1720000003,\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[{\"id\":\"msg_utf01\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"你好世界 🌍 — Unicode 测试通过\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":15,\"output_tokens\":10,\"total_tokens\":25,\"output_tokens_details\":{\"reasoning_tokens\":0}}}}",
            "");

    /** An SSE response with unknown event fields. */
    public static final String RESPONSE_STREAMING_UNKNOWN_FIELDS = sse("event: response.created",
            "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_unk01\",\"object\":\"response\",\"created_at\":1720000004,\"status\":\"in_progress\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[]},\"future_event_field\":true}",
            "", "event: response.completed",
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_unk01\",\"object\":\"response\",\"created_at\":1720000004,\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[],\"usage\":{\"input_tokens\":5,\"output_tokens\":1,\"total_tokens\":6,\"output_tokens_details\":{\"reasoning_tokens\":0}}}}",
            "");

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
}
