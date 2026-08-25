package com.miqroera.miqrokey.domain.vkey;

import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyMaterial;
import com.miqroera.miqrokey.domain.crypto.impl.HmacVirtualKeyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the label-routing Virtual Key format:
 * {@code mqk_live_<22ch pkId>_<43ch secret>.<tag>}.
 */
@DisplayName("VirtualKeyParser")
class VirtualKeyParserTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final VirtualKeyCrypto crypto = new HmacVirtualKeyProvider(new KeyRing("v1", Map.of("v1", new byte[32])));

    /** A real generated key plus its tag, kept for the round-trip tests. */
    private String presented(String tag) {
        VirtualKeyMaterial material = crypto.generate(TENANT_ID, null);
        try {
            return material.fullDisplayString() + "." + tag;
        } finally {
            material.destroy();
        }
    }

    @Nested
    @DisplayName("Valid keys")
    class Valid {

        @Test
        @DisplayName("should parse a generated key with a simple tag")
        void shouldParseGeneratedKey() {
            VirtualKeyMaterial material = crypto.generate(TENANT_ID, null);
            String full = material.fullDisplayString() + ".demo-proj";
            try {
                VirtualKeyParseResult result = VirtualKeyParser.parse(full);
                assertThat(result.valid()).isTrue();
                assertThat(result.publicKeyId()).isEqualTo(material.publicKeyId());
                assertThat(result.projectTag()).isEqualTo("demo-proj");
                assertThat(result.rawSecret()).isEqualTo(material.rawSecret());
                com.miqroera.miqrokey.domain.crypto.impl.SecretWiping.clearArray(result.rawSecret());
            } finally {
                material.destroy();
            }
        }

        @Test
        @DisplayName("should split the label from the dot after the fixed-length core")
        void shouldSplitLabelFromCore() {
            // The core is fixed-length (66 chars), so the label is everything
            // after the first '.' following the secret. A tag may contain '_'
            // (which is why the core is parsed positionally), but never '.':
            // a dot inside the tag inflates the core and the key is malformed.
            VirtualKeyParseResult result = VirtualKeyParser.parse(presented("team-alpha"));
            assertThat(result.valid()).isTrue();
            assertThat(result.projectTag()).isEqualTo("team-alpha");
            assertThat(VirtualKeyParser.parse(presented("team.alpha")).valid()).isFalse();
        }

        @Test
        @DisplayName("should accept tags with underscores and dashes")
        void shouldAcceptTagAlphabet() {
            assertThat(VirtualKeyParser.parse(presented("my_proj-2")).projectTag()).isEqualTo("my_proj-2");
            assertThat(VirtualKeyParser.parse(presented("a")).projectTag()).isEqualTo("a");
            assertThat(VirtualKeyParser.parse(presented("A-9_")).projectTag()).isEqualTo("A-9_");
        }

        @Test
        @DisplayName("should strip nothing from the presented value (no padding allowed)")
        void shouldRejectPadding() {
            // The generator emits unpadded base64url; a padded variant of a
            // VALID core must be rejected (non-canonical encoding).
            VirtualKeyMaterial material = crypto.generate(TENANT_ID, null);
            try {
                String unpadded = material.fullDisplayString();
                String paddedPkId = Base64.getUrlEncoder()
                        .encodeToString(Base64.getUrlDecoder().decode(material.publicKeyId()));
                String tampered = unpadded.replace(material.publicKeyId(), paddedPkId) + ".demo";
                assertThat(paddedPkId).hasSize(24); // includes '='
                assertThat(VirtualKeyParser.parse(tampered).valid()).isFalse();
            } finally {
                material.destroy();
            }
        }
    }

    @Nested
    @DisplayName("Malformed keys")
    class Malformed {

        @Test
        @DisplayName("should reject null and empty input without throwing")
        void shouldRejectNullAndEmpty() {
            assertThat(VirtualKeyParser.parse(null).valid()).isFalse();
            assertThat(VirtualKeyParser.parse("").valid()).isFalse();
        }

        @Test
        @DisplayName("should reject wrong prefixes")
        void shouldRejectWrongPrefix() {
            String valid = presented("demo");
            assertThat(VirtualKeyParser.parse(valid.replace("mqk_live_", "sk_live_")).valid()).isFalse();
            assertThat(VirtualKeyParser.parse("mqk_test_" + valid.substring("mqk_live_".length())).valid()).isFalse();
        }

        @Test
        @DisplayName("should reject truncated and extended cores")
        void shouldRejectWrongLengths() {
            // Truncating or extending the TAG yields a different-but-still-valid
            // label, so only core mutations may be rejected here.
            String valid = presented("demo");
            String core = valid.substring("mqk_live_".length(), valid.lastIndexOf('.'));
            String shortSecret = "mqk_live_" + core.substring(0, core.length() - 1) + ".demo";
            assertThat(VirtualKeyParser.parse(shortSecret).valid()).isFalse();
            String longSecret = "mqk_live_" + core + "ab" + ".demo";
            assertThat(VirtualKeyParser.parse(longSecret).valid()).isFalse();
        }

        @Test
        @DisplayName("should reject non-base64url characters in the core")
        void shouldRejectIllegalAlphabet() {
            String valid = presented("demo");
            // Replace a core char with a non-base64url char ('+' is base64, not
            // base64url) — swap the separator '_' is illegal anyway, so patch a
            // character inside the secret instead.
            String core = valid.substring("mqk_live_".length(), valid.lastIndexOf('.'));
            assertThat(core).contains("_");
            String corrupted = "mqk_live_" + core.replaceFirst("_", "+") + ".demo";
            assertThat(VirtualKeyParser.parse(corrupted).valid()).isFalse();
        }

        @Test
        @DisplayName("should reject missing, empty, or oversized tags")
        void shouldRejectBadTags() {
            String valid = presented("demo");
            String core = valid.substring(0, valid.lastIndexOf('.'));
            assertThat(VirtualKeyParser.parse(core).valid()).isFalse();
            assertThat(VirtualKeyParser.parse(core + ".").valid()).isFalse();
            assertThat(VirtualKeyParser.parse(core + "." + "x".repeat(65)).valid()).isFalse();
            assertThat(VirtualKeyParser.parse(core + ".demo tag").valid()).isFalse();
            assertThat(VirtualKeyParser.parse(core + ".tag.dot").valid()).isFalse();
        }

        @Test
        @DisplayName("should reject a secret that is not canonical base64url")
        void shouldRejectWrongSecretLength() {
            // 43 chars decoding to 32 bytes are only accepted when canonical.
            // 'A'*43 IS the canonical encoding of 32 zero bytes, so the parser
            // accepts it (the HMAC check rejects it downstream). A final 'B'
            // has non-zero padding bits: re-encoding differs, so it is rejected.
            String core = "mqk_live_" + "A".repeat(22) + "_" + "A".repeat(42) + "B" + ".demo";
            assertThat(VirtualKeyParser.parse(core).valid()).isFalse();
        }

        @Test
        @DisplayName("should never throw on arbitrary garbage")
        void shouldNeverThrow() {
            for (String garbage : new String[]{"hello", "mqk_live_", "....", "mqk_live_xxxx",
                    "Bearer mqk_live_1111111111111111111111_2222222222222222222222222222222222222222222.demo"}) {
                assertThat(VirtualKeyParser.parse(garbage).valid()).isFalse();
            }
        }
    }
}
