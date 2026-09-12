package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.service.AlertEvaluator;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook alerting against real PostgreSQL (G4.5): endpoint creation with
 * SSRF-validated URL and encrypted signing secret, signed test delivery, rule
 * evaluation firing a deduplicated event, and delivery attempts recorded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Webhook alert API integration tests (PostgreSQL)")
class WebhookAlertApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // The mock receiver runs on loopback.
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
        // Slow the scheduled evaluator so tests drive evaluation explicitly.
        registry.add("miqrokey.alerts.evaluation-interval-ms", () -> "3600000");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    AlertEvaluator alertEvaluator;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private HttpServer mockReceiver;
    private String mockBaseUrl;
    private final AtomicInteger received = new AtomicInteger();
    private final List<String> signatures = new CopyOnWriteArrayList<>();
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
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    @DisplayName("endpoint creation rejects private URLs and signs test deliveries")
    void endpointLifecycleAndTest() throws Exception {
        // Private URLs are rejected by the SSRF gate.
        mockMvc.perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        Map.of("name", "private", "url", "http://169.254.169.254/meta", "secret", "s3cret-value"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEBHOOK_URL_REJECTED"));

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "main", "url", mockBaseUrl, "secret", "whsec-test-value"))))
                .andExpect(status().isOk()).andReturn();
        String endpointId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // The signing secret is never returned.
        mockMvc.perform(get("/api/v1/admin/webhooks/" + endpointId).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.secretEncrypted").doesNotExist());

        // Signed test delivery reaches the receiver.
        mockMvc.perform(post("/api/v1/admin/webhooks/" + endpointId + "/test").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.httpStatus").value(200));
        org.assertj.core.api.Assertions.assertThat(received.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(signatures.get(0)).startsWith("sha256=");
    }

    @Test
    @DisplayName("rule evaluation fires a deduplicated event and delivers it signed")
    void ruleFiresAndDeduplicates() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "main", "url", mockBaseUrl, "secret", "whsec-test-value"))))
                .andExpect(status().isOk()).andReturn();
        String endpointId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        MvcResult ruleResult = mockMvc
                .perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name", "missing-rate", "type",
                                "USAGE_MISSING_RATE", "threshold", 0.5, "webhookEndpointId", endpointId))))
                .andExpect(status().isOk()).andReturn();
        String ruleId = objectMapper.readValue(ruleResult.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // Seed usage: one missing + one complete -> ratio 0.5 >= threshold 0.5.
        fx.insertUsage(true);
        fx.insertUsage(false);

        alertEvaluator.evaluateAll();

        // Event fired and delivered exactly once, signed.
        Long events = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(ruleId)), Long.class);
        org.assertj.core.api.Assertions.assertThat(events).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(received.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(signatures.get(0)).startsWith("sha256=");
        Long attempts = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery_attempts",
                new MapSqlParameterSource(), Long.class);
        org.assertj.core.api.Assertions.assertThat(attempts).isEqualTo(1L);

        // Second evaluation within the same hour bucket deduplicates.
        alertEvaluator.evaluateAll();
        Long eventsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(ruleId)), Long.class);
        org.assertj.core.api.Assertions.assertThat(eventsAfter).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(received.get()).isEqualTo(1);
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

        void reset() {
            for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                    "export_tasks", "usage_deletions", "usage_event", "cache_hit_event", "price_snapshot",
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

        void insertUsage(boolean usageMissing) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, 'req-' || :id, '00000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
                            'model-a', 'UPSTREAM', 10, 5, 15, 42, 200, TRUE, :usageMissing, 'greq', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                    .addValue("usageMissing", usageMissing));
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

    @Test
    @DisplayName("deleting an endpoint referenced by alert rules is refused with the dependency list (I21)")
    void deleteBlockedByRuleReferences() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "guarded", "url", mockBaseUrl, "secret", "whsec-test-value"))))
                .andExpect(status().isOk()).andReturn();
        String endpointId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();
        MvcResult rule = mockMvc
                .perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name", "引用方规则", "type", "USAGE_MISSING_RATE",
                                "threshold", 0.5, "webhookEndpointId", endpointId))))
                .andExpect(status().isOk()).andReturn();
        String ruleId = objectMapper.readValue(rule.getResponse().getContentAsString(), Map.class).get("id").toString();

        // Referenced: the delete is refused with the dependency list; nothing is
        // touched (no silent SET NULL detach).
        mockMvc.perform(delete("/api/v1/admin/webhooks/" + endpointId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.dependencies", hasSize(1)))
                .andExpect(jsonPath("$.dependencies[0].type").value("ALERT_RULE"))
                .andExpect(jsonPath("$.dependencies[0].id").value(ruleId))
                .andExpect(jsonPath("$.dependencies[0].name").value("引用方规则"));
        mockMvc.perform(get("/api/v1/admin/webhooks/" + endpointId).cookie(sessionCookie)).andExpect(status().isOk());

        // Release the reference, then the delete succeeds (no new endpoint needed).
        mockMvc.perform(delete("/api/v1/admin/alert-rules/" + ruleId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/webhooks/" + endpointId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("delete serializes against a concurrent rule insert — no silent SET NULL detach (#403)")
    void deleteWaitsForConcurrentRuleInsert() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "racing", "url", mockBaseUrl, "secret", "whsec-test-value"))))
                .andExpect(status().isOk()).andReturn();
        String endpointId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();
        UUID endpointUuid = UUID.fromString(endpointId);
        UUID ruleId = UUID.randomUUID();

        var dataSource = jdbc.getJdbcTemplate().getDataSource();
        ExecutorService io = Executors.newSingleThreadExecutor();
        try (Connection conn = dataSource.getConnection()) {
            // T2: an uncommitted rule INSERT referencing the endpoint — its FK
            // check holds FOR KEY SHARE on the endpoint row.
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO alert_rules (id, tenant_id, name, type, threshold, webhook_endpoint_id)
                    VALUES (?, ?, '并发规则', 'USAGE_MISSING_RATE', 0.5, ?)
                    """)) {
                ps.setObject(1, ruleId);
                ps.setObject(2, fx.tenantId);
                ps.setObject(3, endpointUuid);
                ps.executeUpdate();
            }
            // T1: the delete must block on the row lock instead of proceeding.
            Future<Integer> deleteStatus = io
                    .submit(() -> mockMvc.perform(delete("/api/v1/admin/webhooks/" + endpointId)
                            .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andReturn()
                            .getResponse().getStatus());
            Thread.sleep(700);
            org.assertj.core.api.Assertions.assertThat(deleteStatus.isDone())
                    .as("delete must wait on the endpoint row lock").isFalse();

            conn.commit();
            // Once the insert is visible, the guard sees the reference and
            // refuses with 409 — and the rule keeps its endpoint (no SET NULL).
            org.assertj.core.api.Assertions.assertThat(deleteStatus.get(15, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            io.shutdownNow();
        }
        Integer stillReferenced = jdbc.queryForObject(
                "SELECT count(*) FROM alert_rules WHERE id = :id AND webhook_endpoint_id = :endpointId",
                new MapSqlParameterSource("id", ruleId).addValue("endpointId", endpointUuid), Integer.class);
        org.assertj.core.api.Assertions.assertThat(stillReferenced).isEqualTo(1);
        mockMvc.perform(get("/api/v1/admin/webhooks/" + endpointId).cookie(sessionCookie)).andExpect(status().isOk());
    }
}
