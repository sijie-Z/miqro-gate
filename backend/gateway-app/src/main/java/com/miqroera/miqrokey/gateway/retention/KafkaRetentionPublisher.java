package com.miqroera.miqrokey.gateway.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miqroera.miqrokey.domain.model.RetentionEnvelope;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ADR-0014 R3 publisher: ships retention envelopes over the standard Kafka
 * protocol to the {@code content-retention} topic (default).
 *
 * <p>
 * Partition affinity: the record key is {@code SHA-256(tenant/user)} — the same
 * user's envelopes always land on the same partition, so per-user order is
 * preserved and platform consumers can scale by partition while still grouping
 * per user. The envelope is JSON; user text only ever leaves this process as
 * the base64 AES ciphertext carried by the envelope.
 *
 * <p>
 * Best effort: sends are asynchronous with a counted, throttled failure log —
 * the retention pipeline never blocks or fails the request path (same posture
 * as the sidecar). Duplicates are tolerated by design (consumers key on
 * {@code eventId}).
 */
public final class KafkaRetentionPublisher implements RetentionPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaRetentionPublisher.class);
    private static final long FAIL_LOG_THROTTLE = 100;
    private static final ObjectMapper SHARED = new ObjectMapper();

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final AtomicLong failed = new AtomicLong();

    public KafkaRetentionPublisher(String bootstrapServers, String topic, String clientId) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("miqrokey.retention.kafka.bootstrap-servers must be set");
        }
        this.topic = topic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        // Reliability defaults from the client (acks=all + idempotence) stay;
        // envelopes are replay-safe so rare duplicates need no extra machinery.
        if (clientId != null && !clientId.isBlank()) {
            props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        }
        this.producer = new KafkaProducer<>(props);
    }

    @Override
    public void publish(RetentionEnvelope envelope) {
        try {
            byte[] payload = SHARED.writeValueAsBytes(payload(envelope));
            producer.send(new ProducerRecord<>(topic, partitionKey(envelope.tenantId(), envelope.userId()), payload),
                    (metadata, error) -> {
                        if (error != null) {
                            countFail("send failed for envelope " + envelope.eventId());
                        }
                    });
        } catch (Exception e) {
            countFail("publish failed: " + e.getClass().getSimpleName());
        }
    }

    /** JSON payload — every field explicit; ciphertext/nonce ride as base64. */
    static ObjectNode payload(RetentionEnvelope envelope) {
        ObjectNode node = SHARED.createObjectNode();
        node.put("eventId", envelope.eventId().toString());
        node.put("tenantId", envelope.tenantId().toString());
        node.put("userId", envelope.userId().toString());
        node.put("virtualKeyId", envelope.virtualKeyId().toString());
        node.put("wireProtocol", envelope.wireProtocol());
        node.put("gatewayRequestId", envelope.gatewayRequestId());
        node.put("occurredAt", envelope.occurredAt().toString());
        node.put("keyVersion", envelope.keyVersion());
        node.put("textCharCount", envelope.textCharCount());
        node.put("truncated", envelope.truncated());
        node.put("ciphertext", envelope.ciphertext());
        node.put("nonce", envelope.nonce());
        return node;
    }

    /** Stable per-user partition key: SHA-256 hex over {@code tenant/user}. */
    static String partitionKey(UUID tenantId, UUID userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((tenantId + "/" + userId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Dropped/undeliverable sends since start (observability/tests). */
    public long failedCount() {
        return failed.get();
    }

    private void countFail(String detail) {
        long count = failed.incrementAndGet();
        if (count % FAIL_LOG_THROTTLE == 1) {
            log.warn("retention kafka {} - total {}", detail, count);
        }
    }

    @Override
    public void close() {
        // flush() blocks only until buffered records are delivered (bounded by
        // delivery.timeout.ms, default 120s) — never call from a hot path.
        producer.flush();
        producer.close(Duration.ofSeconds(5));
    }
}
