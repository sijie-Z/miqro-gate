package com.miqroera.miqrokey.controlplane.client;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;

/**
 * Pins an upstream URI to a validated address and keeps TLS identity on the
 * original hostname — the control-plane half of the DNS-rebinding TOCTOU
 * defense.
 *
 * <p>
 * {@link UpstreamTargetValidator} validates DNS answers, but the JDK HTTP
 * client re-resolves the hostname when connecting. After
 * {@link #pin(URI, InetAddress)} the URI carries the validated IP literal, so
 * the connection can only go there. For {@code https} targets
 * {@link #sslParametersFor(URI)} keeps the SNI extension and the certificate
 * identity check on the original hostname (the JDK TLS layer verifies against
 * the requested SNI server name when present), which also preserves
 * virtual-host routing.
 * </p>
 *
 * <p>
 * The {@code Host} request header becomes the IP literal (the JDK client
 * derives it from the URI and does not allow overriding it); SNI-based routing
 * and certificate checks are unaffected.
 * </p>
 */
public final class UpstreamTargetPin {

    private UpstreamTargetPin() {
    }

    /**
     * Replaces the URI host with the textual form of {@code address}, keeping
     * scheme, port, raw path, raw query and raw fragment byte-identical. IPv6
     * literals are bracketed as the authority syntax requires.
     */
    public static URI pin(URI uri, InetAddress address) {
        String host = address.getHostAddress();
        if (address instanceof Inet6Address) {
            host = "[" + host + "]";
        }
        StringBuilder sb = new StringBuilder(uri.getScheme()).append("://").append(host);
        if (uri.getPort() >= 0) {
            sb.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        } else if (uri.getPath() != null) {
            sb.append(uri.getPath());
        }
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            sb.append('#').append(uri.getRawFragment());
        }
        return URI.create(sb.toString());
    }

    /**
     * Client-level {@link SSLParameters} for a pinned {@code https} URI:
     * endpoint identification stays {@code HTTPS} and, when the original host
     * is a hostname (not an IP literal), the SNI server name is set to it so
     * the certificate check and virtual-host routing keep using the hostname
     * even though the connection goes to the pinned IP. Returns {@code null}
     * for plain-http URIs (no TLS layer).
     */
    public static SSLParameters sslParametersFor(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        String host = uri.getHost();
        if (host != null && !isIpLiteral(host)) {
            sslParameters.setServerNames(List.of(new SNIHostName(host)));
        }
        return sslParameters;
    }

    /**
     * True for IPv4/IPv6 literal text ({@code URI.getHost()} never carries
     * brackets). Hostnames never contain {@code ':'}; a purely numeric host is
     * treated as a literal (single-label numeric hostnames are not supported
     * as pinned SNI targets).
     */
    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
    }
}
