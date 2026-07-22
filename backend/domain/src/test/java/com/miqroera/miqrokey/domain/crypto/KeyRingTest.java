package com.miqroera.miqrokey.domain.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("KeyRing")
class KeyRingTest {

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("should create with valid active version")
        void shouldCreateValid() {
            byte[] key = randomBytes(32);
            KeyRing ring = new KeyRing("v1", Map.of("v1", key));
            assertThat(ring.activeVersion()).isEqualTo("v1");
            assertThat(ring.activeKey()).isEqualTo(key);
        }

        @Test
        @DisplayName("should reject if activeVersion not in map")
        void shouldRejectMissingActiveVersion() {
            byte[] key = randomBytes(32);
            assertThatThrownBy(() -> new KeyRing("v2", Map.of("v1", key))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("v2");
        }

        @Test
        @DisplayName("should reject null or empty keys")
        void shouldRejectNullKey() {
            assertThatThrownBy(() -> new KeyRing("v1", Map.of("v1", new byte[0])))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject null activeVersion")
        void shouldRejectNullActiveVersion() {
            byte[] key = randomBytes(32);
            assertThatThrownBy(() -> new KeyRing(null, Map.of("v1", key))).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should make defensive copy of key map")
        void shouldCopyKeyMap() {
            byte[] key = randomBytes(32);
            var mutableMap = new java.util.HashMap<String, byte[]>();
            mutableMap.put("v1", key);
            KeyRing ring = new KeyRing("v1", mutableMap);

            // Mutating original map should not affect KeyRing
            mutableMap.put("v2", randomBytes(32));
            assertThat(ring.knownVersions()).containsExactly("v1");
        }
    }

    @Nested
    @DisplayName("key access")
    class KeyAccess {

        @Test
        @DisplayName("should return defensive copy of active key")
        void shouldDefensiveCopyActiveKey() {
            byte[] key = randomBytes(32);
            KeyRing ring = new KeyRing("v1", Map.of("v1", key));

            byte[] copy1 = ring.activeKey();
            byte[] copy2 = ring.activeKey();

            copy1[0] ^= 0xFF;
            assertThat(copy2[0]).isNotEqualTo(copy1[0]);
        }

        @Test
        @DisplayName("should return null for unknown version")
        void shouldReturnNullForUnknown() {
            byte[] key = randomBytes(32);
            KeyRing ring = new KeyRing("v1", Map.of("v1", key));
            assertThat(ring.keyForVersion("nonexistent")).isNull();
        }

        @Test
        @DisplayName("should report known versions")
        void shouldReportKnownVersions() {
            byte[] keyV1 = randomBytes(32);
            byte[] keyV2 = randomBytes(32);
            KeyRing ring = new KeyRing("v1", Map.of("v1", keyV1, "v2", keyV2));

            assertThat(ring.knownVersions()).containsExactlyInAnyOrder("v1", "v2");
            assertThat(ring.hasVersion("v1")).isTrue();
            assertThat(ring.hasVersion("v3")).isFalse();
        }
    }

    @Nested
    @DisplayName("version rotation")
    class Rotation {

        @Test
        @DisplayName("should create new KeyRing with added version")
        void shouldAddNewVersion() {
            byte[] keyV1 = randomBytes(32);
            KeyRing ring = new KeyRing("v1", Map.of("v1", keyV1));

            byte[] keyV2 = randomBytes(32);
            KeyRing rotated = ring.withNewActiveVersion("v2", keyV2);

            assertThat(rotated.activeVersion()).isEqualTo("v2");
            assertThat(rotated.knownVersions()).containsExactlyInAnyOrder("v1", "v2");
            // Original ring unchanged
            assertThat(ring.activeVersion()).isEqualTo("v1");
        }

        @Test
        @DisplayName("should preserve old keys after rotation")
        void shouldPreserveOldKeys() {
            byte[] keyV1 = randomBytes(32);
            KeyRing ring = new KeyRing("v1", Map.of("v1", keyV1));

            byte[] keyV2 = randomBytes(32);
            KeyRing rotated = ring.withNewActiveVersion("v2", keyV2);

            // v1 key should still be accessible
            assertThat(rotated.keyForVersion("v1")).isEqualTo(keyV1);
        }
    }

    @Nested
    @DisplayName("safety")
    class Safety {

        @Test
        @DisplayName("toString should not expose key material")
        void toStringShouldBeSafe() {
            byte[] key = randomBytes(32);
            KeyRing ring = new KeyRing("v1", Map.of("v1", key));

            String str = ring.toString();
            assertThat(str).doesNotContain("key").contains("activeVersion=v1").contains("versionCount=1");
        }
    }
}
