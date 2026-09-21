package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Price-catalog sync (issue #585) against a loopback price source and real
 * PostgreSQL: prefix/alias mapping writes CNY snapshots (USD×rate), unchanged
 * values are skipped on re-sync, unmapped models are reported, and a source
 * failure is a sanitized 502 that writes nothing. The scheduled variant (issue
 * #708) additionally never replaces a MANUAL snapshot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Price catalog sync integration tests (PostgreSQL)")
class PriceSyncApiIntegrationTest {
    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String FIXTURE = """
            {"data":[
              {"id":"deepseek/deepseek-v4.1-flash","pricing":{"prompt":"0.0000003","completion":"0.0000012","input_cache_read":"0.000000006"}},
              {"id":"deepseek/deepseek-v4.1-flash:batch","pricing":{"prompt":"0.00000015","completion":"0.0000006"}},
              {"id":"deepseek/deepseek-v4-pro","pricing":{"prompt":"0.00000132","completion":"0.00000396","input_cache_read":"0.000000044"}},
              {"id":"z-ai/glm-5.3","pricing":{"prompt":"0.0000006","completion":"0.0000024"}},
              {"id":"openai/whatever","pricing":{"prompt":"0.000001","completion":"0.000002"}}
            ]}
            """;

    private static final AtomicInteger SOURCE_STATUS = new AtomicInteger(200);
    private static final HttpServer PRICE_SOURCE;
    private static final int PRICE_SOURCE_PORT;

    static {
        try {
            PRICE_SOURCE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PRICE_SOURCE.createContext("/api/v1/models", exchange -> {
                byte[] body = FIXTURE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(SOURCE_STATUS.get(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            PRICE_SOURCE.start();
            PRICE_SOURCE_PORT = PRICE_SOURCE.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("loopback price source failed to start", e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.price-sync.url", () -> "http://127.0.0.1:" + PRICE_SOURCE_PORT + "/api/v1/models");
        registry.add("miqrokey.price-sync.usd-cny-rate", () -> "7");
    }

    @AfterAll
    static void stopSource() {
        PRICE_SOURCE.stop(0);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    com.miqroera.miqrokey.controlplane.service.AdminPriceSyncService priceSyncService;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        SOURCE_STATUS.set(200);
        fx.reset();
        fx.insertPaygProductWithCatalog();
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
    @DisplayName("sync converts USD quotes to CNY snapshots for catalog models (alias + batch skip)")
    void syncWritesConvertedSnapshots() throws Exception {
        mockMvc.perform(
                post("/api/v1/admin/prices/sync").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.written").value(6))
                .andExpect(jsonPath("$.unchanged").value(0)).andExpect(jsonPath("$.unmatched.length()").value(1))
                .andExpect(jsonPath("$.unmatched[0].modelId").value("ghost-model"));

        List<Map<String, Object>> prices = listPrices();
        assertThat(prices).hasSize(6);
        // deepseek-flash resolves through the alias; the ':batch' variant (half price)
        // must not win.
        assertThat(priceOf(prices, "deepseek-flash", "INPUT")).isEqualByComparingTo("2.100000");
        assertThat(priceOf(prices, "deepseek-flash", "OUTPUT")).isEqualByComparingTo("8.400000");
        assertThat(priceOf(prices, "deepseek-flash", "CACHE_READ")).isEqualByComparingTo("0.042000");
        assertThat(priceOf(prices, "deepseek-v4-pro", "OUTPUT")).isEqualByComparingTo("27.720000");
        assertThat(prices).allSatisfy(row -> {
            assertThat(row.get("currency")).isEqualTo("CNY");
            assertThat(row.get("source")).isEqualTo("OFFICIAL");
        });
        // The non-PAYG product's catalog model (glm-5.3) is out of scope.
        assertThat(prices).noneSatisfy(row -> assertThat(row.get("modelId")).isEqualTo("glm-5.3"));
    }

    @Test
    @DisplayName("a second sync skips unchanged values")
    void resyncSkipsUnchanged() throws Exception {
        mockMvc.perform(
                post("/api/v1/admin/prices/sync").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk());
        mockMvc.perform(
                post("/api/v1/admin/prices/sync").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.written").value(0))
                .andExpect(jsonPath("$.unchanged").value(6));

        assertThat(countAudit("PRICE_SYNC")).isEqualTo(2);
    }

    @Test
    @DisplayName("a source failure is a sanitized 502 that writes nothing")
    void sourceFailureWritesNothing() throws Exception {
        SOURCE_STATUS.set(500);

        mockMvc.perform(
                post("/api/v1/admin/prices/sync").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PRICE_SYNC_FAILED"));

        assertThat(listPrices()).isEmpty();
        assertThat(countAudit("PRICE_SYNC_FAILED")).isEqualTo(1);
    }

    @Test
    @DisplayName("anonymous requests are rejected")
    void anonymousRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/prices/sync")).andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the scheduled run keeps a MANUAL snapshot and reports the conflict (#708)")
    void scheduledRunPreservesManualPrice() throws Exception {
        // A human-curated price already owns (deepseek-flash, INPUT).
        jdbc.update(
                """
                        INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type, currency, unit_price,
                            effective_from, source, created_by, created_at)
                        VALUES (gen_random_uuid(), :productId, 'deepseek-flash', 'INPUT', 'CNY', 9.99, now(), 'MANUAL', NULL, now())
                        """,
                new MapSqlParameterSource("productId", fx.paygProductId));

        Map<String, Object> report = priceSyncService.syncPreservingManual(SEED_TENANT_ID,
                com.miqroera.miqrokey.controlplane.service.AuditContext.human(null, "scheduled-price-sync"));

        // The 6-quote fixture loses exactly one row to the manual owner.
        assertThat(report.get("written")).isEqualTo(5);
        assertThat(report.get("unchanged")).isEqualTo(0);
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) report.get("conflicts");
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).get("modelId")).isEqualTo("deepseek-flash");
        assertThat(conflicts.get(0).get("tokenType")).isEqualTo("INPUT");

        // The manual entry is untouched and still the effective price for the key.
        assertThat(priceOf(listPrices(), "deepseek-flash", "INPUT")).isEqualByComparingTo("9.99");
        Integer manualRows = jdbc.queryForObject("""
                SELECT count(*) FROM price_snapshot WHERE model_id = 'deepseek-flash' AND token_type = 'INPUT'
                """, new MapSqlParameterSource(), Integer.class);
        assertThat(manualRows).isEqualTo(1);
        // The other quotes still landed as OFFICIAL.
        assertThat(priceOf(listPrices(), "deepseek-flash", "OUTPUT")).isEqualByComparingTo("8.400000");
        assertThat(countAudit("PRICE_SYNC")).isEqualTo(1);
    }

    private List<Map<String, Object>> listPrices() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/prices").cookie(sessionCookie)).andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), List.class);
    }

    private static java.math.BigDecimal priceOf(List<Map<String, Object>> prices, String modelId, String tokenType) {
        return prices.stream()
                .filter(row -> modelId.equals(row.get("modelId")) && tokenType.equals(row.get("tokenType")))
                .map(row -> new java.math.BigDecimal(String.valueOf(row.get("unitPrice")))).findFirst().orElseThrow();
    }

    private int countAudit(String action) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Integer.class);
        return count == null ? 0 : count;
    }

    private static Cookie cookie(MvcResult result, String name) {
        return result.getResponse().getCookies() != null
                ? java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                        .findFirst().orElse(null)
                : null;
    }

    private final class Fixture {
        final UUID providerId = UUID.randomUUID();
        final UUID paygProductId = UUID.randomUUID();
        final UUID planProductId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("price_snapshot", "model_catalog", "provider_products", "providers",
                    "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertPaygProductWithCatalog() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            insertProduct(paygProductId, "deepseek-payg-api", "PAYG");
            insertProduct(planProductId, "zhipu-coding-plan-personal", "FIXED_SUBSCRIPTION");
            insertCatalogModel(paygProductId, "deepseek-flash");
            insertCatalogModel(paygProductId, "deepseek-v4-pro");
            insertCatalogModel(paygProductId, "ghost-model");
            insertCatalogModel(planProductId, "glm-5.3");
        }

        private void insertProduct(UUID productId, String code, String billingMode) {
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status,
                         balance_authority, version)
                    VALUES (:productId, :providerId, :code, :code, :billingMode, 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}',
                            'VERIFIED', 'OFFICIAL_API', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId)
                    .addValue("code", code).addValue("billingMode", billingMode));
        }

        private void insertCatalogModel(UUID productId, String modelId) {
            jdbc.update("""
                    INSERT INTO model_catalog (id, provider_product_id, model_id, display_name, status, version,
                        source, created_at, updated_at)
                    VALUES (:id, :productId, :modelId, :modelId, 'ACTIVE', 0, 'OFFICIAL', now(), now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("modelId", modelId));
        }
    }
}
