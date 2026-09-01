package com.miqroera.miqrokey.controlplane.controller;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal service registry (P3.2): CRUD, base URL validation (https, no
 * userinfo) and disable lifecycle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin service API integration tests (PostgreSQL)")
class AdminServiceApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminProviderApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
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

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminProviderApiIntegrationTest.BootstrapHelper.secret(), "root", "Admin"))))
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
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : new String[]{"services", "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("service endpoints require authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/services")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("service lifecycle: create with kind default, list, disable")
    void serviceLifecycle() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"platform-api\",\"description\":\"Platform backend\","
                                + "\"baseUrl\":\"https://platform.internal.example\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("platform-api"))
                .andExpect(jsonPath("$.kind").value("HTTP"))
                .andExpect(jsonPath("$.baseUrl").value("https://platform.internal.example"))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();
        String serviceId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(get("/api/v1/admin/services").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Explicit kind.
        mockMvc.perform(post("/api/v1/admin/services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"mcp-gateway\",\"kind\":\"MCP\",\"baseUrl\":\"https://mcp.internal.example\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("MCP"));

        // Disable, then conflict on re-disable.
        mockMvc.perform(post("/api/v1/admin/services/" + serviceId + "/disable").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(post("/api/v1/admin/services/" + serviceId + "/disable").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_ALREADY_DISABLED"));
    }

    @Test
    @DisplayName("service creation validates the base URL and the name")
    void serviceValidation() throws Exception {
        // Non-https, userinfo, query, unknown kind, duplicate name.
        mockMvc.perform(post("/api/v1/admin/services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad\",\"baseUrl\":\"http://insecure.example\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BASE_URL_INVALID"));
        mockMvc.perform(post("/api/v1/admin/services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad\",\"baseUrl\":\"https://user:pass@example.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BASE_URL_INVALID"));
        mockMvc.perform(post("/api/v1/admin/services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad\",\"baseUrl\":\"https://example.com?x=1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BASE_URL_INVALID"));
        mockMvc.perform(post("/api/v1/admin/services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad\",\"kind\":\"GRPC\",\"baseUrl\":\"https://example.com\"}"))
                .andExpect(status().isBadRequest());

        String body = "{\"name\":\"dup\",\"baseUrl\":\"https://dup.example\"}";
        mockMvc.perform(post("/api/v1/admin/services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_NAME_TAKEN"));
    }

    private static Cookie cookie(MvcResult result, String name) {
        return java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                .findFirst().orElse(null);
    }
}
