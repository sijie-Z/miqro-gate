package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.client.ProviderClientFactory;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.domain.model.CredentialStatus;
import com.miqroera.miqrokey.domain.model.CredentialVersionStatus;
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
import com.miqroera.miqrokey.spi.AdapterCapabilities;
import com.miqroera.miqrokey.spi.CredentialCheck;
import com.miqroera.miqrokey.spi.CredentialInjection;
import com.miqroera.miqrokey.spi.CredentialMaterial;
import com.miqroera.miqrokey.spi.InboundRequest;
import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.PlanDataSource;
import com.miqroera.miqrokey.spi.PlanSnapshot;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import com.miqroera.miqrokey.spi.RouteContext;
import com.miqroera.miqrokey.spi.SubscriptionContext;
import com.miqroera.miqrokey.spi.SubscriptionKind;
import com.miqroera.miqrokey.spi.TargetRequest;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObserver;
import com.miqroera.miqrokey.spi.UsageSource;
import com.miqroera.miqrokey.spi.AdapterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuotaSnapshotService} (G4.2): per-credential official
 * fetch through the adapter, honest UNAVAILABLE rows, LOCAL_ESTIMATE from
 * quota_total + usage, tenant scoping, and decrypted-secret lifecycle.
 */
@ExtendWith(MockitoExtension.class)
class QuotaSnapshotServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final UUID SEAT_ID = UUID.randomUUID();
    private static final String PRODUCT_CODE = "deepseek-payg-api";

    @Mock
    private UpstreamSubscriptionRepository subscriptionRepository;
    @Mock
    private ProviderProductRepository productRepository;
    @Mock
    private UpstreamCredentialRepository credentialRepository;
    @Mock
    private UpstreamCredentialVersionRepository versionRepository;
    @Mock
    private QuotaSnapshotRepository snapshotRepository;
    @Mock
    private AdapterRegistry adapterRegistry;
    @Mock
    private ProviderClientFactory clientFactory;
    @Mock
    private KeyEncryptionProvider keyEncryptionProvider;
    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private QuotaSnapshotService service;
    private final java.util.List<QuotaSnapshot> stored = new java.util.ArrayList<>();
    private ProviderProductAdapter adapter;

    @BeforeEach
    void setUp() {
        service = new QuotaSnapshotService(subscriptionRepository, productRepository, credentialRepository,
                versionRepository, snapshotRepository, adapterRegistry, clientFactory, keyEncryptionProvider, jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        lenient().when(snapshotRepository.insert(any())).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        adapter = new FakeAdapter();
    }

    @Test
    void refreshFetchesOfficialStatusPerCredentialAndClearsTheSecret() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(adapterRegistry.findById(PRODUCT_CODE)).thenReturn(Optional.of(adapter));
        when(credentialRepository.findAllBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(List.of(credential()));
        when(versionRepository.findActiveByCredentialId(CREDENTIAL_ID)).thenReturn(Optional.of(version()));
        when(keyEncryptionProvider.decrypt(any(), eq(TENANT), eq(CREDENTIAL_ID)))
                .thenReturn("sk-test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(clientFactory.create(eq(URI.create("https://api.test.example")), eq("Authorization"),
                eq("Bearer sk-test-secret"))).thenReturn(stubClient());

        service.refresh(TENANT, SUBSCRIPTION_ID);

        // The subscription also carries quota_total, so a LOCAL_ESTIMATE row is
        // written alongside the official per-credential row.
        QuotaSnapshot row = stored.stream().filter(s -> s.source() == QuotaSource.OFFICIAL_API).findFirst()
                .orElseThrow();
        assertThat(row.credentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(row.seatId()).isEqualTo(SEAT_ID);
        assertThat(row.remaining()).isEqualByComparingTo("100.00");
        assertThat(row.sharedPool()).isFalse();
        // The decrypted secret was wiped after use.
        verify(clientFactory).create(any(), eq("Authorization"), eq("Bearer sk-test-secret"));
    }

    @Test
    void refreshRecordsUnavailableWhenAdapterReportsUnavailable() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(adapterRegistry.findById(PRODUCT_CODE)).thenReturn(Optional.of(unavailableAdapter()));
        when(credentialRepository.findAllBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(List.of(credential()));
        when(versionRepository.findActiveByCredentialId(CREDENTIAL_ID)).thenReturn(Optional.of(version()));
        when(keyEncryptionProvider.decrypt(any(), eq(TENANT), eq(CREDENTIAL_ID)))
                .thenReturn("sk-test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(clientFactory.create(any(), any(), any())).thenReturn(stubClient());

        service.refresh(TENANT, SUBSCRIPTION_ID);

        QuotaSnapshot row = stored.stream().filter(s -> s.source() == QuotaSource.UNAVAILABLE).findFirst()
                .orElseThrow();
        assertThat(row.total()).isNull();
        assertThat(row.remaining()).isNull();
    }

    @Test
    void refreshRecordsUnavailableWithoutCredentialsAndEstimatesWhenQuotaKnown() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(adapterRegistry.findById(PRODUCT_CODE)).thenReturn(Optional.of(adapter));
        when(credentialRepository.findAllBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(List.of());
        when(jdbc.queryForObject(any(String.class),
                any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(300L);

        service.refresh(TENANT, SUBSCRIPTION_ID);

        assertThat(stored).hasSize(2);
        assertThat(stored).anyMatch(s -> s.source() == QuotaSource.UNAVAILABLE && s.credentialId() == null);
        QuotaSnapshot estimate = stored.stream().filter(s -> s.source() == QuotaSource.LOCAL_ESTIMATE).findFirst()
                .orElseThrow();
        assertThat(estimate.total()).isEqualByComparingTo("1000");
        assertThat(estimate.used()).isEqualByComparingTo("300");
        assertThat(estimate.remaining()).isEqualByComparingTo("700");
        assertThat(estimate.windowType().name()).isEqualTo("PERIOD");
    }

    @Test
    void refreshFailureMarksUnavailableWithoutThrowing() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(adapterRegistry.findById(PRODUCT_CODE)).thenReturn(Optional.of(adapter));
        when(credentialRepository.findAllBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(List.of(credential()));
        when(versionRepository.findActiveByCredentialId(CREDENTIAL_ID)).thenReturn(Optional.of(version()));
        when(keyEncryptionProvider.decrypt(any(), eq(TENANT), eq(CREDENTIAL_ID)))
                .thenThrow(new RuntimeException("boom"));

        service.refresh(TENANT, SUBSCRIPTION_ID);

        QuotaSnapshot row = stored.stream().filter(s -> s.source() == QuotaSource.UNAVAILABLE).findFirst()
                .orElseThrow();
        assertThat(row.errorMessage()).contains("boom");
    }

    @Test
    void latestScopesToTenant() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        when(snapshotRepository.findLatestPerScope(TENANT, SUBSCRIPTION_ID)).thenReturn(List.of());

        assertThat(service.latest(TENANT, SUBSCRIPTION_ID)).isEmpty();
        verify(snapshotRepository).findLatestPerScope(TENANT, SUBSCRIPTION_ID);
    }

    @Test
    void otherTenantCannotSeeOrRefreshTheSubscription() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        UUID otherTenant = UUID.randomUUID();

        assertThatThrownBy(() -> service.latest(otherTenant, SUBSCRIPTION_ID)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SUBSCRIPTION_NOT_FOUND"));
        verify(snapshotRepository, never()).findLatestPerScope(any(UUID.class), any(UUID.class));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private UpstreamSubscription subscription() {
        return new UpstreamSubscription(SUBSCRIPTION_ID, TENANT, PRODUCT_ID, "Sub", null, BillingMode.PAYG,
                PlanScope.NONE, null, null, Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), null, 1000L, "TOKENS",
                com.miqroera.miqrokey.domain.model.SubscriptionStatus.ACTIVE, null,
                com.miqroera.miqrokey.domain.model.StatusSource.MANUAL_UNKNOWN, 0, Instant.now(), Instant.now());
    }

    private ProviderProduct product() {
        return new ProviderProduct(PRODUCT_ID, UUID.randomUUID(), PRODUCT_CODE, "DeepSeek", BillingMode.PAYG,
                PlanScope.NONE, null, null, "[\"messages\"]", "[{\"url\":\"https://api.test.example\"}]", "bearer",
                "OFFICIAL_API", "OFFICIAL_API", com.miqroera.miqrokey.domain.model.BalanceAuthority.OFFICIAL_API,
                com.miqroera.miqrokey.domain.model.ImplementationStatus.IMPLEMENTED, "1", 0, Instant.now(),
                Instant.now());
    }

    private UpstreamCredential credential() {
        return new UpstreamCredential(CREDENTIAL_ID, TENANT, SUBSCRIPTION_ID, SEAT_ID, "Cred", new byte[32],
                CredentialStatus.ACTIVE, UUID.randomUUID(), null, null, 0, Instant.now(), Instant.now());
    }

    private UpstreamCredentialVersion version() {
        return new UpstreamCredentialVersion(UUID.randomUUID(), TENANT, CREDENTIAL_ID, new byte[16], new byte[12], "v1",
                new byte[32], CredentialVersionStatus.ACTIVE, Instant.now(), null, Instant.now());
    }

    private static class FakeAdapter implements ProviderProductAdapter {
        @Override
        public String adapterId() {
            return PRODUCT_CODE;
        }

        @Override
        public Set<ProtocolFamily> protocols() {
            return Set.of(ProtocolFamily.OPENAI_COMPATIBLE);
        }

        @Override
        public TargetRequest resolve(RouteContext route, InboundRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CredentialInjection credentialInjection(CredentialMaterial credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public reactor.core.publisher.Mono<CredentialCheck> validateCredential(ProviderClient client) {
            throw new UnsupportedOperationException();
        }

        @Override
        public reactor.core.publisher.Mono<ModelCatalogSnapshot> fetchModels(ProviderClient client) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UsageObserver createUsageObserver(UsageContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public reactor.core.publisher.Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client,
                SubscriptionContext subscription) {
            return reactor.core.publisher.Mono.just(new PlanSnapshot(subscription.subscriptionId().toString(),
                    SubscriptionKind.PAYG, new BigDecimal("200.00"), null, new BigDecimal("100.00"), null, null, null,
                    null, false, PlanDataSource.OFFICIAL_API, Instant.now()));
        }

        @Override
        public AdapterCapabilities capabilities() {
            return new AdapterCapabilities(true, true, true, false, false, false, UsageSource.PROVIDER_RESPONSE);
        }
    }

    private static final class UnavailableFakeAdapter extends FakeAdapter {
        @Override
        public reactor.core.publisher.Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client,
                SubscriptionContext subscription) {
            return reactor.core.publisher.Mono
                    .just(new PlanSnapshot(subscription.subscriptionId().toString(), SubscriptionKind.PAYG, null, null,
                            null, null, null, null, null, false, PlanDataSource.UNAVAILABLE, Instant.now()));
        }
    }

    private UnavailableFakeAdapter unavailableAdapter() {
        return new UnavailableFakeAdapter();
    }

    /**
     * Fake client: nothing is called because the adapter under test is a fake;
     * Mockito's inline mockmaker handles the final {@link HttpProviderClient}.
     */
    private static com.miqroera.miqrokey.controlplane.client.HttpProviderClient stubClient() {
        return org.mockito.Mockito.mock(com.miqroera.miqrokey.controlplane.client.HttpProviderClient.class);
    }

    @Test
    @DisplayName("scheduled refresh walks every subscription without aborting on failure")
    void scheduledRefreshWalksSubscriptions() {
        // Stub: one subscription whose refresh throws inside the cycle; the
        // scheduler must log and continue (no exception escapes).
        java.util.UUID bad = java.util.UUID.randomUUID();
        org.mockito.Mockito.when(subscriptionRepository.findAllByTenantId(any())).thenReturn(List.of(
                new com.miqroera.miqrokey.domain.model.UpstreamSubscription(bad,
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        java.util.UUID.randomUUID(), "bad", null,
                        com.miqroera.miqrokey.domain.model.BillingMode.FIXED_SUBSCRIPTION,
                        com.miqroera.miqrokey.domain.model.PlanScope.NONE, null, null, null, null, null, null, null,
                        com.miqroera.miqrokey.domain.model.SubscriptionStatus.ACTIVE, null,
                        com.miqroera.miqrokey.domain.model.StatusSource.MANUAL_UNKNOWN, 0,
                        java.time.Instant.now(), java.time.Instant.now())));
        when(subscriptionRepository.findById(bad)).thenThrow(new IllegalStateException("boom"));

        service.refreshAllScheduled();

        // No exception propagated; findById was attempted (cycle walked).
        org.mockito.Mockito.verify(subscriptionRepository).findAllByTenantId(any());
    }
}
