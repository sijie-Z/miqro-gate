package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cache-ROI report (P5.4): paid/saved money and hit rates over a window with a
 * per-day series, derived from the shared usage aggregator (usage events +
 * cache-hit events priced via price snapshots).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Cache ROI API integration tests (PostgreSQL)")
class AdminRoiApiIntegrationTest {

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
    @DisplayName("ROI report prices paid and saved traffic and derives hit rates")
    void roiTotals() throws Exception {
        fx.insertCatalog();
        fx.insertPrice("INPUT", "2");
        fx.insertPrice("OUTPUT", "8");
        fx.insertPrice("CACHE_READ", "0.5");
        fx.insertUsage(1000L, 500L); // one upstream request: paid = 2*1000 + 8*500 / 1M
        byte[] cacheKey = fx.insertCacheEntryAndHits(2); // two L2 hits
        Integer hitRows = jdbc.queryForObject("SELECT COUNT(*) FROM cache_hit_event", new MapSqlParameterSource(),
                Integer.class);
        assertThat(hitRows).as("both hit rows must be inserted").isEqualTo(2);

        mockMvc.perform(get("/api/v1/admin/usage/roi").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.upstreamRequests").value(1))
                .andExpect(jsonPath("$.totals.l2Hits").value(2)).andExpect(jsonPath("$.totals.hitRatePct").value(66.67))
                .andExpect(jsonPath("$.totals.paidCost").value(0.006))
                .andExpect(jsonPath("$.totals.savedCost").value(0.005))
                .andExpect(jsonPath("$.totals.savedPct").value(45.45)).andExpect(jsonPath("$.byDay.length()").value(1))
                .andExpect(jsonPath("$.byDay[0].hitRequests").value(2));

        // Cache hits on the key must exist (sanity on the seed itself).
        assertThat(cacheKey).hasSize(32);
    }

    @Test
    @DisplayName("empty windows report zero paid and no days")
    void emptyWindow() throws Exception {
        String from = "2025-01-01T00:00:00Z";
        String to = "2025-01-02T00:00:00Z";
        mockMvc.perform(get("/api/v1/admin/usage/roi").param("from", from).param("to", to).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.paidCost").value(0))
                .andExpect(jsonPath("$.totals.hitRatePct").value(0.0)).andExpect(jsonPath("$.byDay.length()").value(0));
    }

    @Test
    @DisplayName("window and permission validation")
    void validation() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usage/roi").cookie(sessionCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/usage/roi").param("from", "not-an-instant").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        // Wider than the shared 93-day rule.
        mockMvc.perform(get("/api/v1/admin/usage/roi").param("from", "2024-01-01T00:00:00Z")
                .param("to", "2026-09-02T00:00:00Z").cookie(sessionCookie)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/usage/roi")).andExpect(status().isUnauthorized());
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    /** Minimal JDBC fixtures: catalog + key chain + prices + usage + cache. */
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
                    "project_provider_grants", "upstream_credential_versions", "upstream_credentials", "plan_seats",
                    "upstream_subscriptions", "project_memberships", "projects", "price_snapshot", "provider_products",
                    "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Child-first order above covers the canonical FK set.
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
                    VALUES (:keyId, :tenantId, 'pk-roi-test', decode(repeat('00', 32), 'hex'), 'mqk_test_', 'abcd',
                            :adminUserId, :projectId, :grantId, :credentialId, 'CUSTOM', 'roi-key', 'ACTIVE', 0)
                    """,
                    new MapSqlParameterSource("keyId", keyId).addValue("tenantId", tenantId)
                            .addValue("adminUserId", adminUserId).addValue("projectId", projectId)
                            .addValue("grantId", grantId).addValue("credentialId", credentialId));
        }

        void insertPrice(String tokenType, String unitPrice) {
            jdbc.update("""
                    INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type, currency, unit_price)
                    VALUES (:id, :productId, 'model-a', :tokenType, 'CNY', :unitPrice)
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("tokenType", tokenType).addValue("unitPrice", new java.math.BigDecimal(unitPrice)));
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

        /** Seeds one cache entry with {@code hits} L2 hits on distinct seconds. */
        byte[] insertCacheEntryAndHits(int hits) {
            byte[] cacheKey = sha256(UUID.randomUUID().toString());
            String keyHex = HexFormat.of().formatHex(cacheKey);
            jdbc.update("""
                    INSERT INTO cache_entry
                        (id, tenant_id, cache_key, virtual_key_id, project_id, provider_product_id, model_id,
                         status_code, body, meta_json, hit_count_l1, hit_count_l2, created_at, updated_at)
                    VALUES (:id, :tenantId, decode(:keyHex, 'hex'), :keyId, :projectId, :productId, 'model-a',
                            200, decode('', 'hex'),
                            '{"usage":{"inputTokens":1000,"cacheReadInputTokens":1000}}', 0, :hits, now(), now())
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("keyHex", keyHex).addValue("keyId", keyId).addValue("projectId", projectId)
                            .addValue("productId", productId).addValue("hits", hits));
            for (int i = 0; i < hits; i++) {
                jdbc.update("""
                        INSERT INTO cache_hit_event
                            (id, tenant_id, cache_key, virtual_key_id, project_id, provider_product_id, level,
                             occurred_at, gateway_request_id, created_at)
                        VALUES (:id, :tenantId, decode(:keyHex, 'hex'), :keyId, :projectId, :productId, 'L2_HIT',
                                now() - make_interval(secs => :offset), :gatewayId, now())
                        """,
                        new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                                .addValue("keyHex", keyHex).addValue("keyId", keyId).addValue("projectId", projectId)
                                .addValue("productId", productId).addValue("offset", 2 + i)
                                .addValue("gatewayId", UUID.randomUUID().toString()));
            }
            return cacheKey;
        }

        private static byte[] sha256(String value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
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
