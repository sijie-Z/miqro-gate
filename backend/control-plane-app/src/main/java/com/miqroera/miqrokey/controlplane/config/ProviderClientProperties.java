package com.miqroera.miqrokey.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Bounds for the control plane's {@code ProviderClient} implementation
 * ({@code HttpProviderClient}) — the single place that enforces timeouts, the
 * response size cap and the SSRF gate for control-plane provider calls.
 */
@ConfigurationProperties(prefix = "miqrokey.control.provider-client")
public class ProviderClientProperties {

    /** TCP connect timeout to the provider. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** Overall request deadline (headers + body). */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /** Cap on a single provider response body (credential/model/plan calls). */
    private int maxResponseBytes = 1024 * 1024;

    /**
     * Additional CIDRs the control-plane SSRF gate may reach besides public
     * addresses (G4.2: quota refreshes against locally hosted or intranet provider
     * gateways). Default empty = public https only. Mirrors the gateway's
     * {@code MIQROKEY_UPSTREAM_ALLOWED_CIDRS}.
     */
    private List<String> allowedCidrs = List.of();

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public List<String> getAllowedCidrs() {
        return allowedCidrs;
    }

    public void setAllowedCidrs(List<String> allowedCidrs) {
        this.allowedCidrs = allowedCidrs != null ? allowedCidrs : List.of();
    }
}
