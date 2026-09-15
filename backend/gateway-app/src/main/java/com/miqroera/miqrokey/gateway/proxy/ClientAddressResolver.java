package com.miqroera.miqrokey.gateway.proxy;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolves the calling-party address recorded on usage facts (#605).
 *
 * <p>
 * The transport peer is authoritative unless it is one of the configured
 * trusted proxies — then the {@code X-Forwarded-For} chain is walked from the
 * RIGHT, trusted entries are skipped and the first non-trusted entry (what the
 * nearest trusted proxy actually saw) wins. Rightmost-walk is correct for both
 * append- and replace-style proxies; trusting the LEFTMOST entry would let any
 * client prepend a forged address (same rule as the control-plane F05
 * allowlist, #445). Non-literal values (including hostnames) are never resolved
 * and never match a trusted CIDR.
 * </p>
 */
@Component
public class ClientAddressResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<Cidr> trustedProxies;

    public ClientAddressResolver(ClientAddressProperties properties) {
        // Parse eagerly: a malformed CIDR must fail startup, not requests.
        this.trustedProxies = properties.getCidrs().stream().map(Cidr::parse).toList();
    }

    /** Calling-party address, or null when the transport exposes none. */
    public String resolve(ServerHttpRequest request) {
        String peer = peerAddress(request);
        if (!isTrusted(peer)) {
            return peer;
        }
        String header = request.getHeaders().getFirst(X_FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return peer;
        }
        String[] parts = header.split(",", -1);
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (candidate.isEmpty() || "unknown".equalsIgnoreCase(candidate) || !Cidr.isIpLiteral(candidate)) {
                // Non-literal entries (hostnames, ports, junk) are never recorded —
                // and never resolved: a request-supplied value must not trigger DNS
                // or overflow the recorded column.
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        // The whole chain is our own infrastructure — fall back to the peer.
        return peer;
    }

    private boolean isTrusted(String address) {
        return address != null && trustedProxies.stream().anyMatch(m -> m.matches(address));
    }

    private static String peerAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        String host = remote.getAddress().getHostAddress();
        int scope = host.indexOf('%');
        return scope < 0 ? host : host.substring(0, scope);
    }

    /**
     * CIDR membership test over literal addresses only. Ported from the
     * control-plane {@code IpCidrMatcher} (#445) so both planes decide
     * trusted-proxy chains identically.
     */
    static final class Cidr {

        private final InetAddress network;
        private final int prefixBits;

        private Cidr(InetAddress network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        /**
         * True only for parseable IP literals: dotted-quad, or a colon form that the
         * JDK accepts as IPv6 (colon strings never trigger DNS). Rejects
         * {@code ip:port} composites a proxy might append.
         */
        static boolean isIpLiteral(String text) {
            if (text.indexOf(':') >= 0) {
                if (text.indexOf('%') >= 0) {
                    return false; // scoped zone ids are not recorded
                }
                try {
                    InetAddress.getByName(text);
                    return true;
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            return text.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        }

        /** Parses {@code 1.2.3.0/24} or {@code 2001:db8::/32} style CIDR text. */
        static Cidr parse(String cidr) {
            if (cidr == null || cidr.isBlank()) {
                throw new IllegalArgumentException("Trusted proxy CIDR must not be blank");
            }
            String[] parts = cidr.trim().split("/", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Trusted proxy CIDR must be <address>/<prefix>: " + cidr);
            }
            int prefix;
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Trusted proxy CIDR prefix must be numeric: " + cidr);
            }
            InetAddress network;
            try {
                network = InetAddress.getByName(parts[0]);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Trusted proxy CIDR network is not parseable: " + cidr);
            }
            int maxBits = network.getAddress().length * 8;
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException("Trusted proxy CIDR prefix out of range: " + cidr);
            }
            return new Cidr(network, prefix);
        }

        boolean matches(String address) {
            if (address == null || address.isBlank()) {
                return false;
            }
            String text = address.trim();
            if (!isIpLiteral(text)) {
                return false;
            }
            InetAddress candidate;
            try {
                candidate = InetAddress.getByName(text);
            } catch (UnknownHostException e) {
                return false;
            }
            byte[] networkBytes = network.getAddress();
            byte[] candidateBytes = candidate.getAddress();
            if (networkBytes.length != candidateBytes.length) {
                return false; // family mismatch (IPv4 vs IPv6)
            }
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != candidateBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                return (networkBytes[fullBytes] & mask) == (candidateBytes[fullBytes] & mask);
            }
            return true;
        }
    }
}
