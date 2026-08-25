package com.miqroera.miqrokey.spi;

import java.util.Set;

/**
 * How an adapter injects the upstream credential and which inbound auth headers
 * must be stripped first (credential smuggling prevention,
 * {@code docs/provider-adapter-contract.md §4}).
 *
 * @param headerName
 *            header to set on the upstream request
 * @param prefix
 *            value prefix, e.g. {@code "Bearer "}; empty when none
 * @param stripInboundHeaders
 *            inbound auth header names (lowercase) to remove from the forwarded
 *            request, e.g. {@code ["authorization", "x-api-key"]}
 */
public record CredentialInjection(String headerName, String prefix, Set<String> stripInboundHeaders) {

    public CredentialInjection {
        if (headerName == null || headerName.isBlank()) {
            throw new IllegalArgumentException("headerName must not be blank");
        }
        if (prefix == null) {
            throw new IllegalArgumentException("prefix must not be null");
        }
        if (stripInboundHeaders == null) {
            throw new IllegalArgumentException("stripInboundHeaders must not be null");
        }
        stripInboundHeaders = Set.copyOf(stripInboundHeaders);
    }
}
