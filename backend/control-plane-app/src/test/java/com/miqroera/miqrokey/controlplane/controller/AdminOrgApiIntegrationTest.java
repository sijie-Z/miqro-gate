package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin organization APIs against real PostgreSQL (G5.2): users (create /
 * disable / reset-password / revoke sessions), teams + members, projects +
 * members, and grants with model scopes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin organization API integration tests (PostgreSQL)")
class AdminOrgApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    PasswordHasher passwordHasher;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("an admin lock kills the session, blocks login and does not self-heal (#445)")
    void manualLockIsEnforced() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "lockme", "displayName", "Lock Me", "role", "USER"))))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> createdBody = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        String temp = createdBody.get("temporaryPassword").toString();
        String userId = ((Map<?, ?>) createdBody.get("user")).get("id").toString();

        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("lockme", temp))))
                .andExpect(status().isOk()).andReturn();
        Cookie userSession = cookie(login, "MIQROKEY_SESSION");
        assertThat(userSession).isNotNull();
        mockMvc.perform(get("/api/v1/auth/me").cookie(userSession)).andExpect(status().isOk());

        // Admin locks the account (indefinite: no lock deadline involved).
        mockMvc.perform(patch("/api/v1/admin/users/" + userId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken).content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isOk());

        // (a) the existing session is dead.
        mockMvc.perform(get("/api/v1/auth/me").cookie(userSession)).andExpect(status().isUnauthorized());
        // (b) a password login is refused while locked.
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("lockme", temp))))
                .andExpect(status().isUnauthorized());
        // (c) the lock did not self-heal: the user list still shows LOCKED.
        String usersBody = mockMvc.perform(get("/api/v1/admin/users").cookie(sessionCookie)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode lockme = null;
        for (JsonNode node : objectMapper.readTree(usersBody)) {
            if ("lockme".equals(node.path("username").asText())) {
                lockme = node;
            }
        }
        assertThat(lockme).isNotNull();
        assertThat(lockme.path("status").asText()).isEqualTo("LOCKED");

        // (d) unlocking restores login.
        mockMvc.perform(patch("/api/v1/admin/users/" + userId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("lockme", temp)))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /admin/users/{id} edits displayName and rejects empty/blank/overlong bodies (#614)")
    void updateUserDisplayNameAndValidation() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "rename-me", "displayName", "Before", "role", "USER"))))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> createdBody = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        String userId = ((Map<?, ?>) createdBody.get("user")).get("id").toString();
        String temp2 = createdBody.get("temporaryPassword").toString();

        // displayName-only update persists; status stays untouched.
        mockMvc.perform(patch("/api/v1/admin/users/" + userId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content("{\"displayName\":\"改名成功\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("改名成功")).andExpect(jsonPath("$.status").value("ACTIVE"));
        String usersBody = mockMvc.perform(get("/api/v1/admin/users").cookie(sessionCookie)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode row = null;
        for (JsonNode node : objectMapper.readTree(usersBody)) {
            if ("rename-me".equals(node.path("username").asText())) {
                row = node;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("displayName").asText()).isEqualTo("改名成功");

        // #614: an unknown-field-only body used to drop displayName and send a
        // null status into the NOT NULL column — 500. It must be a 400 now.
        mockMvc.perform(patch("/api/v1/admin/users/" + userId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content("{\"unknownField\":\"x\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_UPDATE_EMPTY"));
        // Empty body and blank display names are rejected.
        mockMvc.perform(patch("/api/v1/admin/users/" + userId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("USER_UPDATE_EMPTY"));
        mockMvc.perform(patch("/api/v1/admin/users/" + userId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content("{\"displayName\":\"   \"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISPLAY_NAME_INVALID"));
        mockMvc.perform(patch("/api/v1/admin/users/" + userId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("displayName", "x".repeat(201)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DISPLAY_NAME_INVALID"));

        // Combined displayName + status keeps the status side effects (revoke).
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("rename-me", temp2))))
                .andExpect(status().isOk()).andReturn();
        Cookie renameSession = cookie(login, "MIQROKEY_SESSION");
        mockMvc.perform(patch("/api/v1/admin/users/" + userId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content("{\"displayName\":\"锁定中\",\"status\":\"LOCKED\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("锁定中")).andExpect(jsonPath("$.status").value("LOCKED"));
        mockMvc.perform(get("/api/v1/auth/me").cookie(renameSession)).andExpect(status().isUnauthorized());

        // Both successful updates are on the unified USER_UPDATE audit trail.
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_events WHERE action = 'USER_UPDATE' AND target_id = :id",
                new MapSqlParameterSource("id", UUID.fromString(userId)), Integer.class);
        assertThat(auditCount).isEqualTo(2);
    }

    @Test
    @DisplayName("hostile names keep the audit summary valid JSON and cannot forge members (#447)")
    void hostileNamesDoNotBreakAudit() throws Exception {
        // Team name with a quote + newline used to make the ::jsonb cast fail and
        // roll back the whole transaction (500) — pre-#447 red at the isOk.
        String teamName = "Team \"Alpha\"\nline2";
        mockMvc.perform(post("/api/v1/admin/teams").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name", teamName)))).andExpect(status().isOk());
        String teamSummary = jdbc
                .queryForObject("SELECT change_summary::text FROM admin_audit_events WHERE action = 'TEAM_CREATE' "
                        + "ORDER BY chain_position DESC LIMIT 1", new MapSqlParameterSource(), String.class);
        assertThat(objectMapper.readTree(teamSummary).get("name").asText()).contains("Alpha");

        // A crafted username must stay a literal username value, not a member.
        String crafted = "x\",\"role\":\"SYSTEM_ADMIN";
        mockMvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper
                        .writeValueAsString(Map.of("username", crafted, "displayName", "Craft", "role", "USER"))))
                .andExpect(status().isOk());
        String userSummary = jdbc
                .queryForObject("SELECT change_summary::text FROM admin_audit_events WHERE action = 'USER_CREATE' "
                        + "ORDER BY chain_position DESC LIMIT 1", new MapSqlParameterSource(), String.class);
        JsonNode parsed = objectMapper.readTree(userSummary);
        assertThat(parsed.get("username").asText()).isEqualTo(crafted);
        assertThat(parsed.get("role").asText()).isEqualTo("USER");
    }

    @Test
    @DisplayName("user create returns a one-time temporary password usable for login and change")
    void userLifecycle() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "alice", "displayName", "Alice", "role", "USER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist()).andReturn();
        String temp = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class)
                .get("temporaryPassword").toString();
        String userId = ((Map<?, ?>) objectMapper.readValue(created.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        // The temporary password works for login; the user must change it.
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice", temp))))
                .andExpect(status().isOk()).andReturn();
        Cookie userSession = cookie(login, "MIQROKEY_SESSION");
        org.assertj.core.api.Assertions.assertThat(userSession).isNotNull();

        // The user list never serializes password hashes.
        mockMvc.perform(get("/api/v1/admin/users").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='alice')].passwordHash").doesNotExist());

        // Reset: new temporary password, sessions revoked (old login rejected).
        MvcResult reset = mockMvc.perform(post("/api/v1/admin/users/" + userId + "/reset-password")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andReturn();
        String newTemp = objectMapper.readValue(reset.getResponse().getContentAsString(), Map.class)
                .get("temporaryPassword").toString();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).cookie(userSession)
                .content(objectMapper.writeValueAsString(new LoginRequest("alice", temp))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("alice", newTemp))))
                .andExpect(status().isOk());
    }

    /**
     * #1019: shape limits are answered by validation, not by the database. Before
     * the DTO constraints existed, an over-long team name reached Postgres and came
     * back as 409 RESOURCE_CONFLICT ("duplicate or referenced") and a missing
     * project code as PROJECT_CODE_TAKEN — both telling the caller the wrong thing.
     * The boundary value still has to work, or the fix would just be a different
     * wrong answer.
     */
    @Test
    @DisplayName("over-long and missing fields answer 400 with the field named")
    void inputLimitsAreValidationErrors() throws Exception {
        String longName = "T".repeat(201);

        mockMvc.perform(post("/api/v1/admin/teams").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name", longName)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists());

        MvcResult team = mockMvc
                .perform(post("/api/v1/admin/teams").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name", "T".repeat(200)))))
                .andExpect(status().isOk()).andReturn();
        String teamId = objectMapper.readValue(team.getResponse().getContentAsString(), Map.class).get("id").toString();

        mockMvc.perform(patch("/api/v1/admin/teams/" + teamId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name", longName)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists());

        // Absent code is a required-field problem, not a uniqueness conflict.
        mockMvc.perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name", "No Code")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='code')]").exists());

        // One past varchar(64) is rejected; exactly 64 is accepted.
        mockMvc.perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("code", "C".repeat(65), "name", "Too Long"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[?(@.field=='code')]").exists());
        mockMvc.perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("code", "C".repeat(64), "name", "Boundary"))))
                .andExpect(status().isOk());

        // display_name varchar(200) — the create path had no bound while its own update
        // path already answered DISPLAY_NAME_INVALID.
        mockMvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper
                        .writeValueAsString(Map.of("username", "dn_probe", "displayName", "D".repeat(5000)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DISPLAY_NAME_INVALID"));
        mockMvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("username", "dn_ok", "displayName", "D".repeat(200)))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("teams and projects manage members; duplicate project codes are rejected")
    void teamsAndProjects() throws Exception {
        MvcResult team = mockMvc
                .perform(post("/api/v1/admin/teams").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper
                                .writeValueAsString(Map.of("name", "Platform", "description", "Platform team"))))
                .andExpect(status().isOk()).andReturn();
        String teamId = objectMapper.readValue(team.getResponse().getContentAsString(), Map.class).get("id").toString();

        MvcResult user = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "bob"))))
                .andExpect(status().isOk()).andReturn();
        String userId = ((Map<?, ?>) objectMapper.readValue(user.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        mockMvc.perform(post("/api/v1/admin/teams/" + teamId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", userId)))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/teams/" + teamId + "/members").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].username").value("bob"));
        mockMvc.perform(delete("/api/v1/admin/teams/" + teamId + "/members/" + userId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        MvcResult project = mockMvc
                .perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("code", "CORE", "name", "Core AI", "projectTag", "core-ai"))))
                .andExpect(status().isOk()).andReturn();
        String projectId = objectMapper.readValue(project.getResponse().getContentAsString(), Map.class).get("id")
                .toString();
        mockMvc.perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("code", "CORE", "name", "Duplicate"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CODE_TAKEN"));
        mockMvc.perform(post("/api/v1/admin/projects/" + projectId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", userId)))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/projects/" + projectId + "/members").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].username").value("bob"));
    }

    @Test
    @DisplayName("grants bind project, product and credential with a model scope")
    void grants() throws Exception {
        fx.insertProviderAndProductAndCredential();
        MvcResult project = mockMvc
                .perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("code", "CORE", "name", "Core"))))
                .andExpect(status().isOk()).andReturn();
        String projectId = objectMapper.readValue(project.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        MvcResult grant = mockMvc
                .perform(post("/api/v1/admin/grants").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectId, "providerProductId",
                                fx.productId.toString(), "credentialId", fx.credentialId.toString(), "models",
                                List.of("model-a", "model-b")))))
                .andExpect(status().isOk()).andReturn();
        String grantId = objectMapper.readValue(grant.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(get("/api/v1/admin/grants/" + grantId + "/models").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("model-a"))
                .andExpect(jsonPath("$[1]").value("model-b"));
        mockMvc.perform(post("/api/v1/admin/grants/" + grantId + "/models").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("models", List.of("model-a")))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/grants/" + grantId + "/models").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete("/api/v1/admin/grants/" + grantId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("products without catalog data skip the model allowlist (#498 compatibility)")
    void grantModelCheckSkipsEmptyCatalog() throws Exception {
        fx.insertProviderAndProductAndCredential();
        String projectId = createProject("NOCAT");
        mockMvc.perform(post("/api/v1/admin/grants").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("projectId", projectId, "providerProductId",
                        fx.secondProductId.toString(), "credentialId", fx.secondCredentialId.toString(), "models",
                        List.of("any-model-before-catalog-sync")))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a grant rejects a credential whose subscription belongs to another product (#498)")
    void grantRejectsCrossProductCredential() throws Exception {
        fx.insertProviderAndProductAndCredential();
        String projectId = createProject("MISM");
        mockMvc.perform(
                post("/api/v1/admin/grants").contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectId, "providerProductId",
                                fx.productId.toString(), "credentialId", fx.secondCredentialId.toString(), "models",
                                List.of("model-a")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GRANT_CREDENTIAL_PRODUCT_MISMATCH"));
    }

    @Test
    @DisplayName("grant model scopes must exist in the product catalog (#498)")
    void grantModelsMustExistInCatalog() throws Exception {
        fx.insertProviderAndProductAndCredential();
        String projectId = createProject("MODL");
        mockMvc.perform(
                post("/api/v1/admin/grants").contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectId, "providerProductId",
                                fx.productId.toString(), "credentialId", fx.credentialId.toString(), "models",
                                List.of("not-in-catalog")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_NOT_IN_CATALOG"));

        String grantId = createGrant(projectId, List.of("model-a"));
        mockMvc.perform(post("/api/v1/admin/grants/" + grantId + "/models").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("models", List.of("not-in-catalog")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_NOT_IN_CATALOG"));
    }

    @Test
    @DisplayName("system admins cannot be disabled; anonymous access is rejected")
    void guards() throws Exception {
        // The bootstrap admin is a SYSTEM_ADMIN: disabling must be rejected.
        MvcResult list = mockMvc.perform(get("/api/v1/admin/users").cookie(sessionCookie)).andExpect(status().isOk())
                .andReturn();
        String adminId = ((Map<?, ?>) ((java.util.List<?>) objectMapper
                .readValue(list.getResponse().getContentAsString(), java.util.List.class)).get(0)).get("id").toString();
        mockMvc.perform(patch("/api/v1/admin/users/" + adminId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_NOT_DISABLEABLE"));
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    @Test
    @DisplayName("project tag lifecycle and member removal over bound keys (ADR-0018)")
    void projectTagLifecycleAndMemberRemoval() throws Exception {
        fx.insertProviderAndProductAndCredential();

        // 1) Omitted tag is auto-derived from the code — no administrator input.
        MvcResult project = mockMvc
                .perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("code", "QA Team", "name", "QA"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectTag").value("qa-team")).andReturn();
        String projectId = objectMapper.readValue(project.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        MvcResult grant = mockMvc
                .perform(post("/api/v1/admin/grants").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectId, "providerProductId",
                                fx.productId.toString(), "credentialId", fx.credentialId.toString(), "models",
                                List.of("model-a", "model-b")))))
                .andExpect(status().isOk()).andReturn();
        String grantId = objectMapper.readValue(grant.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // 2) A member (bob) joins and creates a key bound to the project.
        MvcResult invited = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "bob"))))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> inviteBody = objectMapper.readValue(invited.getResponse().getContentAsString(), Map.class);
        String bobId = ((Map<?, ?>) inviteBody.get("user")).get("id").toString();
        String bobTemp = (String) inviteBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/admin/projects/" + projectId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", bobId)))).andExpect(status().isOk());

        MvcResult bobLogin = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("bob", bobTemp))))
                .andExpect(status().isOk()).andReturn();
        Cookie bobSession = cookie(bobLogin, "MIQROKEY_SESSION");
        Cookie bobCsrf = cookie(bobLogin, "MIQROKEY_CSRF");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(bobSession, bobCsrf).header("X-CSRF-Token", bobCsrf.getValue())
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(bobTemp, "BobSecurePass1!"))))
                .andExpect(status().isOk());

        MvcResult bobKey = mockMvc
                .perform(post("/api/v1/me/virtual-keys").contentType(MediaType.APPLICATION_JSON)
                        .cookie(bobSession, bobCsrf).header("X-CSRF-Token", bobCsrf.getValue())
                        .content(objectMapper.writeValueAsString(Map.of("name", "bob-key", "projectId", projectId,
                                "providerProductId", fx.productId.toString(), "credentialGrantId", grantId, "purpose",
                                "CLAUDE_CODE"))))
                .andExpect(status().isCreated()).andReturn();
        String keyId = objectMapper.readValue(bobKey.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // 3) The tag is now referenced by a binding: changing it is refused …
        mockMvc.perform(patch("/api/v1/admin/projects/" + projectId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("projectTag", "qa-team-2"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_TAG_IN_USE"));
        // … renaming the project (no tag change) stays allowed.
        mockMvc.perform(patch("/api/v1/admin/projects/" + projectId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name", "QA Renamed")))).andExpect(status().isOk());

        // 4) Removing the member disables the (key × project) binding and, since
        // it was the key's only binding, revokes the key.
        mockMvc.perform(delete("/api/v1/admin/projects/" + projectId + "/members/" + bobId)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        String bindingStatus = jdbc.queryForObject("SELECT status FROM key_project_binding WHERE virtual_key_id = :id",
                new MapSqlParameterSource("id", UUID.fromString(keyId)), String.class);
        String keyStatus = jdbc.queryForObject("SELECT status FROM virtual_keys WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(keyId)), String.class);
        assertThat(bindingStatus).isEqualTo("DISABLED");
        assertThat(keyStatus).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("removing a member severs the project route even while their key is DISABLED")
    void memberRemovalSeversRouteWhileKeyDisabled() throws Exception {
        fx.insertProviderAndProductAndCredential();
        String projectId = createProject("QAOFF");
        String grantId = createGrant(projectId, List.of("model-a"));

        // bob joins the project.
        MvcResult invited = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "bob"))))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> inviteBody = objectMapper.readValue(invited.getResponse().getContentAsString(), Map.class);
        String bobId = ((Map<?, ?>) inviteBody.get("user")).get("id").toString();
        String bobTemp = (String) inviteBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/admin/projects/" + projectId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", bobId)))).andExpect(status().isOk());

        MvcResult bobLogin = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("bob", bobTemp))))
                .andExpect(status().isOk()).andReturn();
        Cookie bobSession = cookie(bobLogin, "MIQROKEY_SESSION");
        Cookie bobCsrf = cookie(bobLogin, "MIQROKEY_CSRF");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(bobSession, bobCsrf).header("X-CSRF-Token", bobCsrf.getValue())
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(bobTemp, "BobSecurePass1!"))))
                .andExpect(status().isOk());

        MvcResult bobKey = mockMvc
                .perform(post("/api/v1/me/virtual-keys").contentType(MediaType.APPLICATION_JSON)
                        .cookie(bobSession, bobCsrf).header("X-CSRF-Token", bobCsrf.getValue())
                        .content(objectMapper.writeValueAsString(Map.of("name", "bob-off", "projectId", projectId,
                                "providerProductId", fx.productId.toString(), "credentialGrantId", grantId, "purpose",
                                "CLAUDE_CODE"))))
                .andExpect(status().isCreated()).andReturn();
        String keyId = objectMapper.readValue(bobKey.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // bob parks his own key first (self-service disable), then the admin
        // removes him from the project.
        mockMvc.perform(post("/api/v1/me/virtual-keys/" + keyId + "/disable").cookie(bobSession, bobCsrf)
                .header("X-CSRF-Token", bobCsrf.getValue())).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/projects/" + projectId + "/members/" + bobId)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        // Membership removal is the authority for the project route (ADR-0018):
        // a key that was merely parked must not keep an ACTIVE binding.
        String bindingStatus = jdbc.queryForObject("SELECT status FROM key_project_binding WHERE virtual_key_id = :id",
                new MapSqlParameterSource("id", UUID.fromString(keyId)), String.class);
        assertThat(bindingStatus).isEqualTo("DISABLED");

        // …and flipping the key back on must not resurrect the route either.
        mockMvc.perform(post("/api/v1/me/virtual-keys/" + keyId + "/enable").cookie(bobSession, bobCsrf)
                .header("X-CSRF-Token", bobCsrf.getValue())).andExpect(status().isOk());
        String bindingAfterEnable = jdbc.queryForObject(
                "SELECT status FROM key_project_binding WHERE virtual_key_id = :id",
                new MapSqlParameterSource("id", UUID.fromString(keyId)), String.class);
        assertThat(bindingAfterEnable).isEqualTo("DISABLED");
    }

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID secondProductId = UUID.randomUUID();
        final UUID secondSubscriptionId = UUID.randomUUID();
        final UUID secondCredentialId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("quota_snapshots", "cost_allocations", "usage_event", "cache_hit_event",
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

        void insertProviderAndProductAndCredential() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, plan_scope, status, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'PAYG', 'NONE', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:id, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", subscriptionId));
            // Product catalog scope for the grant model validation (#498).
            for (String model : List.of("model-a", "model-b")) {
                jdbc.update("""
                        INSERT INTO model_catalog (id, provider_product_id, model_id, display_name, status, version)
                        VALUES (:id, :productId, :model, :model, 'ACTIVE', 0)
                        """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                        .addValue("model", model));
            }
            // A second product with its own subscription/credential for the
            // cross-product mismatch case (#498).
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product-2', 'Test Product 2', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test2.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("productId", secondProductId).addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, plan_scope, status, version)
                    VALUES (:id, :tenantId, :productId, 'Sub-2', 'PAYG', 'NONE', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", secondSubscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", secondProductId));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:id, :tenantId, :subscriptionId, 'Cred-2', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", secondCredentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", secondSubscriptionId));
        }
    }

    @Test
    @DisplayName("unattributed policy: lifecycle, bucket project, warnings, validations (#647)")
    void unattributedPolicyLifecycle() throws Exception {
        fx.insertProviderAndProductAndCredential();

        mockMvc.perform(get("/api/v1/admin/unattributed-policy").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));

        // Configure with a catalog model (#498 semantics apply).
        MvcResult put = mockMvc
                .perform(put("/api/v1/admin/unattributed-policy").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("credentialId", fx.credentialId.toString(),
                                "providerProductId", fx.productId.toString(), "models", List.of("model-a")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.models[0]").value("model-a")).andReturn();
        String bucketId = objectMapper.readValue(put.getResponse().getContentAsString(), Map.class).get("projectId")
                .toString();

        // The bucket project is a system project and appears in the list with the flag.
        Boolean system = jdbc.queryForObject("SELECT system FROM projects WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(bucketId)), Boolean.class);
        assertThat(system).isTrue();
        mockMvc.perform(get("/api/v1/admin/projects").cookie(sessionCookie)).andExpect(status().isOk());

        // A model outside the catalog is rejected.
        mockMvc.perform(put("/api/v1/admin/unattributed-policy").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("credentialId", fx.credentialId.toString(),
                        "providerProductId", fx.productId.toString(), "models", List.of("ghost-model-xyz")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_NOT_IN_CATALOG"));

        // A credential from a different product's subscription is rejected.
        mockMvc.perform(put("/api/v1/admin/unattributed-policy").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("credentialId", fx.secondCredentialId.toString(),
                        "providerProductId", fx.productId.toString(), "models", List.of("model-a")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNAUTH_CREDENTIAL_PRODUCT_MISMATCH"));

        // Once the credential is referenced by a project grant, the view warns
        // (advisory, plan Q1).
        String warnProject = createProject("POLW");
        createGrant(warnProject, List.of("model-a"));
        mockMvc.perform(get("/api/v1/admin/unattributed-policy").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").isNotEmpty());

        // Audit trail for the setter.
        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_events WHERE action = 'UNATTRIBUTED_POLICY_SET'",
                new MapSqlParameterSource(), Integer.class);
        assertThat(audits).isNotNull();
        assertThat(audits).isGreaterThanOrEqualTo(1);

        // Clear -> unconfigured; the bucket project row is retained for history.
        mockMvc.perform(delete("/api/v1/admin/unattributed-policy").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/unattributed-policy").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        Integer bucketRows = jdbc.queryForObject("SELECT count(*) FROM projects WHERE id = :id AND system",
                new MapSqlParameterSource("id", UUID.fromString(bucketId)), Integer.class);
        assertThat(bucketRows).isEqualTo(1);
    }

    @Test
    @DisplayName("CAA project registry: repo mappings normalize, stay tenant-unique, and delete (#639)")
    void projectRepositoryRegistry() throws Exception {
        String p1 = createProject("REPOA");
        String p2 = createProject("REPOB");

        // Full URL form (with .git and mixed case) normalizes to host/owner/repo.
        MvcResult added = mockMvc
                .perform(post("/api/v1/admin/projects/" + p1 + "/repositories").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper
                                .writeValueAsString(Map.of("repoKey", "https://github.com/Acme/Rocket.git"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.repoKey").value("github.com/acme/rocket"))
                .andReturn();
        String mappingId = objectMapper.readValue(added.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // Bare owner/repo defaults to github.com.
        mockMvc.perform(post("/api/v1/admin/projects/" + p1 + "/repositories").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("repoKey", "acme/notes")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.repoKey").value("github.com/acme/notes"));

        mockMvc.perform(get("/api/v1/admin/projects/" + p1 + "/repositories").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        // Tenant-wide uniqueness: the same repo cannot map to a second project.
        mockMvc.perform(post("/api/v1/admin/projects/" + p2 + "/repositories").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("repoKey", "github.com/acme/rocket"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPO_KEY_TAKEN"));

        // Invalid shapes are rejected.
        mockMvc.perform(post("/api/v1/admin/projects/" + p2 + "/repositories").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("repoKey", "not a repo!"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPO_KEY_INVALID"));

        // Delete, then the same id is gone.
        mockMvc.perform(delete("/api/v1/admin/projects/" + p1 + "/repositories/" + mappingId)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/projects/" + p1 + "/repositories/" + mappingId)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // Unknown project stays a 404.
        mockMvc.perform(get("/api/v1/admin/projects/" + UUID.randomUUID() + "/repositories").cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    private String createProject(String code) throws Exception {
        MvcResult project = mockMvc
                .perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("code", code, "name", code))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(project.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private String createGrant(String projectId, List<String> models) throws Exception {
        MvcResult grant = mockMvc.perform(post("/api/v1/admin/grants").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("projectId", projectId, "providerProductId",
                        fx.productId.toString(), "credentialId", fx.credentialId.toString(), "models", models))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(grant.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    static class BootstrapHelper {
        static final java.nio.file.Path SECRET_FILE;
        static final String SECRET = "test-bootstrap-secret-min-16chars";
        static {
            try {
                SECRET_FILE = java.nio.file.Files.createTempFile("bootstrap-secret", ".txt");
                java.nio.file.Files.writeString(SECRET_FILE, SECRET);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        static java.nio.file.Path secretFile() {
            return SECRET_FILE;
        }
        static String secret() {
            return SECRET;
        }
    }

    @Test
    @DisplayName("user project memberships list for the quick-join entry; unknown user 404s")
    void userProjectMemberships() throws Exception {
        MvcResult user = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "join-me"))))
                .andExpect(status().isOk()).andReturn();
        String userId = ((Map<?, ?>) objectMapper.readValue(user.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        // Fresh user has no project memberships.
        mockMvc.perform(get("/api/v1/admin/users/" + userId + "/project-memberships").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        String core = projectId("CORE", "Core AI");
        String tools = projectId("TOOLS", "Tools");
        addMember(core, userId);
        mockMvc.perform(get("/api/v1/admin/users/" + userId + "/project-memberships").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].projectCode").value("CORE"))
                .andExpect(jsonPath("$[0].projectName").value("Core AI")).andExpect(jsonPath("$[0].joinedAt").exists());
        addMember(tools, userId);
        mockMvc.perform(get("/api/v1/admin/users/" + userId + "/project-memberships").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].projectCode").value("CORE"))
                .andExpect(jsonPath("$[1].projectCode").value("TOOLS"));

        // Removing the membership drops the row again.
        mockMvc.perform(delete("/api/v1/admin/projects/" + core + "/members/" + userId)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/users/" + userId + "/project-memberships").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].projectCode").value("TOOLS"));

        // Unknown users and anonymous callers are rejected.
        mockMvc.perform(get("/api/v1/admin/users/" + UUID.randomUUID() + "/project-memberships").cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/users/" + userId + "/project-memberships"))
                .andExpect(status().isUnauthorized());
    }

    private String projectId(String code, String name) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("code", code, "name", name))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private void addMember(String projectId, String userId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/projects/" + projectId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", userId)))).andExpect(status().isOk());
    }
}
