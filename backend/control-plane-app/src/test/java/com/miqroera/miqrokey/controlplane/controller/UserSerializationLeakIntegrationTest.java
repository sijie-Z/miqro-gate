package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level password-hash leak check (#1088 closeout batch, item 8).
 *
 * <p>
 * The two {@code AdminOrgApiIntegrationTest} assertions cover the response
 * <em>shape</em> of two endpoints ({@code $.user.passwordHash} doesNotExist).
 * This test covers the <em>secret surface</em> instead, over every endpoint
 * family that serializes or exports user data: login, me, the admin user list,
 * the org user endpoints (team/project members), the audit log and its CSV
 * export. Each response body must contain the literal key {@code passwordHash}
 * zero times, and the stored hash value prefix ({@code $argon2id$}, plus
 * {@code $2[aby]$} for a bcrypt-shaped legacy value) zero times — the second
 * detector bites the "key renamed but value kept" shape the key check cannot
 * see.
 *
 * <p>
 * {@link #theJsonMapperBeanNeverSerializesTheDomainUserHash()} is the load
 * bearing half: many endpoints render view DTOs, so only serializing the domain
 * {@link User} through the auto-configured Jackson 3 mapper actually exercises
 * the {@code UserMixin} (passwordHash exclusion) that moved off the Jackson 2
 * customizer in this batch. Note the mapper serializes a leaked {@code byte[]}
 * as base64 — the key check catches that shape, the value check would not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("user serialization: no password hash escapes over HTTP (PostgreSQL)")
class UserSerializationLeakIntegrationTest {

    private static final String KEY = "passwordHash";

    /**
     * Hash-value detector, calibrated against this repo's hasher: {@code PasswordHasher}
     * emits Argon2id ({@code $argon2id$v=19$m=...$salt$hash}), so a leaked value carries
     * that prefix — the bcrypt shape stays in the pattern because that is what a
     * "key renamed but value kept" leak looks like if a legacy row or another hasher
     * ever produced one.
     */
    private static final Pattern HASH_VALUE = Pattern.compile("\\$2[aby]\\$|\\$argon2id\\$");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminOrgApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository userRepository;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private String teamId;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        resetTenantData();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminOrgApiIntegrationTest.BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper
                        .writeValueAsString(new PasswordChangeRequest((String) bootBody.get("temporaryPassword"),
                                "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("every user-facing endpoint response carries the hash neither as key nor as value")
    void httpResponsesNeverCarryThePasswordHash() throws Exception {
        String temporaryPassword = createProbeUserAndOrg();

        String login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("leakhash", temporaryPassword))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNoLeak("login", login);

        String me = mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(me).as("me must describe a real user").contains("username");
        assertNoLeak("me", me);

        String users = getBody("/api/v1/admin/users");
        assertThat(users).as("user list must actually contain the probe user").contains("leakhash");
        assertNoLeak("admin users list", users);

        String teamMembers = getBody("/api/v1/admin/teams/" + teamId + "/members");
        assertThat(teamMembers).as("team members must actually contain the probe user").contains("leakhash");
        assertNoLeak("team members", teamMembers);

        String projectMembers = getBody("/api/v1/admin/projects/" + projectId + "/members");
        assertThat(projectMembers).as("project members must actually contain the probe user").contains("leakhash");
        assertNoLeak("project members", projectMembers);

        String audit = getBody("/api/v1/admin/audit-events");
        assertThat(audit).as("audit log must actually contain the USER_CREATE event").contains("USER_CREATE");
        assertNoLeak("audit log", audit);

        String auditExport = getBody("/api/v1/admin/audit-events/export");
        assertThat(auditExport).as("audit export must not be an empty error page").isNotBlank();
        assertNoLeak("audit export", auditExport);
    }

    @Test
    @DisplayName("the Jackson 3 mapper bean actually applies the UserMixin to the domain user")
    void theJsonMapperBeanNeverSerializesTheDomainUserHash() throws Exception {
        createProbeUserAndOrg();

        User probe = userRepository.findByTenantIdAndUsername(TENANT_ID, "leakhash")
                .orElseThrow(() -> new AssertionError("probe user not found"));
        String storedHash = new String(probe.passwordHash(), StandardCharsets.UTF_8);
        assertThat(HASH_VALUE.matcher(storedHash).find())
                .as("positive control: the row really carries a hash the value detector matches")
                .isTrue();

        String json = objectMapper.writeValueAsString(probe);
        assertThat(json).as("the serialized user must really be the probe user").contains("\"username\":\"leakhash\"");
        assertNoLeak("domain user via JsonMapper", json);
    }

    @Test
    @DisplayName("the detectors go red on a planted leak - a green scan is not vacuous")
    void detectorsFlagAPlantedLeak() {
        assertThatThrownBy(() -> assertNoLeak("planted-key", "{\"passwordHash\":\"x\"}"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertNoLeak("planted-bcrypt", "{\"secret\":\"$2a$10$abcdefghijklmnopqrstuv\"}"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertNoLeak("planted-argon2id",
                "{\"secret\":\"$argon2id$v=19$m=65536,t=4,p=1$c2FsdA$aGFzaA\"}"))
                .isInstanceOf(AssertionError.class);
    }

    /** Probe user + a team and a project holding it; returns the temporary password. */
    private String createProbeUserAndOrg() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "leakhash", "displayName", "Leak Probe", "role", "USER"))))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> createdBody = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        String userId = ((Map<?, ?>) createdBody.get("user")).get("id").toString();

        MvcResult team = mockMvc.perform(post("/api/v1/admin/teams").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name", "Leak Team")))).andExpect(status().isOk())
                .andReturn();
        teamId = objectMapper.readValue(team.getResponse().getContentAsString(), Map.class).get("id").toString();
        mockMvc.perform(post("/api/v1/admin/teams/" + teamId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", userId)))).andExpect(status().isOk());

        MvcResult project = mockMvc.perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("code", "LEAK", "name", "Leak Project"))))
                .andExpect(status().isOk()).andReturn();
        projectId = objectMapper.readValue(project.getResponse().getContentAsString(), Map.class).get("id").toString();
        mockMvc.perform(post("/api/v1/admin/projects/" + projectId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", userId)))).andExpect(status().isOk());

        return (String) createdBody.get("temporaryPassword");
    }

    /** Same child-first reset list as {@code AdminOrgApiIntegrationTest.Fixture#reset}. */
    private void resetTenantData() {
        for (String table : java.util.List.of("quota_snapshots", "cost_allocations", "usage_event", "cache_hit_event",
                "price_snapshot", "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                "project_provider_grant_models", "project_provider_grants", "model_catalog", "unattributed_policy",
                "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                "project_memberships", "team_memberships", "project_repositories", "projects", "teams",
                "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering above is child-first for the canonical migration set.
            }
        }
    }

    private String getBody(String path) throws Exception {
        return mockMvc.perform(get(path).cookie(sessionCookie)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    /** Both detectors at once: the literal key, and the bcrypt value prefix. */
    private static void assertNoLeak(String label, String body) {
        assertThat(body).as("%s: passwordHash key must not appear in the response body", label).doesNotContain(KEY);
        assertThat(HASH_VALUE.matcher(body).find())
                .as("%s: a password hash value must not appear in the response body", label).isFalse();
    }

    private static Cookie cookie(MvcResult result, String name) {
        return result.getResponse().getCookie(name);
    }
}
