package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression (#441): the usage summary's {@code subscriptionId} filter used to
 * join a non-existent {@code credentials} table via a non-existent
 * {@code virtual_keys.credential_id} column — every such query failed with
 * {@code relation "credentials" does not exist}. The filter now joins
 * {@code upstream_credentials} on {@code virtual_keys.upstream_credential_id};
 * this test executes the real SQL end to end and proves the filter both matches
 * (107 input tokens across two seeded events) and discriminates (a stranger
 * subscription sees zero).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Usage subscription filter integration tests (PostgreSQL, #441)")
class UsageSubscriptionFilterIntegrationTest {

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
                                "usub_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        fx.adminUserId = UUID.fromString((String) bootBody.get("userId"));
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("usage summary filters by subscription against real SQL (#441)")
    void summaryFiltersBySubscription() throws Exception {
        fx.insertCatalog();
        fx.insertUsage(100, 50);
        fx.insertUsage(7, 3);
        String from = java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS).toString();
        String to = java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.HOURS).toString();

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("subscriptionId", fx.subscriptionId.toString())
                .param("groupBy", "model").param("from", from).param("to", to).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.tokens.input").value(107))
                .andExpect(jsonPath("$.totals.requests.upstream").value(2));

        // A stranger subscription must match nothing — the filter has to
        // discriminate, not merely stop throwing.
        mockMvc.perform(get("/api/v1/admin/usage/summary").param("subscriptionId", UUID.randomUUID().toString())
                .param("groupBy", "model").param("from", from).param("to", to).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.tokens.input").value(0));
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

    /**
     * Minimal FK chain (copied from the ROI fixture): catalog → subscription →
     * credential → grant → key.
     */
    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID keyId = UUID.randomUUID();
        UUID adminUserId;

        void reset() {
            for (String table : List.of("cache_hit_event", "cache_entry", "usage_event", "virtual_key_models",
                    "key_project_binding", "model_approval", "virtual_keys", "project_provider_grant_models",
                    "project_provider_grants", "unattributed_policy", "upstream_credential_versions",
                    "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships",
                    "project_repositories", "projects", "price_snapshot", "provider_products", "providers",
                    "mcp_resilience_policy", "mcp_service_access", "mcp_access_grants", "mcp_tools", "mcp_route_rule",
                    "mcp_services", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Child-first order above covers the canonical FK set
                    // (mcp tables included: their created_by FK would otherwise
                    // block the users delete and break bootstrap).
                }
            }
        }

        void insertCatalog() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:providerId, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("providerId", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("providerId", providerId).addValue("productId", productId));
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Project One', 'ACTIVE', 'core-ai', 0)
                    """, new MapSqlParameterSource("projectId", projectId).addValue("tenantId", tenantId));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:subscriptionId, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("subscriptionId", subscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:credentialId, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("credentialId", credentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", subscriptionId));
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE', :createdBy, 0)
                    """,
                    new MapSqlParameterSource("grantId", grantId).addValue("tenantId", tenantId)
                            .addValue("projectId", projectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId).addValue("createdBy", adminUserId));
            jdbc.update("""
                    INSERT INTO virtual_keys
                        (id, tenant_id, public_key_id, secret_digest, display_prefix, last_four, user_id,
                         project_id, grant_id, upstream_credential_id, purpose, name, status, version)
                    VALUES (:keyId, :tenantId, 'pk-usub-test', decode(repeat('00', 32), 'hex'), 'mqk_test_', 'abcd',
                            :adminUserId, :projectId, :grantId, :credentialId, 'CUSTOM', 'usub-key', 'ACTIVE', 0)
                    """,
                    new MapSqlParameterSource("keyId", keyId).addValue("tenantId", tenantId)
                            .addValue("adminUserId", adminUserId).addValue("projectId", projectId)
                            .addValue("grantId", grantId).addValue("credentialId", credentialId));
        }

        void insertUsage(long input, long output) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, is_complete,
                         gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :requestId, :keyId, :projectId, :productId, null, 'model-a', 'UPSTREAM',
                            :input, :output, TRUE, :gatewayId, now())
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("requestId", UUID.randomUUID().toString()).addValue("keyId", keyId)
                            .addValue("projectId", projectId).addValue("productId", productId).addValue("input", input)
                            .addValue("output", output).addValue("gatewayId", UUID.randomUUID().toString()));
        }
    }
}
