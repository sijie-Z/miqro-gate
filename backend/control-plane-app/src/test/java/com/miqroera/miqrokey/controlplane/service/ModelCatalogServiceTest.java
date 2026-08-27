package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.ModelDefinition;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModelCatalogService}: the success-only write contract —
 * a successful fetch replaces the product's rows and publishes the route
 * refresh; a failed fetch (or an unknown product code) never touches
 * {@code model_catalog} nor publishes, so the gateway keeps serving the last
 * successful catalog.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService")
class ModelCatalogServiceTest {

    @Mock
    NamedParameterJdbcTemplate jdbc;
    @Mock
    RouteRefreshPublisher publisher;
    @Mock
    ProviderClient client;
    @Mock
    ProviderProductAdapter adapter;

    private final UUID productId = UUID.randomUUID();
    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(jdbc, publisher, () -> service);
    }

    @Test
    @DisplayName("a successful snapshot replaces the product's rows and publishes the refresh")
    void applySnapshotReplacesRows() {
        when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<ResultSetExtractor<UUID>>any())).thenReturn(productId);

        service.applySnapshot(snapshot("deepseek-payg-api", "m1", "m2"));

        verify(jdbc).update(eq("DELETE FROM model_catalog WHERE provider_product_id = :productId"), anyMap());
        ArgumentCaptor<SqlParameterSource[]> batch = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbc).batchUpdate(anyString(), batch.capture());
        assertThat(batch.getValue()).hasSize(2);
        // Both models of the snapshot land in the batch.
        assertThat(batch.getValue()[0].getValue("modelId")).isEqualTo("m1");
        assertThat(batch.getValue()[1].getValue("modelId")).isEqualTo("m2");
        verify(publisher).publishChanged();
    }

    @Test
    @DisplayName("an empty snapshot deletes stale rows without a batch, still publishes")
    void applySnapshotWithEmptyCatalog() {
        when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<ResultSetExtractor<UUID>>any())).thenReturn(productId);

        service.applySnapshot(snapshot("deepseek-payg-api"));

        verify(jdbc).update(eq("DELETE FROM model_catalog WHERE provider_product_id = :productId"), anyMap());
        verify(jdbc, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
        verify(publisher).publishChanged();
    }

    @Test
    @DisplayName("an unknown product code is skipped — no rows, no publish")
    void applySnapshotUnknownProduct() {
        when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<ResultSetExtractor<UUID>>any())).thenReturn(null);

        service.applySnapshot(snapshot("ghost-product", "m1"));

        verify(jdbc, never()).update(anyString(), anyMap());
        verify(jdbc, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("a failed fetch keeps the last successful catalog and publishes nothing")
    void refreshProductFailureKeepsLastGood() {
        when(adapter.fetchModels(client)).thenReturn(Mono.error(new RuntimeException("provider unreachable")));

        service.refreshProduct(adapter, client);

        verifyNoInteractions(jdbc);
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("a successful fetch replaces the catalog (delegates to applySnapshot)")
    void refreshProductSuccessApplies() {
        when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<ResultSetExtractor<UUID>>any())).thenReturn(productId);
        when(adapter.fetchModels(client)).thenReturn(Mono.just(snapshot("deepseek-payg-api", "m1", "m2", "m3")));

        service.refreshProduct(adapter, client);

        verify(jdbc).update(eq("DELETE FROM model_catalog WHERE provider_product_id = :productId"), anyMap());
        verify(publisher).publishChanged();
    }

    private static ModelCatalogSnapshot snapshot(String productCode, String... modelIds) {
        List<ModelDefinition> models = java.util.Arrays.stream(modelIds).map(m -> new ModelDefinition(m, "Model " + m))
                .toList();
        return new ModelCatalogSnapshot(productCode, models, Instant.EPOCH);
    }
}
