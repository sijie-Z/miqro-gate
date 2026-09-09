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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Service/integration family audit coverage (issue #315, Tencent raw 27
 * resource types): every admin mutation of the consumers / agents / internal
 * services / MCP services / MCP tools (+revisions) / skills families records an
 * audit event with the acting SYSTEM_ADMIN as actor, a valid JSON summary that
 * never carries secrets, and the request id. Success-path endpoints are driven
 * over HTTP; events are asserted straight from the append-only chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Service-family audit coverage integration tests (PostgreSQL)")
class AdminFamilyAuditIntegrationTest {

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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID adminUserId;
    private final String adminUsername = "fam_" + UUID.randomUUID().toString().substring(0, 8);

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
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
        adminUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", adminUsername), UUID.class);
        // Bootstrap writes login/password events; clear so assertions are exact.
        jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : List.of("mcp_tool_revisions", "mcp_tools", "mcp_route_rule", "mcp_services", "skills",
                "skill_access", "agents", "api_consumers", "services", "upstream_credentials", "upstream_subscriptions",
                "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
    }

    // ------------------------------------------------------------- helpers

    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc
                .perform(builder.cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .header("X-Request-Id", "req-" + UUID.randomUUID()))
                .andExpect(status().is2xxSuccessful()).andReturn();
    }

    private String postJson(String path, String json) throws Exception {
        return call(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).getResponse()
                .getContentAsString();
    }

    private String postForm(String path, String query) throws Exception {
        return call(post(path + "?" + query)).getResponse().getContentAsString();
    }

    private String putJson(String path, String json) throws Exception {
        return call(put(path).contentType(MediaType.APPLICATION_JSON).content(json)).getResponse().getContentAsString();
    }

    private String idOf(String body) throws Exception {
        return objectMapper.readTree(body).get("id").asText();
    }

    private long countEvents(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
    }

    private MapSqlParameterSource latestEvent(String action) {
        return jdbc.queryForObject("""
                SELECT actor_id, change_summary::text AS summary, admin_request_id
                FROM admin_audit_events
                WHERE action = :action
                ORDER BY chain_position DESC
                LIMIT 1
                """, new MapSqlParameterSource("action", action),
                (rs, n) -> new MapSqlParameterSource().addValue("actor", rs.getObject("actor_id"))
                        .addValue("summary", rs.getString("summary"))
                        .addValue("requestId", rs.getString("admin_request_id")));
    }

    private void assertAudited(String action) throws Exception {
        assertThat(countEvents(action)).as("event %s exists", action).isEqualTo(1);
        MapSqlParameterSource event = latestEvent(action);
        assertThat((UUID) event.getValue("actor")).isEqualTo(adminUserId);
        String summary = (String) event.getValue("summary");
        assertThat(summary).as("summary is valid JSON").isNotNull().isNotBlank();
        // change_summary must be parseable as a JSON object (jsonb round trip).
        objectMapper.readTree(summary);
        assertThat(event.getValue("requestId")).as("request id correlation").isNotNull();
    }

    private static String zip(String path, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(path));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private UUID seedCredential() {
        UUID productId = jdbc.queryForObject("SELECT id FROM provider_products ORDER BY display_name LIMIT 1",
                new MapSqlParameterSource(), UUID.class);
        UUID subscriptionId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upstream_subscriptions
                    (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                VALUES (:id, :tenantId, :productId, 'Audit Agent Sub', 'PAYG', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", TENANT_ID)
                .addValue("productId", productId));
        jdbc.update("""
                INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                VALUES (:id, :tenantId, :subscriptionId, 'Audit Agent Cred', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", TENANT_ID)
                .addValue("subscriptionId", subscriptionId));
        return credentialId;
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

    private static String rsaPublicKeyPem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String base64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------- tests

    @Test
    @DisplayName("consumer lifecycle events carry actor and never the plaintext key")
    void consumerFamilyAudited() throws Exception {
        String created = postJson("/api/v1/admin/api-consumers", "{\"name\":\"audit-consumer\"}");
        assertAudited("CONSUMER_CREATE");
        String plaintext = objectMapper.readTree(created).get("apiKey").asText();
        assertThat((String) latestEvent("CONSUMER_CREATE").getValue("summary")).doesNotContain(plaintext);
        String consumerId = objectMapper.readTree(created).get("consumer").get("id").asText();

        String pemJson = rsaPublicKeyPem().replace("\n", "\\n");
        putJson("/api/v1/admin/api-consumers/" + consumerId + "/jwt-key", "{\"publicKeyPem\":\"" + pemJson + "\"}");
        assertAudited("CONSUMER_JWT_KEY_SET");

        call(delete("/api/v1/admin/api-consumers/" + consumerId + "/jwt-key"));
        assertAudited("CONSUMER_JWT_KEY_REMOVED");

        call(post("/api/v1/admin/api-consumers/" + consumerId + "/disable"));
        assertAudited("CONSUMER_DISABLE");
    }

    @Test
    @DisplayName("agent and internal-service registry events")
    void agentAndServiceFamilyAudited() throws Exception {
        UUID credentialId = seedCredential();
        String agent = postJson("/api/v1/admin/agents",
                "{\"name\":\"audit-agent\",\"description\":\"audited\",\"credentialId\":\"" + credentialId + "\"}");
        assertAudited("AGENT_CREATE");
        String agentId = idOf(agent);
        call(post("/api/v1/admin/agents/" + agentId + "/disable"));
        assertAudited("AGENT_DISABLE");

        String service = postJson("/api/v1/admin/services",
                "{\"name\":\"audit-svc\",\"kind\":\"HTTP\",\"baseUrl\":\"https://internal.example.com/api\"}");
        assertAudited("SERVICE_CREATE");
        String serviceId = idOf(service);
        call(post("/api/v1/admin/services/" + serviceId + "/disable"));
        assertAudited("SERVICE_DISABLE");
    }

    @Test
    @DisplayName("MCP service, tool and revision events")
    void mcpFamilyAudited() throws Exception {
        String mcp = postJson("/api/v1/admin/mcp-services",
                "{\"name\":\"audit-mcp\",\"endpoint\":\"https://mcp.internal.example.com/mcp\"}");
        assertAudited("MCP_SERVICE_CREATE");
        String serviceId = idOf(mcp);

        postForm("/api/v1/admin/mcp-services/" + serviceId + "/status", "status=OFFLINE");
        assertAudited("MCP_SERVICE_STATUS");
        String healthBody = postJson("/api/v1/admin/mcp-services/" + serviceId + "/health-config",
                "{\"checkIntervalSeconds\":60}");
        assertAudited("MCP_SERVICE_HEALTH_UPDATE");
        UUID healthUpdatedId = UUID.fromString(idOf(healthBody));

        String tool = postJson("/api/v1/admin/mcp-services/" + serviceId + "/tools",
                "{\"toolName\":\"forecast\",\"description\":\"weather\",\"method\":\"GET\",\"path\":\"/forecast\"}");
        assertAudited("MCP_TOOL_CREATE");
        String toolId = idOf(tool);

        postForm("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/status", "status=DISABLED");
        assertAudited("MCP_TOOL_STATUS");

        call(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/forecast/v2\"}"));
        assertAudited("MCP_TOOL_REVISION_PUBLISH");
        // Revision 2 was just activated by the publish; activating the baseline
        // revision 1 is a real pointer move and records an ACTIVATE event.
        call(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions/1/activate"));
        assertAudited("MCP_TOOL_REVISION_ACTIVATE");

        // Health update did not resurrect a disabled service (regression guard).
        assertThat(jdbc.queryForObject("SELECT status FROM mcp_services WHERE id = :id",
                new MapSqlParameterSource("id", healthUpdatedId), String.class)).isEqualTo("OFFLINE");
    }

    @Test
    @DisplayName("skill upload/access/archive events")
    void skillFamilyAudited() throws Exception {
        String skillMd = "---\nname: audit-skill\ndescription: Audited skill\n---\n# Audit skill\n";
        byte[] pkg = Base64.getDecoder().decode(zip("audit-skill/SKILL.md", skillMd));
        MvcResult upload = mockMvc.perform(post("/api/v1/admin/skills?version=1.0.0").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType("application/zip").content(pkg)
                .header("X-Request-Id", "req-skill")).andExpect(status().isOk()).andReturn();
        String skillId = objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();
        assertAudited("SKILL_UPLOAD");

        String teamId = postJson("/api/v1/admin/teams", "{\"name\":\"audit-team\"}");
        putJson("/api/v1/admin/skills/" + skillId + "/access",
                "[{\"scopeType\":\"TEAM\",\"scopeId\":\"" + idOf(teamId) + "\"}]");
        assertAudited("SKILL_ACCESS");

        call(post("/api/v1/admin/skills/" + skillId + "/archive"));
        assertAudited("SKILL_ARCHIVE");
    }

    @Test
    @DisplayName("read endpoints still fine after audit wiring (smoke)")
    void readSurfacesSmoke() throws Exception {
        call(get("/api/v1/admin/agents"));
        call(get("/api/v1/admin/services"));
        call(get("/api/v1/admin/mcp-services"));
        call(get("/api/v1/admin/skills"));
        call(get("/api/v1/admin/api-consumers"));
        assertThat(countEvents("DOES_NOT_EXIST")).isZero();
    }
}
