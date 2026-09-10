package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.service.ServiceHealthChecker;
import com.miqroera.miqrokey.domain.repository.InternalServiceRepository;
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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal service runtime governance (#326): enable/disable roundtrip with
 * audits, health-config update with validation, and the probe lifecycle against
 * a real local HTTP server (HEALTHY on 2xx; UNHEALTHY after the fail threshold;
 * DISABLED rows never probed). Probe rows are inserted through the repository
 * with a loopback URL (the admin API requires https).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Service runtime governance integration tests (PostgreSQL)")
class AdminServiceRuntimeApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    @Autowired
    InternalServiceRepository serviceRepository;
    @Autowired
    ServiceHealthChecker checker;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID adminUserId;
    private HttpServer probeServer;
    private final String adminUsername = "svc_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        clean();
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
        if (probeServer != null) {
            probeServer.stop(0);
        }
        clean();
    }

    private void clean() {
        for (String table : List.of("services", "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
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

    private String createService(String name) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/services")
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name
                                + "\",\"kind\":\"HTTP\",\"baseUrl\":\"https://" + name + ".internal.example.com\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private long countEvents(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
    }

    @Test
    @DisplayName("enable/disable roundtrip is symmetric and audited")
    void enableDisableRoundtrip() throws Exception {
        String serviceId = createService("roundtrip-svc");
        mockMvc.perform(post("/api/v1/admin/services/" + serviceId + "/disable").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(countEvents("SERVICE_DISABLE")).isEqualTo(1);

        mockMvc.perform(post("/api/v1/admin/services/" + serviceId + "/enable").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(countEvents("SERVICE_ENABLE")).isEqualTo(1);
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT actor_id, change_summary::text AS summary FROM admin_audit_events
                WHERE action = 'SERVICE_ENABLE' LIMIT 1
                """, new MapSqlParameterSource());
        assertThat(event.get("actor_id")).isEqualTo(adminUserId);
        assertThat((String) event.get("summary")).contains("roundtrip-svc");

        // Double enable conflicts (mirror of double disable).
        mockMvc.perform(post("/api/v1/admin/services/" + serviceId + "/enable").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_ALREADY_ENABLED"));
    }

    @Test
    @DisplayName("health-config partial update persists and validates")
    void healthConfigUpdate() throws Exception {
        String serviceId = createService("config-svc");
        mockMvc.perform(post("/api/v1/admin/services/" + serviceId + "/health-config").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkIntervalSeconds\":120,\"checkPath\":\"/readyz\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkIntervalSeconds").value(120))
                .andExpect(jsonPath("$.checkPath").value("/readyz"))
                .andExpect(jsonPath("$.checkTimeoutSeconds").value(5));
        assertThat(countEvents("SERVICE_HEALTH_UPDATE")).isEqualTo(1);

        mockMvc.perform(post("/api/v1/admin/services/" + serviceId + "/health-config").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkIntervalSeconds\":1}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("probe lifecycle: HEALTHY on 2xx, UNHEALTHY after fail threshold, DISABLED not probed")
    void probeLifecycle() throws Exception {
        probeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        probeServer.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        probeServer.start();
        String baseUrl = "http://127.0.0.1:" + probeServer.getAddress().getPort();

        // Insert directly (loopback URL is not https; admin API rejects it).
        UUID serviceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO services (id, tenant_id, name, kind, description, base_url, status, version, created_by,
                    created_at, updated_at, health_status, consecutive_failures, consecutive_successes,
                    check_interval_seconds, check_timeout_seconds, fail_threshold, recover_threshold, check_path)
                VALUES (:id, :tenantId, 'probe-svc', 'HTTP', null, :baseUrl, 'ACTIVE', 0, :createdBy, now(), now(),
                        'UNKNOWN', 0, 0, 5, 2, 2, 1, '/health')
                """, new MapSqlParameterSource("id", serviceId).addValue("tenantId", TENANT_ID)
                .addValue("baseUrl", baseUrl).addValue("createdBy", adminUserId));

        checker.checkAll();
        assertThat(healthOf(serviceId)).isEqualTo("HEALTHY");

        // Next cycles are skipped until the interval elapses; force due-ness.
        probeServer.stop(0);
        probeServer = null;
        checker.checkAll();
        assertThat(healthOf(serviceId)).as("not due yet").isEqualTo("HEALTHY");
        forceDue(serviceId);
        checker.checkAll();
        assertThat(healthOf(serviceId)).as("first failure below threshold").isEqualTo("HEALTHY");
        forceDue(serviceId);
        checker.checkAll();
        assertThat(healthOf(serviceId)).as("fail threshold reached").isEqualTo("UNHEALTHY");

        // Manual disable: never probed, health state frozen as-is.
        jdbc.update("UPDATE services SET status = 'DISABLED', health_status = 'UNKNOWN' WHERE id = :id",
                new MapSqlParameterSource("id", serviceId));
        checker.checkAll();
        assertThat(healthOf(serviceId)).as("disabled rows are not probed").isEqualTo("UNKNOWN");
    }

    private String healthOf(UUID serviceId) {
        return jdbc.queryForObject("SELECT health_status FROM services WHERE id = :id",
                new MapSqlParameterSource("id", serviceId), String.class);
    }

    private void forceDue(UUID serviceId) {
        jdbc.update("""
                UPDATE services SET health_checked_at = now() - interval '1 hour' WHERE id = :id
                """, new MapSqlParameterSource("id", serviceId));
    }
}
