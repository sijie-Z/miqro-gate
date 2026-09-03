package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 3.1 generation contract (F09, api-contract §8): the control plane
 * serves a machine-readable spec at /v3/api-docs that covers the management,
 * self-service and billing surface. The test also writes the spec to
 * {@code target/openapi-spec.json} so CI can diff it against the committed
 * baseline ({@code docs/openapi/openapi-3.1.json}) for breaking changes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("OpenAPI 3.1 spec generation integration tests (PostgreSQL)")
class OpenApiSpecIntegrationTest {

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

    @BeforeEach
    void setUp() throws Exception {
        resetDb();
        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                        "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() {
        resetDb();
    }

    @Test
    @DisplayName("serves an OpenAPI 3.1 spec covering the portal, management and billing surface")
    void servesOpenApi31Spec() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsByteArray());

        assertThat(spec.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(spec.path("info").path("title").asText()).contains("MiQroKey Gateway");
        assertThat(spec.path("paths").size()).isGreaterThan(30);

        // The authentication surface is modeled (components only — enforcement
        // stays at the interceptor level, so no operation is marked required).
        JsonNode schemes = spec.path("components").path("securitySchemes");
        assertThat(schemes.has("portalSession")).isTrue();
        assertThat(schemes.has("csrfToken")).isTrue();
        assertThat(schemes.has("apiKey")).isTrue();
        assertThat(schemes.has("consumerJwt")).isTrue();

        // Representative operations across every surface group.
        for (String[] entry : new String[][]{{"/api/v1/auth/login", "post"}, {"/api/v1/me/virtual-keys", "post"},
                {"/api/v1/me/virtual-keys", "get"}, {"/api/v1/me/quota-rules", "get"},
                {"/api/v1/admin/quota-rules", "put"}, {"/api/v1/admin/quota-default-template", "put"},
                {"/api/v1/admin/model-approvals", "get"}, {"/api/v1/billing/summary", "get"},
                {"/api/v1/billing/quota", "get"}}) {
            JsonNode op = spec.path("paths").path(entry[0]).path(entry[1]);
            assertThat(op.isMissingNode()).as("missing %s %s", entry[1], entry[0]).isFalse();
        }

        // Request and response bodies are modeled as named components.
        assertThat(spec.path("components").path("schemas").size()).isGreaterThan(20);

        // Export for the CI breaking-change diff against the committed baseline.
        Path out = Path.of("target", "openapi-spec.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, result.getResponse().getContentAsString());
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
