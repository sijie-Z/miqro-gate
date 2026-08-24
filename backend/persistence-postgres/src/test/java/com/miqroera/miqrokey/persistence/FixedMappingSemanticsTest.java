package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Fixed mapping semantics")
class FixedMappingSemanticsTest extends AbstractPostgresTest {

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

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("should reject grant with credential not belonging to same-provider-product subscription")
    void shouldRejectGrantWithMismatchedProduct() {
        var user = new User(UUID.randomUUID(), TENANT_ID, "mapuser1", "MU1", new byte[]{1}, UserRole.USER,
                UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(user);
        var project = new Project(UUID.randomUUID(), TENANT_ID, "map-proj1", "MP1", null, null, ProjectStatus.ACTIVE,
                null, 0, NOW, NOW);
        projectRepo.insert(project);

        var provider = new Provider(UUID.randomUUID(), "map-prov-" + UUID.randomUUID().toString().substring(0, 6), "MP",
                null, null, null, ProviderStatus.ACTIVE, 0, NOW, NOW);
        providerRepo.insert(provider);

        var product1 = new ProviderProduct(UUID.randomUUID(), provider.id(),
                "pc1-" + UUID.randomUUID().toString().substring(0, 6), "P1", BillingMode.PAYG, PlanScope.NONE,
                CredentialTopology.SINGLE_SHARED, null, "[]", "[]", "{}", null, null, null, ImplementationStatus.DRAFT,
                null, 0, NOW, NOW);
        var product2 = new ProviderProduct(UUID.randomUUID(), provider.id(),
                "pc2-" + UUID.randomUUID().toString().substring(0, 6), "P2", BillingMode.PAYG, PlanScope.NONE,
                CredentialTopology.SINGLE_SHARED, null, "[]", "[]", "{}", null, null, null, ImplementationStatus.DRAFT,
                null, 0, NOW, NOW);
        productRepo.insert(product1);
        productRepo.insert(product2);

        // Subscription for product 1
        var sub1 = new UpstreamSubscription(UUID.randomUUID(), TENANT_ID, product1.id(), "Sub1", null, BillingMode.PAYG,
                PlanScope.NONE, null, null, null, null, null, null, null, SubscriptionStatus.ACTIVE, null, null, 0, NOW,
                NOW);
        subRepo.insert(sub1);

        // Credential belongs to sub1 (product1)
        var cred1 = new UpstreamCredential(UUID.randomUUID(), TENANT_ID, sub1.id(), null, "Cred1", new byte[]{1},
                CredentialStatus.ACTIVE, null, null, null, 0, NOW, NOW);
        credRepo.insert(cred1);

        // Grant references product2 but credential is from product1's subscription →
        // trigger rejects
        assertThatThrownBy(() -> {
            grantRepo.insert(new ProjectProviderGrant(UUID.randomUUID(), TENANT_ID, project.id(), product2.id(),
                    cred1.id(), GrantStatus.ACTIVE, user.id(), 0, NOW, NOW));
        }).isNotNull();
    }

    @Test
    @DisplayName("should reject virtual key with grant not matching credential in same row")
    void shouldRejectVirtualKeyWithUnmatchedGrantCredential() {
        var user = new User(UUID.randomUUID(), TENANT_ID, "vkuser2", "VKU2", new byte[]{1}, UserRole.USER,
                UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(user);
        var project = new Project(UUID.randomUUID(), TENANT_ID, "vk-proj2", "VP2", null, null, ProjectStatus.ACTIVE,
                null, 0, NOW, NOW);
        projectRepo.insert(project);

        var provider = new Provider(UUID.randomUUID(), "vk-prov-" + UUID.randomUUID().toString().substring(0, 6), "VP",
                null, null, null, ProviderStatus.ACTIVE, 0, NOW, NOW);
        providerRepo.insert(provider);
        var product = new ProviderProduct(UUID.randomUUID(), provider.id(),
                "vkp-" + UUID.randomUUID().toString().substring(0, 6), "VKP", BillingMode.PAYG, PlanScope.NONE,
                CredentialTopology.SINGLE_SHARED, null, "[]", "[]", "{}", null, null, null, ImplementationStatus.DRAFT,
                null, 0, NOW, NOW);
        productRepo.insert(product);

        var sub = new UpstreamSubscription(UUID.randomUUID(), TENANT_ID, product.id(), "VKSub", null, BillingMode.PAYG,
                PlanScope.NONE, null, null, null, null, null, null, null, SubscriptionStatus.ACTIVE, null, null, 0, NOW,
                NOW);
        subRepo.insert(sub);

        // Credential A with subscription sub
        var credA = new UpstreamCredential(UUID.randomUUID(), TENANT_ID, sub.id(), null, "CredA", new byte[]{1},
                CredentialStatus.ACTIVE, null, null, null, 0, NOW, NOW);
        credRepo.insert(credA);

        // Grant G1 references product and credA
        var grant1 = new ProjectProviderGrant(UUID.randomUUID(), TENANT_ID, project.id(), product.id(), credA.id(),
                GrantStatus.ACTIVE, user.id(), 0, NOW, NOW);
        grantRepo.insert(grant1);

        // Virtual Key referencing grant1 but different credential (crosses grant
        // boundary)
        // The DB trigger enforces that grant references the same credential
        var credB = new UpstreamCredential(UUID.randomUUID(), TENANT_ID, sub.id(), null, "CredB", new byte[]{2},
                CredentialStatus.ACTIVE, null, null, null, 0, NOW, NOW);
        credRepo.insert(credB);

        assertThatThrownBy(() -> {
            vkRepo.insert(new VirtualKey(UUID.randomUUID(), TENANT_ID,
                    "mqk_badmap_" + UUID.randomUUID().toString().substring(0, 6), new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                    "mqk_", "bm01", user.id(), project.id(), grant1.id(), credB.id(), // credB != grant1.credential
                    VirtualKeyPurpose.CLAUDE_CODE, "Bad Map", "DISABLED", VirtualKeyStatus.ACTIVE, NOW, null, null,
                    null, 0));
        }).isNotNull(); // DB trigger enforces grant_id matches credential_id
    }
}
