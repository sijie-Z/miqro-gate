package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Input-validation consistency for {@code POST /api/v1/admin/alert-rules}
 * (PH23).
 *
 * <p>
 * The admin UI refuses a blank rule name
 * ({@code NextAdminAlertRulesView.createRule} line 134: "规则名称必填。") and
 * normalises a zero dedupe window to 60, so the API must answer {@code 400} for
 * these values — not store them and not leak a {@code varchar(200)}/
 * {@code numeric(12,6)} breach as a generic 409.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Alert rule input validation (PH23)")
class AlertRuleInputValidationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.alerts.evaluation-interval-ms", () -> "3600000");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        resetData();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    private void resetData() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "export_tasks", "usage_deletions", "usage_event", "cache_hit_event", "price_snapshot",
                "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                "project_memberships", "project_repositories", "projects", "provider_products", "providers",
                "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // child-first ordering for the canonical migration set
            }
        }
    }

    private static Cookie cookie(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private org.springframework.test.web.servlet.ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "rule-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("type", "USAGE_MISSING_RATE");
        body.put("threshold", 0.5);
        body.put("dedupeMinutes", 60);
        return body;
    }

    @Test
    @DisplayName("absent name is a 400, not a NOT NULL breach mapped to 409")
    void missingNameIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("name");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("whitespace-only name is a 400, not a nameless row in the rule list")
    void whitespaceNameIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("name", "   ");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("name longer than varchar(200) is a 400, not a 409")
    void oversizedNameIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("name", "n".repeat(201));
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("absent threshold is a 400, not a NOT NULL breach mapped to 409")
    void missingThresholdIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("threshold");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("threshold outside numeric(12,6) is a 400, not a 409")
    void outOfColumnThresholdIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("threshold", new java.math.BigDecimal("1000000.000001"));
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("zero dedupe window is a 400, not a stored zero")
    void zeroDedupeIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("dedupeMinutes", 0);
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("negative dedupe window is a 400, not a stored negative window")
    void negativeDedupeIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("dedupeMinutes", -5);
        create(body).andExpect(status().isBadRequest());
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
