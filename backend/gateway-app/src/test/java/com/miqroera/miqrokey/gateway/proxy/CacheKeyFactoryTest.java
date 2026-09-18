package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
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
        assertThat(factory.compute(ctx, "m", streaming)).isNotEqualTo(factory.compute(ctx, "m", buffered));
        // Field order and position must not matter for the same format.
        assertThat(factory.compute(ctx, "m", streaming)).isEqualTo(factory.compute(ctx, "m", streamingAgain));
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
            CacheKey k1 = factory.compute(ctx, "gpt-4o-mini", a);
            CacheKey k2 = factory.compute(ctx, "gpt-4o-mini", b);
            assertThat(k1).isEqualTo(k2);
        }

        @Test
        @DisplayName("should differ across tenants, keys, and models")
        void shouldDifferAcrossIdentity() {
            byte[] body = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            CacheKey base = factory.compute(ctx, "gpt-4o-mini", body);
            AuthContext otherKey = context(GatewayTestKeys.OTHER_KEY);
            assertThat(factory.compute(otherKey, "gpt-4o-mini", body)).isNotEqualTo(base);
            assertThat(factory.compute(ctx, "demo-model", body)).isNotEqualTo(base);
        }

        @Test
        @DisplayName("should differ when the normalized body differs")
        void shouldDifferWhenBodyDiffers() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"bye\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a)).isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", b));
        }

        @Test
        @DisplayName("should be a SHA-256 digest, never the body")
        void shouldBeDigest() {
            byte[] body = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            CacheKey key = factory.compute(ctx, "gpt-4o-mini", body);
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
            assertThat(factory.compute(ctx, "gpt-4o-mini", shortHist))
                    .isEqualTo(factory.compute(ctx, "gpt-4o-mini", longHist));
        }

        @Test
        @DisplayName("same for Anthropic messages shape")
        void anthropicShape() {
            byte[] a = json("{\"model\":\"claude-3-7-sonnet\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"claude-3-7-sonnet\",\"messages\":[{\"role\":\"assistant\",\"content\":\"x\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", a))
                    .isEqualTo(factory.compute(ctx, "claude-3-7-sonnet", b));
        }

        @Test
        @DisplayName("same for OpenAI Responses input shape including plain strings")
        void responsesShape() {
            byte[] a = json("{\"model\":\"gpt-5.2\",\"input\":[\"hi\"]}");
            byte[] b = json("{\"model\":\"gpt-5.2\",\"input\":[{\"role\":\"user\",\"content\":\"first\"},"
                    + "{\"role\":\"assistant\",\"content\":\"reply\"},\"hi\"]}");
            assertThat(factory.compute(ctx, "gpt-5.2", a)).isEqualTo(factory.compute(ctx, "gpt-5.2", b));
        }

        @Test
        @DisplayName("system prompt is part of the scope: different system misses")
        void systemMatters() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"system\",\"content\":\"be terse\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"system\",\"content\":\"be verbose\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a)).isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", b));
        }

        @Test
        @DisplayName("array content parts are flattened into the scope")
        void arrayContentParts() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":["
                    + "{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"text\",\"text\":\" there\"}]}]}");
            byte[] b = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi there\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a)).isEqualTo(factory.compute(ctx, "gpt-4o-mini", b));
        }

        @Test
        @DisplayName("no extractable user message falls back to the full body")
        void fallbackWithoutUserMessage() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"assistant\",\"content\":\"only\"}]}");
            byte[] b = json(
                    "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"assistant\",\"content\":\"other\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a)).isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", b));
        }

        @Test
        @DisplayName("different last user message still misses")
        void differentQuestionMisses() {
            byte[] a = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] b = json("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"bye\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", a)).isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", b));
        }
    }

    /**
     * Key-identity hardening (2026-09-18, external review of the semantic-cache
     * evaluation): the chat path keeps only the conversation scope, so
     * output-shaping generation parameters and the Anthropic/Responses top-level
     * system prompt must be explicit key dimensions — otherwise two requests with
     * different sampling or system prompts would replay each other's responses.
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

            assertThat(factory.compute(ctx, "gpt-4o-mini", warm))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", cold));
            assertThat(factory.compute(ctx, "gpt-4o-mini", warm))
                    .isNotEqualTo(factory.compute(ctx, "gpt-4o-mini", budget));
            // Identical parameters (any field order) stay a stable hit.
            byte[] warmAgain = json("{\"max_tokens\":256,\"temperature\":0.9,\"model\":\"gpt-4o-mini\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "gpt-4o-mini", warm))
                    .isEqualTo(factory.compute(ctx, "gpt-4o-mini", warmAgain));
            // Absent parameters keep the pre-existing key shape (both absent = equal).
            assertThat(factory.compute(ctx, "gpt-4o-mini", json(BASE)))
                    .isEqualTo(factory.compute(ctx, "gpt-4o-mini", json(BASE)));
        }

        @Test
        @DisplayName("thinking budget is a key dimension (Anthropic)")
        void thinkingBudgetSplits() {
            byte[] small = json("{\"model\":\"claude-3-7-sonnet\",\"thinking\":{\"type\":\"enabled\","
                    + "\"budget_tokens\":1024},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            byte[] large = json("{\"model\":\"claude-3-7-sonnet\",\"thinking\":{\"type\":\"enabled\","
                    + "\"budget_tokens\":16384},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", small))
                    .isNotEqualTo(factory.compute(ctx, "claude-3-7-sonnet", large));
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

            assertThat(factory.compute(ctx, "claude-3-7-sonnet", a))
                    .isNotEqualTo(factory.compute(ctx, "claude-3-7-sonnet", b));
            // Array-form system parts flatten identically to the plain string.
            assertThat(factory.compute(ctx, "claude-3-7-sonnet", a))
                    .isEqualTo(factory.compute(ctx, "claude-3-7-sonnet", sameAsA));
        }

        @Test
        @DisplayName("OpenAI Responses instructions field is part of the scope")
        void responsesInstructionsMatter() {
            byte[] a = json("{\"model\":\"gpt-5.2\",\"instructions\":\"answer briefly\",\"input\":[\"hi\"]}");
            byte[] b = json("{\"model\":\"gpt-5.2\",\"instructions\":\"answer verbosely\",\"input\":[\"hi\"]}");
            assertThat(factory.compute(ctx, "gpt-5.2", a)).isNotEqualTo(factory.compute(ctx, "gpt-5.2", b));
        }
    }
}
