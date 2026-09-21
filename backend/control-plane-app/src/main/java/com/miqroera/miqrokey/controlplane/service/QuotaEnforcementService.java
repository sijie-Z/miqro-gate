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
 *
 * <p>
 * A verdict is sticky until its window ends (#1316). The live watermark alone
 * cannot be the last word, because it is a function of {@code usage_event} rows
 * that can disappear underneath it — a retention {@code USAGE_DELETE} of the
 * current window zeroes the reading and would otherwise resume traffic, which is
 * an unlock path ADR-0020 explicitly rejected. So a rule that is no longer
 * EXCEEDED carries its recorded block forward while
 * {@code window_end} is still in the future and the rule itself has not been
 * touched since the block was recorded. Both documented recovery paths survive:
 * rolling into a new window drops the stale verdict, and any admin write to the
 * rule (raise the limit, disable it, delete it) lifts the block immediately.
 * </p>
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
        Instant now = Instant.now();
        Map<UUID, RecordedBlock> recorded = recordedBlocks();
        List<BlockedRule> exceeded = new ArrayList<>();
        for (QuotaRule rule : rules) {
            try {
                QuotaWatermarks.Watermark watermark = watermarks.evaluate(rule.tenantId(), rule);
                if (watermark.exceeded()) {
                    exceeded.add(new BlockedRule(rule, watermark.to(), now));
                    continue;
                }
            } catch (RuntimeException e) {
                LOG.warn("Quota watermark failed for rule {} ({}); keeping the previous verdict", rule.id(),
                        e.getMessage());
            }
            // No longer EXCEEDED — or not computable at all. Keep the recorded
            // verdict for the rest of its window unless the admin has touched the
            // rule since it was recorded (#1316): deleting the usage rows a live
            // watermark is derived from is not one of the two documented recovery
            // paths, and a failed watermark derivation must not resume traffic
            // either (ADR-0020 §4).
            RecordedBlock previous = recorded.get(rule.id());
            if (previous != null && previous.windowEnd().isAfter(now)
                    && !rule.updatedAt().isAfter(previous.blockedAt())) {
                exceeded.add(new BlockedRule(rule, previous.windowEnd(), previous.blockedAt()));
                LOG.info("Quota block for rule {} held until {} (recorded {}, live watermark no longer exceeded)",
                        rule.id(), previous.windowEnd(), previous.blockedAt());
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
                    INSERT INTO quota_enforcement (rule_id, tenant_id, scope_type, scope_id, metric, period, window_end,
                        blocked_at)
                    VALUES (:ruleId, :tenantId, :scopeType, :scopeId, :metric, :period, :windowEnd, :blockedAt)
                    """,
                    new MapSqlParameterSource("ruleId", rule.id()).addValue("tenantId", rule.tenantId())
                            .addValue("scopeType", rule.scopeType().name()).addValue("scopeId", rule.scopeId())
                            .addValue("metric", rule.metric().name()).addValue("period", rule.period().name())
                            .addValue("windowEnd", Timestamp.from(blocked.windowEnd()))
                            .addValue("blockedAt", Timestamp.from(blocked.blockedAt())));
        }
        routeRefreshPublisher.publishChanged();
        LOG.info("Quota soft-landing verdict changed: {} blocking rule(s) (was {}); route refresh published",
                after.size(), before.size());
    }

    /**
     * The verdicts the previous cycle recorded, keyed by rule. {@code blockedAt} is
     * the decision time the admin-edit escape is measured against; carrying it over
     * unchanged keeps that comparison stable across rewrites of the table.
     */
    private Map<UUID, RecordedBlock> recordedBlocks() {
        return jdbc
                .query("SELECT rule_id, window_end, blocked_at FROM quota_enforcement", Map.of(),
                        (rs, rowNum) -> new RecordedBlock((UUID) rs.getObject("rule_id"),
                                rs.getTimestamp("window_end").toInstant(), rs.getTimestamp("blocked_at").toInstant()))
                .stream().collect(Collectors.toMap(RecordedBlock::ruleId, block -> block));
    }

    /** A blocking rule with the end of the window it was blocked in. */
    private record BlockedRule(QuotaRule rule, Instant windowEnd, Instant blockedAt) {
    }

    /** A verdict as recorded in {@code quota_enforcement}. */
    private record RecordedBlock(UUID ruleId, Instant windowEnd, Instant blockedAt) {
    }
}
