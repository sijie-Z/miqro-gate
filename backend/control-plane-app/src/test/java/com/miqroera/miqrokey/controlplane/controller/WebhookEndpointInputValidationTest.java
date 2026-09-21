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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Input-validation consistency for {@code POST /api/v1/admin/webhooks} (PH23).
 *
 * <p>
 * Every value sent here is refused by the admin UI
 * ({@code NextAdminWebhooksView.createWebhook} line 137: "名称、URL 与签名密钥必填。") or
 * falls outside the storage/consumer domain of the column it lands in, so the
 * API must answer {@code 400} — the boundary must not depend on which client
 * (browser vs. curl) sent the request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Webhook endpoint input validation (PH23)")
class WebhookEndpointInputValidationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
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
    private HttpServer receiver;
    private String receiverUrl;

    @BeforeEach
    void setUp() throws Exception {
        resetData();
        receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        receiver.start();
        receiverUrl = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";

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
        if (receiver != null) {
            receiver.stop(0);
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

    private org.springframework.test.web.servlet.ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(
                post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "hook-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("url", receiverUrl);
        body.put("secret", "whsec-test-value");
        return body;
    }

    @Test
    @DisplayName("absent secret is a 400, not an NPE 500")
    void missingSecretIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("secret");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("blank secret is a 400, not a stored endpoint no delivery can sign with")
    void blankSecretIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("secret", "");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("absent name is a 400, not a NOT NULL breach mapped to 409")
    void missingNameIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("name");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("whitespace-only name is a 400, not a nameless row in the endpoint list")
    void whitespaceNameIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("name", "   ");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("name longer than varchar(200) is a 400, not a 409")
    void oversizedNameIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("name", "n".repeat(201));
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("absent url is a 400, not an NPE 500 from URI parsing")
    void missingUrlIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("url");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("timeoutMs below the accepted range is a 400, not a stored degenerate timeout")
    void belowRangeTimeoutIsRejected() throws Exception {
        Map<String, Object> zero = validBody();
        zero.put("timeoutMs", 0);
        create(zero).andExpect(status().isBadRequest());

        Map<String, Object> negative = validBody();
        negative.put("timeoutMs", -1);
        create(negative).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("timeoutMs above the accepted range is a 400, not a stored multi-day timeout")
    void aboveRangeTimeoutIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("timeoutMs", 2_000_000_000);
        create(body).andExpect(status().isBadRequest());
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
