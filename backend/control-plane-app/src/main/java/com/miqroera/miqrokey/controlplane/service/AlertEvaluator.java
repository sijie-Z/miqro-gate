package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.WebhookEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alert evaluation and webhook delivery (G4.5, {@code alert_rules} /
 * {@code alert_events} / {@code webhook_delivery_attempts} V12). Runs on a
 * fixed-delay schedule: every enabled rule's metric is computed over a rolling
 * hour, compared to the threshold, deduplicated per (rule, hour bucket), and
 * delivered to the rule's endpoint with HMAC-SHA256 signing. Failed deliveries
 * are retried with exponential backoff up to {@value #MAX_ATTEMPTS} attempts.
 *
 * <p>
 * Metrics (all over the last rolling hour, tenant-wide):
 * <ul>
 * <li>{@code USAGE_MISSING_RATE}: share of events with
 * {@code usage_missing}.</li>
 * <li>{@code UPSTREAM_ERROR_RATE}: share of non-2xx upstream status codes.</li>
 * <li>{@code BALANCE_UNAVAILABLE}: count of UNAVAILABLE quota snapshots synced
 * in the hour (threshold is the alerting count).</li>
 * <li>{@code USAGE_SURGE}: event count ratio current hour / previous hour.</li>
 * </ul>
 * </p>
 */
@Service
public class AlertEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(AlertEvaluator.class);

    static final int MAX_ATTEMPTS = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final WebhookEndpointService endpointService;
    private final AdminBudgetService budgetService;
    private final AdminQuotaRuleService quotaRuleService;
    private final ObjectMapper objectMapper;

    public AlertEvaluator(NamedParameterJdbcTemplate jdbc, WebhookEndpointService endpointService,
            AdminBudgetService budgetService, AdminQuotaRuleService quotaRuleService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.endpointService = endpointService;
        this.budgetService = budgetService;
        this.quotaRuleService = quotaRuleService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${miqrokey.alerts.evaluation-interval-ms:300000}")
    public void evaluateAll() {
        try {
            List<AlertRuleService.AlertRule> rules = jdbc.query("SELECT * FROM alert_rules WHERE enabled = TRUE",
                    new MapSqlParameterSource(), RULE_ROW_MAPPER);
            for (AlertRuleService.AlertRule rule : rules) {
                evaluate(rule);
            }
            retryDue();
        } catch (Exception e) {
            LOG.warn("Alert evaluation cycle failed", e);
        }
    }

    private void evaluate(AlertRuleService.AlertRule rule) {
        BigDecimal value = metric(rule.type(), rule.tenantId(), rule.scopeJson());
        if (value == null || value.compareTo(rule.threshold()) < 0) {
            return;
        }
        // Scoped watermarks dedupe per reset window, not per hour: budgets are
        // monthly, quota rules reset daily/weekly/monthly. The other metrics
        // dedupe per (rule, hour) as before.
        String dedupeKey;
        switch (rule.type()) {
            case "BUDGET_THRESHOLD" -> dedupeKey = "BUDGET_THRESHOLD:" + YearMonth.now();
            case "QUOTA_THRESHOLD" ->
                dedupeKey = "QUOTA_THRESHOLD:" + quotaWindowEpoch(rule.tenantId(), rule.scopeJson());
            default -> dedupeKey = rule.type() + ":" + Instant.now().truncatedTo(ChronoUnit.HOURS);
        }
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        int inserted = jdbc.update("""
                INSERT INTO alert_events (id, tenant_id, rule_id, dedupe_key, occurred_at, value, status, created_at)
                VALUES (:id, :tenantId, :ruleId, :dedupeKey, :occurredAt, :value, 'FIRED', :createdAt)
                ON CONFLICT (tenant_id, rule_id, dedupe_key) DO NOTHING
                """,
                new MapSqlParameterSource("id", eventId).addValue("tenantId", rule.tenantId())
                        .addValue("ruleId", rule.id()).addValue("dedupeKey", dedupeKey)
                        .addValue("occurredAt", Timestamp.from(now)).addValue("value", value)
                        .addValue("createdAt", Timestamp.from(now)));
        if (inserted == 0) {
            return; // deduplicated within the window — no new event, no delivery
        }
        if (rule.webhookEndpointId() == null) {
            LOG.info("Alert rule {} fired (value {}) — no webhook endpoint configured", rule.name(), value);
            return;
        }
        deliver(eventId, rule, value, now);
    }

    /** Metric over the last rolling hour; ratio in 0..1, surge as a ratio. */
    BigDecimal metric(String type, UUID tenantId, String scopeJson) {
        return switch (type) {
            case "USAGE_MISSING_RATE" -> ratio("""
                    SELECT COUNT(*) FILTER (WHERE usage_missing)::float / NULLIF(COUNT(*), 0)
                    FROM usage_event WHERE occurred_at >= now() - interval '1 hour'
                    """);
            case "UPSTREAM_ERROR_RATE" -> ratio("""
                    SELECT COUNT(*) FILTER (WHERE upstream_status_code IS NOT NULL
                            AND upstream_status_code NOT BETWEEN 200 AND 299)::float / NULLIF(COUNT(*), 0)
                    FROM usage_event WHERE occurred_at >= now() - interval '1 hour'
                    """);
            case "BALANCE_UNAVAILABLE" -> count("""
                    SELECT COUNT(*) FROM quota_snapshots
                    WHERE source = 'UNAVAILABLE' AND synced_at >= now() - interval '1 hour'
                    """);
            case "USAGE_SURGE" -> surge();
            case "BUDGET_THRESHOLD" -> budgetWatermark(tenantId, scopeJson);
            case "QUOTA_THRESHOLD" -> quotaWatermark(tenantId, scopeJson);
            default -> null;
        };
    }

    /**
     * The quota rule's current-window watermark as a percentage (used / limit ×
     * 100); null when the scope is absent or the rule is disabled (nothing to alert
     * on).
     */
    private BigDecimal quotaWatermark(UUID tenantId, String scopeJson) {
        com.miqroera.miqrokey.controlplane.dto.QuotaRuleView view = quotaRuleView(tenantId, scopeJson);
        return view == null || view.status() != com.miqroera.miqrokey.domain.model.QuotaRuleStatus.ACTIVE
                ? null
                : view.usedPct();
    }

    /**
     * Epoch millis of the rule's current reset window (the watermark above was
     * computed for this window) — used as the dedupe scope so a rule fires once per
     * reset, not once per evaluation cycle.
     */
    private long quotaWindowEpoch(UUID tenantId, String scopeJson) {
        com.miqroera.miqrokey.controlplane.dto.QuotaRuleView view = quotaRuleView(tenantId, scopeJson);
        return view == null ? 0L : view.windowFrom().toEpochMilli();
    }

    private com.miqroera.miqrokey.controlplane.dto.QuotaRuleView quotaRuleView(UUID tenantId, String scopeJson) {
        try {
            JsonNode node = objectMapper.readTree(scopeJson);
            String quotaRuleId = node.path("quotaRuleId").asText(null);
            if (quotaRuleId == null) {
                return null;
            }
            UUID id = UUID.fromString(quotaRuleId);
            return quotaRuleService.list(tenantId).stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
        } catch (Exception e) {
            return null; // malformed scope — nothing to alert
        }
    }

    /**
     * The project's current-month budget watermark as a percentage (spent / amount
     * × 100); null when the scope is absent or the project has no budget for this
     * month (nothing to alert on).
     */
    private BigDecimal budgetWatermark(UUID tenantId, String scopeJson) {
        try {
            JsonNode node = objectMapper.readTree(scopeJson);
            String projectId = node.path("projectId").asText(null);
            if (projectId == null) {
                return null;
            }
            com.miqroera.miqrokey.controlplane.dto.BudgetView view = budgetService.view(tenantId,
                    UUID.fromString(projectId), YearMonth.now().toString());
            return "ACTIVE".equals(view.status()) ? view.spentPct() : null;
        } catch (Exception e) {
            return null; // malformed scope or no budget yet — nothing to alert
        }
    }

    private BigDecimal ratio(String sql) {
        Double value = jdbc.queryForObject(sql, new MapSqlParameterSource(), Double.class);
        if (value == null || value.isNaN()) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal count(String sql) {
        Long value = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return value != null ? BigDecimal.valueOf(value) : BigDecimal.ZERO;
    }

    private BigDecimal surge() {
        Long current = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_event WHERE occurred_at >= now() - interval '1 hour'",
                new MapSqlParameterSource(), Long.class);
        Long previous = jdbc
                .queryForObject(
                        "SELECT COUNT(*) FROM usage_event WHERE occurred_at >= now() - interval '2 hours'"
                                + " AND occurred_at < now() - interval '1 hour'",
                        new MapSqlParameterSource(), Long.class);
        if (previous == null || previous == 0) {
            return current != null && current > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(current).divide(BigDecimal.valueOf(previous), 4, RoundingMode.HALF_UP);
    }

    private void deliver(UUID eventId, AlertRuleService.AlertRule rule, BigDecimal value, Instant occurredAt) {
        WebhookEndpoint endpoint = endpointService.get(rule.tenantId(), rule.webhookEndpointId());
        if (!endpoint.enabled()) {
            return;
        }
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(Map.of("eventId", eventId.toString(), "ruleId",
                    rule.id().toString(), "type", rule.type(), "value", value, "occurredAt", occurredAt.toString()));
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

    private void retryDue() {
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
                byte[] payload = objectMapper
                        .writeValueAsBytes(Map.of("eventId", eventId.toString(), "ruleId", ruleId.toString(), "type",
                                rule.type(), "value", event.value(), "occurredAt", event.occurredAt().toString()));
                attempt(eventId, rule, endpoint, payload, attempt);
            } catch (Exception e) {
                LOG.warn("Alert retry for event {} failed", eventId);
            }
        }
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
                new MapSqlParameterSource("id", ruleId), RULE_ROW_MAPPER);
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

    public record AlertEvent(UUID id, UUID tenantId, UUID ruleId, String dedupeKey, Instant occurredAt,
            BigDecimal value, String status, String payloadJson, Instant createdAt) {
    }

    private static final RowMapper<AlertRuleService.AlertRule> RULE_ROW_MAPPER = (rs,
            rowNum) -> new AlertRuleService.AlertRule((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
                    rs.getString("name"), rs.getString("type"), rs.getString("scope_json"),
                    rs.getObject("threshold", BigDecimal.class), rs.getInt("dedupe_minutes"), rs.getBoolean("enabled"),
                    (UUID) rs.getObject("webhook_endpoint_id"), rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null);

    private static final RowMapper<AlertEvent> EVENT_ROW_MAPPER = (rs, rowNum) -> new AlertEvent(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("rule_id"),
            rs.getString("dedupe_key"), rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("value", BigDecimal.class), rs.getString("status"), rs.getString("payload_json"),
            rs.getTimestamp("created_at").toInstant());
}
