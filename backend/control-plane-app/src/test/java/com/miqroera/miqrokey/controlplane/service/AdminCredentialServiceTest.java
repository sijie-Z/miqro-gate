package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.client.ProviderClientFactory;
import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.controlplane.dto.AdminCredentialCreateRequest;
import com.miqroera.miqrokey.controlplane.dto.CredentialDetailView;
import com.miqroera.miqrokey.controlplane.dto.CredentialView;
import com.miqroera.miqrokey.controlplane.dto.RotateCredentialRequest;
import com.miqroera.miqrokey.controlplane.dto.ValidateCredentialRequest;
import com.miqroera.miqrokey.controlplane.dto.ValidateCredentialResponse;
import com.miqroera.miqrokey.controlplane.service.credential.FormatCredentialValidator;
import com.miqroera.miqrokey.domain.crypto.CredentialFingerprint;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.spi.AdapterRegistry;
import com.miqroera.miqrokey.spi.CredentialCheck;
import com.miqroera.miqrokey.controlplane.client.HttpProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import com.miqroera.miqrokey.domain.model.CredentialStatus;
import com.miqroera.miqrokey.domain.model.CredentialVersionStatus;
import com.miqroera.miqrokey.domain.model.PlanScope;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import reactor.core.publisher.Mono;
import com.miqroera.miqrokey.domain.model.StatusSource;
import com.miqroera.miqrokey.domain.model.SubscriptionStatus;
import com.miqroera.miqrokey.domain.model.UpstreamCredential;
import com.miqroera.miqrokey.domain.model.UpstreamCredentialVersion;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialVersionRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCredentialService}: masked-in/masked-out secrets,
 * validate-never-writes, and the atomic rotate ordering that satisfies
 * {@code uq_credential_versions_one_active} (demote old ACTIVE before inserting
 * the new one).
 */
@ExtendWith(MockitoExtension.class)
class AdminCredentialServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.randomUUID();
    private static final String SECRET = "sk-test-abcdef1234567890";

    @Mock
    private UpstreamCredentialRepository credentialRepository;
    @Mock
    private UpstreamCredentialVersionRepository versionRepository;
    @Mock
    private UpstreamSubscriptionRepository subscriptionRepository;
    @Mock
    private KeyEncryptionProvider keyEncryptionProvider;
    @Mock
    private AuditService auditService;
    @Mock
    private AdapterRegistry adapterRegistry;
    @Mock
    private ProviderClientFactory clientFactory;
    @Mock
    private ProviderProductRepository productRepository;

    private final AuthProperties authProperties = new AuthProperties();
    private AdminCredentialService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new AdminCredentialService(credentialRepository, versionRepository, subscriptionRepository,
                keyEncryptionProvider, new FormatCredentialValidator(), auditService, authProperties,
                RouteRefreshPublisher.NONE, adapterRegistry, clientFactory, productRepository);
        admin = new User(UUID.randomUUID(), TENANT, "admin", "Admin", new byte[32], UserRole.SYSTEM_ADMIN,
                UserStatus.ACTIVE, false, 0, null, null, 0L, Instant.now(), Instant.now());
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    void createPersistsFingerprintAndEncryptedSecretOnly() {
        when(subscriptionRepository.findById(SUBSCRIPTION)).thenReturn(Optional.of(subscription()));
        when(keyEncryptionProvider.encrypt(any(), eq(TENANT), any()))
                .thenReturn(new EncryptedSecret(new byte[]{1, 2, 3}, new byte[]{4, 5}, "v1"));

        CredentialView view = service.create(admin, new AdminCredentialCreateRequest("prod-key", SUBSCRIPTION, SECRET),
                "req-1");

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.name()).isEqualTo("prod-key");
        assertThat(view.fingerprintPrefix())
                .isEqualTo(CredentialFingerprint.hexPrefix(CredentialFingerprint.sha256(SECRET), 8));

        ArgumentCaptor<UpstreamCredential> credCaptor = ArgumentCaptor.forClass(UpstreamCredential.class);
        verify(credentialRepository).insert(credCaptor.capture());
        assertThat(credCaptor.getValue().secretFingerprint()).isEqualTo(CredentialFingerprint.sha256(SECRET));
        // active_version_id is filled by the follow-up optimistic update (the
        // circular FK requires insert-with-null then point)
        assertThat(credCaptor.getValue().activeVersionId()).isNull();

        ArgumentCaptor<UpstreamCredentialVersion> verCaptor = ArgumentCaptor.forClass(UpstreamCredentialVersion.class);
        verify(versionRepository).insert(verCaptor.capture());
        assertThat(verCaptor.getValue().status()).isEqualTo(CredentialVersionStatus.ACTIVE);
        assertThat(verCaptor.getValue().encryptedSecret()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(verCaptor.getValue().secretFingerprint()).isEqualTo(CredentialFingerprint.sha256(SECRET));
        // plaintext reaches the crypto layer as bytes, never as a stored String
        verify(keyEncryptionProvider).encrypt(SECRET.getBytes(StandardCharsets.UTF_8), TENANT,
                credCaptor.getValue().id());
        // the point update bumps version 0 -> 1 and links the active version
        verify(credentialRepository).update(argThat(u -> u.activeVersionId() != null && u.version() == 1L));
        verify(auditService).record(eq(TENANT), eq(admin.id()), eq("CREDENTIAL_CREATE"), eq("UPSTREAM_CREDENTIAL"),
                eq(credCaptor.getValue().id()), any(), eq("req-1"));
    }

    @Test
    void createWithInvalidSecretWritesNothing() {
        when(subscriptionRepository.findById(SUBSCRIPTION)).thenReturn(Optional.of(subscription()));

        assertThatThrownBy(
                () -> service.create(admin, new AdminCredentialCreateRequest("k", SUBSCRIPTION, "short"), "req-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_INVALID"));

        verifyNoInteractions(credentialRepository, versionRepository, keyEncryptionProvider, auditService);
    }

    @Test
    void createRejectsForeignTenantSubscription() {
        UpstreamSubscription foreign = new UpstreamSubscription(SUBSCRIPTION, OTHER_TENANT, UUID.randomUUID(), "Sub",
                null, BillingMode.PAYG, PlanScope.NONE, null, "USD", null, null, null, null, null,
                SubscriptionStatus.ACTIVE, null, StatusSource.MANUAL_UNKNOWN, 0L, Instant.now(), Instant.now());
        when(subscriptionRepository.findById(SUBSCRIPTION)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(
                () -> service.create(admin, new AdminCredentialCreateRequest("k", SUBSCRIPTION, SECRET), "req-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SUBSCRIPTION_NOT_FOUND"));
        verifyNoInteractions(credentialRepository, versionRepository, keyEncryptionProvider, auditService);
    }

    // ------------------------------------------------------------------
    // validate (never writes)
    // ------------------------------------------------------------------

    @Test
    void validateComparesFingerprintOfActiveVersionOnly() {
        when(credentialRepository.findById(any())).thenReturn(Optional.of(credential()));
        when(versionRepository.findActiveByCredentialId(any())).thenReturn(
                Optional.of(version(CredentialVersionStatus.ACTIVE, CredentialFingerprint.sha256(SECRET), null, null)));

        ValidateCredentialResponse same = service.validate(admin, UUID.randomUUID(),
                new ValidateCredentialRequest(SECRET), "req-1");
        assertThat(same.matchesActive()).isTrue();

        ValidateCredentialResponse different = service.validate(admin, UUID.randomUUID(),
                new ValidateCredentialRequest("sk-other-secret-9999999999"), "req-1");
        assertThat(different.matchesActive()).isFalse();
        assertThat(different.message()).isNotBlank();

        verify(credentialRepository, never()).insert(any());
        verify(credentialRepository, never()).update(any());
        verify(versionRepository, never()).insert(any());
        verify(versionRepository, never()).update(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void validateRejectsMalformedSecret() {
        when(credentialRepository.findById(any())).thenReturn(Optional.of(credential()));

        assertThatThrownBy(
                () -> service.validate(admin, UUID.randomUUID(), new ValidateCredentialRequest("short"), "req-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_INVALID"));
        verify(versionRepository, never()).update(any());
    }

    @Test
    void validateRejectsForeignTenantCredential() {
        when(credentialRepository.findById(any())).thenReturn(Optional.of(credentialOfTenant(OTHER_TENANT)));

        assertThatThrownBy(
                () -> service.validate(admin, UUID.randomUUID(), new ValidateCredentialRequest(SECRET), "req-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // rotate
    // ------------------------------------------------------------------

    @Test
    void rotateDemotesOldActiveBeforeInsertingNewActive() {
        authProperties.setCredentialDrainGrace(Duration.ofMinutes(5));
        UpstreamCredential credential = credential();
        when(credentialRepository.findByIdForUpdate(credential.id())).thenReturn(Optional.of(credential));
        byte[] oldFp = CredentialFingerprint.sha256("sk-old-secret-111111111111");
        UpstreamCredentialVersion oldActive = version(CredentialVersionStatus.ACTIVE, oldFp,
                Instant.now().minusSeconds(3600), null);
        // one already-drained version past its grace window -> lazily retired
        UpstreamCredentialVersion drained = version(CredentialVersionStatus.DRAINING,
                CredentialFingerprint.sha256("sk-drained-secret-2222222222"), Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(60));
        when(versionRepository.findAllByCredentialId(credential.id())).thenReturn(List.of(oldActive, drained));
        when(versionRepository.findActiveByCredentialId(credential.id())).thenReturn(Optional.of(oldActive));
        when(keyEncryptionProvider.encrypt(any(), eq(TENANT), eq(credential.id())))
                .thenReturn(new EncryptedSecret(new byte[]{9}, new byte[]{8}, "v1"));

        CredentialView view = service.rotate(admin, credential.id(), new RotateCredentialRequest(SECRET), "req-1");

        InOrder inOrder = inOrder(versionRepository, credentialRepository);
        // drained version past grace is retired first
        inOrder.verify(versionRepository).update(argThat(v -> v.status() == CredentialVersionStatus.RETIRED));
        // old ACTIVE -> DRAINING with retiredAt = now + grace
        inOrder.verify(versionRepository).update(argThat(v -> v.status() == CredentialVersionStatus.DRAINING
                && v.retiredAt() != null && !v.retiredAt().isBefore(Instant.now().minusSeconds(1))));
        // new ACTIVE inserted only after the old one is no longer ACTIVE
        inOrder.verify(versionRepository).insert(argThat(v -> v.status() == CredentialVersionStatus.ACTIVE));
        // credential row points at the new version
        inOrder.verify(credentialRepository)
                .update(argThat(u -> u.status() == CredentialStatus.ACTIVE && u.activeVersionId() != null
                        && u.version() == credential.version() + 1 && u.secretFingerprint() != null
                        && u.secretFingerprint().length == 32));

        assertThat(view.status()).isEqualTo("ACTIVE");
        verify(auditService).record(eq(TENANT), eq(admin.id()), eq("CREDENTIAL_ROTATE"), eq("UPSTREAM_CREDENTIAL"),
                eq(credential.id()), any(), eq("req-1"));
    }

    @Test
    void rotateRejectsNonActiveCredential() {
        when(credentialRepository.findByIdForUpdate(any()))
                .thenReturn(Optional.of(credentialOfStatus(CredentialStatus.DISABLED)));

        assertThatThrownBy(() -> service.rotate(admin, UUID.randomUUID(), new RotateCredentialRequest(SECRET), "req-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_NOT_ROTATABLE"));
        verify(versionRepository, never()).insert(any());
        verify(credentialRepository, never()).update(any());
    }

    @Test
    void rotateWithInvalidSecretAbortsBeforeAnyWrite() {
        when(credentialRepository.findByIdForUpdate(any())).thenReturn(Optional.of(credential()));

        assertThatThrownBy(
                () -> service.rotate(admin, UUID.randomUUID(), new RotateCredentialRequest("short"), "req-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_INVALID"));
        verify(versionRepository, never()).update(any());
        verify(versionRepository, never()).insert(any());
        verify(credentialRepository, never()).update(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void rotateRejectsForeignTenantCredential() {
        when(credentialRepository.findByIdForUpdate(any())).thenReturn(Optional.of(credentialOfTenant(OTHER_TENANT)));

        assertThatThrownBy(() -> service.rotate(admin, UUID.randomUUID(), new RotateCredentialRequest(SECRET), "req-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // disable
    // ------------------------------------------------------------------

    @Test
    void disableDemotesActiveVersionAndMarksCredentialDisabled() {
        UpstreamCredential credential = credential();
        when(credentialRepository.findByIdForUpdate(credential.id())).thenReturn(Optional.of(credential));
        UpstreamCredentialVersion oldActive = version(CredentialVersionStatus.ACTIVE,
                CredentialFingerprint.sha256(SECRET), Instant.now().minusSeconds(3600), null);
        when(versionRepository.findAllByCredentialId(credential.id())).thenReturn(List.of(oldActive));
        when(versionRepository.findActiveByCredentialId(credential.id())).thenReturn(Optional.of(oldActive));

        service.disable(admin, credential.id(), "req-1");

        verify(versionRepository).update(argThat(v -> v.status() == CredentialVersionStatus.DRAINING));
        verify(credentialRepository).update(
                argThat(u -> u.status() == CredentialStatus.DISABLED && u.version() == credential.version() + 1));
        verify(auditService).record(eq(TENANT), eq(admin.id()), eq("CREDENTIAL_DISABLE"), eq("UPSTREAM_CREDENTIAL"),
                eq(credential.id()), any(), eq("req-1"));
    }

    @Test
    void disableRejectsAlreadyDisabledCredential() {
        when(credentialRepository.findByIdForUpdate(any()))
                .thenReturn(Optional.of(credentialOfStatus(CredentialStatus.DISABLED)));

        assertThatThrownBy(() -> service.disable(admin, UUID.randomUUID(), "req-1")).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_NOT_DISABLEABLE"));
        verify(versionRepository, never()).update(any());
        verify(credentialRepository, never()).update(any());
    }

    // ------------------------------------------------------------------
    // list / detail
    // ------------------------------------------------------------------

    @Test
    void listScopesToCallerTenant() {
        when(credentialRepository.findAllByTenantId(TENANT)).thenReturn(List.of(credential()));

        List<CredentialView> views = service.list(admin);

        assertThat(views).hasSize(1);
        verify(credentialRepository).findAllByTenantId(TENANT);
    }

    @Test
    void detailReturnsVersionHistoryNewestFirst() {
        when(credentialRepository.findById(any())).thenReturn(Optional.of(credential()));
        UpstreamCredentialVersion newest = version(CredentialVersionStatus.ACTIVE, CredentialFingerprint.sha256(SECRET),
                Instant.now(), null);
        UpstreamCredentialVersion oldest = version(CredentialVersionStatus.DRAINING,
                CredentialFingerprint.sha256("sk-old-secret-111111111111"), Instant.now().minusSeconds(3600), null);
        when(versionRepository.findAllByCredentialId(any())).thenReturn(List.of(newest, oldest));

        CredentialDetailView detail = service.detail(admin, UUID.randomUUID());

        assertThat(detail.credential().id()).isNotNull();
        assertThat(detail.versions()).hasSize(2);
        assertThat(detail.versions().get(0).status()).isEqualTo("ACTIVE");
        assertThat(detail.versions().get(0).fingerprintPrefix())
                .isEqualTo(CredentialFingerprint.hexPrefix(CredentialFingerprint.sha256(SECRET), 8));
        assertThat(detail.versions().get(0).encryptionKeyVersion()).isNotBlank();
    }

    // ------------------------------------------------------------------

    private static UpstreamSubscription subscription() {
        return new UpstreamSubscription(SUBSCRIPTION, TENANT, UUID.randomUUID(), "Sub", null, BillingMode.PAYG,
                PlanScope.NONE, null, "USD", null, null, null, null, null, SubscriptionStatus.ACTIVE, null,
                StatusSource.MANUAL_UNKNOWN, 0L, Instant.now(), Instant.now());
    }

    private static UpstreamCredential credential() {
        return credentialOfStatus(CredentialStatus.ACTIVE);
    }

    private static UpstreamCredential credentialOfTenant(UUID tenantId) {
        return new UpstreamCredential(UUID.randomUUID(), tenantId, SUBSCRIPTION, null, "prod-key",
                CredentialFingerprint.sha256(SECRET), CredentialStatus.ACTIVE, UUID.randomUUID(), Instant.now(), null,
                3L, Instant.now(), Instant.now());
    }

    private static UpstreamCredential credentialOfStatus(CredentialStatus status) {
        return new UpstreamCredential(UUID.randomUUID(), TENANT, SUBSCRIPTION, null, "prod-key",
                CredentialFingerprint.sha256(SECRET), status, UUID.randomUUID(), Instant.now(), null, 3L, Instant.now(),
                Instant.now());
    }

    private static UpstreamCredentialVersion version(CredentialVersionStatus status, byte[] fingerprint,
            Instant validFrom, Instant retiredAt) {
        return new UpstreamCredentialVersion(UUID.randomUUID(), TENANT, UUID.randomUUID(), new byte[]{1, 2, 3},
                new byte[]{4}, "v1", fingerprint, status, validFrom, retiredAt, Instant.now());
    }

    private static ProviderProduct productFor(UUID productId) {
        return new ProviderProduct(productId, UUID.randomUUID(), "deepseek-payg-api", "DeepSeek PAYG", BillingMode.PAYG,
                PlanScope.NONE, null, null, "[\"messages\"]", "[{\"url\":\"https://api.deepseek.com\"}]", "bearer",
                "OFFICIAL_API", "OFFICIAL_API", com.miqroera.miqrokey.domain.model.BalanceAuthority.OFFICIAL_API,
                com.miqroera.miqrokey.domain.model.ImplementationStatus.IMPLEMENTED, "1", 0, Instant.now(),
                Instant.now());
    }

    @Test
    void validateProbesProviderWhenSecretMatches() {
        UpstreamCredential credential = credentialOfStatus(CredentialStatus.ACTIVE);
        UpstreamSubscription subscription = subscription();
        ProviderProduct product = productFor(subscription.providerProductId());
        ProviderProductAdapter adapter = mock(ProviderProductAdapter.class);
        HttpProviderClient client = mock(HttpProviderClient.class);
        when(credentialRepository.findById(credential.id())).thenReturn(java.util.Optional.of(credential));
        when(versionRepository.findActiveByCredentialId(credential.id())).thenReturn(
                java.util.Optional.of(version(com.miqroera.miqrokey.domain.model.CredentialVersionStatus.ACTIVE,
                        CredentialFingerprint.sha256(SECRET), Instant.now(), null)));
        when(subscriptionRepository.findById(credential.subscriptionId()))
                .thenReturn(java.util.Optional.of(subscription));
        when(productRepository.findById(subscription.providerProductId())).thenReturn(java.util.Optional.of(product));
        when(adapterRegistry.findById("deepseek-payg-api")).thenReturn(java.util.Optional.of(adapter));
        when(clientFactory.create(any(), any(), any())).thenReturn(client);
        when(adapter.validateCredential(client)).thenReturn(Mono.just(CredentialCheck.valid(Instant.now())));

        ValidateCredentialResponse response = service.validate(admin, credential.id(),
                new ValidateCredentialRequest(SECRET), "req");

        assertThat(response.matchesActive()).isTrue();
        assertThat(response.providerStatus()).isEqualTo("VALID");
    }

    @Test
    void validateReportsProviderRejection() {
        UpstreamCredential credential = credentialOfStatus(CredentialStatus.ACTIVE);
        UpstreamSubscription subscription = subscription();
        ProviderProduct product = productFor(subscription.providerProductId());
        ProviderProductAdapter adapter = mock(ProviderProductAdapter.class);
        HttpProviderClient client = mock(HttpProviderClient.class);
        when(credentialRepository.findById(credential.id())).thenReturn(java.util.Optional.of(credential));
        when(versionRepository.findActiveByCredentialId(credential.id())).thenReturn(
                java.util.Optional.of(version(com.miqroera.miqrokey.domain.model.CredentialVersionStatus.ACTIVE,
                        CredentialFingerprint.sha256(SECRET), Instant.now(), null)));
        when(subscriptionRepository.findById(credential.subscriptionId()))
                .thenReturn(java.util.Optional.of(subscription));
        when(productRepository.findById(subscription.providerProductId())).thenReturn(java.util.Optional.of(product));
        when(adapterRegistry.findById("deepseek-payg-api")).thenReturn(java.util.Optional.of(adapter));
        when(clientFactory.create(any(), any(), any())).thenReturn(client);
        when(adapter.validateCredential(client))
                .thenReturn(Mono.just(CredentialCheck.invalid("credential rejected", Instant.now())));

        ValidateCredentialResponse response = service.validate(admin, credential.id(),
                new ValidateCredentialRequest(SECRET), "req");

        assertThat(response.matchesActive()).isTrue();
        assertThat(response.providerStatus()).isEqualTo("REJECTED");
    }

    @Test
    void validateMarksUnreachableWhenProviderCallFails() {
        UpstreamCredential credential = credentialOfStatus(CredentialStatus.ACTIVE);
        UpstreamSubscription subscription = subscription();
        ProviderProduct product = productFor(subscription.providerProductId());
        when(credentialRepository.findById(credential.id())).thenReturn(java.util.Optional.of(credential));
        when(versionRepository.findActiveByCredentialId(credential.id())).thenReturn(
                java.util.Optional.of(version(com.miqroera.miqrokey.domain.model.CredentialVersionStatus.ACTIVE,
                        CredentialFingerprint.sha256(SECRET), Instant.now(), null)));
        when(subscriptionRepository.findById(credential.subscriptionId()))
                .thenReturn(java.util.Optional.of(subscription));
        when(productRepository.findById(subscription.providerProductId())).thenReturn(java.util.Optional.of(product));
        when(adapterRegistry.findById("deepseek-payg-api"))
                .thenReturn(java.util.Optional.of(mock(ProviderProductAdapter.class)));
        when(clientFactory.create(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        ValidateCredentialResponse response = service.validate(admin, credential.id(),
                new ValidateCredentialRequest(SECRET), "req");

        assertThat(response.matchesActive()).isTrue();
        assertThat(response.providerStatus()).isEqualTo("UNREACHABLE");
    }
}
