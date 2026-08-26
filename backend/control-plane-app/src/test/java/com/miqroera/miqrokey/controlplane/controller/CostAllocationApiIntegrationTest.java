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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cost allocation endpoints against real PostgreSQL (G4.3): the admin-triggered
 * allocation computes per-project metered cost from price snapshots, prorates
 * and distributes the Plan fixed cost by token weight, persists idempotently,
 * and the GET view returns the same rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Cost allocation API integration tests (PostgreSQL)")
class CostAllocationApiIntegrationTest {

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
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("allocation computes metered cost and weighted fixed share per project")
    void allocateComputesPerProjectCosts() throws Exception {
        fx.insertCatalogAndSubscription();
        fx.createCredentialViaApi("sk-test-1234567890");
        fx.insertPrices();
        fx.insertUsage(fx.projectA, "req-a-1", 1_000L, 500L);
        fx.insertUsage(fx.projectB, "req-b-1", 500L, 0L);

        mockMvc.perform(post("/api/v1/admin/subscriptions/" + fx.subscriptionId + "/cost-allocation/allocate")
                .param("from", "2026-08-01T00:00:00Z").param("to", "2026-08-31T00:00:00Z")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.targetId=='" + fx.projectA + "')].usageCost").value(0.002))
                .andExpect(jsonPath("$[?(@.targetId=='" + fx.projectA + "')].weightTokens").value(1500))
                // Fixed 100 prorated over the full 30-day period, split 3:1.
                .andExpect(jsonPath("$[?(@.targetId=='" + fx.projectA + "')].fixedCost").value(75.0))
                .andExpect(jsonPath("$[?(@.targetId=='" + fx.projectB + "')].fixedCost").value(25.0))
                .andExpect(jsonPath("$[?(@.targetId=='" + fx.projectA + "')].algorithmVersion").value("1"));

        // GET returns the persisted rows without recomputation.
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + fx.subscriptionId + "/cost-allocation")
                .param("from", "2026-08-01T00:00:00Z").param("to", "2026-08-31T00:00:00Z").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    @DisplayName("allocation without usage writes nothing")
    void allocateWithoutUsageWritesNothing() throws Exception {
        fx.insertCatalogAndSubscription();

        mockMvc.perform(post("/api/v1/admin/subscriptions/" + fx.subscriptionId + "/cost-allocation/allocate")
                .param("from", "2026-08-01T00:00:00Z").param("to", "2026-08-31T00:00:00Z")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("unknown subscriptions are 404 and anonymous is 401")
    void errorPaths() throws Exception {
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + UUID.randomUUID() + "/cost-allocation")
                .param("from", "2026-08-01T00:00:00Z").param("to", "2026-08-31T00:00:00Z").cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + UUID.randomUUID() + "/cost-allocation")
                .param("from", "2026-08-01T00:00:00Z").param("to", "2026-08-31T00:00:00Z"))
                .andExpect(status().isUnauthorized());
        // Resource existence is checked before parameters (uniform 404, no
        // validation leakage for unknown resources); an existing subscription
        // with an inverted period is rejected as a bad request.
        fx.insertCatalogAndSubscription();
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + fx.subscriptionId + "/cost-allocation")
                .param("from", "2026-08-31T00:00:00Z").param("to", "2026-08-01T00:00:00Z").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
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
        final UUID projectA = UUID.randomUUID();
        final UUID projectB = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("cost_allocations", "usage_event", "cache_hit_event", "price_snapshot",
                    "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                    "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships", "projects",
                    "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertCatalogAndSubscription() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'FIXED_SUBSCRIPTION',
                            'SINGLE_SHARED', '["messages"]', '[{"url":"https://api.test.example"}]',
                            '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:a, :tenantId, 'A', 'Project A', 'ACTIVE', 'tag-a', 0),
                           (:b, :tenantId, 'B', 'Project B', 'ACTIVE', 'tag-b', 0)
                    """,
                    new MapSqlParameterSource("a", projectA).addValue("b", projectB).addValue("tenantId", tenantId));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, plan_scope, subscription_price,
                         currency, status, period_start, period_end, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'FIXED_SUBSCRIPTION', 'PERSONAL', 100.00, 'USD',
                            'ACTIVE', :periodStart, :periodEnd, 0)
                    """,
                    new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                            .addValue("productId", productId)
                            .addValue("periodStart", Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")))
                            .addValue("periodEnd", Timestamp.from(Instant.parse("2026-08-31T00:00:00Z"))));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:id, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", subscriptionId));
        }

        /**
         * Creates an ACTIVE credential version through the admin API (real encryption).
         */
        void createCredentialViaApi(String secret) throws Exception {
            mockMvc.perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON)
                    .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                    .content(objectMapper.writeValueAsString(
                            Map.of("name", "cred-1", "subscriptionId", subscriptionId.toString(), "secret", secret))))
                    .andExpect(status().isCreated()).andReturn();
        }

        void insertPrices() {
            Instant effective = Instant.parse("2026-01-01T00:00:00Z");
            jdbc.update("""
                    INSERT INTO price_snapshot
                        (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source,
                         created_at)
                    VALUES (:id, :productId, :model, 'INPUT', 'USD', 1.00, :effective, 'MANUAL', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("model", MODEL).addValue("effective", Timestamp.from(effective)));
            jdbc.update("""
                    INSERT INTO price_snapshot
                        (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source,
                         created_at)
                    VALUES (:id, :productId, :model, 'OUTPUT', 'USD', 2.00, :effective, 'MANUAL', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("model", MODEL).addValue("effective", Timestamp.from(effective)));
        }

        void insertUsage(UUID projectId, String providerRequestId, long input, long output) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, '00000000-0000-0000-0000-000000000000', :projectId,
                            :productId, :credentialId, :model, 'UPSTREAM', :input, :output, :total, 42, 200, TRUE,
                            FALSE, 'greq', :occurredAt)
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                    .addValue("providerRequestId", providerRequestId).addValue("projectId", projectId)
                    .addValue("productId", productId).addValue("credentialId", credentialId).addValue("model", MODEL)
                    .addValue("input", input).addValue("output", output).addValue("total", input + output)
                    .addValue("occurredAt", Timestamp.from(Instant.parse("2026-08-10T00:00:00Z"))));
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
