package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyRequest;
import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyResponse;
import com.miqroera.miqrokey.controlplane.dto.MeGrantsResponse;
import com.miqroera.miqrokey.controlplane.dto.VirtualKeyView;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyMaterial;
import com.miqroera.miqrokey.domain.model.GrantStatus;
import com.miqroera.miqrokey.domain.model.KeyProjectBinding;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectMembership;
import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import com.miqroera.miqrokey.domain.model.ProjectStatus;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;
import com.miqroera.miqrokey.domain.repository.KeyProjectBindingRepository;
import com.miqroera.miqrokey.domain.repository.ProjectMembershipRepository;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VirtualKeyService}: the create validation chain,
 * digest-only persistence, one-time secret display, rotation grace, revoke
 * state machine, and the ownership guard.
 */
@ExtendWith(MockitoExtension.class)
class VirtualKeyServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID GRANT_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final String TAG = "core-ai";

    @Mock
    private VirtualKeyRepository keyRepository;
    @Mock
    private KeyProjectBindingRepository bindingRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectProviderGrantRepository grantRepository;
    @Mock
    private ProjectMembershipRepository membershipRepository;
    @Mock
    private VirtualKeyCrypto keyCrypto;
    @Mock
    private AuditService auditService;

    private AuthProperties authProperties;
    private VirtualKeyService service;
    private User user;
    private User admin;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authProperties.setGatewayBaseUrl("https://gateway.example.internal");
        authProperties.setVirtualKeyRotateGrace(Duration.ZERO);
        service = new VirtualKeyService(keyRepository, bindingRepository, projectRepository, grantRepository,
                membershipRepository, keyCrypto, auditService, authProperties);
        user = user(UserRole.USER);
        admin = user(UserRole.SYSTEM_ADMIN);
    }

    // ------------------------------------------------------------------
    // create: validation chain
    // ------------------------------------------------------------------

    @Test
    void createStoresOnlyDigestAndReturnsSecretOnce() {
        Project project = activeProject(TENANT, TAG);
        ProjectProviderGrant grant = activeGrant();
        VirtualKeyMaterial material = material();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(grantRepository.findById(GRANT_ID)).thenReturn(Optional.of(grant));
        when(membershipRepository.exists(PROJECT_ID, USER_ID)).thenReturn(true);
        when(grantRepository.findModelIds(GRANT_ID)).thenReturn(Set.of("model-a", "model-b"));
        when(keyCrypto.generate(TENANT, TAG)).thenReturn(material);

        CreateVirtualKeyResponse resp = service.create(user, request("claude-code-main", List.of("model-a")), "req-1");

        // Secret returned exactly once in the response...
        assertThat(resp.secret()).isEqualTo(material.fullDisplayString());
        assertThat(resp.shownOnce()).isTrue();
        assertThat(resp.baseUrl()).isEqualTo("https://gateway.example.internal");

        // ...and only the digest lands in the repository.
        ArgumentCaptor<VirtualKey> keyCaptor = ArgumentCaptor.forClass(VirtualKey.class);
        verify(keyRepository).insert(keyCaptor.capture());
        VirtualKey stored = keyCaptor.getValue();
        assertThat(stored.secretDigest()).isEqualTo(material.digest());
        assertThat(stored.publicKeyId()).isEqualTo(material.publicKeyId());
        assertThat(stored.userId()).isEqualTo(USER_ID);
        assertThat(stored.projectId()).isEqualTo(PROJECT_ID);
        assertThat(stored.grantId()).isEqualTo(GRANT_ID);
        assertThat(stored.upstreamCredentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(stored.cachePolicy()).isEqualTo("DISABLED");
        assertThat(stored.status()).isEqualTo(VirtualKeyStatus.ACTIVE);
        // The stored record must never contain the raw secret or the full display
        // string.
        assertThat(stored.secretDigest()).isNotEqualTo(material.rawSecret());

        verify(bindingRepository).insert(any(KeyProjectBinding.class));
        verify(keyRepository).replaceKeyModels(TENANT, stored.id(), Set.of("model-a"));

        // Audit summary must not leak the secret.
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(TENANT), eq(USER_ID), eq("VIRTUAL_KEY_CREATE"), eq("VIRTUAL_KEY"),
                eq(stored.id()), summary.capture(), eq("req-1"));
        assertThat(summary.getValue()).doesNotContain(material.fullDisplayString())
                .doesNotContain(material.displayPrefix() + "…");

        // Material wiped after the response is built.
        verify(keyCrypto).generate(TENANT, TAG);
        assertThat(material.rawSecret()).containsOnly((byte) 0);
    }

    @Test
    void createRejectsProjectOutsideTenant() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(OTHER_TENANT, TAG)));

        assertThatThrownBy(() -> service.create(user, request("k", null), "req"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("PROJECT_NOT_FOUND");
                });
        verify(keyRepository, never()).insert(any());
    }

    @Test
    void createRejectsInactiveProject() {
        Project project = new Project(PROJECT_ID, TENANT, "P", "p", null, null, ProjectStatus.DISABLED, TAG, 0L,
                Instant.now(), Instant.now());
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.create(user, request("k", null), "req"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PROJECT_INACTIVE"));
        verify(keyRepository, never()).insert(any());
    }

    @Test
    void createRejectsProjectWithoutRoutingTag() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, null)));

        assertThatThrownBy(() -> service.create(user, request("k", null), "req")).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ROUTING_TAG_MISSING"));
        verify(keyRepository, never()).insert(any());
    }

    @Test
    void createRejectsNonMember() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, TAG)));
        when(membershipRepository.exists(PROJECT_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(user, request("k", null), "req")).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PROJECT_MEMBERSHIP_REQUIRED"));
        verify(keyRepository, never()).insert(any());
    }

    @Test
    void adminSkipsMembershipCheck() {
        Project project = activeProject(TENANT, TAG);
        ProjectProviderGrant grant = activeGrant();
        VirtualKeyMaterial material = material();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(grantRepository.findById(GRANT_ID)).thenReturn(Optional.of(grant));
        when(grantRepository.findModelIds(GRANT_ID)).thenReturn(Set.of("model-a"));
        when(keyCrypto.generate(TENANT, TAG)).thenReturn(material);

        CreateVirtualKeyResponse resp = service.create(admin, request("admin-key", null), "req");

        assertThat(resp.secret()).isEqualTo(material.fullDisplayString());
        verify(membershipRepository, never()).exists(any(), any());
    }

    @Test
    void createRejectsMismatchedGrant() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, TAG)));
        when(membershipRepository.exists(PROJECT_ID, USER_ID)).thenReturn(true);
        // Grant belongs to a different project.
        ProjectProviderGrant grant = new ProjectProviderGrant(GRANT_ID, TENANT, UUID.randomUUID(), PRODUCT_ID,
                CREDENTIAL_ID, GrantStatus.ACTIVE, USER_ID, 0L, Instant.now(), Instant.now());
        when(grantRepository.findById(GRANT_ID)).thenReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.create(user, request("k", null), "req"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GRANT_INVALID"));
        verify(keyRepository, never()).insert(any());
    }

    @Test
    void createRejectsInactiveGrant() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, TAG)));
        when(membershipRepository.exists(PROJECT_ID, USER_ID)).thenReturn(true);
        ProjectProviderGrant grant = new ProjectProviderGrant(GRANT_ID, TENANT, PROJECT_ID, PRODUCT_ID, CREDENTIAL_ID,
                GrantStatus.DISABLED, USER_ID, 0L, Instant.now(), Instant.now());
        when(grantRepository.findById(GRANT_ID)).thenReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.create(user, request("k", null), "req"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GRANT_INACTIVE"));
        verify(keyRepository, never()).insert(any());
    }

    @Test
    void createRejectsModelNotGranted() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, TAG)));
        when(membershipRepository.exists(PROJECT_ID, USER_ID)).thenReturn(true);
        when(grantRepository.findById(GRANT_ID)).thenReturn(Optional.of(activeGrant()));
        when(grantRepository.findModelIds(GRANT_ID)).thenReturn(Set.of("model-a"));

        assertThatThrownBy(() -> service.create(user, request("k", List.of("model-b")), "req")).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("MODEL_NOT_GRANTED"));
        verify(keyRepository, never()).insert(any());
    }

    @Test
    void createDefaultsToAllGrantedModelsWhenRequestOmitsThem() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, TAG)));
        when(membershipRepository.exists(PROJECT_ID, USER_ID)).thenReturn(true);
        when(grantRepository.findById(GRANT_ID)).thenReturn(Optional.of(activeGrant()));
        when(grantRepository.findModelIds(GRANT_ID)).thenReturn(Set.of("model-a", "model-b"));
        when(keyCrypto.generate(TENANT, TAG)).thenReturn(material());

        service.create(user, request("k", null), "req");

        ArgumentCaptor<VirtualKey> keyCaptor = ArgumentCaptor.forClass(VirtualKey.class);
        verify(keyRepository).insert(keyCaptor.capture());
        verify(keyRepository).replaceKeyModels(TENANT, keyCaptor.getValue().id(), Set.of("model-a", "model-b"));
    }

    // ------------------------------------------------------------------
    // rotate
    // ------------------------------------------------------------------

    @Test
    void rotateCreatesReplacementAndPutsOldKeyInGraceWindow() {
        authProperties.setVirtualKeyRotateGrace(Duration.ofMinutes(5));
        VirtualKey oldKey = key("old-key", VirtualKeyStatus.ACTIVE, null);
        VirtualKeyMaterial newMaterial = material();
        when(keyRepository.findById(oldKey.id())).thenReturn(Optional.of(oldKey));
        when(keyRepository.findModelIds(oldKey.id())).thenReturn(Set.of("model-a"));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, TAG)));
        when(keyCrypto.generate(TENANT, TAG)).thenReturn(newMaterial);

        CreateVirtualKeyResponse resp = service.rotate(user, oldKey.id(), "req-2");

        assertThat(resp.secret()).isEqualTo(newMaterial.fullDisplayString());

        ArgumentCaptor<VirtualKey> inserted = ArgumentCaptor.forClass(VirtualKey.class);
        verify(keyRepository).insert(inserted.capture());
        VirtualKey replacement = inserted.getValue();
        assertThat(replacement.projectId()).isEqualTo(PROJECT_ID);
        assertThat(replacement.grantId()).isEqualTo(GRANT_ID);
        assertThat(replacement.upstreamCredentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(replacement.cachePolicy()).isEqualTo("DISABLED");
        assertThat(replacement.userId()).isEqualTo(USER_ID);
        verify(keyRepository).replaceKeyModels(TENANT, replacement.id(), Set.of("model-a"));
        verify(bindingRepository).insert(any(KeyProjectBinding.class));

        ArgumentCaptor<VirtualKey> updated = ArgumentCaptor.forClass(VirtualKey.class);
        verify(keyRepository).update(updated.capture());
        VirtualKey rotated = updated.getValue();
        assertThat(rotated.status()).isEqualTo(VirtualKeyStatus.ROTATING);
        assertThat(rotated.replacedByKeyId()).isEqualTo(replacement.id());
        assertThat(rotated.revokedAt()).isNotNull();
        assertThat(rotated.revokedAt()).isAfter(rotated.createdAt());
    }

    @Test
    void rotateRejectsKeyInOtherTenant() {
        VirtualKey otherTenantKey = new VirtualKey(UUID.randomUUID(), OTHER_TENANT, "pk", new byte[32], "pre", "0000",
                USER_ID, PROJECT_ID, GRANT_ID, CREDENTIAL_ID, VirtualKeyPurpose.CLAUDE_CODE, "other", "DISABLED",
                VirtualKeyStatus.ACTIVE, Instant.now(), null, null, null, 0L);
        when(keyRepository.findById(otherTenantKey.id())).thenReturn(Optional.of(otherTenantKey));

        assertThatThrownBy(() -> service.rotate(user, otherTenantKey.id(), "req"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("KEY_NOT_FOUND");
                });
        verify(keyRepository, never()).insert(any());
    }

    @Test
    void rotateRejectsNonActiveKey() {
        VirtualKey revoked = key("revoked", VirtualKeyStatus.REVOKED, Instant.now());
        when(keyRepository.findById(revoked.id())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotate(user, revoked.id(), "req")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("KEY_NOT_ROTATABLE"));
        verify(keyRepository, never()).insert(any());
    }

    // ------------------------------------------------------------------
    // revoke
    // ------------------------------------------------------------------

    @Test
    void revokeMarksKeyRevokedWithNowTimestamp() {
        VirtualKey key = key("active", VirtualKeyStatus.ACTIVE, null);
        when(keyRepository.findById(key.id())).thenReturn(Optional.of(key));

        service.revoke(user, key.id(), "req-3");

        ArgumentCaptor<VirtualKey> updated = ArgumentCaptor.forClass(VirtualKey.class);
        verify(keyRepository).update(updated.capture());
        VirtualKey revoked = updated.getValue();
        assertThat(revoked.status()).isEqualTo(VirtualKeyStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.secretDigest()).isEqualTo(key.secretDigest());
        verify(auditService).record(eq(TENANT), eq(USER_ID), eq("VIRTUAL_KEY_REVOKE"), eq("VIRTUAL_KEY"), eq(key.id()),
                any(String.class), eq("req-3"));
    }

    @Test
    void revokeRejectsAlreadyRevokedKey() {
        VirtualKey key = key("revoked", VirtualKeyStatus.REVOKED, Instant.now());
        when(keyRepository.findById(key.id())).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> service.revoke(user, key.id(), "req")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("KEY_NOT_REVOCABLE"));
        verify(keyRepository, never()).update(any());
    }

    // ------------------------------------------------------------------
    // ownership guard
    // ------------------------------------------------------------------

    @Test
    void otherUsersKeyIsIndistinguishableFromMissing() {
        VirtualKey someoneElses = new VirtualKey(UUID.randomUUID(), TENANT, "pk", new byte[32], "pre", "0000",
                UUID.randomUUID(), PROJECT_ID, GRANT_ID, CREDENTIAL_ID, VirtualKeyPurpose.CLAUDE_CODE, "theirs",
                "DISABLED", VirtualKeyStatus.ACTIVE, Instant.now(), null, null, null, 0L);
        when(keyRepository.findById(someoneElses.id())).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.get(user, someoneElses.id())).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getCode()).isEqualTo("KEY_NOT_FOUND");
        });
    }

    @Test
    void adminCanReadAnyKeyInTenant() {
        VirtualKey someoneElses = key("theirs", VirtualKeyStatus.ACTIVE, null);
        when(keyRepository.findById(someoneElses.id())).thenReturn(Optional.of(someoneElses));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, TAG)));
        when(keyRepository.findModelIds(someoneElses.id())).thenReturn(Set.of("model-a"));

        VirtualKeyView view = service.get(admin, someoneElses.id());

        assertThat(view.id()).isEqualTo(someoneElses.id());
        assertThat(view.display()).isEqualTo("pre…0000");
        assertThat(view.projectTag()).isEqualTo(TAG);
        assertThat(view.baseUrl()).isEqualTo("https://gateway.example.internal");
        // The safe view must never expose the digest or any secret material.
        assertThat(view.toString()).doesNotContain("digest");
    }

    // ------------------------------------------------------------------
    // grant options
    // ------------------------------------------------------------------

    @Test
    void grantOptionsForRegularUserOnlyListsMemberProjects() {
        ProjectMembership membership = new ProjectMembership(TENANT, PROJECT_ID, USER_ID, USER_ID, Instant.now());
        when(membershipRepository.findAllByUserId(USER_ID)).thenReturn(List.of(membership));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject(TENANT, TAG)));
        when(grantRepository.findAllByProjectIdAndStatus(PROJECT_ID, "ACTIVE")).thenReturn(List.of(activeGrant()));
        when(grantRepository.findModelIds(GRANT_ID)).thenReturn(Set.of("model-a"));

        MeGrantsResponse resp = service.grantOptions(user);

        assertThat(resp.projects()).hasSize(1);
        assertThat(resp.projects().get(0).projectTag()).isEqualTo(TAG);
        assertThat(resp.grants()).hasSize(1);
        assertThat(resp.grants().get(0).models()).containsExactly("model-a");
        assertThat(resp.purposes()).contains(VirtualKeyPurpose.CLAUDE_CODE.name());
    }

    @Test
    void grantOptionsForAdminListsAllActiveProjects() {
        UUID otherProject = UUID.randomUUID();
        Project first = activeProject(TENANT, TAG);
        Project second = new Project(otherProject, TENANT, "O", "o", null, null, ProjectStatus.ACTIVE, "other", 0L,
                Instant.now(), Instant.now());
        when(projectRepository.findAllByTenantId(TENANT)).thenReturn(List.of(first, second));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(first));
        when(projectRepository.findById(otherProject)).thenReturn(Optional.of(second));

        MeGrantsResponse resp = service.grantOptions(admin);

        assertThat(resp.projects()).hasSize(2);
    }

    // ------------------------------------------------------------------

    private static VirtualKeyMaterial material() {
        byte[] rawSecret = new byte[32];
        Arrays.fill(rawSecret, (byte) 7);
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) 9);
        return new VirtualKeyMaterial("mqk_live_abcdefghijklmnopqrstuv.secretvalue.core-ai", "abcdefghijklmnopqrstuv",
                rawSecret, "mqk_live_abcdefghijklmnopqrstuv", "secretvalue", digest);
    }

    private static Project activeProject(UUID tenant, String tag) {
        return new Project(PROJECT_ID, tenant, "P", "p", null, null, ProjectStatus.ACTIVE, tag, 0L, Instant.now(),
                Instant.now());
    }

    private static ProjectProviderGrant activeGrant() {
        return new ProjectProviderGrant(GRANT_ID, TENANT, PROJECT_ID, PRODUCT_ID, CREDENTIAL_ID, GrantStatus.ACTIVE,
                USER_ID, 0L, Instant.now(), Instant.now());
    }

    private static CreateVirtualKeyRequest request(String name, List<String> models) {
        return new CreateVirtualKeyRequest(name, PROJECT_ID, PRODUCT_ID, GRANT_ID, VirtualKeyPurpose.CLAUDE_CODE,
                models, null);
    }

    private VirtualKey key(String name, VirtualKeyStatus status, Instant revokedAt) {
        return new VirtualKey(UUID.randomUUID(), TENANT, "pk", new byte[32], "pre", "0000", USER_ID, PROJECT_ID,
                GRANT_ID, CREDENTIAL_ID, VirtualKeyPurpose.CLAUDE_CODE, name, "DISABLED", status, Instant.now(), null,
                revokedAt, null, 0L);
    }

    private static User user(UserRole role) {
        return new User(USER_ID, TENANT, "u", "U", new byte[32], role, UserStatus.ACTIVE, false, 0, null, null, 0L,
                Instant.now(), Instant.now());
    }
}
