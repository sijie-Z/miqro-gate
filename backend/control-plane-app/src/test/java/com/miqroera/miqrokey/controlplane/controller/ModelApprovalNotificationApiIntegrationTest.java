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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model-approval webhook notifications (F03): the approval workflow fires
 * event-driven alert rules (MODEL_APPROVAL_SUBMITTED / _APPROVED / _REJECTED)
 * the moment a transition happens — signed delivery, payload metadata and the
 * no-endpoint/disabled-record paths, all through the shared alert machinery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Model approval notification API integration tests (PostgreSQL)")
class ModelApprovalNotificationApiIntegrationTest {

    static final String TAG = "core-ai";
    static final String MODEL_A = "model-alpha";
    static final String MODEL_NEW = "model-beta";
    static final String MODEL_AUTO = "model-auto";

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.gateway-base-url", () -> "https://gateway.test.internal");
        // The mock receiver runs on loopback (webhook delivery re-validates the URL).
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
        // Slow the scheduled evaluator so nothing races the event-driven fires.
        registry.add("miqrokey.alerts.evaluation-interval-ms", () -> "3600000");
        // MODEL_AUTO skips the review queue (submit fires SUBMITTED + APPROVED).
        registry.add("miqrokey.approval.whitelist-models", () -> MODEL_AUTO);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private String bootUsername;
    private HttpServer mockReceiver;
    private String mockBaseUrl;
    private final AtomicInteger received = new AtomicInteger();
    private final List<String> signatures = new CopyOnWriteArrayList<>();
    private final List<Map<?, ?>> bodies = new CopyOnWriteArrayList<>();
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        mockReceiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        mockReceiver.createContext("/hook", this::handleHook);
        mockReceiver.start();
        mockBaseUrl = "http://127.0.0.1:" + mockReceiver.getAddress().getPort() + "/hook";

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                bootUsername = "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        if (mockReceiver != null) {
            mockReceiver.stop(0);
        }
        fx.reset();
    }

    private void handleHook(HttpExchange exchange) throws java.io.IOException {
        received.incrementAndGet();
        String signature = exchange.getRequestHeaders().getFirst("X-MiQroKey-Signature");
        if (signature != null) {
            signatures.add(signature);
        }
        String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            bodies.add(objectMapper.readValue(payload, Map.class));
        } catch (Exception ignored) {
            bodies.add(Map.of("raw", payload));
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    // ------------------------------------------------------------------
    // event-driven notifications
    // ------------------------------------------------------------------

    @Test
    @DisplayName("submitting an approval fires a signed MODEL_APPROVAL_SUBMITTED notification")
    void submittedFiresNotification() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        String endpointId = createEndpoint("approval-hook");
        String ruleId = createRule("on-submit", "MODEL_APPROVAL_SUBMITTED", endpointId);

        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW, "reason", "need it for agents"))
                .andExpect(status().isCreated()).andReturn();
        String approvalId = (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class)
                .get("id");

        assertThat(received.get()).isEqualTo(1);
        assertThat(signatures).hasSize(1);
        assertThat(signatures.get(0)).startsWith("sha256=");
        Map<?, ?> body = bodies.get(0);
        assertThat((String) body.get("type")).isEqualTo("MODEL_APPROVAL_SUBMITTED");
        assertThat((String) body.get("approvalId")).isEqualTo(approvalId);
        assertThat((String) body.get("modelId")).isEqualTo(MODEL_NEW);
        assertThat((String) body.get("status")).isEqualTo("PENDING");
        assertThat((String) body.get("username")).isEqualTo(bootUsername);
        assertThat((String) body.get("keyName")).isEqualTo("claude-code-main");
        assertThat((String) body.get("reason")).isEqualTo("need it for agents");

        // Exactly one event row for the one rule, value 1, payload stored for retries.
        List<Map<String, Object>> events = jdbc.query(
                "SELECT value, payload_json FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(ruleId)), (rs, i) -> Map.<String, Object>of("value",
                        rs.getObject("value"), "payload", rs.getString("payload_json")));
        assertThat(events).hasSize(1);
        assertThat((java.math.BigDecimal) events.get(0).get("value")).isEqualByComparingTo("1");
        assertThat((String) events.get(0).get("payload")).contains("\"approvalId\"");
    }

    @Test
    @DisplayName("approve and reject each fire their own notification with the review note")
    void reviewFiresResultNotifications() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        String endpointId = createEndpoint("approval-hook");
        createRule("on-approved", "MODEL_APPROVAL_APPROVED", endpointId);
        createRule("on-rejected", "MODEL_APPROVAL_REJECTED", endpointId);

        UUID keyId = fx.createKeyViaAdmin();
        UUID first = submitRequest(keyId, MODEL_NEW);

        bodies.clear();
        received.set(0);
        postJson("/api/v1/admin/model-approvals/" + first + "/approve", Map.of("reviewNote", "granted"))
                .andExpect(status().isOk());
        assertThat(received.get()).isEqualTo(1);
        Map<?, ?> approved = bodies.get(0);
        assertThat((String) approved.get("type")).isEqualTo("MODEL_APPROVAL_APPROVED");
        assertThat((String) approved.get("status")).isEqualTo("APPROVED");
        assertThat((String) approved.get("approvalId")).isEqualTo(first.toString());
        assertThat((String) approved.get("reviewNote")).isEqualTo("granted");

        UUID second = submitRequest(keyId, "model-gamma");
        bodies.clear();
        received.set(0);
        postJson("/api/v1/admin/model-approvals/" + second + "/reject", Map.of("reviewNote", "超预算，驳回"))
                .andExpect(status().isOk());
        assertThat(received.get()).isEqualTo(1);
        Map<?, ?> rejected = bodies.get(0);
        assertThat((String) rejected.get("type")).isEqualTo("MODEL_APPROVAL_REJECTED");
        assertThat((String) rejected.get("status")).isEqualTo("REJECTED");
        assertThat((String) rejected.get("approvalId")).isEqualTo(second.toString());
        assertThat((String) rejected.get("reviewNote")).isEqualTo("超预算，驳回");
    }

    @Test
    @DisplayName("disabled rules are silent; rules without endpoints record the event only")
    void disabledAndEndpointlessRules() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        String endpointId = createEndpoint("approval-hook");
        String ruleId = createRule("on-submit", "MODEL_APPROVAL_SUBMITTED", endpointId);
        String noEndpointRule = createRule("record-only", "MODEL_APPROVAL_SUBMITTED", null);

        // Disable the delivered rule: only the record-only rule stays active.
        mockMvc.perform(patch("/api/v1/admin/alert-rules/" + ruleId).cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());

        UUID keyId = fx.createKeyViaAdmin();
        submitRequest(keyId, MODEL_NEW);

        assertThat(received.get()).isZero();
        List<UUID> eventRuleIds = jdbc.query("SELECT rule_id FROM alert_events", new MapSqlParameterSource(),
                (rs, i) -> (UUID) rs.getObject(1));
        assertThat(eventRuleIds).containsExactly(UUID.fromString(noEndpointRule));
    }

    @Test
    @DisplayName("whitelist auto-approval notifies both transitions in the one submission")
    void autoApprovalFiresBothTransitions() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        String endpointId = createEndpoint("approval-hook");
        createRule("on-submit", "MODEL_APPROVAL_SUBMITTED", endpointId);
        createRule("on-approved", "MODEL_APPROVAL_APPROVED", endpointId);
        UUID keyId = fx.createKeyViaAdmin();

        postJson("/api/v1/me/model-approvals", Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_AUTO))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(received.get()).isEqualTo(2);
        Map<?, ?> submitted = bodies.get(0);
        Map<?, ?> autoApproved = bodies.get(1);
        assertThat((String) submitted.get("type")).isEqualTo("MODEL_APPROVAL_SUBMITTED");
        assertThat((String) autoApproved.get("type")).isEqualTo("MODEL_APPROVAL_APPROVED");
        assertThat((Boolean) autoApproved.get("autoApproved")).isTrue();
        assertThat((String) autoApproved.get("status")).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID submitRequest(UUID keyId, String modelId) throws Exception {
        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", modelId)).andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, Map<?, ?> body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).content(objectMapper.writeValueAsString(body)));
    }

    private String createEndpoint(String name) throws Exception {
        MvcResult r = mockMvc
                .perform(post("/api/v1/admin/webhooks").cookie(adminSession, adminCsrf)
                        .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", name, "url", mockBaseUrl, "secret", "whsec-" + name))))
                .andExpect(status().isOk()).andReturn();
        return (String) objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private String createRule(String name, String type, String endpointId) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("threshold", 1);
        body.put("webhookEndpointId", endpointId);
        MvcResult r = mockMvc.perform(
                post("/api/v1/admin/alert-rules").cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return (String) objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    /** Direct JDBC fixtures: provider catalog + project/grant chain + admin key. */
    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID seedUserId = UUID.randomUUID();
        UUID keyId;

        void reset() {
            for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                    "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                    "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships", "projects",
                    "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Child-first order above covers the canonical FK set.
                }
            }
        }

        void insertProviderCatalog() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("providerId", providerId).addValue("productId", productId);
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:providerId, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, p);
        }

        void insertProjectWithGrant() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("tenantId", tenantId).addValue("projectId", projectId).addValue("subscriptionId", subscriptionId)
                    .addValue("credentialId", credentialId).addValue("grantId", grantId)
                    .addValue("productId", productId).addValue("seedUserId", seedUserId);
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Project One', 'ACTIVE', 'core-ai', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:subscriptionId, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:credentialId, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE', :seedUserId, 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, 'model-alpha')
                    """, p);
        }

        /** A Virtual Key owned by the bootstrap admin, bound to the fixture project. */
        UUID createKeyViaAdmin() throws Exception {
            MvcResult r = mockMvc.perform(post("/api/v1/me/virtual-keys").cookie(adminSession, adminCsrf)
                    .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", "claude-code-main", "projectId",
                            projectId.toString(), "providerProductId", productId.toString(), "credentialGrantId",
                            grantId.toString(), "purpose", "CLAUDE_CODE", "allowedModels", List.of("model-alpha")))))
                    .andExpect(status().isCreated()).andReturn();
            keyId = UUID.fromString(
                    (String) objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("id"));
            return keyId;
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
