package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * External-system API channel (ADR-0010): consumers, API-key auth on
 * {@code /api/v1/billing/**}, and the admin consumer lifecycle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Billing API channel integration tests (PostgreSQL)")
class BillingApiIntegrationTest {
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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminProviderApiIntegrationTest.BootstrapHelper.secret(), "root", "Admin"))))
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
        for (String table : new String[]{"api_consumers", "usage_event", "cache_hit_event", "virtual_keys",
                "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                "upstream_credentials", "upstream_subscriptions", "projects", "user_sessions", "users",
                "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("billing endpoints reject requests without an API key or session")
    void billingRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/billing/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/billing/records")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a created consumer key accesses billing; admin session also passes")
    void consumerKeyAccessesBilling() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/api-consumers").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content("{\"name\":\"platform\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shownOnce").value(true))
                .andExpect(jsonPath("$.consumer.keyPrefix").isNotEmpty()).andReturn();
        String apiKey = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("apiKey")
                .toString();
        assert apiKey.startsWith("mqk_api_");

        // API key works.
        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey)).andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").isNotEmpty());

        // Admin session works.
        mockMvc.perform(get("/api/v1/billing/summary").cookie(sessionCookie)).andExpect(status().isOk());

        // A random key is rejected.
        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", "mqk_api_00000000_bogus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("disabling a consumer revokes its key immediately")
    void disableRevokesKey() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/api-consumers").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken).content("{\"name\":\"platform\"}"))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> createdBody = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        String apiKey = createdBody.get("apiKey").toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> consumer = (Map<String, Object>) createdBody.get("consumer");
        String consumerId = consumer.get("id").toString();

        mockMvc.perform(post("/api/v1/admin/api-consumers/" + consumerId + "/disable").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("duplicate consumer names are rejected")
    void duplicateNameRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/api-consumers").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken).content("{\"name\":\"platform\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/api-consumers").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken).content("{\"name\":\"platform\"}"))
                .andExpect(status().isConflict());
    }

    private static Cookie cookie(MvcResult result, String name) {
        return java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                .findFirst().orElse(null);
    }
}
