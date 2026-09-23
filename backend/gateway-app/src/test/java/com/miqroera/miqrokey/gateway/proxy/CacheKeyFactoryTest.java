package com.miqroera.miqrokey.gateway.proxy;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the normalized cache key derivation (ADR-0008): the key is
 * a SHA-256 over the request identity, never the raw body.
 */
@DisplayName("CacheKeyFactory")
class CacheKeyFactoryTest {

    private final CacheKeyFactory factory = new CacheKeyFactory(new ObjectMapper());

    private AuthContext context(GatewayTestKeys.KeyFixture key) {
        return new AuthContext(key.keyRecord(GatewayTestKeys.TENANT_ID), key.bindingRecord(),
                java.util.Set.copyOf(key.models()), GatewayTestKeys.snapshot("http://mock.example", key));
    }

    private final AuthContext ctx = context(GatewayTestKeys.DEFAULT_KEY);

    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The wire protocol family for tests that do not exercise the protocol
     * dimension itself — the chat endpoint's family (#1236).
     */
    private static final ProtocolFamily CHAT_FAMILY = ProtocolFamily.OPENAI_CHAT_COMPLETIONS;

    @Test
    @DisplayName("wire protocol family is a key dimension (#1236)")
    void wireProtocolChangesTheKey() {
        byte[] body = json("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        // The same bytes sent to two endpoints produce two response shapes, so
        // the family must split the key — otherwise the cached chat answer
        // replays into an Anthropic client (#444's argument one level up).
        assertThat(factory.compute(ctx, "m", body, ProtocolFamily.OPENAI_CHAT_COMPLETIONS))
                .isNotEqualTo(factory.compute(ctx, "m", body, ProtocolFamily.ANTHROPIC_MESSAGES));
        // Same endpoint: still one key.
        assertThat(factory.compute(ctx, "m", body, ProtocolFamily.OPENAI_CHAT_COMPLETIONS))
                .isEqualTo(factory.compute(ctx, "m", body, ProtocolFamily.OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    @DisplayName("stream flag is a key format dimension (#444)")
    void streamFlagChangesTheKey() {
        byte[] streaming = json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}");
        byte[] buffered = json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}");
        byte[] streamingAgain = json(
                "{\"model\":\"m\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        // An SSE response must never be replayed to a JSON client (or vice versa).
        assertThat(factory.compute(ctx, "m", streaming, CHAT_FAMILY))
                .isNotEqualTo(factory.compute(ctx, "m", buffered, CHAT_FAMILY));
        // Field order and position must not matter for the same format.
        assertThat(factory.compute(ctx, "m", streaming, CHAT_FAMILY))
                .isEqualTo(factory.compute(ctx, "m", streamingAgain, CHAT_FAMILY));
    }

    @Nested
    @DisplayName("Normalization")
    class Normalization {

        @Test
        @DisplayName("should strip client-only fields that do not affect output")
        void shouldStripClientOnlyFields() {
            String withExtras = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                    + "\"stream\":true,\"stream_options\":{\"include_usage\":true},\"metadata\":{\"k\":\"v\"},"
                    + "\"user\":\"alice\"}";
            String without = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            assertThat(factory.normalize(json(withExtras))).isEqualTo(factory.normalize(json(without)));
        }

        @Test
        @DisplayName("should sort object keys recursively and strip whitespace")
        void shouldSortAndStripWhitespace() {
            String a = "{\"z\":1,\"a\":{\"y\":2,\"b\":3}}";
            String b = "{ \"a\" : { \"b\" : 3 , \"y\" : 2 } , \"z\" : 1 }";
            assertThat(factory.normalize(json(a))).isEqualTo(factory.normalize(json(b)));
        }

        @Test
        @DisplayName("should keep arrays ordered")
        void shouldKeepArrayOrder() {
            String a = "{\"messages\":[{\"role\":\"user\"},{\"role\":\"assistant\"}]}";
            String b = "{\"messages\":[{\"role\":\"assistant\"},{\"role\":\"user\"}]}";
            assertThat(factory.normalize(json(a))).isNotEqualTo(factory.normalize(json(b)));
        }

        @Test
        @DisplayName("should fall back to empty string for non-JSON or empty bodies")
        void shouldFallBackForNonJson() {
            assertThat(factory.normalize(null)).isEmpty();
            assertThat(factory.normalize(new byte[0])).isEmpty();
            assertThat(factory.normalize("not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Key derivation")
    class Derivation {

        @Test
        @DisplayName("should produce stable keys for semantically equal requests")
        void shouldBeStableForEqualRequests() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json(
                    "{ \"messages\": [ { \"content\" : \"hi\", \"role\" : \"user\" } ], \"model\" : \"gpt-4o-mini\" }");
            CacheKey k1 = factory.compute(ctx, "gpt-4o-mini", a, CHAT_FAMILY);
            CacheKey k2 = factory.compute(ctx, "gpt-4o-mini", b, CHAT_FAMILY);
            assertThat(k1).isEqualTo(k2);
        }

        @Test
        @DisplayName("should differ across tenants, keys, and models")
        void shouldDifferAcrossIdentity() {
            byte[] body = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            CacheKey base = factory.compute(ctx, "gpt-4o-mini", body, CHAT_FAMILY);
            AuthContext otherKey = context(GatewayTestKeys.OTHER_KEY);
            assertThat(factory.compute(otherKey, "gpt-4o-mini", body, CHAT_FAMILY)).isNotEqualTo(base);
            assertThat(factory.compute(ctx, "demo-model", body, CHAT_FAMILY)).isNotEqualTo(base);
        }

        @Test
        @DisplayName("should differ when the normalized body differs")
        void shouldDifferWhenBodyDiffers() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"bye\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", b, CHAT_FAMILY));
        }

        @Test
        @DisplayName("should be a SHA-256 digest, never the body")
        void shouldBeDigest() {
            byte[] body = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            CacheKey key = factory.compute(ctx, "gpt-4o-mini", body, CHAT_FAMILY);
            assertThat(key.sha256()).hasSize(32);
            assertThat(key.hex()).hasSize(64);
            // The key must not be recoverable as any substring of the request.
            assertThat(key.hex()).doesNotContain("gpt-4o-mini");
        }
    }

    @Nested
    @DisplayName("Semantic scope (system + last user message)")
    class SemanticScope {

        @Test
        @DisplayName("same last user message hits across different histories (OpenAI chat)")
        void chatHistoryIrrelevant() {
            byte[] shortHist = json(
                    "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] longHist = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"first\"},"
                    + "{\"role\":\"assistant\",\"content\":\"first reply\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", shortHist, CHAT_FAMILY))
                    .isEqualTo(factory.compute(ctx, "gpt-4o-mini", longHist, CHAT_FAMILY));
        }

        @Test
        @DisplayName("same for Anthropic messages shape")
        void anthropicShape() {
            byte[] a = json("{\"model\":\"claude-3-7-sonnet\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"claude-3-7-sonnet\",\"messages\":[{\"role\":\"assistant\",\"content\":\"x\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", a, CHAT_FAMILY))
                    .isEqualTo(factory.compute(ctx, "claude-3-7-sonnet", b, CHAT_FAMILY));
        }

        @Test
        @DisplayName("same for OpenAI Responses input shape including plain strings")
        void responsesShape() {
            byte[] a = json("{\"model\":\"gpt-5.2\",\"input\":[\"hi\"]}");
            byte[] b = json("{\"model\":\"gpt-5.2\",\"input\":[{\"role\":\"user\",\"content\":\"first\"},"
                    + "{\"role\":\"assistant\",\"content\":\"reply\"},\"hi\"]}");
            assertThat(factory.compute(ctx, "gpt-5.2", a, CHAT_FAMILY))
                    .isEqualTo(factory.compute(ctx, "gpt-5.2", b, CHAT_FAMILY));
        }

        @Test
        @DisplayName("system prompt is part of the scope: different system misses")
        void systemMatters() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"system\",\"content\":\"be terse\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"system\",\"content\":\"be verbose\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", b, CHAT_FAMILY));
        }

        @Test
        @DisplayName("array content parts are flattened into the scope")
        void arrayContentParts() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":["
                    + "{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"text\",\"text\":\" there\"}]}]}");
            byte[] b = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi there\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a, CHAT_FAMILY))
                    .isEqualTo(factory.compute(ctx, "gpt-4o-mini", b, CHAT_FAMILY));
        }

        @Test
        @DisplayName("no extractable user message falls back to the full body")
        void fallbackWithoutUserMessage() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"assistant\",\"content\":\"only\"}]}");
            byte[] b = json(
                    "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"assistant\",\"content\":\"other\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", b, CHAT_FAMILY));
        }

        @Test
        @DisplayName("different last user message still misses")
        void differentQuestionMisses() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"bye\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", b, CHAT_FAMILY));
        }
    }

    /**
     * Multimodal content (#PH22): the semantic scope flattens array content parts
     * by keeping only the {@code text} ones, so every non-text part — an image, an
     * uploaded document, an audio clip — is invisible to the key. Two requests that
     * ask the same question about two different images therefore share one cache
     * entry and replay each other's answer.
     *
     * <p>
     * The bail-out applies to <em>any</em> message part that is not text, at any
     * position: the last user turn, the top-level {@code system} / {@code
     * instructions} prompt, and the assistant history (which is where Anthropic
     * echoes back {@code thinking} / {@code redacted_thinking} blocks). The tests
     * below pin each of those to the full-body fallback so the widened trigger is
     * intended behaviour rather than an accident of where the check sits.
     */
    @Nested
    @DisplayName("Multimodal content parts")
    class MultimodalContent {

        private static final String ANTHROPIC = "{\"model\":\"claude-3-7-sonnet\",\"messages\":[{\"role\":\"user\","
                + "\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                + "\"media_type\":\"image/png\",\"data\":\"%s\"}},{\"type\":\"text\","
                + "\"text\":\"describe this image\"}]}]}";

        private static final String OPENAI = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"describe this image\"},{\"type\":\"image_url\","
                + "\"image_url\":{\"url\":\"%s\"}}]}]}";

        /** Anthropic top-level {@code system} array carrying the image. */
        private static final String ANTHROPIC_SYSTEM = "{\"model\":\"claude-3-7-sonnet\",\"system\":["
                + "{\"type\":\"text\",\"text\":\"describe this image\"},{\"type\":\"image\",\"source\":"
                + "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"%s\"}}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"what is this\"}]}";

        /** OpenAI Responses {@code instructions} array carrying the image. */
        private static final String RESPONSES_INSTRUCTIONS = "{\"model\":\"gpt-5.2\",\"instructions\":["
                + "{\"type\":\"text\",\"text\":\"describe this image\"},{\"type\":\"input_image\","
                + "\"image_url\":\"%s\"}],\"input\":[\"what is this\"]}";

        /** Non-text part in the assistant history, not in the last user turn. */
        private static final String ASSISTANT_HISTORY = "{\"model\":\"claude-3-7-sonnet\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"what is this\"},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                + "\"media_type\":\"image/png\",\"data\":\"%s\"}}]},"
                + "{\"role\":\"user\",\"content\":\"and this\"}]}";

        @Test
        @DisplayName("two different images must not share one key (Anthropic messages)")
        void anthropicImageSplits() {
            byte[] imageA = json(ANTHROPIC.formatted("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"));
            byte[] imageB = json(ANTHROPIC.formatted("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAC"));
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", imageA, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "claude-3-7-sonnet", imageB, CHAT_FAMILY));
        }

        @Test
        @DisplayName("two different image URLs must not share one key (OpenAI chat)")
        void openAiImageSplits() {
            byte[] imageA = json(OPENAI.formatted("https://example.test/cat.png"));
            byte[] imageB = json(OPENAI.formatted("https://example.test/dog.png"));
            assertThat(factory.compute(ctx, "gpt-4o-mini", imageA, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", imageB, CHAT_FAMILY));
        }

        @Test
        @DisplayName("image in the Anthropic top-level system array must not share one key")
        void anthropicTopLevelSystemImageSplits() {
            byte[] imageA = json(ANTHROPIC_SYSTEM.formatted("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"));
            byte[] imageB = json(ANTHROPIC_SYSTEM.formatted("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAC"));
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", imageA, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "claude-3-7-sonnet", imageB, CHAT_FAMILY));
        }

        @Test
        @DisplayName("image in the Responses instructions array must not share one key")
        void responsesInstructionsImageSplits() {
            byte[] imageA = json(RESPONSES_INSTRUCTIONS.formatted("https://example.test/cat.png"));
            byte[] imageB = json(RESPONSES_INSTRUCTIONS.formatted("https://example.test/dog.png"));
            assertThat(factory.compute(ctx, "gpt-5.2", imageA, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", imageB, CHAT_FAMILY));
        }

        @Test
        @DisplayName("non-text part in the assistant history forces the full-body key")
        void assistantHistoryNonTextFallsBack() {
            byte[] imageA = json(ASSISTANT_HISTORY.formatted("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"));
            byte[] imageB = json(ASSISTANT_HISTORY.formatted("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAC"));
            // The last user turn ("and this") is identical in both, so the scope
            // path would collapse them; the history image must keep them apart.
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", imageA, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "claude-3-7-sonnet", imageB, CHAT_FAMILY));
        }
    }

    /**
     * Hot-path cost: key derivation runs on the gateway request path, so the
     * buffered body must be parsed once per {@code compute} — not once per key
     * dimension.
     */
    @Nested
    @DisplayName("Hot-path parse cost")
    class HotPathParseCost {

        @Test
        @DisplayName("derives the key with a single parse of the request body")
        void singleBodyParse() {
            // Chat shape: scope is extractable, so the fallback normalize() is
            // not reached — this is the 3-parse case.
            byte[] chat = json("{\"model\":\"gpt-4o-mini\",\"temperature\":0.9,\"max_tokens\":256,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            // Non-chat shape: no extractable user message, so the key falls back
            // to the normalized body — the worst case, one parse more than chat.
            byte[] fallback = json("{\"model\":\"text-embedding-3-small\",\"input\":\"hello world\"}");

            assertThat(parsesFor(chat)).isEqualTo(1);
            assertThat(parsesFor(fallback)).isEqualTo(1);
        }

        /** Full-body parses performed by one {@code compute} of the given body. */
        private int parsesFor(byte[] body) {
            CountingObjectMapper counting = new CountingObjectMapper();
            new CacheKeyFactory(counting).compute(ctx, "gpt-4o-mini", body, CHAT_FAMILY);
            return counting.bodyParses();
        }
    }

    /** Counts full-body JSON parses; the helpers must share one parsed tree. */
    private static final class CountingObjectMapper extends ObjectMapper {

        private static final long serialVersionUID = 1L;

        private final java.util.concurrent.atomic.AtomicInteger bodyParses = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public tools.jackson.databind.JsonNode readTree(byte[] content) {
            if (content != null && content.length > 0) {
                bodyParses.incrementAndGet();
            }
            return super.readTree(content);
        }

        int bodyParses() {
            return bodyParses.get();
        }
    }

    /**
     * Key-identity hardening (2026-09-18, external review of the semantic-cache
     * evaluation): the chat path keeps only the conversation scope, so
     * output-shaping generation parameters and the Anthropic/Responses top-level
     * system prompt must be explicit key dimensions — otherwise two requests with
     * different sampling or system prompts would replay each other's responses.
     *
     * <p>
     * Second wave (#1302 follow-up, PH64 adversarial review): the same rule has to
     * hold one level down. The OpenAI Responses protocol spells several of these
     * knobs as nested objects ({@code text.format} ≈ {@code response_format},
     * {@code text.verbosity} ≈ {@code verbosity}, {@code reasoning.effort} ≈
     * {@code reasoning_effort}), and a Responses body with an extractable scope
     * never falls back to the full body — so a nested spelling that is not picked
     * collides exactly like the unlisted flat names of #1302.
     */
    @Nested
    @DisplayName("Key identity hardening")
    class IdentityHardening {

        private static final String BASE = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\","
                + "\"content\":\"hi\"}]}";

        @Test
        @DisplayName("generation parameters are a key dimension (temperature / token budget)")
        void generationParametersSplit() {
            byte[] warm = json("{\"model\":\"gpt-4o-mini\",\"temperature\":0.9,\"max_tokens\":256,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] cold = json("{\"model\":\"gpt-4o-mini\",\"temperature\":0.1,\"max_tokens\":256,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] budget = json("{\"model\":\"gpt-4o-mini\",\"temperature\":0.9,\"max_tokens\":1024,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

            assertThat(factory.compute(ctx, "gpt-4o-mini", warm, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", cold, CHAT_FAMILY));
            assertThat(factory.compute(ctx, "gpt-4o-mini", warm, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", budget, CHAT_FAMILY));
            // Identical parameters (any field order) stay a stable hit.
            byte[] warmAgain = json("{\"max_tokens\":256,\"temperature\":0.9,\"model\":\"gpt-4o-mini\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", warm, CHAT_FAMILY))
                    .isEqualTo(factory.compute(ctx, "gpt-4o-mini", warmAgain, CHAT_FAMILY));
            // Absent parameters keep the pre-existing key shape (both absent = equal).
            assertThat(factory.compute(ctx, "gpt-4o-mini", json(BASE), CHAT_FAMILY))
                    .isEqualTo(factory.compute(ctx, "gpt-4o-mini", json(BASE), CHAT_FAMILY));
        }

        @Test
        @DisplayName("thinking budget is a key dimension (Anthropic)")
        void thinkingBudgetSplits() {
            byte[] small = json("{\"model\":\"claude-3-7-sonnet\",\"thinking\":{\"type\":\"enabled\","
                    + "\"budget_tokens\":1024},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] large = json("{\"model\":\"claude-3-7-sonnet\",\"thinking\":{\"type\":\"enabled\","
                    + "\"budget_tokens\":16384},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", small, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "claude-3-7-sonnet", large, CHAT_FAMILY));
        }

        @Test
        @DisplayName("Anthropic top-level system prompt is part of the scope")
        void anthropicTopLevelSystemMatters() {
            byte[] a = json("{\"model\":\"claude-3-7-sonnet\",\"system\":\"You are terse.\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"claude-3-7-sonnet\",\"system\":\"You are verbose.\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] sameAsA = json("{\"model\":\"claude-3-7-sonnet\",\"system\":[{\"type\":\"text\","
                    + "\"text\":\"You are terse.\"}],\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

            assertThat(factory.compute(ctx, "claude-3-7-sonnet", a, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "claude-3-7-sonnet", b, CHAT_FAMILY));
            // Array-form system parts flatten identically to the plain string.
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", a, CHAT_FAMILY))
                    .isEqualTo(factory.compute(ctx, "claude-3-7-sonnet", sameAsA, CHAT_FAMILY));
        }

        @Test
        @DisplayName("OpenAI Responses instructions field is part of the scope")
        void responsesInstructionsMatter() {
            byte[] a = json("{\"model\":\"gpt-5.2\",\"instructions\":\"answer briefly\",\"input\":[\"hi\"]}");
            byte[] b = json("{\"model\":\"gpt-5.2\",\"instructions\":\"answer verbosely\",\"input\":[\"hi\"]}");
            assertThat(factory.compute(ctx, "gpt-5.2", a, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", b, CHAT_FAMILY));
        }

        @Test
        @DisplayName("Anthropic stop_sequences is a key dimension")
        void anthropicStopSequencesSplit() {
            byte[] unbounded = json("{\"model\":\"claude-3-7-sonnet\",\"max_tokens\":256,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] bounded = json("{\"model\":\"claude-3-7-sonnet\",\"max_tokens\":256,"
                    + "\"stop_sequences\":[\"\\n\\nHuman:\"],\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

            // Anthropic names this parameter "stop_sequences"; "stop" (OpenAI's
            // name) is already a dimension, so an Anthropic client that bounds the
            // generation must not replay an unbounded response.
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", unbounded, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "claude-3-7-sonnet", bounded, CHAT_FAMILY));
        }

        @Test
        @DisplayName("max_completion_tokens is a key dimension (current OpenAI name)")
        void maxCompletionTokensSplit() {
            byte[] tiny = json("{\"model\":\"gpt-4o-mini\",\"max_completion_tokens\":16,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] large = json("{\"model\":\"gpt-4o-mini\",\"max_completion_tokens\":4096,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

            // max_tokens (the legacy name) is a dimension; max_completion_tokens is
            // the name current OpenAI models require, and must split identically.
            assertThat(factory.compute(ctx, "gpt-4o-mini", tiny, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", large, CHAT_FAMILY));
        }

        @Test
        @DisplayName("logprobs / top_logprobs are key dimensions")
        void logprobsSplit() {
            byte[] plain = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] withLogprobs = json("{\"model\":\"gpt-4o-mini\",\"logprobs\":true,\"top_logprobs\":5,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

            // A client that asked for token-level probabilities must not receive a
            // cached response that carries none.
            assertThat(factory.compute(ctx, "gpt-4o-mini", plain, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", withLogprobs, CHAT_FAMILY));
        }

        @Test
        @DisplayName("stream_options.include_usage is a key dimension")
        void streamOptionsIncludeUsageSplits() {
            byte[] withoutUsage = json("{\"model\":\"gpt-4o-mini\",\"stream\":true,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] withUsage = json("{\"model\":\"gpt-4o-mini\",\"stream\":true,"
                    + "\"stream_options\":{\"include_usage\":true},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

            // include_usage makes the upstream append a final usage chunk; replaying
            // a stream that lacks it violates the client's stream contract.
            assertThat(factory.compute(ctx, "gpt-4o-mini", withoutUsage, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", withUsage, CHAT_FAMILY));
        }

        @Test
        @DisplayName("Responses nested text knobs are key dimensions (text.format / text.verbosity)")
        void responsesNestedTextKnobsSplit() {
            String base = "{\"model\":\"gpt-5.2\",\"instructions\":\"answer briefly\",\"input\":[\"hi\"],";
            byte[] plainText = json(base + "\"text\":{\"format\":{\"type\":\"text\"}}}");
            byte[] jsonSchema = json(base + "\"text\":{\"format\":{\"type\":\"json_schema\",\"name\":\"out\","
                    + "\"schema\":{\"type\":\"object\"}}}}");
            byte[] terse = json(base + "\"text\":{\"verbosity\":\"low\"}}");
            byte[] chatty = json(base + "\"text\":{\"verbosity\":\"high\"}}");

            // Responses spells response_format/verbosity as a nested object, and this
            // body has an extractable scope, so an unpicked nested field is invisible
            // to the key: a client that demanded a JSON schema would be served a
            // cached plain-text answer.
            assertThat(factory.compute(ctx, "gpt-5.2", plainText, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", jsonSchema, CHAT_FAMILY));
            assertThat(factory.compute(ctx, "gpt-5.2", terse, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", chatty, CHAT_FAMILY));
            // Key order inside the nested object is normalized away, so an
            // equivalent body stays a stable hit.
            byte[] schemaReordered = json(
                    base + "\"text\":{\"format\":{\"schema\":{\"type\":\"object\"},\"name\":\"out\","
                            + "\"type\":\"json_schema\"}}}");
            assertThat(factory.compute(ctx, "gpt-5.2", jsonSchema, CHAT_FAMILY))
                    .isEqualTo(factory.compute(ctx, "gpt-5.2", schemaReordered, CHAT_FAMILY));
        }

        @Test
        @DisplayName("Responses reasoning.effort is a key dimension")
        void responsesNestedReasoningSplits() {
            String base = "{\"model\":\"gpt-5.2\",\"instructions\":\"answer briefly\",\"input\":[\"hi\"],";
            byte[] low = json(base + "\"reasoning\":{\"effort\":\"low\"}}");
            byte[] high = json(base + "\"reasoning\":{\"effort\":\"high\"}}");

            assertThat(factory.compute(ctx, "gpt-5.2", low, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", high, CHAT_FAMILY));
        }

        @Test
        @DisplayName("Responses payload-shaping flags are key dimensions (include / truncation / background)")
        void responsesPayloadShapingFlagsSplit() {
            String base = "{\"model\":\"gpt-5.2\",\"instructions\":\"answer briefly\",\"input\":[\"hi\"]";
            byte[] plain = json(base + "}");
            byte[] withReasoningItems = json(base + ",\"include\":[\"reasoning.encrypted_content\"]}");
            byte[] autoTruncation = json(base + ",\"truncation\":\"auto\"}");
            byte[] disabledTruncation = json(base + ",\"truncation\":\"disabled\"}");
            byte[] background = json(base + ",\"background\":true}");

            // Each of these changes what the client receives back (extra output
            // items, a truncated input, an in-progress envelope) without touching
            // the scope, so a shared entry would return the wrong payload shape.
            assertThat(factory.compute(ctx, "gpt-5.2", plain, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", withReasoningItems, CHAT_FAMILY));
            assertThat(factory.compute(ctx, "gpt-5.2", autoTruncation, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", disabledTruncation, CHAT_FAMILY));
            assertThat(factory.compute(ctx, "gpt-5.2", plain, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", background, CHAT_FAMILY));
        }

        @Test
        @DisplayName("chat audio output knobs are key dimensions (modalities / audio)")
        void chatAudioOutputSplits() {
            String base = "{\"model\":\"gpt-4o-audio-preview\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]";
            byte[] textOnly = json(base + ",\"modalities\":[\"text\"]}");
            byte[] withAudio = json(base + ",\"modalities\":[\"text\",\"audio\"]}");
            byte[] alloy = json(
                    base + ",\"modalities\":[\"text\",\"audio\"],\"audio\":{\"voice\":\"alloy\",\"format\":\"wav\"}}");
            byte[] echo = json(
                    base + ",\"modalities\":[\"text\",\"audio\"],\"audio\":{\"voice\":\"echo\",\"format\":\"wav\"}}");

            // The response payload is audio, not text: a text-only client must not
            // receive a base64 audio envelope, nor a client that chose a voice
            // receive another one's audio.
            assertThat(factory.compute(ctx, "gpt-4o-audio-preview", textOnly, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-audio-preview", withAudio, CHAT_FAMILY));
            assertThat(factory.compute(ctx, "gpt-4o-audio-preview", alloy, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-audio-preview", echo, CHAT_FAMILY));
        }

        @Test
        @DisplayName("Responses continuation pointer is a key dimension (previous_response_id)")
        void responsesContinuationPointerSplits() {
            String base = "{\"model\":\"gpt-5.2\",\"instructions\":\"answer briefly\",\"input\":[\"hi\"]";
            byte[] firstConversation = json(base + ",\"previous_response_id\":\"resp_a\"}");
            byte[] secondConversation = json(base + ",\"previous_response_id\":\"resp_b\"}");

            // The referenced history is invisible to the gateway — the body carries
            // only the new turn — so the pointer is the sole representation of the
            // conversation and has to split the key.
            assertThat(factory.compute(ctx, "gpt-5.2", firstConversation, CHAT_FAMILY))
                    .isNotEqualTo(factory.compute(ctx, "gpt-5.2", secondConversation, CHAT_FAMILY));
        }
    }
}
