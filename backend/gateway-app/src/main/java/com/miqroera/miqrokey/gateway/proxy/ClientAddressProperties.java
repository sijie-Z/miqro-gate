package com.miqroera.miqrokey.gateway.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-plane client-address resolution (#605): reverse proxies whose
 * {@code X-Forwarded-For} header is honored when recording the calling party of
 * a usage fact. Empty (the default) means the transport peer is recorded as-is
 * — a request-supplied header is never trusted.
 */
@ConfigurationProperties(prefix = "miqrokey.trusted-proxy")
public class ClientAddressProperties {

    /** Trusted reverse-proxy CIDRs, e.g. {@code 172.28.0.0/24}. */
    private List<String> cidrs = new ArrayList<>();

    public List<String> getCidrs() {
        return cidrs;
    }

    public void setCidrs(List<String> cidrs) {
        this.cidrs = cidrs;
    }
}
