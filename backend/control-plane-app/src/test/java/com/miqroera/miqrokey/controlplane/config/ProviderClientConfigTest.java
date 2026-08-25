package com.miqroera.miqrokey.controlplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.deepseek.DeepSeekPaygAdapter;
import com.miqroera.miqrokey.adapters.registry.BuiltInAdapterRegistry;
import com.miqroera.miqrokey.controlplane.client.HttpProviderClient;
import com.miqroera.miqrokey.controlplane.client.ProviderClientFactory;
import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProviderClientConfig (G3.1)")
class ProviderClientConfigTest {

    private final ProviderClientConfig config = new ProviderClientConfig();

    @Test
    @DisplayName("registers the DeepSeek PAYG adapter at compile time")
    void registryContainsDeepSeekAdapter() {
        BuiltInAdapterRegistry registry = config.adapterRegistry(new ObjectMapper());

        assertThat(registry.adapterIds()).containsExactly(DeepSeekPaygAdapter.ADAPTER_ID);
        assertThat(registry.findById(DeepSeekPaygAdapter.ADAPTER_ID)).isPresent().get()
                .isInstanceOf(DeepSeekPaygAdapter.class);
    }

    @Test
    @DisplayName("production default validator allows no private targets")
    void productionValidatorAllowsNothingPrivate() {
        UpstreamTargetValidator validator = config.controlPlaneTargetValidator();

        assertThat(validator.allowsPrivateTargets()).isFalse();
    }

    @Test
    @DisplayName("the factory builds credential-scoped HttpProviderClient instances")
    void factoryBuildsCredentialScopedClients() {
        ProviderClientFactory factory = config.providerClientFactory(config.controlPlaneTargetValidator(),
                new ProviderClientProperties());

        assertThat(factory.create(URI.create("https://api.deepseek.com"), "Authorization", "Bearer sk-a"))
                .isInstanceOf(HttpProviderClient.class);
    }
}
