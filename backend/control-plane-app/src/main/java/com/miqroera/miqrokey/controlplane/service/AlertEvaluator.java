package com.miqroera.miqrokey.controlplane.service;

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
import java.util.UUID;

/**
 * Alert evaluation (G4.5, {@code alert_rules} V12): every enabled rule's metric
 * is computed over a rolling hour, compared to the threshold, deduplicated per
 * (rule, hour bucket / reset window), and delivered by
 * {@link AlertEventDispatcher} (signing, attempts, retries). Event-driven
 * notification rule types (model-approval transitions, F03) never reach this
 * evaluator's metric switch — the workflow fires them immediately through the
 * same dispatcher.
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

    private final NamedParameterJdbcTemplate jdbc;
    private final AlertEventDispatcher dispatcher;
    private final AdminBudgetService budgetService;
    private final AdminQuotaRuleService quotaRuleService;
    private final ObjectMapper objectMapper;

    public AlertEvaluator(NamedParameterJdbcTemplate jdbc, AlertEventDispatcher dispatcher,
            AdminBudgetService budgetService, AdminQuotaRuleService quotaRuleService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
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
            dispatcher.retryDue();
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
        dispatcher.deliverEvent(rule.tenantId(), eventId, rule, value, now);
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
            default -> null; // event-driven notification types fire outside the scheduler
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

    private static final RowMapper<AlertRuleService.AlertRule> RULE_ROW_MAPPER = (rs,
            rowNum) -> new AlertRuleService.AlertRule((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
                    rs.getString("name"), rs.getString("type"), rs.getString("scope_json"),
                    rs.getObject("threshold", BigDecimal.class), rs.getInt("dedupe_minutes"), rs.getBoolean("enabled"),
                    (UUID) rs.getObject("webhook_endpoint_id"), rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null);
}
