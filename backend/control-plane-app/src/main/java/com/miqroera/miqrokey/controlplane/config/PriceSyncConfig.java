package com.miqroera.miqrokey.controlplane.config;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.client.OpenRouterPriceSourceClient;
import com.miqroera.miqrokey.controlplane.client.PriceSourceClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Price-catalog sync wiring (issue #585). The source client is a compile-time
 * adapter; only its URL and bounds are operational configuration.
 */
@Configuration
@EnableConfigurationProperties(PriceSyncProperties.class)
public class PriceSyncConfig {

    @Bean
    public PriceSourceClient priceSourceClient(ObjectMapper objectMapper, PriceSyncProperties properties) {
        return new OpenRouterPriceSourceClient(objectMapper, properties);
    }
}
