package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Maintains {@code model_catalog} from the provider official-API model catalog
 * (G2.3 "上游模型" layer of the {@code /v1/models} intersection).
 *
 * <p>
 * <b>Success-only writes ("上游失败可回退最后成功目录"):</b>
 * {@link #applySnapshot(ModelCatalogSnapshot)} is only ever called with a
 * snapshot of a <em>successful</em> fetch. A failed fetch never touches
 * {@code model_catalog}, so the gateway keeps serving the last successful
 * catalog. {@link #refreshProduct} enforces this boundary: any adapter error is
 * logged and swallowed before the snapshot is applied.
 * </p>
 *
 * <p>
 * {@code applySnapshot} replaces the product's rows transactionally (the
 * official API is the source of truth for OFFICIAL_API products; admin curation
 * flags are a future workflow), then publishes the route-refresh notification
 * AFTER_COMMIT so the gateway reloads the upstream-models layer promptly. The
 * gateway's scheduled refresh remains the safety net.
 * </p>
 *
 * <p>
 * Adapters are registered from G3.x on; until then the service is exercised by
 * contract tests with fake adapters.
 * </p>
 */
@Service
public class ModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);

    /**
     * Hard bound on a single official-API catalog fetch (control plane, MVC
     * thread).
     */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

    private final NamedParameterJdbcTemplate jdbc;
    private final RouteRefreshPublisher routeRefreshPublisher;
    /**
     * Self-proxy: {@link #refreshProduct} must cross the transactional boundary of
     * {@link #applySnapshot} through the Spring proxy — a direct self-call would
     * bypass {@code @Transactional} and split the replace into two autocommit
     * statements (a crash between them would serve an empty catalog instead of the
     * last successful one).
     */
    private final ObjectFactory<ModelCatalogService> self;

    public ModelCatalogService(NamedParameterJdbcTemplate jdbc, RouteRefreshPublisher routeRefreshPublisher,
            ObjectFactory<ModelCatalogService> self) {
        this.jdbc = jdbc;
        this.routeRefreshPublisher = routeRefreshPublisher;
        this.self = self;
    }

    /**
     * Fetches a product's model catalog and applies it. On any fetch failure the
     * previous catalog rows are kept untouched (last-successful fallback).
     */
    public void refreshProduct(ProviderProductAdapter adapter, ProviderClient client) {
        ModelCatalogSnapshot snapshot;
        try {
            snapshot = adapter.fetchModels(client).block(FETCH_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("Model catalog fetch failed; keeping last successful catalog", e);
            return;
        }
        if (snapshot == null) {
            log.warn("Model catalog fetch returned null; keeping last successful catalog");
            return;
        }
        self.getObject().applySnapshot(snapshot);
    }
    /**
     * Replaces a product's {@code model_catalog} rows with a successful fetch's
     * snapshot. Rows are keyed by {@code provider_products.product_code} (the
     * snapshot's {@code providerProductId}); an unknown product code is logged and
     * skipped — no row is invented for a product outside the catalog.
     */
    @Transactional
    public void applySnapshot(ModelCatalogSnapshot snapshot) {
        UUID productId = resolveProductId(snapshot.providerProductId());
        if (productId == null) {
            log.warn("No provider_products row for product code '{}'; model catalog not updated",
                    snapshot.providerProductId());
            return;
        }
        jdbc.update("DELETE FROM model_catalog WHERE provider_product_id = :productId", Map.of("productId", productId));
        if (!snapshot.models().isEmpty()) {
            SqlParameterSource[] batch = snapshot.models().stream()
                    .map(m -> new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                            .addValue("modelId", m.id()).addValue("displayName", m.displayName()))
                    .toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("""
                    INSERT INTO model_catalog (id, provider_product_id, model_id, display_name, status, version)
                    VALUES (:id, :productId, :modelId, :displayName, 'ACTIVE', 0)
                    """, batch);
        }
        // AFTER_COMMIT: the gateway never refreshes against uncommitted rows.
        routeRefreshPublisher.publishChanged();
    }

    private UUID resolveProductId(String productCode) {
        return jdbc.query("""
                SELECT id FROM provider_products WHERE product_code = :productCode
                """, Map.of("productCode", productCode), rs -> rs.next() ? (UUID) rs.getObject(1) : null);
    }
}
