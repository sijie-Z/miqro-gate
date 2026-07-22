package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Repository integration tests")
class RepositoryIntegrationTest extends AbstractPostgresTest {

    @Autowired
    private TenantRepository tenantRepo;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private ProjectRepository projectRepo;
    @Autowired
    private ProjectMembershipRepository membershipRepo;
    @Autowired
    private TeamRepository teamRepo;
    @Autowired
    private ProviderRepository providerRepo;
    @Autowired
    private ProviderProductRepository productRepo;
    @Autowired
    private UpstreamSubscriptionRepository subRepo;
    @Autowired
    private UpstreamCredentialRepository credRepo;
    @Autowired
    private UpstreamCredentialVersionRepository versionRepo;
    @Autowired
    private ProjectProviderGrantRepository grantRepo;
    @Autowired
    private VirtualKeyRepository vkRepo;
    @Autowired
    private AdminAuditEventRepository auditRepo;

    // Use seed tenant from V1 migration
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.now();

    private User user;
    private Project project;
    private Provider provider;
    private ProviderProduct product;
    private UpstreamSubscription subscription;
    private UpstreamCredential credential;
    private UpstreamCredentialVersion credentialVersion;
    private ProjectProviderGrant grant;

    @BeforeEach
    void setUp() {
        user = new User(UUID.randomUUID(), TENANT_ID, "testuser", "Test User", new byte[]{1, 2, 3, 4}, UserRole.USER,
                UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(user);

        project = new Project(UUID.randomUUID(), TENANT_ID, "test-proj", "Test Project", null, null,
                ProjectStatus.ACTIVE, 0, NOW, NOW);
        projectRepo.insert(project);

        provider = new Provider(UUID.randomUUID(), "test-provider", "Test Provider", null, null, null,
                ProviderStatus.ACTIVE, 0, NOW, NOW);
        providerRepo.insert(provider);

        product = new ProviderProduct(UUID.randomUUID(), provider.id(), "test-product", "Test Product",
                BillingMode.PAYG, PlanScope.NONE, CredentialTopology.SINGLE_SHARED, null, "[]", "[]", "{}", null, null,
                null, ImplementationStatus.DRAFT, null, 0, NOW, NOW);
        productRepo.insert(product);

        subscription = new UpstreamSubscription(UUID.randomUUID(), TENANT_ID, product.id(), "Test Subscription", null,
                BillingMode.PAYG, PlanScope.NONE, null, null, null, null, null, null, null, SubscriptionStatus.ACTIVE,
                null, null, 0, NOW, NOW);
        subRepo.insert(subscription);

        credential = new UpstreamCredential(UUID.randomUUID(), TENANT_ID, subscription.id(), null, "Test Credential",
                new byte[]{10, 20, 30}, CredentialStatus.ACTIVE, null, null, null, 0, NOW, NOW);
        credRepo.insert(credential);

        credentialVersion = new UpstreamCredentialVersion(UUID.randomUUID(), TENANT_ID, credential.id(),
                new byte[]{0x01, 0x02}, new byte[]{0x03, 0x04}, "v1", new byte[]{10, 20, 30},
                CredentialVersionStatus.ACTIVE, NOW, null, NOW);
        versionRepo.insert(credentialVersion);

        grant = new ProjectProviderGrant(UUID.randomUUID(), TENANT_ID, project.id(), product.id(), credential.id(),
                GrantStatus.ACTIVE, user.id(), 0, NOW, NOW);
        grantRepo.insert(grant);
    }

    @Nested
    @DisplayName("Tenant CRUD")
    class TenantCrud {
        @Test
        @DisplayName("should find seed tenant by id")
        void shouldFindSeedTenant() {
            var found = tenantRepo.findById(TENANT_ID);
            assertThat(found).isPresent();
            assertThat(found.get().code()).isEqualTo("default");
            assertThat(found.get().status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(found.get().version()).isEqualTo(0);
        }

        @Test
        @DisplayName("should find seed tenant by code")
        void shouldFindByCode() {
            var found = tenantRepo.findByCode("default");
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should update tenant with optimistic locking")
        void shouldUpdateWithOptimisticLock() {
            var found = tenantRepo.findById(TENANT_ID).orElseThrow();
            var updated = new Tenant(found.id(), found.code(), found.name(), TenantStatus.DISABLED, found.version() + 1,
                    NOW, NOW);
            tenantRepo.update(updated);
            var refetched = tenantRepo.findById(TENANT_ID).orElseThrow();
            assertThat(refetched.status()).isEqualTo(TenantStatus.DISABLED);
        }
    }

    @Nested
    @DisplayName("User CRUD")
    class UserCrud {
        @Test
        @DisplayName("should find user by tenant and username")
        void shouldFindByTenantAndUsername() {
            var found = userRepo.findByTenantIdAndUsername(TENANT_ID, "TESTUSER");
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(user.id());
        }

        @Test
        @DisplayName("should prevent duplicate username in same tenant")
        void shouldPreventDuplicateUsername() {
            assertThat(userRepo.existsByTenantIdAndUsername(TENANT_ID, "testuser")).isTrue();
        }

        @Test
        @DisplayName("should update user with optimistic locking")
        void shouldUpdateWithOptimisticLock() {
            var updated = new User(user.id(), TENANT_ID, user.username(), user.displayName(), user.passwordHash(),
                    UserRole.SYSTEM_ADMIN, UserStatus.LOCKED, false, 5, Instant.now().plusSeconds(3600), null,
                    user.version() + 1, NOW, NOW);
            userRepo.update(updated);
            var found = userRepo.findById(user.id()).orElseThrow();
            assertThat(found.status()).isEqualTo(UserStatus.LOCKED);
            assertThat(found.role()).isEqualTo(UserRole.SYSTEM_ADMIN);
        }
    }

    @Nested
    @DisplayName("Project and membership CRUD")
    class ProjectCrud {
        @Test
        @DisplayName("should insert and find project")
        void shouldInsertAndFindProject() {
            var found = projectRepo.findById(project.id());
            assertThat(found).isPresent();
            assertThat(found.get().code()).isEqualTo("test-proj");
        }

        @Test
        @DisplayName("should add and remove memberships")
        void shouldAddAndRemoveMembership() {
            var m = new ProjectMembership(TENANT_ID, project.id(), user.id(), user.id(), NOW);
            membershipRepo.insert(m);
            assertThat(membershipRepo.exists(project.id(), user.id())).isTrue();
            membershipRepo.delete(project.id(), user.id());
            assertThat(membershipRepo.exists(project.id(), user.id())).isFalse();
        }
    }

    @Nested
    @DisplayName("Team CRUD")
    class TeamCrud {
        @Test
        @DisplayName("should insert and find team")
        void shouldInsertAndFindTeam() {
            var team = new Team(UUID.randomUUID(), TENANT_ID, "Test Team", "desc", TeamStatus.ACTIVE, 0, NOW, NOW);
            teamRepo.insert(team);
            var found = teamRepo.findById(team.id());
            assertThat(found).isPresent();
            assertThat(found.get().name()).isEqualTo("Test Team");
        }

        @Test
        @DisplayName("should list teams by tenant")
        void shouldListByTenant() {
            var team = new Team(UUID.randomUUID(), TENANT_ID, "List Team", null, TeamStatus.ACTIVE, 0, NOW, NOW);
            teamRepo.insert(team);
            var teams = teamRepo.findAllByTenantId(TENANT_ID);
            assertThat(teams).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Provider CRUD")
    class ProviderCrud {
        @Test
        @DisplayName("should find provider by slug")
        void shouldFindBySlug() {
            var found = providerRepo.findBySlug("test-provider");
            assertThat(found).isPresent();
        }
    }

    @Nested
    @DisplayName("Credential version lifecycle")
    class CredentialVersionLifecycle {
        @Test
        @DisplayName("should find active version")
        void shouldFindActiveVersion() {
            var active = versionRepo.findActiveByCredentialId(credential.id());
            assertThat(active).isPresent();
            assertThat(active.get().status()).isEqualTo(CredentialVersionStatus.ACTIVE);
        }

        @Test
        @DisplayName("should retire version")
        void shouldRetireVersion() {
            var retired = new UpstreamCredentialVersion(credentialVersion.id(), TENANT_ID,
                    credentialVersion.credentialId(), credentialVersion.encryptedSecret().clone(),
                    credentialVersion.nonce().clone(), credentialVersion.encryptionKeyVersion(),
                    credentialVersion.secretFingerprint().clone(), CredentialVersionStatus.RETIRED,
                    credentialVersion.validFrom(), NOW, credentialVersion.createdAt());
            versionRepo.update(retired);
            var found = versionRepo.findById(credentialVersion.id()).orElseThrow();
            assertThat(found.status()).isEqualTo(CredentialVersionStatus.RETIRED);
        }
    }

    @Nested
    @DisplayName("Grant lifecycle")
    class GrantLifecycle {
        @Test
        @DisplayName("should disable grant")
        void shouldDisableGrant() {
            var disabled = new ProjectProviderGrant(grant.id(), TENANT_ID, grant.projectId(), grant.providerProductId(),
                    grant.upstreamCredentialId(), GrantStatus.DISABLED, grant.createdBy(), grant.version() + 1, NOW,
                    NOW);
            grantRepo.update(disabled);
            var found = grantRepo.findById(grant.id()).orElseThrow();
            assertThat(found.status()).isEqualTo(GrantStatus.DISABLED);
        }
    }

    @Nested
    @DisplayName("Virtual Key lifecycle")
    class VirtualKeyLifecycle {
        @Test
        @DisplayName("should insert and find virtual key by public_key_id")
        void shouldInsertAndFindByPublicKeyId() {
            var key = new VirtualKey(UUID.randomUUID(), TENANT_ID, "mqk_test_key_abc",
                    new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, "mqk_", "bc12", user.id(), project.id(), grant.id(),
                    credential.id(), VirtualKeyPurpose.CLAUDE_CODE, "Test Key", VirtualKeyStatus.ACTIVE, NOW, null,
                    null, null, 0);
            vkRepo.insert(key);
            var found = vkRepo.findByPublicKeyId("mqk_test_key_abc");
            assertThat(found).isPresent();
            assertThat(found.get().purpose()).isEqualTo(VirtualKeyPurpose.CLAUDE_CODE);
        }

        @Test
        @DisplayName("should revoke virtual key")
        void shouldRevokeKey() {
            var keyId = UUID.randomUUID();
            var pubId = "mqk_revoke_" + UUID.randomUUID().toString().substring(0, 8);
            var key = new VirtualKey(keyId, TENANT_ID, pubId, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, "mqk_", "rv01",
                    user.id(), project.id(), grant.id(), credential.id(), VirtualKeyPurpose.CLAUDE_CODE, "Revocable",
                    VirtualKeyStatus.ACTIVE, NOW, null, null, null, 0);
            vkRepo.insert(key);

            var revoked = new VirtualKey(keyId, TENANT_ID, pubId, key.secretDigest(), key.displayPrefix(),
                    key.lastFour(), key.userId(), key.projectId(), key.grantId(), key.upstreamCredentialId(),
                    key.purpose(), key.name(), VirtualKeyStatus.REVOKED, key.createdAt(), key.lastUsedAt(), NOW, null,
                    key.version() + 1);
            vkRepo.update(revoked);

            var found = vkRepo.findByPublicKeyId(pubId).orElseThrow();
            assertThat(found.status()).isEqualTo(VirtualKeyStatus.REVOKED);
            assertThat(found.revokedAt()).isNotNull();
        }

        @Test
        @DisplayName("should list virtual keys by tenant")
        void shouldListByTenant() {
            var keys = vkRepo.findAllByTenantId(TENANT_ID);
            assertThat(keys).isNotNull();
        }
    }

    @Nested
    @DisplayName("Audit event")
    class AuditEventTest {
        @Test
        @DisplayName("should insert and query audit events")
        void shouldInsertAndQuery() {
            var event = new AdminAuditEvent(UUID.randomUUID(), TENANT_ID, user.id(), "user.create", "User", user.id(),
                    "{}", null, null, null, new byte[]{1, 2, 3}, NOW);
            auditRepo.insert(event);

            var results = auditRepo.findByTargetTypeAndTargetId("User", user.id());
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).action()).isEqualTo("user.create");
        }
    }

    @Nested
    @DisplayName("Optimistic locking")
    class OptimisticLocking {
        @Test
        @DisplayName("should detect stale version on update")
        void shouldDetectStaleVersion() {
            var original = userRepo.findById(user.id()).orElseThrow();
            var update1 = new User(user.id(), TENANT_ID, user.username(), "Updated1", user.passwordHash(),
                    UserRole.USER, UserStatus.ACTIVE, false, 0, null, null, original.version() + 1, NOW, NOW);
            userRepo.update(update1);

            // Stale: uses the old version
            var stale = new User(user.id(), TENANT_ID, user.username(), "Stale", user.passwordHash(), UserRole.USER,
                    UserStatus.ACTIVE, false, 0, null, null, original.version() + 1, NOW, NOW);
            assertThatThrownBy(() -> userRepo.update(stale)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Optimistic lock failure");
        }
    }
}
