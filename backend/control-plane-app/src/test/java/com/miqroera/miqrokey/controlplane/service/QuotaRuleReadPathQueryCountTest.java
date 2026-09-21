package com.miqroera.miqrokey.controlplane.service;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.dto.QuotaRuleView;
import com.miqroera.miqrokey.domain.model.QuotaAction;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingGap;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cost guard for the quota-rule read paths (PH8b): reading one rule must not
 * render every other rule of the tenant first. Each rendered rule costs a live
 * watermark — one usage aggregation over the rule's current window, which the
 * aggregator turns into two SQL aggregates — plus the scope's display-name
 * lookup, so the cost of a read is proportional to the rendered rows.
 *
 * <p>
 * Two callers are pinned here:
 * <ul>
 * <li>{@code /api/v1/me/quota-rules} (self-service, F04) renders the caller's
 * own rules — a user-facing page load. It must not pay for the tenant's other
 * rules, which the caller never sees.</li>
 * <li>{@code AlertEvaluator.evaluateAll()} (every
 * {@code miqrokey.alerts.evaluation-interval-ms}, default 5 minutes) resolves
 * one {@code QUOTA_THRESHOLD} rule. It must not pay for the tenant's unrelated
 * quota rules, or an alert cycle costs O(alert rules × tenant quota
 * rules).</li>
 * </ul>
 * </p>
 *
 * <p>
 * The assertions are relational (a bigger tenant must cost the same as a small
 * one), which pins the shape of the fix without freezing an implementation: a
 * filter before rendering and a targeted repository lookup both satisfy them.
 * </p>
 */
class QuotaRuleReadPathQueryCountTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();

    @Test
    void listForUserDoesNotRenderOtherRulesOfTheTenant() {
        long smallTenant = listForUserWatermarks(1);
        long largeTenant = listForUserWatermarks(7);
        System.out.printf("listForUser watermarks computed: 2 tenant rules -> %d, 8 tenant rules -> %d%n", smallTenant,
                largeTenant);
        assertThat(largeTenant).as("watermarks computed to render the caller's single rule (tenant with 8 rules vs 2)")
                .isEqualTo(smallTenant);
    }

    @Test
    void alertCycleDoesNotRenderUnrelatedQuotaRules() {
        long smallTenant = alertCycleWatermarks(1);
        long largeTenant = alertCycleWatermarks(9);
        System.out.printf("alert cycle watermarks computed: 2 tenant rules -> %d, 10 tenant rules -> %d%n", smallTenant,
                largeTenant);
        assertThat(largeTenant).as("watermarks computed per alert cycle (tenant with 10 quota rules vs 2)")
                .isEqualTo(smallTenant);
    }

    /**
     * Watermarks computed while {@code listForUser} renders the caller's slice of a
     * tenant that holds {@code otherRules} rules belonging to someone else.
     */
    private long listForUserWatermarks(int otherRules) {
        QuotaWatermarks watermarks = watermarksMock();
        QuotaRuleRepository rules = mock(QuotaRuleRepository.class);
        when(rules.findAllByTenant(TENANT)).thenReturn(tenantRules(otherRules));
        AdminQuotaRuleService service = new AdminQuotaRuleService(rules, mock(UserRepository.class),
                mock(ProjectRepository.class), watermarks, mock(AuditService.class),
                mock(NamedParameterJdbcTemplate.class));

        long before = Mockito.mockingDetails(watermarks).getInvocations().size();
        List<QuotaRuleView> views = service.listForUser(TENANT, CALLER);
        long calls = Mockito.mockingDetails(watermarks).getInvocations().size() - before;

        // The slice itself is unchanged: exactly the caller's own rules, in order.
        assertThat(views).extracting(QuotaRuleView::scopeId).containsExactly(CALLER);
        return calls;
    }

    /**
     * Watermarks computed during one scheduler cycle over a tenant with
     * {@code otherRules} unrelated quota rules plus the one rule the alert points
     * at. The watermark stays far below the alert threshold, so the cycle never
     * reaches the dedupe/insert path.
     */
    private long alertCycleWatermarks(int otherRules) {
        UUID targetId = UUID.randomUUID();
        QuotaRuleRepository rules = mock(QuotaRuleRepository.class);
        when(rules.findAllByTenant(TENANT)).thenReturn(tenantRules(targetId, otherRules));
        when(rules.findById(TENANT, targetId)).thenReturn(target(targetId));
        QuotaWatermarks watermarks = watermarksMock();
        AdminQuotaRuleService quotaRules = new AdminQuotaRuleService(rules, mock(UserRepository.class),
                mock(ProjectRepository.class), watermarks, mock(AuditService.class),
                mock(NamedParameterJdbcTemplate.class));

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AlertRuleService.AlertRule alert = new AlertRuleService.AlertRule(UUID.randomUUID(), TENANT, "quota",
                "QUOTA_THRESHOLD", "{\"quotaRuleId\":\"" + targetId + "\"}", BigDecimal.valueOf(50), 60, true, null, 1L,
                Instant.now(), Instant.now());
        doReturn(List.of(alert)).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        AlertEvaluator evaluator = new AlertEvaluator(jdbc, mock(AlertEventDispatcher.class),
                mock(AdminBudgetService.class), quotaRules, new ObjectMapper());

        long before = Mockito.mockingDetails(watermarks).getInvocations().size();
        evaluator.evaluateAll();
        return Mockito.mockingDetails(watermarks).getInvocations().size() - before;
    }

    private static QuotaWatermarks watermarksMock() {
        QuotaWatermarks watermarks = mock(QuotaWatermarks.class);
        when(watermarks.evaluate(any(UUID.class), any(QuotaRule.class)))
                .thenReturn(new QuotaWatermarks.Watermark(BigDecimal.TEN, BigDecimal.TEN, "NORMAL",
                        PricingStatus.COMPLETE, PricingGap.NONE, Instant.now(), Instant.now()));
        return watermarks;
    }

    /**
     * The caller's rule, the alert target, then {@code otherRules} of someone else.
     */
    private static List<QuotaRule> tenantRules(int otherRules) {
        return tenantRules(CALLER, otherRules);
    }

    private static List<QuotaRule> tenantRules(UUID targetId, int otherRules) {
        List<QuotaRule> rules = new ArrayList<>();
        rules.add(rule(targetId));
        for (int i = 0; i < otherRules; i++) {
            rules.add(rule(UUID.randomUUID()));
        }
        return rules;
    }

    private static Optional<QuotaRule> target(UUID id) {
        return Optional.of(rule(id));
    }

    private static QuotaRule rule(UUID scopeId) {
        Instant now = Instant.now();
        return new QuotaRule(UUID.randomUUID(), TENANT, QuotaScopeType.USER, scopeId, QuotaMetric.TOKENS,
                QuotaPeriod.MONTHLY, QuotaAction.ALERT, 1_000_000L, 80, QuotaRuleStatus.ACTIVE, CALLER, 1L, now, now);
    }
}
