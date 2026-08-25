package com.miqroera.miqrokey.adapters.registry;

import com.miqroera.miqrokey.spi.AdapterCapabilities;
import com.miqroera.miqrokey.spi.CredentialInjection;
import com.miqroera.miqrokey.spi.CredentialMaterial;
import com.miqroera.miqrokey.spi.CredentialCheck;
import com.miqroera.miqrokey.spi.InboundRequest;
import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.PlanSnapshot;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import com.miqroera.miqrokey.spi.RouteContext;
import com.miqroera.miqrokey.spi.SubscriptionContext;
import com.miqroera.miqrokey.spi.TargetRequest;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObserver;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BuiltInAdapterRegistry compile-time registration")
class BuiltInAdapterRegistryTest {

    @Test
    @DisplayName("adapters register and resolve by id")
    void registerAndResolve() {
        BuiltInAdapterRegistry registry = new BuiltInAdapterRegistry();
        registry.register(dummy("deepseek-payg-api"));
        registry.register(dummy("zhipu-payg-api"));

        assertThat(registry.findById("deepseek-payg-api")).isPresent();
        assertThat(registry.findById("zhipu-payg-api")).isPresent();
        assertThat(registry.findById("not-registered")).isEmpty();
        assertThat(registry.adapterIds()).containsExactly("deepseek-payg-api", "zhipu-payg-api");
    }

    @Test
    @DisplayName("duplicate adapterId aborts registration")
    void duplicateAdapterIdRejected() {
        BuiltInAdapterRegistry registry = new BuiltInAdapterRegistry();
        registry.register(dummy("deepseek-payg-api"));
        assertThatThrownBy(() -> registry.register(dummy("deepseek-payg-api")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate adapterId");
    }

    @Test
    @DisplayName("null adapter or blank adapterId is rejected")
    void nullRejected() {
        BuiltInAdapterRegistry registry = new BuiltInAdapterRegistry();
        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(dummy(" "))).isInstanceOf(IllegalArgumentException.class);
    }

    /** Minimal dummy adapter used to prove the registration mechanism. */
    private static ProviderProductAdapter dummy(String id) {
        return new ProviderProductAdapter() {
            @Override
            public String adapterId() {
                return id;
            }

            @Override
            public Set<ProtocolFamily> protocols() {
                return Set.of(ProtocolFamily.OPENAI_COMPATIBLE);
            }

            @Override
            public TargetRequest resolve(RouteContext route, InboundRequest request) {
                throw new UnsupportedOperationException("dummy");
            }

            @Override
            public CredentialInjection credentialInjection(CredentialMaterial credential) {
                throw new UnsupportedOperationException("dummy");
            }

            @Override
            public Mono<CredentialCheck> validateCredential(ProviderClient client) {
                throw new UnsupportedOperationException("dummy");
            }

            @Override
            public Mono<ModelCatalogSnapshot> fetchModels(ProviderClient client) {
                throw new UnsupportedOperationException("dummy");
            }

            @Override
            public UsageObserver createUsageObserver(UsageContext context) {
                throw new UnsupportedOperationException("dummy");
            }

            @Override
            public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
                throw new UnsupportedOperationException("dummy");
            }

            @Override
            public AdapterCapabilities capabilities() {
                throw new UnsupportedOperationException("dummy");
            }
        };
    }
}
