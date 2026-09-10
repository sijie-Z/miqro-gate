package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ModelCatalogView;
import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
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

    private final com.miqroera.miqrokey.domain.service.AuditService auditService;

    public ModelCatalogService(NamedParameterJdbcTemplate jdbc, RouteRefreshPublisher routeRefreshPublisher,
            ObjectFactory<ModelCatalogService> self, com.miqroera.miqrokey.domain.service.AuditService auditService) {
        this.jdbc = jdbc;
        this.routeRefreshPublisher = routeRefreshPublisher;
        this.self = self;
        this.auditService = auditService;
    }

    /**
     * Fetches a product's model catalog and applies it. On any fetch failure the
     * previous catalog rows are kept untouched (last-successful fallback).
     */
    public void refreshProduct(ProviderProductAdapter adapter, ProviderClient client) {
        try {
            probeProduct(adapter, client, FETCH_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("Model catalog fetch failed; keeping last successful catalog", e);
        }
    }

    /**
     * Fetch core shared by the scheduled refresh and the admin probe (#346, I4):
     * fetches the official model catalog and applies it on success only. Failures
     * and empty responses throw — the probe surfaces them, {@link #refreshProduct}
     * swallows them — and the previous rows stay untouched either way.
     */
    public ModelCatalogSnapshot probeProduct(ProviderProductAdapter adapter, ProviderClient client, Duration timeout) {
        ModelCatalogSnapshot snapshot = adapter.fetchModels(client).block(timeout);
        if (snapshot == null) {
            throw new IllegalStateException("model catalog fetch returned an empty response");
        }
        self.getObject().applySnapshot(snapshot);
        return snapshot;
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
        // OFFICIAL rows are fully owned by fetches; MANUAL rows (F18 fallback
        // entries) survive a refresh untouched.
        jdbc.update("DELETE FROM model_catalog WHERE provider_product_id = :productId AND source = 'OFFICIAL'",
                Map.of("productId", productId));
        if (!snapshot.models().isEmpty()) {
            SqlParameterSource[] batch = snapshot.models().stream()
                    .map(m -> new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                            .addValue("modelId", m.id()).addValue("displayName", m.displayName()))
                    .toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("""
                    INSERT INTO model_catalog (id, provider_product_id, model_id, display_name, status, version,
                        source)
                    VALUES (:id, :productId, :modelId, :displayName, 'ACTIVE', 0, 'OFFICIAL')
                    ON CONFLICT (provider_product_id, model_id) DO NOTHING
                    """, batch);
        }
        // AFTER_COMMIT: the gateway never refreshes against uncommitted rows.
        routeRefreshPublisher.publishChanged();
    }

    /**
     * Catalog rows for admin surfaces, optionally filtered by product/source (F18).
     */
    public List<ModelCatalogView> list(UUID providerProductId, String source) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = " WHERE 1 = 1 ";
        if (providerProductId != null) {
            where += " AND provider_product_id = :productId ";
            params.addValue("productId", providerProductId);
        }
        if (source != null && !source.isBlank()) {
            where += " AND source = :source ";
            params.addValue("source", source.trim());
        }
        return jdbc.query("""
                SELECT id, provider_product_id, model_id, display_name, context_window, max_output_tokens, status,
                       source, version, updated_at
                FROM model_catalog%s
                ORDER BY model_id
                """.formatted(where), params, VIEW_MAPPER);
    }

    /**
     * Manual model entry — the probe-failure fallback (F18, Tencent raw 5):
     * administrators can register a model id by hand when the official-API catalog
     * fetch cannot reach the provider. The row is marked MANUAL and is never
     * removed by later official refreshes.
     */
    @Transactional
    public ModelCatalogView addManual(UUID tenantId, UUID adminId, UUID providerProductId, String modelId,
            String displayName, Integer contextWindow, Integer maxOutputTokens, AuditContext context) {
        String normalizedId = modelId.trim();
        if (normalizedId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_ID_INVALID", "模型 ID 不能为空。");
        }
        // The provider catalog is global (no tenant column) — existence only.
        if (jdbc.query("SELECT 1 FROM provider_products WHERE id = :productId", Map.of("productId", providerProductId),
                rs -> rs.next() ? 1 : 0) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "供应商产品不存在。");
        }
        UUID rowId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO model_catalog (id, provider_product_id, model_id, display_name, context_window,
                        max_output_tokens, status, version, source, created_at, updated_at)
                    VALUES (:id, :productId, :modelId, :displayName, :contextWindow, :maxOutputTokens, 'ACTIVE', 0,
                            'MANUAL', now(), now())
                    """,
                    new MapSqlParameterSource("id", rowId).addValue("productId", providerProductId)
                            .addValue("modelId", normalizedId).addValue("displayName", displayName)
                            .addValue("contextWindow", contextWindow).addValue("maxOutputTokens", maxOutputTokens));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_ALREADY_IN_CATALOG", "该模型已在目录中。");
        }
        // AFTER_COMMIT so the gateway never reloads against uncommitted rows.
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, context.actorId(), "MODEL_CATALOG_ADD_MANUAL", "MODEL_CATALOG", rowId,
                AuditSummaries.summary(context, "productId", providerProductId.toString(), "modelId", normalizedId),
                context.requestId());
        return list(providerProductId, "MANUAL").stream().filter(v -> v.id().equals(rowId)).findFirst().orElseThrow();
    }

    /**
     * Removes a MANUAL catalog row. OFFICIAL rows are owned by the fetch pipeline
     * and cannot be deleted by hand.
     */
    @Transactional
    public void removeManual(UUID tenantId, UUID adminId, UUID rowId, AuditContext context) {
        Map<String, Object> row = jdbc.query("""
                SELECT source, model_id FROM model_catalog WHERE id = :id
                """, Map.of("id", rowId), rs -> {
            if (!rs.next()) {
                return null;
            }
            Map<String, Object> found = new java.util.HashMap<>();
            found.put("source", rs.getString("source"));
            found.put("modelId", rs.getString("model_id"));
            return found;
        });
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "目录行不存在。");
        }
        if (!"MANUAL".equals(row.get("source"))) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_NOT_MANUAL", "仅人工录入的模型可删除。");
        }
        jdbc.update("DELETE FROM model_catalog WHERE id = :id", Map.of("id", rowId));
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, context.actorId(), "MODEL_CATALOG_DELETE_MANUAL", "MODEL_CATALOG", rowId,
                AuditSummaries.summary(context, "modelId", String.valueOf(row.get("modelId"))), context.requestId());
    }

    private static final RowMapper<ModelCatalogView> VIEW_MAPPER = (rs, rowNum) -> new ModelCatalogView(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("provider_product_id"), rs.getString("model_id"),
            rs.getString("display_name"), rs.getObject("context_window", Integer.class),
            rs.getObject("max_output_tokens", Integer.class), rs.getString("status"), rs.getString("source"),
            rs.getLong("version"), rs.getTimestamp("updated_at").toInstant());

    private UUID resolveProductId(String productCode) {
        return jdbc.query("""
                SELECT id FROM provider_products WHERE product_code = :productCode
                """, Map.of("productCode", productCode), rs -> rs.next() ? (UUID) rs.getObject(1) : null);
    }
}
