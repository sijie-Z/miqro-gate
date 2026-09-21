package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An alert rule's {@code webhookEndpointId} must point at an endpoint of the
 * rule's own tenant (#1335).
 *
 * <p>
 * The column's foreign key used to be single-column, so a rule could reference
 * another tenant's endpoint. Both sides of that state were invisible: the
 * delete guard filtered on {@code tenant_id} and therefore could not see the
 * foreign referrer (deleting the endpoint nulled the rule's delivery target
 * behind its owner's back), and the dispatcher — which looks the endpoint up
 * under the rule's own tenant — answered 404 on every delivery. Schema-level
 * fixes live in {@code V74}; the write path rejects the reference up front with
 * a readable 4xx, and the delete guard blocks on <em>any</em> referrer while
 * still naming only same-tenant rules.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Alert-rule → webhook-endpoint references are tenant-scoped (#1335)")
class AlertRuleEndpointTenantScopeTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    /** A second tenant, so "another tenant's endpoint" is a real row. */
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000b075");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // The endpoint URL has to clear the SSRF gate; loopback is allowlisted for
        // the test only.
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
        registry.add("miqrokey.alerts.evaluation-interval-ms", () -> "3600000");
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
    private HttpServer mockReceiver;
    private String hookUrl;

    @BeforeEach
    void setUp() throws Exception {
        resetData();
        jdbc.update("INSERT INTO tenants (id, code, name) VALUES (:id, 'ph75-it-b', 'PH75 IT tenant B')",
                new MapSqlParameterSource("id", TENANT_B));

        mockReceiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        mockReceiver.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        mockReceiver.start();
        hookUrl = "http://127.0.0.1:" + mockReceiver.getAddress().getPort() + "/hook";

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        if (mockReceiver != null) {
            mockReceiver.stop(0);
        }
        resetData();
        try {
            jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", TENANT_B));
        } catch (Exception ignored) {
            // a leftover reference would fail the FK; resetData above cleared them
        }
    }

    private void resetData() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "export_tasks", "usage_deletions", "usage_event", "cache_hit_event", "price_snapshot",
                "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                "project_memberships", "project_repositories", "projects", "provider_products", "providers",
                "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // child-first ordering for the canonical migration set
            }
        }
    }

    // ------------------------------------------------------------------ red

    @Test
    @DisplayName("a rule cannot be created pointing at another tenant's endpoint")
    void crossTenantEndpointIsRejectedOnCreate() throws Exception {
        UUID foreign = insertForeignEndpoint();

        // Before the fix this answered 200 and stored the reference: the rule then
        // 404s on every delivery while pinning the other tenant's delete guard.
        createRule("cross-tenant", foreign.toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_INVALID"));

        assertThat(countRulesWithEndpoint(foreign)).isZero();
    }

    @Test
    @DisplayName("a rule cannot be moved onto another tenant's endpoint")
    void crossTenantEndpointIsRejectedOnUpdate() throws Exception {
        UUID foreign = insertForeignEndpoint();
        UUID own = createEndpoint("own-" + shortId());
        MvcResult created = createRule("movable", own.toString()).andExpect(status().isOk()).andReturn();
        String ruleId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(patch("/api/v1/admin/alert-rules/" + ruleId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("webhookEndpointId", foreign.toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_INVALID"));

        assertThat(ruleEndpoint(ruleId)).isEqualTo(own);
    }

    @Test
    @DisplayName("the database rejects a cross-tenant reference even without the service check (V74 backstop)")
    void databaseBackstopRejectsCrossTenantReference() {
        // Straight SQL, i.e. any write path that bypasses AlertRuleService.
        assertForeignKeyStillEnforced();
    }

    @Test
    @DisplayName("an endpoint of another tenant's rule blocks the delete; no foreign rule name leaks")
    void foreignReferrerBlocksTheDelete() throws Exception {
        UUID endpoint = createEndpoint("shared-" + shortId());
        insertForeignRuleIgnoringFk("外租户规则名-不应外泄", endpoint);
        // The session-scoped switch must not have leaked: other tests rely on the
        // composite FK being live.
        assertForeignKeyStillEnforced();

        // Before the fix the guard's tenant filter hid that rule: the delete
        // answered 200 and ON DELETE SET NULL detached the rule silently.
        mockMvc.perform(delete("/api/v1/admin/webhooks/" + endpoint).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.detail", containsString("1 条告警规则")))
                // The count is reported; the foreign rule itself is not named.
                .andExpect(jsonPath("$.dependencies", hasSize(0)));

        assertThat(ruleEndpointOfForeignRule(endpoint)).isEqualTo(endpoint);
        assertThat(endpointRowCount(endpoint)).isOne();
    }

    // -------------------------------------------------------------- green

    @Test
    @DisplayName("a same-tenant reference still works and still blocks the delete, with the rule named")
    void ownTenantReferenceIsUnaffected() throws Exception {
        UUID endpoint = createEndpoint("own-" + shortId());
        MvcResult created = createRule("own-tenant", endpoint.toString()).andExpect(status().isOk()).andReturn();
        String ruleId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(delete("/api/v1/admin/webhooks/" + endpoint).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.dependencies", hasSize(1)))
                .andExpect(jsonPath("$.dependencies[0].id").value(ruleId));

        // Releasing the reference releases the endpoint (no over-blocking).
        mockMvc.perform(delete("/api/v1/admin/alert-rules/" + ruleId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/webhooks/" + endpoint).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a missing or foreign endpoint id is a 400, not a raw foreign-key failure")
    void unknownEndpointIsRejectedOnCreate() throws Exception {
        createRule("unknown-endpoint", UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_INVALID"));
    }

    // ------------------------------------------------------------ helpers

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions createRule(String name, String endpointId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name + "-" + shortId());
        body.put("type", "USAGE_MISSING_RATE");
        body.put("threshold", 0.5);
        body.put("dedupeMinutes", 60);
        body.put("webhookEndpointId", endpointId);
        return mockMvc.perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UUID createEndpoint(String name) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", name, "url", hookUrl, "secret", "whsec-tenant-scope-test"))))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(
                objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString());
    }

    /**
     * An endpoint of {@link #TENANT_B}, written directly — the console session
     * belongs to the default tenant and cannot create one for another tenant.
     */
    private UUID insertForeignEndpoint() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webhook_endpoints (id, tenant_id, name, url, secret_encrypted, secret_nonce,
                                               secret_key_version, enabled, timeout_ms, version)
                VALUES (:id, :tenantId, 'foreign-endpoint', 'https://example.invalid/hook',
                        '\\x00'::bytea, '\\x00'::bytea, 'v1', TRUE, 5000, 0)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", TENANT_B));
        return id;
    }

    /**
     * A rule of {@link #TENANT_B} pointing at {@code endpointId}.
     *
     * <p>
     * V74 makes the state unrepresentable, so the one way left to build it is to
     * switch foreign-key enforcement off — the way it can still reach production
     * too: a dump taken before V74, restored without the migration's repair step.
     * The switch is {@code session_replication_role}, which is session-scoped on
     * purpose: {@code DISABLE TRIGGER} would turn the check off for every later
     * test in this JVM that shares the container, silently. The same connection
     * runs the INSERT and restores the role in {@code finally}.
     * </p>
     */
    private void insertForeignRuleIgnoringFk(String name, UUID endpointId) {
        // A connection of its own, never returned to the pool: the role is set for
        // this session only and cannot follow the insert into another test.
        try (Connection connection = DriverManager.getConnection(
                AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl(),
                AbstractControlPlaneIntegrationTest.POSTGRES.getUsername(),
                AbstractControlPlaneIntegrationTest.POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO alert_rules (id, tenant_id, name, type, threshold, webhook_endpoint_id) "
                            + "VALUES (?, ?, ?, 'USAGE_SURGE', 1.0, ?)")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, TENANT_B);
                insert.setString(3, name);
                insert.setObject(4, endpointId);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not build the pre-V74 state", e);
        }
    }

    /** Precondition guard: this connection still enforces the composite FK. */
    private void assertForeignKeyStillEnforced() {
        UUID foreign = insertForeignEndpoint();
        UUID ruleId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO alert_rules (id, tenant_id, name, type, threshold, webhook_endpoint_id)
                VALUES (:id, '00000000-0000-0000-0000-000000000001', 'control-insert', 'USAGE_SURGE', 1.0, :endpoint)
                """, new MapSqlParameterSource("id", ruleId).addValue("endpoint", foreign)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_alert_rules_webhook_endpoint");
    }

    private long countRulesWithEndpoint(UUID endpointId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_rules WHERE webhook_endpoint_id = :id",
                new MapSqlParameterSource("id", endpointId), Long.class);
        return count == null ? 0 : count;
    }

    private UUID ruleEndpoint(String ruleId) {
        return jdbc.queryForObject("SELECT webhook_endpoint_id FROM alert_rules WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(ruleId)), UUID.class);
    }

    private UUID ruleEndpointOfForeignRule(UUID endpointId) {
        return jdbc.queryForObject("SELECT webhook_endpoint_id FROM alert_rules WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", TENANT_B), UUID.class);
    }

    private long endpointRowCount(UUID endpointId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_endpoints WHERE id = :id",
                new MapSqlParameterSource("id", endpointId), Long.class);
        return count == null ? 0 : count;
    }

    private static Cookie cookie(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
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
