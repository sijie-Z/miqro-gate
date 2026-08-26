package com.miqroera.miqrokey.controlplane.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Alert rule management (G4.5, {@code alert_rules} V12): metric type,
 * threshold, dedupe window and an optional webhook endpoint (null = the alert
 * is only recorded as an event, not delivered).
 */
@Service
public class AlertRuleService {

    private final NamedParameterJdbcTemplate jdbc;

    public AlertRuleService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AlertRule create(UUID tenantId, String name, String type, BigDecimal threshold, int dedupeMinutes,
            UUID webhookEndpointId) {
        validateType(type);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO alert_rules
                    (id, tenant_id, name, type, threshold, dedupe_minutes, enabled, webhook_endpoint_id, version)
                VALUES (:id, :tenantId, :name, :type, :threshold, :dedupeMinutes, TRUE, :webhookEndpointId, 0)
                """,
                new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("name", name)
                        .addValue("type", type).addValue("threshold", threshold)
                        .addValue("dedupeMinutes", dedupeMinutes).addValue("webhookEndpointId", webhookEndpointId));
        return get(tenantId, id);
    }

    public List<AlertRule> list(UUID tenantId) {
        return jdbc.query("SELECT * FROM alert_rules WHERE tenant_id = :tenantId ORDER BY created_at",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    public AlertRule get(UUID tenantId, UUID ruleId) {
        List<AlertRule> found = jdbc.query("SELECT * FROM alert_rules WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", ruleId).addValue("tenantId", tenantId), ROW_MAPPER);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ALERT_RULE_NOT_FOUND", "Alert rule not found");
        }
        return found.get(0);
    }

    public AlertRule update(UUID tenantId, UUID ruleId, String name, BigDecimal threshold, Integer dedupeMinutes,
            Boolean enabled, UUID webhookEndpointId) {
        AlertRule existing = get(tenantId, ruleId);
        jdbc.update("""
                UPDATE alert_rules
                SET name = :name, threshold = :threshold, dedupe_minutes = :dedupeMinutes, enabled = :enabled,
                    webhook_endpoint_id = :webhookEndpointId, version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId
                """,
                new MapSqlParameterSource("name", name != null ? name : existing.name())
                        .addValue("threshold", threshold != null ? threshold : existing.threshold())
                        .addValue("dedupeMinutes", dedupeMinutes != null ? dedupeMinutes : existing.dedupeMinutes())
                        .addValue("enabled", enabled != null ? enabled : existing.enabled())
                        .addValue("webhookEndpointId",
                                webhookEndpointId != null ? webhookEndpointId : existing.webhookEndpointId())
                        .addValue("id", ruleId).addValue("tenantId", tenantId));
        return get(tenantId, ruleId);
    }

    public void delete(UUID tenantId, UUID ruleId) {
        get(tenantId, ruleId);
        jdbc.update("DELETE FROM alert_rules WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", ruleId).addValue("tenantId", tenantId));
    }

    private static void validateType(String type) {
        if (!List.of("USAGE_MISSING_RATE", "UPSTREAM_ERROR_RATE", "BALANCE_UNAVAILABLE", "USAGE_SURGE")
                .contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALERT_TYPE_INVALID",
                    "type must be one of USAGE_MISSING_RATE, UPSTREAM_ERROR_RATE, BALANCE_UNAVAILABLE, USAGE_SURGE");
        }
    }

    public record AlertRule(UUID id, UUID tenantId, String name, String type, BigDecimal threshold, int dedupeMinutes,
            boolean enabled, UUID webhookEndpointId, long version, Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<AlertRule> ROW_MAPPER = (rs, rowNum) -> new AlertRule((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("type"),
            rs.getObject("threshold", BigDecimal.class), rs.getInt("dedupe_minutes"), rs.getBoolean("enabled"),
            (UUID) rs.getObject("webhook_endpoint_id"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null);
}
