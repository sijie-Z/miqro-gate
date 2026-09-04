package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.security.SessionService;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectMembership;
import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import com.miqroera.miqrokey.domain.model.GrantStatus;
import com.miqroera.miqrokey.domain.model.ProjectStatus;
import com.miqroera.miqrokey.domain.model.Team;
import com.miqroera.miqrokey.domain.model.TeamStatus;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.ProjectMembershipRepository;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.TeamRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialRepository;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin organization operations (G5.2, api-contract §5): users (create /
 * disable / reset-password / revoke sessions), teams + members, projects, and
 * project grants with model scopes. All operations are SYSTEM_ADMIN-only via
 * the deny-by-default {@code /api/v1/admin/**} interceptor; temporary passwords
 * are returned exactly once and never persisted in plaintext.
 */
@Service
public class AdminOrgService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository projectMembershipRepository;
    private final ProjectProviderGrantRepository grantRepository;
    private final ProviderProductRepository productRepository;
    private final UpstreamCredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final AdminQuotaDefaultTemplateService quotaDefaultTemplateService;
    private final NamedParameterJdbcTemplate jdbc;

    public AdminOrgService(UserRepository userRepository, TeamRepository teamRepository,
            ProjectRepository projectRepository, ProjectMembershipRepository projectMembershipRepository,
            ProjectProviderGrantRepository grantRepository, UpstreamCredentialRepository credentialRepository,
            ProviderProductRepository productRepository, PasswordHasher passwordHasher, SessionService sessionService,
            AuditService auditService, AdminQuotaDefaultTemplateService quotaDefaultTemplateService,
            NamedParameterJdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.projectRepository = projectRepository;
        this.projectMembershipRepository = projectMembershipRepository;
        this.grantRepository = grantRepository;
        this.productRepository = productRepository;
        this.credentialRepository = credentialRepository;
        this.passwordHasher = passwordHasher;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.quotaDefaultTemplateService = quotaDefaultTemplateService;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // users
    // ------------------------------------------------------------------

    public List<User> listUsers(UUID tenantId) {
        return userRepository.findAllByTenantId(tenantId).stream().map(AdminOrgService::sanitize).toList();
    }

    /** Creates a user and returns the one-time temporary password. */
    @Transactional
    public UserCreated createUser(UUID tenantId, UUID adminId, String username, String displayName, UserRole role) {
        if (username == null || username.isBlank() || username.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USERNAME_INVALID", "username is required (<= 128 chars)");
        }
        if (userRepository.findByTenantIdAndUsername(tenantId, username).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "username already exists");
        }
        String temporaryPassword = generateTemporaryPassword();
        User user = new User(UUID.randomUUID(), tenantId, username,
                displayName != null && !displayName.isBlank() ? displayName : username,
                passwordHasher.hash(temporaryPassword), role != null ? role : UserRole.USER, UserStatus.ACTIVE, true, 0,
                null, null, 0L, Instant.now(), Instant.now());
        userRepository.insert(user);
        auditService.record(tenantId, adminId, "USER_CREATE", "USER", user.id(),
                "{\"username\":\"" + username + "\",\"role\":\"" + user.role().name() + "\"}", null);
        quotaDefaultTemplateService.applyToNewUser(tenantId, adminId, user.id());
        return new UserCreated(sanitize(user), temporaryPassword);
    }

    public User updateUserStatus(UUID tenantId, UUID adminId, UUID userId, UserStatus status) {
        User user = requireUser(tenantId, userId);
        if (user.role() == UserRole.SYSTEM_ADMIN && status == UserStatus.DISABLED) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_NOT_DISABLEABLE", "system admins cannot be disabled");
        }
        User updated = new User(user.id(), user.tenantId(), user.username(), user.displayName(), user.passwordHash(),
                user.role(), status, user.mustChangePassword(), user.failedLoginCount(), user.lockedUntil(),
                user.lastLoginAt(), user.version() + 1, user.createdAt(), Instant.now());
        userRepository.update(updated);
        if (status == UserStatus.DISABLED) {
            sessionService.revokeOtherSessions(userId, null);
        }
        auditService.record(tenantId, adminId, "USER_STATUS", "USER", userId, "{\"status\":\"" + status.name() + "\"}",
                null);
        return sanitize(updated);
    }

    /**
     * Resets the password and revokes all sessions; returns the new temporary
     * password once.
     */
    @Transactional
    public UserPasswordReset resetPassword(UUID tenantId, UUID adminId, UUID userId) {
        User user = requireUser(tenantId, userId);
        String temporaryPassword = generateTemporaryPassword();
        User updated = new User(user.id(), user.tenantId(), user.username(), user.displayName(),
                passwordHasher.hash(temporaryPassword), user.role(), user.status(), true, 0, null, null,
                user.version() + 1, user.createdAt(), Instant.now());
        userRepository.update(updated);
        sessionService.revokeOtherSessions(userId, null);
        auditService.record(tenantId, adminId, "USER_PASSWORD_RESET", "USER", userId, "{}", null);
        return new UserPasswordReset(sanitize(updated), temporaryPassword);
    }

    public void revokeSessions(UUID tenantId, UUID adminId, UUID userId) {
        requireUser(tenantId, userId);
        sessionService.revokeOtherSessions(userId, null);
        auditService.record(tenantId, adminId, "USER_SESSIONS_REVOKED", "USER", userId, "{}", null);
    }

    // ------------------------------------------------------------------
    // teams + members
    // ------------------------------------------------------------------

    public List<Team> listTeams(UUID tenantId) {
        return teamRepository.findAllByTenantId(tenantId);
    }

    @Transactional
    public Team createTeam(UUID tenantId, UUID adminId, String name, String description) {
        Team team = new Team(UUID.randomUUID(), tenantId, name, description, TeamStatus.ACTIVE, 0, Instant.now(),
                Instant.now());
        teamRepository.insert(team);
        auditService.record(tenantId, adminId, "TEAM_CREATE", "TEAM", team.id(), "{\"name\":\"" + name + "\"}", null);
        return team;
    }

    public Team updateTeam(UUID tenantId, UUID adminId, UUID teamId, String name, String description,
            TeamStatus status) {
        Team team = requireTeam(tenantId, teamId);
        Team updated = new Team(team.id(), team.tenantId(), name != null ? name : team.name(),
                description != null ? description : team.description(), status != null ? status : team.status(),
                team.version() + 1, team.createdAt(), Instant.now());
        teamRepository.update(updated);
        auditService.record(tenantId, adminId, "TEAM_UPDATE", "TEAM", teamId, "{}", null);
        return updated;
    }

    /** Team members (flattened: member rows + user display names). */
    public List<TeamMemberView> teamMembers(UUID tenantId, UUID teamId) {
        requireTeam(tenantId, teamId);
        return jdbc.query("""
                SELECT tm.team_id, tm.user_id, tm.created_by, tm.created_at, u.username, u.display_name
                FROM team_memberships tm
                JOIN users u ON u.id = tm.user_id AND u.tenant_id = tm.tenant_id
                WHERE tm.tenant_id = :tenantId AND tm.team_id = :teamId
                ORDER BY u.username
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("teamId", teamId),
                (rs, rowNum) -> new TeamMemberView((UUID) rs.getObject("user_id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getTimestamp("created_at").toInstant()));
    }

    @Transactional
    public void addTeamMember(UUID tenantId, UUID adminId, UUID teamId, UUID userId) {
        requireTeam(tenantId, teamId);
        requireUser(tenantId, userId);
        jdbc.update("""
                INSERT INTO team_memberships (tenant_id, team_id, user_id, created_by, created_at)
                VALUES (:tenantId, :teamId, :userId, :createdBy, now())
                ON CONFLICT (team_id, user_id) DO NOTHING
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("teamId", teamId)
                .addValue("userId", userId).addValue("createdBy", adminId));
        auditService.record(tenantId, adminId, "TEAM_MEMBER_ADD", "TEAM", teamId, "{\"userId\":\"" + userId + "\"}",
                null);
    }

    @Transactional
    public void removeTeamMember(UUID tenantId, UUID adminId, UUID teamId, UUID userId) {
        requireTeam(tenantId, teamId);
        jdbc.update(
                "DELETE FROM team_memberships WHERE tenant_id = :tenantId AND team_id = :teamId"
                        + " AND user_id = :userId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("teamId", teamId).addValue("userId", userId));
        auditService.record(tenantId, adminId, "TEAM_MEMBER_REMOVE", "TEAM", teamId, "{\"userId\":\"" + userId + "\"}",
                null);
    }

    // ------------------------------------------------------------------
    // projects + members
    // ------------------------------------------------------------------

    public List<Project> listProjects(UUID tenantId) {
        return projectRepository.findAllByTenantId(tenantId);
    }

    @Transactional
    public Project createProject(UUID tenantId, UUID adminId, String code, String name, String projectTag) {
        requireValidProjectTag(projectTag);
        if (code == null || code.isBlank() || projectRepository.existsByTenantIdAndCode(tenantId, code)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_CODE_TAKEN",
                    "project code is required and must be unique");
        }
        Project project = new Project(UUID.randomUUID(), tenantId, code, name, name, null, ProjectStatus.ACTIVE,
                projectTag, 0, Instant.now(), Instant.now());
        projectRepository.insert(project);
        auditService.record(tenantId, adminId, "PROJECT_CREATE", "PROJECT", project.id(), "{\"code\":\"" + code + "\"}",
                null);
        return project;
    }

    public Project updateProject(UUID tenantId, UUID adminId, UUID projectId, String name, String projectTag,
            ProjectStatus status) {
        requireValidProjectTag(projectTag);
        Project project = requireProject(tenantId, projectId);
        Project updated = new Project(project.id(), project.tenantId(), project.code(),
                name != null ? name : project.name(), project.description(), project.costCenter(),
                status != null ? status : project.status(), projectTag != null ? projectTag : project.projectTag(),
                project.version() + 1, project.createdAt(), Instant.now());
        projectRepository.update(updated);
        auditService.record(tenantId, adminId, "PROJECT_UPDATE", "PROJECT", projectId, "{}", null);
        return updated;
    }

    public List<ProjectMemberView> projectMembers(UUID tenantId, UUID projectId) {
        requireProject(tenantId, projectId);
        return jdbc.query("""
                SELECT pm.user_id, pm.created_by, pm.created_at, u.username, u.display_name
                FROM project_memberships pm
                JOIN users u ON u.id = pm.user_id AND u.tenant_id = pm.tenant_id
                WHERE pm.tenant_id = :tenantId AND pm.project_id = :projectId
                ORDER BY u.username
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("projectId", projectId),
                (rs, rowNum) -> new ProjectMemberView((UUID) rs.getObject("user_id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getTimestamp("created_at").toInstant()));
    }

    /** Projects a user is a member of (quick-join entry point, F-REG loop). */
    public List<UserProjectMembershipView> userProjectMemberships(UUID tenantId, UUID userId) {
        requireUser(tenantId, userId);
        return jdbc.query("""
                SELECT p.id, p.code, p.name, p.status, pm.created_at AS joined_at
                FROM project_memberships pm
                JOIN projects p ON p.id = pm.project_id AND p.tenant_id = pm.tenant_id
                WHERE pm.tenant_id = :tenantId AND pm.user_id = :userId
                ORDER BY p.code
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("userId", userId),
                (rs, rowNum) -> new UserProjectMembershipView((UUID) rs.getObject("id"), rs.getString("code"),
                        rs.getString("name"), rs.getString("status"), rs.getTimestamp("joined_at").toInstant()));
    }

    @Transactional
    public void addProjectMember(UUID tenantId, UUID adminId, UUID projectId, UUID userId) {
        requireProject(tenantId, projectId);
        requireUser(tenantId, userId);
        ProjectMembership membership = new ProjectMembership(tenantId, projectId, userId, adminId, Instant.now());
        projectMembershipRepository.insert(membership);
        auditService.record(tenantId, adminId, "PROJECT_MEMBER_ADD", "PROJECT", projectId,
                "{\"userId\":\"" + userId + "\"}", null);
    }

    @Transactional
    public void removeProjectMember(UUID tenantId, UUID adminId, UUID projectId, UUID userId) {
        requireProject(tenantId, projectId);
        projectMembershipRepository.delete(projectId, userId);
        auditService.record(tenantId, adminId, "PROJECT_MEMBER_REMOVE", "PROJECT", projectId,
                "{\"userId\":\"" + userId + "\"}", null);
    }

    // ------------------------------------------------------------------
    // grants + model scopes
    // ------------------------------------------------------------------

    public List<ProjectProviderGrant> listGrants(UUID tenantId) {
        return jdbc.query("""
                SELECT * FROM project_provider_grants WHERE tenant_id = :tenantId ORDER BY created_at
                """, new MapSqlParameterSource("tenantId", tenantId), GRANT_ROW_MAPPER);
    }

    /** Creates a grant (project × product × credential) with its model scope. */
    @Transactional
    public ProjectProviderGrant createGrant(UUID tenantId, UUID adminId, UUID projectId, UUID providerProductId,
            UUID credentialId, List<String> models) {
        requireProject(tenantId, projectId);
        credentialRepository.findById(credentialId).filter(c -> c.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND",
                        "Credential not found or not visible"));
        if (providerProductId != null) {
            productRepository.findById(providerProductId).orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Provider product not found"));
        }
        if (grantRepository.existsByProjectIdAndProductIdAndCredentialId(projectId, providerProductId, credentialId)) {
            throw new ApiException(HttpStatus.CONFLICT, "GRANT_EXISTS",
                    "a grant for this project/product/credential already exists");
        }
        ProjectProviderGrant grant = new ProjectProviderGrant(UUID.randomUUID(), tenantId, projectId, providerProductId,
                credentialId, GrantStatus.ACTIVE, adminId, 0, Instant.now(), Instant.now());
        grantRepository.insert(grant);
        replaceModels(tenantId, grant.id(), models);
        auditService.record(tenantId, adminId, "GRANT_CREATE", "GRANT", grant.id(),
                "{\"projectId\":\"" + projectId + "\",\"productId\":\"" + providerProductId + "\"}", null);
        return grant;
    }

    @Transactional
    public ProjectProviderGrant updateGrantModels(UUID tenantId, UUID adminId, UUID grantId, List<String> models) {
        ProjectProviderGrant grant = requireGrant(tenantId, grantId);
        replaceModels(tenantId, grantId, models);
        auditService.record(tenantId, adminId, "GRANT_MODELS", "GRANT", grantId, "{}", null);
        return grant;
    }

    @Transactional
    public void disableGrant(UUID tenantId, UUID adminId, UUID grantId) {
        ProjectProviderGrant grant = requireGrant(tenantId, grantId);
        ProjectProviderGrant updated = new ProjectProviderGrant(grant.id(), grant.tenantId(), grant.projectId(),
                grant.providerProductId(), grant.upstreamCredentialId(), GrantStatus.DISABLED, grant.createdBy(),
                grant.version() + 1, grant.createdAt(), Instant.now());
        grantRepository.update(updated);
        auditService.record(tenantId, adminId, "GRANT_DISABLE", "GRANT", grantId, "{}", null);
    }

    /** Models granted to a grant (for the edit view). */
    public List<String> grantModels(UUID tenantId, UUID grantId) {
        requireGrant(tenantId, grantId);
        return jdbc.queryForList("""
                SELECT model_id FROM project_provider_grant_models
                WHERE tenant_id = :tenantId AND grant_id = :grantId ORDER BY model_id
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId), String.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void replaceModels(UUID tenantId, UUID grantId, List<String> models) {
        jdbc.update("DELETE FROM project_provider_grant_models WHERE tenant_id = :tenantId AND grant_id = :grantId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId));
        if (models != null) {
            for (String model : models) {
                if (model != null && !model.isBlank()) {
                    jdbc.update("""
                            INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                            VALUES (:tenantId, :grantId, :model)
                            """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId)
                            .addValue("model", model.trim()));
                }
            }
        }
    }

    /**
     * The tag becomes the Virtual Key's dot-suffix (VirtualKeyParser): it must
     * match the same pattern, otherwise the generated keys are unparseable and
     * permanently dead.
     */
    private void requireValidProjectTag(String projectTag) {
        if (projectTag != null && !java.util.regex.Pattern.matches("^[A-Za-z0-9_-]{1,64}$", projectTag)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_TAG_INVALID",
                    "projectTag must match [A-Za-z0-9_-]{1,64}");
        }
    }

    private User requireUser(UUID tenantId, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        if (!user.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        }
        return user;
    }

    private Team requireTeam(UUID tenantId, UUID teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found"));
        if (!team.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found");
        }
        return team;
    }

    private Project requireProject(UUID tenantId, UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
        if (!project.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found");
        }
        return project;
    }

    private ProjectProviderGrant requireGrant(UUID tenantId, UUID grantId) {
        ProjectProviderGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "Grant not found"));
        if (!grant.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "Grant not found");
        }
        return grant;
    }

    /** The temporary password contains no look-alike characters. */
    private static String generateTemporaryPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** Strip password hash — never serialize it. */
    private static User sanitize(User user) {
        return new User(user.id(), user.tenantId(), user.username(), user.displayName(), new byte[0], user.role(),
                user.status(), user.mustChangePassword(), user.failedLoginCount(), user.lockedUntil(),
                user.lastLoginAt(), user.version(), user.createdAt(), user.updatedAt());
    }

    public record UserCreated(User user, String temporaryPassword) {
    }

    public record UserPasswordReset(User user, String temporaryPassword) {
    }

    public record TeamMemberView(UUID userId, String username, String displayName, Instant createdAt) {
    }

    public record ProjectMemberView(UUID userId, String username, String displayName, Instant createdAt) {
    }

    public record UserProjectMembershipView(UUID projectId, String projectCode, String projectName,
            String projectStatus, Instant joinedAt) {
    }

    private static final RowMapper<ProjectProviderGrant> GRANT_ROW_MAPPER = (rs, rowNum) -> new ProjectProviderGrant(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("project_id"),
            (UUID) rs.getObject("provider_product_id"), (UUID) rs.getObject("upstream_credential_id"),
            GrantStatus.valueOf(rs.getString("status")), (UUID) rs.getObject("created_by"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null);
}
