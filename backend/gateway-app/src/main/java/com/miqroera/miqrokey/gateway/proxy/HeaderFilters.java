package com.miqroera.miqrokey.gateway.proxy;

import org.springframework.http.HttpHeaders;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Safe inbound and upstream header filtering for the transparent proxy.
 *
 * <h3>Inbound filtering</h3> Removes credential headers that carry the Virtual
 * Key, preventing them from leaking to the upstream provider.
 *
 * <h3>Upstream (outbound) filtering</h3> Removes hop-by-hop headers and headers
 * that the Gateway must reconstruct (e.g. {@code Host},
 * {@code Content-Length}).
 *
 * <p>
 * All filtering rules follow {@code docs/proxy-and-cc-switch.md} §6.
 * </p>
 */
public final class HeaderFilters {

    private HeaderFilters() {
        // utility class
    }

    /**
     * Headers that carry credentials and must be stripped from inbound requests
     * before forwarding to upstream. The Gateway replaces them with the real
     * upstream credential.
     */
    private static final Set<String> INBOUND_CREDENTIAL_HEADERS = Set.of("authorization", "x-api-key", "api-key");

    /**
     * Hop-by-hop headers that must be removed when forwarding to upstream. These
     * headers are connection-specific and should not be propagated.
     */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    /**
     * Headers the Gateway must reconstruct instead of forwarding.
     */
    private static final Set<String> RECONSTRUCT_HEADERS = Set.of("host", "content-length");

    /**
     * All header names that must be removed before forwarding to upstream.
     */
    private static final Set<String> ALL_STRIP_HEADERS;

    static {
        ALL_STRIP_HEADERS = new java.util.HashSet<>();
        ALL_STRIP_HEADERS.addAll(INBOUND_CREDENTIAL_HEADERS);
        ALL_STRIP_HEADERS.addAll(HOP_BY_HOP_HEADERS);
        ALL_STRIP_HEADERS.addAll(RECONSTRUCT_HEADERS);
    }

    /**
     * Headers that must be propagated from upstream response to the client. All
     * headers except hop-by-hop and connection-specific ones are preserved.
     */
    private static final Set<String> RESPONSE_STRIP_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    /**
     * Filters inbound request headers, removing credential and hop-by-hop headers.
     * Returns a new {@link HttpHeaders} instance with only allowed headers.
     */
    public static HttpHeaders filterInboundHeaders(HttpHeaders inbound) {
        Set<String> connectionHeaders = connectionHeaderTokens(inbound);
        HttpHeaders filtered = new HttpHeaders();
        inbound.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!ALL_STRIP_HEADERS.contains(lower) && !connectionHeaders.contains(lower)
                    && !lower.startsWith("x-miqrokey-")) {
                filtered.addAll(name, values);
            }
        });
        return filtered;
    }

    /**
     * Filters upstream response headers, including extension headers named by
     * {@code Connection} as required by RFC 9110.
     */
    public static HttpHeaders filterResponseHeaders(HttpHeaders upstream) {
        Set<String> connectionHeaders = connectionHeaderTokens(upstream);
        HttpHeaders filtered = new HttpHeaders();
        upstream.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!RESPONSE_STRIP_HEADERS.contains(lower) && !connectionHeaders.contains(lower)) {
                filtered.addAll(name, values);
            }
        });
        return filtered;
    }

    private static Set<String> connectionHeaderTokens(HttpHeaders headers) {
        Set<String> tokens = new HashSet<>();
        for (String value : headers.getOrEmpty(HttpHeaders.CONNECTION)) {
            for (String token : value.split(",")) {
                String normalized = token.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    tokens.add(normalized);
                }
            }
        }
        return tokens;
    }

    /**
     * Returns the list of credential header names that are stripped from inbound
     * requests. Visible for testing.
     */
    public static Set<String> inboundCredentialHeaders() {
        return Set.copyOf(INBOUND_CREDENTIAL_HEADERS);
    }

    /**
     * Returns the list of hop-by-hop header names. Visible for testing.
     */
    public static Set<String> hopByHopHeaders() {
        return Set.copyOf(HOP_BY_HOP_HEADERS);
    }
}
