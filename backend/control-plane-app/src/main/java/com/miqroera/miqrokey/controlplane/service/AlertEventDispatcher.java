package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.WebhookEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared alert-event delivery (G4.5 machinery extracted from
 * {@link AlertEvaluator}): signing, delivery attempts with exponential backoff,
 * and the due-retry sweep. Periodically evaluated rules (threshold metrics)
 * route through {@link #deliverEvent}; event-driven notification rule types
 * (model-approval transitions, F03) fire immediately through
 * {@link #notifyForType}, which looks up the tenant's enabled rules of that
 * type and records + delivers one event per rule.
 */
@Service
public class AlertEventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(AlertEventDispatcher.class);

    static final int MAX_ATTEMPTS = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final WebhookEndpointService endpointService;
    private final ObjectMapper objectMapper;

    public AlertEventDispatcher(NamedParameterJdbcTemplate jdbc, WebhookEndpointService endpointService,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.endpointService = endpointService;
        this.objectMapper = objectMapper;
    }

    /**
     * Records one FIRED event per enabled rule of the type (dedupe key
     * {@code type:eventDedupeKey} per rule) and delivers it when the rule has an
     * enabled endpoint. Used by the model-approval workflow: value is always 1 (the
     * occurrence itself), the notification detail lives in the event's
     * {@code payload_json} and is forwarded verbatim on every delivery attempt.
     */
    public void notifyForType(UUID tenantId, String type, Map<String, Object> details) {
        List<AlertRuleService.AlertRule> rules = enabledRulesOfType(tenantId, type);
        String dedupeKey = type + ":" + details.get("approvalId");
        for (AlertRuleService.AlertRule rule : rules) {
            fireEvent(rule, dedupeKey, details);
        }
    }

    /**
     * Inserts the event row and, when the rule carries an enabled endpoint,
     * delivers it.
     */
    private void fireEvent(AlertRuleService.AlertRule rule, String dedupeKey, Map<String, Object> details) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> stored = details;
        try {
            String payloadJson = objectMapper.writeValueAsString(stored);
            int inserted = jdbc.update(
                    """
                            INSERT INTO alert_events (id, tenant_id, rule_id, dedupe_key, occurred_at, value, status,
                                payload_json, created_at)
                            VALUES (:id, :tenantId, :ruleId, :dedupeKey, :occurredAt, 1, 'FIRED', :payloadJson::jsonb, :createdAt)
                            ON CONFLICT (tenant_id, rule_id, dedupe_key) DO NOTHING
                            """,
                    new MapSqlParameterSource("id", eventId).addValue("tenantId", rule.tenantId())
                            .addValue("ruleId", rule.id()).addValue("dedupeKey", dedupeKey)
                            .addValue("occurredAt", Timestamp.from(now)).addValue("payloadJson", payloadJson)
                            .addValue("createdAt", Timestamp.from(now)));
            if (inserted == 0) {
                return; // already fired for this approval transition
            }
        } catch (Exception e) {
            LOG.warn("Alert event recording failed for rule {}", rule.id(), e);
            return;
        }
        WebhookEndpoint endpoint = endpointOf(rule);
        if (endpoint == null) {
            LOG.info("Alert rule {} fired — no webhook endpoint configured", rule.name());
            return;
        }
        deliver(eventId, rule, endpoint, BigDecimal.ONE, now, details);
    }

    /**
     * Event already inserted by the evaluator: deliver it to the rule's endpoint.
     */
    public void deliverEvent(UUID tenantId, UUID eventId, AlertRuleService.AlertRule rule, BigDecimal value,
            Instant occurredAt) {
        WebhookEndpoint endpoint = endpointOf(rule);
        if (endpoint == null) {
            LOG.info("Alert rule {} fired (value {}) — no webhook endpoint configured", rule.name(), value);
            return;
        }
        deliver(eventId, rule, endpoint, value, occurredAt, Map.of());
    }

    private WebhookEndpoint endpointOf(AlertRuleService.AlertRule rule) {
        if (rule.webhookEndpointId() == null) {
            return null;
        }
        WebhookEndpoint endpoint = endpointService.get(rule.tenantId(), rule.webhookEndpointId());
        return endpoint.enabled() ? endpoint : null;
    }

    private void deliver(UUID eventId, AlertRuleService.AlertRule rule, WebhookEndpoint endpoint, BigDecimal value,
            Instant occurredAt, Map<String, Object> details) {
        byte[] payload;
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", eventId.toString());
            envelope.put("ruleId", rule.id().toString());
            envelope.put("type", rule.type());
            envelope.put("value", value);
            envelope.put("occurredAt", occurredAt.toString());
            envelope.putAll(details);
            payload = objectMapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            LOG.warn("Alert payload serialization failed", e);
            return;
        }
        attempt(eventId, rule, endpoint, payload, 1);
    }

    private void attempt(UUID eventId, AlertRuleService.AlertRule rule, WebhookEndpoint endpoint, byte[] payload,
            int attempt) {
        Instant now = Instant.now();
        try {
            int status = endpointService.postSigned(endpoint, payload);
            recordAttempt(eventId, endpoint, attempt, status, null, null);
            LOG.info("Alert {} delivered to endpoint {} (HTTP {})", eventId, endpoint.id(), status);
        } catch (Exception e) {
            LOG.warn("Alert {} delivery attempt {} failed", eventId, attempt);
            Instant nextRetry = attempt < MAX_ATTEMPTS ? now.plusSeconds((long) Math.pow(2, attempt) * 60) : null;
            recordAttempt(eventId, endpoint, attempt, null, nextRetry, truncate(e.getMessage()));
        }
    }

    /** Retries deliveries whose backoff deadline passed. */
    public void retryDue() {
        List<Map<String, Object>> due = jdbc.query("""
                SELECT a.event_id, a.endpoint_id, a.attempt, r.id AS rule_id, r.tenant_id
                FROM webhook_delivery_attempts a
                JOIN alert_events e ON e.id = a.event_id
                JOIN alert_rules r ON r.id = e.rule_id
                WHERE a.next_retry_at IS NOT NULL AND a.next_retry_at <= now()
                """, new MapSqlParameterSource(),
                (rs, rowNum) -> Map.of("eventId", rs.getObject("event_id"), "endpointId", rs.getObject("endpoint_id"),
                        "attempt", rs.getInt("attempt"), "ruleId", rs.getObject("rule_id"), "tenantId",
                        rs.getObject("tenant_id")));
        for (Map<String, Object> row : due) {
            UUID eventId = (UUID) row.get("eventId");
            UUID endpointId = (UUID) row.get("endpointId");
            int attempt = (int) row.get("attempt") + 1;
            UUID ruleId = (UUID) row.get("ruleId");
            UUID tenantId = (UUID) row.get("tenantId");
            try {
                WebhookEndpoint endpoint = endpointService.get(tenantId, endpointId);
                AlertEvent event = event(eventId);
                AlertRuleService.AlertRule rule = ruleFor(ruleId);
                if (event == null || rule == null) {
                    continue;
                }
                Map<String, Object> details = new LinkedHashMap<>();
                if (event.payloadJson() != null) {
                    try {
                        details.putAll(objectMapper.readValue(event.payloadJson(),
                                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
                    } catch (Exception ignored) {
                        // non-object payload_json — envelope only
                    }
                }
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", eventId.toString());
                envelope.put("ruleId", ruleId.toString());
                envelope.put("type", rule.type());
                envelope.put("value", event.value());
                envelope.put("occurredAt", event.occurredAt().toString());
                envelope.putAll(details);
                byte[] payload = objectMapper.writeValueAsBytes(envelope);
                attempt(eventId, rule, endpoint, payload, attempt);
            } catch (Exception e) {
                LOG.warn("Alert retry for event {} failed", eventId);
            }
        }
    }

    private List<AlertRuleService.AlertRule> enabledRulesOfType(UUID tenantId, String type) {
        return jdbc.query("SELECT * FROM alert_rules WHERE tenant_id = :tenantId AND type = :type AND enabled = TRUE",
                new MapSqlParameterSource("tenantId", tenantId).addValue("type", type),
                (rs, rowNum) -> new AlertRuleService.AlertRule((UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("type"),
                        rs.getString("scope_json"), rs.getObject("threshold", BigDecimal.class),
                        rs.getInt("dedupe_minutes"), rs.getBoolean("enabled"),
                        (UUID) rs.getObject("webhook_endpoint_id"), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null));
    }

    private void recordAttempt(UUID eventId, WebhookEndpoint endpoint, int attempt, Integer httpStatus,
            Instant nextRetryAt, String error) {
        jdbc.update("""
                INSERT INTO webhook_delivery_attempts
                    (id, tenant_id, event_id, endpoint_id, attempt, http_status, next_retry_at, error_message,
                     created_at)
                VALUES (:id, :tenantId, :eventId, :endpointId, :attempt, :httpStatus, :nextRetryAt, :error, now())
                ON CONFLICT (event_id, endpoint_id, attempt) DO UPDATE
                    SET http_status = EXCLUDED.http_status, next_retry_at = EXCLUDED.next_retry_at,
                        error_message = EXCLUDED.error_message
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", endpoint.tenantId())
                        .addValue("eventId", eventId).addValue("endpointId", endpoint.id()).addValue("attempt", attempt)
                        .addValue("httpStatus", httpStatus)
                        .addValue("nextRetryAt", nextRetryAt != null ? Timestamp.from(nextRetryAt) : null)
                        .addValue("error", error));
    }

    private AlertRuleService.AlertRule ruleFor(UUID ruleId) {
        List<AlertRuleService.AlertRule> found = jdbc.query("SELECT * FROM alert_rules WHERE id = :id",
                new MapSqlParameterSource("id", ruleId),
                (rs, rowNum) -> new AlertRuleService.AlertRule((UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("type"),
                        rs.getString("scope_json"), rs.getObject("threshold", BigDecimal.class),
                        rs.getInt("dedupe_minutes"), rs.getBoolean("enabled"),
                        (UUID) rs.getObject("webhook_endpoint_id"), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null));
        return found.isEmpty() ? null : found.get(0);
    }

    private AlertEvent event(UUID eventId) {
        List<AlertEvent> found = jdbc.query("SELECT * FROM alert_events WHERE id = :id",
                new MapSqlParameterSource("id", eventId), EVENT_ROW_MAPPER);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static final RowMapper<AlertEvent> EVENT_ROW_MAPPER = (rs, rowNum) -> new AlertEvent(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("rule_id"),
            rs.getString("dedupe_key"), rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("value", BigDecimal.class), rs.getString("status"), rs.getString("payload_json"),
            rs.getTimestamp("created_at").toInstant());

    public record AlertEvent(UUID id, UUID tenantId, UUID ruleId, String dedupeKey, Instant occurredAt,
            BigDecimal value, String status, String payloadJson, Instant createdAt) {
    }
}
