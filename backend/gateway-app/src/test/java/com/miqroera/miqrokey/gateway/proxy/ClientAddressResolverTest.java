package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client-address resolution for usage facts (#605): the transport peer is
 * authoritative; X-Forwarded-For is honored only behind a configured trusted
 * proxy and walked from the RIGHT, so a client-forged leftmost entry can never
 * be recorded as the caller.
 */
@DisplayName("Client address resolver")
class ClientAddressResolverTest {

    private static ClientAddressResolver resolver(String... cidrs) {
        ClientAddressProperties properties = new ClientAddressProperties();
        properties.setCidrs(List.of(cidrs));
        return new ClientAddressResolver(properties);
    }

    private static MockServerHttpRequest request(String peer, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/v1/messages")
                .remoteAddress(new InetSocketAddress(peer, 51234));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return builder.build();
    }

    @Test
    @DisplayName("without trusted proxies the transport peer is recorded and XFF is ignored")
    void peerOnlyWithoutTrust() {
        ClientAddressResolver resolver = resolver();
        assertThat(resolver.resolve(request("203.0.113.9", "198.51.100.1"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("behind a trusted proxy the rightmost non-trusted XFF hop wins")
    void rightmostWalkBehindTrustedProxy() {
        ClientAddressResolver resolver = resolver("172.28.0.0/24");
        assertThat(resolver.resolve(request("172.28.0.3", "203.0.113.9"))).isEqualTo("203.0.113.9");
        // A forged leftmost entry is ignored: the nearest proxy appended the real
        // client.
        assertThat(resolver.resolve(request("172.28.0.3", "10.0.0.99, 203.0.113.9"))).isEqualTo("203.0.113.9");
        // Nested trusted hops are skipped on the way right-to-left.
        assertThat(resolver.resolve(request("172.28.0.3", "203.0.113.9, 172.28.0.4"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("blank and 'unknown' entries are skipped; an all-trusted chain falls back to the peer")
    void skipsBlankUnknownAndFallsBack() {
        ClientAddressResolver resolver = resolver("172.28.0.0/24");
        assertThat(resolver.resolve(request("172.28.0.3", "unknown, 203.0.113.9, "))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("172.28.0.3", "172.28.0.4"))).isEqualTo("172.28.0.3");
        assertThat(resolver.resolve(request("172.28.0.3", null))).isEqualTo("172.28.0.3");
    }

    @Test
    @DisplayName("non-literal entries (hostnames, ports, junk) are skipped, never resolved, never recorded")
    void literalsOnly() {
        ClientAddressResolver resolver = resolver("172.28.0.0/24");
        // A forged hostname is skipped; with no other hop the peer is recorded instead.
        assertThat(resolver.resolve(request("172.28.0.3", "evil.example.com"))).isEqualTo("172.28.0.3");
        assertThat(resolver.resolve(request("172.28.0.3", "junk, 203.0.113.9"))).isEqualTo("203.0.113.9");
        // A port-suffixed entry is not a literal either.
        assertThat(resolver.resolve(request("172.28.0.3", "203.0.113.9:443"))).isEqualTo("172.28.0.3");
    }

    @Test
    @DisplayName("a transport without a resolvable peer records null and never trusts XFF")
    void unresolvedPeer() {
        ClientAddressResolver resolver = resolver("172.28.0.0/24");
        MockServerHttpRequest unresolved = MockServerHttpRequest.post("/v1/messages")
                .remoteAddress(InetSocketAddress.createUnresolved("proxy.internal", 51234))
                .header("X-Forwarded-For", "203.0.113.9").build();
        assertThat(resolver.resolve(unresolved)).isNull();
    }

    @Test
    @DisplayName("IPv6 peers and XFF entries work, including the compose subnet form")
    void ipv6Support() {
        ClientAddressResolver resolver = resolver("2001:db8::/32");
        assertThat(resolver.resolve(request("2001:db8::1", "2001:db8:1::7, 2001:4860:4860::8888")))
                .isEqualTo("2001:4860:4860::8888");
        // The JDK reports the peer in expanded form; XFF entries pass through as sent.
        assertThat(resolver.resolve(request("::1", "2001:4860:4860::8888"))).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    @DisplayName("a malformed trusted-proxy CIDR fails fast at construction")
    void malformedCidrFailsFast() {
        assertThatThrownBy(() -> resolver("172.28.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver("172.28.0.0/99")).isInstanceOf(IllegalArgumentException.class);
    }
}
