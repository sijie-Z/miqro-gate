package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
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
 * Alert rule management (G4.5/G8.3/quota-alerting, {@code alert_rules}
 * V12/V15/V24): metric type, threshold, dedupe window, an optional webhook
 * endpoint (null = the alert is only recorded as an event, not delivered) and a
 * JSON scope ({@code {"projectId": "…"}} for {@code BUDGET_THRESHOLD} rules,
 * {@code {"quotaRuleId": "…"}} for {@code QUOTA_THRESHOLD} rules).
 */
@Service
public class AlertRuleService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectRepository projectRepository;
    private final QuotaRuleRepository quotaRuleRepository;

    public AlertRuleService(NamedParameterJdbcTemplate jdbc, ProjectRepository projectRepository,
            QuotaRuleRepository quotaRuleRepository) {
        this.jdbc = jdbc;
        this.projectRepository = projectRepository;
        this.quotaRuleRepository = quotaRuleRepository;
    }

    public AlertRule create(UUID tenantId, String name, String type, BigDecimal threshold, int dedupeMinutes,
            UUID webhookEndpointId, String scopeJson) {
        validateType(type);
        validateScope(tenantId, type, scopeJson);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO alert_rules
                    (id, tenant_id, name, type, scope_json, threshold, dedupe_minutes, enabled,
                     webhook_endpoint_id, version)
                VALUES (:id, :tenantId, :name, :type, :scopeJson::jsonb, :threshold, :dedupeMinutes, TRUE,
                        :webhookEndpointId, 0)
                """,
                new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("name", name)
                        .addValue("type", type).addValue("scopeJson", scopeJson).addValue("threshold", threshold)
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
            Boolean enabled, UUID webhookEndpointId, String scopeJson) {
        AlertRule existing = get(tenantId, ruleId);
        String newScope = scopeJson != null ? scopeJson : existing.scopeJson();
        validateScope(tenantId, existing.type(), newScope);
        jdbc.update("""
                UPDATE alert_rules
                SET name = :name, threshold = :threshold, dedupe_minutes = :dedupeMinutes, enabled = :enabled,
                    webhook_endpoint_id = :webhookEndpointId, scope_json = :scopeJson::jsonb,
                    version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId
                """,
                new MapSqlParameterSource("name", name != null ? name : existing.name())
                        .addValue("threshold", threshold != null ? threshold : existing.threshold())
                        .addValue("dedupeMinutes", dedupeMinutes != null ? dedupeMinutes : existing.dedupeMinutes())
                        .addValue("enabled", enabled != null ? enabled : existing.enabled())
                        .addValue("webhookEndpointId",
                                webhookEndpointId != null ? webhookEndpointId : existing.webhookEndpointId())
                        .addValue("scopeJson", newScope).addValue("id", ruleId).addValue("tenantId", tenantId));
        return get(tenantId, ruleId);
    }

    public void delete(UUID tenantId, UUID ruleId) {
        get(tenantId, ruleId);
        jdbc.update("DELETE FROM alert_rules WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", ruleId).addValue("tenantId", tenantId));
    }

    private static void validateType(String type) {
        if (!List.of("USAGE_MISSING_RATE", "UPSTREAM_ERROR_RATE", "BALANCE_UNAVAILABLE", "USAGE_SURGE",
                "BUDGET_THRESHOLD", "QUOTA_THRESHOLD", "MODEL_APPROVAL_SUBMITTED", "MODEL_APPROVAL_APPROVED",
                "MODEL_APPROVAL_REJECTED").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALERT_TYPE_INVALID",
                    "type must be one of USAGE_MISSING_RATE, UPSTREAM_ERROR_RATE, BALANCE_UNAVAILABLE, "
                            + "USAGE_SURGE, BUDGET_THRESHOLD, QUOTA_THRESHOLD, MODEL_APPROVAL_SUBMITTED, "
                            + "MODEL_APPROVAL_APPROVED, MODEL_APPROVAL_REJECTED");
        }
    }

    /**
     * Scoped rule types must point at an existing same-tenant target:
     * BUDGET_THRESHOLD → a project; QUOTA_THRESHOLD → a quota rule.
     */
    private void validateScope(UUID tenantId, String type, String scopeJson) {
        switch (type) {
            case "BUDGET_THRESHOLD" -> requireProjectScope(tenantId, scopeJson);
            case "QUOTA_THRESHOLD" -> requireQuotaScope(tenantId, scopeJson);
            default -> {
                // unscoped metric types need no scopeJson
            }
        }
    }

    private void requireProjectScope(UUID tenantId, String scopeJson) {
        try {
            JsonNode node = JSON.readTree(scopeJson);
            String projectId = node.path("projectId").asText(null);
            if (projectId == null) {
                throw new IllegalArgumentException("missing projectId");
            }
            Project project = projectRepository.findById(UUID.fromString(projectId))
                    .orElseThrow(() -> new IllegalArgumentException("unknown project"));
            if (!project.tenantId().equals(tenantId)) {
                throw new IllegalArgumentException("cross-tenant project");
            }
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID",
                    "BUDGET_THRESHOLD 规则必须提供 scopeJson: {\"projectId\": \"<uuid>\"}，且项目需存在。");
        }
    }

    private void requireQuotaScope(UUID tenantId, String scopeJson) {
        try {
            JsonNode node = JSON.readTree(scopeJson);
            String quotaRuleId = node.path("quotaRuleId").asText(null);
            if (quotaRuleId == null) {
                throw new IllegalArgumentException("missing quotaRuleId");
            }
            UUID id = UUID.fromString(quotaRuleId);
            if (quotaRuleRepository.findById(tenantId, id).isEmpty()) {
                throw new IllegalArgumentException("unknown quota rule");
            }
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID",
                    "QUOTA_THRESHOLD 规则必须提供 scopeJson: {\"quotaRuleId\": \"<uuid>\"}，且配额规则需存在。");
        }
    }

    public record AlertRule(UUID id, UUID tenantId, String name, String type, String scopeJson, BigDecimal threshold,
            int dedupeMinutes, boolean enabled, UUID webhookEndpointId, long version, Instant createdAt,
            Instant updatedAt) {
    }

    private static final RowMapper<AlertRule> ROW_MAPPER = (rs, rowNum) -> new AlertRule((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("type"), rs.getString("scope_json"),
            rs.getObject("threshold", BigDecimal.class), rs.getInt("dedupe_minutes"), rs.getBoolean("enabled"),
            (UUID) rs.getObject("webhook_endpoint_id"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null);
}
