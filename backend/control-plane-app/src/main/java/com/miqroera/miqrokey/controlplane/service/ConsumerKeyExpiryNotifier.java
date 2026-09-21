package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
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
 * Issue #322: fires {@code CONSUMER_KEY_EXPIRING} events for consumers expiring
 * within the next seven days — the direct mirror of
 * {@link AdminApiKeyExpiryNotifier}. Inert by default: no alert rule of that
 * type exists unless an admin creates one (opt-in, webhook delivery through the
 * standard dispatcher with per-(consumer, day) dedupe).
 */
@Service
public class ConsumerKeyExpiryNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerKeyExpiryNotifier.class);
    private static final String TYPE = "CONSUMER_KEY_EXPIRING";
    private static final int LOOKAHEAD_DAYS = 7;

    private final NamedParameterJdbcTemplate jdbc;
    private final ApiConsumerRepository consumerRepository;
    private final AlertEventDispatcher dispatcher;

    public ConsumerKeyExpiryNotifier(NamedParameterJdbcTemplate jdbc, ApiConsumerRepository consumerRepository,
            AlertEventDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.consumerRepository = consumerRepository;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${miqrokey.alerts.consumer-key-expiry-interval-ms:21600000}")
    public void scheduledCheck() {
        try {
            checkNow();
        } catch (Exception e) {
            LOG.warn("Consumer key expiry check failed", e);
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
            List<ApiConsumer> expiring = consumerRepository.findActiveExpiringBetween(tenantId, now, horizon);
            for (ApiConsumer consumer : expiring) {
                dispatcher.notifyConsumerKeyExpiring(tenantId, consumer.id().toString(), consumer.name(),
                        consumer.expiresAt());
            }
        }
    }
}
