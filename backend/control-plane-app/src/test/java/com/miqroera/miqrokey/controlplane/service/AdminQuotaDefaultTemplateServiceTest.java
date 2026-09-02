package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ConfigureQuotaDefaultTemplateRequest;
import com.miqroera.miqrokey.domain.model.QuotaDefaultTemplate;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.repository.QuotaDefaultTemplateRepository;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Default quota template service decisions (Tencent doc 135489): snapshot
 * copies only while the template is enabled, insert-if-absent so a manual rule
 * wins, and conflict states for enable/disable transitions.
 */
@DisplayName("Admin quota default template service unit tests")
class AdminQuotaDefaultTemplateServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID newUserId = UUID.randomUUID();

    private final QuotaDefaultTemplateRepository templates = mock(QuotaDefaultTemplateRepository.class);
    private final QuotaRuleRepository rules = mock(QuotaRuleRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final AdminQuotaDefaultTemplateService service = new AdminQuotaDefaultTemplateService(templates, rules,
            audit);

    private QuotaDefaultTemplate template(boolean enabled) {
        return new QuotaDefaultTemplate(tenantId, enabled, QuotaMetric.TOKENS, QuotaPeriod.MONTHLY, 1_000_000, adminId,
                0, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("no template or disabled template creates no rule and no audit")
    void disabledOrMissingTemplateSkipsCopy() {
        when(templates.find(tenantId)).thenReturn(Optional.empty());
        assertThat(service.applyToNewUser(tenantId, adminId, newUserId)).isEmpty();
        verify(rules, never()).insertIfAbsent(any());

        when(templates.find(tenantId)).thenReturn(Optional.of(template(false)));
        assertThat(service.applyToNewUser(tenantId, adminId, newUserId)).isEmpty();
        verify(rules, never()).insertIfAbsent(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("enabled template snapshots an ACTIVE USER rule with default warn and audits it as automatic")
    void enabledTemplateCopiesSnapshot() {
        when(templates.find(tenantId)).thenReturn(Optional.of(template(true)));
        QuotaRule inserted = new QuotaRule(UUID.randomUUID(), tenantId, QuotaScopeType.USER, newUserId,
                QuotaMetric.TOKENS, QuotaPeriod.MONTHLY, 1_000_000, 80, QuotaRuleStatus.ACTIVE, adminId, 0,
                Instant.now(), Instant.now());
        when(rules.insertIfAbsent(any())).thenReturn(Optional.of(inserted));

        assertThat(service.applyToNewUser(tenantId, adminId, newUserId)).contains(inserted);

        ArgumentCaptor<QuotaRule> captor = ArgumentCaptor.forClass(QuotaRule.class);
        verify(rules).insertIfAbsent(captor.capture());
        QuotaRule snapshot = captor.getValue();
        assertThat(snapshot.scopeType()).isEqualTo(QuotaScopeType.USER);
        assertThat(snapshot.scopeId()).isEqualTo(newUserId);
        assertThat(snapshot.metric()).isEqualTo(QuotaMetric.TOKENS);
        assertThat(snapshot.period()).isEqualTo(QuotaPeriod.MONTHLY);
        assertThat(snapshot.limitValue()).isEqualTo(1_000_000);
        assertThat(snapshot.warnPercent()).isEqualTo(80);
        assertThat(snapshot.status()).isEqualTo(QuotaRuleStatus.ACTIVE);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(tenantId), eq(adminId), eq("QUOTA_RULE_CREATE"), eq("QUOTA_RULE"), eq(inserted.id()),
                summary.capture(), eq(null));
        assertThat(summary.getValue()).contains("\"auto\":true").contains("\"limit\":1000000");
    }

    @Test
    @DisplayName("a pre-existing manual rule blocks the copy and produces no audit")
    void manualRuleWinsOverTemplate() {
        when(templates.find(tenantId)).thenReturn(Optional.of(template(true)));
        when(rules.insertIfAbsent(any())).thenReturn(Optional.empty());

        assertThat(service.applyToNewUser(tenantId, adminId, newUserId)).isEmpty();
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("configure keeps the enabled state and audits create vs update by stored version")
    void configurePreservesEnabled() {
        when(templates.find(tenantId)).thenReturn(Optional.of(template(true)));
        QuotaDefaultTemplate stored = new QuotaDefaultTemplate(tenantId, true, QuotaMetric.REQUESTS, QuotaPeriod.WEEKLY,
                500, adminId, 2, Instant.now(), Instant.now());
        when(templates.upsertDefinition(any())).thenReturn(stored);

        service.configure(tenantId, adminId,
                new ConfigureQuotaDefaultTemplateRequest(QuotaMetric.REQUESTS, QuotaPeriod.WEEKLY, 500L), "req-1");
        verify(audit).record(eq(tenantId), eq(adminId), eq("QUOTA_DEFAULT_TEMPLATE_UPDATE"),
                eq("QUOTA_DEFAULT_TEMPLATE"), eq(tenantId), any(), eq("req-1"));

        // A fresh row (version 0) is audited as a create.
        when(templates.upsertDefinition(any())).thenReturn(new QuotaDefaultTemplate(tenantId, false, QuotaMetric.TOKENS,
                QuotaPeriod.MONTHLY, 1000, adminId, 0, Instant.now(), Instant.now()));
        service.configure(tenantId, adminId,
                new ConfigureQuotaDefaultTemplateRequest(QuotaMetric.TOKENS, QuotaPeriod.MONTHLY, 1000L), "req-2");
        verify(audit).record(eq(tenantId), eq(adminId), eq("QUOTA_DEFAULT_TEMPLATE_CREATE"),
                eq("QUOTA_DEFAULT_TEMPLATE"), eq(tenantId), any(), eq("req-2"));
    }

    @Test
    @DisplayName("enable and disable conflict on unconfigured or already-matching state")
    void setEnabledConflicts() {
        when(templates.find(tenantId)).thenReturn(Optional.empty());
        ApiException unconfigured = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.setEnabled(tenantId, adminId, true, "r"));
        assertThat(unconfigured.getCode()).isEqualTo("QUOTA_TEMPLATE_NOT_CONFIGURED");

        when(templates.find(tenantId)).thenReturn(Optional.of(template(true)));
        ApiException alreadyEnabled = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.setEnabled(tenantId, adminId, true, "r"));
        assertThat(alreadyEnabled.getCode()).isEqualTo("QUOTA_TEMPLATE_ALREADY_ENABLED");

        when(templates.find(tenantId)).thenReturn(Optional.of(template(false)));
        ApiException alreadyDisabled = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.setEnabled(tenantId, adminId, false, "r"));
        assertThat(alreadyDisabled.getCode()).isEqualTo("QUOTA_TEMPLATE_ALREADY_DISABLED");
    }
}
