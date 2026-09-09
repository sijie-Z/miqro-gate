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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API consumer capability scope (issue #316): the billing channel fails closed
 * without {@code billing:read} (403 CONSUMER_SCOPE_DENIED), PATCH scope
 * validation/round-trip semantics match the F60 admin-key precedent, every
 * change is audited (CONSUMER_SCOPE_UPDATE with from/to), and the scope restore
 * to null (= full) re-enables the channel. The MCP data-plane gate is covered
 * by the gateway contract test (McpProxyContractTest, consumer_scope_denied).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Consumer capability scope integration tests (PostgreSQL)")
class ConsumerScopeIntegrationTest {

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
    private UUID adminUserId;
    private final String adminUsername = "scope_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("admin_audit_events", "api_consumers", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), adminUsername, "Admin"))))
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
        adminUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", adminUsername), UUID.class);
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("admin_audit_events", "api_consumers", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
    }

    private String createConsumer() throws Exception {
        MvcResult created = mockMvc.perform(
                post("/api/v1/admin/api-consumers").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"scope-consumer\"}"))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> consumerBody = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        return consumerBody.get("apiKey").toString();
    }

    private String patchScope(String consumerId, String json) throws Exception {
        return mockMvc
                .perform(patch("/api/v1/admin/api-consumers/" + consumerId + "/scope").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getContentAsString();
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

    @Test
    @DisplayName("billing:read gate: scoped-out consumer is denied, restore re-enables")
    void billingScopeGate() throws Exception {
        String apiKey = createConsumer();
        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey)).andExpect(status().isOk());

        // Narrow to the MCP channel only → the billing channel must fail closed.
        String idByName = jdbc.queryForObject("SELECT id FROM api_consumers WHERE name = :name",
                new MapSqlParameterSource("name", "scope-consumer"), UUID.class).toString();
        String view = patchScope(idByName, "{\"capabilities\":[\"mcp:call\"]}");
        assertThat(objectMapper.readTree(view).get("capabilities").toString()).isEqualTo("[\"mcp:call\"]");
        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSUMER_SCOPE_DENIED"));

        // Restore null = full access → billing works again.
        patchScope(idByName, "{\"capabilities\":null}");
        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey)).andExpect(status().isOk());

        // Every scope change is audited with actor + from/to.
        Long updates = jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", "CONSUMER_SCOPE_UPDATE"), Long.class);
        assertThat(updates).isEqualTo(2);
        String summary = jdbc.queryForObject("""
                SELECT change_summary::text FROM admin_audit_events
                WHERE action = 'CONSUMER_SCOPE_UPDATE' ORDER BY chain_position DESC LIMIT 1
                """, new MapSqlParameterSource(), String.class);
        // jsonb reorders keys and normalizes spacing — assert on values.
        assertThat(summary).contains("\"from\": [\"mcp:call\"]").contains("\"to\": null");
    }

    @Test
    @DisplayName("scope validation: unknown codes rejected, empty list means no channels")
    void scopeValidation() throws Exception {
        String apiKey = createConsumer();
        String consumerId = jdbc.queryForObject("SELECT id FROM api_consumers WHERE name = :name",
                new MapSqlParameterSource("name", "scope-consumer"), UUID.class).toString();

        mockMvc.perform(patch("/api/v1/admin/api-consumers/" + consumerId + "/scope").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"capabilities\":[\"sudo:all\"]}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSUMER_SCOPE_INVALID"));

        // Empty list = no channels: billing denied, MCP gate would deny too.
        patchScope(consumerId, "{\"capabilities\":[]}");
        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSUMER_SCOPE_DENIED"));
        assertThat(objectMapper.readTree(patchScope(consumerId, "{\"capabilities\":[\"billing:read\",\"mcp:call\"]}"))
                .get("capabilities").toString()).isEqualTo("[\"billing:read\",\"mcp:call\"]");
        mockMvc.perform(get("/api/v1/billing/summary").header("X-API-Key", apiKey)).andExpect(status().isOk());
    }
}
