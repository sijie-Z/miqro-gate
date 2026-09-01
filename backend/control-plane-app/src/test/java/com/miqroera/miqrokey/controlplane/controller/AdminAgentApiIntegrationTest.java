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
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Managed smart agents (P3.1): CRUD with credential validation, per-agent usage
 * aggregation over the bound credential.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin agent API integration tests (PostgreSQL)")
class AdminAgentApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    /** Seed tenant of the single-tenant deployment (bootstrap creates it). */
    private static final UUID SEED_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        for (String table : new String[]{"agents", "usage_event", "virtual_keys", "project_provider_grant_models",
                "project_provider_grants", "upstream_credential_versions", "upstream_credentials", "quota_snapshots",
                "upstream_subscriptions", "projects", "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("agent endpoints require authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/agents")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("agent lifecycle: create with derived product, list, disable")
    void agentLifecycle() throws Exception {
        UUID credentialId = seedCredential("MiqroForge Cred", "ACTIVE");

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/agents").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"miqro-forge\",\"description\":\"Internal coding agent\","
                                + "\"credentialId\":\"" + credentialId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("miqro-forge"))
                .andExpect(jsonPath("$.credentialName").value("MiqroForge Cred"))
                .andExpect(jsonPath("$.providerProductName").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();
        String agentId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // List and single view.
        mockMvc.perform(get("/api/v1/admin/agents").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/v1/admin/agents/" + agentId).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.providerProductName").isNotEmpty());

        // Disable, then conflict on re-disable.
        mockMvc.perform(post("/api/v1/admin/agents/" + agentId + "/disable").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(post("/api/v1/admin/agents/" + agentId + "/disable").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_ALREADY_DISABLED"));
    }

    @Test
    @DisplayName("agent creation validates the credential and the name")
    void agentValidation() throws Exception {
        // Unknown credential.
        mockMvc.perform(post("/api/v1/admin/agents").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"forge\",\"credentialId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));

        // Disabled credential.
        UUID disabled = seedCredential("Disabled Cred", "DISABLED");
        mockMvc.perform(post("/api/v1/admin/agents").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"forge\",\"credentialId\":\"" + disabled + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));

        // Duplicate name.
        UUID credentialId = seedCredential("Forge Cred", "ACTIVE");
        String body = "{\"name\":\"forge\",\"credentialId\":\"" + credentialId + "\"}";
        mockMvc.perform(post("/api/v1/admin/agents").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/agents").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_NAME_TAKEN"));
    }

    @Test
    @DisplayName("per-agent usage aggregates over the bound credential")
    void agentUsage() throws Exception {
        UUID credentialId = seedCredential("Forge Cred", "ACTIVE");
        seedUsage(credentialId, 100L, 50L);

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/agents").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"miqro-forge\",\"credentialId\":\"" + credentialId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String agentId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(get("/api/v1/admin/agents/" + agentId + "/usage").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.requests.upstream").value(1))
                .andExpect(jsonPath("$.totals.tokens.input").value(100))
                .andExpect(jsonPath("$.totals.tokens.output").value(50));
    }

    // ------------------------------------------------------------------

    private UUID seedCredential(String name, String status) {
        UUID productId = jdbc.queryForObject("SELECT id FROM provider_products ORDER BY display_name LIMIT 1",
                new MapSqlParameterSource(), UUID.class);
        UUID subscriptionId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upstream_subscriptions
                    (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                VALUES (:id, :tenantId, :productId, 'Agent Sub', 'PAYG', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", SEED_TENANT)
                .addValue("productId", productId));
        jdbc.update("""
                INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                VALUES (:id, :tenantId, :subscriptionId, :name, :status, 0)
                """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", SEED_TENANT)
                .addValue("subscriptionId", subscriptionId).addValue("name", name).addValue("status", status));
        return credentialId;
    }

    private void seedUsage(UUID credentialId, long input, long output) {
        UUID productId = jdbc.queryForObject("SELECT id FROM provider_products ORDER BY display_name LIMIT 1",
                new MapSqlParameterSource(), UUID.class);
        UUID projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                VALUES (:id, :tenantId, 'AGENT', 'Agent Project', 'ACTIVE', 'agent-proj', 0)
                """, new MapSqlParameterSource("id", projectId).addValue("tenantId", SEED_TENANT));
        jdbc.update("""
                INSERT INTO usage_event
                    (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                     credential_id, model_id, cache_level, input_tokens, output_tokens, is_complete,
                     gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :requestId, :keyId, :projectId, :productId, :credentialId, 'agent-model',
                        'UPSTREAM', :input, :output, TRUE, :gatewayId, now())
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", SEED_TENANT)
                        .addValue("requestId", UUID.randomUUID().toString()).addValue("keyId", UUID.randomUUID())
                        .addValue("projectId", projectId).addValue("productId", productId)
                        .addValue("credentialId", credentialId).addValue("input", input).addValue("output", output)
                        .addValue("gatewayId", UUID.randomUUID().toString()));
    }

    private static Cookie cookie(MvcResult result, String name) {
        return java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                .findFirst().orElse(null);
    }
}
