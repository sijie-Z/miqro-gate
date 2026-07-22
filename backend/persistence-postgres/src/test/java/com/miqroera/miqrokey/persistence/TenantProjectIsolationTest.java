package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies tenant-level and project-level data isolation. Users in one tenant
 * should not see or affect resources in another tenant. Projects are scoped to
 * their tenant.
 */
@DisplayName("Tenant and project isolation")
class TenantProjectIsolationTest extends AbstractPostgresTest {

    @Autowired
    private TenantRepository tenantRepo;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private ProjectRepository projectRepo;
    @Autowired
    private ProjectMembershipRepository membershipRepo;
    @Autowired
    private VirtualKeyRepository vkRepo;

    private static final Instant NOW = Instant.now();

    private Tenant tenantA;
    private Tenant tenantB;
    private User userA;
    private User userB;
    private Project projectA;
    private Project projectB;

    @BeforeEach
    void setUp() {
        // Two distinct tenants
        tenantA = new Tenant(UUID.randomUUID(), "tenant-alpha", "Alpha", "ACTIVE", NOW, NOW);
        tenantB = new Tenant(UUID.randomUUID(), "tenant-beta", "Beta", "ACTIVE", NOW, NOW);
        tenantRepo.insert(tenantA);
        tenantRepo.insert(tenantB);

        // User in each tenant
        userA = new User(UUID.randomUUID(), tenantA.id(), "alice", "Alice", new byte[]{1}, "USER", "ACTIVE", false, 0,
                null, null, 0, NOW, NOW);
        userB = new User(UUID.randomUUID(), tenantB.id(), "bob", "Bob", new byte[]{2}, "USER", "ACTIVE", false, 0, null,
                null, 0, NOW, NOW);
        userRepo.insert(userA);
        userRepo.insert(userB);

        // Project in each tenant
        projectA = new Project(UUID.randomUUID(), tenantA.id(), "proj-alpha", "Alpha Project", null, null, "ACTIVE", 0,
                NOW, NOW);
        projectB = new Project(UUID.randomUUID(), tenantB.id(), "proj-beta", "Beta Project", null, null, "ACTIVE", 0,
                NOW, NOW);
        projectRepo.insert(projectA);
        projectRepo.insert(projectB);
    }

    @Test
    @DisplayName("should isolate users by tenant")
    void shouldIsolateUsersByTenant() {
        // Users from tenant A
        var usersA = userRepo.findAllByTenantId(tenantA.id());
        assertThat(usersA).hasSize(1);
        assertThat(usersA.get(0).username()).isEqualTo("alice");

        // Users from tenant B
        var usersB = userRepo.findAllByTenantId(tenantB.id());
        assertThat(usersB).hasSize(1);
        assertThat(usersB.get(0).username()).isEqualTo("bob");
    }

    @Test
    @DisplayName("should isolate projects by tenant")
    void shouldIsolateProjectsByTenant() {
        var projectsA = projectRepo.findAllByTenantId(tenantA.id());
        assertThat(projectsA).hasSize(1);
        assertThat(projectsA.get(0).code()).isEqualTo("proj-alpha");

        var projectsB = projectRepo.findAllByTenantId(tenantB.id());
        assertThat(projectsB).hasSize(1);
        assertThat(projectsB.get(0).code()).isEqualTo("proj-beta");
    }

    @Test
    @DisplayName("should allow same project code in different tenants")
    void shouldAllowSameCodeInDifferentTenants() {
        // project code "proj-alpha" exists in tenant A
        // Should be able to create "proj-alpha" in tenant B
        var sameCodeProjB = new Project(UUID.randomUUID(), tenantB.id(), "proj-alpha", "Also Alpha", null, null,
                "ACTIVE", 0, NOW, NOW);
        projectRepo.insert(sameCodeProjB);

        // Both exist with same code
        assertThat(projectRepo.existsByTenantIdAndCode(tenantA.id(), "proj-alpha")).isTrue();
        assertThat(projectRepo.existsByTenantIdAndCode(tenantB.id(), "proj-alpha")).isTrue();
    }

    @Test
    @DisplayName("should reject username lookup across tenants")
    void shouldRejectCrossTenantUsernameLookup() {
        // "alice" is in tenant A, should not be found in tenant B
        var found = userRepo.findByTenantIdAndUsername(tenantB.id(), "alice");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("should allow same username in different tenants")
    void shouldAllowSameUsernameInDifferentTenants() {
        // Create "alice" in tenant B too
        var aliceB = new User(UUID.randomUUID(), tenantB.id(), "alice", "Alice B", new byte[]{3}, "USER", "ACTIVE",
                false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(aliceB);

        // Both should be findable within their own tenants
        var aInA = userRepo.findByTenantIdAndUsername(tenantA.id(), "alice");
        var aInB = userRepo.findByTenantIdAndUsername(tenantB.id(), "alice");

        assertThat(aInA).isPresent();
        assertThat(aInB).isPresent();
        assertThat(aInA.get().id()).isNotEqualTo(aInB.get().id());
    }

    @Test
    @DisplayName("should isolate membership lists by project")
    void shouldIsolateMembershipByProject() {
        // Add user A to project A only
        membershipRepo.insert(new ProjectMembership(projectA.id(), userA.id(), userA.id(), NOW));

        var membersOfA = membershipRepo.findAllByProjectId(projectA.id());
        assertThat(membersOfA).hasSize(1);

        var membersOfB = membershipRepo.findAllByProjectId(projectB.id());
        assertThat(membersOfB).isEmpty();
    }
}
