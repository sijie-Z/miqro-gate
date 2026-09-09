package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * F60 batch 3 follow-up: fires {@code ADMIN_API_KEY_EXPIRING} events for keys
 * expiring within the next seven days. Inert by default - no alert rule of that
 * type exists unless an admin creates one (opt-in, webhook delivery through the
 * standard dispatcher with per-(key, day) dedupe).
 */
@Service
public class AdminApiKeyExpiryNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(AdminApiKeyExpiryNotifier.class);
    private static final String TYPE = "ADMIN_API_KEY_EXPIRING";
    private static final int LOOKAHEAD_DAYS = 7;

    private final NamedParameterJdbcTemplate jdbc;
    private final AdminApiKeyRepository keyRepository;
    private final AlertEventDispatcher dispatcher;

    public AdminApiKeyExpiryNotifier(NamedParameterJdbcTemplate jdbc, AdminApiKeyRepository keyRepository,
            AlertEventDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.keyRepository = keyRepository;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${miqrokey.alerts.admin-key-expiry-interval-ms:21600000}")
    public void scheduledCheck() {
        try {
            checkNow();
        } catch (Exception e) {
            LOG.warn("Admin API key expiry check failed", e);
        }
    }

    /** One pass: every tenant with an enabled expiring rule is scanned. */
    public void checkNow() {
        List<UUID> tenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM alert_rules WHERE type = :type AND enabled = TRUE",
                new MapSqlParameterSource("type", TYPE), UUID.class);
        Instant now = Instant.now();
        Instant horizon = now.plus(LOOKAHEAD_DAYS, ChronoUnit.DAYS);
        for (UUID tenantId : tenants) {
            List<AdminApiKey> expiring = keyRepository.findActiveExpiringBetween(tenantId, now, horizon);
            for (AdminApiKey key : expiring) {
                dispatcher.notifyAdminKeyExpiring(tenantId, key.id().toString(), key.name(), key.expiresAt());
            }
        }
    }
}
