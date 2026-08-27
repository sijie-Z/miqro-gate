package com.miqroera.miqrokey.testing.compatibility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Deterministic contract and safety tests for {@link CompatibilityMockServer}.
 *
 * <p>
 * No test contains a real API key, token, or credential string. Privacy tests
 * prove that synthetic credential-like values are never retained in
 * observations.
 * </p>
 */
@DisplayName("CompatibilityMockServer")
class CompatibilityMockServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private CompatibilityMockServer server;
    private int port;

    @BeforeEach
    void startServer() {
        server = new CompatibilityMockServer(0, 10, 16_384);
        port = server.getPort();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    // === JSON endpoints ==============================================

    @Nested
    @DisplayName("JSON non-streaming endpoints")
    class JsonEndpoints {

        @Test
        @DisplayName("POST /v1/messages returns Anthropic JSON")
        void anthropicMessagesReturnsJson() throws Exception {
            HttpResponse<String> resp = post("/v1/messages",
                    body("claude-sonnet-4-20250514", "\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]"));
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(resp.body()).contains("\"type\":\"message\"");
            assertThat(resp.body()).contains("\"role\":\"assistant\"");
            assertThat(resp.body()).contains("\"stop_reason\":\"end_turn\"");
            assertThat(resp.body()).contains("\"usage\":");
        }

        @Test
        @DisplayName("POST /v1/chat/completions returns OpenAI Chat JSON")
        void chatCompletionsReturnsJson() throws Exception {
            HttpResponse<String> resp = post("/v1/chat/completions",
                    "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(resp.body()).contains("\"object\":\"chat.completion\"");
            assertThat(resp.body()).contains("\"finish_reason\":\"stop\"");
        }

        @Test
        @DisplayName("POST /v1/responses returns OpenAI Responses JSON")
        void responsesReturnsJson() throws Exception {
            HttpResponse<String> resp = post("/v1/responses", "{\"model\":\"gpt-4o\",\"input\":\"Hello\"}");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(resp.body()).contains("\"object\":\"response\"");
            assertThat(resp.body()).contains("\"type\":\"output_text\"");
        }
    }

    // === SSE endpoints ===============================================

    @Nested
    @DisplayName("SSE streaming endpoints")
    class SseEndpoints {

        @Test
        @DisplayName("POST /v1/messages with stream returns Anthropic SSE")
        void anthropicMessagesReturnsSse() throws Exception {
            HttpResponse<String> resp = postSse("/v1/messages", body("claude-sonnet-4-20250514",
                    "\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]"));
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("text/event-stream");
            String b = resp.body();
            assertThat(b).contains("event: message_start");
            assertThat(b).contains("event: content_block_delta");
            assertThat(b).contains("event: message_stop");
        }

        @Test
        @DisplayName("POST /v1/messages SSE terminates with message_stop")
        void anthropicSseTerminatesWithMessageStop() throws Exception {
            HttpResponse<String> resp = postSse("/v1/messages", body("claude-sonnet-4-20250514",
                    "\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]"));
            String b = resp.body();
            assertThat(b).endsWith("\n\n");
            assertThat(b).contains("event: message_stop");
            int lastEvent = b.lastIndexOf("event:");
            assertThat(b.substring(lastEvent)).startsWith("event: message_stop");
        }

        @Test
        @DisplayName("POST /v1/chat/completions with stream returns Chat SSE")
        void chatCompletionsReturnsSse() throws Exception {
            HttpResponse<String> resp = postSse("/v1/chat/completions",
                    "{\"model\":\"gpt-4\",\"stream\":true," + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("text/event-stream");
            String b = resp.body();
            assertThat(b).contains("data: [DONE]");
            assertThat(b).contains("\"object\":\"chat.completion.chunk\"");
        }

        @Test
        @DisplayName("POST /v1/chat/completions SSE terminates with [DONE]")
        void chatSseTerminatesWithDone() throws Exception {
            HttpResponse<String> resp = postSse("/v1/chat/completions",
                    "{\"model\":\"gpt-4\",\"stream\":true," + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            String b = resp.body();
            assertThat(b).endsWith("\n\n");
            assertThat(b).contains("data: [DONE]");
            int lastData = b.lastIndexOf("data:");
            assertThat(b.substring(lastData)).startsWith("data: [DONE]");
        }

        @Test
        @DisplayName("POST /v1/responses with stream returns Responses SSE")
        void responsesReturnsSse() throws Exception {
            HttpResponse<String> resp = postSse("/v1/responses",
                    "{\"model\":\"gpt-4o\",\"stream\":true,\"input\":\"Hello\"}");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("text/event-stream");
            String b = resp.body();
            assertThat(b).contains("event: response.created");
            assertThat(b).contains("event: response.output_text.delta");
            assertThat(b).contains("event: response.completed");
        }

        @Test
        @DisplayName("POST /v1/responses SSE terminates with response.completed")
        void responsesSseTerminatesWithCompleted() throws Exception {
            HttpResponse<String> resp = postSse("/v1/responses",
                    "{\"model\":\"gpt-4o\",\"stream\":true,\"input\":\"Hello\"}");
            String b = resp.body();
            assertThat(b).endsWith("\n\n");
            assertThat(b).contains("event: response.completed");
            int lastEvent = b.lastIndexOf("event:");
            assertThat(b.substring(lastEvent)).startsWith("event: response.completed");
        }
    }

    // === Raw URI / query metadata ====================================

    @Nested
    @DisplayName("Raw URI and query metadata")
    class RawUriAndQueryMetadata {

        @Test
        @DisplayName("rawUri captures exact query string")
        void rawUriCapturesExactQueryString() throws Exception {
            post("/v1/messages?stream=true&custom=value",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            List<RequestObservation> snap = server.getStore().snapshot();
            assertThat(snap).hasSize(1);
            assertThat(snap.get(0).rawUri()).isEqualTo("/v1/messages?stream=true&custom=value");
        }

        @Test
        @DisplayName("rawUri with no query string is just the path")
        void rawUriWithoutQuery() throws Exception {
            post("/v1/chat/completions", "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            List<RequestObservation> snap = server.getStore().snapshot();
            assertThat(snap).hasSize(1);
            assertThat(snap.get(0).rawUri()).isEqualTo("/v1/chat/completions");
        }
    }

    // === Protocol classification =====================================

    @Nested
    @DisplayName("Protocol classification")
    class ProtocolClassification {

        @Test
        @DisplayName("/v1/messages is ANTHROPIC_MESSAGES")
        void messagesIsAnthropic() throws Exception {
            post("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            assertThat(server.getStore().snapshot().get(0).protocol()).isEqualTo(Protocol.ANTHROPIC_MESSAGES);
        }

        @Test
        @DisplayName("/v1/chat/completions is OPENAI_CHAT_COMPLETIONS")
        void chatIsOpenaiChat() throws Exception {
            post("/v1/chat/completions", "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            assertThat(server.getStore().snapshot().get(0).protocol()).isEqualTo(Protocol.OPENAI_CHAT_COMPLETIONS);
        }

        @Test
        @DisplayName("/v1/responses is OPENAI_RESPONSES")
        void responsesIsOpenaiResponses() throws Exception {
            post("/v1/responses", "{\"model\":\"gpt-4o\",\"input\":\"Hello\"}");
            assertThat(server.getStore().snapshot().get(0).protocol()).isEqualTo(Protocol.OPENAI_RESPONSES);
        }

        @Test
        @DisplayName("/health is DIAGNOSTIC")
        void healthIsDiagnostic() throws Exception {
            get("/health");
            assertThat(server.getStore().snapshot().get(0).protocol()).isEqualTo(Protocol.DIAGNOSTIC);
        }

        @Test
        @DisplayName("/observations is DIAGNOSTIC")
        void observationsIsDiagnostic() throws Exception {
            get("/observations");
            assertThat(server.getStore().snapshot().get(0).protocol()).isEqualTo(Protocol.DIAGNOSTIC);
        }

        @Test
        @DisplayName("unknown path is UNKNOWN")
        void unknownPathIsUnknown() throws Exception {
            post("/some/random/path", "body");
            assertThat(server.getStore().snapshot().get(0).protocol()).isEqualTo(Protocol.UNKNOWN);
        }
    }

    // === Credential header detection =================================

    @Nested
    @DisplayName("Credential header detection")
    class CredentialHeaderDetection {

        @Test
        @DisplayName("x-api-key header is detected as credential")
        void xApiKeyIsDetected() throws Exception {
            postWithHeader("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}",
                    "x-api-key", "sk-ant-secret-value-that-must-not-be-retained");
            assertThat(server.getStore().snapshot().get(0).forbiddenCredentialHeaderReached()).isTrue();
        }

        @Test
        @DisplayName("Authorization header is detected as credential")
        void authorizationIsDetected() throws Exception {
            postWithHeader("/v1/chat/completions",
                    "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}", "Authorization",
                    "Bearer sk-secret-bearer-token");
            assertThat(server.getStore().snapshot().get(0).forbiddenCredentialHeaderReached()).isTrue();
        }

        @Test
        @DisplayName("case-insensitive credential header name detection")
        void caseInsensitiveDetection() throws Exception {
            postWithHeader("/v1/responses", "{\"model\":\"gpt-4o\",\"input\":\"Hello\"}", "X-API-KEY",
                    "value-does-not-matter");
            assertThat(server.getStore().snapshot().get(0).forbiddenCredentialHeaderReached()).isTrue();
        }

        @Test
        @DisplayName("ordinary headers are not flagged as credential")
        void ordinaryHeadersNotFlagged() throws Exception {
            postWithHeaders("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}",
                    "Content-Type", "application/json", "Accept", "text/event-stream", "User-Agent", "test-client");
            assertThat(server.getStore().snapshot().get(0).forbiddenCredentialHeaderReached()).isFalse();
        }

        @Test
        @DisplayName("credential header value is never retained in observations")
        void credentialValueNotRetained() throws Exception {
            String secretValue = "sk-ant-api03-TOP-SECRET-CREDENTIAL-12345";
            postWithHeader("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}",
                    "x-api-key", secretValue);
            HttpResponse<String> snapResp = get("/observations");
            assertThat(snapResp.body()).doesNotContain(secretValue);
            assertThat(snapResp.body()).doesNotContain("TOP-SECRET");
            assertThat(snapResp.body()).doesNotContain("sk-ant");
        }
    }

    // === Bounded observation eviction ================================

    @Nested
    @DisplayName("Bounded oldest-first observation eviction")
    class ObservationBounding {

        @Test
        @DisplayName("observations never exceed capacity")
        void neverExceedsCapacity() throws Exception {
            CompatibilityMockServer bounded = new CompatibilityMockServer(0, 3, 16_384);
            try {
                int p = bounded.getPort();
                for (int i = 0; i < 10; i++) {
                    postToPort(p, "/v1/messages",
                            "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
                }
                assertThat(bounded.getStore().size()).isEqualTo(3);
                assertThat(bounded.getStore().snapshot()).hasSize(3);
            } finally {
                bounded.close();
            }
        }

        @Test
        @DisplayName("oldest observations are evicted first")
        void oldestEvictedFirst() throws Exception {
            CompatibilityMockServer bounded = new CompatibilityMockServer(0, 3, 16_384);
            try {
                int p = bounded.getPort();
                for (int i = 0; i < 3; i++) {
                    postToPort(p, "/v1/messages",
                            "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
                }
                String first = bounded.getStore().snapshot().get(0).requestId();
                postToPort(p, "/v1/messages",
                        "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
                List<RequestObservation> snap = bounded.getStore().snapshot();
                assertThat(snap).hasSize(3);
                assertThat(snap.get(0).requestId()).isNotEqualTo(first);
            } finally {
                bounded.close();
            }
        }
    }

    // === Diagnostics =================================================

    @Nested
    @DisplayName("Diagnostic endpoints")
    class Diagnostics {

        @Test
        @DisplayName("GET /health returns UP")
        void healthReturnsUp() throws Exception {
            HttpResponse<String> resp = get("/health");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(resp.body()).contains("\"status\":\"UP\"");
            assertThat(resp.body()).contains("\"service\":\"compatibility-mock\"");
        }

        @Test
        @DisplayName("GET /observations returns empty array when no requests")
        void emptyObservations() throws Exception {
            HttpResponse<String> resp = get("/observations");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(resp.body().trim()).isEqualTo("[]");
        }

        @Test
        @DisplayName("GET /observations returns observation snapshot")
        void observationsReturnsSnapshot() throws Exception {
            post("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            post("/v1/chat/completions", "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            HttpResponse<String> resp = get("/observations");
            assertThat(resp.statusCode()).isEqualTo(200);
            String b = resp.body();
            assertThat(b).contains("\"protocol\":\"ANTHROPIC_MESSAGES\"");
            assertThat(b).contains("\"protocol\":\"OPENAI_CHAT_COMPLETIONS\"");
        }

        @Test
        @DisplayName("GET /observations never contains body content or credential values")
        void observationsNeverContainsBodyOrCredentials() throws Exception {
            String sentinelBody = "SENTINEL-BODY-xyzzy-98765";
            postWithHeader("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"" + sentinelBody
                            + "\"}]}",
                    "x-api-key", "sk-synthetic-secret-abc123");
            HttpResponse<String> resp = get("/observations");
            assertThat(resp.body()).doesNotContain(sentinelBody);
            assertThat(resp.body()).doesNotContain("sk-synthetic-secret-abc123");
        }

        @Test
        @DisplayName("DELETE /observations clears the store")
        void deleteClearsObservations() throws Exception {
            post("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            HttpResponse<String> deleteResp = delete("/observations");
            assertThat(deleteResp.statusCode()).isEqualTo(200);
            assertThat(deleteResp.body()).contains("\"cleared\":true");
            assertThat(server.getStore().size()).isZero();
        }

        @Test
        @DisplayName("after clear the store is reusable")
        void afterClearStoreReusable() throws Exception {
            post("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            delete("/observations");
            post("/v1/chat/completions", "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            assertThat(server.getStore().snapshot()).hasSize(1);
        }

        @Test
        @DisplayName("health endpoint has deterministic JSON")
        void healthJsonIsDeterministic() throws Exception {
            HttpResponse<String> resp1 = get("/health");
            HttpResponse<String> resp2 = get("/health");
            assertThat(resp1.body()).isEqualTo(resp2.body());
            assertThat(resp1.body()).isEqualTo("{\"service\":\"compatibility-mock\",\"status\":\"UP\"}");
        }
    }

    // === Error handling ==============================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("GET on inference path returns 405")
        void getOnInferencePathReturns405() throws Exception {
            HttpResponse<String> resp = get("/v1/messages");
            assertThat(resp.statusCode()).isEqualTo(405);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(resp.body()).contains("Method not allowed");
        }

        @Test
        @DisplayName("unknown path returns 404")
        void unknownPathReturns404() throws Exception {
            HttpResponse<String> resp = post("/nonexistent/path", "body");
            assertThat(resp.statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("404 response never echoes the requested path")
        void notFoundDoesNotEchoPath() throws Exception {
            HttpResponse<String> resp = post("/nonexistent/path", "body");
            assertThat(resp.body()).doesNotContain("nonexistent");
            assertThat(resp.body()).contains("not_found");
        }

        @Test
        @DisplayName("oversize body returns 413")
        void oversizeBodyReturns413() throws Exception {
            CompatibilityMockServer tinyServer = new CompatibilityMockServer(0, 5, 50);
            try {
                int p = tinyServer.getPort();
                String bigBody = "{\"model\":\"x\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(200)
                        + "\"}]}";
                HttpResponse<String> resp = postToPort(p, "/v1/messages", bigBody);
                assertThat(resp.statusCode()).isEqualTo(413);
            } finally {
                tinyServer.close();
            }
        }

        @Test
        @DisplayName("413 response never echoes body content")
        void oversizedBodyNotEchoed() throws Exception {
            CompatibilityMockServer tinyServer = new CompatibilityMockServer(0, 5, 20);
            try {
                int p = tinyServer.getPort();
                String sentinel = "SENTINEL-DATA-SHOULD-NEVER-APPEAR";
                String bigBody = "{\"model\":\"x\",\"messages\":[{\"role\":\"user\",\"content\":\"" + sentinel
                        + "\"}]}";
                HttpResponse<String> resp = postToPort(p, "/v1/messages", bigBody);
                assertThat(resp.statusCode()).isEqualTo(413);
                assertThat(resp.body()).doesNotContain(sentinel);
            } finally {
                tinyServer.close();
            }
        }
    }

    // === Loopback binding ============================================

    @Nested
    @DisplayName("Loopback binding")
    class LoopbackBinding {

        @Test
        @DisplayName("server binds and responds")
        void serverResponds() throws Exception {
            HttpResponse<String> resp = get("/health");
            assertThat(resp.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("server port is positive")
        void portIsPositive() {
            assertThat(port).isGreaterThan(0);
        }

        @Test
        @DisplayName("port 0 yields unique ephemeral ports")
        void portZeroYieldsEphemeralPort() throws Exception {
            CompatibilityMockServer s1 = new CompatibilityMockServer(0, 5);
            CompatibilityMockServer s2 = new CompatibilityMockServer(0, 5);
            try {
                assertThat(s1.getPort()).isGreaterThan(0);
                assertThat(s2.getPort()).isGreaterThan(0);
                assertThat(s1.getPort()).isNotEqualTo(s2.getPort());
            } finally {
                s1.close();
                s2.close();
            }
        }
    }

    // === Privacy safety ==============================================

    @Nested
    @DisplayName("Privacy safety")
    class PrivacySafety {

        @Test
        @DisplayName("snapshot JSON never contains request body content")
        void snapshotNeverContainsBodyContent() throws Exception {
            String uniqueContent = "UNIQUE-BODY-CONTENT-" + System.nanoTime();
            post("/v1/messages", "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\""
                    + uniqueContent + "\"}]}");
            HttpResponse<String> resp = get("/observations");
            assertThat(resp.body()).doesNotContain(uniqueContent);
        }

        @Test
        @DisplayName("snapshot JSON never contains prompt content")
        void snapshotNeverContainsPrompt() throws Exception {
            String promptText = "Tell me about the SECRET-PROJECT-XYZ";
            post("/v1/chat/completions",
                    "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"" + promptText + "\"}]}");
            HttpResponse<String> resp = get("/observations");
            assertThat(resp.body()).doesNotContain("SECRET-PROJECT-XYZ");
        }

        @Test
        @DisplayName("snapshot JSON never contains synthetic credential in body")
        void snapshotNeverContainsCredentialInBody() throws Exception {
            post("/v1/responses", "{\"model\":\"gpt-4o\",\"api_key\":\"sk-fake-key-body\",\"input\":\"Hello\"}");
            HttpResponse<String> resp = get("/observations");
            assertThat(resp.body()).doesNotContain("sk-fake-key-body");
            assertThat(resp.body()).doesNotContain("api_key");
        }

        @Test
        @DisplayName("synthetic model output is not in observations")
        void syntheticOutputNotInObservations() throws Exception {
            post("/v1/chat/completions", "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            HttpResponse<String> resp = get("/observations");
            assertThat(resp.body()).doesNotContain("Mock chat response");
            assertThat(resp.body()).doesNotContain("Mock response");
            assertThat(resp.body()).doesNotContain("Mock SSE");
        }

        @Test
        @DisplayName("observation snapshot only contains allowlisted fields and no body/header values")
        void snapshotOnlyContainsAllowedFields() throws Exception {
            postWithHeader("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}",
                    "x-api-key", "sk-secret-12345");
            HttpResponse<String> resp = get("/observations");
            String json = resp.body();
            assertThat(json).contains("timestamp");
            assertThat(json).contains("requestId");
            assertThat(json).contains("httpMethod");
            assertThat(json).contains("rawUri");
            assertThat(json).contains("protocol");
            assertThat(json).contains("contentType");
            assertThat(json).contains("streamingRequest");
            assertThat(json).contains("forbiddenCredentialHeaderReached");
            assertThat(json).doesNotContain("bodyBytes");
            assertThat(json).doesNotContain("headers");
            assertThat(json).doesNotContain("sk-secret");
            assertThat(json).doesNotContain("\"content\":\"a\"");

            // Security boundary: parse JSON to verify exact field allowlist.
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> observations = mapper.readValue(json, new TypeReference<>() {
            });
            assertThat(observations).hasSize(1);
            Map<String, Object> obs = observations.get(0);
            Set<String> allowed = Set.of("timestamp", "requestId", "httpMethod", "rawUri", "protocol", "contentType",
                    "streamingRequest", "forbiddenCredentialHeaderReached");
            assertThat(obs).hasSize(8);
            assertThat(obs.keySet()).isEqualTo(allowed);
            // No value contains credential or body content
            for (Object v : obs.values()) {
                String s = String.valueOf(v);
                assertThat(s).doesNotContain("sk-secret");
                assertThat(s).doesNotContain("a\"");
            }
        }
    }

    // === Shutdown ====================================================

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("close releases the port for reuse")
        void closeReleasesPort() {
            CompatibilityMockServer s1 = new CompatibilityMockServer(0, 5);
            int port = s1.getPort();
            s1.close();
            assertThatCode(() -> {
                CompatibilityMockServer s2 = new CompatibilityMockServer(port, 1);
                s2.close();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("try-with-resources works")
        void tryWithResourcesWorks() {
            int port;
            try (CompatibilityMockServer s = new CompatibilityMockServer(0, 5)) {
                port = s.getPort();
                assertThat(port).isGreaterThan(0);
            }
            assertThatCode(() -> {
                CompatibilityMockServer s2 = new CompatibilityMockServer(0, 5);
                s2.close();
            }).doesNotThrowAnyException();
        }
    }

    // === Streaming detection =========================================

    @Nested
    @DisplayName("Streaming detection")
    class StreamingDetection {

        @Test
        @DisplayName("stream=true query param triggers SSE response")
        void streamQueryTriggersSse() throws Exception {
            HttpResponse<String> resp = post("/v1/messages?stream=true",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("text/event-stream");
            assertThat(server.getStore().snapshot().get(0).streamingRequest()).isTrue();
        }

        @Test
        @DisplayName("Accept: text/event-stream header triggers SSE response")
        void acceptSseTriggersSse() throws Exception {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/v1/messages"))
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}"))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("text/event-stream");
            assertThat(server.getStore().snapshot().get(0).streamingRequest()).isTrue();
        }

        @Test
        @DisplayName("stream:true in body triggers SSE response")
        void streamInBodyTriggersSse() throws Exception {
            HttpResponse<String> resp = post("/v1/chat/completions",
                    "{\"model\":\"gpt-4\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("text/event-stream");
            assertThat(server.getStore().snapshot().get(0).streamingRequest()).isTrue();
        }

        @Test
        @DisplayName("without stream indicators returns JSON")
        void noStreamReturnsJson() throws Exception {
            HttpResponse<String> resp = post("/v1/responses", "{\"model\":\"gpt-4o\",\"input\":\"Hello\"}");
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(server.getStore().snapshot().get(0).streamingRequest()).isFalse();
        }
    }

    // === HTTP method recording =======================================

    @Nested
    @DisplayName("HTTP method recording")
    class HttpMethodRecording {

        @Test
        @DisplayName("POST method is recorded correctly")
        void postMethodRecorded() throws Exception {
            post("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            assertThat(server.getStore().snapshot().get(0).httpMethod()).isEqualTo("POST");
        }

        @Test
        @DisplayName("GET method is recorded correctly")
        void getMethodRecorded() throws Exception {
            get("/health");
            assertThat(server.getStore().snapshot().get(0).httpMethod()).isEqualTo("GET");
        }

        @Test
        @DisplayName("DELETE /observations clears the store and returns success")
        void deleteMethodRecorded() throws Exception {
            post("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            HttpResponse<String> resp = delete("/observations");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"cleared\":true");
            assertThat(server.getStore().size()).isZero();
        }
    }

    // === Content-Type recording ======================================

    @Nested
    @DisplayName("Content-Type recording")
    class ContentTypeRecording {

        @Test
        @DisplayName("contentType is recorded from request")
        void contentTypeRecorded() throws Exception {
            post("/v1/messages",
                    "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}");
            assertThat(server.getStore().snapshot().get(0).contentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("missing Content-Type is recorded as empty string")
        void missingContentTypeIsEmpty() throws Exception {
            get("/health");
            assertThat(server.getStore().snapshot().get(0).contentType()).isEmpty();
        }
    }

    // === Bounded body collector (Repair 1) ============================

    @Nested
    @DisplayName("Bounded body collector")
    class BodyBounding {

        @Test
        @DisplayName("body within the bound is inspected for stream=true")
        void bodyWithinBoundIsInspected() throws Exception {
            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, 100);
            try {
                int p = srv.getPort();
                HttpResponse<String> resp = postToPort(p, "/v1/messages",
                        "{\"model\":\"x\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.headers().firstValue("Content-Type")).hasValue("text/event-stream");
                assertThat(srv.getStore().snapshot().get(0).streamingRequest()).isTrue();
            } finally {
                srv.close();
            }
        }

        @Test
        @DisplayName("body exactly at the bound is still inspected")
        void bodyExactlyAtBoundIsInspected() throws Exception {
            // Construct a body whose UTF-8 byte length is exactly at the bound.
            // {"a":" + N b's + "} = 6 + N + 2 = N + 8
            int bound = 100;
            int padding = bound - 8; // 92
            String body = "{\"a\":\"" + "b".repeat(padding) + "\"}";
            assertThat(body.getBytes(StandardCharsets.UTF_8).length).isEqualTo(bound);

            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, bound);
            try {
                int p = srv.getPort();
                HttpResponse<String> resp = postToPort(p, "/v1/messages", body);
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
                assertThat(srv.getStore().snapshot().get(0).streamingRequest()).isFalse();
            } finally {
                srv.close();
            }
        }

        @Test
        @DisplayName("body exceeding bound returns 413")
        void bodyExceedingBoundReturns413() throws Exception {
            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, 50);
            try {
                int p = srv.getPort();
                String bigBody = "{\"model\":\"x\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(200)
                        + "\"}]}";
                HttpResponse<String> resp = postToPort(p, "/v1/messages", bigBody);
                assertThat(resp.statusCode()).isEqualTo(413);
                // Anthropic error format
                assertThat(resp.body()).contains("invalid_request_error");
            } finally {
                srv.close();
            }
        }

        @Test
        @DisplayName("body much larger than bound returns 413 and does not OOM")
        void bodyMuchLargerThanBoundReturns413Safe() throws Exception {
            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, 50);
            try {
                int p = srv.getPort();
                // Build a 500 kB body — far larger than the 50-byte bound.
                String bigBody = "{\"model\":\"x\",\"messages\":[" + "x".repeat(500_000) + "]}";
                HttpResponse<String> resp = postToPort(p, "/v1/messages", bigBody);
                assertThat(resp.statusCode()).isEqualTo(413);
                // Server must still respond to health checks.
                HttpResponse<String> health = getFromPort(p, "/health");
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(health.body()).contains("\"status\":\"UP\"");
            } finally {
                srv.close();
            }
        }

        @Test
        @DisplayName("413 response never echoes oversize body content")
        void oversized413NeverEchoesBody() throws Exception {
            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, 20);
            try {
                int p = srv.getPort();
                String sentinel = "SENTINEL-DATA-NEVER-APPEAR-413";
                String bigBody = "{\"model\":\"x\",\"data\":\"" + sentinel + "\"}";
                HttpResponse<String> resp = postToPort(p, "/v1/messages", bigBody);
                assertThat(resp.statusCode()).isEqualTo(413);
                assertThat(resp.body()).doesNotContain(sentinel);
                // Observations must also be free of the body content.
                HttpResponse<String> obs = getFromPort(p, "/observations");
                assertThat(obs.body()).doesNotContain(sentinel);
                assertThat(obs.body()).doesNotContain("model\":\"x\"");
            } finally {
                srv.close();
            }
        }

        @Test
        @DisplayName("empty body within bound produces streaming=false")
        void emptyBodyWithinBound() throws Exception {
            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, 200);
            try {
                int p = srv.getPort();
                HttpResponse<String> resp = postToPort(p, "/v1/messages", "");
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(srv.getStore().snapshot().get(0).streamingRequest()).isFalse();
            } finally {
                srv.close();
            }
        }

        @Test
        @DisplayName("bounded collector never retains more than limit+1 bytes with fragmented input")
        void boundedCollectorFragmentedInput() {
            int bound = 20;
            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, bound);
            // 5 chunks of 10 bytes each = 50 bytes total, bound is 20.
            ByteBuf c1 = Unpooled.copiedBuffer("AAAAAAAAAA", StandardCharsets.UTF_8);
            ByteBuf c2 = Unpooled.copiedBuffer("BBBBBBBBBB", StandardCharsets.UTF_8);
            ByteBuf c3 = Unpooled.copiedBuffer("CCCCCCCCCC", StandardCharsets.UTF_8);
            ByteBuf c4 = Unpooled.copiedBuffer("DDDDDDDDDD", StandardCharsets.UTF_8);
            ByteBuf c5 = Unpooled.copiedBuffer("EEEEEEEEEE", StandardCharsets.UTF_8);
            try {
                Flux<ByteBuf> chunks = Flux.just(c1, c2, c3, c4, c5);
                byte[] result = srv.collectBodyBounded(chunks).block();
                // Chunks 1-2 each consume 10 bytes (position 20, not > bound);
                // chunk 3 reads 1 byte → position 21 > bound → cancel.
                // Result array always has exactly bound+1 length.
                assertThat(result).isNotNull();
                assertThat(result.length).isEqualTo(bound + 1);
            } finally {
                // Release any ByteBufs not consumed by the cancelled subscription.
                for (ByteBuf buf : new ByteBuf[]{c1, c2, c3, c4, c5}) {
                    if (buf.refCnt() > 0) {
                        buf.release();
                    }
                }
                srv.close();
            }
        }

        @Test
        @DisplayName("bounded collector for body within bound returns correct content")
        void boundedCollectorWithinBound() {
            int bound = 100;
            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, bound);
            ByteBuf c1 = Unpooled.copiedBuffer("Hello, ", StandardCharsets.UTF_8);
            ByteBuf c2 = Unpooled.copiedBuffer("World!", StandardCharsets.UTF_8);
            try {
                Flux<ByteBuf> chunks = Flux.just(c1, c2);
                byte[] result = srv.collectBodyBounded(chunks).block();
                assertThat(result).isNotNull();
                assertThat(result.length).isLessThanOrEqualTo(bound);
                String content = new String(result, StandardCharsets.UTF_8);
                assertThat(content).isEqualTo("Hello, World!");
            } finally {
                for (ByteBuf buf : new ByteBuf[]{c1, c2}) {
                    if (buf.refCnt() > 0) {
                        buf.release();
                    }
                }
                srv.close();
            }
        }

        @Test
        @DisplayName("bounded collector with single chunk exceeding bound")
        void boundedCollectorSingleChunkExceeds() {
            int bound = 10;
            CompatibilityMockServer srv = new CompatibilityMockServer(0, 5, bound);
            ByteBuf chunk = Unpooled.copiedBuffer("This body is way too large for the bound", StandardCharsets.UTF_8);
            try {
                Flux<ByteBuf> flux = Flux.just(chunk);
                byte[] result = srv.collectBodyBounded(flux).block();
                assertThat(result).isNotNull();
                assertThat(result.length).isEqualTo(bound + 1);
            } finally {
                if (chunk.refCnt() > 0) {
                    chunk.release();
                }
                srv.close();
            }
        }
    }

    // === Media type normalization (Repair 2) ==========================

    @Nested
    @DisplayName("Media type normalization")
    class MediaTypeNormalization {

        @Test
        @DisplayName("plain media type is preserved as-is")
        void plainMediaTypePreserved() {
            assertThat(CompatibilityMockServer.normalizeMediaType("application/json")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("parameters are stripped")
        void parametersStripped() {
            assertThat(CompatibilityMockServer.normalizeMediaType("application/json; charset=utf-8"))
                    .isEqualTo("application/json");
        }

        @Test
        @DisplayName("multiple parameters are stripped")
        void multipleParametersStripped() {
            assertThat(CompatibilityMockServer.normalizeMediaType("text/plain; charset=utf-8; boundary=abc"))
                    .isEqualTo("text/plain");
        }

        @Test
        @DisplayName("mixed case is lowercased")
        void mixedCaseLowercased() {
            assertThat(CompatibilityMockServer.normalizeMediaType("Application/JSON")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("whitespace is trimmed")
        void whitespaceTrimmed() {
            assertThat(CompatibilityMockServer.normalizeMediaType("  text/plain  ")).isEqualTo("text/plain");
        }

        @Test
        @DisplayName("whitespace with parameters")
        void whitespaceWithParameters() {
            assertThat(CompatibilityMockServer.normalizeMediaType("  text/html ; charset=utf-8 "))
                    .isEqualTo("text/html");
        }

        @Test
        @DisplayName("null input returns empty")
        void nullReturnsEmpty() {
            assertThat(CompatibilityMockServer.normalizeMediaType(null)).isEmpty();
        }

        @Test
        @DisplayName("empty input returns empty")
        void emptyReturnsEmpty() {
            assertThat(CompatibilityMockServer.normalizeMediaType("")).isEmpty();
        }

        @Test
        @DisplayName("blank input returns empty")
        void blankReturnsEmpty() {
            assertThat(CompatibilityMockServer.normalizeMediaType("   ")).isEmpty();
        }

        @Test
        @DisplayName("invalid form without slash returns empty")
        void invalidFormWithoutSlash() {
            assertThat(CompatibilityMockServer.normalizeMediaType("invalid")).isEmpty();
        }

        @Test
        @DisplayName("invalid form with only slash returns empty")
        void invalidFormOnlySlash() {
            assertThat(CompatibilityMockServer.normalizeMediaType("/")).isEmpty();
        }

        @Test
        @DisplayName("value starting with slash returns empty")
        void valueStartingWithSlash() {
            assertThat(CompatibilityMockServer.normalizeMediaType("/json")).isEmpty();
        }

        @Test
        @DisplayName("value ending with slash returns empty")
        void valueEndingWithSlash() {
            assertThat(CompatibilityMockServer.normalizeMediaType("application/")).isEmpty();
        }

        @Test
        @DisplayName("oversized value returns empty")
        void oversizedReturnsEmpty() {
            String longType = "a/".repeat(100) + "b";
            assertThat(CompatibilityMockServer.normalizeMediaType(longType)).isEmpty();
        }

        @Test
        @DisplayName("normalized Content-Type is stored in observations")
        void normalizedContentTypeInObservations() throws Exception {
            // Send a request with parameters in Content-Type
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/v1/messages"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}"))
                    .build();
            HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            RequestObservation obs = server.getStore().snapshot().get(0);
            // Must be normalized — no parameters, lowercased.
            assertThat(obs.contentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("observations never contain raw parameter values")
        void observationsNeverContainRawParameters() throws Exception {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/v1/messages"))
                    .header("Content-Type", "application/json; charset=windows-1252")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"x\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}"))
                    .build();
            HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            HttpResponse<String> snap = get("/observations");
            assertThat(snap.body()).doesNotContain("windows-1252");
            assertThat(snap.body()).doesNotContain("charset");
            assertThat(snap.body()).contains("\"contentType\":\"application/json\"");
        }
    }

    // =================================================================
    // HTTP Helpers
    // =================================================================

    private HttpResponse<String> get(String path) throws Exception {
        return getFromPort(port, path);
    }

    private HttpResponse<String> getFromPort(int p, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + p + path)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path)).DELETE().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return postToPort(port, path, body);
    }

    private HttpResponse<String> postToPort(int p, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + p + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postSse(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithHeader(String path, String body, String headerName, String headerValue)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").header(headerName, headerValue)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithHeaders(String path, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String body(String model, String rest) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":100," + rest + "}";
    }
}
