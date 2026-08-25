package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G2.6 SSRF guard: scheme, userinfo and resolved-address gates. All targets are
 * IP literals or {@code localhost} so the tests never depend on DNS or the
 * network.
 */
@DisplayName("UpstreamTargetValidator")
class UpstreamTargetValidatorTest {

    private static final UpstreamTargetValidator STRICT = new UpstreamTargetValidator(List.of());

    // -------------------------------------------------------------------
    // Scheme and URL structure
    // -------------------------------------------------------------------

    @Test
    @DisplayName("an https public target is allowed")
    void allowsHttpsPublicTarget() {
        assertThat(STRICT.validate("https://1.1.1.1")).satisfies(r -> {
            assertThat(r.allowed()).isTrue();
        });
    }

    @Test
    @DisplayName("a plain-http target is rejected even when public")
    void rejectsHttpPublicTarget() {
        assertThat(STRICT.validate("http://1.1.1.1").reason()).isEqualTo("non-https");
    }

    @Test
    @DisplayName("a plain-http loopback target is rejected without an allowlist")
    void rejectsHttpLoopbackWithoutAllowlist() {
        assertThat(STRICT.validate("http://127.0.0.1").reason()).isEqualTo("non-https");
    }

    @Test
    @DisplayName("an http target inside the allowlist is allowed")
    void allowsHttpTargetInAllowlist() {
        UpstreamTargetValidator permissive = new UpstreamTargetValidator(List.of("127.0.0.0/8", "::1/128"));
        assertThat(permissive.validate("http://127.0.0.1").allowed()).isTrue();
        assertThat(permissive.validate("http://localhost").allowed()).isTrue();
        assertThat(permissive.validate("http://[::1]").allowed()).isTrue();
    }

    @Test
    @DisplayName("a URL carrying userinfo is always rejected")
    void rejectsUserinfo() {
        assertThat(STRICT.validate("https://user:pass@1.1.1.1").reason()).isEqualTo("userinfo-forbidden");
    }

    @Test
    @DisplayName("a malformed URL is rejected")
    void rejectsMalformedUrl() {
        assertThat(STRICT.validate("not a url").reason()).isEqualTo("invalid-url");
        assertThat(STRICT.validate("https://").reason()).isEqualTo("invalid-url");
    }

    // -------------------------------------------------------------------
    // Non-public address families
    // -------------------------------------------------------------------

    @Test
    @DisplayName("loopback, link-local, private, CGNAT and any-local IPv4 are rejected")
    void rejectsNonPublicIpv4() {
        assertThat(STRICT.validate("https://127.0.0.1").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://10.0.0.1").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://172.16.0.1").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://192.168.1.1").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://169.254.169.254").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://100.64.0.1").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://0.0.0.0").reason()).isEqualTo("non-public-address");
    }

    @Test
    @DisplayName("loopback, unique-local and multicast IPv6 are rejected")
    void rejectsNonPublicIpv6() {
        assertThat(STRICT.validate("https://[::1]").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://[fc00::1]").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://[ff02::1]").reason()).isEqualTo("non-public-address");
        assertThat(STRICT.validate("https://[fe80::1]").reason()).isEqualTo("non-public-address");
    }

    @Test
    @DisplayName("public IPv6 is allowed")
    void allowsPublicIpv6() {
        assertThat(STRICT.validate("https://[2606:4700:4700::1111]").allowed()).isTrue();
    }

    @Test
    @DisplayName("an allowlisted private address is allowed")
    void allowsAllowlistedPrivateAddress() {
        UpstreamTargetValidator permissive = new UpstreamTargetValidator(List.of("192.168.0.0/16"));
        assertThat(permissive.validate("https://192.168.7.7").allowed()).isTrue();
        // A private address outside the allowlist stays rejected.
        assertThat(permissive.validate("https://10.0.0.1").allowed()).isFalse();
    }

    // -------------------------------------------------------------------
    // CIDR parsing and matching
    // -------------------------------------------------------------------

    @Test
    @DisplayName("an allowlist entry matches only its own range")
    void cidrMatchesItsOwnRangeOnly() {
        UpstreamTargetValidator permissive = new UpstreamTargetValidator(List.of("192.168.1.0/24"));
        assertThat(permissive.validate("https://192.168.1.5").allowed()).isTrue();
        assertThat(permissive.validate("https://192.168.2.5").allowed()).isFalse();
    }

    @Test
    @DisplayName("hostnames resolve through the same gates")
    void hostnameResolution() {
        // localhost must be treated like 127.0.0.1/::1: rejected by default,
        // allowed when both loopback families are allowlisted (Windows
        // resolves localhost to ::1, Linux to 127.0.0.1).
        assertThat(STRICT.validate("https://localhost").reason()).isEqualTo("non-public-address");
        UpstreamTargetValidator permissive = new UpstreamTargetValidator(List.of("127.0.0.0/8", "::1/128"));
        assertThat(permissive.validate("https://localhost").allowed()).isTrue();
    }

    @Test
    @DisplayName("blank and malformed CIDRs are rejected at construction")
    void rejectsMalformedCidrs() {
        assertThatThrownBy(() -> new UpstreamTargetValidator(List.of("not-a-cidr")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UpstreamTargetValidator(List.of("127.0.0.1/99")))
                .isInstanceOf(IllegalArgumentException.class);
        // Blank entries are ignored, not errors.
        assertThat(new UpstreamTargetValidator(List.of("", "  ")).allowsPrivateTargets()).isFalse();
    }

    @Test
    @DisplayName("the allowlist state is visible for configuration checks")
    void exposesAllowlistState() {
        assertThat(STRICT.allowsPrivateTargets()).isFalse();
        assertThat(new UpstreamTargetValidator(List.of("127.0.0.0/8")).allowsPrivateTargets()).isTrue();
    }
}
