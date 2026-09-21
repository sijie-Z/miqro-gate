package com.miqroera.miqrokey.controlplane.adversarial;

import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-stack JSON seam: every response that carries configuration must render
 * the <em>content</em> of the JSON, never a serializer's view of the node
 * object. The historical symptom is {@code "detail":{"empty":false,
 * "object":true,…}} — a node serialized through its bean properties.
 *
 * <p>
 * These tests assert concrete keys and values over real HTTP (the real filter
 * chain, real PostgreSQL — no mocks), on the three config surfaces named in the
 * taskbook: alert-rule {@code scopeJson}, MCP route-rule header conditions, and
 * the MCP tool/retry configuration. Every response body is additionally scanned
 * for the bean-leak signature, so a shape the key assertions do not know about
 * still fails loudly.
 * </p>
 */
@Tag("integration")
@DisplayName("Adversarial: JSON configuration renders node content, not node beans (PostgreSQL)")
class JsonSeamAdversarialIntegrationTest extends AbstractAdversarialIntegrationTest {

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(AdversarialTestSupport.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        String tempPassword = map(boot).get("temporaryPassword").toString();
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // alert-rule scopeJson
    // ------------------------------------------------------------------

    @Test
    @DisplayName("alert-rule scopeJson round-trips as the JSON object it was given, on create/get/list")
    void alertRuleScopeJsonIsRenderedAsContent() throws Exception {
        UUID projectId = createProject("ADVAL", "Adv Alerts");
        String body = """
                {"name":"adv-budget","type":"BUDGET_THRESHOLD","threshold":100.5,"dedupeMinutes":30,\
                "scopeJson":"{\\"projectId\\":\\"%s\\"}"}""".formatted(projectId);

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/alert-rules").cookie(adminSession, adminCsrf)
                        .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String createdRaw = created.getResponse().getContentAsString();
        assertNoNodeBeanLeak(createdRaw);
        JsonNode createdTree = objectMapper.readTree(createdRaw);
        UUID ruleId = UUID.fromString(createdTree.get("id").asText());
        assertScopeCarriesProject(createdTree, projectId);

        // GET by id and GET list must return the same content, not a re-serialized
        // node.
        MvcResult fetched = mockMvc.perform(get("/api/v1/admin/alert-rules/" + ruleId).cookie(adminSession))
                .andExpect(status().isOk()).andReturn();
        assertNoNodeBeanLeak(fetched.getResponse().getContentAsString());
        assertScopeCarriesProject(body(fetched), projectId);

        MvcResult list = mockMvc.perform(get("/api/v1/admin/alert-rules").cookie(adminSession))
                .andExpect(status().isOk()).andReturn();
        assertNoNodeBeanLeak(list.getResponse().getContentAsString());
        JsonNode listed = null;
        for (JsonNode node : body(list)) {
            if (ruleId.toString().equals(node.get("id").asText())) {
                listed = node;
            }
        }
        assertThat(listed).as("rule %s is in the list response", ruleId).isNotNull();
        assertScopeCarriesProject(listed, projectId);

        // Behaviour behind the seam: an unknown project in scopeJson is refused.
        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace(projectId.toString(), UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest());
    }

    private void assertScopeCarriesProject(JsonNode rule, UUID projectId) throws Exception {
        JsonNode scope = rule.get("scopeJson");
        assertThat(scope).as("scopeJson field is present").isNotNull();
        // The field is the JSON text itself; parse it and assert by key, not by
        // substring — a bean-serialized node has neither a projectId key nor JSON
        // syntax at all.
        JsonNode parsed = objectMapper.readTree(scope.asText());
        assertThat(parsed.get("projectId")).as("scopeJson content carries projectId").isNotNull();
        assertThat(parsed.get("projectId").asText()).isEqualTo(projectId.toString());
    }

    // ------------------------------------------------------------------
    // MCP route-rule header conditions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("route-rule header conditions render as name/mode/value on create and on list")
    void routeRuleHeaderConditionsRenderAsValues() throws Exception {
        UUID serviceId = createMcpService("adv-route-mcp");
        String body = """
                {"name":"adv-route","description":"adversarial","priority":900,"pathMode":"PREFIX",\
                "pathValue":"/erp","methods":["GET","POST"],"headers":[\
                {"name":"X-Tenant","mode":"EXACT","value":"acme"},\
                {"name":"X-Trace","mode":"PREFIX","value":"trc-"}]}""";

        MvcResult created = mockMvc.perform(
                post("/api/v1/admin/mcp-services/" + serviceId + "/route-rules").cookie(adminSession, adminCsrf)
                        .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String createdRaw = created.getResponse().getContentAsString();
        assertNoNodeBeanLeak(createdRaw);
        JsonNode createdTree = objectMapper.readTree(createdRaw);
        assertHeaderConditions(createdTree);
        assertThat(createdTree.get("matchExpression").asText()).contains("X-Tenant").contains("X-Trace");

        MvcResult list = mockMvc
                .perform(get("/api/v1/admin/mcp-services/" + serviceId + "/route-rules").cookie(adminSession))
                .andExpect(status().isOk()).andReturn();
        assertNoNodeBeanLeak(list.getResponse().getContentAsString());
        String ruleId = createdTree.get("id").asText();
        JsonNode listed = null;
        for (JsonNode node : body(list)) {
            if (ruleId.equals(node.get("id").asText())) {
                listed = node;
            }
        }
        assertThat(listed).as("route rule %s is in the list response", ruleId).isNotNull();
        assertHeaderConditions(listed);
    }

    private void assertHeaderConditions(JsonNode rule) {
        JsonNode conditions = rule.get("headerConditions");
        assertThat(conditions).as("headerConditions is an array").isNotNull();
        assertThat(conditions.isArray()).isTrue();
        assertThat(conditions.size()).isEqualTo(2);
        JsonNode tenant = null;
        JsonNode trace = null;
        for (JsonNode condition : conditions) {
            if ("X-Tenant".equals(condition.get("name").asText())) {
                tenant = condition;
            }
            if ("X-Trace".equals(condition.get("name").asText())) {
                trace = condition;
            }
        }
        assertThat(tenant).as("X-Tenant condition present").isNotNull();
        assertThat(tenant.get("mode").asText()).isEqualTo("EXACT");
        assertThat(tenant.get("value").asText()).isEqualTo("acme");
        assertThat(trace).as("X-Trace condition present").isNotNull();
        assertThat(trace.get("mode").asText()).isEqualTo("PREFIX");
        assertThat(trace.get("value").asText()).isEqualTo("trc-");
    }

    // ------------------------------------------------------------------
    // MCP tool configuration + retry override
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MCP tool create and its retry override round-trip concrete values")
    void mcpToolConfigRoundTrips() throws Exception {
        UUID serviceId = createMcpService("adv-tool-mcp");
        MvcResult created = mockMvc
                .perform(
                        post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(adminSession, adminCsrf)
                                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"toolName\":\"list_invoices\",\"description\":\"List invoices\","
                                        + "\"method\":\"GET\",\"path\":\"/invoices\"}"))
                .andExpect(status().isOk()).andReturn();
        String createdRaw = created.getResponse().getContentAsString();
        assertNoNodeBeanLeak(createdRaw);
        JsonNode tool = objectMapper.readTree(createdRaw);
        assertThat(tool.get("toolName").asText()).isEqualTo("list_invoices");
        assertThat(tool.get("method").asText()).isEqualTo("GET");
        assertThat(tool.get("path").asText()).isEqualTo("/invoices");
        assertThat(tool.get("status").asText()).isEqualTo("ENABLED");
        String toolId = tool.get("id").asText();

        String retryUrl = "/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/retry-policy";
        MvcResult configured = mockMvc.perform(put(retryUrl).cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"retryEnabled\":true,\"retryMax\":3,"
                        + "\"retryConditions\":[\"SERVER_5XX\",\"CONNECTION_FAILURE\"],"
                        + "\"idempotencyConfirmed\":true}"))
                .andExpect(status().isOk()).andReturn();
        assertNoNodeBeanLeak(configured.getResponse().getContentAsString());
        JsonNode configuredTree = body(configured);
        assertThat(configuredTree.get("retryEnabled").asBoolean()).isTrue();
        assertThat(configuredTree.get("retryMax").asInt()).isEqualTo(3);
        assertThat(configuredTree.get("idempotencyConfirmed").asBoolean()).isTrue();

        MvcResult fetched = mockMvc.perform(get(retryUrl).cookie(adminSession)).andExpect(status().isOk()).andReturn();
        assertNoNodeBeanLeak(fetched.getResponse().getContentAsString());
        JsonNode fetchedTree = body(fetched);
        assertThat(fetchedTree.get("retryEnabled").asBoolean()).isTrue();
        assertThat(fetchedTree.get("retryMax").asInt()).isEqualTo(3);
        assertThat(fetchedTree.get("idempotencyConfirmed").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID createProject(String code, String name) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/projects").cookie(adminSession, adminCsrf)
                        .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("code", code, "name", name, "projectTag", code.toLowerCase()))))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(body(created).get("id").asText());
    }

    private UUID createMcpService(String name) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(adminSession, adminCsrf)
                        .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"endpoint\":\"https://mcp.adv.example\"}"))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(body(created).get("id").asText());
    }

    /**
     * The historical leak signature: a JSON node serialized through its bean
     * properties produces keys no legit response here uses. Asserted on the raw
     * body so a nested occurrence the key assertions do not reach still fails.
     */
    private static void assertNoNodeBeanLeak(String rawJson) {
        assertThat(rawJson).as("response body carries no serializer bean-property leak").doesNotContain("\"empty\":")
                .doesNotContain("\"object\":true").doesNotContain("\"pojo\":");
    }
}
