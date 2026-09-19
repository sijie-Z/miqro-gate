package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.QuotaRuleView;
import com.miqroera.miqrokey.controlplane.dto.ResourceDependency;
import com.miqroera.miqrokey.controlplane.dto.UpsertQuotaRuleRequest;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.QuotaAction;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

/**
 * Usage quota plans (V23/V58, {@code quota_rules}, platform-middleware roadmap
 * "quota management" step): per scope (USER | PROJECT) limits of a metric
 * (TOKENS | REQUESTS | COST) per UTC period (DAILY | WEEKLY | MONTHLY | YEARLY)
 * with a warn threshold. The current-period watermark is computed at read time
 * from usage events through the shared aggregator; the derived level follows
 * the Tencent consumer-quota states (NORMAL / WARNING / NEAR_LIMIT / EXCEEDED)
 * and comes from the shared {@link QuotaWatermarks}. An exceeded rule acts
 * according to its {@code action}: ALERT only reports (default), REJECT blocks
 * the covered scope at the gateway (#684/ADR-0020).
 */
@Service
public class AdminQuotaRuleService {

    private static final int DEFAULT_WARN_PERCENT = 80;

    private final QuotaRuleRepository quotaRuleRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final QuotaWatermarks watermarks;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbc;

    public AdminQuotaRuleService(QuotaRuleRepository quotaRuleRepository, UserRepository userRepository,
            ProjectRepository projectRepository, QuotaWatermarks watermarks, AuditService auditService,
            NamedParameterJdbcTemplate jdbc) {
        this.quotaRuleRepository = quotaRuleRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.watermarks = watermarks;
        this.auditService = auditService;
        this.jdbc = jdbc;
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
     * One rule with its live watermark, or {@code null} when the tenant has no such
     * rule — the targeted lookup for callers that already know the rule id. Reading
     * a single rule must not render the tenant's other rules: every rendered rule
     * costs a live watermark over its own window (PH8b, mirrors
     * {@code AdminBudgetService.view}).
     */
    public QuotaRuleView view(UUID tenantId, UUID ruleId) {
        return quotaRuleRepository.findById(tenantId, ruleId).map(rule -> view(tenantId, rule)).orElse(null);
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
        // Soft landing (#684): an omitted action keeps the alerting-only behaviour.
        QuotaAction exceededAction = request.action() == null ? QuotaAction.ALERT : request.action();
        QuotaRule existing = quotaRuleRepository
                .findByKey(tenantId, request.scopeType(), request.scopeId(), request.metric(), request.period())
                .orElse(null);
        Instant now = Instant.now();
        QuotaRule plan = new QuotaRule(existing == null ? UUID.randomUUID() : existing.id(), tenantId,
                request.scopeType(), request.scopeId(), request.metric(), request.period(), exceededAction,
                request.limitValue(), warnPercent, status, adminId, existing == null ? 0 : existing.version(), now,
                now);
        QuotaRule stored = quotaRuleRepository.upsert(plan);
        String action = existing == null ? "QUOTA_RULE_CREATE" : "QUOTA_RULE_UPDATE";
        auditService.record(tenantId, adminId, action, "QUOTA_RULE", stored.id(),
                auditSummary("scopeType", stored.scopeType().name(), "scopeId", stored.scopeId(), "metric",
                        stored.metric().name(), "period", stored.period().name(), "action", stored.action().name(),
                        "limit", stored.limitValue(), "warnPercent", stored.warnPercent(), "status",
                        stored.status().name()),
                requestId);
        return view(tenantId, stored);
    }

    /**
     * Deletes the rule. I21 (Tencent model-API delete semantics), mirroring
     * {@link WebhookEndpointService#delete}: a rule still referenced by a
     * {@code QUOTA_THRESHOLD} alert rule is NOT silently orphaned. The reference
     * lives in {@code alert_rules.scope_json->>'quotaRuleId'} — a jsonb field with
     * no foreign key — so nothing at the database level would stop the delete; the
     * alert rule would stay enabled and visible with its threshold while
     * {@link AlertEvaluator#quotaRuleView} resolves it to null forever, and it
     * could never fire again. The delete is refused with the referencing rules
     * until the references are released.
     *
     * <p>
     * The reference is matched by UUID <em>identity</em>, the way
     * {@link AlertEvaluator#quotaRuleView} resolves it — not by comparing the
     * stored text to {@code UUID.toString()}. The scope text is whatever the writer
     * submitted ({@code scopeJson} is passed through verbatim) and
     * {@link UUID#fromString} accepts non-canonical spellings, so a text equality
     * test silently misses references the evaluator happily resolves (for example
     * an upper-case spelling), leaving the very dangling rule this method exists to
     * prevent. Malformed text resolves to nothing in both places.
     *
     * <p>
     * Only {@code QUOTA_THRESHOLD} rules count: the key is meaningless on any other
     * rule type, and {@link AlertEvaluator} never reads it there, so a stray key
     * must not block the delete.
     */
    @Transactional
    public void delete(UUID tenantId, UUID adminId, UUID ruleId, String requestId) {
        QuotaRule rule = quotaRuleRepository.findById(tenantId, ruleId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "QUOTA_RULE_NOT_FOUND", "Quota rule not found"));
        List<ResourceDependency> dependents = jdbc
                .query("""
                        SELECT id, name, enabled, scope_json ->> 'quotaRuleId' AS scope_ref FROM alert_rules
                        WHERE tenant_id = :tenantId AND type = 'QUOTA_THRESHOLD'
                          AND scope_json ->> 'quotaRuleId' IS NOT NULL
                        ORDER BY name
                        """, new MapSqlParameterSource("tenantId", tenantId),
                        (rs, rowNum) -> new ReferencingRule((UUID) rs.getObject("id"), rs.getString("name"),
                                rs.getBoolean("enabled"), rs.getString("scope_ref")))
                .stream().filter(ref -> resolvesTo(ref.scopeRef(), ruleId))
                .map(ref -> new ResourceDependency("ALERT_RULE", ref.id(), ref.name(), ref.enabled() ? "已启用" : "已停用"))
                .toList();
        if (!dependents.isEmpty()) {
            throw new ResourceInUseException("该配额规则被 " + dependents.size() + " 条告警规则引用，请先删除或改配这些告警规则。", dependents);
        }
        quotaRuleRepository.delete(tenantId, ruleId);
        auditService
                .record(tenantId, adminId, "QUOTA_RULE_DELETE", "QUOTA_RULE", ruleId,
                        auditSummary("scopeType", rule.scopeType().name(), "scopeId", rule.scopeId(), "metric",
                                rule.metric().name(), "period", rule.period().name(), "limit", rule.limitValue()),
                        requestId);
    }

    // -------------------------------------------------------------------

    /**
     * True when the stored scope text denotes {@code ruleId}, using the same UUID
     * parsing the evaluator uses. Text that does not parse denotes nothing, so it
     * never blocks a delete (and never 500s the endpoint).
     */
    private static boolean resolvesTo(String scopeRef, UUID ruleId) {
        if (scopeRef == null) {
            return false;
        }
        try {
            return UUID.fromString(scopeRef).equals(ruleId);
        } catch (IllegalArgumentException e) {
            return false; // malformed scope — the evaluator ignores it too
        }
    }

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

    /**
     * Live watermark of the current UTC window — delegated to the shared
     * {@link QuotaWatermarks} so the page and the enforcement gate can never
     * disagree on the level (#684).
     */
    private QuotaRuleView view(UUID tenantId, QuotaRule rule) {
        QuotaWatermarks.Watermark watermark = watermarks.evaluate(tenantId, rule);
        ScopeInfo scope = scopeInfo(tenantId, rule.scopeType(), rule.scopeId());
        return new QuotaRuleView(rule.id(), rule.scopeType(), rule.scopeId(), scope.name(), scope.tag(), rule.metric(),
                rule.period(), rule.action(), rule.limitValue(), rule.warnPercent(), rule.status(), watermark.used(),
                watermark.usedPct(), watermark.level(), watermark.from(), watermark.to(), rule.createdAt(),
                rule.updatedAt(), rule.version());
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

    /**
     * A {@code QUOTA_THRESHOLD} rule carrying scope text, before identity matching.
     */
    private record ReferencingRule(UUID id, String name, boolean enabled, String scopeRef) {
    }

    /** UTC calendar slice for the period: day / week (Mon-start) / month / year. */
    static Window window(QuotaPeriod period) {
        return window(period, LocalDate.now(ZoneOffset.UTC));
    }

    static Window window(QuotaPeriod period, LocalDate today) {
        LocalDate fromDate = switch (period) {
            case DAILY -> today;
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> today.withDayOfMonth(1);
            case YEARLY -> today.withDayOfYear(1);
        };
        LocalDate toDate = switch (period) {
            case DAILY -> fromDate.plusDays(1);
            case WEEKLY -> fromDate.plusWeeks(1);
            case MONTHLY -> fromDate.plusMonths(1);
            case YEARLY -> fromDate.plusYears(1);
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
