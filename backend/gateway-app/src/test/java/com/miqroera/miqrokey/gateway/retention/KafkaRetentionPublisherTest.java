package com.miqroera.miqrokey.gateway.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.miqroera.miqrokey.domain.model.RetentionEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Kafka retention publisher payload + partition key (ADR-0014 R3)")
class KafkaRetentionPublisherTest {

    private static RetentionEnvelope envelope() {
        return new RetentionEnvelope(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"), "OPENAI_CHAT", "req-1",
                Instant.parse("2026-09-06T00:00:00Z"), "v1", "user text".getBytes(StandardCharsets.UTF_8),
                new byte[]{1, 2, 3}, 9);
    }

    @Test
    @DisplayName("payload carries every envelope field with base64 ciphertext")
    void payloadShape() throws Exception {
        JsonNode node = KafkaRetentionPublisher.payload(envelope());
        assertThat(node.get("eventId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(node.get("tenantId").asText()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(node.get("userId").asText()).isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(node.get("virtualKeyId").asText()).isEqualTo("44444444-4444-4444-4444-444444444444");
        assertThat(node.get("wireProtocol").asText()).isEqualTo("OPENAI_CHAT");
        assertThat(node.get("gatewayRequestId").asText()).isEqualTo("req-1");
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-09-06T00:00:00Z");
        assertThat(node.get("keyVersion").asText()).isEqualTo("v1");
        assertThat(node.get("textCharCount").asInt()).isEqualTo(9);
        // ciphertext stays opaque base64 — plaintext must never be visible
        assertThat(node.get("ciphertext").asText())
                .isEqualTo(Base64.getEncoder().encodeToString("user text".getBytes(StandardCharsets.UTF_8)));
        assertThat(node.get("ciphertext").asText()).doesNotContain("user text");
        assertThat(node.get("nonce").asText()).isEqualTo(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        // the JSON round-trips without surprises: the field set is exactly the
        // envelope's
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("eventId", "tenantId", "userId", "virtualKeyId", "wireProtocol",
                "gatewayRequestId", "occurredAt", "keyVersion", "textCharCount", "truncated", "ciphertext", "nonce");
    }

    @Test
    @DisplayName("partition key is a stable 64-hex per user and differs across users")
    void partitionKeyShape() {
        UUID tenant = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID userA = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID userB = UUID.fromString("55555555-5555-5555-5555-555555555555");
        String keyA = KafkaRetentionPublisher.partitionKey(tenant, userA);
        String keyB = KafkaRetentionPublisher.partitionKey(tenant, userB);
        assertThat(keyA).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(keyA).isEqualTo(KafkaRetentionPublisher.partitionKey(tenant, userA));
        assertThat(keyB).isNotEqualTo(keyA);
        // different tenants never collide on the same key space either
        assertThat(KafkaRetentionPublisher.partitionKey(UUID.randomUUID(), userA)).isNotEqualTo(keyA);
    }
}
