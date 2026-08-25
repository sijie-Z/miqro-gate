package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.crypto.CredentialFingerprint;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import jakarta.servlet.http.Cookie;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin upstream-credential API against real PostgreSQL (api-contract §5):
 * masked-in/masked-out secrets (plaintext never in responses, logs, or the
 * database), validation without writes, atomic rotate (old version drained
 * before the new ACTIVE lands), disable, tenant scoping, and the SYSTEM_ADMIN
 * role gate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin credential API integration tests (PostgreSQL)")
class AdminCredentialApiIntegrationTest {

    static final String SECRET = "sk-ant-test-secret-1234567890";
    static final String SECRET_2 = "sk-ant-test-secret-0987654321";

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
    @Autowired
    KeyEncryptionProvider keyEncryptionProvider;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
        fx.insertSubscription();
    }

    // ------------------------------------------------------------------
    // role gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("anonymous requests to /api/v1/admin/credentials are denied with 401")
    void anonymousIsDenied() throws Exception {
        mockMvc.perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"k\",\"subscriptionId\":\"" + fx.subscriptionId + "\",\"secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("USER role receives 403 for admin credential endpoints")
    void userRoleIsForbidden() throws Exception {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = :t",
                new MapSqlParameterSource("t", fx.tenantId), UUID.class);
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = :id", new MapSqlParameterSource("id", userId));

        mockMvc.perform(get("/api/v1/admin/credentials").cookie(sessionCookie)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create persists encrypted secret and fingerprint only; plaintext never returned")
    void createPersistsMaskedOnly() throws Exception {
        MvcResult r = mockMvc
                .perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "prod-key", "subscriptionId", fx.subscriptionId, "secret", SECRET))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.name").value("prod-key")).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET);

        Map<?, ?> view = objectMapper.readValue(body, Map.class);
        UUID credentialId = UUID.fromString((String) view.get("id"));
        assertThat((String) view.get("fingerprintPrefix"))
                .isEqualTo(CredentialFingerprint.hexPrefix(CredentialFingerprint.sha256(SECRET), 8));

        Map<String, Object> cred = row("SELECT * FROM upstream_credentials WHERE id = :id", credentialId);
        assertThat(Arrays.equals((byte[]) cred.get("secret_fingerprint"), CredentialFingerprint.sha256(SECRET)))
                .isTrue();
        assertThat(cred.get("status")).isEqualTo("ACTIVE");
        assertThat(cred.get("active_version_id")).isNotNull();

        Map<String, Object> ver = row(
                "SELECT * FROM upstream_credential_versions WHERE credential_id = :id AND status = 'ACTIVE'",
                credentialId);
        byte[] ciphertext = (byte[]) ver.get("encrypted_secret");
        assertThat(ciphertext).isNotEmpty();
        // the encrypted bytes must not equal the plaintext
        assertThat(Arrays.equals(ciphertext, SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat((String) ver.get("encryption_key_version")).isNotBlank();

        // AES-256-GCM round-trip proves the secret is recoverable by the
        // gateway injector path.
        byte[] plaintext = keyEncryptionProvider.decrypt(
                new EncryptedSecret(ciphertext, (byte[]) ver.get("nonce"), (String) ver.get("encryption_key_version")),
                fx.tenantId, credentialId);
        try {
            assertThat(new String(plaintext, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(SECRET);
        } finally {
            SecretWiping.clearArray(plaintext);
        }
    }

    @Test
    @DisplayName("create with an invalid secret returns 400 and writes nothing")
    void createWithInvalidSecretWritesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        Map.of("name", "k", "subscriptionId", fx.subscriptionId, "secret", "short"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CREDENTIAL_INVALID"));

        Long credentials = jdbc.queryForObject("SELECT COUNT(*) FROM upstream_credentials", new MapSqlParameterSource(),
                Long.class);
        Long versions = jdbc.queryForObject("SELECT COUNT(*) FROM upstream_credential_versions",
                new MapSqlParameterSource(), Long.class);
        assertThat(credentials).isZero();
        assertThat(versions).isZero();
    }

    @Test
    @DisplayName("create rejects a subscription from another tenant with 404")
    void createRejectsForeignTenantSubscription() throws Exception {
        mockMvc.perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        Map.of("name", "k", "subscriptionId", UUID.randomUUID(), "secret", SECRET))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // validate (never writes)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("validate compares the candidate secret without persisting anything")
    void validateMatchesActiveWithoutWrites() throws Exception {
        UUID credentialId = fx.createCredential(SECRET);

        mockMvc.perform(post("/api/v1/admin/credentials/{id}/validate", credentialId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(Map.of("secret", SECRET))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matchesActive").value(true));

        mockMvc.perform(post("/api/v1/admin/credentials/{id}/validate", credentialId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(Map.of("secret", SECRET_2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matchesActive").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());

        // malformed secret -> 400, still nothing written
        mockMvc.perform(post("/api/v1/admin/credentials/{id}/validate", credentialId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content("{\"secret\":\"short\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_INVALID"));

        Long versions = jdbc.queryForObject("SELECT COUNT(*) FROM upstream_credential_versions",
                new MapSqlParameterSource(), Long.class);
        assertThat(versions).isEqualTo(1L);
    }

    @Test
    @DisplayName("validate on a foreign tenant credential returns 404")
    void validateRejectsForeignTenantCredential() throws Exception {
        mockMvc.perform(post("/api/v1/admin/credentials/{id}/validate", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(Map.of("secret", SECRET))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // rotate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rotate switches the active version; old version drains and stays decryptable")
    void rotateSwitchesActiveVersionAtomically() throws Exception {
        UUID credentialId = fx.createCredential(SECRET);

        MvcResult r = mockMvc
                .perform(post("/api/v1/admin/credentials/{id}/rotate", credentialId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("secret", SECRET_2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.fingerprintPrefix")
                        .value(CredentialFingerprint.hexPrefix(CredentialFingerprint.sha256(SECRET_2), 8)))
                .andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET).doesNotContain(SECRET_2);

        // exactly one ACTIVE version, one DRAINING version
        List<Map<String, Object>> versions = rows(
                "SELECT * FROM upstream_credential_versions WHERE credential_id = :id ORDER BY created_at",
                credentialId);
        assertThat(versions).hasSize(2);
        Map<String, Object> active = versions.stream().filter(v -> "ACTIVE".equals(v.get("status"))).findFirst()
                .orElseThrow();
        Map<String, Object> drained = versions.stream().filter(v -> "DRAINING".equals(v.get("status"))).findFirst()
                .orElseThrow();
        assertThat(Arrays.equals((byte[]) active.get("secret_fingerprint"), CredentialFingerprint.sha256(SECRET_2)))
                .isTrue();
        assertThat(Arrays.equals((byte[]) drained.get("secret_fingerprint"), CredentialFingerprint.sha256(SECRET)))
                .isTrue();
        // PT0S drain grace: the drained version is retired effectively immediately
        assertThat(drained.get("retired_at")).isNotNull();

        Map<String, Object> cred = row("SELECT * FROM upstream_credentials WHERE id = :id", credentialId);
        assertThat(cred.get("active_version_id")).isEqualTo(active.get("id"));

        // both versions decrypt: in-flight requests that already loaded the old
        // secret complete; new requests use the new one
        assertThat(decrypt(active, credentialId)).isEqualTo(SECRET_2);
        assertThat(decrypt(drained, credentialId)).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("rotate with an invalid secret aborts and keeps the current version")
    void rotateWithInvalidSecretAborts() throws Exception {
        UUID credentialId = fx.createCredential(SECRET);

        mockMvc.perform(post("/api/v1/admin/credentials/{id}/rotate", credentialId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content("{\"secret\":\"short\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_INVALID"));

        Map<String, Object> cred = row("SELECT * FROM upstream_credentials WHERE id = :id", credentialId);
        assertThat(cred.get("status")).isEqualTo("ACTIVE");
        List<Map<String, Object>> versions = rows(
                "SELECT * FROM upstream_credential_versions WHERE credential_id = :id", credentialId);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat(
                Arrays.equals((byte[]) versions.get(0).get("secret_fingerprint"), CredentialFingerprint.sha256(SECRET)))
                .isTrue();
    }

    @Test
    @DisplayName("rotate on a disabled credential returns 409")
    void rotateOnDisabledCredentialIsRejected() throws Exception {
        UUID credentialId = fx.createCredential(SECRET);
        fx.disableCredential(credentialId);

        mockMvc.perform(post("/api/v1/admin/credentials/{id}/rotate", credentialId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(Map.of("secret", SECRET_2))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_ROTATABLE"));
    }

    // ------------------------------------------------------------------
    // disable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("disable drains the active version and marks the credential disabled")
    void disableDemotesAndDisables() throws Exception {
        UUID credentialId = fx.createCredential(SECRET);

        mockMvc.perform(post("/api/v1/admin/credentials/{id}/disable", credentialId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Credential disabled"));

        Map<String, Object> cred = row("SELECT * FROM upstream_credentials WHERE id = :id", credentialId);
        assertThat(cred.get("status")).isEqualTo("DISABLED");
        Map<String, Object> version = row(
                "SELECT * FROM upstream_credential_versions WHERE credential_id = :id AND status = 'DRAINING'",
                credentialId);
        assertThat(version.get("retired_at")).isNotNull();

        // second disable is a conflict
        mockMvc.perform(post("/api/v1/admin/credentials/{id}/disable", credentialId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_DISABLEABLE"));
    }

    // ------------------------------------------------------------------
    // list / detail / audit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("list and detail expose masked metadata with version history")
    void listAndDetailExposeMaskedMetadata() throws Exception {
        UUID credentialId = fx.createCredential(SECRET);
        mockMvc.perform(post("/api/v1/admin/credentials/{id}/rotate", credentialId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(Map.of("secret", SECRET_2))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/credentials").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(credentialId.toString()))
                .andExpect(jsonPath("$[0].fingerprintPrefix").isNotEmpty());

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/credentials/{id}", credentialId).cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn();
        String detailBody = detail.getResponse().getContentAsString();
        assertThat(detailBody).doesNotContain(SECRET).doesNotContain(SECRET_2);
        assertThat(detailBody).contains("\"DRAINING\"").contains("\"ACTIVE\"");
    }

    @Test
    @DisplayName("audit events record lifecycle transitions without plaintext")
    void auditEventsNeverContainPlaintext() throws Exception {
        UUID credentialId = fx.createCredential(SECRET);
        mockMvc.perform(post("/api/v1/admin/credentials/{id}/rotate", credentialId)
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(Map.of("secret", SECRET_2))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/credentials/{id}/disable", credentialId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        List<String> summaries = jdbc.queryForList(
                "SELECT change_summary::text FROM admin_audit_events WHERE target_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", credentialId), String.class);
        assertThat(summaries).hasSize(3);
        for (String summary : summaries) {
            assertThat(summary).doesNotContain(SECRET).doesNotContain(SECRET_2).doesNotContain("sk-ant");
        }
        List<String> actions = jdbc.queryForList(
                "SELECT action FROM admin_audit_events WHERE target_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", credentialId), String.class);
        assertThat(actions).containsExactly("CREDENTIAL_CREATE", "CREDENTIAL_ROTATE", "CREDENTIAL_DISABLE");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String decrypt(Map<String, Object> version, UUID credentialId) {
        byte[] plaintext = keyEncryptionProvider.decrypt(new EncryptedSecret((byte[]) version.get("encrypted_secret"),
                (byte[]) version.get("nonce"), (String) version.get("encryption_key_version")), fx.tenantId,
                credentialId);
        try {
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            SecretWiping.clearArray(plaintext);
        }
    }

    private Map<String, Object> row(String sql, UUID id) {
        return jdbc.queryForMap(sql, new MapSqlParameterSource("id", id));
    }

    private List<Map<String, Object>> rows(String sql, UUID id) {
        return jdbc.queryForList(sql, new MapSqlParameterSource("id", id));
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("usage_event", "cache_hit_event", "price_snapshot", "virtual_key_models",
                    "key_project_binding", "model_approval", "virtual_keys", "project_provider_grant_models",
                    "project_provider_grants", "upstream_credential_versions", "upstream_credentials", "plan_seats",
                    "upstream_subscriptions", "project_memberships", "projects", "provider_products", "providers",
                    "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertSubscription() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
        }

        UUID createCredential(String secret) throws Exception {
            MvcResult r = mockMvc
                    .perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON)
                            .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("name", "prod-key", "subscriptionId", subscriptionId, "secret", secret))))
                    .andExpect(status().isCreated()).andReturn();
            Map<?, ?> view = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
            return UUID.fromString((String) view.get("id"));
        }

        void disableCredential(UUID credentialId) throws Exception {
            mockMvc.perform(post("/api/v1/admin/credentials/{id}/disable", credentialId)
                    .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
        }
    }

    static class BootstrapHelper {
        private static final Path SECRET_FILE = Path.of(System.getProperty("java.io.tmpdir"),
                "miqrokey-bootstrap-test");
        private static final String SECRET = "bootstrap-secret-for-g16-tests";

        static {
            try {
                Files.writeString(SECRET_FILE, SECRET);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static Path secretFile() {
            return SECRET_FILE;
        }

        static String secret() {
            return SECRET;
        }
    }
}
