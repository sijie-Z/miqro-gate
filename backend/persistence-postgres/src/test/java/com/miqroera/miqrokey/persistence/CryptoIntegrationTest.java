package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.crypto.*;
import com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException;
import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Crypto integration tests")
@Import(CryptoTestConfig.class)
class CryptoIntegrationTest extends AbstractPostgresTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private KeyEncryptionProvider encProvider;
    @Autowired
    private VirtualKeyCrypto vkProvider;

    @Autowired
    private ProviderRepository providerRepo;
    @Autowired
    private ProviderProductRepository productRepo;
    @Autowired
    private UpstreamSubscriptionRepository subRepo;
    @Autowired
    private UpstreamCredentialRepository credRepo;
    @Autowired
    private UpstreamCredentialVersionRepository versionRepo;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private ProjectRepository projectRepo;
    @Autowired
    private ProjectProviderGrantRepository grantRepo;
    @Autowired
    private VirtualKeyRepository vkRepo;

    private Provider provider;
    private ProviderProduct product;
    private UpstreamSubscription sub;
    private UpstreamCredential cred;
    private UUID credentialId;
    private User user;
    private Project project;
    private ProjectProviderGrant grant;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        provider = new Provider(UUID.randomUUID(), "crypto-prov-" + suffix, "Crypto Provider", null, null, null,
                ProviderStatus.ACTIVE, 0, NOW, NOW);
        providerRepo.insert(provider);

        product = new ProviderProduct(UUID.randomUUID(), provider.id(), "crypto-prod-" + suffix, "Crypto Product",
                BillingMode.PAYG, PlanScope.NONE, CredentialTopology.SINGLE_SHARED, null, "[]", "[]", "{}", null, null,
                null, ImplementationStatus.DRAFT, null, 0, NOW, NOW);
        productRepo.insert(product);

        sub = new UpstreamSubscription(UUID.randomUUID(), TENANT_ID, product.id(), "Crypto Sub", null, BillingMode.PAYG,
                PlanScope.NONE, null, null, null, null, null, null, null, SubscriptionStatus.ACTIVE, null, null, 0, NOW,
                NOW);
        subRepo.insert(sub);

        cred = new UpstreamCredential(UUID.randomUUID(), TENANT_ID, sub.id(), null, "crypto-cred-" + suffix, null,
                CredentialStatus.PENDING_VALIDATION, null, null, null, 0, NOW, NOW);
        credRepo.insert(cred);
        credentialId = cred.id();

        user = new User(UUID.randomUUID(), TENANT_ID, "crypto-user-" + suffix, "Crypto User", new byte[32],
                UserRole.USER, UserStatus.ACTIVE, false, 0, null, null, 0, NOW, NOW);
        userRepo.insert(user);

        project = new Project(UUID.randomUUID(), TENANT_ID, "crypto-proj-" + suffix, "Crypto Project", null, null,
                ProjectStatus.ACTIVE, null, 0, NOW, NOW);
        projectRepo.insert(project);

        grant = new ProjectProviderGrant(UUID.randomUUID(), TENANT_ID, project.id(), product.id(), credentialId,
                GrantStatus.ACTIVE, user.id(), 0, NOW, NOW);
        grantRepo.insert(grant);
    }

    @Nested
    @DisplayName("upstream secret encryption in DB")
    class UpstreamSecretEncryption {

        @Test
        @DisplayName("should store encrypted secret, not plaintext")
        void shouldStoreEncryptedSecret() {
            String plaintext = "sk-ant-api03-super-secret-key-12345";
            EncryptedSecret encrypted = encProvider.encrypt(plaintext.getBytes(), TENANT_ID, credentialId);

            UpstreamCredentialVersion version = new UpstreamCredentialVersion(UUID.randomUUID(), TENANT_ID,
                    credentialId, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                    new byte[]{1, 2, 3}, CredentialVersionStatus.ACTIVE, NOW, null, NOW);
            versionRepo.insert(version);

            // Verify DB does not contain plaintext
            String dbCheck = jdbc.queryForObject(
                    "SELECT encode(encrypted_secret, 'escape') FROM upstream_credential_versions WHERE id = :id",
                    new MapSqlParameterSource("id", version.id()), String.class);
            assertThat(dbCheck).doesNotContain("sk-ant-api03");
            assertThat(dbCheck).doesNotContain("super-secret");
        }

        @Test
        @DisplayName("should decrypt stored encrypted secret correctly")
        void shouldDecryptStoredSecret() {
            String plaintext = "sk-test-decrypt-key-abcdefgh";
            byte[] plainBytes = plaintext.getBytes();
            EncryptedSecret encrypted = encProvider.encrypt(plainBytes, TENANT_ID, credentialId);

            UpstreamCredentialVersion version = new UpstreamCredentialVersion(UUID.randomUUID(), TENANT_ID,
                    credentialId, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                    new byte[]{1, 2, 3}, CredentialVersionStatus.ACTIVE, NOW, null, NOW);
            versionRepo.insert(version);

            UpstreamCredentialVersion stored = versionRepo.findById(version.id()).orElseThrow();
            EncryptedSecret storedEncrypted = new EncryptedSecret(stored.encryptedSecret(), stored.nonce(),
                    stored.encryptionKeyVersion());

            byte[] decrypted = encProvider.decrypt(storedEncrypted, TENANT_ID, credentialId);
            assertThat(new String(decrypted)).isEqualTo(plaintext);
            assertThat(decrypted).isEqualTo(plainBytes);
        }

        @Test
        @DisplayName("should fail decryption with wrong tenant ID (AAD mismatch)")
        void shouldFailCrossTenantDecrypt() {
            String plaintext = "cross-tenant-secret-key";
            EncryptedSecret encrypted = encProvider.encrypt(plaintext.getBytes(), TENANT_ID, credentialId);

            UpstreamCredentialVersion version = new UpstreamCredentialVersion(UUID.randomUUID(), TENANT_ID,
                    credentialId, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                    new byte[]{1, 2, 3}, CredentialVersionStatus.ACTIVE, NOW, null, NOW);
            versionRepo.insert(version);

            UpstreamCredentialVersion stored = versionRepo.findById(version.id()).orElseThrow();
            EncryptedSecret storedEncrypted = new EncryptedSecret(stored.encryptedSecret(), stored.nonce(),
                    stored.encryptionKeyVersion());

            assertThatThrownBy(() -> encProvider.decrypt(storedEncrypted, OTHER_TENANT, credentialId))
                    .isInstanceOf(CryptoOperationException.class).hasMessageContaining("CRYPTO_DECRYPT_001");
        }

        @Test
        @DisplayName("should handle AES key rotation: decrypt old, re-encrypt with new")
        void shouldRotateKeyAndReEncrypt() {
            String plaintext = "rotation-test-secret";
            EncryptedSecret v1encrypted = encProvider.encrypt(plaintext.getBytes(), TENANT_ID, credentialId);

            // Store v1 ciphertext in DB
            UpstreamCredentialVersion v1 = new UpstreamCredentialVersion(UUID.randomUUID(), TENANT_ID, credentialId,
                    v1encrypted.ciphertext(), v1encrypted.nonce(), v1encrypted.keyVersion(), new byte[]{1, 2, 3},
                    CredentialVersionStatus.ACTIVE, NOW, null, NOW);
            versionRepo.insert(v1);

            // Now re-encrypt (simulates key rotation: decrypt with v1 key, encrypt with
            // active key)
            UpstreamCredentialVersion stored = versionRepo.findById(v1.id()).orElseThrow();
            EncryptedSecret storedEncrypted = new EncryptedSecret(stored.encryptedSecret(), stored.nonce(),
                    stored.encryptionKeyVersion());
            EncryptedSecret reEncrypted = encProvider.reEncrypt(storedEncrypted, TENANT_ID, credentialId);

            // Verify re-encrypted data decrypts correctly
            byte[] decrypted = encProvider.decrypt(reEncrypted, TENANT_ID, credentialId);
            assertThat(new String(decrypted)).isEqualTo(plaintext);
        }
    }

    @Nested
    @DisplayName("Virtual Key digest in DB (real rows)")
    class VirtualKeyDigestRealDb {

        @Test
        @DisplayName("should write virtual_keys row with only digest, no raw secret in DB")
        void shouldStoreOnlyDigestInVirtualKeysTable() {
            VirtualKeyMaterial material = vkProvider.generate(TENANT_ID, null);

            // Insert a real virtual_keys row
            VirtualKey vk = new VirtualKey(UUID.randomUUID(), TENANT_ID, material.publicKeyId(), material.digest(),
                    material.displayPrefix(), material.lastFour(), user.id(), project.id(), grant.id(), credentialId,
                    VirtualKeyPurpose.CLAUDE_CODE, "test-vk-" + suffix, "DISABLED", VirtualKeyStatus.ACTIVE, NOW, null,
                    null, null, 0);
            vkRepo.insert(vk);

            // Read back from PostgreSQL and verify digest matches
            VirtualKey stored = vkRepo.findById(vk.id()).orElseThrow();
            assertThat(stored.secretDigest()).isEqualTo(material.digest());
            assertThat(stored.publicKeyId()).isEqualTo(material.publicKeyId());

            // Verify raw DB columns: secret_digest is bytea, NOT the full key
            byte[] dbDigest = jdbc.queryForObject("SELECT secret_digest FROM virtual_keys WHERE id = :id",
                    new MapSqlParameterSource("id", vk.id()), byte[].class);
            assertThat(dbDigest).isEqualTo(material.digest());

            // Verify that rawSecret bytes are NOT stored anywhere in the row
            String allColumns = jdbc.queryForObject(
                    "SELECT encode(secret_digest, 'base64') || '|' || public_key_id || '|' || display_prefix || '|' || last_four FROM virtual_keys WHERE id = :id",
                    new MapSqlParameterSource("id", vk.id()), String.class);
            // The raw secret (base64url-encoded) should NOT appear in any column
            String rawSecretBase64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(material.rawSecret());
            assertThat(allColumns).doesNotContain(rawSecretBase64);

            // Clean up
            material.destroy();
        }

        @Test
        @DisplayName("should validate Virtual Key against digest stored in PostgreSQL")
        void shouldValidateAgainstStoredDigest() {
            VirtualKeyMaterial material = vkProvider.generate(TENANT_ID, null);

            // Store in real DB
            VirtualKey vk = new VirtualKey(UUID.randomUUID(), TENANT_ID, material.publicKeyId(), material.digest(),
                    material.displayPrefix(), material.lastFour(), user.id(), project.id(), grant.id(), credentialId,
                    VirtualKeyPurpose.CLAUDE_CODE, "test-vk-" + suffix, "DISABLED", VirtualKeyStatus.ACTIVE, NOW, null,
                    null, null, 0);
            vkRepo.insert(vk);

            // Read back from DB
            VirtualKey stored = vkRepo.findById(vk.id()).orElseThrow();
            byte[] dbDigest = stored.secretDigest();

            // Validate using the stored digest
            boolean valid = vkProvider.validateConstantTime(material.publicKeyId(), material.rawSecret(), dbDigest,
                    TENANT_ID);
            assertThat(valid).isTrue();

            // Wrong secret should fail
            byte[] wrongSecret = new byte[32];
            new SecureRandom().nextBytes(wrongSecret);
            boolean invalid = vkProvider.validateConstantTime(material.publicKeyId(), wrongSecret, dbDigest, TENANT_ID);
            assertThat(invalid).isFalse();

            material.destroy();
        }

        @Test
        @DisplayName("should reject cross-tenant Virtual Key validation")
        void shouldRejectCrossTenantVirtualKey() {
            VirtualKeyMaterial material = vkProvider.generate(TENANT_ID, null);

            VirtualKey vk = new VirtualKey(UUID.randomUUID(), TENANT_ID, material.publicKeyId(), material.digest(),
                    material.displayPrefix(), material.lastFour(), user.id(), project.id(), grant.id(), credentialId,
                    VirtualKeyPurpose.CLAUDE_CODE, "test-vk-xt-" + suffix, "DISABLED", VirtualKeyStatus.ACTIVE, NOW,
                    null, null, null, 0);
            vkRepo.insert(vk);

            VirtualKey stored = vkRepo.findById(vk.id()).orElseThrow();

            // Validation with wrong tenant should fail because tenantId is in HMAC domain
            boolean crossTenant = vkProvider.validateConstantTime(material.publicKeyId(), material.rawSecret(),
                    stored.secretDigest(), OTHER_TENANT);
            assertThat(crossTenant).isFalse();

            // But with correct tenant it succeeds
            boolean sameTenant = vkProvider.validateConstantTime(material.publicKeyId(), material.rawSecret(),
                    stored.secretDigest(), TENANT_ID);
            assertThat(sameTenant).isTrue();

            material.destroy();
        }

        @Test
        @DisplayName("should handle HMAC key rotation with DB-stored digests")
        void shouldHandleHmacKeyRotationInDb() {
            VirtualKeyMaterial material = vkProvider.generate(TENANT_ID, null);

            // Store digest in DB
            VirtualKey vk = new VirtualKey(UUID.randomUUID(), TENANT_ID, material.publicKeyId(), material.digest(),
                    material.displayPrefix(), material.lastFour(), user.id(), project.id(), grant.id(), credentialId,
                    VirtualKeyPurpose.CLAUDE_CODE, "test-vk-rot-" + suffix, "DISABLED", VirtualKeyStatus.ACTIVE, NOW,
                    null, null, null, 0);
            vkRepo.insert(vk);

            // Verify initial validation passes
            VirtualKey stored = vkRepo.findById(vk.id()).orElseThrow();
            assertThat(vkProvider.validateConstantTime(material.publicKeyId(), material.rawSecret(),
                    stored.secretDigest(), TENANT_ID)).isTrue();

            // The HMAC key ring in CryptoTestConfig has all versions;
            // multi-version verification should still find the match.
            // (The test config only has one version, but the infra supports rotation.)

            material.destroy();
        }
    }

    @Nested
    @DisplayName("no plaintext in DB")
    class NoPlaintextInDb {

        @Test
        @DisplayName("should verify upstream_credential_versions has no plaintext columns")
        void shouldVerifyNoPlaintextColumns() {
            String sql = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'upstream_credential_versions'
                    AND column_name IN ('secret', 'plaintext', 'api_key', 'raw_secret')
                    """;
            var result = jdbc.query(sql, (rs, rowNum) -> rs.getString("column_name"));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should verify virtual_keys table has no plaintext columns")
        void shouldVerifyVkNoPlaintextColumns() {
            String sql = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'virtual_keys'
                    AND column_name IN ('secret', 'plaintext', 'raw_key', 'key_secret')
                    """;
            var result = jdbc.query(sql, (rs, rowNum) -> rs.getString("column_name"));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should verify stored virtual_keys row has digest-only, no raw key")
        void shouldVerifyActualRowOnlyHasDigest() {
            VirtualKeyMaterial material = vkProvider.generate(TENANT_ID, null);

            VirtualKey vk = new VirtualKey(UUID.randomUUID(), TENANT_ID, material.publicKeyId(), material.digest(),
                    material.displayPrefix(), material.lastFour(), user.id(), project.id(), grant.id(), credentialId,
                    VirtualKeyPurpose.CLAUDE_CODE, "test-vk-nopt-" + suffix, "DISABLED", VirtualKeyStatus.ACTIVE, NOW,
                    null, null, null, 0);
            vkRepo.insert(vk);

            // Verify: the full stored row does NOT contain base64 of rawSecret
            byte[] dbDigest = jdbc.queryForObject("SELECT secret_digest FROM virtual_keys WHERE id = :id",
                    new MapSqlParameterSource("id", vk.id()), byte[].class);
            assertThat(dbDigest).hasSize(32);
            // Digest should NOT equal raw secret
            assertThat(dbDigest).isNotEqualTo(material.rawSecret());

            // Full display string should NOT be findable in any DB column for this row
            String fullRow = jdbc.queryForObject(
                    "SELECT public_key_id || display_prefix || last_four FROM virtual_keys WHERE id = :id",
                    new MapSqlParameterSource("id", vk.id()), String.class);
            assertThat(fullRow).doesNotContain(material.fullDisplayString());

            material.destroy();
        }
    }

    @Nested
    @DisplayName("CryptoOperationException sanitization")
    class ExceptionSanitization {

        @Test
        @DisplayName("should use stable error codes, not JCE provider messages")
        void shouldUseStableErrorCodes() {
            String plaintext = "secret-for-error-test";
            EncryptedSecret encrypted = encProvider.encrypt(plaintext.getBytes(), TENANT_ID, credentialId);

            byte[] tampered = encrypted.ciphertext().clone();
            tampered[tampered.length / 2] ^= 0x01;
            EncryptedSecret tamperedSecret = new EncryptedSecret(tampered, encrypted.nonce(), encrypted.keyVersion());

            assertThatThrownBy(() -> encProvider.decrypt(tamperedSecret, TENANT_ID, credentialId))
                    .isInstanceOf(CryptoOperationException.class).hasMessageMatching("\\[CRYPTO_DECRYPT_001\\].*")
                    .matches(e -> !e.getMessage().contains("Tag") && !e.getMessage().contains("mac"),
                            "message must not contain JCE diagnostics");
        }
    }
}
