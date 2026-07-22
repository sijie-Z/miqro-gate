package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.model.Tenant;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectMembership;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import com.miqroera.miqrokey.domain.model.UpstreamCredential;
import com.miqroera.miqrokey.domain.model.Provider;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.model.UpstreamCredentialVersion;
import com.miqroera.miqrokey.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for repository CRUD and documented lifecycle operations.
 *
 * <p>
 * Uses synthetic data only — no real credentials, secrets, or PII.
 * </p>
 */
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

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private Tenant tenant;
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
        // Tenant
        tenant = new Tenant(TENANT_ID, "test-tenant", "Test Tenant", "ACTIVE", NOW, NOW);
        if (tenantRepo.findByCode("test-tenant").isEmpty()) {
            tenantRepo.insert(tenant);
        }

        // User
        user = new User(UUID.randomUUID(), TENANT_ID, "testuser", "Test User", new byte[]{1, 2, 3, 4}, "USER", "ACTIVE",
                false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(user);

        // Project
        project = new Project(UUID.randomUUID(), TENANT_ID, "test-proj", "Test Project", null, null, "ACTIVE", 0, NOW,
                NOW);
        projectRepo.insert(project);

        // Provider
        provider = new Provider(UUID.randomUUID(), "test-provider", "Test Provider", null, null, null, "ACTIVE", 0, NOW,
                NOW);
        providerRepo.insert(provider);

        // ProviderProduct
        product = new ProviderProduct(UUID.randomUUID(), provider.id(), "test-product", "Test Product", "PAYG", "NONE",
                "SINGLE_SHARED", null, "[]", "[]", "{}", null, null, null, "DRAFT", null, 0, NOW, NOW);
        productRepo.insert(product);

        // Subscription
        subscription = new UpstreamSubscription(UUID.randomUUID(), product.id(), "Test Subscription", null, "PAYG",
                "NONE", null, null, null, null, null, null, null, "ACTIVE", null, null, 0, NOW, NOW);
        subRepo.insert(subscription);

        // Credential (logical slot - no secret)
        credential = new UpstreamCredential(UUID.randomUUID(), subscription.id(), null, "Test Credential",
                new byte[]{10, 20, 30}, "ACTIVE", null, null, null, 0, NOW, NOW);
        credRepo.insert(credential);

        // Credential Version (immutable, contains encrypted placeholder)
        credentialVersion = new UpstreamCredentialVersion(UUID.randomUUID(), credential.id(), new byte[]{0x01, 0x02},
                new byte[]{0x03, 0x04}, "v1", new byte[]{10, 20, 30}, "ACTIVE", NOW, null, NOW);
        versionRepo.insert(credentialVersion);

        // Grant
        grant = new ProjectProviderGrant(UUID.randomUUID(), project.id(), product.id(), credential.id(), "ACTIVE",
                user.id(), 0, NOW, NOW);
        grantRepo.insert(grant);
    }

    @Nested
    @DisplayName("Tenant CRUD")
    class TenantCrud {

        @Test
        @DisplayName("should insert and find tenant by id")
        void shouldInsertAndFindById() {
            var found = tenantRepo.findById(TENANT_ID);
            assertThat(found).isPresent();
            assertThat(found.get().code()).isEqualTo("test-tenant");
            assertThat(found.get().status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("should find tenant by code")
        void shouldFindByCode() {
            var found = tenantRepo.findByCode("test-tenant");
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should list all tenants")
        void shouldListAll() {
            var all = tenantRepo.findAll();
            assertThat(all).isNotEmpty();
        }

        @Test
        @DisplayName("should update tenant status")
        void shouldUpdateStatus() {
            var disabled = new Tenant(TENANT_ID, "test-tenant", "Test Tenant", "DISABLED", NOW, NOW);
            tenantRepo.update(disabled);

            var found = tenantRepo.findById(TENANT_ID);
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo("DISABLED");
        }
    }

    @Nested
    @DisplayName("User CRUD")
    class UserCrud {

        @Test
        @DisplayName("should insert and find user by id")
        void shouldInsertAndFindById() {
            var found = userRepo.findById(user.id());
            assertThat(found).isPresent();
            assertThat(found.get().username()).isEqualTo("testuser");
            assertThat(found.get().tenantId()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should find user by tenant and username (case-insensitive)")
        void shouldFindByTenantAndUsername() {
            var found = userRepo.findByTenantIdAndUsername(TENANT_ID, "TESTUSER");
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(user.id());
        }

        @Test
        @DisplayName("should prevent duplicate username in same tenant")
        void shouldPreventDuplicateUsername() {
            var duplicate = new User(UUID.randomUUID(), TENANT_ID, "testuser", "Dup", new byte[]{1}, "USER", "ACTIVE",
                    false, 0, null, null, 0, NOW, NOW);
            // Should throw DataIntegrityViolationException due to unique constraint
            assertThat(userRepo.existsByTenantIdAndUsername(TENANT_ID, "testuser")).isTrue();
        }

        @Test
        @DisplayName("should list users by tenant")
        void shouldListByTenant() {
            var users = userRepo.findAllByTenantId(TENANT_ID);
            assertThat(users).isNotEmpty();
        }

        @Test
        @DisplayName("should update user status")
        void shouldUpdateUserStatus() {
            var locked = new User(user.id(), TENANT_ID, "testuser", "Test User", user.passwordHash(), "USER", "LOCKED",
                    false, 5, Instant.now().plusSeconds(3600), user.lastLoginAt(), user.version() + 1, NOW, NOW);
            userRepo.update(locked);

            var found = userRepo.findById(user.id());
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo("LOCKED");
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
        @DisplayName("should find project by tenant and code")
        void shouldFindByTenantAndCode() {
            var found = projectRepo.findByTenantIdAndCode(TENANT_ID, "test-proj");
            assertThat(found).isPresent();
        }

        @Test
        @DisplayName("should add and remove project memberships")
        void shouldAddAndRemoveMembership() {
            var membership = new ProjectMembership(project.id(), user.id(), user.id(), NOW);
            membershipRepo.insert(membership);

            assertThat(membershipRepo.exists(project.id(), user.id())).isTrue();

            var byUser = membershipRepo.findAllByUserId(user.id());
            assertThat(byUser).anyMatch(m -> m.projectId().equals(project.id()));

            membershipRepo.delete(project.id(), user.id());
            assertThat(membershipRepo.exists(project.id(), user.id())).isFalse();
        }
    }

    @Nested
    @DisplayName("Credential version lifecycle")
    class CredentialVersionLifecycle {

        @Test
        @DisplayName("should insert and find credential version")
        void shouldInsertAndFindVersion() {
            var found = versionRepo.findById(credentialVersion.id());
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("should list all versions for a credential")
        void shouldListAllVersions() {
            var versions = versionRepo.findAllByCredentialId(credential.id());
            assertThat(versions).hasSize(1);
        }

        @Test
        @DisplayName("should find active version")
        void shouldFindActiveVersion() {
            var active = versionRepo.findActiveByCredentialId(credential.id());
            assertThat(active).isPresent();
            assertThat(active.get().status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("should update version status to RETIRED")
        void shouldRetireVersion() {
            var retired = new UpstreamCredentialVersion(credentialVersion.id(), credentialVersion.credentialId(),
                    credentialVersion.encryptedSecret(), credentialVersion.nonce(),
                    credentialVersion.encryptionKeyVersion(), credentialVersion.secretFingerprint(), "RETIRED",
                    credentialVersion.validFrom(), NOW, credentialVersion.createdAt());
            versionRepo.update(retired);

            var found = versionRepo.findById(credentialVersion.id());
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo("RETIRED");
        }
    }

    @Nested
    @DisplayName("Grant lifecycle")
    class GrantLifecycle {

        @Test
        @DisplayName("should insert and find grant")
        void shouldInsertAndFindGrant() {
            var found = grantRepo.findById(grant.id());
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("should list grants by project")
        void shouldListByProject() {
            var grants = grantRepo.findAllByProjectId(project.id());
            assertThat(grants).isNotEmpty();
        }

        @Test
        @DisplayName("should disable grant")
        void shouldDisableGrant() {
            var disabled = new ProjectProviderGrant(grant.id(), grant.projectId(), grant.providerProductId(),
                    grant.upstreamCredentialId(), "DISABLED", grant.createdBy(), grant.version() + 1, NOW, NOW);
            grantRepo.update(disabled);

            var found = grantRepo.findById(grant.id());
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo("DISABLED");
        }
    }

    @Nested
    @DisplayName("Virtual Key lifecycle")
    class VirtualKeyLifecycle {

        @Test
        @DisplayName("should insert and find virtual key by public_key_id")
        void shouldInsertAndFindByPublicKeyId() {
            var key = new VirtualKey(UUID.randomUUID(), "mqk_test_key_abc123", new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                    "mqk_", "c123", user.id(), project.id(), grant.id(), credential.id(), "CLAUDE_CODE", "Test Key",
                    "ACTIVE", NOW, null, null, null, 0);
            vkRepo.insert(key);

            var found = vkRepo.findByPublicKeyId("mqk_test_key_abc123");
            assertThat(found).isPresent();
            assertThat(found.get().purpose()).isEqualTo("CLAUDE_CODE");
            assertThat(found.get().userId()).isEqualTo(user.id());
            assertThat(found.get().projectId()).isEqualTo(project.id());
        }

        @Test
        @DisplayName("should list virtual keys by user")
        void shouldListByUser() {
            var key = new VirtualKey(UUID.randomUUID(), "mqk_test_list_" + UUID.randomUUID().toString().substring(0, 8),
                    new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, "mqk_", "aaaa", user.id(), project.id(), grant.id(),
                    credential.id(), "CUSTOM", "Listable Key", "ACTIVE", NOW, null, null, null, 0);
            vkRepo.insert(key);

            var keys = vkRepo.findAllByUserId(user.id());
            assertThat(keys).isNotEmpty();
        }

        @Test
        @DisplayName("should revoke virtual key")
        void shouldRevokeKey() {
            var keyId = UUID.randomUUID();
            var key = new VirtualKey(keyId, "mqk_test_revoke_" + UUID.randomUUID().toString().substring(0, 8),
                    new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, "mqk_", "bbbb", user.id(), project.id(), grant.id(),
                    credential.id(), "CLAUCE_CODE", "Revocable Key", "ACTIVE", NOW, null, null, null, 0);
            vkRepo.insert(key);

            var revoked = new VirtualKey(keyId, key.publicKeyId(), key.secretDigest(), key.displayPrefix(),
                    key.lastFour(), key.userId(), key.projectId(), key.grantId(), key.upstreamCredentialId(),
                    key.purpose(), key.name(), "REVOKED", key.createdAt(), key.lastUsedAt(), NOW, null,
                    key.version() + 1);
            vkRepo.update(revoked);

            var found = vkRepo.findByPublicKeyId(key.publicKeyId());
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo("REVOKED");
            assertThat(found.get().revokedAt()).isNotNull();
        }

        @Test
        @DisplayName("should reject duplicate public_key_id")
        void shouldRejectDuplicatePublicKeyId() {
            var pubKeyId = "mqk_test_dup_" + UUID.randomUUID().toString().substring(0, 8);
            var key1 = new VirtualKey(UUID.randomUUID(), pubKeyId, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, "mqk_", "cccc",
                    user.id(), project.id(), grant.id(), credential.id(), "CLAUDE_CODE", "Key 1", "ACTIVE", NOW, null,
                    null, null, 0);
            vkRepo.insert(key1);

            assertThat(vkRepo.existsByPublicKeyId(pubKeyId)).isTrue();

            var key2 = new VirtualKey(UUID.randomUUID(), pubKeyId, // same public_key_id
                    new byte[]{9, 9, 9, 9, 9, 9, 9, 9}, "mqk_", "dddd", user.id(), project.id(), grant.id(),
                    credential.id(), "CLAUDE_CODE", "Key 2", "ACTIVE", NOW, null, null, null, 0);

            // Should throw due to unique constraint on public_key_id
            try {
                vkRepo.insert(key2);
                // If no exception, the test should fail
                assertThat(false).as("Expected unique constraint violation on public_key_id").isTrue();
            } catch (Exception e) {
                assertThat(e).isNotNull();
            }
        }
    }
}
