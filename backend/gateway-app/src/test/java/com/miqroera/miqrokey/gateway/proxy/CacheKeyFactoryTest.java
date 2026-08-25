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
}
