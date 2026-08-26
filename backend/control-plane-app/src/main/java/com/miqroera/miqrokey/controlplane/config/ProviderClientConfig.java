package com.miqroera.miqrokey.controlplane.config;

import com.miqroera.miqrokey.adapters.deepseek.DeepSeekPaygAdapter;
import com.miqroera.miqrokey.adapters.registry.BuiltInAdapterRegistry;
import com.miqroera.miqrokey.adapters.tencent.TencentTokenHubAdapter;
import com.miqroera.miqrokey.controlplane.client.ProviderClientFactory;
import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Compile-time adapter registration and the bounded {@code ProviderClient}
 * surface for control-plane provider calls (G3.1/G3.2).
 *
 * <p>
 * The registry is populated from classpath code only — no runtime discovery.
 * Duplicate {@code adapterId}s abort startup. The SSRF validator is the
 * production default (empty allowlist): control-plane calls may only reach
 * public https endpoints from the signed catalog.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(ProviderClientProperties.class)
public class ProviderClientConfig {

    @Bean
    public BuiltInAdapterRegistry adapterRegistry(ObjectMapper objectMapper) {
        BuiltInAdapterRegistry registry = new BuiltInAdapterRegistry();
        registry.register(new DeepSeekPaygAdapter(objectMapper));
        registry.register(TencentTokenHubAdapter.codingPlan(objectMapper));
        registry.register(TencentTokenHubAdapter.tokenPlanPersonal(objectMapper));
        registry.register(TencentTokenHubAdapter.enterprisePro(objectMapper));
        registry.register(TencentTokenHubAdapter.enterpriseLite(objectMapper));
        registry.register(TencentTokenHubAdapter.paygApi(objectMapper));
        return registry;
    }

    @Bean
    public UpstreamTargetValidator controlPlaneTargetValidator() {
        // Production default: https-only, public addresses only.
        return new UpstreamTargetValidator(List.of());
    }

    /**
     * Factory for per-credential {@link com.miqroera.miqrokey.spi.ProviderClient}
     * instances; see {@link ProviderClientFactory}.
     */
    @Bean
    public ProviderClientFactory providerClientFactory(UpstreamTargetValidator controlPlaneTargetValidator,
            ProviderClientProperties properties) {
        return new ProviderClientFactory(controlPlaneTargetValidator, properties);
    }
}
