package com.miqroera.miqrokey.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.registry.AdapterRegistryFactory;
import com.miqroera.miqrokey.adapters.registry.BuiltInAdapterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adapter wiring for the gateway data plane (G3.x relay): the same compile-time
 * registry the control plane uses. The signed provider catalog bean lives in
 * {@code GatewayFeatureConfig} (Ed25519-verified at load, fails startup on
 * tampering) and gates product resolution.
 */
@Configuration
public class ProviderAdapterConfig {

    @Bean
    public BuiltInAdapterRegistry adapterRegistry(ObjectMapper objectMapper) {
        return AdapterRegistryFactory.create(objectMapper);
    }
}
