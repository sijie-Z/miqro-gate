package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.domain.model.CredentialStatus;
import com.miqroera.miqrokey.domain.model.PlanScope;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.model.UpstreamCredential;
import com.miqroera.miqrokey.domain.model.UpstreamCredentialVersion;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.repository.QuotaSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialVersionRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import com.miqroera.miqrokey.domain.usage.QuotaSnapshot;
import com.miqroera.miqrokey.domain.usage.QuotaSource;
import com.miqroera.miqrokey.domain.usage.QuotaUnit;
import com.miqroera.miqrokey.domain.usage.QuotaWindow;
import com.miqroera.miqrokey.spi.AdapterRegistry;
import com.miqroera.miqrokey.spi.PlanDataSource;
import com.miqroera.miqrokey.spi.PlanSnapshot;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import com.miqroera.miqrokey.spi.SubscriptionContext;
import com.miqroera.miqrokey.spi.SubscriptionKind;
import com.miqroera.miqrokey.controlplane.client.ProviderClientFactory;
import com.miqroera.miqrokey.controlplane.dto.SubscriptionQuotaView;
import com.miqroera.miqrokey.controlplane.dto.QuotaEntryView;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Quota/Plan status snapshots (G4.2, {@code quota_snapshots} V9). A refresh
 * walks one subscription's ACTIVE credentials and asks the product's adapter
 * for the official Plan status (per-credential fetch, so multi-key plans get
 * one snapshot per member key); products without an official balance API
 * produce {@code UNAVAILABLE} rows — never invented values. When the
 * subscription carries an admin-recorded {@code quota_total} and a period
 * start, a separate {@code LOCAL_ESTIMATE} row is written from local usage
 * tokens so the UI can show an estimate clearly labeled as such.
 *
 * <p>
 * Decrypted secrets exist only inside this call, are bound to the credential
 * scoped {@link ProviderClient}, and are zero-filled before returning. Errors
 * never leak the secret or the upstream URL into the snapshot or logs.
 * </p>
 */
@Service
public class QuotaSnapshotService {

    private static final Logger LOG = LoggerFactory.getLogger(QuotaSnapshotService.class);

    /** Upper bound for a single adapter balance fetch. */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);

    private final UpstreamSubscriptionRepository subscriptionRepository;
    private final ProviderProductRepository productRepository;
    private final UpstreamCredentialRepository credentialRepository;
    private final UpstreamCredentialVersionRepository versionRepository;
    private final QuotaSnapshotRepository snapshotRepository;
    private final AdapterRegistry adapterRegistry;
    private final ProviderClientFactory clientFactory;
    private final KeyEncryptionProvider keyEncryptionProvider;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    /**
     * Short write transaction for the collected snapshot rows (#728). Rows are
     * gathered first (the provider fetches are blocking HTTP and must never run
     * inside a transaction) and inserted here in one unit of work — the same path
     * serves the admin trigger and the scheduled walk.
     */
    private final TransactionTemplate transactionTemplate;

    public QuotaSnapshotService(UpstreamSubscriptionRepository subscriptionRepository,
            ProviderProductRepository productRepository, UpstreamCredentialRepository credentialRepository,
            UpstreamCredentialVersionRepository versionRepository, QuotaSnapshotRepository snapshotRepository,
            AdapterRegistry adapterRegistry, ProviderClientFactory clientFactory,
            KeyEncryptionProvider keyEncryptionProvider, NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
            MeterRegistry meterRegistry, PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.subscriptionRepository = subscriptionRepository;
        this.productRepository = productRepository;
        this.credentialRepository = credentialRepository;
        this.versionRepository = versionRepository;
        this.snapshotRepository = snapshotRepository;
        this.adapterRegistry = adapterRegistry;
        this.clientFactory = clientFactory;
        this.keyEncryptionProvider = keyEncryptionProvider;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        // Low-cardinality only: adapterId is a stable product identifier; user,
        // key and model values are never metric labels (config §8). Counters are
        // looked up per adapter/result at the increment site so the recorded
        // series carries the real label value instead of a placeholder.
        this.meterRegistry = meterRegistry;
    }

    /**
     * Periodic refresh: every subscription gets its snapshots refreshed on a fixed
     * delay. Failures are logged per subscription, never aborting the cycle.
     */
    @Scheduled(fixedDelayString = "${miqrokey.quota.refresh-interval-ms:900000}")
    public void refreshAllScheduled() {
        for (UpstreamSubscription subscription : subscriptionRepository.findAllByTenantId(SEED_TENANT_ID)) {
            try {
                refresh(SEED_TENANT_ID, subscription.id());
            } catch (Exception e) {
                LOG.warn("Scheduled quota refresh failed for subscription {}", subscription.id(), e);
            }
        }
    }

    /** Seed tenant of the single-tenant deployment. */
    private static final java.util.UUID SEED_TENANT_ID = java.util.UUID
            .fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Refreshes quota snapshots for one subscription: per-credential official fetch
     * (when the adapter supports it), a subscription-level {@code UNAVAILABLE} row
     * when no credential can be checked, and a {@code LOCAL_ESTIMATE} row when
     * {@code quota_total} + period are known. Always appends; readers take the
     * latest per scope.
     *
     * <p>
     * #728: the provider fetches are blocking HTTP calls and deliberately run
     * <em>outside</em> any database transaction — a slow upstream must never pin a
     * pooled connection (previously this method was {@code @Transactional} and held
     * one for up to N×20s). The snapshot rows are collected first and written in
     * one short transaction, so the admin trigger and the scheduled walk take the
     * identical path (the scheduled self-invocation used to silently skip the
     * transaction annotation).
     * </p>
     */
    public void refresh(UUID tenantId, UUID subscriptionId) {
        UpstreamSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                        "Subscription not found or not visible"));
        if (!subscription.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                    "Subscription not found or not visible");
        }
        ProviderProduct product = productRepository.findById(subscription.providerProductId()).orElse(null);
        ProviderProductAdapter adapter = product != null
                ? adapterRegistry.findById(product.productCode()).orElse(null)
                : null;
        Instant now = Instant.now();

        List<UpstreamCredential> credentials = credentialRepository.findAllBySubscriptionId(subscriptionId).stream()
                .filter(c -> c.status() == CredentialStatus.ACTIVE).toList();
        List<QuotaSnapshot> rows = new ArrayList<>();
        if (adapter != null) {
            for (UpstreamCredential credential : credentials) {
                rows.add(fetch(adapter, subscription, product, credential, now));
            }
        }
        if (credentials.isEmpty() || adapter == null) {
            rows.add(unavailable(subscription, null, null, now, "no ACTIVE credential or adapter"));
        }
        if (subscription.quotaTotal() != null && subscription.periodStart() != null) {
            rows.add(estimate(subscription, now));
        }
        transactionTemplate.executeWithoutResult(status -> rows.forEach(snapshotRepository::insert));
    }

    /** Latest snapshot per scope for the subscription (admin view). */
    public List<QuotaSnapshot> latest(UUID tenantId, UUID subscriptionId) {
        UpstreamSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                        "Subscription not found or not visible"));
        if (!subscription.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                    "Subscription not found or not visible");
        }
        return snapshotRepository.findLatestPerScope(tenantId, subscriptionId);
    }

    /**
     * Tenant-wide quota status for the external billing API: latest snapshot per
     * scope, grouped by subscription. Subscriptions without snapshots appear with
     * an empty list. Only quota numbers and their authority level are exposed —
     * internal error hints and provider status payloads stay on the admin surface.
     */
    public List<SubscriptionQuotaView> quotaStatus(UUID tenantId) {
        Map<UUID, UpstreamSubscription> subscriptions = subscriptionRepository.findAllByTenantId(tenantId).stream()
                .collect(Collectors.toMap(UpstreamSubscription::id, s -> s));
        Map<UUID, List<QuotaSnapshot>> bySubscription = snapshotRepository.findLatestForTenant(tenantId).stream()
                .collect(Collectors.groupingBy(QuotaSnapshot::subscriptionId));
        return subscriptions.values().stream()
                .sorted(Comparator.comparing(UpstreamSubscription::name, Comparator.nullsLast(String::compareTo)))
                .map(s -> new SubscriptionQuotaView(s.id(), s.name(), bySubscription.getOrDefault(s.id(), List.of())
                        .stream().map(QuotaSnapshotService::toEntry).toList()))
                .toList();
    }

    private static QuotaEntryView toEntry(QuotaSnapshot snapshot) {
        return new QuotaEntryView(snapshot.seatId(), snapshot.credentialId(), snapshot.windowType(), snapshot.total(),
                snapshot.used(), snapshot.remaining(), snapshot.unit(), snapshot.sharedPool(), snapshot.source(),
                snapshot.syncedAt());
    }

    // -------------------------------------------------------------------

    /**
     * Fetches one credential's official plan status and maps it to a snapshot row
     * (never throws: failures become an honest {@code UNAVAILABLE} row). Blocking
     * HTTP — the caller guarantees no transaction is active (#728).
     */
    private QuotaSnapshot fetch(ProviderProductAdapter adapter, UpstreamSubscription subscription,
            ProviderProduct product, UpstreamCredential credential, Instant now) {
        byte[] secret = null;
        try {
            UpstreamCredentialVersion active = versionRepository.findActiveByCredentialId(credential.id())
                    .orElseThrow(() -> new IllegalStateException("no ACTIVE credential version"));
            secret = keyEncryptionProvider.decrypt(
                    new EncryptedSecret(active.encryptedSecret(), active.nonce(), active.encryptionKeyVersion()),
                    subscription.tenantId(), credential.id());
            URI baseUrl = firstBaseUrl(product.baseUrlTemplates());
            if (baseUrl == null) {
                return unavailable(subscription, credential.id(), credential.seatId(), now, "product has no base URL");
            }
            ProviderClient client = clientFactory.create(baseUrl, "Authorization",
                    "Bearer " + new String(secret, StandardCharsets.UTF_8));
            meterRegistry.counter("miqrokey_control_provider_calls_total", "adapter_id", adapter.adapterId())
                    .increment();
            PlanSnapshot plan = adapter
                    .fetchPlanStatus(client, new SubscriptionContext(subscription.id(), kind(subscription), null))
                    .block(FETCH_TIMEOUT);
            countRefresh("success");
            return fromPlan(plan, subscription, credential, now);
        } catch (Exception e) {
            countRefresh("failure");
            LOG.warn("Quota refresh failed for credential {}; recording UNAVAILABLE", credential.id());
            return unavailable(subscription, credential.id(), credential.seatId(), now, sanitize(e.getMessage()));
        } finally {
            if (secret != null) {
                SecretWiping.clearArray(secret);
            }
        }
    }

    private void countRefresh(String result) {
        meterRegistry.counter("miqrokey_control_quota_refresh_total", "result", result).increment();
    }

    private static QuotaSnapshot fromPlan(PlanSnapshot plan, UpstreamSubscription subscription,
            UpstreamCredential credential, Instant now) {
        QuotaSource source = plan.source() == PlanDataSource.OFFICIAL_API
                ? QuotaSource.OFFICIAL_API
                : QuotaSource.UNAVAILABLE;
        return new QuotaSnapshot(UUID.randomUUID(), subscription.tenantId(), subscription.id(), credential.seatId(),
                credential.id(), windowOf(plan), plan.total(), plan.used(), plan.remaining(), unitOf(subscription),
                plan.sharedPool(), source, null, now, null, now);
    }

    private QuotaSnapshot estimate(UpstreamSubscription subscription, Instant now) {
        long usedTokens = usedTokensSince(subscription, now);
        BigDecimal used = BigDecimal.valueOf(usedTokens);
        BigDecimal total = BigDecimal.valueOf(subscription.quotaTotal());
        BigDecimal remaining = total.subtract(used).max(BigDecimal.ZERO);
        return new QuotaSnapshot(UUID.randomUUID(), subscription.tenantId(), subscription.id(), null, null,
                QuotaWindow.PERIOD, total, used, remaining, unitOf(subscription), false, QuotaSource.LOCAL_ESTIMATE,
                null, now, null, now);
    }

    private static QuotaSnapshot unavailable(UpstreamSubscription subscription, UUID credentialId, UUID seatId,
            Instant now, String error) {
        return new QuotaSnapshot(UUID.randomUUID(), subscription.tenantId(), subscription.id(), seatId, credentialId,
                QuotaWindow.UNKNOWN, null, null, null, unitOf(subscription), false, QuotaSource.UNAVAILABLE, null, now,
                truncate(error), now);
    }

    private static QuotaUnit unitOf(UpstreamSubscription subscription) {
        if (subscription.quotaUnit() == null) {
            return QuotaUnit.UNKNOWN;
        }
        return switch (subscription.quotaUnit().toUpperCase()) {
            case "POINTS" -> QuotaUnit.POINTS;
            case "TOKENS" -> QuotaUnit.TOKENS;
            case "REQUESTS" -> QuotaUnit.REQUESTS;
            case "CNY", "USD", "CURRENCY" -> QuotaUnit.CURRENCY;
            default -> QuotaUnit.UNKNOWN;
        };
    }

    private static QuotaWindow windowOf(PlanSnapshot plan) {
        if (plan.periodStart() != null || plan.periodEnd() != null) {
            return QuotaWindow.PERIOD;
        }
        return QuotaWindow.UNKNOWN;
    }

    private static SubscriptionKind kind(UpstreamSubscription subscription) {
        if (subscription.billingMode() == BillingMode.PAYG) {
            return SubscriptionKind.PAYG;
        }
        return switch (subscription.planScope() == null ? PlanScope.NONE : subscription.planScope()) {
            case TEAM -> SubscriptionKind.TEAM_PLAN;
            case ENTERPRISE -> SubscriptionKind.ENTERPRISE_PLAN;
            default -> SubscriptionKind.INDIVIDUAL_PLAN;
        };
    }

    /**
     * Local estimate input: tokens (input + output) attributed to the
     * subscription's credentials since the period start. Cache-read tokens are
     * included in the provider's own prompt accounting, so no extra weighting is
     * applied.
     */
    private long usedTokensSince(UpstreamSubscription subscription, Instant now) {
        Long used = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(input_tokens, prompt_tokens)) + SUM(COALESCE(output_tokens,
                       completion_tokens)), 0)
                FROM usage_event ue
                WHERE ue.tenant_id = :tenantId
                  AND ue.occurred_at >= :periodStart AND ue.occurred_at < :now
                  AND ue.credential_id IN (SELECT id FROM upstream_credentials
                                           WHERE subscription_id = :subscriptionId)
                """,
                new MapSqlParameterSource("tenantId", subscription.tenantId())
                        .addValue("periodStart", Timestamp.from(subscription.periodStart()))
                        .addValue("now", Timestamp.from(now)).addValue("subscriptionId", subscription.id()),
                Long.class);
        return used != null ? used : 0;
    }

    private URI firstBaseUrl(String baseUrlTemplates) {
        if (baseUrlTemplates == null || baseUrlTemplates.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(baseUrlTemplates);
            if (node.isArray() && !node.isEmpty() && node.get(0).hasNonNull("url")) {
                return URI.create(node.get(0).get("url").asText());
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** Snapshot errors are short sanitized hints; never URLs, secrets or bodies. */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static String sanitize(String message) {
        String truncated = truncate(message);
        if (truncated == null) {
            return null;
        }
        // Never persist upstream hosts/URLs even inside error hints
        // (adapter/client exceptions often embed the target URI).
        return truncated.replaceAll("https?://[^\s,;]+", "[url]");
    }
}
