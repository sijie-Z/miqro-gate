package com.miqroera.miqrokey.controlplane.controller;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Global default quota strategy (V26, Tencent doc 135489): configure a
 * per-tenant template, then enable — newly created users receive a snapshot
 * copy as an ordinary quota rule. Later template edits or a disable never touch
 * already-assigned copies; insert-if-absent keeps manual rules winning.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin quota default template API integration tests (PostgreSQL)")
class AdminQuotaDefaultTemplateApiIntegrationTest {

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

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private Cookie userSession;
    private UUID adminUserId;

    @BeforeEach
    void setUp() throws Exception {
        resetDb();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        adminUserId = UUID.fromString((String) bootBody.get("userId"));
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());

        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                   must_change_password, version)
                VALUES (:id, :tenantId, 'regular_user', 'Regular', :hash, 'USER', 'ACTIVE', FALSE, 0)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId())
                .addValue("hash", passwordHasher.hash("NewSecurePass1!")));
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("regular_user", "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        userSession = cookie(login, "MIQROKEY_SESSION");
    }

    @AfterEach
    void tearDown() {
        resetDb();
    }

    // ------------------------------------------------------------------
    // template lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("empty state, configure definition, enable and disable keep the definition")
    void lifecycle() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quota-default-template").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.metric").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.limitValue").value(org.hamcrest.Matchers.nullValue()));

        configure("TOKENS", "MONTHLY", 1_000_000).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.metric").value("TOKENS"))
                .andExpect(jsonPath("$.period").value("MONTHLY")).andExpect(jsonPath("$.limitValue").value(1_000_000))
                .andExpect(jsonPath("$.version").value(0));

        // Re-configuring edits the definition in place (disabled stays disabled).
        configure("REQUESTS", "WEEKLY", 500).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.metric").value("REQUESTS")).andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/v1/admin/quota-default-template/enable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.metric").value("REQUESTS"))
                .andExpect(jsonPath("$.limitValue").value(500));

        // Enabling twice is a conflict.
        mockMvc.perform(post("/api/v1/admin/quota-default-template/enable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_TEMPLATE_ALREADY_ENABLED"));

        // Disabling keeps the stored definition (the snapshot source survives).
        mockMvc.perform(post("/api/v1/admin/quota-default-template/disable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.metric").value("REQUESTS"))
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(post("/api/v1/admin/quota-default-template/disable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_TEMPLATE_ALREADY_DISABLED"));
    }

    @Test
    @DisplayName("enable and disable before any configuration are conflicts")
    void requiresConfiguration() throws Exception {
        mockMvc.perform(post("/api/v1/admin/quota-default-template/enable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_TEMPLATE_NOT_CONFIGURED"));
        mockMvc.perform(post("/api/v1/admin/quota-default-template/disable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_TEMPLATE_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("definition validation rejects bad enums and non-positive limits")
    void validation() throws Exception {
        configure("BAD_METRIC", "MONTHLY", 100).andExpect(status().isBadRequest());
        configure("TOKENS", "YEARLY", 100).andExpect(status().isBadRequest());
        configure("TOKENS", "MONTHLY", 0).andExpect(status().isBadRequest());
        configure("TOKENS", "MONTHLY", -1).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/admin/quota-default-template").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"MONTHLY\",\"limitValue\":100}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/admin/quota-default-template").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric\":\"TOKENS\",\"period\":\"MONTHLY\"}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("regular users and anonymous callers are rejected")
    void permissions() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quota-default-template").cookie(userSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/quota-default-template")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/quota-default-template").cookie(adminSession)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // snapshot copy semantics on user creation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("enabled template snapshots a quota rule onto each new user; edits and disable never touch copies")
    void snapshotCopyOnUserCreation() throws Exception {
        configure("TOKENS", "MONTHLY", 1_000_000).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/quota-default-template/enable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isOk());

        UUID alice = createUser("alice");

        // The new user received an ACTIVE USER-scope rule carrying the snapshot.
        Map<String, Object> aliceRule = ruleRow(alice);
        assertThat(aliceRule).isNotNull();
        assertThat(aliceRule.get("metric")).isEqualTo("TOKENS");
        assertThat(aliceRule.get("period")).isEqualTo("MONTHLY");
        assertThat(aliceRule.get("limit_value")).isEqualTo(1_000_000L);
        assertThat(aliceRule.get("warn_percent")).isEqualTo(80);
        assertThat(aliceRule.get("status")).isEqualTo("ACTIVE");
        assertThat(aliceRule.get("created_by")).isEqualTo(adminUserId);

        // Editing the template definition later never touches the copy.
        configure("REQUESTS", "WEEKLY", 500).andExpect(status().isOk());
        assertThat(ruleRow(alice)).isNotNull();
        assertThat(ruleRow(alice).get("metric")).isEqualTo("TOKENS");
        assertThat(ruleRow(alice).get("limit_value")).isEqualTo(1_000_000L);

        // Disabling keeps assigned rules; new users stop receiving copies.
        mockMvc.perform(post("/api/v1/admin/quota-default-template/disable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isOk());
        UUID bob = createUser("bob");
        assertThat(ruleRow(alice)).isNotNull();
        assertThat(ruleRow(bob)).isNull();

        // Re-enabling resumes copies from the (preserved) definition.
        mockMvc.perform(post("/api/v1/admin/quota-default-template/enable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isOk());
        UUID carol = createUser("carol");
        assertThat(ruleRow(bob)).isNull();
        Map<String, Object> carolRule = ruleRow(carol);
        assertThat(carolRule).isNotNull();
        assertThat(carolRule.get("metric")).isEqualTo("REQUESTS");
        assertThat(carolRule.get("period")).isEqualTo("WEEKLY");
        assertThat(carolRule.get("limit_value")).isEqualTo(500L);
    }

    @Test
    @DisplayName("no rule is created while the template is disabled or never configured")
    void disabledTemplateCreatesNothing() throws Exception {
        UUID ann = createUser("ann");
        assertThat(ruleRow(ann)).isNull();

        configure("TOKENS", "DAILY", 10_000).andExpect(status().isOk());
        UUID ben = createUser("ben");
        assertThat(ruleRow(ben)).isNull(); // configured but not enabled
    }

    @Test
    @DisplayName("template and snapshot-copy changes are audited with dedicated actions")
    void auditTrail() throws Exception {
        configure("TOKENS", "MONTHLY", 1_000_000).andExpect(status().isOk());
        configure("REQUESTS", "WEEKLY", 500).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/quota-default-template/enable").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isOk());
        createUser("audit_user");

        List<String> actions = jdbc.query(
                "SELECT action FROM admin_audit_events"
                        + " WHERE action LIKE 'QUOTA_DEFAULT_TEMPLATE_%' ORDER BY created_at",
                new MapSqlParameterSource(), (rs, i) -> rs.getString(1));
        assertThat(actions).containsExactly("QUOTA_DEFAULT_TEMPLATE_CREATE", "QUOTA_DEFAULT_TEMPLATE_UPDATE",
                "QUOTA_DEFAULT_TEMPLATE_ENABLE");

        // The snapshot copy is an audited rule creation flagged as automatic.
        List<String> summaries = jdbc.query(
                "SELECT change_summary FROM admin_audit_events"
                        + " WHERE action = 'QUOTA_RULE_CREATE' AND target_type = 'QUOTA_RULE'",
                new MapSqlParameterSource(), (rs, i) -> rs.getString(1));
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0)).contains("\"auto\": true").contains("\"metric\": \"REQUESTS\"")
                .contains("\"limit\": 500");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID tenantId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private org.springframework.test.web.servlet.ResultActions configure(String metric, String period, long limit)
            throws Exception {
        return mockMvc.perform(put("/api/v1/admin/quota-default-template").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric\":\"" + metric + "\",\"period\":\"" + period + "\",\"limitValue\":" + limit + "}"));
    }

    /** Creates a user through the admin API; returns the new user's id. */
    private UUID createUser(String username) throws Exception {
        MvcResult r = mockMvc
                .perform(post("/api/v1/admin/users")
                        .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username
                                + "\",\"displayName\":\"" + username + "\",\"role\":\"USER\"}"))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        Map<?, ?> user = (Map<?, ?>) body.get("user");
        return UUID.fromString((String) user.get("id"));
    }

    /** The quota rule the template copied for a scope, or null when absent. */
    private Map<String, Object> ruleRow(UUID scopeId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT metric, period, limit_value, warn_percent, status, created_by FROM quota_rules"
                        + " WHERE tenant_id = :tenantId AND scope_type = 'USER' AND scope_id = :scopeId",
                new MapSqlParameterSource("tenantId", tenantId()).addValue("scopeId", scopeId),
                (rs, i) -> Map.<String, Object>of("metric", rs.getString("metric"), "period", rs.getString("period"),
                        "limit_value", rs.getLong("limit_value"), "warn_percent", rs.getInt("warn_percent"), "status",
                        rs.getString("status"), "created_by", rs.getObject("created_by")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void resetDb() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "usage_event",
                "price_snapshot", "quota_rules", "quota_default_template", "virtual_key_models", "key_project_binding",
                "model_approval", "virtual_keys", "project_provider_grant_models", "project_provider_grants",
                "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                "project_memberships", "projects", "provider_products", "providers", "admin_audit_events",
                "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Child-first order above covers the canonical FK set.
            }
        }
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
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
}
