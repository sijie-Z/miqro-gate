package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quota snapshot refresh against real PostgreSQL + a loopback mock of the
 * DeepSeek balance API (G4.2): the admin-triggered refresh decrypts the
 * credential, calls the real adapter's {@code fetchPlanStatus} through the real
 * {@code HttpProviderClient}, persists an OFFICIAL_API row and a LOCAL_ESTIMATE
 * row, and the GET view returns them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Quota snapshot API integration tests (PostgreSQL)")
class QuotaSnapshotApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // The mock balance server runs on loopback; production default would
        // reject it via the SSRF validator.
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
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
    private HttpServer mockBalanceServer;
    private String mockBaseUrl;
    private final AtomicInteger balanceCalls = new AtomicInteger();
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        mockBalanceServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        mockBalanceServer.createContext("/user/balance", this::handleBalance);
        mockBalanceServer.start();
        mockBaseUrl = "http://127.0.0.1:" + mockBalanceServer.getAddress().getPort();

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
        if (mockBalanceServer != null) {
            mockBalanceServer.stop(0);
        }
        fx.reset();
    }

    private void handleBalance(HttpExchange exchange) throws java.io.IOException {
        balanceCalls.incrementAndGet();
        byte[] body = ("{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"110.00\","
                + "\"granted_balance\":\"10.00\",\"topped_up_balance\":\"100.00\"}]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    @DisplayName("admin refresh fetches the official balance and writes estimate and unavailable rows honestly")
    void refreshWritesOfficialEstimateAndUnavailableRows() throws Exception {
        fx.insertCatalogAndGrant(mockBaseUrl);
        fx.createCredentialViaApi("sk-test-1234567890");
        // A second subscription without credentials -> UNAVAILABLE only.
        fx.insertEmptySubscription(mockBaseUrl);

        mockMvc.perform(post("/api/v1/admin/subscriptions/" + fx.subscriptionId + "/quota/refresh")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source=='OFFICIAL_API')].remaining").value(110.00))
                .andExpect(jsonPath("$[?(@.source=='LOCAL_ESTIMATE')].total").value(1000.0))
                .andExpect(jsonPath("$[?(@.source=='LOCAL_ESTIMATE')].remaining").value(1000.0));

        // The mock was actually called once (per-credential fetch).
        org.assertj.core.api.Assertions.assertThat(balanceCalls.get()).isEqualTo(1);

        // GET returns the same latest-per-scope view.
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + fx.subscriptionId + "/quota").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source=='OFFICIAL_API')].remaining").value(110.00));

        // The empty subscription records an honest UNAVAILABLE row after its
        // own refresh.
        mockMvc.perform(post("/api/v1/admin/subscriptions/" + fx.emptySubscriptionId + "/quota/refresh")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("UNAVAILABLE"))
                .andExpect(jsonPath("$[0].total").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + fx.emptySubscriptionId + "/quota").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].source").value("UNAVAILABLE"))
                .andExpect(jsonPath("$[0].total").doesNotExist());
    }

    @Test
    @DisplayName("unknown or foreign subscriptions are uniformly 404")
    void unknownSubscriptionIs404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + UUID.randomUUID() + "/quota").cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/admin/subscriptions/" + UUID.randomUUID() + "/quota/refresh")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("anonymous access is rejected at the session layer")
    void anonymousForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/subscriptions/" + fx.subscriptionId + "/quota"))
                .andExpect(status().isUnauthorized());
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
        final UUID emptySubscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("quota_snapshots", "usage_event", "cache_hit_event", "price_snapshot",
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

        /**
         * Seeds a provider/product with the real {@code deepseek-payg-api} adapterId
         * and the mock balance server as base URL, plus a subscription with quota_total
         * so the LOCAL_ESTIMATE row is produced.
         */
        void insertCatalogAndGrant(String balanceBaseUrl) {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'deepseek', 'DeepSeek', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'deepseek-payg-api', 'DeepSeek PAYG', 'PAYG', 'SINGLE_SHARED',
                            '["chat_completions","messages"]', CAST(:baseUrl AS jsonb), '{"type":"bearer"}',
                            'IMPLEMENTED', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId)
                    .addValue("baseUrl", "[{\"url\":\"" + balanceBaseUrl + "\"}]"));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, plan_scope, status, quota_total,
                         quota_unit, period_start, period_end, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'PAYG', 'NONE', 'ACTIVE', 1000, 'TOKENS',
                            :periodStart, :periodEnd, 0)
                    """,
                    new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                            .addValue("productId", productId)
                            .addValue("periodStart", java.sql.Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")))
                            .addValue("periodEnd", java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"))));
        }

        void insertEmptySubscription(String balanceBaseUrl) {
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, plan_scope, status, quota_total,
                         quota_unit, version)
                    VALUES (:id, :tenantId, :productId, 'Empty', 'PAYG', 'NONE', 'ACTIVE', 1000, 'TOKENS', 0)
                    """, new MapSqlParameterSource("id", emptySubscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
        }

        /** Creates an ACTIVE credential through the admin API (real encryption). */
        void createCredentialViaApi(String secret) throws Exception {
            mockMvc.perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON)
                    .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                    .content(objectMapper.writeValueAsString(
                            Map.of("name", "cred-1", "subscriptionId", subscriptionId.toString(), "secret", secret))))
                    .andExpect(status().isCreated()).andReturn();
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
