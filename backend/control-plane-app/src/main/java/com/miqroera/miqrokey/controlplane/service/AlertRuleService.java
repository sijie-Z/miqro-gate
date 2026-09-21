package com.miqroera.miqrokey.controlplane.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final AuditService auditService;

    public AlertRuleService(NamedParameterJdbcTemplate jdbc, ProjectRepository projectRepository,
            QuotaRuleRepository quotaRuleRepository, AuditService auditService) {
        this.jdbc = jdbc;
        this.projectRepository = projectRepository;
        this.quotaRuleRepository = quotaRuleRepository;
        this.auditService = auditService;
    }

    public AlertRule create(UUID tenantId, String name, String type, BigDecimal threshold, int dedupeMinutes,
            UUID webhookEndpointId, String scopeJson, AuditContext context) {
        validateName(name);
        validateThreshold(threshold);
        validateDedupe(dedupeMinutes);
        validateType(type);
        validateScope(tenantId, type, scopeJson);
        validateWebhookEndpoint(tenantId, webhookEndpointId);
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
                        .addValue("threshold", threshold).addValue("dedupeMinutes", dedupeMinutes)
                        .addValue("webhookEndpointId", webhookEndpointId));
        auditService.record(tenantId, context.actorId(), "ALERT_RULE_CREATE", "ALERT_RULE", id,
                AuditSummaries.summary(context, "name", AuditSummaries.sanitize(name), "type", type),
                context.requestId());
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

    @Transactional
    public AlertRule update(UUID tenantId, UUID ruleId, String name, BigDecimal threshold, Integer dedupeMinutes,
            Boolean enabled, UUID webhookEndpointId, String scopeJson, AuditContext context) {
        if (name != null) {
            validateName(name);
        }
        if (threshold != null) {
            validateThreshold(threshold);
        }
        if (dedupeMinutes != null) {
            validateDedupe(dedupeMinutes);
        }
        AlertRule existing = get(tenantId, ruleId);
        String newScope = scopeJson != null ? scopeJson : existing.scopeJson();
        validateScope(tenantId, existing.type(), newScope);
        // Only a newly supplied endpoint is validated: null means "keep the stored
        // one", and a rule created before V74 may still carry a reference this
        // check would now reject — renaming such a rule must not fail.
        validateWebhookEndpoint(tenantId, webhookEndpointId);
        // #475 sibling: compare-and-set on the version read above. The snapshot is
        // re-written field by field (absent fields keep their stored value), so
        // without the version predicate a concurrent commit between the read and
        // the write is silently reverted — the other admin's committed field is
        // overwritten with the value this request happened to read (lost update),
        // and the caller still gets a 200.
        int rows = jdbc.update("""
                UPDATE alert_rules
                SET name = :name, threshold = :threshold, dedupe_minutes = :dedupeMinutes, enabled = :enabled,
                    webhook_endpoint_id = :webhookEndpointId, scope_json = :scopeJson::jsonb,
                    version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """,
                new MapSqlParameterSource("name", name != null ? name : existing.name())
                        .addValue("threshold", threshold != null ? threshold : existing.threshold())
                        .addValue("dedupeMinutes", dedupeMinutes != null ? dedupeMinutes : existing.dedupeMinutes())
                        .addValue("enabled", enabled != null ? enabled : existing.enabled())
                        .addValue("webhookEndpointId",
                                webhookEndpointId != null ? webhookEndpointId : existing.webhookEndpointId())
                        .addValue("scopeJson", newScope).addValue("id", ruleId).addValue("tenantId", tenantId)
                        .addValue("expectedVersion", existing.version()));
        if (rows != 1) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "Optimistic lock failure: alert rule " + ruleId);
        }
        AlertRule updated = get(tenantId, ruleId);
        auditService.record(tenantId, context.actorId(), "ALERT_RULE_UPDATE", "ALERT_RULE", ruleId, AuditSummaries
                .summary(context, "name", AuditSummaries.sanitize(updated.name()), "type", updated.type()),
                context.requestId());
        return updated;
    }

    public void delete(UUID tenantId, UUID ruleId, AuditContext context) {
        AlertRule existing = get(tenantId, ruleId);
        jdbc.update("DELETE FROM alert_rules WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", ruleId).addValue("tenantId", tenantId));
        auditService.record(
                tenantId, context.actorId(), "ALERT_RULE_DELETE", "ALERT_RULE", ruleId, AuditSummaries.summary(context,
                        "name", AuditSummaries.sanitize(existing.name()), "type", existing.type()),
                context.requestId());
    }

    /**
     * Rule types accepted by the API; mirrors the {@code alert_rules_type_check}
     * constraint.
     */
    private static final List<String> RULE_TYPES = List.of("USAGE_MISSING_RATE", "UPSTREAM_ERROR_RATE",
            "BALANCE_UNAVAILABLE", "USAGE_SURGE", "BUDGET_THRESHOLD", "QUOTA_THRESHOLD", "MODEL_APPROVAL_SUBMITTED",
            "MODEL_APPROVAL_APPROVED", "MODEL_APPROVAL_REJECTED", "ADMIN_API_KEY_EXPIRING", "CONSUMER_KEY_EXPIRING",
            "USAGE_QUEUE_SATURATION", "UPSTREAM_RATE_LIMITED", "KEY_REQUEST_RATE");

    private static void validateType(String type) {
        // #1252: `type` is optional in the submitted baseline, so "absent" reaches
        // here as null — and `List.of(...)` throws on contains(null) instead of
        // answering false. A missing type is a client error, not an internal one.
        if (type == null || !RULE_TYPES.contains(type)) {
            // The message lists the list itself: it used to drift (the two key-expiry
            // types were accepted but undocumented in the error), so an operator was
            // told their valid input was invalid.
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALERT_TYPE_INVALID",
                    "type must be one of " + String.join(", ", RULE_TYPES));
        }
    }

    /**
     * An optional {@code webhookEndpointId} must name an endpoint of this rule's own
     * tenant (#1335).
     *
     * <p>
     * The column's foreign key used to be single-column, so any endpoint id was
     * accepted and a rule could deliver into (and pin a delete guard onto) another
     * tenant's endpoint. Rejected here as a client error with a readable code
     * instead of letting the composite foreign key surface as a 409/500, and here
     * rather than on the console DTO because the machine-key surface
     * ({@code /api/v1/admin-api/alert-rules}) reaches the same INSERT with no DTO
     * constraints of its own (#1021). The migration's FK remains the backstop.
     * </p>
     */
    private void validateWebhookEndpoint(UUID tenantId, UUID webhookEndpointId) {
        if (webhookEndpointId == null) {
            return;
        }
        Integer owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_endpoints WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", webhookEndpointId).addValue("tenantId", tenantId), Integer.class);
        if (owned == null || owned == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_ENDPOINT_INVALID",
                    "webhookEndpointId 必须指向本租户已存在的 Webhook 端点。");
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

    // Shape bounds live here, not only on the console DTOs (#1021): the machine-key
    // surface (/api/v1/admin-api/alert-rules) reaches the same INSERT without any
    // DTO
    // constraint of its own, so a blank name or an out-of-range threshold used to
    // be
    // written (or surfaced as a 409 from the column) instead of being rejected.
    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALERT_NAME_INVALID", "name 不能为空白，且长度不超过 200 个字符。");
        }
    }

    private static void validateThreshold(BigDecimal threshold) {
        boolean fitsColumn = threshold != null && threshold.scale() <= 6
                && threshold.precision() - threshold.scale() <= 6;
        if (!fitsColumn) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALERT_THRESHOLD_INVALID",
                    "threshold 必填，整数部分不超过 6 位、小数不超过 6 位。");
        }
    }

    private static void validateDedupe(int dedupeMinutes) {
        if (dedupeMinutes < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALERT_DEDUPE_INVALID", "dedupeMinutes 必须不小于 1。");
        }
    }
}
