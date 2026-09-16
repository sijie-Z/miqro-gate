package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Quota soft-landing evaluator (#684, ADR-0020): on a fixed delay, recompute
 * the EXCEEDED verdict for every ACTIVE REJECT rule (through the same
 * {@link QuotaWatermarks} the quota pages use), replace
 * {@code quota_enforcement} with the current block set, and publish a route
 * refresh when the set changed — the gateway enforces the new set within one
 * snapshot cycle and never evaluates quotas itself. Per-rule failures are
 * isolated: one broken rule keeps its previous verdict instead of clearing the
 * whole block set.
 */
@Service
public class QuotaEnforcementService {

    private static final Logger LOG = LoggerFactory.getLogger(QuotaEnforcementService.class);

    private final QuotaRuleRepository quotaRuleRepository;
    private final QuotaWatermarks watermarks;
    private final NamedParameterJdbcTemplate jdbc;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public QuotaEnforcementService(QuotaRuleRepository quotaRuleRepository, QuotaWatermarks watermarks,
            NamedParameterJdbcTemplate jdbc, RouteRefreshPublisher routeRefreshPublisher) {
        this.quotaRuleRepository = quotaRuleRepository;
        this.watermarks = watermarks;
        this.jdbc = jdbc;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    @Scheduled(fixedDelayString = "${miqrokey.quota.enforcement-interval-ms:60000}", initialDelayString = "${miqrokey.quota.enforcement-initial-delay-ms:45000}")
    @Transactional
    public void evaluate() {
        List<QuotaRule> rules = quotaRuleRepository.findAllActiveReject();
        List<BlockedRule> exceeded = new ArrayList<>();
        for (QuotaRule rule : rules) {
            try {
                QuotaWatermarks.Watermark watermark = watermarks.evaluate(rule.tenantId(), rule);
                if (watermark.exceeded()) {
                    exceeded.add(new BlockedRule(rule, watermark.to()));
                }
            } catch (RuntimeException e) {
                LOG.warn("Quota watermark failed for rule {} ({}); keeping the previous verdict", rule.id(),
                        e.getMessage());
            }
        }

        Set<UUID> before = Set.copyOf(jdbc.queryForList("SELECT rule_id FROM quota_enforcement", Map.of(), UUID.class));
        Set<UUID> after = exceeded.stream().map(blocked -> blocked.rule().id()).collect(Collectors.toUnmodifiableSet());
        if (before.equals(after)) {
            return;
        }
        jdbc.update("DELETE FROM quota_enforcement", Map.of());
        for (BlockedRule blocked : exceeded) {
            QuotaRule rule = blocked.rule();
            jdbc.update("""
                    INSERT INTO quota_enforcement (rule_id, tenant_id, scope_type, scope_id, metric, period, window_end)
                    VALUES (:ruleId, :tenantId, :scopeType, :scopeId, :metric, :period, :windowEnd)
                    """,
                    new MapSqlParameterSource("ruleId", rule.id()).addValue("tenantId", rule.tenantId())
                            .addValue("scopeType", rule.scopeType().name()).addValue("scopeId", rule.scopeId())
                            .addValue("metric", rule.metric().name()).addValue("period", rule.period().name())
                            .addValue("windowEnd", Timestamp.from(blocked.windowEnd())));
        }
        routeRefreshPublisher.publishChanged();
        LOG.info("Quota soft-landing verdict changed: {} blocking rule(s) (was {}); route refresh published",
                after.size(), before.size());
    }

    /** A rule whose current window is EXCEEDED, with that window's end. */
    private record BlockedRule(QuotaRule rule, Instant windowEnd) {
    }
}
