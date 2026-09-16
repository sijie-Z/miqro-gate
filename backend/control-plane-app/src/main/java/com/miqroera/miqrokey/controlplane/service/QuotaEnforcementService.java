package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.QuotaEnforcement;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleEnforcement;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.repository.QuotaEnforcementRepository;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quota soft-landing evaluator (#684, {@code quota_enforcement} V59). A rule is
 * alert-only unless it opts into {@link QuotaRuleEnforcement#REJECT}; this
 * service turns an exhausted REJECT rule (watermark ≥ 100% of the limit in the
 * current period window) into one row per blocked scope, which the route
 * snapshot loader publishes to the gateway.
 *
 * <p>
 * The reconcile is a projection, never a second source of truth: it rewrites a
 * row only when a blocking-relevant input changed (rule, metric, period, limit
 * or window) and deletes rows for scopes that no longer qualify — limit raised
 * back above usage, rule disabled, rule deleted (FK CASCADE already covers the
 * last one), or period rolled over. Running it twice in a row is a no-op, which
 * is what makes the periodic tick safe.
 * </p>
 *
 * <p>
 * Watermarks come from {@link AdminQuotaRuleService#watermark}, the same
 * computation the admin list shows, so "blocked" and "the UI says EXCEEDED" can
 * never drift apart. A scope can be covered by several REJECT rules (one per
 * metric/period); the scope's single row is the most severe one — highest
 * watermark, then smallest rule id as a stable tie-break.
 * </p>
 *
 * <p>
 * Soft landing is the whole point: nothing here deletes, disables or rotates a
 * virtual key or credential. Only requests are refused, and only until the
 * window rolls over or the limit is raised.
 * </p>
 */
@Service
public class QuotaEnforcementService {

    /** The limit itself: the level a REJECT rule blocks at. */
    private static final BigDecimal EXCEEDED_PERCENT = BigDecimal.valueOf(100);

    private final QuotaRuleRepository quotaRuleRepository;
    private final QuotaEnforcementRepository quotaEnforcementRepository;
    private final AdminQuotaRuleService quotaRuleService;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public QuotaEnforcementService(QuotaRuleRepository quotaRuleRepository,
            QuotaEnforcementRepository quotaEnforcementRepository, AdminQuotaRuleService quotaRuleService,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.quotaRuleRepository = quotaRuleRepository;
        this.quotaEnforcementRepository = quotaEnforcementRepository;
        this.quotaRuleService = quotaRuleService;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    /**
     * Reconciles the tenant's blocked scopes against its current rules and
     * watermarks, publishing a route refresh when the set actually changed so
     * the gateway picks the block up without waiting for its periodic reload.
     *
     * @return true when at least one block was materialised or removed
     */
    @Transactional
    public boolean reconcile(UUID tenantId) {
        Map<ScopeKey, QuotaRule> blocked = new LinkedHashMap<>();
        Map<ScopeKey, AdminQuotaRuleService.Watermark> watermarks = new LinkedHashMap<>();
        for (QuotaRule rule : quotaRuleRepository.findAllByTenant(tenantId)) {
            if (rule.status() != QuotaRuleStatus.ACTIVE || rule.enforcement() != QuotaRuleEnforcement.REJECT) {
                continue;
            }
            AdminQuotaRuleService.Watermark mark = quotaRuleService.watermark(tenantId, rule);
            if (mark.usedPct().compareTo(EXCEEDED_PERCENT) < 0) {
                continue;
            }
            ScopeKey key = new ScopeKey(rule.scopeType(), rule.scopeId());
            QuotaRule current = blocked.get(key);
            if (current == null || beats(rule, mark, current, watermarks.get(key))) {
                blocked.put(key, rule);
                watermarks.put(key, mark);
            }
        }

        List<QuotaEnforcement> existing = quotaEnforcementRepository.findAllByTenant(tenantId);
        Instant now = Instant.now();
        boolean changed = false;
        for (QuotaEnforcement row : existing) {
            if (!blocked.containsKey(new ScopeKey(row.scopeType(), row.scopeId()))) {
                changed |= quotaEnforcementRepository.deleteByScope(tenantId, row.scopeType(), row.scopeId());
            }
        }
        for (Map.Entry<ScopeKey, QuotaRule> entry : blocked.entrySet()) {
            QuotaRule rule = entry.getValue();
            AdminQuotaRuleService.Watermark mark = watermarks.get(entry.getKey());
            QuotaEnforcement current = find(existing, entry.getKey());
            if (current != null && current.matches(rule.id(), rule.metric(), rule.period(), rule.limitValue(),
                    mark.windowFrom(), mark.windowTo())) {
                continue;
            }
            // created_at is kept across rewrites: it marks when the scope entered
            // the blocked state, not when the row was last touched.
            quotaEnforcementRepository.upsert(new QuotaEnforcement(current == null ? UUID.randomUUID() : current.id(),
                    tenantId, rule.scopeType(), rule.scopeId(), rule.id(), rule.metric(), rule.period(),
                    rule.limitValue(), mark.used(), mark.usedPct(), mark.windowFrom(), mark.windowTo(),
                    current == null ? now : current.createdAt(), now));
            changed = true;
        }
        if (changed) {
            routeRefreshPublisher.publishChanged();
        }
        return changed;
    }

    /** Highest watermark wins; the smaller rule id breaks ties deterministically. */
    private static boolean beats(QuotaRule candidate, AdminQuotaRuleService.Watermark candidateMark,
            QuotaRule current, AdminQuotaRuleService.Watermark currentMark) {
        int byWatermark = candidateMark.usedPct().compareTo(currentMark.usedPct());
        return byWatermark != 0 ? byWatermark > 0 : candidate.id().compareTo(current.id()) < 0;
    }

    private static QuotaEnforcement find(List<QuotaEnforcement> rows, ScopeKey key) {
        return rows.stream().filter(row -> row.scopeType() == key.scopeType() && row.scopeId().equals(key.scopeId()))
                .findFirst().orElse(null);
    }

    /** The blocked unit: at most one row per (tenant, scope_type, scope_id). */
    private record ScopeKey(QuotaScopeType scopeType, UUID scopeId) {
    }
}
