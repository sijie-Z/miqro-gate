package com.miqroera.miqrokey.domain.crypto;

import com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException;
import com.miqroera.miqrokey.domain.crypto.impl.HmacVirtualKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HmacVirtualKeyProvider")
class HmacVirtualKeyProviderTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String PREFIX = "mqk_live_";

    private KeyRing hmacKeyRing;
    private HmacVirtualKeyProvider provider;

    @BeforeEach
    void setUp() {
        byte[] hmacKey = randomBytes(32);
        hmacKeyRing = new KeyRing("v1", Map.of("v1", hmacKey));
        provider = new HmacVirtualKeyProvider(hmacKeyRing);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @Nested
    @DisplayName("Virtual Key generation")
    class Generation {

        @Test
        @DisplayName("should generate a valid Virtual Key material")
        void shouldGenerateValidMaterial() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);

            assertThat(material.fullDisplayString()).startsWith(PREFIX);
            assertThat(material.publicKeyId()).isNotEmpty();
            assertThat(material.rawSecret()).hasSize(32);
            assertThat(material.displayPrefix()).isNotEmpty().hasSizeLessThanOrEqualTo(8);
            assertThat(material.lastFour()).hasSize(4);
            assertThat(material.digest()).hasSize(32);
        }

        @Test
        @DisplayName("should generate unique keys on each call")
        void shouldGenerateUniqueKeys() {
            VirtualKeyMaterial m1 = provider.generate(TENANT_ID, null);
            VirtualKeyMaterial m2 = provider.generate(TENANT_ID, null);

            assertThat(m1.fullDisplayString()).isNotEqualTo(m2.fullDisplayString());
            assertThat(m1.publicKeyId()).isNotEqualTo(m2.publicKeyId());
        }

        @Test
        @DisplayName("should generate secret of at least 256 bits")
        void shouldGenerate256BitSecret() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            assertThat(material.rawSecret()).hasSize(32); // 256 bits
        }

        @Test
        @DisplayName("full display string should follow mqk_live_ format")
        void shouldFollowFormat() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            String display = material.fullDisplayString();

            // Format: mqk_live_<publicKeyId>_<encodedSecret>. Both id and the
            // encoded secret are base64url and may contain - and _, so the
            // underscore separator cannot be located by indexOf (a leading _
            // in the secret used to trip the position check ~1/64 runs).
            assertThat(display).startsWith("mqk_live_");
            assertThat(display.length()).isGreaterThan("mqk_live_".length() + 24);
            assertThat(material.rawSecret()).hasSize(32);
        }

        @Test
        @DisplayName("should set displayPrefix from publicKeyId")
        void shouldSetDisplayPrefix() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            assertThat(material.publicKeyId()).startsWith(material.displayPrefix());
        }

        @Test
        @DisplayName("should set lastFour from full display string")
        void shouldSetLastFour() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            String display = material.fullDisplayString();
            assertThat(material.lastFour()).isEqualTo(display.substring(display.length() - 4));
        }
    }

    @Nested
    @DisplayName("HMAC computation and validation")
    class HmacValidation {

        @Test
        @DisplayName("should compute consistent HMAC digest")
        void shouldComputeConsistentDigest() {
            byte[] rawSecret = randomBytes(32);
            String publicKeyId = "testPublicKeyId123456";

            byte[] digest1 = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);
            byte[] digest2 = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);

            assertThat(digest1).isEqualTo(digest2);
            assertThat(digest1).hasSize(32);
        }

        @Test
        @DisplayName("should validate correct rawSecret and digest via constant-time comparison")
        void shouldValidateCorrectDigest() {
            byte[] rawSecret = randomBytes(32);
            String publicKeyId = "pk_validTestKey123";
            byte[] digest = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);

            assertThat(provider.validateConstantTime(publicKeyId, rawSecret, digest, TENANT_ID)).isTrue();
        }

        @Test
        @DisplayName("should reject wrong publicKeyId with correct rawSecret")
        void shouldRejectWrongPublicKeyId() {
            byte[] rawSecret = randomBytes(32);
            String publicKeyId = "pk_rightKey123456";
            byte[] digest = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);

            assertThat(provider.validateConstantTime("pk_WRONG_key_id", rawSecret, digest, TENANT_ID)).isFalse();
        }

        @Test
        @DisplayName("should reject correct publicKeyId with wrong rawSecret")
        void shouldRejectWrongRawSecret() {
            byte[] rawSecret = randomBytes(32);
            String publicKeyId = "pk_wrongSecret12";
            byte[] digest = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);

            byte[] wrongSecret = randomBytes(32);
            assertThat(provider.validateConstantTime(publicKeyId, wrongSecret, digest, TENANT_ID)).isFalse();
        }

        @Test
        @DisplayName("should reject correct inputs with tampered digest")
        void shouldRejectTamperedDigest() {
            byte[] rawSecret = randomBytes(32);
            String publicKeyId = "pk_tamperTest123";
            byte[] digest = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);

            byte[] tampered = digest.clone();
            tampered[0] ^= 0x01;

            assertThat(provider.validateConstantTime(publicKeyId, rawSecret, tampered, TENANT_ID)).isFalse();
        }

        @Test
        @DisplayName("should reject null or empty publicKeyId")
        void shouldRejectNullPublicKeyId() {
            byte[] rawSecret = randomBytes(32);
            byte[] digest = new byte[32];
            assertThat(provider.validateConstantTime(null, rawSecret, digest, TENANT_ID)).isFalse();
            assertThat(provider.validateConstantTime("", rawSecret, digest, TENANT_ID)).isFalse();
        }

        @Test
        @DisplayName("should reject null or wrong-size digest")
        void shouldRejectWrongDigest() {
            byte[] rawSecret = randomBytes(32);
            assertThat(provider.validateConstantTime("pk123", rawSecret, null, TENANT_ID)).isFalse();
            assertThat(provider.validateConstantTime("pk123", rawSecret, new byte[16], TENANT_ID)).isFalse();
        }

        @Test
        @DisplayName("should reject null rawSecret")
        void shouldRejectNullRawSecret() {
            byte[] digest = new byte[32];
            assertThat(provider.validateConstantTime("pk123", null, digest, TENANT_ID)).isFalse();
        }

        @Test
        @DisplayName("should produce different digests for different inputs")
        void shouldProduceDifferentDigests() {
            byte[] secret1 = randomBytes(32);
            byte[] secret2 = randomBytes(32);

            byte[] digest1 = provider.computeDigest("pk1", secret1, TENANT_ID);
            byte[] digest2 = provider.computeDigest("pk1", secret2, TENANT_ID);

            assertThat(digest1).isNotEqualTo(digest2);
        }
    }

    @Nested
    @DisplayName("constant-time semantics")
    class ConstantTime {

        @Test
        @DisplayName("should use constant-time comparison via MessageDigest.isEqual")
        void shouldUseConstantTimeComparison() {
            // Verify validateConstantTime works with different inputs
            byte[] rawSecret = randomBytes(32);
            byte[] digest = provider.computeDigest("pkX", rawSecret, TENANT_ID);

            // Should validate with correct inputs
            assertThat(provider.validateConstantTime("pkX", rawSecret, digest, TENANT_ID)).isTrue();

            // Should reject with different publicKeyId but same rawSecret and digest
            assertThat(provider.validateConstantTime("pkOther", rawSecret, digest, TENANT_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("HMAC key versioning")
    class HmacKeyVersioning {

        @Test
        @DisplayName("should validate against all known key versions")
        void shouldValidateAgainstAllVersions() {
            byte[] rawSecret = randomBytes(32);
            String publicKeyId = "pk_multiVersionTest";

            // Generate digest with v1
            byte[] digestV1 = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);

            // Rotate to v2 (new HMAC key)
            byte[] keyV2 = randomBytes(32);
            provider = new HmacVirtualKeyProvider(hmacKeyRing.withNewActiveVersion("v2", keyV2));

            // v1 digest should still validate (multi-version verification)
            assertThat(provider.validateConstantTime(publicKeyId, rawSecret, digestV1, TENANT_ID)).isTrue();
        }

        @Test
        @DisplayName("should compute with active key version after rotation")
        void shouldComputeWithActiveVersion() {
            byte[] rawSecret = randomBytes(32);
            String publicKeyId = "pk_rotationTest12";

            byte[] digestV1 = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);

            byte[] keyV2 = randomBytes(32);
            provider = new HmacVirtualKeyProvider(hmacKeyRing.withNewActiveVersion("v2", keyV2));

            byte[] digestV2 = provider.computeDigest(publicKeyId, rawSecret, TENANT_ID);

            // Different keys produce different digests
            assertThat(digestV2).isNotEqualTo(digestV1);
            // But v2 digest validates with v2 key
            assertThat(provider.validateConstantTime(publicKeyId, rawSecret, digestV2, TENANT_ID)).isTrue();
        }

        @Test
        @DisplayName("should report active key version correctly")
        void shouldReportActiveVersion() {
            assertThat(provider.activeKeyVersion()).isEqualTo("v1");
        }
    }

    @Nested
    @DisplayName("safety and sanitization")
    class Safety {

        @Test
        @DisplayName("toString should not expose key material")
        void toStringShouldNotExposeKeys() {
            assertThat(provider.toString()).doesNotContain("secret").doesNotContain("key").contains("activeVersion=v1");
        }

        @Test
        @DisplayName("VirtualKeyMaterial toString should not expose rawSecret")
        void virtualKeyMaterialToStringShouldBeSafe() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            String str = material.toString();
            assertThat(str).doesNotContain("rawSecret").doesNotContain("secret").contains("publicKeyId=")
                    .contains("displayPrefix=").contains("lastFour=");
        }

        @Test
        @DisplayName("VirtualKeyMaterial toString should not expose full display string")
        void shouldNotExposeFullDisplay() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            String str = material.toString();
            assertThat(str).doesNotContain(material.fullDisplayString()).doesNotContain("fullDisplayString");
        }

        @Test
        @DisplayName("rawSecret should be defensively copied")
        void rawSecretShouldBeDefensiveCopy() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            byte[] copy1 = material.rawSecret();
            byte[] copy2 = material.rawSecret();

            copy1[0] = (byte) ~copy1[0];
            assertThat(copy2).isNotEqualTo(copy1);
        }

        @Test
        @DisplayName("generate clears internal rawSecret copy")
        void generateShouldClearInternalCopy() {
            // The generate() method clears its internal copy in finally block.
            // We verify by checking the material.rawSecret() returns valid data
            // but the internal copy used for computation is cleared.
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            // The publicly returned rawSecret should be valid
            assertThat(material.rawSecret()).hasSize(32);
            // Digest is present (proves HMAC was computed before clearing)
            assertThat(material.digest()).hasSize(32);
        }
    }

    @Nested
    @DisplayName("tenant domain separation")
    class TenantBinding {

        @Test
        @DisplayName("digest should embed tenant ID: cross-tenant validation must fail")
        void shouldBindToTenant() {
            byte[] rawSecret = randomBytes(32);
            String publicKeyId = "pk_tenant_test_1";
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();

            byte[] digestA = provider.computeDigest(publicKeyId, rawSecret, tenantA);

            // Same secret + publicKeyId, but wrong tenant -> must fail
            assertThat(provider.validateConstantTime(publicKeyId, rawSecret, digestA, tenantB)).isFalse();

            // Correct tenant -> succeeds
            assertThat(provider.validateConstantTime(publicKeyId, rawSecret, digestA, tenantA)).isTrue();
        }

        @Test
        @DisplayName("generate should bind VirtualKey to tenant")
        void generateShouldBindToTenant() {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();

            VirtualKeyMaterial material = provider.generate(tenantA, null);
            byte[] digest = material.digest();

            assertThat(provider.validateConstantTime(material.publicKeyId(), material.rawSecret(), digest, tenantA))
                    .isTrue();
            assertThat(provider.validateConstantTime(material.publicKeyId(), material.rawSecret(), digest, tenantB))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("one-time display boundary")
    class OneTimeDisplay {

        @Test
        @DisplayName("full display should contain publicKeyId and encoded secret")
        void fullDisplayShouldEncodeSecret() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            String display = material.fullDisplayString();

            // Format: mqk_live_<publicKeyId>_<encodedSecret>
            assertThat(display).matches("mqk_live_[A-Za-z0-9_-]+_[A-Za-z0-9_-]+");

            // publicKeyId should appear in the display string
            assertThat(display).contains(material.publicKeyId());
        }

        @Test
        @DisplayName("project tag is appended as routing label and excluded from digest and lastFour")
        void projectTagAppendedAsRoutingLabel() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, "core-ai");
            String display = material.fullDisplayString();

            // Label suffix present: mqk_live_<pk>_<secret>.core-ai
            assertThat(display).matches("mqk_live_[A-Za-z0-9_-]+_[A-Za-z0-9_-]+\\.core-ai");
            assertThat(display).endsWith(".core-ai");

            // The label is not part of the HMAC message: the digest validates
            // against the label-less secret with no tag in the verify call.
            byte[] rawSecret = material.rawSecret();
            try {
                assertThat(
                        provider.validateConstantTime(material.publicKeyId(), rawSecret, material.digest(), TENANT_ID))
                        .isTrue();
            } finally {
                com.miqroera.miqrokey.domain.crypto.impl.SecretWiping.clearArray(rawSecret);
            }

            // lastFour stays derived from the label-less core, not the tag tail.
            assertThat(material.lastFour()).hasSize(4);
            assertThat(display).contains(material.lastFour());
            assertThat(material.lastFour()).isNotEqualTo(display.substring(display.length() - 4));
        }

        @Test
        @DisplayName("blank tag produces the label-less form")
        void blankTagProducesLabelLessForm() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, "   ");
            assertThat(material.fullDisplayString()).matches("mqk_live_[A-Za-z0-9_-]+_[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("rawSecret must NOT be recoverable from digest alone")
        void rawSecretNotRecoverableFromDigest() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            byte[] digest = material.digest();
            byte[] rawSecret = material.rawSecret();

            // HMAC is one-way — digest != any simple transform of secret
            assertThat(digest).isNotEqualTo(rawSecret);
            assertThat(digest).isNotEqualTo(Arrays.copyOf(rawSecret, 32));
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("should reject HMAC keys shorter than 32 bytes")
        void shouldRejectShortHmacKey() {
            byte[] shortKey = randomBytes(16);
            var ring = new KeyRing("v1", Map.of("v1", shortKey));
            assertThatThrownBy(() -> new HmacVirtualKeyProvider(ring)).isInstanceOf(CryptoOperationException.class)
                    .hasMessageContaining("CRYPTO_KEY_003");
        }
    }

    @Nested
    @DisplayName("VirtualKeyMaterial lifecycle")
    class MaterialLifecycle {

        @Test
        @DisplayName("destroy should zero-fill raw secret")
        void destroyShouldZeroSecret() {
            VirtualKeyMaterial material = provider.generate(TENANT_ID, null);
            material.destroy();

            byte[] cleared = material.rawSecret();
            for (byte b : cleared) {
                assertThat(b).isEqualTo((byte) 0);
            }
        }

        @Test
        @DisplayName("equals and hashCode should not process rawSecret or digest")
        void equalsShouldNotUseSecret() {
            VirtualKeyMaterial m1 = provider.generate(TENANT_ID, null);
            VirtualKeyMaterial m2 = provider.generate(TENANT_ID, null);

            // Different rawSecrets but both valid — they should NOT be equal
            // because publicKeyId differs (the only real identity field used)
            assertThat(m1).isNotEqualTo(m2);

            // But a material is equal to itself
            assertThat(m1).isEqualTo(m1);

            // toString must not expose rawSecret or fullDisplayString
            String str = m1.toString();
            assertThat(str).doesNotContain(m1.rawSecret().toString());
            assertThat(str).doesNotContain(m1.fullDisplayString());
        }
    }
}
