package com.miqroera.miqrokey.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Management-portal access restrictions (F05, security §6). An empty
 * {@code ipAllowlist} keeps the historical behavior (no IP restriction); once
 * configured, every portal request (management and self-service surface) must
 * originate from an allowlisted address. {@code trustedProxies} names the
 * reverse proxies whose {@code X-Forwarded-For} header is honored — a direct
 * caller outside this set can never forge the header.
 */
@ConfigurationProperties(prefix = "miqrokey.control.admin-access")
public record AdminAccessProperties(@DefaultValue( {
    }) List<String> ipAllowlist, @DefaultValue({}) List<String> trustedProxies){

    public AdminAccessProperties {
        if (ipAllowlist == null || trustedProxies == null) {
            throw new IllegalArgumentException("admin-access ip-allowlist/trusted-proxies must not be null");
        }
    }
}
