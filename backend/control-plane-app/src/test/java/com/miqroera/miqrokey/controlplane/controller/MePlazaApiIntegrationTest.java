package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model-plaza API integration tests (#1201): the usable set is the same
 * {@code key ∩ grant ∩ ACTIVE catalog} intersection the gateway applies, prices
 * come from the latest unit-price snapshots, and the approvable set is what the
 * model-approval flow can still extend.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Model plaza API integration tests (PostgreSQL)")
class MePlazaApiIntegrationTest {

    static final String TAG = "core-ai";
    static final String MODEL_A = "model-alpha";
    static final String MODEL_B = "model-beta";
    static final String MODEL_NEW = "model-gamma";

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.gateway-base-url", () -> "https://gateway.test.internal");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    PasswordHasher passwordHasher;

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    // ------------------------------------------------------------------
    // usable intersection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("models are key ∩ grant ∩ ACTIVE catalog, attributed to the keys that carry them")
    void usableIntersectionAcrossKeys() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        fx.catalogModel(MODEL_A, "Alpha");
        fx.catalogModel(MODEL_B, "Beta");
        fx.catalogModel(MODEL_NEW, "Gamma");
        fx.grantModel(MODEL_A);
        fx.grantModel(MODEL_B);
        UUID key1 = createKey("key-one", List.of(MODEL_A));
        UUID key2 = createKey("key-two", List.of(MODEL_A, MODEL_B));

        Map<?, ?> body = plaza();

        List<?> models = (List<?>) body.get("models");
        assertThat(models).hasSize(2);
        Map<?, ?> alpha = (Map<?, ?>) models.get(0);
        assertThat(alpha.get("modelId")).isEqualTo(MODEL_A);
        assertThat(alpha.get("displayName")).isEqualTo("Alpha");
        assertThat(alpha.get("providerProductCode")).isEqualTo("test-product");
        List<?> alphaKeys = (List<?>) alpha.get("keys");
        assertThat(alphaKeys).hasSize(2);
        assertThat(alphaKeys.stream().map(k -> (String) ((Map<?, ?>) k).get("name")).toList())
                .containsExactlyInAnyOrder("key-one", "key-two");
        Map<?, ?> firstRef = (Map<?, ?>) alphaKeys.get(0);
        assertThat(firstRef.get("id")).isIn(key1.toString(), key2.toString());
        assertThat((String) firstRef.get("display")).contains("…");
        Map<?, ?> beta = (Map<?, ?>) models.get(1);
        assertThat(beta.get("modelId")).isEqualTo(MODEL_B);
        assertThat(((List<?>) beta.get("keys")).stream().map(k -> (String) ((Map<?, ?>) k).get("name")).toList())
                .containsExactly("key-two");

        // The catalog model missing from a key is approvable on that key only.
        List<?> requestable = (List<?>) body.get("requestable");
        assertThat(requestable).hasSize(3);
        assertThat(requestable.stream().map(r -> (String) ((Map<?, ?>) r).get("modelId")).toList())
                .containsExactlyInAnyOrder(MODEL_B, MODEL_NEW, MODEL_NEW);
        assertThat(requestable.stream().filter(r -> MODEL_NEW.equals((String) ((Map<?, ?>) r).get("modelId")))
                .map(r -> (String) ((Map<?, ?>) r).get("keyName")).toList())
                .containsExactlyInAnyOrder("key-one", "key-two");
        Map<?, ?> newRow = (Map<?, ?>) requestable.stream()
                .filter(r -> MODEL_NEW.equals((String) ((Map<?, ?>) r).get("modelId"))).findFirst().orElseThrow();
        assertThat(((Map<?, ?>) newRow).get("displayName")).isEqualTo("Gamma");
    }

    @Test
    @DisplayName("a key without an ACTIVE grant contributes nothing")
    void inactiveGrantSuppressesEverything() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        fx.catalogModel(MODEL_A, "Alpha");
        fx.grantModel(MODEL_A);
        createKey("key-one", List.of(MODEL_A));

        assertThat((List<?>) plaza().get("models")).hasSize(1);

        jdbc.update("UPDATE project_provider_grants SET status = 'DISABLED' WHERE id = :id",
                new MapSqlParameterSource("id", fx.grantId));
        Map<?, ?> body = plaza();
        assertThat((List<?>) body.get("models")).isEmpty();
        assertThat((List<?>) body.get("requestable")).isEmpty();
    }

    @Test
    @DisplayName("disabled keys drop out: their models and their approvable rows")
    void disabledKeyDropsOut() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        fx.catalogModel(MODEL_A, "Alpha");
        fx.catalogModel(MODEL_NEW, "Gamma");
        fx.grantModel(MODEL_A);
        createKey("key-one", List.of(MODEL_A));
        UUID key2 = createKey("key-two", List.of(MODEL_A));

        postJson("/api/v1/me/virtual-keys/" + key2 + "/disable", Map.of()).andExpect(status().isOk());

        Map<?, ?> body = plaza();
        List<?> models = (List<?>) body.get("models");
        assertThat(models).hasSize(1);
        assertThat(((List<?>) ((Map<?, ?>) models.get(0)).get("keys"))).hasSize(1);
        assertThat((List<?>) body.get("requestable")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) body.get("requestable")).get(0)).get("keyName")).isEqualTo("key-one");
    }

    @Test
    @DisplayName("catalog is the third gate: a DISABLED catalog row is neither usable nor approvable")
    void catalogGateApplies() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        fx.catalogModel(MODEL_A, "Alpha");
        fx.catalogModel(MODEL_B, "Beta");
        fx.grantModel(MODEL_A);
        fx.grantModel(MODEL_B);
        createKey("key-one", List.of(MODEL_A, MODEL_B));

        jdbc.update("UPDATE model_catalog SET status = 'DISABLED' WHERE provider_product_id = :p AND model_id = :m",
                new MapSqlParameterSource("p", fx.productId).addValue("m", MODEL_B));

        Map<?, ?> body = plaza();
        assertThat(((List<?>) body.get("models")).stream().map(m -> (String) ((Map<?, ?>) m).get("modelId")).toList())
                .containsExactly(MODEL_A);
        assertThat((List<?>) body.get("requestable")).isEmpty();
    }

    // ------------------------------------------------------------------
    // prices
    // ------------------------------------------------------------------

    @Test
    @DisplayName("prices are the latest snapshot per token type; absent token types stay null")
    void pricesAttachLatestPerTokenType() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        fx.catalogModel(MODEL_A, "Alpha");
        fx.catalogModel(MODEL_B, "Beta");
        fx.grantModel(MODEL_A);
        fx.grantModel(MODEL_B);
        createKey("key-one", List.of(MODEL_A, MODEL_B));
        fx.price(MODEL_A, "INPUT", "1.0", Instant.parse("2026-01-01T00:00:00Z"));
        fx.price(MODEL_A, "INPUT", "2.0", Instant.parse("2026-06-01T00:00:00Z"));
        fx.price(MODEL_A, "OUTPUT", "8.0", Instant.parse("2026-06-01T00:00:00Z"));

        Map<?, ?> body = plaza();
        List<?> models = (List<?>) body.get("models");
        Map<?, ?> alphaPrice = (Map<?, ?>) ((Map<?, ?>) models.get(0)).get("price");
        assertThat(new BigDecimal(String.valueOf(alphaPrice.get("inputPerMillion"))))
                .isEqualByComparingTo(new BigDecimal("2.0"));
        assertThat(new BigDecimal(String.valueOf(alphaPrice.get("outputPerMillion"))))
                .isEqualByComparingTo(new BigDecimal("8.0"));
        assertThat(alphaPrice.get("cacheReadPerMillion")).isNull();
        assertThat(alphaPrice.get("currency")).isEqualTo("USD");

        // No snapshot at all: undefined price, never a zero placeholder (#878).
        assertThat(((Map<?, ?>) models.get(1)).get("price")).isNull();
    }

    // ------------------------------------------------------------------
    // scope & auth
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a user without keys sees an empty plaza; other users' keys never leak")
    void emptyForUserWithoutKeys() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        fx.catalogModel(MODEL_A, "Alpha");
        fx.grantModel(MODEL_A);
        createKey("admin-key", List.of(MODEL_A));

        fx.insertRegularUser("NewSecurePass1!");
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("regular_user", "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        Cookie userSession = cookie(login, "MIQROKEY_SESSION");

        MvcResult r = mockMvc.perform(get("/api/v1/me/plaza/models").cookie(userSession)).andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        assertThat((List<?>) body.get("models")).isEmpty();
        assertThat((List<?>) body.get("requestable")).isEmpty();

        mockMvc.perform(get("/api/v1/me/plaza/models")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Map<?, ?> plaza() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/me/plaza/models").cookie(adminSession)).andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    private UUID createKey(String name, List<String> models) throws Exception {
        MvcResult r = postJson("/api/v1/me/virtual-keys",
                Map.of("name", name, "projectId", fx.projectId.toString(), "providerProductId", fx.productId.toString(),
                        "credentialGrantId", fx.grantId.toString(), "purpose", "CLAUDE_CODE", "allowedModels", models))
                .andExpect(status().isCreated()).andReturn();
        return UUID
                .fromString((String) objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("id"));
    }

    private ResultActions postJson(String path, Object payload) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).content(objectMapper.writeValueAsString(payload)));
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    /** Direct JDBC fixtures: catalog, project, grant, credential, prices. */
    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID adminId = UUID.randomUUID();
        final UUID userId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                    "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                    "project_memberships", "project_repositories", "projects", "price_snapshot", "model_catalog",
                    "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Child-first order above covers the canonical FK set.
                }
            }
        }

        void insertRegularUser(String password) {
            MapSqlParameterSource p = new MapSqlParameterSource("id", userId).addValue("tenantId", tenantId)
                    .addValue("hash", passwordHasher.hash(password));
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:id, :tenantId, 'regular_user', 'Regular', :hash, 'USER', 'ACTIVE', FALSE, 0)
                    """, p);
        }

        void insertProviderCatalog() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("providerId", providerId).addValue("productId", productId);
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:providerId, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, p);
        }

        void catalogModel(String modelId, String displayName) {
            jdbc.update("""
                    INSERT INTO model_catalog (id, provider_product_id, model_id, display_name, status, version)
                    VALUES (:id, :productId, :modelId, :displayName, 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("modelId", modelId).addValue("displayName", displayName));
        }

        void insertProjectWithGrant(String tag) {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("tenantId", tenantId).addValue("projectId", projectId).addValue("subscriptionId", subscriptionId)
                    .addValue("credentialId", credentialId).addValue("grantId", grantId)
                    .addValue("productId", productId).addValue("tag", tag);
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Project One', 'ACTIVE', :tag, 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:subscriptionId, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:credentialId, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE', :adminId, 0)
                    """, p.addValue("adminId", adminId));
        }

        void grantModel(String modelId) {
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, :modelId)
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId)
                    .addValue("modelId", modelId));
        }

        void price(String modelId, String tokenType, String unitPrice, Instant effectiveFrom) {
            jdbc.update("""
                    INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type, currency, unit_price,
                                                effective_from, source)
                    VALUES (:id, :productId, :modelId, :tokenType, 'USD', :unitPrice, :effectiveFrom, 'MANUAL')
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                            .addValue("modelId", modelId).addValue("tokenType", tokenType)
                            .addValue("unitPrice", new BigDecimal(unitPrice))
                            .addValue("effectiveFrom", java.sql.Timestamp.from(effectiveFrom)));
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
