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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service quota visibility (F04): the caller sees exactly the USER-scope
 * plans set on their own account with live watermarks — never another user's or
 * project's rules. Watermark arithmetic itself is the shared admin view path
 * already covered by AdminQuotaRuleApiIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Me quota visibility API integration tests (PostgreSQL)")
class MeQuotaApiIntegrationTest {

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
    private UUID adminUserId;
    private final UUID regularUserId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private Cookie regularSession;

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

        for (UUID id : new UUID[]{regularUserId, otherUserId}) {
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:id, :tenantId, :username, :name, :hash, 'USER', 'ACTIVE', FALSE, 0)
                    """,
                    new MapSqlParameterSource("id", id).addValue("tenantId", tenantId())
                            .addValue("username", id.equals(regularUserId) ? "regular_user" : "other_user")
                            .addValue("name", id.equals(regularUserId) ? "Regular" : "Other")
                            .addValue("hash", passwordHasher.hash("NewSecurePass1!")));
        }
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("regular_user", "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        regularSession = cookie(login, "MIQROKEY_SESSION");
    }

    @AfterEach
    void tearDown() {
        resetDb();
    }

    @Test
    @DisplayName("a user sees only their own USER-scope quota rules with watermarks")
    void userSeesOnlyOwnRules() throws Exception {
        // Rules on three different scopes: the caller, another user, the admin.
        putRule("USER", regularUserId, "TOKENS", "MONTHLY", 1_000_000, 80).andExpect(status().isOk());
        putRule("USER", regularUserId, "REQUESTS", "DAILY", 500, 80, "DISABLED").andExpect(status().isOk());
        putRule("USER", otherUserId, "TOKENS", "DAILY", 10, 80).andExpect(status().isOk());
        putRule("USER", adminUserId, "TOKENS", "WEEKLY", 100, 90).andExpect(status().isOk());

        MvcResult mine = mockMvc.perform(get("/api/v1/me/quota-rules").cookie(regularSession))
                .andExpect(status().isOk()).andReturn();
        java.util.List<?> items = objectMapper.readValue(mine.getResponse().getContentAsString(), java.util.List.class);
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> {
            Map<?, ?> view = (Map<?, ?>) item;
            assertThat((String) view.get("scopeId")).isEqualTo(regularUserId.toString());
            assertThat((Number) view.get("used")).isEqualTo(0);
        });
        assertThat(items).extracting(item -> (String) ((Map<?, ?>) item).get("metric"))
                .containsExactlyInAnyOrder("TOKENS", "REQUESTS");
        // DISABLED plans stay visible on the self-service view.
        assertThat(items).extracting(item -> (String) ((Map<?, ?>) item).get("status"))
                .containsExactlyInAnyOrder("ACTIVE", "DISABLED");

        // The admin's own slice likewise shows only admin-scoped rules.
        mockMvc.perform(get("/api/v1/me/quota-rules").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scopeId").value(adminUserId.toString()))
                .andExpect(jsonPath("$[0].warnPercent").value(90));
    }

    @Test
    @DisplayName("anonymous callers are rejected; empty rules return an empty list")
    void permissionsAndEmptyState() throws Exception {
        mockMvc.perform(get("/api/v1/me/quota-rules")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/quota-rules").cookie(regularSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID tenantId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private org.springframework.test.web.servlet.ResultActions putRule(String scopeType, UUID scopeId, String metric,
            String period, long limitValue, int warnPercent) throws Exception {
        return putRule(scopeType, scopeId, metric, period, limitValue, warnPercent, null);
    }

    private org.springframework.test.web.servlet.ResultActions putRule(String scopeType, UUID scopeId, String metric,
            String period, long limitValue, int warnPercent, String status) throws Exception {
        StringBuilder body = new StringBuilder("{\"scopeType\":\"").append(scopeType).append("\",\"scopeId\":\"")
                .append(scopeId).append("\",\"metric\":\"").append(metric).append("\",\"period\":\"").append(period)
                .append("\",\"limitValue\":").append(limitValue).append(",\"warnPercent\":").append(warnPercent);
        if (status != null) {
            body.append(",\"status\":\"").append(status).append('"');
        }
        return mockMvc.perform(
                put("/api/v1/admin/quota-rules").cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.append('}').toString()));
    }

    private void resetDb() {
        for (String table : java.util.List.of("webhook_delivery_attempts", "alert_events", "alert_rules",
                "webhook_endpoints", "usage_event", "price_snapshot", "quota_rules", "quota_default_template",
                "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships", "projects",
                "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
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
