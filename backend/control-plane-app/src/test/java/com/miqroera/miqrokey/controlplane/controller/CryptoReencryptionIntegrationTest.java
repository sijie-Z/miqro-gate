package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.impl.AesGcmEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Master-key batch re-encryption (issue #432, security.md「后台分批重新加密旧密文」): rows
 * of all three ciphertext tables living on an older AES key version are
 * migrated onto the active version via
 * {@code POST /api/v1/admin/crypto/reencrypt}, verified by decrypting the
 * migrated ciphertext back to the original plaintext, idempotent on re-run,
 * isolating per-row failures, and audited without any secret material.
 *
 * <p>
 * The context runs a two-version key ring ({@code v1} + {@code v2}, active
 * {@code v2}) so seeds can be encrypted under v1 with the same key material.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@Import(CryptoReencryptionIntegrationTest.TwoVersionCrypto.class)
@DisplayName("Crypto key re-encryption integration tests (PostgreSQL)")
class CryptoReencryptionIntegrationTest {

    /**
     * Test-only two-version ring ({@code v1} + {@code v2}, active {@code v2}).
     *
     * <p>
     * Deliberately self-contained (keys and helper live <em>inside</em> this
     * class): test {@code @Configuration} classes in scanned packages are picked up
     * by sibling test contexts (the module-wide test crypto provider has always
     * come from exactly that mechanism). Referencing anything on the outer test
     * class from a {@code @Bean} factory would trigger the outer static initializer
     * — which starts the Testcontainers PostgreSQL — and break Docker-less runners
     * (the Windows unit CI job has no Docker; #434 CI failure). This class must
     * stay constructible without any Docker.
     * </p>
     */
    @TestConfiguration
    static class TwoVersionCrypto {

        static final byte[] V1_KEY = patternKey(0x40);
        static final byte[] V2_KEY = patternKey(0x80);

        static byte[] patternKey(int base) {
            byte[] key = new byte[32];
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (base + i);
            }
            return key;
        }

        @Bean
        @Primary
        KeyEncryptionProvider cryptoReencryptTestProvider() {
            return new AesGcmEncryptionProvider(new KeyRing("v2", Map.of("v1", V1_KEY.clone(), "v2", V2_KEY.clone())));
        }
    }

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // The webhook fixture uses a loopback URL (creation-time SSRF gate).
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    KeyEncryptionProvider keyEncryptionProvider;

    /** Encrypts with the OLD version — simulates pre-rotation ciphertext. */
    private final AesGcmEncryptionProvider v1Provider = new AesGcmEncryptionProvider(
            new KeyRing("v1", Map.of("v1", TwoVersionCrypto.V1_KEY.clone(), "v2", TwoVersionCrypto.V2_KEY.clone())));

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID tenantId;
    private UUID adminUserId;
    private final String adminUsername = "reenc_" + UUID.randomUUID().toString().substring(0, 8);
    private final UUID providerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("mcp_tool_revisions", "mcp_tools", "mcp_route_rule", "mcp_services",
                "webhook_endpoints", "upstream_credential_versions", "upstream_credentials", "upstream_subscriptions",
                "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Shared-container residue ordering; the seeded rows use fresh ids.
            }
        }
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), adminUsername, "Admin"))))
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
        Map<String, Object> admin = jdbc.queryForMap("SELECT id, tenant_id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", adminUsername));
        adminUserId = (UUID) admin.get("id");
        tenantId = (UUID) admin.get("tenant_id");
    }

    // ------------------------------------------------------------------
    // Seeding: build rows through the real APIs (active = v2), then swap the
    // ciphertext triple to the v1 form via the same AAD bindings.
    // ------------------------------------------------------------------

    private UUID seedUpstreamCredential(String secret, boolean corrupt) throws Exception {
        jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version)
                VALUES (:id, :slug, 'Reenc Provider', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", providerId).addValue("slug", "reenc-" + providerId));
        jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                VALUES (:productId, :providerId, :code, 'Reenc Product', 'PAYG', 'SINGLE_SHARED',
                        '["messages"]', '[{"url":"https://api.reenc.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId)
                .addValue("code", "reenc-" + productId));
        jdbc.update("""
                INSERT INTO upstream_subscriptions
                    (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                VALUES (:id, :tenantId, :productId, 'Reenc Sub', 'PAYG', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                .addValue("productId", productId));
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "reenc-cred", "subscriptionId", subscriptionId, "secret", secret))))
                .andExpect(status().isCreated()).andReturn();
        UUID credentialId = UUID
                .fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        UUID versionId = jdbc.queryForObject("SELECT id FROM upstream_credential_versions WHERE credential_id = :cid",
                new MapSqlParameterSource("cid", credentialId), UUID.class);
        EncryptedSecret v1 = v1Provider.encrypt(secret.getBytes(StandardCharsets.UTF_8), tenantId, credentialId);
        jdbc.update("""
                UPDATE upstream_credential_versions
                SET encrypted_secret = :ciphertext, nonce = :nonce, encryption_key_version = :version
                WHERE id = :id
                """, new MapSqlParameterSource("ciphertext", corrupted(v1.ciphertext(), corrupt))
                .addValue("nonce", v1.nonce()).addValue("version", v1.keyVersion()).addValue("id", versionId));
        return versionId;
    }

    private UUID seedWebhook(String secret) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name", "reenc-hook", "url",
                                "http://127.0.0.1:18089/reenc-hook", "secret", secret))))
                .andExpect(status().isOk()).andReturn();
        UUID endpointId = UUID
                .fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        EncryptedSecret v1 = v1Provider.encrypt(secret.getBytes(StandardCharsets.UTF_8), tenantId, endpointId);
        jdbc.update("""
                UPDATE webhook_endpoints
                SET secret_encrypted = :ciphertext, secret_nonce = :nonce, secret_key_version = :version
                WHERE id = :id
                """, new MapSqlParameterSource("ciphertext", v1.ciphertext()).addValue("nonce", v1.nonce())
                .addValue("version", v1.keyVersion()).addValue("id", endpointId));
        return endpointId;
    }

    private UUID seedMcpBackendSecret(String secret) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content("{\"name\":\"reenc-mcp\",\"endpoint\":\"https://mcp.reenc.example.com/mcp\"}"))
                .andExpect(status().isOk()).andReturn();
        UUID serviceId = UUID
                .fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(
                put("/api/v1/admin/mcp-services/" + serviceId + "/backend-auth").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"API_KEY\",\"secret\":\"" + secret + "\"}"))
                .andExpect(status().isOk());
        EncryptedSecret v1 = v1Provider.encrypt(secret.getBytes(StandardCharsets.UTF_8), tenantId, serviceId);
        jdbc.update("""
                UPDATE mcp_services
                SET backend_secret_ciphertext = :ciphertext, backend_secret_nonce = :nonce,
                    backend_secret_key_version = :version
                WHERE id = :id
                """, new MapSqlParameterSource("ciphertext", v1.ciphertext()).addValue("nonce", v1.nonce())
                .addValue("version", v1.keyVersion()).addValue("id", serviceId));
        return serviceId;
    }

    private static byte[] corrupted(byte[] ciphertext, boolean corrupt) {
        if (!corrupt) {
            return ciphertext;
        }
        byte[] broken = ciphertext.clone();
        broken[0] ^= 0xFF;
        return broken;
    }

    private MvcResult reencrypt() throws Exception {
        return mockMvc.perform(post("/api/v1/admin/crypto/reencrypt").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andReturn();
    }

    private String decrypt(UUID aadId, String version, byte[] ciphertext, byte[] nonce) {
        byte[] plain = keyEncryptionProvider.decrypt(new EncryptedSecret(ciphertext, nonce, version), tenantId, aadId);
        try {
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            SecretWiping.clearArray(plain);
        }
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

    // ------------------------------------------------------------------

    @Test
    @DisplayName("migrates all three ciphertext tables onto the active version; re-run is a no-op")
    void migratesAllTablesAndIsIdempotent() throws Exception {
        UUID credentialVersionId = seedUpstreamCredential("sk-reenc-credential-1", false);
        UUID endpointId = seedWebhook("reenc-webhook-secret-1");
        UUID serviceId = seedMcpBackendSecret("reenc-mcp-secret-1");

        MvcResult run = reencrypt();
        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        String body = run.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"activeKeyVersion\":\"v2\"").contains("\"scanned\":3").contains("\"reencrypted\":3")
                .contains("\"failed\":0").contains("\"remaining\":0").doesNotContain("sk-reenc-credential-1")
                .doesNotContain("reenc-webhook-secret-1").doesNotContain("reenc-mcp-secret-1");

        Map<String, Object> credRow = jdbc.queryForMap("""
                SELECT encrypted_secret, nonce, encryption_key_version FROM upstream_credential_versions
                WHERE id = :id
                """, new MapSqlParameterSource("id", credentialVersionId));
        assertThat(credRow.get("encryption_key_version")).isEqualTo("v2");
        assertThat(decrypt(
                UUID.fromString(
                        jdbc.queryForObject("SELECT credential_id FROM upstream_credential_versions WHERE id = :id",
                                new MapSqlParameterSource("id", credentialVersionId), UUID.class).toString()),
                (String) credRow.get("encryption_key_version"), (byte[]) credRow.get("encrypted_secret"),
                (byte[]) credRow.get("nonce"))).isEqualTo("sk-reenc-credential-1");

        Map<String, Object> hookRow = jdbc.queryForMap("""
                SELECT secret_encrypted, secret_nonce, secret_key_version FROM webhook_endpoints WHERE id = :id
                """, new MapSqlParameterSource("id", endpointId));
        assertThat(hookRow.get("secret_key_version")).isEqualTo("v2");
        assertThat(decrypt(endpointId, (String) hookRow.get("secret_key_version"),
                (byte[]) hookRow.get("secret_encrypted"), (byte[]) hookRow.get("secret_nonce")))
                .isEqualTo("reenc-webhook-secret-1");

        Map<String, Object> mcpRow = jdbc.queryForMap("""
                SELECT backend_secret_ciphertext, backend_secret_nonce, backend_secret_key_version
                FROM mcp_services WHERE id = :id
                """, new MapSqlParameterSource("id", serviceId));
        assertThat(mcpRow.get("backend_secret_key_version")).isEqualTo("v2");
        assertThat(decrypt(serviceId, (String) mcpRow.get("backend_secret_key_version"),
                (byte[]) mcpRow.get("backend_secret_ciphertext"), (byte[]) mcpRow.get("backend_secret_nonce")))
                .isEqualTo("reenc-mcp-secret-1");

        // Audited with counts only — never any secret material.
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_id, change_summary::text AS summary FROM admin_audit_events
                WHERE action = 'CRYPTO_REENCRYPT' ORDER BY chain_position DESC LIMIT 1
                """, new MapSqlParameterSource());
        assertThat(audit.get("actor_id")).isEqualTo(adminUserId);
        JsonNode summary = objectMapper.readTree((String) audit.get("summary"));
        assertThat(summary.get("activeVersion").asText()).isEqualTo("v2");
        assertThat(summary.get("reencrypted").asText()).isEqualTo("3");
        assertThat(summary.get("remaining").asText()).isEqualTo("0");
        assertThat((String) audit.get("summary")).doesNotContain("reenc-webhook-secret-1");

        // Idempotent: nothing left to migrate.
        MvcResult rerun = reencrypt();
        assertThat(rerun.getResponse().getStatus()).isEqualTo(200);
        assertThat(rerun.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"scanned\":0")
                .contains("\"reencrypted\":0").contains("\"remaining\":0");
    }

    @Test
    @DisplayName("a corrupt row fails in isolation; the rest migrate and are reported as remaining")
    void corruptRowFailsInIsolation() throws Exception {
        UUID corruptVersionId = seedUpstreamCredential("sk-reenc-corrupt", true);
        UUID endpointId = seedWebhook("reenc-webhook-secret-2");
        UUID serviceId = seedMcpBackendSecret("reenc-mcp-secret-2");

        MvcResult run = reencrypt();
        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        String body = run.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"reencrypted\":2").contains("\"failed\":1").contains("\"remaining\":1")
                .contains("\"table\":\"upstream_credential_versions\"").contains(corruptVersionId.toString());

        // The corrupt row stays on v1 (untouched); the other two are on v2.
        assertThat(jdbc.queryForObject("SELECT encryption_key_version FROM upstream_credential_versions WHERE id = :id",
                new MapSqlParameterSource("id", corruptVersionId), String.class)).isEqualTo("v1");
        assertThat(jdbc.queryForObject("SELECT secret_key_version FROM webhook_endpoints WHERE id = :id",
                new MapSqlParameterSource("id", endpointId), String.class)).isEqualTo("v2");
        assertThat(jdbc.queryForObject("SELECT backend_secret_key_version FROM mcp_services WHERE id = :id",
                new MapSqlParameterSource("id", serviceId), String.class)).isEqualTo("v2");
    }

    @Test
    @DisplayName("a caught-up deployment reports zero work without touching rows")
    void caughtUpDeploymentReportsZero() throws Exception {
        MvcResult run = reencrypt();
        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        assertThat(run.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"scanned\":0")
                .contains("\"reencrypted\":0").contains("\"failed\":0").contains("\"remaining\":0");
    }

    @Test
    @DisplayName("admin session and CSRF are required")
    void requiresAdminSessionAndCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/admin/crypto/reencrypt")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/crypto/reencrypt").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isForbidden());
    }
}
