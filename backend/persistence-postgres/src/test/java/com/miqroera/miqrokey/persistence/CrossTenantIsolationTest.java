package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cross-tenant isolation")
class CrossTenantIsolationTest extends AbstractPostgresTest {

    @Autowired
    private TenantRepository tenantRepo;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private ProjectRepository projectRepo;
    @Autowired
    private ProjectMembershipRepository membershipRepo;
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
    private ProviderRepository providerRepo;
    @Autowired
    private ProviderProductRepository productRepo;

    private static final UUID SEED_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.now();

    private UUID tenantA;
    private UUID tenantB;
    private User userA, userB;
    private Project projectA, projectB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tenantRepo.insert(new Tenant(tenantA, "tenant-a-" + tenantA.toString().substring(0, 6), "Tenant A",
                TenantStatus.ACTIVE, 0, NOW, NOW));
        tenantRepo.insert(new Tenant(tenantB, "tenant-b-" + tenantB.toString().substring(0, 6), "Tenant B",
                TenantStatus.ACTIVE, 0, NOW, NOW));

        userA = new User(UUID.randomUUID(), tenantA, "user-a", "User A", new byte[]{1}, UserRole.USER,
                UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userB = new User(UUID.randomUUID(), tenantB, "user-b", "User B", new byte[]{2}, UserRole.USER,
                UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(userA);
        userRepo.insert(userB);

        projectA = new Project(UUID.randomUUID(), tenantA, "proj-a", "Project A", null, null, ProjectStatus.ACTIVE,
                null, 0, NOW, NOW);
        projectB = new Project(UUID.randomUUID(), tenantB, "proj-b", "Project B", null, null, ProjectStatus.ACTIVE,
                null, 0, NOW, NOW);
        projectRepo.insert(projectA);
        projectRepo.insert(projectB);
    }

    @Test
    @DisplayName("should reject cross-tenant membership insert")
    void shouldRejectCrossTenantMembership() {
        // Try to add user from tenant A to project in tenant B (wrong tenantId)
        assertThatThrownBy(() -> {
            membershipRepo.insert(new ProjectMembership(tenantB, projectB.id(), userA.id(), userA.id(), NOW));
        }).isNotNull(); // FK violation from composite FK
    }

    @Test
    @DisplayName("should isolate users by tenant")
    void shouldIsolateUsersByTenant() {
        var usersA = userRepo.findAllByTenantId(tenantA);
        assertThat(usersA).hasSize(1);
        assertThat(userRepo.findAllByTenantId(tenantB)).hasSize(1);
    }

    @Test
    @DisplayName("should allow same username in different tenants")
    void shouldAllowSameUsernameInDifferentTenants() {
        var aliceB = new User(UUID.randomUUID(), tenantB, "user-a", "User A in B", new byte[]{3}, UserRole.USER,
                UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(aliceB);
        assertThat(userRepo.findByTenantIdAndUsername(tenantA, "user-a")).isPresent();
        assertThat(userRepo.findByTenantIdAndUsername(tenantB, "user-a")).isPresent();
    }

    @Test
    @DisplayName("should reject grant with credential from different tenant")
    void shouldRejectCrossTenantGrant() {
        var provA = new Provider(UUID.randomUUID(), "slug-" + UUID.randomUUID().toString().substring(0, 6), "P", null,
                null, null, ProviderStatus.ACTIVE, 0, NOW, NOW);
        providerRepo.insert(provA);
        var prodA = new ProviderProduct(UUID.randomUUID(), provA.id(),
                "pc-" + UUID.randomUUID().toString().substring(0, 6), "PP", BillingMode.PAYG, PlanScope.NONE,
                CredentialTopology.SINGLE_SHARED, null, "[]", "[]", "{}", null, null, null, ImplementationStatus.DRAFT,
                null, 0, NOW, NOW);
        productRepo.insert(prodA);

        var subA = new UpstreamSubscription(UUID.randomUUID(), tenantA, prodA.id(), "Sub", null, BillingMode.PAYG,
                PlanScope.NONE, null, null, null, null, null, null, null, SubscriptionStatus.ACTIVE, null, null, 0, NOW,
                NOW);
        subRepo.insert(subA);

        var credA = new UpstreamCredential(UUID.randomUUID(), tenantA, subA.id(), null, "CredA", new byte[]{1},
                CredentialStatus.ACTIVE, null, null, null, 0, NOW, NOW);
        credRepo.insert(credA);

        // Grant for tenant A project but referencing tenant A credential - works
        var grantOk = new ProjectProviderGrant(UUID.randomUUID(), tenantA, projectA.id(), prodA.id(), credA.id(),
                GrantStatus.ACTIVE, userA.id(), 0, NOW, NOW);
        grantRepo.insert(grantOk);

        // Grant for tenant B project but referencing tenant A credential - should fail
        // The composite FK (tenant_id, upstream_credential_id) REFERENCES
        // upstream_credentials(tenant_id, id)
        // will reject because tenantB != tenantA on the credential
        assertThatThrownBy(() -> {
            grantRepo.insert(new ProjectProviderGrant(UUID.randomUUID(), tenantB, projectB.id(), prodA.id(), credA.id(),
                    GrantStatus.ACTIVE, userB.id(), 0, NOW, NOW));
        }).isNotNull(); // FK violation
    }

    @Test
    @DisplayName("should reject virtual key with cross-tenant grant")
    void shouldRejectCrossTenantVirtualKey() {
        // The composite FKs on virtual_keys will reject cross-tenant
        // We verify by trying a key that references a tenant-A user with a tenant-B
        // project
        assertThatThrownBy(() -> {
            vkRepo.insert(new VirtualKey(UUID.randomUUID(), tenantB,
                    "mqk_cross_" + UUID.randomUUID().toString().substring(0, 6), new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                    "mqk_", "xc01", userA.id(), projectB.id(), // cross-tenant: userA in A, projectB in B
                    UUID.randomUUID(), UUID.randomUUID(), VirtualKeyPurpose.CLAUDE_CODE, "Cross Key", "DISABLED",
                    VirtualKeyStatus.ACTIVE, NOW, null, null, null, 0));
        }).isNotNull(); // Composite FK rejects: tenantB,userA doesn't match users(tenantB,id)
    }
}
