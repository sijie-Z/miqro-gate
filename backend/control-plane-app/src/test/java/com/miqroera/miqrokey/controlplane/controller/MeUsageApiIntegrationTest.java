package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usage statistics endpoints against real PostgreSQL: summary aggregation with
 * cost from price snapshots, paged records, and strict caller scoping (only the
 * user's own keys count).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Usage API integration tests (PostgreSQL)")
class MeUsageApiIntegrationTest {

    static final String MODEL = "claude-3-7-sonnet";

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
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("summary aggregates only the caller's own keys with cost from price snapshots")
    void summaryScopedToOwnKeys() throws Exception {
        fx.insertCatalogAndGrant();
        UUID keyId = fx.createOwnKey();
        fx.insertOtherUsersKey();
        fx.insertPrices();
        fx.insertUsage(keyId, "chatcmpl-own-1", 1_000L, 500L);
        fx.insertUsage(keyId, null, 200L, 100L);
        // Other user's usage must be invisible to the caller.
        fx.insertUsage(fx.otherKeyId, "chatcmpl-other-1", 9_000L, 9_000L);

        mockMvc.perform(get("/api/v1/me/usage/summary").param("groupBy", "VIRTUAL_KEY").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupBy").value("virtual_key"))
                .andExpect(jsonPath("$.groups").isArray()).andExpect(jsonPath("$.groups.length()").value(1))
                // upstream + coalesced = 2 requests, 1,200 input / 600 output tokens
                .andExpect(jsonPath("$.groups[0].requests.upstream").value(1))
                .andExpect(jsonPath("$.groups[0].requests.coalesced").value(1))
                .andExpect(jsonPath("$.groups[0].tokens.input").value(1_200))
                .andExpect(jsonPath("$.groups[0].tokens.output").value(600))
                // input 1200 * 1.00/1e6 = 0.0012; output 600 * 2.00/1e6 = 0.0012
                .andExpect(jsonPath("$.groups[0].cost.gatewayObserved").value(0.0024))
                .andExpect(jsonPath("$.groups[0].cost.upstreamPaid").value(0.002))
                .andExpect(jsonPath("$.totals.requests.total").value(2));
    }

    @Test
    @DisplayName("summary with no keys returns a zeroed summary")
    void summaryWithNoKeys() throws Exception {
        mockMvc.perform(get("/api/v1/me/usage/summary").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("project")).andExpect(jsonPath("$.groups.length()").value(0))
                .andExpect(jsonPath("$.totals.requests.total").value(0))
                .andExpect(jsonPath("$.totals.cost.upstreamPaid").value(0));
    }

    @Test
    @DisplayName("records pages the caller's own usage, newest first")
    void recordsPaged() throws Exception {
        fx.insertCatalogAndGrant();
        UUID keyId = fx.createOwnKey();
        fx.insertPrices();
        fx.insertUsage(keyId, "chatcmpl-own-1", 1_000L, 500L);
        fx.insertUsage(keyId, "chatcmpl-own-2", 200L, 100L);

        mockMvc.perform(get("/api/v1/me/usage/records").param("page", "1").param("size", "1").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].providerRequestId").value("chatcmpl-own-2"))
                .andExpect(jsonPath("$.items[0].inputTokens").value(200))
                .andExpect(jsonPath("$.items[0].outputTokens").value(100))
                .andExpect(jsonPath("$.items[0].modelId").value(MODEL));

        mockMvc.perform(get("/api/v1/me/usage/records").param("page", "2").param("size", "1").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].providerRequestId").value("chatcmpl-own-1"))
                .andExpect(jsonPath("$.items[0].latencyMs").value(42))
                .andExpect(jsonPath("$.items[0].upstreamStatusCode").value(200))
                .andExpect(jsonPath("$.items[0].isComplete").value(true))
                .andExpect(jsonPath("$.items[0].usageMissing").value(false));
    }

    @Test
    @DisplayName("records reject invalid pagination and oversized windows")
    void recordsValidation() throws Exception {
        mockMvc.perform(get("/api/v1/me/usage/records").param("page", "0").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_INVALID"));
        mockMvc.perform(get("/api/v1/me/usage/records").param("size", "999").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SIZE_INVALID"));
        mockMvc.perform(get("/api/v1/me/usage/summary").param("groupBy", "bogus").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GROUP_BY_INVALID"));
        mockMvc.perform(get("/api/v1/me/usage/records").param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-12-31T00:00:00Z").cookie(sessionCookie)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_TOO_WIDE"));
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

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID otherUserId = UUID.randomUUID();
        final UUID otherKeyId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("usage_event", "cache_hit_event", "price_snapshot", "virtual_key_models",
                    "key_project_binding", "model_approval", "virtual_keys", "project_provider_grant_models",
                    "project_provider_grants", "upstream_credential_versions", "upstream_credentials", "plan_seats",
                    "upstream_subscriptions", "project_memberships", "projects", "provider_products", "providers",
                    "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertCatalogAndGrant() throws Exception {
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
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Project One', 'ACTIVE', 'core-ai', 0)
                    """, new MapSqlParameterSource("projectId", projectId).addValue("tenantId", tenantId));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:id, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", subscriptionId));
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE',
                            '00000000-0000-0000-0000-000000000000', 0)
                    """,
                    new MapSqlParameterSource("grantId", grantId).addValue("tenantId", tenantId)
                            .addValue("projectId", projectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId));
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, :model)
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId).addValue("model",
                    MODEL));
        }

        UUID createOwnKey() throws Exception {
            MvcResult r = mockMvc.perform(post("/api/v1/me/virtual-keys").contentType(MediaType.APPLICATION_JSON)
                    .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                    .content(objectMapper.writeValueAsString(Map.of("name", "k", "projectId", projectId,
                            "providerProductId", productId, "credentialGrantId", grantId, "purpose", "CLAUDE_CODE"))))
                    .andExpect(status().isCreated()).andReturn();
            Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
            return UUID.fromString((String) body.get("id"));
        }

        /** A key owned by another user; its usage must stay invisible to the caller. */
        void insertOtherUsersKey() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("otherUserId", otherUserId).addValue("tenantId", tenantId).addValue("otherKeyId", otherKeyId)
                    .addValue("projectId", projectId).addValue("grantId", grantId)
                    .addValue("credentialId", credentialId);
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:otherUserId, :tenantId, 'other_user', 'Other', decode('00', 'hex'), 'USER', 'ACTIVE',
                            FALSE, 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO virtual_keys
                        (id, tenant_id, public_key_id, secret_digest, display_prefix, last_four, user_id, project_id,
                         grant_id, upstream_credential_id, purpose, name, cache_policy, status, version)
                    VALUES (:otherKeyId, :tenantId, 'pk-other', decode('00', 'hex'), 'pre', '0000', :otherUserId,
                            :projectId, :grantId, :credentialId, 'CLAUDE_CODE', 'other', 'DISABLED', 'ACTIVE', 0)
                    """, p);
        }

        void insertPrices() {
            Instant effective = Instant.parse("2026-01-01T00:00:00Z");
            jdbc.update("""
                    INSERT INTO price_snapshot
                        (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source,
                         created_at)
                    VALUES (:id, :productId, :model, 'INPUT', 'USD', 1.00, :effective, 'MANUAL', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("model", MODEL).addValue("effective", effective));
            jdbc.update("""
                    INSERT INTO price_snapshot
                        (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source,
                         created_at)
                    VALUES (:id, :productId, :model, 'OUTPUT', 'USD', 2.00, :effective, 'MANUAL', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("model", MODEL).addValue("effective", effective));
        }

        void insertUsage(UUID keyId, String providerRequestId, long input, long output) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, :keyId, :projectId, :productId, :credentialId, :model,
                            'UPSTREAM', :input, :output, :total, 42, 200, TRUE, FALSE, 'greq', :occurredAt)
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", providerRequestId).addValue("keyId", keyId)
                            .addValue("projectId", projectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId).addValue("model", MODEL).addValue("input", input)
                            .addValue("output", output).addValue("total", input + output)
                            .addValue("occurredAt", Instant.now()));
        }
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
