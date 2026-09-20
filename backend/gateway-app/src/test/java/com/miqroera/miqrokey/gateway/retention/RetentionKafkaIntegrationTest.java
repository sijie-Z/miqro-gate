package com.miqroera.miqrokey.gateway.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.RetentionConfig;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.InMemoryRouteSnapshotProvider;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0014 R3 end-to-end against a real broker (Redpanda container): with
 * {@code miqrokey.retention.kafka.bootstrap-servers} configured, envelopes
 * captured by the sidecar leave the gateway as JSON records on the
 * {@code content-retention} topic, keyed by the stable user hash (partition
 * affinity), with user text present only as base64 ciphertext.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.retention.kafka.topic=content-retention-it"})
@Import({GatewayAuthTestConfig.class, RetentionKafkaIntegrationTest.TestCrypto.class})
@Tag("integration")
@DisplayName("Retention envelope shipping over Kafka (ADR-0014 R3)")
class RetentionKafkaIntegrationTest {

    /** Invertible stand-in cipher, same convention as the R2 capture test. */
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestCrypto {
        @Bean
        @Primary
        KeyEncryptionProvider fakeKeyEncryptionProvider() {
            return new FakeCrypto();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Integration-only broker image (not scanned by the Security gate — the
    // deployment compose never runs it). Pin a version tag once verified on CI.
    private static final RedpandaContainer REDPANDA = new RedpandaContainer(
            DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:latest"));

    static {
        REDPANDA.start();
    }

    // The broker is deliberately NOT stopped here (#985): the Spring context —
    // and with it the Kafka retention publisher — closes on JVM shutdown, i.e.
    // AFTER this method runs. Verified the hard way: a @AfterAll that closed the
    // context first made Spring's own afterTestClass fail with "The
    // ApplicationContext loaded for ...". The publisher flushes buffered records
    // from close(), and with the broker already gone that flush waits out
    // delivery.timeout.ms (120s default) — past surefire's 30s exit budget, which
    // is why a fully green suite still exited non-zero. So the broker has to
    // outlive the context; Testcontainers reaps it through Ryuk once the JVM is
    // gone. What production should wait for on shutdown is a separate decision.

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.retention.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        registry.add("miqrokey.gateway.upstream.url", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private RetentionSidecar sidecar;

    @Autowired
    private InMemoryRouteSnapshotProvider snapshotProvider;

    private static final String CHAT_BODY = """
            {"model":"demo-model","messages":[
              {"role":"system","content":"secret system prompt"},
              {"role":"user","content":"remember this phrase"},
              {"role":"assistant","content":"ok"},
              {"role":"user","content":"and this one"}
            ]}""";

    private final AtomicBoolean retentionInstalled = new AtomicBoolean();

    @BeforeEach
    void installRetention() {
        if (retentionInstalled.compareAndSet(false, true)) {
            RetentionConfig config = new RetentionConfig(true, RetentionConfig.USER_TEXT_ONLY, "v1", 1);
            snapshotProvider.install(GatewayTestKeys.snapshotWithRetention("http://127.0.0.1:1", Map.of(),
                    Map.of(GatewayTestKeys.TENANT_ID, config), GatewayTestKeys.DEFAULT_KEY));
        }
    }

    @AfterEach
    void drainSidecar() {
        sidecar.flushNow();
    }

    private AuthContext ctx() {
        RouteSnapshot snapshot = snapshotProvider.current();
        RouteSnapshot.KeyRecord key = snapshot.key(GatewayTestKeys.DEFAULT_KEY.publicKeyId());
        return new AuthContext(key, snapshot.binding(key.keyId(), GatewayTestKeys.PROJECT_TAG),
                snapshot.models(key.keyId()), snapshot);
    }

    private List<ConsumerRecord<String, String>> consume(int expected, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "retention-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("content-retention-it"));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
                batch.forEach(records::add);
            }
        }
        return records;
    }

    @Test
    @DisplayName("captured envelopes land on the topic keyed by user hash, ciphertext only")
    void shipsEnvelopesOverKafka() throws Exception {
        sidecar.capture("/v1/chat/completions", CHAT_BODY.getBytes(StandardCharsets.UTF_8), ctx(), "req-k-1");
        sidecar.capture("/v1/chat/completions", CHAT_BODY.getBytes(StandardCharsets.UTF_8), ctx(), "req-k-2");
        sidecar.flushNow();

        List<ConsumerRecord<String, String>> records = consume(2, Duration.ofSeconds(20));
        assertThat(records).hasSize(2);

        UUID tenant = GatewayTestKeys.TENANT_ID;
        UUID user = GatewayTestKeys.DEFAULT_KEY.userId();
        String expectedKey = KafkaRetentionPublisher.partitionKey(tenant, user);

        for (ConsumerRecord<String, String> record : records) {
            assertThat(record.key()).isEqualTo(expectedKey);
            JsonNode json = MAPPER.readTree(record.value());
            assertThat(json.get("tenantId").asText()).isEqualTo(tenant.toString());
            assertThat(json.get("userId").asText()).isEqualTo(user.toString());
            assertThat(json.get("virtualKeyId").asText()).isEqualTo(GatewayTestKeys.DEFAULT_KEY.keyId().toString());
            assertThat(json.get("wireProtocol").asText()).isEqualTo("OPENAI_CHAT");
            assertThat(json.get("gatewayRequestId").asText()).isIn("req-k-1", "req-k-2");
            assertThat(json.get("keyVersion").asText()).isEqualTo("v1");
            assertThat(json.get("textCharCount").asInt()).isGreaterThan(0);
            // plaintext must never appear on the wire — only base64 ciphertext
            String value = record.value();
            assertThat(value).doesNotContain("remember this phrase").doesNotContain("secret system prompt");
            byte[] plain = new FakeCrypto().decrypt(
                    new EncryptedSecret(Base64.getDecoder().decode(json.get("ciphertext").asText()),
                            Base64.getDecoder().decode(json.get("nonce").asText()), json.get("keyVersion").asText()),
                    tenant, UUID.randomUUID());
            assertThat(new String(plain, StandardCharsets.UTF_8)).contains("remember this phrase")
                    .contains("and this one").doesNotContain("secret system prompt");
        }

        // partition affinity: both envelopes for this user share one partition
        assertThat(records.stream().map(ConsumerRecord::partition).distinct()).hasSize(1);
        assertThat(sidecar.droppedCount()).isZero();
    }
}
