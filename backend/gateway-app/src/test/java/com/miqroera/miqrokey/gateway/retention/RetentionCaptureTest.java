package com.miqroera.miqrokey.gateway.retention;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.RetentionConfig;
import com.miqroera.miqrokey.domain.model.RetentionDirection;
import com.miqroera.miqrokey.domain.model.RetentionEnvelope;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.InMemoryRouteSnapshotProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0014 R2 capture semantics, deterministic (no HTTP/scheduling timing):
 * with the tenant retention switch on, a buffered LLM request body yields
 * exactly one {@link RetentionEnvelope} whose decrypted payload is the user
 * turns only (system prompt excluded); with the switch off, nothing is
 * captured. The crypto stand-in is invertible — the real AES-GCM path is the
 * domain suite's concern; this test covers gating, extraction and envelope
 * shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive"})
@Import({GatewayAuthTestConfig.class, RetentionCaptureTest.TestCryptoAndPublisher.class})
@DisplayName("Retention capture semantics (ADR-0014 R2)")
class RetentionCaptureTest {

    /** Invertible stand-in cipher: base64 plaintext in the ciphertext slot. */
    static final class FakeCrypto implements KeyEncryptionProvider {
        @Override
        public EncryptedSecret encrypt(byte[] plaintext, UUID tenantId, UUID credentialId) {
            return new EncryptedSecret(Base64.getEncoder().encode(plaintext), new byte[]{1}, "v1");
        }

        @Override
        public byte[] decrypt(EncryptedSecret secret, UUID tenantId, UUID credentialId) {
            return Base64.getDecoder().decode(secret.ciphertext());
        }

        @Override
        public String activeKeyVersion() {
            return "v1";
        }

        @Override
        public EncryptedSecret reEncrypt(EncryptedSecret secret, UUID tenantId, UUID credentialId) {
            return secret;
        }
    }

    static final class FakeRetentionPublisher implements RetentionPublisher {
        final List<RetentionEnvelope> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(RetentionEnvelope envelope) {
            published.add(envelope);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestCryptoAndPublisher {
        @Bean
        @Primary
        RetentionPublisher fakeRetentionPublisher() {
            return new FakeRetentionPublisher();
        }

        @Bean
        @Primary
        KeyEncryptionProvider fakeKeyEncryptionProvider() {
            return new FakeCrypto();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private RetentionSidecar sidecar;

    @Autowired
    private InMemoryRouteSnapshotProvider snapshotProvider;

    @Autowired
    private FakeRetentionPublisher publisher;

    private static final String CHAT_BODY = """
            {"model":"demo-model","messages":[
              {"role":"system","content":"secret system prompt"},
              {"role":"user","content":"remember this phrase"},
              {"role":"assistant","content":"ok"},
              {"role":"user","content":[{"type":"text","text":"and this one"}]}
            ]}""";

    @BeforeEach
    void resetState() {
        publisher.published.clear();
        installRetention(false);
    }

    @AfterEach
    void cleanState() {
        publisher.published.clear();
        installRetention(false);
    }

    private void installRetention(boolean enabled) {
        installRetention(enabled, RetentionConfig.DEFAULT_MAX_CONTENT_BYTES);
    }

    private void installRetention(boolean enabled, int maxContentBytes) {
        RetentionConfig config = new RetentionConfig(enabled, RetentionConfig.USER_TEXT_ONLY, "v1", 1, maxContentBytes);
        snapshotProvider.install(GatewayTestKeys.snapshotWithRetention("http://127.0.0.1:1", Map.of(),
                Map.of(GatewayTestKeys.TENANT_ID, config), GatewayTestKeys.DEFAULT_KEY));
    }

    private AuthContext ctx() {
        RouteSnapshot snapshot = snapshotProvider.current();
        RouteSnapshot.KeyRecord key = snapshot.key(GatewayTestKeys.DEFAULT_KEY.publicKeyId());
        return new AuthContext(key, snapshot.binding(key.keyId()), snapshot.models(key.keyId()), snapshot);
    }

    private void captureChat() {
        sidecar.capture("/v1/chat/completions", CHAT_BODY.getBytes(StandardCharsets.UTF_8), ctx(), "req-1");
        sidecar.flushNow();
    }

    @Test
    @DisplayName("enabled retention captures one envelope with exactly the user text")
    void capturesWhenEnabled() {
        installRetention(true);
        captureChat();

        assertThat(publisher.published).hasSize(1);
        RetentionEnvelope envelope = publisher.published.get(0);
        assertThat(envelope.tenantId()).isEqualTo(GatewayTestKeys.TENANT_ID);
        assertThat(envelope.userId()).isEqualTo(GatewayTestKeys.DEFAULT_KEY.userId());
        assertThat(envelope.virtualKeyId()).isEqualTo(GatewayTestKeys.DEFAULT_KEY.keyId());
        assertThat(envelope.wireProtocol()).isEqualTo("OPENAI_CHAT");
        assertThat(envelope.keyVersion()).isEqualTo("v1");

        byte[] plain = new FakeCrypto().decrypt(
                new EncryptedSecret(envelope.ciphertext(), envelope.nonce(), envelope.keyVersion()),
                envelope.tenantId(), UUID.randomUUID());
        String text = new String(plain, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("remember this phrase\n---\nand this one");
        assertThat(envelope.textCharCount()).isEqualTo(text.length());
        assertThat(text).doesNotContain("secret system prompt");
        assertThat(sidecar.droppedCount()).isZero();
    }

    @Test
    @DisplayName("disabled retention never captures")
    void disabledCapturesNothing() {
        captureChat();
        assertThat(publisher.published).isEmpty();
        assertThat(sidecar.droppedCount()).isZero();
    }

    @Test
    @DisplayName("unknown protocols and empty user text are skipped")
    void skipsIrrelevantRequests() {
        installRetention(true);
        sidecar.capture("/v1/chat/completions",
                "{\"model\":\"m\",\"messages\":[{\"role\":\"assistant\",\"content\":\"x\"}]}"
                        .getBytes(StandardCharsets.UTF_8),
                ctx(), "req-2");
        sidecar.capture("/some/other/path", CHAT_BODY.getBytes(StandardCharsets.UTF_8), ctx(), "req-3");
        sidecar.flushNow();
        assertThat(publisher.published).isEmpty();
        assertThat(sidecar.droppedCount()).isZero();
    }

    @Test
    @DisplayName("content beyond the tenant cap truncates on a UTF-8 boundary, flagged and counted (#367)")
    void truncatesAtTenantCap() {
        installRetention(true, 1024);
        String longText = "user-".repeat(700); // 3500 ASCII chars, above the 1024-byte cap
        String body = "{\"messages\":[{\"role\":\"system\",\"content\":\"secret system prompt\"},"
                + "{\"role\":\"user\",\"content\":\"" + longText + "\"}]}";
        sidecar.capture("/v1/chat/completions", body.getBytes(StandardCharsets.UTF_8), ctx(), "req-cap-1");
        sidecar.flushNow();

        assertThat(publisher.published).hasSize(1);
        RetentionEnvelope envelope = publisher.published.get(0);
        assertThat(envelope.truncated()).isTrue();
        assertThat(sidecar.truncatedCount()).isEqualTo(1);

        byte[] plain = new FakeCrypto().decrypt(
                new EncryptedSecret(envelope.ciphertext(), envelope.nonce(), envelope.keyVersion()),
                envelope.tenantId(), UUID.randomUUID());
        String text = new String(plain, StandardCharsets.UTF_8);
        assertThat(plain.length).isEqualTo(1024);
        assertThat(text).startsWith("user-user-");
        assertThat(envelope.textCharCount()).isEqualTo(text.length());
        assertThat(text).doesNotContain("secret system prompt");
        assertThat(sidecar.droppedCount()).isZero();
    }

    @Test
    @DisplayName("textCharCount counts characters of the captured text, not UTF-8 bytes (#518)")
    void textCharCountCountsCharactersNotBytes() {
        installRetention(true, 1024);
        String cjk = "留痕字符数验证".repeat(60); // 420 chars / 1260 bytes, above the 1024-byte cap
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + cjk + "\"}]}";
        sidecar.capture("/v1/chat/completions", body.getBytes(StandardCharsets.UTF_8), ctx(), "req-chars");
        sidecar.flushNow();

        assertThat(publisher.published).hasSize(1);
        RetentionEnvelope envelope = publisher.published.get(0);
        assertThat(envelope.truncated()).isTrue();
        byte[] plain = new FakeCrypto().decrypt(
                new EncryptedSecret(envelope.ciphertext(), envelope.nonce(), envelope.keyVersion()),
                envelope.tenantId(), UUID.randomUUID());
        String text = new String(plain, StandardCharsets.UTF_8);
        // The 1024-byte cap backs off to the previous 3-byte character boundary: 1023
        // bytes / 341 chars.
        assertThat(plain.length).isEqualTo(1023);
        assertThat(text.length()).isEqualTo(341);
        assertThat(envelope.textCharCount()).isEqualTo(341).isNotEqualTo(plain.length);
        assertThat(cjk).startsWith(text);
    }

    // ------------------------------------------------------------------
    // Output side (ADR-0014 增补, 2026-09-15)
    // ------------------------------------------------------------------

    private static final String CHAT_RESPONSE = """
            {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant",
              "content":"the model reply text"},"finish_reason":"stop"}]}""";

    private static final String CHAT_SSE_RESPONSE = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"the model \"}}]}\n\n"
            + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"reply text\"}}]}\n\n"
            + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n";

    private static String decryptText(RetentionEnvelope envelope) {
        byte[] plain = new FakeCrypto().decrypt(
                new EncryptedSecret(envelope.ciphertext(), envelope.nonce(), envelope.keyVersion()),
                envelope.tenantId(), UUID.randomUUID());
        return new String(plain, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("model output is captured as a separate OUTPUT envelope (non-streaming)")
    void capturesModelOutputNonStreaming() {
        installRetention(true);
        sidecar.captureOutput("/v1/chat/completions", CHAT_RESPONSE.getBytes(StandardCharsets.UTF_8), false, ctx(),
                "req-out-1", false);
        sidecar.flushNow();

        assertThat(publisher.published).hasSize(1);
        RetentionEnvelope envelope = publisher.published.get(0);
        assertThat(envelope.direction()).isEqualTo(RetentionDirection.OUTPUT);
        assertThat(envelope.gatewayRequestId()).isEqualTo("req-out-1");
        String text = decryptText(envelope);
        assertThat(text).isEqualTo("the model reply text");
        assertThat(envelope.textCharCount()).isEqualTo(text.length());
        assertThat(envelope.truncated()).isFalse();
        assertThat(sidecar.droppedCount()).isZero();
    }

    @Test
    @DisplayName("streaming model output concatenates SSE text deltas")
    void capturesModelOutputFromSse() {
        installRetention(true);
        sidecar.captureOutput("/v1/chat/completions", CHAT_SSE_RESPONSE.getBytes(StandardCharsets.UTF_8), true, ctx(),
                "req-out-2", false);
        sidecar.flushNow();

        assertThat(publisher.published).hasSize(1);
        RetentionEnvelope envelope = publisher.published.get(0);
        assertThat(envelope.direction()).isEqualTo(RetentionDirection.OUTPUT);
        assertThat(decryptText(envelope)).isEqualTo("the model reply text");
    }

    @Test
    @DisplayName("a reply bounded by the proxy buffer is flagged truncated on the OUTPUT envelope")
    void outputOverflowFlagsTruncated() {
        installRetention(true);
        long truncatedBefore = sidecar.truncatedCount();
        sidecar.captureOutput("/v1/chat/completions", CHAT_RESPONSE.getBytes(StandardCharsets.UTF_8), false, ctx(),
                "req-out-3", true);
        sidecar.flushNow();

        assertThat(publisher.published).hasSize(1);
        RetentionEnvelope envelope = publisher.published.get(0);
        assertThat(envelope.truncated()).isTrue();
        // The sidecar's counter is instance-scoped and monotonic across tests.
        assertThat(sidecar.truncatedCount()).isEqualTo(truncatedBefore + 1);
    }

    @Test
    @DisplayName("output extraction understands Anthropic and Responses shapes")
    void outputExtractionAcrossProtocols() {
        RetentionTextExtractor extractor = new RetentionTextExtractor();
        String anthropic = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"anthropic reply\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"x\",\"input\":{}}]}";
        assertThat(extractor.extractOutput(RetentionTextExtractor.Protocol.ANTHROPIC_MESSAGES,
                anthropic.getBytes(StandardCharsets.UTF_8), false)).isEqualTo("anthropic reply");

        String responses = "{\"id\":\"resp_1\",\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"responses reply\"}]}]}";
        assertThat(extractor.extractOutput(RetentionTextExtractor.Protocol.OPENAI_RESPONSES,
                responses.getBytes(StandardCharsets.UTF_8), false)).isEqualTo("responses reply");

        String anthropicSse = "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hel\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        assertThat(extractor.extractOutput(RetentionTextExtractor.Protocol.ANTHROPIC_MESSAGES,
                anthropicSse.getBytes(StandardCharsets.UTF_8), true)).isEqualTo("hello");

        // A truncated tail line must not throw and must not lose earlier deltas.
        String truncatedSse = CHAT_SSE_RESPONSE + "data: {\"choices\":[{\"delta\":{\"content\":\"par";
        assertThat(extractor.extractOutput(RetentionTextExtractor.Protocol.OPENAI_CHAT,
                truncatedSse.getBytes(StandardCharsets.UTF_8), true)).isEqualTo("the model reply text");
    }
}
