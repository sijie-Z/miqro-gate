package com.miqroera.miqrokey.controlplane.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Periodic model-catalog re-probe (#350, I8, raw doc 05 note 4): for every
 * (seed tenant, OFFICIAL_API product) pair with an ACTIVE subscription, runs
 * the same probe as the admin endpoint so catalog updates land without manual
 * clicks. Off by default — the vendor guidance is "不要过于频繁" — enable with
 * {@code miqrokey.model-catalog.reprobe.enabled=true} and tune
 * {@code miqrokey.model-catalog.reprobe.cycle-ms} (default 6h). The delay is
 * measured after the previous cycle ends, so a slow upstream cannot stack
 * cycles; per-product failures are counted, logged, and already visible on the
 * product's probe status (V43).
 */
@Component
@ConditionalOnProperty(prefix = "miqrokey.model-catalog.reprobe", name = "enabled", havingValue = "true")
public class ModelCatalogReprobeScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ModelCatalogReprobeScheduler.class);
    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelCatalogProbeService probeService;

    public ModelCatalogReprobeScheduler(NamedParameterJdbcTemplate jdbc, ModelCatalogProbeService probeService) {
        this.jdbc = jdbc;
        this.probeService = probeService;
    }

    @Scheduled(fixedDelayString = "${miqrokey.model-catalog.reprobe.cycle-ms:21600000}")
    public void runCycle() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT DISTINCT s.tenant_id, s.provider_product_id
                FROM upstream_subscriptions s
                JOIN provider_products p ON p.id = s.provider_product_id
                WHERE s.tenant_id = :tenantId AND s.status = 'ACTIVE'
                  AND p.model_catalog_strategy = 'OFFICIAL_API'
                """, new MapSqlParameterSource("tenantId", SEED_TENANT_ID));
        int succeeded = 0;
        int failed = 0;
        for (Map<String, Object> pair : due) {
            UUID tenantId = (UUID) pair.get("tenant_id");
            UUID productId = (UUID) pair.get("provider_product_id");
            try {
                probeService.probe(tenantId, null, productId, AuditContext.human(null, UUID.randomUUID().toString()));
                succeeded++;
            } catch (RuntimeException e) {
                // A single product's upstream failure never aborts the cycle; the
                // probe already recorded the FAILED outcome on the product row.
                failed++;
                LOG.warn("Scheduled model re-probe failed for product {}: {}", productId, e.getMessage());
            }
        }
        if (!due.isEmpty()) {
            LOG.info("Scheduled model re-probe: {} product(s), {} succeeded, {} failed", due.size(), succeeded, failed);
        }
    }
}
