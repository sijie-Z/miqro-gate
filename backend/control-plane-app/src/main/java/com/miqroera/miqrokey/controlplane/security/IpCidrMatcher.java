package com.miqroera.miqrokey.controlplane.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * CIDR membership test for the admin-IP allowlist (F05, security §6). Pure
 * helper — {@link #matches(String)} throws when the CIDR or the address is not
 * parseable so a misconfigured allowlist fails fast at startup instead of
 * silently locking out or opening up the portal.
 */
public final class IpCidrMatcher {

    private final InetAddress network;
    private final int prefixBits;

    private IpCidrMatcher(InetAddress network, int prefixBits) {
        this.network = network;
        this.prefixBits = prefixBits;
    }

    /** Parses {@code 1.2.3.0/24} or {@code 2001:db8::/32} style CIDR text. */
    public static IpCidrMatcher parse(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            throw new IllegalArgumentException("CIDR must not be blank");
        }
        String[] parts = cidr.trim().split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("CIDR must be <address>/<prefix>: " + cidr);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("CIDR prefix must be numeric: " + cidr);
        }
        InetAddress network;
        try {
            network = InetAddress.getByName(parts[0]);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("CIDR network address is not parseable: " + cidr);
        }
        int maxBits = network.getAddress().length * 8;
        if (prefix < 0 || prefix > maxBits) {
            throw new IllegalArgumentException("CIDR prefix out of range for the address family: " + cidr);
        }
        return new IpCidrMatcher(network, prefix);
    }

    /** True when the address text falls inside this CIDR block. */
    public boolean matches(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        InetAddress candidate;
        try {
            candidate = InetAddress.getByName(address.trim());
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
