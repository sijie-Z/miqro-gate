package com.miqroera.miqrokey.controlplane.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pinning helpers for the DNS-rebinding TOCTOU defense: URI host replacement
 * and SNI-preserving SSL parameters. Pure computation — no DNS, no network.
 */
@DisplayName("UpstreamTargetPin")
class UpstreamTargetPinTest {

    @Test
    @DisplayName("pin replaces the host and keeps scheme, port, path and query")
    void pinReplacesHostKeepsEverythingElse() throws Exception {
        URI pinned = UpstreamTargetPin.pin(URI.create("https://api.example.com:8443/v1/models?x=1"),
                InetAddress.getByName("1.2.3.4"));

        assertThat(pinned.toString()).isEqualTo("https://1.2.3.4:8443/v1/models?x=1");
        assertThat(pinned.getScheme()).isEqualTo("https");
        assertThat(pinned.getPort()).isEqualTo(8443);
        assertThat(pinned.getPath()).isEqualTo("/v1/models");
        assertThat(pinned.getQuery()).isEqualTo("x=1");
    }

    @Test
    @DisplayName("pin brackets IPv6 literals in the authority")
    void pinBracketsIpv6Literals() throws Exception {
        InetAddress ipv6 = InetAddress.getByName("2606:4700:4700::1111");
        URI pinned = UpstreamTargetPin.pin(URI.create("http://example.com/p"), ipv6);

        // getHostAddress() may render the long form — both are valid literals.
        assertThat(pinned.toString()).isEqualTo("http://[" + ipv6.getHostAddress() + "]/p");
    }

    @Test
    @DisplayName("pin preserves raw percent-escapes in path and query")
    void pinPreservesRawEncoding() throws Exception {
        URI pinned = UpstreamTargetPin.pin(URI.create("https://example.com/a%2Fb?q=a%2Fb"),
                InetAddress.getByName("1.2.3.4"));

        assertThat(pinned.getRawPath()).isEqualTo("/a%2Fb");
        assertThat(pinned.getRawQuery()).isEqualTo("q=a%2Fb");
    }

    @Test
    @DisplayName("https hostnames keep SNI and HTTPS endpoint identification")
    void sslParametersKeepSniAndIdentityForHostnames() {
        SSLParameters sslParameters = UpstreamTargetPin.sslParametersFor(URI.create("https://api.example.com/v1"));

        assertThat(sslParameters.getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(sslParameters.getServerNames()).containsExactly(new SNIHostName("api.example.com"));
    }

    @Test
    @DisplayName("https IP literals keep HTTPS endpoint identification but no SNI")
    void sslParametersOmitSniForIpLiterals() {
        SSLParameters sslParameters = UpstreamTargetPin.sslParametersFor(URI.create("https://1.1.1.1/v1"));

        assertThat(sslParameters.getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(sslParameters.getServerNames()).isNullOrEmpty();
    }

    @Test
    @DisplayName("plain-http URIs need no TLS parameters")
    void sslParametersAreNullForPlainHttp() {
        assertThat(UpstreamTargetPin.sslParametersFor(URI.create("http://api.example.com/v1"))).isNull();
    }

    @Test
    @DisplayName("IP literal detection distinguishes literals from hostnames")
    void ipLiteralDetection() {
        assertThat(UpstreamTargetPin.isIpLiteral("127.0.0.1")).isTrue();
        assertThat(UpstreamTargetPin.isIpLiteral("::1")).isTrue();
        assertThat(UpstreamTargetPin.isIpLiteral("api.example.com")).isFalse();
        assertThat(UpstreamTargetPin.isIpLiteral("localhost")).isFalse();
    }
}
