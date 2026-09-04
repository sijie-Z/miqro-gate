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
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP route rules (F11, Tencent doc 135482): the immutable system default
 * catch-all per service, custom rule CRUD with RE2/method/path validation,
 * per-service name uniqueness, real-time match-surface conflict checks and the
 * idempotent enable/disable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin MCP route rule API integration tests (PostgreSQL)")
class AdminMcpRouteRuleApiIntegrationTest {

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
    private UUID serviceId;

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
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"erp-mcp\",\"endpoint\":\"https://erp.internal.example\"}"))
                .andExpect(status().isOk()).andReturn();
        serviceId = UUID.fromString(
                objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : new String[]{"mcp_route_rule", "mcp_tools", "mcp_service_access", "mcp_services",
                "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    private Cookie cookie(MvcResult result, String name) {
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private String rulesUrl() {
        return "/api/v1/admin/mcp-services/" + serviceId + "/route-rules";
    }

    private String rule(String name, String extra) {
        return "{\"name\":\"" + name + "\"," + extra + "}";
    }

    @Test
    @DisplayName("route endpoints require authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get(rulesUrl())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("every service owns an immutable default catch-all rule")
    void defaultRuleAutoCreated() throws Exception {
        mockMvc.perform(get(rulesUrl()).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].name").value("default"))
                .andExpect(jsonPath("$[0].priority").value(0)).andExpect(jsonPath("$[0].status").value("ENABLED"))
                .andExpect(jsonPath("$[0].pathMode").value(org.hamcrest.Matchers.nullValue()));

        String defaultId = jdbc.queryForObject(
                "SELECT id FROM mcp_route_rule WHERE tenant_id = (SELECT id FROM tenants LIMIT 1) "
                        + "AND mcp_service_id = :serviceId",
                new MapSqlParameterSource("serviceId", serviceId), UUID.class).toString();

        // Immutable: update / disable / delete all rejected.
        mockMvc.perform(
                patch(rulesUrl() + "/" + defaultId).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(rule("renamed", "\"priority\":1000")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTE_DEFAULT_IMMUTABLE"));
        mockMvc.perform(post(rulesUrl() + "/" + defaultId + "/status?status=DISABLED").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_DEFAULT_IMMUTABLE"));
        mockMvc.perform(delete(rulesUrl() + "/" + defaultId).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token",
                csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_DEFAULT_IMMUTABLE"));
    }

    @Test
    @DisplayName("create/list with full matchers round-trips header conditions")
    void createAndList() throws Exception {
        String body = rule("gray-v2",
                "\"description\":\"灰度 v2\",\"priority\":1500,"
                        + "\"pathMode\":\"REGEX\",\"pathValue\":\"^/api/v[0-9]+$\","
                        + "\"hostMode\":\"PREFIX\",\"hostValue\":\"mcp-\",\"methods\":[\"GET\",\"POST\"],"
                        + "\"headers\":[{\"name\":\"X-Tenant-Id\",\"mode\":\"EXACT\",\"value\":\"acme\"},"
                        + "{\"name\":\"X-Canary\",\"mode\":\"EXACT\",\"value\":\"true\"}]}");
        MvcResult created = mockMvc
                .perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("gray-v2"))
                .andExpect(jsonPath("$.priority").value(1500)).andExpect(jsonPath("$.pathMode").value("REGEX"))
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.headerConditions[0].name").value("X-Tenant-Id")).andReturn();
        String ruleId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // List orders by priority desc: custom (1500) before default (0).
        mockMvc.perform(get(rulesUrl()).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].name").value("gray-v2"))
                .andExpect(jsonPath("$[1].name").value("default"));
        // jsonb round-trip preserved the full condition set.
        Integer headers = jdbc.queryForObject(
                "SELECT jsonb_array_length(header_conditions) FROM mcp_route_rule WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(ruleId)), Integer.class);
        org.assertj.core.api.Assertions.assertThat(headers).isEqualTo(2);
    }

    @Test
    @DisplayName("validation rejects reserved names, bad paths, methods, regexes and oversized header lists")
    void validation() throws Exception {
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(rule("default", "\"priority\":1000")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROUTE_NAME_RESERVED"));
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rule("no-slash", "\"pathMode\":\"EXACT\",\"pathValue\":\"api\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROUTE_PATH_INVALID"));
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rule("bad-regex", "\"pathMode\":\"REGEX\",\"pathValue\":\"^(unclosed\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROUTE_PATTERN_INVALID"));
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(rule("bad-method", "\"methods\":[\"TRACE\"]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROUTE_METHOD_INVALID"));
        String nineHeaders = "\"headers\":["
                + String.join(",",
                        java.util.Collections.nCopies(9, "{\"name\":\"X-H\",\"mode\":\"EXACT\",\"value\":\"1\"}"))
                + "]";
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(rule("too-many", nineHeaders)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROUTE_HEADERS_TOO_MANY"));
        // Same service duplicate name — the second rule uses a different
        // surface so the per-service name uniqueness is what fires (an
        // identical surface would report the match conflict first).
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rule("dup", "\"pathMode\":\"PREFIX\",\"pathValue\":\"/api\""))).andExpect(status().isOk());
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rule("dup", "\"pathMode\":\"PREFIX\",\"pathValue\":\"/api2\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTE_NAME_TAKEN"));
    }

    @Test
    @DisplayName("identical match surfaces conflict; distinct ones coexist; re-arming is re-checked")
    void conflicts() throws Exception {
        String a = rule("a", "\"pathMode\":\"EXACT\",\"pathValue\":\"/api\",\"methods\":[\"GET\"]");
        String twin = rule("b", "\"pathMode\":\"EXACT\",\"pathValue\":\"/api\",\"methods\":[\"GET\"]");
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(a)).andExpect(status().isOk());
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(twin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_MATCH_CONFLICT"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("a")));

        // A catch-all custom rule collides with the system default.
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(rule("catch-all", "\"methods\":[]")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTE_MATCH_CONFLICT"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("default")));

        // Different method whitelist → distinct surface → allowed.
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rule("c", "\"pathMode\":\"EXACT\",\"pathValue\":\"/api\",\"methods\":[\"POST\"]")))
                .andExpect(status().isOk());

        // Disable a, then create its twin while dormant, then re-arm fails.
        String ruleA = jdbc.queryForObject("SELECT id FROM mcp_route_rule WHERE mcp_service_id = :s AND name = 'a'",
                new MapSqlParameterSource("s", serviceId), UUID.class).toString();
        mockMvc.perform(post(rulesUrl() + "/" + ruleA + "/status?status=DISABLED").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(twin)).andExpect(status().isOk());
        // Re-arming the dormant duplicate is re-checked against the enabled twin.
        mockMvc.perform(post(rulesUrl() + "/" + ruleA + "/status?status=ENABLED").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_MATCH_CONFLICT"));
    }

    @Test
    @DisplayName("update replaces the whole match surface; delete removes; cascade clears on service delete")
    void updateAndDelete() throws Exception {
        String body = rule("gray", "\"pathMode\":\"PREFIX\",\"pathValue\":\"/api\"");
        MvcResult created = mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String ruleId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // Full replace: clear path, restrict to POST + one header.
        mockMvc.perform(patch(rulesUrl() + "/" + ruleId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content(rule("gray",
                        "\"methods\":[\"POST\"],"
                                + "\"headers\":[{\"name\":\"X-Tenant-Id\",\"mode\":\"EXACT\",\"value\":\"acme\"}]")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pathMode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.methods").value("POST"))
                .andExpect(jsonPath("$.headerConditions[0].name").value("X-Tenant-Id"));

        // Renaming onto an existing name is rejected; other service rules unaffected.
        mockMvc.perform(post(rulesUrl()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rule("other", "\"pathMode\":\"PREFIX\",\"pathValue\":\"/v2\""))).andExpect(status().isOk());
        mockMvc.perform(
                patch(rulesUrl() + "/" + ruleId).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(rule("other", "\"methods\":[\"POST\"]")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTE_NAME_TAKEN"));

        // Enable/disable is idempotent per the upstream doc.
        mockMvc.perform(post(rulesUrl() + "/" + ruleId + "/status?status=DISABLED").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
        mockMvc.perform(post(rulesUrl() + "/" + ruleId + "/status?status=DISABLED").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        mockMvc.perform(
                delete(rulesUrl() + "/" + ruleId).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(rulesUrl()).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))); // default + other

        // Deleting the service cascades its route rules.
        jdbc.update("DELETE FROM mcp_services WHERE id = :id", new MapSqlParameterSource("id", serviceId));
        Integer remaining = jdbc.queryForObject("SELECT count(*) FROM mcp_route_rule WHERE mcp_service_id = :id",
                new MapSqlParameterSource("id", serviceId), Integer.class);
        org.assertj.core.api.Assertions.assertThat(remaining).isZero();
    }
}
