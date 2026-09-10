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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP upstream backend auth (#320): secret is write-only (never echoed by any
 * read surface), stored AES-GCM encrypted (ciphertext differs from plaintext),
 * VISITOR clears it, validation rejects bad modes/secrets, and every change is
 * audited with the mode only. Gateway-side injection/fail-closed behavior is
 * covered by McpProxyContractTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP backend auth integration tests (PostgreSQL)")
class McpBackendAuthApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final String SECRET = "supersecret-mcp-backend-abc";

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
    private final String adminUsername = "bkauth_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("mcp_tool_revisions", "mcp_tools", "mcp_route_rule", "mcp_services",
                "admin_audit_events", "user_sessions", "users")) {
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
        for (String table : List.of("mcp_tool_revisions", "mcp_tools", "mcp_route_rule", "mcp_services",
                "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
    }

    private String createService() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"secured-mcp\",\"endpoint\":\"https://mcp.internal.example.com/mcp\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private MvcResult putAuth(String serviceId, String json) throws Exception {
        return mockMvc.perform(
                put("/api/v1/admin/mcp-services/" + serviceId + "/backend-auth").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
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
    @DisplayName("secret is write-only, encrypted at rest, audited, and clearable")
    void apiKeyLifecycle() throws Exception {
        String serviceId = createService();

        MvcResult set = putAuth(serviceId, "{\"mode\":\"API_KEY\",\"secret\":\"" + SECRET + "\"}");
        assertThat(set.getResponse().getStatus()).isEqualTo(200);
        String view = set.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(view).contains("\"backendAuthMode\":\"API_KEY\"").doesNotContain(SECRET)
                .contains("backendSecretUpdatedAt");

        // At rest: ciphertext present, and the plaintext bytes appear nowhere in it.
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT backend_secret_ciphertext, backend_secret_key_version, backend_secret_updated_at
                FROM mcp_services WHERE id = :id
                """, new MapSqlParameterSource("id", UUID.fromString(serviceId)));
        byte[] ciphertext = (byte[]) row.get("backend_secret_ciphertext");
        assertThat(ciphertext).isNotNull();
        assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain(SECRET);
        assertThat(row.get("backend_secret_key_version")).isNotNull();
        assertThat(row.get("backend_secret_updated_at")).isNotNull();

        // The read surfaces never echo it either.
        MvcResult list = mockMvc.perform(get("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRET);

        // Rotate, then clear with VISITOR.
        putAuth(serviceId, "{\"mode\":\"API_KEY\",\"secret\":\"rotated-secret-xyz\"}");
        MvcResult cleared = putAuth(serviceId, "{\"mode\":\"VISITOR\"}");
        assertThat(cleared.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"backendAuthMode\":\"VISITOR\"");
        Map<String, Object> clearedRow = jdbc.queryForMap(
                "SELECT backend_secret_ciphertext, backend_secret_updated_at FROM mcp_services WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(serviceId)));
        assertThat(clearedRow.get("backend_secret_ciphertext")).isNull();
        assertThat(clearedRow.get("backend_secret_updated_at")).isNull();

        // Three audited changes, actor = admin, summaries carry the mode only.
        Long events = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_events WHERE action = 'MCP_SERVICE_BACKEND_AUTH'",
                new MapSqlParameterSource(), Long.class);
        assertThat(events).isEqualTo(3);
        Map<String, Object> latest = jdbc.queryForMap("""
                SELECT actor_id, change_summary::text AS summary FROM admin_audit_events
                WHERE action = 'MCP_SERVICE_BACKEND_AUTH' ORDER BY chain_position DESC LIMIT 1
                """, new MapSqlParameterSource());
        assertThat(latest.get("actor_id")).isEqualTo(adminUserId);
        assertThat((String) latest.get("summary")).contains("VISITOR").doesNotContain(SECRET);
    }

    @Test
    @DisplayName("validation: bad mode / missing / oversized secret are rejected")
    void validation() throws Exception {
        String serviceId = createService();
        mockMvc.perform(put("/api/v1/admin/mcp-services/" + serviceId + "/backend-auth")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"HEADER\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MCP_BACKEND_AUTH_INVALID"));
        mockMvc.perform(put("/api/v1/admin/mcp-services/" + serviceId + "/backend-auth")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"API_KEY\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MCP_BACKEND_AUTH_INVALID"));
        String oversized = "x".repeat(4097);
        mockMvc.perform(
                put("/api/v1/admin/mcp-services/" + serviceId + "/backend-auth").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"API_KEY\",\"secret\":\"" + oversized + "\"}"))
                .andExpect(status().isBadRequest());
        Long events = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_events WHERE action = 'MCP_SERVICE_BACKEND_AUTH'",
                new MapSqlParameterSource(), Long.class);
        assertThat(events).isZero();
    }
}
