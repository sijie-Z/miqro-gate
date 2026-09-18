package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsagePriceBackfillResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Keeps the frozen price basis and its derived columns converging while nobody
 * is watching (#777, backlog F21-A).
 *
 * <p>
 * The stamping passes only ever look forward: a row that has not been evaluated
 * gets stamped, and a row that has is never revisited. Without a cycle of its
 * own, an event's {@code price_status} stays NULL until an operator remembers
 * to call the admin endpoint, and a verdict the rule has since moved past stays
 * wrong for good — one deployment carried 4027 rows still saying
 * {@code PARTIAL}. The read path has its own as-of fallback and is unaffected
 * either way, but "the stored columns are the auditable record" quietly stops
 * being true.
 * </p>
 *
 * <p>
 * It calls the same service the admin endpoint does, over a recent window,
 * attributed to the seed tenant with no human actor. Off by default — enable
 * with {@code miqrokey.usage-price-reconcile.enabled=true}. The delay is
 * measured after the previous cycle ends, so a slow window cannot stack cycles
 * (same shape as {@link ModelCatalogReprobeScheduler}).
 * </p>
 *
 * <p>
 * The pass only recomputes labels and fills amounts that were never written: no
 * price lookup, no price column touched, no amount moved. A failure is
 * contained — logged at ERROR and counted under
 * {@code miqrokey_control_usage_price_reconcile_total} (tag {@code result}) so
 * a monitoring profile can alert on it — and the next cycle still runs.
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "miqrokey.usage-price-reconcile", name = "enabled", havingValue = "true")
public class UsagePriceReconcileScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(UsagePriceReconcileScheduler.class);

    /** Meter name for scheduled-run outcomes (low-cardinality: success|failure). */
    static final String RUNS_METRIC = "miqrokey_control_usage_price_reconcile_total";

    /** No human session: scheduled runs are attributed to the seed tenant. */
    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Marks the audit trail of an unattended run (no request behind it). */
    static final String SCHEDULED_REQUEST_ID = "scheduled-usage-price-reconcile";

    /**
     * How far back each cycle looks.
     *
     * <p>
     * A constant rather than a knob: the window is what bounds the scan, and a
     * deployment that needs a different range can run the endpoint over it. Wide
     * enough to cover a gap in cycles, narrow enough that the scan stays small.
     * </p>
     */
    private static final Duration WINDOW = Duration.ofHours(48);

    private final UsagePriceBackfillService backfillService;
    private final MeterRegistry meterRegistry;

    public UsagePriceReconcileScheduler(UsagePriceBackfillService backfillService, MeterRegistry meterRegistry) {
        this.backfillService = backfillService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${miqrokey.usage-price-reconcile.cycle-ms:900000}", initialDelayString = "${miqrokey.usage-price-reconcile.initial-delay-ms:120000}")
    public void runCycle() {
        Instant to = Instant.now();
        Instant from = to.minus(WINDOW);
        try {
            // A null actor is the documented shape for a system-initiated run.
            UsagePriceBackfillResult result = backfillService.backfill(SEED_TENANT_ID, null, from, to,
                    SCHEDULED_REQUEST_ID);
            count("success");
            LOG.info("Scheduled usage price reconcile {}..{}: scanned={}, baseCostFilled={}, reclassified={}", from, to,
                    result.scanned(), result.baseCostFilled(), result.reclassified());
        } catch (RuntimeException e) {
            // Contained on purpose: a failed cycle must never stop the next one.
            count("failure");
            LOG.error("Scheduled usage price reconcile failed: {}", e.getMessage(), e);
        }
    }

    private void count(String result) {
        meterRegistry.counter(RUNS_METRIC, "result", result).increment();
    }
}
