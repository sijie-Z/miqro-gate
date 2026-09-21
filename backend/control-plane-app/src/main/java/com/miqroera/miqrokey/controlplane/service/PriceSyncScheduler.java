package com.miqroera.miqrokey.controlplane.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled official-price sync (issue #708, backlog F08): pulls the public
 * price source on a 24h cycle and appends the changed quotes as
 * {@code source=OFFICIAL} snapshots, so a vendor price move reaches our catalog
 * without an admin click. Off by default — enable with
 * {@code miqrokey.price-sync.auto.enabled=true} and tune
 * {@code miqrokey.price-sync.auto.cycle-ms} (default 24h). The delay is
 * measured after the previous cycle ends, so a slow source cannot stack cycles
 * (same shape as {@link ModelCatalogReprobeScheduler}).
 *
 * <p>
 * It reuses the admin pipeline through
 * {@link AdminPriceSyncService#syncPreservingManual}, which never replaces an
 * existing {@code MANUAL} snapshot: a differing quote is kept out of the
 * catalog and reported under {@code conflicts} in the run report and the audit
 * summary (the admin-triggered endpoint keeps its documented overwrite
 * semantics).
 * </p>
 *
 * <p>
 * A fetch failure writes nothing and is never silent: the service records a
 * {@code PRICE_SYNC_FAILED} audit event, this scheduler logs the failure at
 * ERROR, and the run is counted under
 * {@code miqrokey_control_price_sync_auto_total} (tag {@code result}) so a
 * monitoring profile can alert on it. The failure is contained so the next
 * cycle still runs.
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "miqrokey.price-sync.auto", name = "enabled", havingValue = "true")
public class PriceSyncScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PriceSyncScheduler.class);

    /** Meter name for scheduled-run outcomes (low-cardinality: success|failure). */
    static final String RUNS_METRIC = "miqrokey_control_price_sync_auto_total";

    /** No human session: scheduled runs are attributed to the seed tenant. */
    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Marks the audit trail of an unattended run (no request behind it). */
    static final String SCHEDULED_REQUEST_ID = "scheduled-price-sync";

    private final AdminPriceSyncService syncService;
    private final MeterRegistry meterRegistry;

    public PriceSyncScheduler(AdminPriceSyncService syncService, MeterRegistry meterRegistry) {
        this.syncService = syncService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${miqrokey.price-sync.auto.cycle-ms:86400000}", initialDelayString = "${miqrokey.price-sync.auto.initial-delay-ms:60000}")
    public void runCycle() {
        try {
            Map<String, Object> report = syncService.syncPreservingManual(SEED_TENANT_ID,
                    AuditContext.human(null, SCHEDULED_REQUEST_ID));
            count("success");
            LOG.info("Scheduled price sync: written={}, unchanged={}, conflicts={}, unmatched={}, syncedAt={}",
                    report.get("written"), report.get("unchanged"), sizeOf(report.get("conflicts")),
                    sizeOf(report.get("unmatched")), report.get("syncedAt"));
        } catch (RuntimeException e) {
            // Contained on purpose: the service already recorded PRICE_SYNC_FAILED
            // (zero writes); a failed cycle must never stop the next one.
            count("failure");
            LOG.error("Scheduled price sync failed: {}", e.getMessage(), e);
        }
    }

    private void count(String result) {
        meterRegistry.counter(RUNS_METRIC, "result", result).increment();
    }

    private static int sizeOf(Object reportEntry) {
        return reportEntry instanceof List<?> list ? list.size() : 0;
    }
}
