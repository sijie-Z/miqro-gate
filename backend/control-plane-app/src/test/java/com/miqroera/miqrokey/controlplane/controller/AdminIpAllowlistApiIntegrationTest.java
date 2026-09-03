package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Management-portal IP allowlist (F05, security §6): with
 * {@code miqrokey.control.admin-access.ip-allowlist} configured, portal
 * requests from outside the list are rejected with {@code IP_NOT_ALLOWED};
 * billing and bootstrap stay reachable, and {@code X-Forwarded-For} is honored
 * only from trusted proxies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin IP allowlist API integration tests (PostgreSQL)")
class AdminIpAllowlistApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // MockMvc runs from the loopback address; a second allowed block is the
        // X-Forwarded-For client in the trusted-proxy scenario.
        registry.add("miqrokey.control.admin-access.ip-allowlist", () -> "127.0.0.0/8,198.51.100.0/24");
        registry.add("miqrokey.control.admin-access.trusted-proxies", () -> "127.0.0.0/8");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie sessionCookie;
    private String adminPassword = "NewSecurePass1!";
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        resetDb();
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
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), adminPassword))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        resetDb();
    }

    /** A request presented from the given remote address. */
    private static RequestPostProcessor remote(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @Test
    @DisplayName("allowlisted peers reach the portal; others get 403 IP_NOT_ALLOWED")
    void allowlistEnforced() throws Exception {
        mockMvc.perform(get("/api/v1/me/virtual-keys").with(remote("127.0.0.1")).cookie(sessionCookie))
                .andExpect(status().isOk());
        // A second allowlisted block (from the proxy-forwarded scenario) also passes.
        mockMvc.perform(get("/api/v1/me/virtual-keys").with(remote("198.51.100.9")).cookie(sessionCookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/virtual-keys").with(remote("203.0.113.5")).cookie(sessionCookie))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IP_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("bootstrap and the billing channel are exempt from the portal allowlist")
    void exemptions() throws Exception {
        // Re-bootstrapping from a foreign IP is rejected by the BUSINESS layer
        // (401: already bootstrapped), not by the IP allowlist (403) — the path
        // is exempt and reached the controller.
        mockMvc.perform(
                post("/api/v1/auth/bootstrap").with(remote("203.0.113.5")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm2_" + UUID.randomUUID().toString().substring(0, 8), "Admin2"))))
                .andExpect(status().isUnauthorized());

        // Billing without a credential from a foreign IP: rejected by the API-key
        // gate (401), not by the IP allowlist (403) — the channel is exempt.
        mockMvc.perform(get("/api/v1/billing/summary").with(remote("203.0.113.5")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("X-Forwarded-For is honored only from trusted proxies")
    void forwardedForTrustedProxiesOnly() throws Exception {
        // Trusted proxy (loopback) forwards the client address -> allowlisted client
        // passes.
        mockMvc.perform(get("/api/v1/me/virtual-keys").with(remote("127.0.0.1"))
                .header("X-Forwarded-For", "198.51.100.7").cookie(sessionCookie)).andExpect(status().isOk());
        // Same forwarded header from an untrusted direct peer: the real address
        // decides -> 403 (no header forgery possible).
        mockMvc.perform(get("/api/v1/me/virtual-keys").with(remote("203.0.113.5"))
                .header("X-Forwarded-For", "198.51.100.7").cookie(sessionCookie)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IP_NOT_ALLOWED"));
        // A trusted proxy forwarding a foreign client is still rejected.
        mockMvc.perform(get("/api/v1/me/virtual-keys").with(remote("127.0.0.1"))
                .header("X-Forwarded-For", "203.0.113.9").cookie(sessionCookie)).andExpect(status().isForbidden());
    }

    private void resetDb() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "usage_event", "price_snapshot", "quota_rules", "quota_default_template", "virtual_key_models",
                "key_project_binding", "model_approval", "virtual_keys", "project_provider_grant_models",
                "project_provider_grants", "upstream_credential_versions", "upstream_credentials", "plan_seats",
                "upstream_subscriptions", "project_memberships", "projects", "provider_products", "providers",
                "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Child-first order above covers the canonical FK set.
            }
        }
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
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
