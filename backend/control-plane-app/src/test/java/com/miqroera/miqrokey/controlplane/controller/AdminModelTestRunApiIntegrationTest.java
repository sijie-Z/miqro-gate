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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model test-run (#552, console "在线调试"): admin-triggered one-shot chat call
 * against a loopback upstream with a real encrypted credential — success
 * returns reply/latency/usage, failures are sanitized 502s, and the audit chain
 * carries metadata only (never the prompt or the reply).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Model test-run integration tests (PostgreSQL)")
class AdminModelTestRunApiIntegrationTest {

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
    private static final String OK_BODY = "{\"id\":\"chatcmpl-it\",\"choices\":"
            + "[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"联调OK\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}";

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
    private volatile int upstreamStatus = 200;
    private volatile String upstreamBody = OK_BODY;
    private final AtomicReference<CapturedRequest> lastUpstream = new AtomicReference<>();

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID productId;
    private String originalBaseUrlTemplates;
    private UUID credentialId;

    record CapturedRequest(String method, String path, String authorization, String body) {
    }

    @BeforeEach
    void setUp() throws Exception {
        clean();
        lastUpstream.set(null);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastUpstream.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            byte[] out = upstreamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        upstream.start();
        int upstreamPort = upstream.getAddress().getPort();

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), "testrun", "Admin"))))
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
        jdbc.update("UPDATE provider_products SET base_url_templates = :templates::jsonb WHERE id = :id",
                new MapSqlParameterSource("templates", "[{\"url\":\"http://127.0.0.1:" + upstreamPort + "/v1\"}]")
                        .addValue("id", productId));

        // Subscription + ACTIVE credential carrying a real encrypted secret.
        UUID subscriptionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upstream_subscriptions (id, tenant_id, provider_product_id, name, billing_mode, status)
                VALUES (:id, :tenantId, :productId, 'testrun-it', 'PAYG', 'ACTIVE')
                """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", TENANT_ID)
                .addValue("productId", productId));
        credentialId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        EncryptedSecret encrypted = keyEncryptionProvider.encrypt("sk-testrun-secret".getBytes(StandardCharsets.UTF_8),
                TENANT_ID, credentialId);
        jdbc.update("""
                INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status)
                VALUES (:id, :tenantId, :subscriptionId, 'testrun-key', 'ACTIVE')
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
        jdbc.update("UPDATE upstream_credentials SET active_version_id = :versionId WHERE id = :id",
                new MapSqlParameterSource("versionId", versionId).addValue("id", credentialId));
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
        clean();
        if (productId != null && originalBaseUrlTemplates != null) {
            jdbc.update("UPDATE provider_products SET base_url_templates = :templates::jsonb WHERE id = :id",
                    new MapSqlParameterSource("templates", originalBaseUrlTemplates).addValue("id", productId));
        }
    }

    @Test
    @DisplayName("test-run requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/admin/models/test-run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerProductId\":\"" + productId + "\",\"modelId\":\"m\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("successful test-run returns reply, latency and usage; audit carries metadata only")
    void successfulRun() throws Exception {
        testRun("联调测试：请回复").andExpect(status().isOk()).andExpect(jsonPath("$.productCode").isNotEmpty())
                .andExpect(jsonPath("$.modelId").value("deepseek-chat")).andExpect(jsonPath("$.httpStatus").value(200))
                .andExpect(jsonPath("$.latencyMs").isNumber()).andExpect(jsonPath("$.content").value("联调OK"))
                .andExpect(jsonPath("$.promptTokens").value(7)).andExpect(jsonPath("$.completionTokens").value(3))
                .andExpect(jsonPath("$.totalTokens").value(10));

        CapturedRequest captured = lastUpstream.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.path()).isEqualTo("/v1/chat/completions");
        assertThat(captured.authorization()).isEqualTo("Bearer sk-testrun-secret");
        assertThat(captured.body()).contains("\"model\":\"deepseek-chat\"").contains("联调测试：请回复");

        List<String> summaries = jdbc.queryForList(
                "SELECT change_summary::text FROM admin_audit_events WHERE action = 'MODEL_TEST_RUN'",
                new MapSqlParameterSource(), String.class);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0)).contains("deepseek-chat").contains("SUCCEEDED").contains("10")
                .doesNotContain("联调测试"); // 正文永不入审计
    }

    @Test
    @DisplayName("missing prompt defaults to the smoke message")
    void defaultPrompt() throws Exception {
        testRun(null).andExpect(status().isOk());
        assertThat(lastUpstream.get().body()).contains("请回复OK");
    }

    @Test
    @DisplayName("upstream failure is a sanitized 502 and audited as HTTP_<code>")
    void upstreamFailureSanitized() throws Exception {
        upstreamStatus = 401;
        upstreamBody = "{\"error\":{\"message\":\"Authentication Fails, Your api key: ****test is invalid\"}}";
        String problem = testRun("探活").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_TEST_RUN_FAILED")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(problem).contains("HTTP 401").contains("Authentication Fails").doesNotContain("sk-testrun-secret");

        List<String> summaries = jdbc.queryForList(
                "SELECT change_summary::text FROM admin_audit_events WHERE action = 'MODEL_TEST_RUN'",
                new MapSqlParameterSource(), String.class);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0)).contains("HTTP_401").doesNotContain("探活");
    }

    @Test
    @DisplayName("a product without an ACTIVE credential cannot be test-run")
    void missingCredentialFails() throws Exception {
        jdbc.update("DELETE FROM upstream_credential_versions WHERE credential_id = :id",
                new MapSqlParameterSource("id", credentialId));
        jdbc.update("DELETE FROM upstream_credentials WHERE id = :id", new MapSqlParameterSource("id", credentialId));

        testRun("x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_TEST_RUN_CREDENTIAL_UNAVAILABLE"));
    }

    @Test
    @DisplayName("unknown product is a 404")
    void unknownProductIs404() throws Exception {
        mockMvc.perform(post("/api/v1/admin/models/test-run").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerProductId\":\"" + UUID.randomUUID() + "\",\"modelId\":\"m\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODEL_TEST_RUN_PRODUCT_NOT_FOUND"));
    }

    // ------------------------------------------------------------- helpers

    private ResultActions testRun(String prompt) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("providerProductId", productId.toString());
        body.put("modelId", "deepseek-chat");
        if (prompt != null) {
            body.put("prompt", prompt);
        }
        return mockMvc.perform(post("/api/v1/admin/models/test-run").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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
