package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.Tenant;
import com.miqroera.miqrokey.domain.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic driver for the quota soft-landing evaluator (#684): reconciles every
 * tenant's blocked scopes on a fixed delay (default 1 minute — short enough
 * that a scope stops being served soon after it crosses its limit, long enough
 * that the per-rule watermark aggregation stays cheap).
 *
 * <p>
 * The cycle calls {@link QuotaEnforcementService#reconcile} per tenant through
 * the Spring proxy, so each tenant gets its own transaction and its own
 * after-commit route-refresh signal. One tenant's failure is logged and never
 * aborts the rest of the cycle; the next tick retries it.
 * </p>
 */
@Component
public class QuotaEnforcementScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(QuotaEnforcementScheduler.class);

    private final TenantRepository tenantRepository;
    private final QuotaEnforcementService enforcementService;

    public QuotaEnforcementScheduler(TenantRepository tenantRepository, QuotaEnforcementService enforcementService) {
        this.tenantRepository = tenantRepository;
        this.enforcementService = enforcementService;
    }

    @Scheduled(fixedDelayString = "${miqrokey.quota.enforcement-interval-ms:60000}")
    public void runCycle() {
        int changed = 0;
        int failed = 0;
        for (Tenant tenant : tenantRepository.findAll()) {
            try {
                if (enforcementService.reconcile(tenant.id())) {
                    changed++;
                }
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("Scheduled quota enforcement reconcile failed for tenant {}", tenant.id(), e);
            }
        }
        // Quiet by default: a cycle that changed nothing and failed nothing is
        // the normal case and must not fill the log every minute.
        if (changed > 0 || failed > 0) {
            LOG.info("Scheduled quota enforcement: {} tenant(s) changed, {} failed", changed, failed);
        }
    }
}
