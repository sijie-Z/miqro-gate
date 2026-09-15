package com.miqroera.miqrokey.controlplane.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ingests retention envelopes (ADR-0014 §8, JSON exactly as the gateway's Kafka
 * producer emits them) into {@code retention_log}. The stream is at-least-once
 * — {@code event_id} is the idempotency key ({@code ON CONFLICT DO NOTHING}). A
 * malformed or foreign envelope is counted and skipped: it must never kill the
 * consumer loop.
 */
@Service
public class RetentionLogIngestService {

    private static final Logger log = LoggerFactory.getLogger(RetentionLogIngestService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AtomicLong malformed = new AtomicLong();

    public RetentionLogIngestService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * @return true when a row was inserted; false for duplicates, unparseable
     *         envelopes and rows rejected by constraints (FK etc.).
     */
    public boolean ingest(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            UUID eventId = uuid(node, "eventId");
            UUID tenantId = uuid(node, "tenantId");
            UUID userId = uuid(node, "userId");
            UUID virtualKeyId = uuid(node, "virtualKeyId");
            String wireProtocol = text(node, "wireProtocol");
            String gatewayRequestId = text(node, "gatewayRequestId");
            String occurredAt = text(node, "occurredAt");
            String keyVersion = text(node, "keyVersion");
            String ciphertext = text(node, "ciphertext");
            String nonce = text(node, "nonce");
            if (eventId == null || tenantId == null || userId == null || virtualKeyId == null || wireProtocol == null
                    || gatewayRequestId == null || occurredAt == null || keyVersion == null || ciphertext == null
                    || nonce == null) {
                countMalformed("missing required field");
                return false;
            }
            String direction = node.path("direction").asText("INPUT").toUpperCase(Locale.ROOT);
            if (!"INPUT".equals(direction) && !"OUTPUT".equals(direction)) {
                direction = "INPUT";
            }
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("eventId", eventId)
                    .addValue("tenantId", tenantId).addValue("userId", userId).addValue("virtualKeyId", virtualKeyId)
                    .addValue("wireProtocol", wireProtocol).addValue("direction", direction)
                    .addValue("gatewayRequestId", gatewayRequestId)
                    .addValue("occurredAt", Timestamp.from(Instant.parse(occurredAt)))
                    .addValue("keyVersion", keyVersion).addValue("ciphertext", Base64.getDecoder().decode(ciphertext))
                    .addValue("nonce", Base64.getDecoder().decode(nonce))
                    .addValue("textCharCount", node.path("textCharCount").asInt(0))
                    .addValue("truncated", node.path("truncated").asBoolean(false));
            int rows = jdbc.update("""
                    INSERT INTO retention_log (event_id, tenant_id, user_id, virtual_key_id, wire_protocol,
                        direction, gateway_request_id, occurred_at, key_version, ciphertext, nonce,
                        text_char_count, truncated)
                    VALUES (:eventId, :tenantId, :userId, :virtualKeyId, :wireProtocol,
                        :direction, :gatewayRequestId, :occurredAt, :keyVersion, :ciphertext, :nonce,
                        :textCharCount, :truncated)
                    ON CONFLICT (event_id) DO NOTHING
                    """, params);
            return rows > 0;
        } catch (Exception e) {
            countMalformed(e.getClass().getSimpleName());
            return false;
        }
    }

    /** Malformed/rejected envelope count since start (observability/tests). */
    public long malformedCount() {
        return malformed.get();
    }

    private void countMalformed(String reason) {
        long count = malformed.incrementAndGet();
        if (count % 100 == 1) {
            log.warn("retention envelope rejected ({}) - total {}", reason, count);
        }
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
