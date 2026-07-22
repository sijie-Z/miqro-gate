package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    private Tenant tenantA, tenantB;
    private User userA, userB;
    private Project projectA, projectB;

    @BeforeEach
    void setUp() {
        tenantA = new Tenant(UUID.randomUUID(), "tenant-alpha-" + UUID.randomUUID().toString().substring(0, 4), "Alpha",
                TenantStatus.ACTIVE, 0, NOW, NOW);
        tenantB = new Tenant(UUID.randomUUID(), "tenant-beta-" + UUID.randomUUID().toString().substring(0, 4), "Beta",
                TenantStatus.ACTIVE, 0, NOW, NOW);
        tenantRepo.insert(tenantA);
        tenantRepo.insert(tenantB);

        userA = new User(UUID.randomUUID(), tenantA.id(), "alice", "Alice", new byte[]{1}, UserRole.USER,
                UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userB = new User(UUID.randomUUID(), tenantB.id(), "bob", "Bob", new byte[]{2}, UserRole.USER, UserStatus.ACTIVE,
                false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(userA);
        userRepo.insert(userB);

        projectA = new Project(UUID.randomUUID(), tenantA.id(), "proj-alpha", "Alpha Project", null, null,
                ProjectStatus.ACTIVE, 0, NOW, NOW);
        projectB = new Project(UUID.randomUUID(), tenantB.id(), "proj-beta", "Beta Project", null, null,
                ProjectStatus.ACTIVE, 0, NOW, NOW);
        projectRepo.insert(projectA);
        projectRepo.insert(projectB);
    }

    @Test
    @DisplayName("should isolate users by tenant")
    void shouldIsolateUsersByTenant() {
        assertThat(userRepo.findAllByTenantId(tenantA.id())).hasSize(1);
        assertThat(userRepo.findAllByTenantId(tenantB.id())).hasSize(1);
    }

    @Test
    @DisplayName("should isolate projects by tenant")
    void shouldIsolateProjectsByTenant() {
        assertThat(projectRepo.findAllByTenantId(tenantA.id())).hasSize(1);
        assertThat(projectRepo.findAllByTenantId(tenantB.id())).hasSize(1);
    }

    @Test
    @DisplayName("should allow same project code in different tenants")
    void shouldAllowSameCodeInDifferentTenants() {
        var sameCodeProjB = new Project(UUID.randomUUID(), tenantB.id(), "proj-alpha", "Also Alpha", null, null,
                ProjectStatus.ACTIVE, 0, NOW, NOW);
        projectRepo.insert(sameCodeProjB);
        assertThat(projectRepo.existsByTenantIdAndCode(tenantA.id(), "proj-alpha")).isTrue();
        assertThat(projectRepo.existsByTenantIdAndCode(tenantB.id(), "proj-alpha")).isTrue();
    }

    @Test
    @DisplayName("should allow same username in different tenants")
    void shouldAllowSameUsernameInDifferentTenants() {
        var aliceB = new User(UUID.randomUUID(), tenantB.id(), "alice", "Alice B", new byte[]{3}, UserRole.USER,
                UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(aliceB);
        assertThat(userRepo.findByTenantIdAndUsername(tenantA.id(), "alice")).isPresent();
        assertThat(userRepo.findByTenantIdAndUsername(tenantB.id(), "alice")).isPresent();
    }

    @Test
    @DisplayName("should isolate membership lists by project")
    void shouldIsolateMembershipByProject() {
        membershipRepo.insert(new ProjectMembership(tenantA.id(), projectA.id(), userA.id(), userA.id(), NOW));
        assertThat(membershipRepo.findAllByProjectId(projectA.id())).hasSize(1);
        assertThat(membershipRepo.findAllByProjectId(projectB.id())).isEmpty();
    }
}
