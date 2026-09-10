package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.spi.AdapterRegistry;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model probe (#346, I4, raw doc 05): admin-triggered official /models fetch
 * against a loopback upstream with a real encrypted credential — success lands
 * the catalog and the probe state, failure is a sanitized 502 with the outcome
 * persisted next to the unchanged last-successful catalog.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Model catalog probe integration tests (PostgreSQL)")
class ModelCatalogProbeApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    KeyEncryptionProvider keyEncryptionProvider;
    @Autowired
    AdapterRegistry adapterRegistry;

    private HttpServer upstream;
    private int upstreamPort;
    private volatile int upstreamStatus = 200;
    private volatile String upstreamBody = "{\"data\":[{\"id\":\"deepseek-chat\",\"display_name\":\"DeepSeek Chat\"},"
            + "{\"id\":\"deepseek-reasoner\"}]}";

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID productId;
    /**
     * Original base URL templates, restored on teardown (seeded state is shared).
     */
    private String originalBaseUrlTemplates;
    private UUID subscriptionId;
    private UUID credentialId;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] out = upstreamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper
                                .writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(), "prober", "Admin"))))
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

        // A seeded product whose code resolves to a registered adapter.
        List<Map<String, Object>> products = jdbc
                .queryForList("SELECT id, product_code, base_url_templates::text AS templates FROM provider_products"
                        + " ORDER BY product_code", new MapSqlParameterSource());
        for (Map<String, Object> candidate : products) {
            String code = (String) candidate.get("product_code");
            if (adapterRegistry.findById(code).isPresent()) {
                productId = (UUID) candidate.get("id");
                originalBaseUrlTemplates = (String) candidate.get("templates");
                break;
            }
        }
        assertThat(productId).as("a seeded product with a registered adapter").isNotNull();

        // Point the product at the loopback stub and clear any previous probe state.
        jdbc.update("""
                UPDATE provider_products
                SET base_url_templates = :templates::jsonb, model_catalog_probe_status = NULL,
                    model_catalog_probe_error = NULL, model_catalog_model_count = NULL,
                    model_catalog_probed_at = NULL
                WHERE id = :id
                """, new MapSqlParameterSource("templates", "[{\"url\":\"http://127.0.0.1:" + upstreamPort + "/v1\"}]")
                .addValue("id", productId));

        // Subscription + ACTIVE credential carrying a real encrypted secret.
        subscriptionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upstream_subscriptions (id, tenant_id, provider_product_id, name, billing_mode, status)
                VALUES (:id, :tenantId, :productId, 'probe-it', 'PAYG', 'ACTIVE')
                """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", TENANT_ID)
                .addValue("productId", productId));
        credentialId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        EncryptedSecret encrypted = keyEncryptionProvider.encrypt("sk-probe-test".getBytes(StandardCharsets.UTF_8),
                TENANT_ID, credentialId);
        jdbc.update("""
                INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status)
                VALUES (:id, :tenantId, :subscriptionId, 'probe-key', 'ACTIVE')
                """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", TENANT_ID)
                .addValue("subscriptionId", subscriptionId));
        jdbc.update("""
                INSERT INTO upstream_credential_versions (id, tenant_id, credential_id, encrypted_secret, nonce,
                    encryption_key_version, secret_fingerprint, status, valid_from)
                VALUES (:id, :tenantId, :credentialId, :ciphertext, :nonce, :keyVersion, :fingerprint, 'ACTIVE', now())
                """,
                new MapSqlParameterSource("id", versionId).addValue("tenantId", TENANT_ID)
                        .addValue("credentialId", credentialId).addValue("ciphertext", encrypted.ciphertext())
                        .addValue("nonce", encrypted.nonce()).addValue("keyVersion", encrypted.keyVersion())
                        .addValue("fingerprint", new byte[]{1}));
        // The active-version pointer has its own FK: set it after the version exists.
        jdbc.update("UPDATE upstream_credentials SET active_version_id = :versionId WHERE id = :id",
                new MapSqlParameterSource("versionId", versionId).addValue("id", credentialId));
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
        clean();
        // The product row is seeded shared state: restore the real base URL and
        // clear probe columns so other IT classes see the original configuration.
        if (productId != null && originalBaseUrlTemplates != null) {
            jdbc.update("""
                    UPDATE provider_products
                    SET base_url_templates = :templates::jsonb, model_catalog_probe_status = NULL,
                        model_catalog_probe_error = NULL, model_catalog_model_count = NULL,
                        model_catalog_probed_at = NULL
                    WHERE id = :id
                    """, new MapSqlParameterSource("templates", originalBaseUrlTemplates).addValue("id", productId));
        }
    }

    @Test
    @DisplayName("probe requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/admin/models/probe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerProductId\":\"" + productId + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("successful probe lands the catalog, the probe state and the audit")
    void successfulProbe() throws Exception {
        probe().andExpect(status().isOk()).andExpect(jsonPath("$.productCode").isNotEmpty())
                .andExpect(jsonPath("$.modelCount").value(2)).andExpect(jsonPath("$.models", hasSize(2)))
                .andExpect(jsonPath("$.models[0].modelId").value("deepseek-chat"))
                .andExpect(jsonPath("$.models[0].displayName").value("DeepSeek Chat"));

        // Catalog rows (OFFICIAL) and the probe status view.
        mockMvc.perform(
                get("/api/v1/admin/models").param("providerProductId", productId.toString()).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.modelId=='deepseek-reasoner')].source", contains("OFFICIAL")));
        mockMvc.perform(get("/api/v1/admin/models/probe-status").param("providerProductId", productId.toString())
                .cookie(sessionCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.modelCount").value(2)).andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.probedAt").isNotEmpty());
        assertThat(auditCount("MODEL_CATALOG_PROBE_SUCCEEDED")).isEqualTo(1);
    }

    @Test
    @DisplayName("failed probe is a sanitized 502; the last-successful catalog is retained")
    void failedProbeKeepsCatalog() throws Exception {
        probe().andExpect(status().isOk());

        upstreamStatus = 500;
        upstreamBody = "boom";
        String problem = probe().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_PROBE_FAILED")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(problem).contains("500").doesNotContain("127.0.0.1").doesNotContain("boom");

        // Failure is visible; the catalog rows are untouched.
        mockMvc.perform(get("/api/v1/admin/models/probe-status").param("providerProductId", productId.toString())
                .cookie(sessionCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").isNotEmpty()).andExpect(jsonPath("$.modelCount").isEmpty());
        mockMvc.perform(
                get("/api/v1/admin/models").param("providerProductId", productId.toString()).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        assertThat(auditCount("MODEL_CATALOG_PROBE_FAILED")).isEqualTo(1);
    }

    @Test
    @DisplayName("a product without an ACTIVE credential cannot be probed")
    void missingCredentialFails() throws Exception {
        jdbc.update("DELETE FROM upstream_credential_versions WHERE credential_id = :id",
                new MapSqlParameterSource("id", credentialId));
        jdbc.update("DELETE FROM upstream_credentials WHERE id = :id", new MapSqlParameterSource("id", credentialId));

        probe().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_PROBE_CREDENTIAL_UNAVAILABLE"));
    }

    @Test
    @DisplayName("unknown product is a 404")
    void unknownProductIs404() throws Exception {
        mockMvc.perform(post("/api/v1/admin/models/probe").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerProductId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODEL_PROBE_PRODUCT_NOT_FOUND"));
    }

    // ------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions probe() throws Exception {
        return mockMvc.perform(post("/api/v1/admin/models/probe").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerProductId\":\"" + productId + "\"}"));
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
    }

    private void clean() {
        for (String table : new String[]{"upstream_credential_versions", "upstream_credentials",
                "upstream_subscriptions", "model_catalog", "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    private static Cookie cookie(MvcResult result, String name) {
        if (result.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie candidate : result.getResponse().getCookies()) {
            if (name.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }
}
