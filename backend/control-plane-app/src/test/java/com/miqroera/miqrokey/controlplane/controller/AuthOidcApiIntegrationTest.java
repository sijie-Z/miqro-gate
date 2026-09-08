package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Platform OIDC login (P0a, ADR-0017): authorization-code flow against a stub
 * IdP serving /token and /userinfo — auto-provision on first login, link reuse
 * on subsequent logins, state validation and audit actions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Platform OIDC login integration tests (PostgreSQL)")
class AuthOidcApiIntegrationTest {

    private static HttpServer idp;

    @BeforeAll
    static void startIdp() throws IOException {
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/token", AuthOidcApiIntegrationTest::tokenHandler);
        idp.createContext("/userinfo", AuthOidcApiIntegrationTest::userinfoHandler);
        idp.start();
    }

    private static void tokenHandler(HttpExchange exchange) throws IOException {
        byte[] body = "{\"access_token\":\"stub-access-token\",\"token_type\":\"Bearer\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void userinfoHandler(HttpExchange exchange) throws IOException {
        byte[] body = ("{\"sub\":\"forge-sub-001\",\"username\":\"forge_user\",\"nickname\":\"平台用户\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static int idpPort() {
        return idp.getAddress().getPort();
    }

    @AfterAll
    static void stopIdp() {
        if (idp != null) {
            idp.stop(0);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminProviderApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.platform-oidc-enabled", () -> "true");
        registry.add("miqrokey.platform-oidc-client-id", () -> "stub-client");
        registry.add("miqrokey.platform-oidc-client-secret", () -> "stub-secret");
        registry.add("miqrokey.platform-oidc-authorize-uri", () -> "http://127.0.0.1:" + idpPort() + "/authorize");
        registry.add("miqrokey.platform-oidc-token-uri", () -> "http://127.0.0.1:" + idpPort() + "/token");
        registry.add("miqrokey.platform-oidc-userinfo-uri", () -> "http://127.0.0.1:" + idpPort() + "/userinfo");
        registry.add("miqrokey.platform-oidc-redirect-uri", () -> "http://localhost/api/v1/auth/oauth/callback");
        registry.add("miqrokey.platform-oidc-auto-provision", () -> "true");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : new String[]{"user_identity_link", "user_sessions", "admin_audit_events", "users"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminProviderApiIntegrationTest.BootstrapHelper.secret(), "root", "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        Cookie session = extractCookie(boot, "MIQROKEY_SESSION");
        Cookie csrf = extractCookie(boot, "MIQROKEY_CSRF");
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).cookie(session, csrf)
                .header("X-CSRF-Token", csrf != null ? csrf.getValue() : "")
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        for (String table : new String[]{"user_identity_link", "user_sessions", "admin_audit_events", "users"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    private static Cookie extractCookie(MvcResult r, String name) {
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
    @DisplayName("enabled provider is advertised for the login page")
    void providerAdvertised() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/providers")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("forge")).andExpect(jsonPath("$[0].name").value("平台账号登录"));
    }

    @Test
    @DisplayName("code flow auto-provisions the user, links sub and logs them in")
    void codeFlowAutoProvisions() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/v1/auth/oauth/start")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("response_type=code")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("client_id=stub-client")))
                .andExpect(cookie().exists("MIQROKEY_OAUTH_STATE")).andReturn();
        String state = extractCookie(started, "MIQROKEY_OAUTH_STATE").getValue();

        MvcResult done = mockMvc
                .perform(get("/api/v1/auth/oauth/callback").param("code", "stub-code").param("state", state)
                        .cookie(new Cookie("MIQROKEY_OAUTH_STATE", state)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/app/keys")))
                .andExpect(cookie().exists("MIQROKEY_SESSION")).andReturn();
        Cookie session = extractCookie(done, "MIQROKEY_SESSION");

        // Session is live for the provisioned user.
        mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("forge_user"))
                .andExpect(jsonPath("$.displayName").value("平台用户"));
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'forge_user'",
                new MapSqlParameterSource(), Long.class);
        assertThat(users).isEqualTo(1L);
        Long links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_identity_link WHERE idp = 'forge' AND platform_user_id = 'forge-sub-001'",
                new MapSqlParameterSource(), Long.class);
        assertThat(links).isEqualTo(1L);
        Long provisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_events WHERE action = 'OAUTH_PROVISION'", new MapSqlParameterSource(),
                Long.class);
        assertThat(provisions).isEqualTo(1L);
    }

    @Test
    @DisplayName("second login reuses the linked account instead of provisioning another")
    void secondLoginReusesLink() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/auth/oauth/start")).andExpect(status().is3xxRedirection())
                .andReturn();
        String state1 = extractCookie(first, "MIQROKEY_OAUTH_STATE").getValue();
        mockMvc.perform(get("/api/v1/auth/oauth/callback").param("code", "c1").param("state", state1)
                .cookie(new Cookie("MIQROKEY_OAUTH_STATE", state1))).andExpect(status().is3xxRedirection());

        MvcResult second = mockMvc.perform(get("/api/v1/auth/oauth/start")).andExpect(status().is3xxRedirection())
                .andReturn();
        String state2 = extractCookie(second, "MIQROKEY_OAUTH_STATE").getValue();
        mockMvc.perform(get("/api/v1/auth/oauth/callback").param("code", "c2").param("state", state2)
                .cookie(new Cookie("MIQROKEY_OAUTH_STATE", state2))).andExpect(status().is3xxRedirection());

        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'forge_user'",
                new MapSqlParameterSource(), Long.class);
        assertThat(users).isEqualTo(1L);
        Long logins = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_events WHERE action = 'OAUTH_LOGIN'",
                new MapSqlParameterSource(), Long.class);
        assertThat(logins).isEqualTo(1L);
    }

    @Test
    @DisplayName("state mismatch bounces to the login page without a session")
    void stateMismatchRejected() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/callback").param("code", "stub-code").param("state", "wrong-state"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("oauth_error=STATE_MISMATCH")));
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'forge_user'",
                new MapSqlParameterSource(), Long.class);
        assertThat(users).isZero();
    }
}
