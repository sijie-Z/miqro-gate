package com.miqroera.miqrokey.controlplane.client;

import com.miqroera.miqrokey.controlplane.config.ProviderClientProperties;
import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;

import java.net.URI;

/**
 * Single creation point for credential-scoped {@link HttpProviderClient}
 * instances (G3.1). Each upstream credential gets its own client bound to the
 * product's base URL and its own secret — never shared across credentials.
 */
public final class ProviderClientFactory {

    private final UpstreamTargetValidator targetValidator;
    private final ProviderClientProperties properties;

    public ProviderClientFactory(UpstreamTargetValidator targetValidator, ProviderClientProperties properties) {
        this.targetValidator = targetValidator;
        this.properties = properties;
    }

    public HttpProviderClient create(URI baseUrl, String credentialHeader, String credentialValue) {
        return new HttpProviderClient(baseUrl, credentialHeader, credentialValue, targetValidator,
                properties.getConnectTimeout(), properties.getRequestTimeout(), properties.getMaxResponseBytes());
    }
}
