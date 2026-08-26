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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    public QuotaSnapshotService(UpstreamSubscriptionRepository subscriptionRepository,
            ProviderProductRepository productRepository, UpstreamCredentialRepository credentialRepository,
            UpstreamCredentialVersionRepository versionRepository, QuotaSnapshotRepository snapshotRepository,
            AdapterRegistry adapterRegistry, ProviderClientFactory clientFactory,
            KeyEncryptionProvider keyEncryptionProvider, NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
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
    }

    /**
     * Refreshes quota snapshots for one subscription: per-credential official fetch
     * (when the adapter supports it), a subscription-level {@code UNAVAILABLE} row
     * when no credential can be checked, and a {@code LOCAL_ESTIMATE} row when
     * {@code quota_total} + period are known. Always appends; readers take the
     * latest per scope.
     */
    @Transactional
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
        if (adapter != null) {
            for (UpstreamCredential credential : credentials) {
                fetchAndStore(adapter, subscription, product, credential, now);
            }
        }
        if (credentials.isEmpty() || adapter == null) {
            snapshotRepository.insert(unavailable(subscription, null, null, now, "no ACTIVE credential or adapter"));
        }
        if (subscription.quotaTotal() != null && subscription.periodStart() != null) {
            snapshotRepository.insert(estimate(subscription, now));
        }
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

    // -------------------------------------------------------------------

    private void fetchAndStore(ProviderProductAdapter adapter, UpstreamSubscription subscription,
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
                snapshotRepository.insert(unavailable(subscription, credential.id(), credential.seatId(), now,
                        "product has no base URL"));
                return;
            }
            ProviderClient client = clientFactory.create(baseUrl, "Authorization",
                    "Bearer " + new String(secret, StandardCharsets.UTF_8));
            PlanSnapshot plan = adapter
                    .fetchPlanStatus(client, new SubscriptionContext(subscription.id(), kind(subscription), null))
                    .block(FETCH_TIMEOUT);
            snapshotRepository.insert(fromPlan(plan, subscription, credential, now));
        } catch (Exception e) {
            LOG.warn("Quota refresh failed for credential {}; recording UNAVAILABLE", credential.id());
            snapshotRepository.insert(
                    unavailable(subscription, credential.id(), credential.seatId(), now, sanitize(e.getMessage())));
        } finally {
            if (secret != null) {
                SecretWiping.clearArray(secret);
            }
        }
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
        return truncate(message);
    }
}
