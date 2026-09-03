package com.miqroera.miqrokey.controlplane.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CIDR membership for the admin-IP allowlist (F05). */
@DisplayName("IpCidrMatcher unit tests")
class IpCidrMatcherTest {

    @Test
    @DisplayName("IPv4 /24 membership respects the network mask")
    void ipv4Slash24() {
        IpCidrMatcher matcher = IpCidrMatcher.parse("192.168.1.0/24");
        assertThat(matcher.matches("192.168.1.1")).isTrue();
        assertThat(matcher.matches("192.168.1.254")).isTrue();
        assertThat(matcher.matches("192.168.2.1")).isFalse();
        assertThat(matcher.matches("10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("host routes (/32) and full blocks match exactly")
    void exactAndFullBlocks() {
        assertThat(IpCidrMatcher.parse("203.0.113.7/32").matches("203.0.113.7")).isTrue();
        assertThat(IpCidrMatcher.parse("203.0.113.7/32").matches("203.0.113.8")).isFalse();
        assertThat(IpCidrMatcher.parse("0.0.0.0/0").matches("8.8.8.8")).isTrue();
        assertThat(IpCidrMatcher.parse("0.0.0.0/0").matches("::1")).isFalse(); // family mismatch
    }

    @Test
    @DisplayName("IPv6 /64 and prefix masks work")
    void ipv6() {
        IpCidrMatcher matcher = IpCidrMatcher.parse("2001:db8::/64");
        assertThat(matcher.matches("2001:db8::1")).isTrue();
        assertThat(matcher.matches("2001:db8:0:1::1")).isFalse();
        IpCidrMatcher compressed = IpCidrMatcher.parse("::1/128");
        assertThat(compressed.matches("::1")).isTrue();
        assertThat(compressed.matches("::2")).isFalse();
    }

    @Test
    @DisplayName("parse rejects malformed CIDR and out-of-range prefixes")
    void parseValidation() {
        assertThatThrownBy(() -> IpCidrMatcher.parse("not-a-cidr")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.parse("192.168.1.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.parse("192.168.1.0/-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.parse("999.1.1.1/24")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("unparseable or blank candidate addresses never match")
    void unparseableCandidates() {
        IpCidrMatcher matcher = IpCidrMatcher.parse("10.0.0.0/8");
        assertThat(matcher.matches("not-an-ip")).isFalse();
        assertThat(matcher.matches("")).isFalse();
        assertThat(matcher.matches(null)).isFalse();
    }
}
