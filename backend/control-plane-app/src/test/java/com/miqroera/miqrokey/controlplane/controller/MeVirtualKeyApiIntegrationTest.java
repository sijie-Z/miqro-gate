package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end Virtual Key lifecycle on PostgreSQL: create through the management
 * API, digest-only persistence, key-project binding, model snapshot, rotation
 * grace in the gateway route snapshot, and revoke.
 *
 * <p>
 * Verifies the closed loop: API create → DB rows → route snapshot loader sees
 * the new key with its binding, credential, and models.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Virtual Key API integration tests (PostgreSQL)")
class MeVirtualKeyApiIntegrationTest {

    static final String TAG = "core-ai";
    static final String MODEL_A = "claude-3-7-sonnet";
    static final String MODEL_B = "claude-3-5-haiku";

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // 5-minute rotation grace so the ROTATING key stays routable in tests.
        registry.add("miqrokey.virtual-key-rotate-grace", () -> "PT5M");
        registry.add("miqrokey.gateway-base-url", () -> "https://gateway.test.internal");
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
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = bootstrapAdmin();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        // Bootstrap admin is created with mustChangePassword=true; SessionFilter
        // 401s every path outside the password-change allowlist until the temp
        // password is rotated. Clear the gate so /me/** is reachable.
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create returns one-time secret and persists digest-only with binding and models")
    void createFullFlow() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);

        MvcResult r = postJson("/api/v1/me/virtual-keys",
                Map.of("name", "claude-code-main", "projectId", fx.projectId, "providerProductId", fx.productId,
                        "credentialGrantId", fx.grantId, "purpose", "CLAUDE_CODE", "allowedModels", List.of(MODEL_A)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shownOnce").value(true))
                .andExpect(jsonPath("$.baseUrl").value("https://gateway.test.internal")).andReturn();

        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        String secret = (String) body.get("secret");
        String id = (String) body.get("id");
        assertThat(secret).startsWith("mqk_live_").endsWith("." + TAG);

        // DB: digest only — the 32-byte HMAC digest is stored, and no column
        // that could carry plaintext material contains the secret string.
        String digestHex = jdbc.queryForObject("SELECT encode(secret_digest, 'hex') FROM virtual_keys WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(id)), String.class);
        assertThat(digestHex).hasSize(64);
        Integer plaintextLeaks = jdbc.queryForObject("""
                SELECT COUNT(*) FROM virtual_keys WHERE id = :id AND (
                    public_key_id LIKE '%' || :secret || '%'
                 OR display_prefix LIKE '%' || :secret || '%'
                 OR last_four LIKE '%' || :secret || '%')
                """, new MapSqlParameterSource("id", UUID.fromString(id)).addValue("secret", secret), Integer.class);
        assertThat(plaintextLeaks).isZero();

        // Binding is ACTIVE and points at the project.
        String boundProject = jdbc.queryForObject(
                "SELECT project_id FROM key_project_binding WHERE virtual_key_id = :id AND status = 'ACTIVE'",
                new MapSqlParameterSource("id", UUID.fromString(id)), String.class);
        assertThat(boundProject).isEqualTo(fx.projectId.toString());

        // Model authorization snapshot.
        List<String> models = jdbc.query(
                "SELECT model_id FROM virtual_key_models WHERE virtual_key_id = :id ORDER BY model_id",
                new MapSqlParameterSource("id", UUID.fromString(id)), (rs, i) -> rs.getString(1));
        assertThat(models).containsExactly(MODEL_A);

        // Route snapshot picks up the new key end to end.
        RouteSnapshot snapshot = snapshot();
        assertThat(snapshot.keys()).containsKey(secretPublicKeyId(secret));
        RouteSnapshot.BindingRecord binding = snapshot.bindings().get(UUID.fromString(id));
        assertThat(binding).isNotNull();
        assertThat(binding.projectTag()).isEqualTo(TAG);
        assertThat(snapshot.credentials()).containsKey(fx.credentialId);
        assertThat(snapshot.credentials().get(fx.credentialId).baseUrl()).isEqualTo("https://api.test.example");
        assertThat(snapshot.models(UUID.fromString(id))).containsExactly(MODEL_A);

        // GET list/detail expose safe metadata, never the secret.
        mockMvc.perform(get("/api/v1/me/virtual-keys").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].display").isNotEmpty()).andExpect(jsonPath("$[0].projectTag").value(TAG));
        mockMvc.perform(get("/api/v1/me/virtual-keys/" + id).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cachePolicy").value("DISABLED"));
    }

    @Test
    @DisplayName("create without project routing tag is rejected")
    void createRejectsMissingProjectTag() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(null);

        postJson("/api/v1/me/virtual-keys",
                Map.of("name", "k", "projectId", fx.projectId, "providerProductId", fx.productId, "credentialGrantId",
                        fx.grantId, "purpose", "CLAUDE_CODE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTING_TAG_MISSING"));
    }

    @Test
    @DisplayName("create with a model outside the grant is rejected")
    void createRejectsModelNotGranted() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);

        postJson("/api/v1/me/virtual-keys",
                Map.of("name", "k", "projectId", fx.projectId, "providerProductId", fx.productId, "credentialGrantId",
                        fx.grantId, "purpose", "CLAUDE_CODE", "allowedModels", List.of("gpt-4")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_NOT_GRANTED"));
    }

    @Test
    @DisplayName("create without allowedModels defaults to all granted models")
    void createDefaultsToAllGrantedModels() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);

        MvcResult r = postJson("/api/v1/me/virtual-keys", Map.of("name", "k", "projectId", fx.projectId,
                "providerProductId", fx.productId, "credentialGrantId", fx.grantId, "purpose", "CLAUDE_CODE"))
                .andExpect(status().isCreated()).andReturn();

        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        List<String> models = jdbc.query(
                "SELECT model_id FROM virtual_key_models WHERE virtual_key_id = :id ORDER BY model_id",
                new MapSqlParameterSource("id", UUID.fromString((String) body.get("id"))), (rs, i) -> rs.getString(1));
        assertThat(models).containsExactlyInAnyOrder(MODEL_A, MODEL_B);
    }

    // ------------------------------------------------------------------
    // rotate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rotate keeps old key routable during grace and routes the replacement")
    void rotateGraceWindow() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(TAG);

        MvcResult r = postJson("/api/v1/me/virtual-keys/" + keyId + "/rotate", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.shownOnce").value(true)).andReturn();
        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        UUID newKeyId = UUID.fromString((String) body.get("id"));

        // Old key: ROTATING with a future revoked_at and replaced_by set.
        MapSqlParameterSource oldP = new MapSqlParameterSource("id", keyId);
        assertThat(jdbc.queryForObject("SELECT status FROM virtual_keys WHERE id = :id", oldP, String.class))
                .isEqualTo("ROTATING");
        assertThat(jdbc.queryForObject("SELECT replaced_by_key_id FROM virtual_keys WHERE id = :id", oldP, UUID.class))
                .isEqualTo(newKeyId);
        assertThat(
                jdbc.queryForObject("SELECT revoked_at > now() FROM virtual_keys WHERE id = :id", oldP, Boolean.class))
                .isTrue();

        // Route snapshot still routes BOTH keys while the old one is in grace.
        RouteSnapshot snapshot = snapshot();
        assertThat(snapshot.keys().values()).extracting(RouteSnapshot.KeyRecord::publicKeyId)
                .contains(fx.publicKeyId(keyId), fx.publicKeyId(newKeyId));

        // Expire the grace window: the old key drops out of the snapshot.
        jdbc.update("UPDATE virtual_keys SET revoked_at = now() - interval '1 minute' WHERE id = :id", oldP);
        RouteSnapshot after = snapshot();
        assertThat(after.keys().values()).extracting(RouteSnapshot.KeyRecord::publicKeyId)
                .doesNotContain(fx.publicKeyId(keyId)).contains(fx.publicKeyId(newKeyId));
    }

    @Test
    @DisplayName("rotate rejects a revoked key")
    void rotateRejectsRevokedKey() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(TAG);
        postJson("/api/v1/me/virtual-keys/" + keyId + "/revoke", Map.of()).andExpect(status().isOk());

        postJson("/api/v1/me/virtual-keys/" + keyId + "/rotate", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_NOT_ROTATABLE"));
    }

    // ------------------------------------------------------------------
    // revoke
    // ------------------------------------------------------------------

    @Test
    @DisplayName("revoke drops the key from the route snapshot immediately")
    void revokeDropsFromSnapshot() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(TAG);

        postJson("/api/v1/me/virtual-keys/" + keyId + "/revoke", Map.of()).andExpect(status().isOk());

        MapSqlParameterSource p = new MapSqlParameterSource("id", keyId);
        assertThat(jdbc.queryForObject("SELECT status FROM virtual_keys WHERE id = :id", p, String.class))
                .isEqualTo("REVOKED");
        assertThat(
                jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM virtual_keys WHERE id = :id", p, Boolean.class))
                .isTrue();

        RouteSnapshot snapshot = snapshot();
        assertThat(snapshot.keys().values()).extracting(RouteSnapshot.KeyRecord::publicKeyId)
                .doesNotContain(fx.publicKeyId(keyId));

        // Second revoke is a conflict.
        postJson("/api/v1/me/virtual-keys/" + keyId + "/revoke", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_NOT_REVOCABLE"));
    }

    // ------------------------------------------------------------------
    // grants endpoint
    // ------------------------------------------------------------------

    @Test
    @DisplayName("grants endpoint lists project with tag and granted models")
    void grantsEndpoint() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);

        mockMvc.perform(get("/api/v1/me/grants").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].projectTag").value(TAG))
                .andExpect(jsonPath("$.projects[0].id").value(fx.projectId.toString()))
                // Models are served in lexicographic order (deterministic API).
                .andExpect(jsonPath("$.grants[0].models[0]").value(MODEL_B))
                .andExpect(jsonPath("$.grants[0].models[1]").value(MODEL_A))
                .andExpect(jsonPath("$.purposes[0]").isNotEmpty());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private MvcResult bootstrapAdmin() throws Exception {
        return mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
    }

    private UUID createKey(String tag) throws Exception {
        MvcResult r = postJson("/api/v1/me/virtual-keys",
                Map.of("name", "claude-code-main", "projectId", fx.projectId, "providerProductId", fx.productId,
                        "credentialGrantId", fx.grantId, "purpose", "CLAUDE_CODE", "allowedModels", List.of(MODEL_A)))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    private ResultActions postJson(String path, Object payload) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(payload)));
    }

    private RouteSnapshot snapshot() {
        return new JdbcRouteSnapshotLoader(jdbc, objectMapper).load(1L, Instant.now());
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    /**
     * Extracts the public key ID from a full display string
     * ({@code mqk_live_<22ch pkId>_<43ch secret>.<tag>}). The pkId length is fixed,
     * so substring extraction is safe even though the secret's base64url alphabet
     * contains underscores.
     */
    private static String secretPublicKeyId(String secret) {
        return secret.substring("mqk_live_".length(), "mqk_live_".length() + 22);
    }

    /** Direct JDBC fixtures: catalog, project, grant, credential. */
    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID adminId = UUID.randomUUID();

        void reset() {
            // Child-first FK order: virtual keys reference grants, credentials,
            // projects and users; grants reference credentials; etc.
            for (String table : List.of("virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                    "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships", "projects",
                    "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Tolerate migration variance; FK violations are caught by the
                    // ordering above in the canonical migration set.
                }
            }
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
            for (String model : List.of(MODEL_A, MODEL_B)) {
                jdbc.update("""
                        INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                        VALUES (:tenantId, :grantId, :model)
                        """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId)
                        .addValue("model", model));
            }
        }

        String publicKeyId(UUID keyId) {
            return jdbc.queryForObject("SELECT public_key_id FROM virtual_keys WHERE id = :id",
                    new MapSqlParameterSource("id", keyId), String.class);
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
