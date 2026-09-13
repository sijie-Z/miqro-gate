package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.route.JdbcRouteSnapshotLoader;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression (#441): once ANY mcp_resilience_policy row exists, the route
 * snapshot must still load. The loader's row mapper reads {@code p.version},
 * but the service query did not select it — every load threw
 * {@code PSQLException: column name version was not found} and the refresher
 * kept the previous snapshot forever, silently freezing key revocations,
 * rotations, grants and approvals while traffic continued on the stale
 * snapshot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Route snapshot loads with a resilience policy row (#441)")
class RouteSnapshotPolicyLoadIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    private UUID serviceId;
    private String serviceName;
    private UUID adminUserId;

    @BeforeEach
    void setUp() throws Exception {
        cleanState();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "snap_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        adminUserId = UUID.fromString((String) bootBody.get("userId"));
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());

        serviceId = UUID.randomUUID();
        serviceName = "policy-" + serviceId.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO mcp_services (id, tenant_id, name, endpoint, transport, status, check_interval_seconds,
                    check_timeout_seconds, fail_threshold, recover_threshold, check_path, health_status, version,
                    created_by)
                VALUES (:id, :tenantId, :name, 'https://mcp.example.test/mcp', 'STREAMABLE_HTTP', 'ONLINE', 30,
                    3, 3, 1, '/health', 'UNKNOWN', 0, :createdBy)
                """, new MapSqlParameterSource().addValue("id", serviceId).addValue("tenantId", TENANT_ID)
                .addValue("name", serviceName).addValue("createdBy", adminUserId));
    }

    @AfterEach
    void tearDown() {
        // mcp_services.created_by references users: leaving this class's rows
        // behind would make the next bootstrap in the shared container fail
        // (its users-DELETE would be FK-blocked, so bootstrap sees an admin).
        cleanState();
    }

    private void cleanState() {
        for (String table : List.of("mcp_resilience_policy", "mcp_service_access", "mcp_access_grants", "mcp_tools",
                "mcp_route_rule", "mcp_services", "user_sessions", "admin_audit_events", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
    }

    @Test
    @DisplayName("a stored resilience policy loads into the snapshot instead of breaking it")
    void snapshotLoadsWithPolicyRow() throws Exception {
        // The policy row (written through the real API) is what used to break
        // every subsequent snapshot load.
        mockMvc.perform(put("/api/v1/admin/mcp-services/" + serviceId + "/resilience").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"retryEnabled\":true,\"retryMax\":1,\"retryConditions\":[\"SERVER_5XX\"]}"))
                .andExpect(status().isOk());

        RouteSnapshot snapshot = new JdbcRouteSnapshotLoader(jdbc, objectMapper).load(1L, Instant.now());

        RouteSnapshot.McpServerRecord record = snapshot.mcpService(serviceName);
        assertThat(record).isNotNull();
        assertThat(record.resilience()).isNotNull();
        assertThat(record.resilience().retryEnabled()).isTrue();
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
}
