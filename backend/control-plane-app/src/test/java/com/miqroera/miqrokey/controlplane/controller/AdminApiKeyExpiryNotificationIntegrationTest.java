package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.service.AdminApiKeyExpiryNotifier;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADMIN_API_KEY_EXPIRING event type (F60 batch 3 follow-up): opt-in alert rule
 * + daily notifier fires one event per key expiring within 7 days, deduped per
 * (key, day); keys beyond the horizon stay silent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin API key expiry notification integration tests (PostgreSQL)")
class AdminApiKeyExpiryNotificationIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminProviderApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    AdminApiKeyExpiryNotifier notifier;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(AdminProviderApiIntegrationTest.BootstrapHelper.secret(),
                                        "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "admin_api_keys", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("expiring keys fire one deduped event per day when an opt-in rule exists")
    void expiringKeyNotifiedOncePerDay() throws Exception {
        String expiringSecret = issueKey("soon", Instant.now().plus(2, ChronoUnit.DAYS));
        issueKey("far", Instant.now().plus(30, ChronoUnit.DAYS));
        String expiringId = keyId(expiringSecret);

        mockMvc.perform(post("/api/v1/admin/alert-rules")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("name",
                        "key-expiry", "type", "ADMIN_API_KEY_EXPIRING", "threshold", 1, "dedupeMinutes", 1440))))
                .andExpect(status().isOk());

        notifier.checkNow();
        notifier.checkNow(); // dedupe: same key + same day fires once

        // debug: rule and window must line up before asserting events
        Integer ruleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_rules WHERE type = 'ADMIN_API_KEY_EXPIRING' AND enabled",
                new MapSqlParameterSource(), Integer.class);
        assertThat(ruleCount).isEqualTo(1);
        Integer inWindow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_api_keys WHERE revoked_at IS NULL AND expires_at BETWEEN now() AND now() + interval '7 days'",
                new MapSqlParameterSource(), Integer.class);
        assertThat(inWindow).isEqualTo(1);
        Integer nullExpiry = jdbc.queryForObject("SELECT COUNT(*) FROM admin_api_keys WHERE expires_at IS NULL",
                new MapSqlParameterSource(), Integer.class);
        assertThat(nullExpiry).isZero();

        Integer events = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events", new MapSqlParameterSource(),
                Integer.class);
        assertThat(events).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload_json::text FROM alert_events LIMIT 1",
                new MapSqlParameterSource(), String.class);
        assertThat(payload).contains(expiringId).contains("soon");

        // Disabling the rule stops new events (default off) without touching history.
        jdbc.update("UPDATE alert_rules SET enabled = FALSE WHERE type = 'ADMIN_API_KEY_EXPIRING'",
                new MapSqlParameterSource());
        notifier.checkNow();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_events", new MapSqlParameterSource(), Integer.class))
                .isEqualTo(1);
    }

    private String issueKey(String name, Instant expiresAt) throws Exception {
        MvcResult issued = mockMvc
                .perform(post("/api/v1/admin/api-keys").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}").param("expiresAt", expiresAt.toString()))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> body = objectMapper.readValue(issued.getResponse().getContentAsString(), Map.class);
        return String.valueOf(body.get("secret"));
    }

    private String keyId(String secret) {
        return jdbc.queryForObject("SELECT id::text FROM admin_api_keys WHERE key_prefix = left(:secret, 18)",
                new MapSqlParameterSource("secret", secret), String.class);
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie c : r.getResponse().getCookies()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }
}
