package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ConfigureQuotaDefaultTemplateRequest;
import com.miqroera.miqrokey.controlplane.dto.QuotaDefaultTemplateView;
import com.miqroera.miqrokey.domain.model.QuotaDefaultTemplate;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.repository.QuotaDefaultTemplateRepository;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Global default quota strategy (V26, Tencent doc 135489): one per-tenant
 * template acting as the snapshot source for quota rules of newly created
 * users. Copy semantics: the template is copied at user-creation time into an
 * ordinary USER-scope {@link QuotaRule}; editing or disabling the template
 * never touches already-assigned rules, and the copy is insert-if-absent so
 * manual rules always win.
 */
@Service
public class AdminQuotaDefaultTemplateService {

    /** Snapshot copies carry the same default as manually created rules. */
    private static final int SNAPSHOT_WARN_PERCENT = 80;

    private final QuotaDefaultTemplateRepository templateRepository;
    private final QuotaRuleRepository quotaRuleRepository;
    private final AuditService auditService;

    public AdminQuotaDefaultTemplateService(QuotaDefaultTemplateRepository templateRepository,
            QuotaRuleRepository quotaRuleRepository, AuditService auditService) {
        this.templateRepository = templateRepository;
        this.quotaRuleRepository = quotaRuleRepository;
        this.auditService = auditService;
    }

    /** Current strategy; an empty view when the tenant never configured one. */
    public QuotaDefaultTemplateView get(UUID tenantId) {
        return templateRepository.find(tenantId).map(QuotaDefaultTemplateView::of)
                .orElseGet(QuotaDefaultTemplateView::empty);
    }

    /**
     * Stores the snapshot source (definition). The enabled flag is preserved across
     * edits — re-configuring never re-arms an intentionally disabled strategy. An
     * upserted row with version 0 was just created.
     */
    @Transactional
    public QuotaDefaultTemplateView configure(UUID tenantId, UUID adminId, ConfigureQuotaDefaultTemplateRequest request,
            String requestId) {
        boolean enabled = templateRepository.find(tenantId).map(QuotaDefaultTemplate::enabled).orElse(false);
        Instant now = Instant.now();
        QuotaDefaultTemplate template = new QuotaDefaultTemplate(tenantId, enabled, request.metric(), request.period(),
                request.limitValue(), adminId, 0, now, now);
        QuotaDefaultTemplate stored = templateRepository.upsertDefinition(template);
        auditService
                .record(tenantId, adminId,
                        stored.version() == 0 ? "QUOTA_DEFAULT_TEMPLATE_CREATE" : "QUOTA_DEFAULT_TEMPLATE_UPDATE",
                        "QUOTA_DEFAULT_TEMPLATE", tenantId, summary("metric", stored.metric().name(), "period",
                                stored.period().name(), "limit", stored.limitValue(), "enabled", stored.enabled()),
                        requestId);
        return QuotaDefaultTemplateView.of(stored);
    }

    /** Enables/disables the strategy without touching the stored definition. */
    @Transactional
    public QuotaDefaultTemplateView setEnabled(UUID tenantId, UUID adminId, boolean enable, String requestId) {
        QuotaDefaultTemplate template = templateRepository.find(tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUOTA_TEMPLATE_NOT_CONFIGURED",
                        "Configure the default quota template before enabling or disabling it"));
        if (template.enabled() == enable) {
            String code = enable ? "QUOTA_TEMPLATE_ALREADY_ENABLED" : "QUOTA_TEMPLATE_ALREADY_DISABLED";
            throw new ApiException(HttpStatus.CONFLICT, code, "The template is already in that state");
        }
        QuotaDefaultTemplate stored = templateRepository.setEnabled(tenantId, enable, adminId);
        auditService
                .record(tenantId, adminId, enable ? "QUOTA_DEFAULT_TEMPLATE_ENABLE" : "QUOTA_DEFAULT_TEMPLATE_DISABLE",
                        "QUOTA_DEFAULT_TEMPLATE", tenantId, summary("metric", stored.metric().name(), "period",
                                stored.period().name(), "limit", stored.limitValue(), "enabled", stored.enabled()),
                        requestId);
        return QuotaDefaultTemplateView.of(stored);
    }

    /**
     * Snapshot copy invoked right after a user was created: while the template is
     * enabled, the new user receives one quota rule (USER scope) carrying the
     * template definition. Insert-if-absent keeps any pre-existing manual rule
     * untouched; later template edits or a disable never affect copies. Returns the
     * inserted rule when one was created.
     */
    public Optional<QuotaRule> applyToNewUser(UUID tenantId, UUID adminId, UUID newUserId) {
        QuotaDefaultTemplate template = templateRepository.find(tenantId).orElse(null);
        if (template == null || !template.enabled()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        QuotaRule snapshot = new QuotaRule(UUID.randomUUID(), tenantId, QuotaScopeType.USER, newUserId,
                template.metric(), template.period(), template.limitValue(), SNAPSHOT_WARN_PERCENT,
                QuotaRuleStatus.ACTIVE, adminId, 0, now, now);
        Optional<QuotaRule> inserted = quotaRuleRepository.insertIfAbsent(snapshot);
        if (inserted.isPresent()) {
            QuotaRule rule = inserted.get();
            auditService.record(tenantId, adminId, "QUOTA_RULE_CREATE", "QUOTA_RULE", rule.id(),
                    summary("scopeType", rule.scopeType().name(), "scopeId", rule.scopeId(), "metric",
                            rule.metric().name(), "period", rule.period().name(), "limit", rule.limitValue(),
                            "warnPercent", rule.warnPercent(), "status", rule.status().name(), "auto", true),
                    null);
        }
        return inserted;
    }

    private static String summary(Object... kv) {
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
