package com.miqroera.miqrokey.domain.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rejects upstream targets that could be abused as an SSRF vector (G2.6).
 *
 * <p>
 * The transparent proxy only ever forwards to base URLs that come from the
 * route snapshot (admin-controlled product data) — never from client input —
 * but a compromised or misconfigured product entry must still not turn the
 * gateway into a scanner of the internal network. Two independent gates:
 * <ol>
 * <li><b>Scheme</b>: {@code https} is required unless the target host is
 * explicitly allow-listed (the "trusted self-hosted model" escape hatch of
 * {@code docs/security.md} §6). A URL with {@code userinfo} is always rejected
 * (credential smuggling).</li>
 * <li><b>Resolved addresses</b>: the host is DNS-resolved and every returned
 * address must be public — loopback, link-local, site-local, CGNAT, multicast,
 * any-local and IPv6 unique-local ranges are rejected — unless the address
 * falls inside an allow-listed CIDR.</li>
 * </ol>
 *
 * <p>
 * DNS resolution is blocking; callers must never run {@link #validate(String)}
 * on the Reactor event loop (see {@code GatewayNoBlockingTest}).
 * </p>
 *
 * <p>
 * Failure reasons never include the target URL or hostname — the error surfaces
 * to the client as a generic {@code route_unavailable} 502.
 * </p>
 */
public final class UpstreamTargetValidator {

    /** IPv4 CGNAT range 100.64.0.0/10 — not marked private by the JDK. */
    private static final int CGNAT_FIRST = 0x64400000; // 100.64.0.0
    private static final int CGNAT_LAST = 0x647FFFFF; // 100.127.255.255

    private final List<Cidr> allowedCidrs;

    /**
     * @param allowedCidrs
     *            CIDR notation ({@code 127.0.0.0/8}, {@code ::1/128}, bare
     *            addresses count as a single-address /32 or /128). Empty means "no
     *            private targets allowed".
     */
    public UpstreamTargetValidator(List<String> allowedCidrs) {
        this.allowedCidrs = new ArrayList<>();
        for (String cidr : allowedCidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            this.allowedCidrs.add(Cidr.parse(cidr.trim()));
        }
    }

    public boolean allowsPrivateTargets() {
        return !allowedCidrs.isEmpty();
    }

    /**
     * Outcome of a target check. {@link #reason()} is null when allowed and is a
     * stable, URL-free category token when rejected.
     */
    public record Result(boolean allowed, String reason) {
        static Result allow() {
            return new Result(true, null);
        }

        static Result deny(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * Validates an upstream base URL. Blocking (DNS); never call on the event loop.
     */
    public Result validate(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            return Result.deny("invalid-url");
        }
        if (uri.getUserInfo() != null) {
            return Result.deny("userinfo-forbidden");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            return Result.deny("invalid-url");
        }

        boolean hostAllowed = cidrsContainHost(host);
        if (!"https".equalsIgnoreCase(scheme) && !hostAllowed) {
            return Result.deny("non-https");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return Result.deny("unresolvable");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address) && !cidrsContainAddress(address)) {
                return Result.deny("non-public-address");
            }
        }
        return Result.allow();
    }

    /**
     * Non-allow-listed addresses must be globally routable. Rejects loopback,
     * any-local, link-local (incl. IPv4 169.254/16), site-local (IPv4 10/8,
     * 172.16/12, 192.168/16; deprecated IPv6 fec0::/10), multicast, IPv4 CGNAT
     * 100.64/10, and IPv6 unique-local fc00::/7.
     */
    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int ipv4 = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16) | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
            if (ipv4 >= CGNAT_FIRST && ipv4 <= CGNAT_LAST) {
                return false;
            }
        } else if (raw.length == 16) {
            // fc00::/7 — unique local addresses (RFC 4193).
            if ((raw[0] & 0xFE) == 0xFC) {
                return false;
            }
        }
        return true;
    }

    private boolean cidrsContainHost(String host) {
        for (Cidr cidr : allowedCidrs) {
            if (cidr.matchesHost(host)) {
                return true;
            }
        }
        return false;
    }

    private boolean cidrsContainAddress(InetAddress address) {
        for (Cidr cidr : allowedCidrs) {
            if (cidr.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /** A single CIDR range, IPv4 or IPv6. */
    private static final class Cidr {
        private final InetAddress network;
        private final int prefixLength;

        static Cidr parse(String text) {
            String[] parts = text.split("/", -1);
            try {
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : -1;
                InetAddress network = InetAddress.getByName(parts[0]);
                int maxBits = network.getAddress().length * 8;
                if (prefix < 0) {
                    prefix = maxBits;
                } else if (prefix < 0 || prefix > maxBits) {
                    throw new IllegalArgumentException("invalid prefix length in CIDR: " + text);
                }
                return new Cidr(network, prefix);
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid CIDR: " + text, e);
            }
        }

        private Cidr(InetAddress network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        boolean matchesHost(String host) {
            try {
                return matches(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                return false;
            }
        }

        boolean matches(InetAddress address) {
            byte[] candidate = address.getAddress();
            byte[] base = network.getAddress();
            if (candidate.length != base.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != base[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                if ((candidate[fullBytes] & mask) != (base[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return network.getHostAddress() + "/" + prefixLength;
        }
    }

    /** Parses a CIDR string; stable for tests. */
    static String normalize(String cidr) {
        return Cidr.parse(cidr).toString().toLowerCase(Locale.ROOT);
    }
}
