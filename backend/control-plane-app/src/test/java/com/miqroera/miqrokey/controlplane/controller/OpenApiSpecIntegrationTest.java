package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

        // The admin user contract must never advertise the password hash: admin
        // endpoints serialize AdminUserView (domain User minus hash), so no
        // schema anywhere may mention it.
        assertThat(objectMapper.writeValueAsString(spec)).doesNotContain("passwordHash");

        // Same rule for the usage-deletion one-time token (api-contract §5.6):
        // only the create response may carry it, so neither the plaintext field
        // nor its persisted SHA-256 may be advertised in the machine-readable spec.
        assertThat(objectMapper.writeValueAsString(spec)).doesNotContain("confirmTokenHash");

        // Export for the CI breaking-change diff against the committed baseline.
        Path out = Path.of("target", "openapi-spec.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, result.getResponse().getContentAsString());
    }

    private void resetDb() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "usage_event", "price_snapshot", "quota_rules", "quota_default_template", "virtual_key_models",
                "key_project_binding", "model_approval", "virtual_keys", "project_provider_grant_models",
                "project_provider_grants", "unattributed_policy", "upstream_credential_versions",
                "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships",
                "project_repositories", "projects", "provider_products", "providers", "admin_audit_events",
                "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Child-first order above covers the canonical FK set.
            }
        }
    }

    /**
     * #1073: the console face and the machine-key face are twins of the same
     * resources writing through the same services, so the machine-readable contract
     * has to describe them identically. Endpoint tests cannot catch drift here —
     * each face compiles and validates on its own — while a client generated from
     * the spec would inherit the looser of the two.
     */
    @Test
    @DisplayName("#1073: twin schemas declare the same fields and constraints")
    void twinSchemasDeclareTheSameConstraints() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        JsonNode schemas = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("components")
                .path("schemas");
        assertTwinsAgree(schemas, "WebhookCreateRequest", "OpenAdminWebhookCreateRequest");
        assertTwinsAgree(schemas, "WebhookUpdateRequest", "OpenAdminWebhookUpdateRequest");
        assertTwinsAgree(schemas, "AlertRuleCreateRequest", "OpenAdminAlertRuleCreateRequest");
        assertTwinsAgree(schemas, "AlertRuleUpdateRequest", "OpenAdminAlertRuleUpdateRequest");
    }

    /**
     * The {@code tools/import} endpoint accepts an arbitrary OpenAPI document and
     * models it as {@link tools.jackson.databind.JsonNode} — a free-form JSON
     * value, which the committed baseline declares as {@code JsonNode: {}}.
     * springdoc instead reads that type as a bean and publishes Jackson's own
     * accessors ({@code array}, {@code empty}, {@code pojo}, {@code nodeType}, …)
     * as if they were request-body fields, so a client generated from the served
     * spec sends a document the endpoint cannot parse. Anything that is not
     * JsonNode (a DTO, a Map) must keep its fields, so the guard is scoped to the
     * free-form component.
     */
    @Test
    @DisplayName("free-form JSON bodies stay free-form: JsonNode is not introspected as a bean")
    void freeFormJsonBodiesAreNotIntrospected() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        JsonNode schemas = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("components")
                .path("schemas");

        JsonNode jsonNode = schemas.path("JsonNode");
        assertThat(jsonNode.isMissingNode()).as("JsonNode component (tools/import request body)").isFalse();
        Set<String> advertised = new TreeSet<>();
        jsonNode.path("properties").propertyNames().forEach(advertised::add);
        assertThat(advertised).as("JsonNode must stay free-form; the served spec advertises its Jackson accessors")
                .isEmpty();
    }

    /**
     * Compares required-fields and, per property, the constraint keywords — by
     * name, so a keyword present on one face and absent on the other fails rather
     * than passing a substring check.
     */
    private static void assertTwinsAgree(JsonNode schemas, String console, String machine) {
        JsonNode consoleSchema = schemas.path(console);
        JsonNode machineSchema = schemas.path(machine);
        assertThat(consoleSchema.isMissingNode()).as("console schema %s", console).isFalse();
        assertThat(machineSchema.isMissingNode()).as("machine schema %s", machine).isFalse();

        assertThat(sortedNames(machineSchema.path("required"))).as("%s vs %s: required", machine, console)
                .isEqualTo(sortedNames(consoleSchema.path("required")));

        Set<String> fields = new TreeSet<>();
        consoleSchema.path("properties").propertyNames().forEach(fields::add);
        Set<String> machineFields = new TreeSet<>();
        machineSchema.path("properties").propertyNames().forEach(machineFields::add);
        assertThat(machineFields).as("%s vs %s: field names", machine, console).isEqualTo(fields);

        for (String field : fields) {
            assertThat(constraintsOf(machineSchema.path("properties").path(field)))
                    .as("%s.%s constraints", machine, field)
                    .isEqualTo(constraintsOf(consoleSchema.path("properties").path(field)));
        }
    }

    private static List<String> sortedNames(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.asText()));
        names.sort(String::compareTo);
        return names;
    }

    private static Map<String, String> constraintsOf(JsonNode property) {
        Map<String, String> constraints = new TreeMap<>();
        for (String keyword : List.of("type", "minLength", "maxLength", "minimum", "maximum", "pattern")) {
            JsonNode value = property.path(keyword);
            if (!value.isMissingNode()) {
                constraints.put(keyword, value.asText());
            }
        }
        return constraints;
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
