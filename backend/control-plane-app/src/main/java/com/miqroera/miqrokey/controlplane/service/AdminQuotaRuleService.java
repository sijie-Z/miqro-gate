package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.QuotaRuleView;
import com.miqroera.miqrokey.controlplane.dto.UpsertQuotaRuleRequest;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

/**
 * Usage quota plans (V23, {@code quota_rules}, platform-middleware roadmap
 * "quota management" step): per scope (USER | PROJECT) limits of a metric
 * (TOKENS | REQUESTS) per UTC period (DAILY | WEEKLY | MONTHLY) with a warn
 * threshold. The current-period watermark is computed at read time from usage
 * events through the shared aggregator; the derived level follows the Tencent
 * consumer-quota states (NORMAL / WARNING / EXCEEDED). A rule never blocks
 * traffic — this plan is the alerting-only half of quota governance; hard
 * blocking and webhook alerting are separate ADR/extension steps.
 */
@Service
public class AdminQuotaRuleService {

    private static final int DEFAULT_WARN_PERCENT = 80;

    private final QuotaRuleRepository quotaRuleRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final AdminUsageStatsService usageStatsService;
    private final AuditService auditService;

    public AdminQuotaRuleService(QuotaRuleRepository quotaRuleRepository, UserRepository userRepository,
            ProjectRepository projectRepository, AdminUsageStatsService usageStatsService, AuditService auditService) {
        this.quotaRuleRepository = quotaRuleRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.usageStatsService = usageStatsService;
        this.auditService = auditService;
    }

    /** All quota rules with live watermarks for their current windows. */
    public List<QuotaRuleView> list(UUID tenantId) {
        return quotaRuleRepository.findAllByTenant(tenantId).stream().map(rule -> view(tenantId, rule)).toList();
    }

    /**
     * USER-scope rules that apply to the given user — the self-service visibility
     * slice of the admin list (F04): the user sees exactly the plans set on their
     * own account, watermarks and all, never another scope's rules.
     */
    public List<QuotaRuleView> listForUser(UUID tenantId, UUID userId) {
        return quotaRuleRepository.findAllByTenant(tenantId).stream()
                .filter(rule -> rule.scopeType() == QuotaScopeType.USER && rule.scopeId().equals(userId))
                .map(rule -> view(tenantId, rule)).toList();
    }

    /**
     * Inserts or updates the plan keyed on (tenant, scope, metric, period). An
     * existing rule keeps its id, version bumps and created_at stays.
     */
    @Transactional
    public QuotaRuleView put(UUID tenantId, UUID adminId, UpsertQuotaRuleRequest request, String requestId) {
        requireScope(tenantId, request.scopeType(), request.scopeId());
        QuotaRuleStatus status = request.status() == null ? QuotaRuleStatus.ACTIVE : request.status();
        int warnPercent = request.warnPercent() == null ? DEFAULT_WARN_PERCENT : request.warnPercent();
        QuotaRule existing = quotaRuleRepository
                .findByKey(tenantId, request.scopeType(), request.scopeId(), request.metric(), request.period())
                .orElse(null);
        Instant now = Instant.now();
        QuotaRule plan = new QuotaRule(existing == null ? UUID.randomUUID() : existing.id(), tenantId,
                request.scopeType(), request.scopeId(), request.metric(), request.period(), request.limitValue(),
                warnPercent, status, adminId, existing == null ? 0 : existing.version(), now, now);
        QuotaRule stored = quotaRuleRepository.upsert(plan);
        String action = existing == null ? "QUOTA_RULE_CREATE" : "QUOTA_RULE_UPDATE";
        auditService.record(tenantId, adminId, action, "QUOTA_RULE", stored.id(),
                auditSummary("scopeType", stored.scopeType().name(), "scopeId", stored.scopeId(), "metric",
                        stored.metric().name(), "period", stored.period().name(), "limit", stored.limitValue(),
                        "warnPercent", stored.warnPercent(), "status", stored.status().name()),
                requestId);
        return view(tenantId, stored);
    }

    @Transactional
    public void delete(UUID tenantId, UUID adminId, UUID ruleId, String requestId) {
        QuotaRule rule = quotaRuleRepository.findById(tenantId, ruleId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "QUOTA_RULE_NOT_FOUND", "Quota rule not found"));
        quotaRuleRepository.delete(tenantId, ruleId);
        auditService
                .record(tenantId, adminId, "QUOTA_RULE_DELETE", "QUOTA_RULE", ruleId,
                        auditSummary("scopeType", rule.scopeType().name(), "scopeId", rule.scopeId(), "metric",
                                rule.metric().name(), "period", rule.period().name(), "limit", rule.limitValue()),
                        requestId);
    }

    // -------------------------------------------------------------------

    private void requireScope(UUID tenantId, QuotaScopeType scopeType, UUID scopeId) {
        boolean exists = switch (scopeType) {
            case USER -> userRepository.findById(scopeId).filter(u -> u.tenantId().equals(tenantId)).isPresent();
            case PROJECT -> projectRepository.findById(scopeId).filter(p -> p.tenantId().equals(tenantId)).isPresent();
        };
        if (!exists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCOPE_NOT_FOUND",
                    "The quota scope does not exist in this tenant");
        }
    }

    /** Live watermark: usage of the current UTC window of the rule's period. */
    private QuotaRuleView view(UUID tenantId, QuotaRule rule) {
        Window window = window(rule.period());
        UsageSummary summary = usageStatsService.summary(tenantId, "project", window.from(), window.to(),
                rule.scopeType() == QuotaScopeType.USER ? rule.scopeId() : null,
                rule.scopeType() == QuotaScopeType.PROJECT ? rule.scopeId() : null, null, null, null, null, null);
        long used = rule.metric() == QuotaMetric.TOKENS
                ? summary.totals().tokens().total()
                : summary.totals().requests().upstream();
        BigDecimal usedPct = BigDecimal.valueOf(used).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(rule.limitValue()), 2, RoundingMode.HALF_UP);
        String level = usedPct.compareTo(BigDecimal.valueOf(100)) >= 0
                ? "EXCEEDED"
                : usedPct.compareTo(BigDecimal.valueOf(rule.warnPercent())) >= 0 ? "WARNING" : "NORMAL";
        ScopeInfo scope = scopeInfo(tenantId, rule.scopeType(), rule.scopeId());
        return new QuotaRuleView(rule.id(), rule.scopeType(), rule.scopeId(), scope.name(), scope.tag(), rule.metric(),
                rule.period(), rule.limitValue(), rule.warnPercent(), rule.status(), used, usedPct, level,
                window.from(), window.to(), rule.createdAt(), rule.updatedAt(), rule.version());
    }

    private ScopeInfo scopeInfo(UUID tenantId, QuotaScopeType scopeType, UUID scopeId) {
        if (scopeType == QuotaScopeType.USER) {
            User user = userRepository.findById(scopeId).filter(u -> u.tenantId().equals(tenantId)).orElse(null);
            return new ScopeInfo(user == null ? null : user.displayName(), user == null ? null : user.username());
        }
        Project project = projectRepository.findById(scopeId).filter(p -> p.tenantId().equals(tenantId)).orElse(null);
        return new ScopeInfo(project == null ? null : project.name(), project == null ? null : project.code());
    }

    private record ScopeInfo(String name, String tag) {
    }

    /** UTC calendar slice for the period: day / week (Mon-start) / month. */
    static Window window(QuotaPeriod period) {
        return window(period, LocalDate.now(ZoneOffset.UTC));
    }

    static Window window(QuotaPeriod period, LocalDate today) {
        LocalDate fromDate = switch (period) {
            case DAILY -> today;
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> today.withDayOfMonth(1);
        };
        LocalDate toDate = switch (period) {
            case DAILY -> fromDate.plusDays(1);
            case WEEKLY -> fromDate.plusWeeks(1);
            case MONTHLY -> fromDate.plusMonths(1);
        };
        return new Window(fromDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                toDate.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    record Window(Instant from, Instant to) {
    }

    private static String auditSummary(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(v).append('"');
            }
        }
        return sb.append('}').toString();
    }
}
