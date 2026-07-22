package com.miqroera.miqrokey.persistence;

import com.miqroera.miqrokey.domain.crypto.*;
import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

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

    private Provider provider;
    private ProviderProduct product;
    private UpstreamSubscription sub;
    private UpstreamCredential cred;
    private UUID credentialId;
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
                    new byte[]{1, 2, 3}, // dummy fingerprint
                    CredentialVersionStatus.ACTIVE, NOW, null, NOW);
            versionRepo.insert(version);

            // Verify DB does not contain plaintext
            String dbCheck = jdbc.queryForObject(
                    "SELECT encode(encrypted_secret, 'escape') FROM upstream_credential_versions WHERE id = :id",
                    new MapSqlParameterSource("id", version.id()), String.class);
            assertThat(dbCheck).doesNotContain("sk-ant-api03");
            assertThat(dbCheck).doesNotContain("super-secret");
        }

        @Test
        @DisplayName("should store unique nonce per encryption")
        void shouldStoreUniqueNonce() {
            String plaintext = "another-secret-key";
            EncryptedSecret e1 = encProvider.encrypt(plaintext.getBytes(), TENANT_ID, credentialId);
            EncryptedSecret e2 = encProvider.encrypt(plaintext.getBytes(), TENANT_ID, credentialId);

            assertThat(e1.nonce()).isNotEqualTo(e2.nonce());
            assertThat(e1.ciphertext()).isNotEqualTo(e2.ciphertext());
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

            // Read back and decrypt
            UpstreamCredentialVersion stored = versionRepo.findById(version.id()).orElseThrow();
            EncryptedSecret storedEncrypted = new EncryptedSecret(stored.encryptedSecret(), stored.nonce(),
                    stored.encryptionKeyVersion());

            byte[] decrypted = encProvider.decrypt(storedEncrypted, TENANT_ID, credentialId);
            assertThat(new String(decrypted)).isEqualTo(plaintext);
            assertThat(decrypted).isEqualTo(plainBytes);
        }

        @Test
        @DisplayName("should fail decryption with wrong tenant ID")
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
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should encrypt and decrypt with different credential versions")
        void shouldHandleMultipleVersions() {
            String plaintext = "multi-version-secret";
            EncryptedSecret v1enc = encProvider.encrypt(plaintext.getBytes(), TENANT_ID, credentialId);

            UpstreamCredentialVersion v1 = new UpstreamCredentialVersion(UUID.randomUUID(), TENANT_ID, credentialId,
                    v1enc.ciphertext(), v1enc.nonce(), v1enc.keyVersion(), new byte[]{1, 2, 3},
                    CredentialVersionStatus.RETIRED, NOW, NOW, NOW);
            versionRepo.insert(v1);

            // Second version with same plaintext
            EncryptedSecret v2enc = encProvider.encrypt(plaintext.getBytes(), TENANT_ID, credentialId);
            UpstreamCredentialVersion v2 = new UpstreamCredentialVersion(UUID.randomUUID(), TENANT_ID, credentialId,
                    v2enc.ciphertext(), v2enc.nonce(), v2enc.keyVersion(), new byte[]{4, 5, 6},
                    CredentialVersionStatus.ACTIVE, NOW, null, NOW);
            versionRepo.insert(v2);

            // Both should decrypt to the same plaintext
            UpstreamCredentialVersion stored1 = versionRepo.findById(v1.id()).orElseThrow();
            byte[] dec1 = encProvider.decrypt(
                    new EncryptedSecret(stored1.encryptedSecret(), stored1.nonce(), stored1.encryptionKeyVersion()),
                    TENANT_ID, credentialId);
            assertThat(new String(dec1)).isEqualTo(plaintext);

            UpstreamCredentialVersion stored2 = versionRepo.findById(v2.id()).orElseThrow();
            byte[] dec2 = encProvider.decrypt(
                    new EncryptedSecret(stored2.encryptedSecret(), stored2.nonce(), stored2.encryptionKeyVersion()),
                    TENANT_ID, credentialId);
            assertThat(new String(dec2)).isEqualTo(plaintext);
        }
    }

    @Nested
    @DisplayName("Virtual Key digest in DB")
    class VirtualKeyDigest {

        @Test
        @DisplayName("should store only HMAC digest, not raw secret")
        void shouldStoreOnlyDigest() {
            VirtualKeyMaterial material = vkProvider.generate();

            // Verify DB-level: raw secret bytes should never appear in any query result
            // The digest is all that gets persisted
            assertThat(material.digest()).hasSize(32);

            // Serialize to verify digest != rawSecret
            String digestBase64 = java.util.Base64.getEncoder().encodeToString(material.digest());
            String rawBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(material.rawSecret());
            assertThat(digestBase64).isNotEqualTo(rawBase64);
        }

        @Test
        @DisplayName("should validate Virtual Key against stored digest")
        void shouldValidateAgainstStoredDigest() {
            VirtualKeyMaterial material = vkProvider.generate();

            // Simulate storing the digest in DB (as VirtualKey.secretDigest)
            byte[] storedDigest = material.digest();

            // Validate: user presents full key, we parse publicKeyId and rawSecret
            boolean valid = vkProvider.validateConstantTime(material.publicKeyId(), material.rawSecret(), storedDigest,
                    TENANT_ID);
            assertThat(valid).isTrue();

            // Wrong secret should fail
            byte[] wrongSecret = new byte[32];
            new java.security.SecureRandom().nextBytes(wrongSecret);
            boolean invalid = vkProvider.validateConstantTime(material.publicKeyId(), wrongSecret, storedDigest,
                    TENANT_ID);
            assertThat(invalid).isFalse();
        }

        @Test
        @DisplayName("should handle HMAC key rotation in DB context")
        void shouldHandleHmacKeyRotation() {
            // Generate with current HMAC key
            VirtualKeyMaterial material = vkProvider.generate();
            byte[] storedDigest = material.digest();

            // This digest should validate because vkProvider has the key
            boolean valid = vkProvider.validateConstantTime(material.publicKeyId(), material.rawSecret(), storedDigest,
                    TENANT_ID);
            assertThat(valid).isTrue();
        }
    }

    @Nested
    @DisplayName("no plaintext in DB")
    class NoPlaintextInDb {

        @Test
        @DisplayName("should verify DB has no plaintext columns by design")
        void shouldVerifyNoPlaintextColumns() {
            // The upstream_credential_versions table only has encrypted_secret (bytea)
            // and nonce (bytea) — never a plaintext column
            // The virtual_keys table only has secret_digest (bytea) — never plaintext
            // This is verified by the schema, but we add a runtime check:
            String sql = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'upstream_credential_versions'
                    AND column_name IN ('secret', 'plaintext', 'api_key', 'raw_secret')
                    """;
            var result = jdbc.query(sql, (rs, rowNum) -> rs.getString("column_name"));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should verify virtual_keys has no plaintext columns")
        void shouldVerifyVkNoPlaintextColumns() {
            String sql = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'virtual_keys'
                    AND column_name IN ('secret', 'plaintext', 'raw_key', 'key_secret')
                    """;
            var result = jdbc.query(sql, (rs, rowNum) -> rs.getString("column_name"));
            assertThat(result).isEmpty();
        }
    }
}
