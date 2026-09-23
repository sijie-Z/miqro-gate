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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * current window zeroes the reading and would otherwise resume traffic, which
 * is an unlock path ADR-0020 explicitly rejected. So a rule that is no longer
 * EXCEEDED carries its recorded block forward while {@code window_end} is still
 * in the future and the rule itself has not been touched since the block was
 * recorded. A rule that is still EXCEEDED extends its block into the window the
 * live reading was taken in — {@code window_end} moves forward with the window
 * while the block's decision time stays put, so the block survives a deletion
 * in every window it was over the limit in, not just the first. Both documented
 * recovery paths survive: rolling into a new window drops the stale verdict,
 * and an admin write to the rule (raise the limit past the recorded reading,
 * disable it, delete it) lifts the block.
 * </p>
 *
 * <p>
 * What decides whether an admin write recovered anything is the reading
 * recorded with the verdict, not the rule's {@code updated_at}. Every save
 * bumps that column, and this evaluator only runs every 60s, so "has the rule
 * changed since the verdict?" hands the recovery path to a save that recovered
 * nothing: the order {@code save → retention deletion → next cycle} lifts a
 * block whose scope is still over the limit. The recorded reading has no such
 * window — the block is held while the reading is at or above the rule's
 * current limit, and lets go once the admin raises the limit above it. That
 * reading is refreshed on every cycle the rule stays exceeded, so it cannot
 * decay into a stale lower bound that makes a later raise read as a recovery.
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
            RecordedBlock previous = recorded.get(rule.id());
            boolean derived = true;
            try {
                QuotaWatermarks.Watermark watermark = watermarks.evaluate(rule.tenantId(), rule);
                if (watermark.exceeded()) {
                    // Still over the limit. Record the window the live reading was taken
                    // in: a block that started in an earlier window must not keep a
                    // window_end that has already passed, or the sticky branch below
                    // would drop it the first time the reading dips — which is exactly
                    // what a retention deletion does (#1316). The reading comes along so
                    // a later cycle can tell a recovery from that deletion, and the
                    // decision time is carried over untouched: a save is not a verdict.
                    exceeded.add(new BlockedRule(rule, watermark.to(), previous == null ? now : previous.blockedAt(),
                            watermark.used()));
                    continue;
                }
            } catch (RuntimeException e) {
                derived = false;
                LOG.warn("Quota watermark failed for rule {} ({}); keeping the previous verdict", rule.id(),
                        e.getMessage());
            }
            // No longer EXCEEDED — or not computable at all. Keep the recorded verdict for
            // the rest of its window (#1316): deleting the usage rows a live watermark is
            // derived from is not one of the two documented recovery paths.
            if (holdsVerdict(previous, rule, derived, now)) {
                exceeded.add(
                        new BlockedRule(rule, previous.windowEnd(), previous.blockedAt(), previous.observedUsed()));
                LOG.info("Quota block for rule {} held until {} (recorded {}, live watermark no longer exceeded)",
                        rule.id(), previous.windowEnd(), previous.blockedAt());
            }
        }

        // Compared per rule *and* on both gateway-visible columns: the ids alone would
        // report "unchanged" while a continuously exceeded rule crosses into a new
        // window, and window_end alone would hide a re-aligned decision time — leaving
        // the admin-edit escape disarmed in the table (#1316).
        Map<UUID, Verdict> before = verdicts(recorded.values().stream()
                .map(block -> new Verdict(block.ruleId(), block.windowEnd(), block.blockedAt())));
        Map<UUID, Verdict> after = verdicts(exceeded.stream()
                .map(blocked -> new Verdict(blocked.rule().id(), blocked.windowEnd(), blocked.blockedAt())));
        if (!before.equals(after)) {
            jdbc.update("DELETE FROM quota_enforcement", Map.of());
            for (BlockedRule blocked : exceeded) {
                QuotaRule rule = blocked.rule();
                jdbc.update("""
                        INSERT INTO quota_enforcement (rule_id, tenant_id, scope_type, scope_id, metric, period,
                            window_end, blocked_at, observed_used)
                        VALUES (:ruleId, :tenantId, :scopeType, :scopeId, :metric, :period, :windowEnd, :blockedAt,
                            :observedUsed)
                        """,
                        new MapSqlParameterSource("ruleId", rule.id()).addValue("tenantId", rule.tenantId())
                                .addValue("scopeType", rule.scopeType().name()).addValue("scopeId", rule.scopeId())
                                .addValue("metric", rule.metric().name()).addValue("period", rule.period().name())
                                .addValue("windowEnd", Timestamp.from(blocked.windowEnd()))
                                .addValue("blockedAt", Timestamp.from(blocked.blockedAt()))
                                .addValue("observedUsed", blocked.observedUsed(), Types.NUMERIC));
            }
            routeRefreshPublisher.publishChanged();
            LOG.info("Quota soft-landing verdict changed: {} blocking rule(s) (was {}); route refresh published",
                    after.size(), before.size());
            return;
        }
        refreshRecordedReadings(recorded, exceeded);
    }

    /**
     * Whether a cycle whose live reading is no longer EXCEEDED — or could not be
     * derived at all — keeps the recorded block. The rule's {@code updated_at} is
     * the last resort, not the arbiter: it moves on any save, so an "was the rule
     * touched since the verdict?" test hands the recovery path to a save that
     * recovered nothing whenever the evaluator has not run in between (#1316).
     *
     * <p>
     * A row written before V76 carries no reading, and a rule whose metric or
     * period changed holds a reading in a unit that no longer compares; those fall
     * back to the untouched-since comparison. A derivation that failed outright
     * keeps the verdict whatever the columns say (ADR-0020 §4: 宁可维持现状也不静默放行) — the
     * next successful cycle is what decides.
     * </p>
     */
    private static boolean holdsVerdict(RecordedBlock previous, QuotaRule rule, boolean derived, Instant now) {
        if (previous == null || !previous.windowEnd().isAfter(now)) {
            return false;
        }
        if (!derived) {
            return true;
        }
        if (previous.observedUsed() != null && Objects.equals(previous.metric(), rule.metric().name())
                && Objects.equals(previous.period(), rule.period().name())) {
            return QuotaWatermarks.reachesTheLimit(previous.observedUsed(), rule.limitValue());
        }
        return !rule.updatedAt().isAfter(previous.blockedAt());
    }

    /**
     * Keeps the recorded reading in step with a rule that stays exceeded. The
     * verdict and the window do not move, so nothing the gateway reads changes —
     * but the reading must not be left at the one that first established the block:
     * it would become a stale lower bound, and an admin raise to a limit between
     * the two would compare as "recovered" and lift a block the scope is still over
     * (#1316). The metric and period come along for the same reason: they are the
     * units that reading is counted in, and a rule edited into another metric must
     * not fall back to the timestamp comparison with the edit still newer than the
     * verdict.
     */
    private void refreshRecordedReadings(Map<UUID, RecordedBlock> recorded, List<BlockedRule> exceeded) {
        for (BlockedRule blocked : exceeded) {
            RecordedBlock previous = recorded.get(blocked.rule().id());
            if (previous == null || !bookkeepingMoved(previous, blocked)) {
                continue;
            }
            jdbc.update("""
                    UPDATE quota_enforcement SET metric = :metric, period = :period, observed_used = :observedUsed
                     WHERE rule_id = :ruleId
                    """,
                    new MapSqlParameterSource("ruleId", blocked.rule().id())
                            .addValue("metric", blocked.rule().metric().name())
                            .addValue("period", blocked.rule().period().name())
                            .addValue("observedUsed", blocked.observedUsed(), Types.NUMERIC));
            LOG.debug("Quota verdict for rule {} held; recorded reading moved to {}", blocked.rule().id(),
                    blocked.observedUsed());
        }
    }

    /** Whether the recorded bookkeeping still describes this cycle's reading. */
    private static boolean bookkeepingMoved(RecordedBlock previous, BlockedRule blocked) {
        if (!Objects.equals(previous.metric(), blocked.rule().metric().name())
                || !Objects.equals(previous.period(), blocked.rule().period().name())) {
            return true;
        }
        // Scale-insensitive on purpose: the column is numeric(24,10) and the aggregated
        // reading is not, so an equals() here would rewrite the row every cycle.
        return blocked.observedUsed() != null
                && (previous.observedUsed() == null || previous.observedUsed().compareTo(blocked.observedUsed()) != 0);
    }

    private static Map<UUID, Verdict> verdicts(java.util.stream.Stream<Verdict> verdicts) {
        return verdicts.collect(Collectors.toMap(Verdict::ruleId, verdict -> verdict, (first, second) -> first));
    }

    /**
     * The verdicts the previous cycle recorded, keyed by rule. {@code blockedAt} is
     * what the block's age is measured on and {@code observedUsed} is what a
     * recovery is measured against; carrying both over unchanged keeps those
     * comparisons stable across rewrites of the table.
     */
    private Map<UUID, RecordedBlock> recordedBlocks() {
        return jdbc
                .query("""
                        SELECT rule_id, window_end, blocked_at, metric, period, observed_used
                          FROM quota_enforcement
                        """, Map.of(),
                        (rs, rowNum) -> new RecordedBlock((UUID) rs.getObject("rule_id"),
                                rs.getTimestamp("window_end").toInstant(), rs.getTimestamp("blocked_at").toInstant(),
                                rs.getString("metric"), rs.getString("period"), rs.getBigDecimal("observed_used")))
                .stream().collect(Collectors.toMap(RecordedBlock::ruleId, block -> block));
    }

    /**
     * A blocking rule with the end of the window it was blocked in and the reading
     * it saw.
     */
    private record BlockedRule(QuotaRule rule, Instant windowEnd, Instant blockedAt, BigDecimal observedUsed) {
    }

    /** A verdict as recorded in {@code quota_enforcement}. */
    private record RecordedBlock(UUID ruleId, Instant windowEnd, Instant blockedAt, String metric, String period,
            BigDecimal observedUsed) {
    }

    /**
     * What the gateway reacts to per rule, compared to decide whether a rewrite is
     * needed.
     */
    private record Verdict(UUID ruleId, Instant windowEnd, Instant blockedAt) {
    }
}
