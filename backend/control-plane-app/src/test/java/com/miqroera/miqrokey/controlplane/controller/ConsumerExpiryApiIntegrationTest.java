package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.service.ConsumerKeyExpiryNotifier;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consumer key expiry (issue #322, mirror of the admin-key lifecycle): optional
 * expires_at at creation, silent 401 on every channel at/after expiry (both
 * auth repository lookups stop returning the row while the admin list still
 * shows it), validation rejects past/malformed values, and the opt-in
 * CONSUMER_KEY_EXPIRING notifier fires one deduped event per consumer per day
 * within the 7-day window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Consumer key expiry integration tests (PostgreSQL)")
class ConsumerExpiryApiIntegrationTest {

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
    ApiConsumerRepository consumerRepository;
    @Autowired
    ConsumerKeyExpiryNotifier notifier;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private final String adminUsername = "exp_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), adminUsername, "Admin"))))
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
                "api_consumers", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
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

    private String createConsumer(String name, Instant expiresAt) throws Exception {
        Map<String, String> request = expiresAt == null
                ? Map.of("name", name)
                : Map.of("name", name, "expiresAt", expiresAt.toString());
        MvcResult created = mockMvc.perform(
                post("/api/v1/admin/api-consumers").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("apiKey").toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("creation with future expiresAt surfaces in the view and billing works")
    void createWithFutureExpiry() throws Exception {
        Instant expiresAt = Instant.now().plus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String apiKey = createConsumer("platform-90d", expiresAt);

        MvcResult list = mockMvc.perform(get("/api/v1/admin/api-consumers").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn();
        String body = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains(expiresAt.toString());

        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("expiry is enforced silently on every auth lookup (list stays visible)")
    void expiredConsumerRejected() throws Exception {
        String apiKey = createConsumer("short-lived", Instant.now().plus(1, ChronoUnit.HOURS));
        // Move the expiry into the past (no update endpoint by design).
        jdbc.update("UPDATE api_consumers SET expires_at = now() - interval '1 hour' WHERE name = :name",
                new MapSqlParameterSource("name", "short-lived"));

        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey))
                .andExpect(status().isUnauthorized());
        assertThat(consumerRepository.findByKeyDigest(sha256(apiKey))).isEmpty();
        assertThat(consumerRepository.findByName("short-lived")).isEmpty();
        // Admin list continues to show the row (and its expiry) for auditing.
        MvcResult list = mockMvc.perform(get("/api/v1/admin/api-consumers").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("short-lived");
    }

    @Test
    @DisplayName("past or malformed expiresAt are rejected")
    void invalidExpiryRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/api-consumers").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"past\",\"expiresAt\":\"" + Instant.now().minusSeconds(60) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSUMER_EXPIRES_INVALID"));
        mockMvc.perform(post("/api/v1/admin/api-consumers").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"garbled\",\"expiresAt\":\"tomorrow\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSUMER_EXPIRES_INVALID"));
    }

    @Test
    @DisplayName("expiring consumers fire one deduped event per day when the opt-in rule exists")
    void expiringConsumerNotifiedOncePerDay() throws Exception {
        String apiKey = createConsumer("soon", Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));
        String consumerId = consumerRepository.findByKeyDigest(sha256(apiKey)).orElseThrow().id().toString();
        createConsumer("far", Instant.now().plus(30, ChronoUnit.DAYS));
        createConsumer("never", null);

        mockMvc.perform(post("/api/v1/admin/alert-rules")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("name",
                        "consumer-expiry", "type", "CONSUMER_KEY_EXPIRING", "threshold", 1, "dedupeMinutes", 1440))))
                .andExpect(status().isOk());

        notifier.checkNow();
        notifier.checkNow(); // per-(consumer, day) dedupe: one row only

        Integer events = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events", new MapSqlParameterSource(),
                Integer.class);
        assertThat(events).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload_json::text FROM alert_events LIMIT 1",
                new MapSqlParameterSource(), String.class);
        assertThat(payload).contains(consumerId).contains("soon");

        // Default off: disabling the rule stops new events without touching history.
        jdbc.update("UPDATE alert_rules SET enabled = FALSE WHERE type = 'CONSUMER_KEY_EXPIRING'",
                new MapSqlParameterSource());
        notifier.checkNow();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_events", new MapSqlParameterSource(), Integer.class))
                .isEqualTo(1);
    }
}
