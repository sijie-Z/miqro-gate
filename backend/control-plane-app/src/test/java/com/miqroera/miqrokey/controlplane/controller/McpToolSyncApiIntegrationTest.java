package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP tools/list sync (#344, raw doc 03): differential merge into mcp_tools
 * against a real loopback upstream — adds with baseline revisions, description
 * refresh through F16 revisions, absent-upstream reporting, dry-run, backend
 * credential injection and the sanitized upstream-failure surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP tools sync integration tests (PostgreSQL)")
class McpToolSyncApiIntegrationTest {

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

    private HttpServer upstream;
    private int upstreamPort;
    private volatile int upstreamStatus = 200;
    private volatile String upstreamBody = toolsJson();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID serviceId;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/mcp", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = upstreamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper
                                .writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(), "syncer", "Admin"))))
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

        // The admin API requires https endpoints; point the stored endpoint at the
        // loopback stub afterwards (config manipulation, not an API behavior).
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sync-mcp\",\"endpoint\":\"https://mcp.internal.example/mcp\"}"))
                .andExpect(status().isOk()).andReturn();
        serviceId = UUID.fromString(
                objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString());
        // OFFLINE keeps the scheduled McpHealthChecker away from the loopback stub:
        // its probes carry no Authorization header and would race the captured-header
        // assertions; the sync endpoints ignore the service status.
        jdbc.update("UPDATE mcp_services SET endpoint = :endpoint, status = 'OFFLINE' WHERE id = :id",
                new MapSqlParameterSource("endpoint", "http://127.0.0.1:" + upstreamPort + "/mcp").addValue("id",
                        serviceId));
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
        clean();
    }

    @Test
    @DisplayName("sync requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/sync"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("first sync adds tools with baseline revisions; re-sync is idempotent")
    void syncAddsAndIsIdempotent() throws Exception {
        upstreamBody = toolsJson("{\"name\":\"get_word\",\"description\":\"每日单词\"}", "{\"name\":\"post_json\"}",
                "{\"name\":\"Bad-Name\",\"description\":\"x\"}");

        sync(false).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.upstreamToolCount").value(3)).andExpect(jsonPath("$.added", hasSize(2)))
                .andExpect(jsonPath("$.added[0]").value("get_word"))
                .andExpect(jsonPath("$.added[1]").value("post_json")).andExpect(jsonPath("$.skipped", hasSize(1)))
                .andExpect(jsonPath("$.skipped[0].toolName").value("Bad-Name"));

        MvcResult page = mockMvc.perform(get(toolsUrl()).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].method").value("POST"))
                .andExpect(jsonPath("$[0].path").value("/")).andExpect(jsonPath("$[0].status").value("ENABLED"))
                .andReturn();
        String toolId = objectMapper.readTree(page.getResponse().getContentAsString()).get(0).get("id").asText();
        mockMvc.perform(get(toolsUrl() + "/" + toolId + "/revisions").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].revision").value(1));
        assertThat(auditCount("MCP_TOOLS_SYNCED")).isEqualTo(1);

        // Idempotent: nothing added, nothing updated, no new revisions.
        sync(false).andExpect(status().isOk()).andExpect(jsonPath("$.added", hasSize(0)))
                .andExpect(jsonPath("$.updated", hasSize(0))).andExpect(jsonPath("$.unchanged").value(2));
        mockMvc.perform(get(toolsUrl() + "/" + toolId + "/revisions").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        assertThat(auditCount("MCP_TOOLS_SYNCED")).isEqualTo(2);
    }

    @Test
    @DisplayName("an upstream description change publishes the next active revision")
    void descriptionRefreshPublishesRevision() throws Exception {
        upstreamBody = toolsJson("{\"name\":\"alpha\",\"description\":\"v1\"}");
        sync(false).andExpect(status().isOk()).andExpect(jsonPath("$.added", hasSize(1)));
        String toolId = firstToolId();

        upstreamBody = toolsJson("{\"name\":\"alpha\",\"description\":\"v2\"}");
        sync(false).andExpect(status().isOk()).andExpect(jsonPath("$.updated", contains("alpha")))
                .andExpect(jsonPath("$.added", hasSize(0)));

        mockMvc.perform(get(toolsUrl()).cookie(sessionCookie)).andExpect(jsonPath("$[0].description").value("v2"));
        mockMvc.perform(get(toolsUrl() + "/" + toolId + "/revisions").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].revision").value(2))
                .andExpect(jsonPath("$[0].activatedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].description").value("v2")).andExpect(jsonPath("$[1].revision").value(1))
                .andExpect(jsonPath("$[1].activatedAt").isEmpty());
    }

    @Test
    @DisplayName("tools the upstream no longer returns are reported, never disabled")
    void absentUpstreamIsReportedOnly() throws Exception {
        mockMvc.perform(post(toolsUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"manual_one\",\"description\":\"手抄\",\"path\":\"/manual\"}"))
                .andExpect(status().isOk());
        upstreamBody = toolsJson("{\"name\":\"upstream_only\"}");

        sync(false).andExpect(status().isOk()).andExpect(jsonPath("$.absentUpstream", contains("manual_one")))
                .andExpect(jsonPath("$.added", contains("upstream_only")));

        mockMvc.perform(get(toolsUrl()).cookie(sessionCookie))
                .andExpect(jsonPath("$[?(@.toolName=='manual_one')].status").value(contains("ENABLED")));
    }

    @Test
    @DisplayName("dryRun returns the diff without writing or auditing")
    void dryRunWritesNothing() throws Exception {
        upstreamBody = toolsJson("{\"name\":\"preview_only\"}");

        sync(true).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.added", contains("preview_only")));

        mockMvc.perform(get(toolsUrl()).cookie(sessionCookie)).andExpect(jsonPath("$", hasSize(0)));
        assertThat(auditCount("MCP_TOOLS_SYNCED")).isZero();
    }

    @Test
    @DisplayName("upstream failures surface as a sanitized 502")
    void upstreamFailureIsSanitized() throws Exception {
        upstreamStatus = 500;
        upstreamBody = "boom";

        String problem = sync(false).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TOOLS_SYNC_UPSTREAM_FAILED")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(problem).contains("500").doesNotContain("127.0.0.1").doesNotContain("boom");
    }

    @Test
    @DisplayName("API_KEY backends receive the decrypted bearer; VISITOR sends none")
    void backendCredentialInjection() throws Exception {
        mockMvc.perform(put(backendAuthUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"API_KEY\",\"secret\":\"sk-upstream-1\"}"))
                .andExpect(status().isOk());

        sync(false).andExpect(status().isOk());

        assertThat(lastAuthorization.get()).isEqualTo("Bearer sk-upstream-1");
        assertThat(lastBody.get()).contains("\"method\":\"tools/list\"");

        mockMvc.perform(put(backendAuthUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"VISITOR\"}")).andExpect(status().isOk());
        sync(false).andExpect(status().isOk());

        assertThat(lastAuthorization.get()).isNull();
    }

    @Test
    @DisplayName("unknown service is a 404")
    void unknownServiceIs404() throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + UUID.randomUUID() + "/tools/sync")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MCP_SERVICE_NOT_FOUND"));
    }

    // ------------------------------------------------------------- helpers

    /** POSTs the sync endpoint (status asserted by each test). */
    private org.springframework.test.web.servlet.ResultActions sync(boolean dryRun) throws Exception {
        return mockMvc.perform(
                post("/api/v1/admin/mcp-services/" + serviceId + "/tools/sync" + (dryRun ? "?dryRun=true" : ""))
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken));
    }

    private String toolsUrl() {
        return "/api/v1/admin/mcp-services/" + serviceId + "/tools";
    }

    private String backendAuthUrl() {
        return "/api/v1/admin/mcp-services/" + serviceId + "/backend-auth";
    }

    private String firstToolId() throws Exception {
        MvcResult page = mockMvc.perform(get(toolsUrl()).cookie(sessionCookie)).andReturn();
        return objectMapper.readTree(page.getResponse().getContentAsString()).get(0).get("id").asText();
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
    }

    private static String toolsJson(String... toolJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" + String.join(",", toolJson) + "]}}";
    }

    private void clean() {
        for (String table : new String[]{"mcp_tool_revisions", "mcp_tools", "mcp_services", "user_sessions", "users",
                "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    private static Cookie cookie(MvcResult result, String name) {
        if (result.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie candidate : result.getResponse().getCookies()) {
            if (name.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }
}
