package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.MePlazaView;
import com.miqroera.miqrokey.domain.model.GrantStatus;
import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Model plaza (#1201): the caller's usable models plus the catalog models they
 * could ask for — assembled from the same inputs the gateway gates on.
 *
 * <p>
 * Per ACTIVE key whose grant is ACTIVE, the usable set is {@code key models ∩
 * grant models ∩ ACTIVE model_catalog}, exactly the control-plane-visible part
 * of the {@code /v1/models} intersection (the gateway additionally requires the
 * product to be in the signed provider catalog; a product unknown there yields
 * nothing at the gateway, which the control plane cannot see). Models in the
 * catalog but missing from a key become {@code requestable} rows — the
 * candidates the model-approval flow (§8.2) was built for.
 * </p>
 *
 * <p>
 * Prices are the latest unit-price snapshots per (product, model, token type) —
 * the same rows the cost report prices calls with, so the number shown here is
 * the number a call will be charged at. No prices means {@code null}, never a
 * zero placeholder (#878 semantics).
 * </p>
 */
@Service
public class MePlazaService {

    private final VirtualKeyRepository keyRepository;
    private final ProjectProviderGrantRepository grantRepository;
    private final ProviderProductRepository productRepository;
    private final PriceSnapshotRepository priceRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public MePlazaService(VirtualKeyRepository keyRepository, ProjectProviderGrantRepository grantRepository,
            ProviderProductRepository productRepository, PriceSnapshotRepository priceRepository,
            NamedParameterJdbcTemplate jdbc) {
        this.keyRepository = keyRepository;
        this.grantRepository = grantRepository;
        this.productRepository = productRepository;
        this.priceRepository = priceRepository;
        this.jdbc = jdbc;
    }

    /**
     * The caller's plaza. Users without usable keys get empty lists, not an error.
     */
    public MePlazaView view(User user) {
        List<VirtualKey> keys = keyRepository.findAllByUserId(user.id()).stream()
                .filter(key -> key.status() == VirtualKeyStatus.ACTIVE).toList();
        if (keys.isEmpty()) {
            return new MePlazaView(List.of(), List.of());
        }

        Map<UUID, ProjectProviderGrant> grants = activeGrants(user, keys);
        Map<UUID, Map<String, CatalogRow>> catalog = catalogByProduct(grants.values());
        Map<UUID, ProviderProduct> products = new HashMap<>();
        Map<PriceKey, PriceSnapshot> prices = latestPrices();

        Map<ModelKey, ModelAccumulator> usable = new LinkedHashMap<>();
        List<MePlazaView.RequestableModel> requestable = new ArrayList<>();
        for (VirtualKey key : keys) {
            ProjectProviderGrant grant = grants.get(key.grantId());
            if (grant == null) {
                // A revoked/cross-tenant grant authorizes nothing — the key is
                // silently inert here, matching the gateway's binding check.
                continue;
            }
            Map<String, CatalogRow> catalogRows = catalog.getOrDefault(grant.providerProductId(), Map.of());
            ProviderProduct product = products.computeIfAbsent(grant.providerProductId(),
                    id -> productRepository.findById(id).orElse(null));
            MePlazaView.PlazaKeyRef ref = new MePlazaView.PlazaKeyRef(key.id(), key.name(),
                    key.displayPrefix() + "…" + key.lastFour());
            Set<String> keyModels = keyRepository.findModelIds(key.id());
            Set<String> grantModels = grantRepository.findModelIds(grant.id());
            for (CatalogRow row : catalogRows.values()) {
                if (keyModels.contains(row.modelId())) {
                    if (grantModels.contains(row.modelId())) {
                        usable.computeIfAbsent(new ModelKey(grant.providerProductId(), row.modelId()),
                                ignored -> new ModelAccumulator(row)).keys().add(ref);
                    }
                    // On the key but outside the grant: not usable, and the
                    // approval flow cannot re-grant it (it is already there).
                } else {
                    requestable.add(new MePlazaView.RequestableModel(row.modelId(), row.displayName(),
                            row.contextWindow(), row.maxOutputTokens(), grant.providerProductId(),
                            product == null ? null : product.productCode(),
                            product == null ? null : product.displayName(), key.id(), key.name()));
                }
            }
        }

        List<MePlazaView.PlazaModel> models = new ArrayList<>(usable.size());
        for (Map.Entry<ModelKey, ModelAccumulator> entry : usable.entrySet()) {
            ModelKey key = entry.getKey();
            CatalogRow row = entry.getValue().row();
            ProviderProduct product = products.get(key.providerProductId());
            models.add(new MePlazaView.PlazaModel(row.modelId(), row.displayName(), row.contextWindow(),
                    row.maxOutputTokens(), key.providerProductId(), product == null ? null : product.productCode(),
                    product == null ? null : product.displayName(),
                    priceOf(prices, key.providerProductId(), row.modelId()), List.copyOf(entry.getValue().keys())));
        }
        models.sort(Comparator.comparing(MePlazaView.PlazaModel::modelId)
                .thenComparing(m -> m.providerProductCode() == null ? "" : m.providerProductCode()));
        requestable.sort(Comparator.comparing(MePlazaView.RequestableModel::modelId)
                .thenComparing(m -> m.keyName() == null ? "" : m.keyName()));

        return new MePlazaView(models, requestable);
    }

    // -------------------------------------------------------------------
    // lookups
    // -------------------------------------------------------------------

    /**
     * The caller's ACTIVE grants, keyed by grant id; a null value marks a grant
     * that exists in a key's binding but does not authorize (missing, cross-tenant,
     * or not ACTIVE).
     */
    private Map<UUID, ProjectProviderGrant> activeGrants(User user, List<VirtualKey> keys) {
        Map<UUID, ProjectProviderGrant> grants = new HashMap<>();
        for (VirtualKey key : keys) {
            if (grants.containsKey(key.grantId())) {
                continue;
            }
            ProjectProviderGrant grant = grantRepository.findById(key.grantId())
                    .filter(g -> g.tenantId().equals(user.tenantId()) && g.status() == GrantStatus.ACTIVE).orElse(null);
            grants.put(key.grantId(), grant);
        }
        return grants;
    }

    /**
     * ACTIVE catalog rows of the products in play, grouped by product and model id.
     */
    private Map<UUID, Map<String, CatalogRow>> catalogByProduct(Iterable<ProjectProviderGrant> grants) {
        Set<UUID> productIds = new LinkedHashSet<>();
        for (ProjectProviderGrant grant : grants) {
            if (grant != null) {
                productIds.add(grant.providerProductId());
            }
        }
        Map<UUID, Map<String, CatalogRow>> catalog = new HashMap<>();
        if (productIds.isEmpty()) {
            return catalog;
        }
        jdbc.query("""
                SELECT provider_product_id, model_id, display_name, context_window, max_output_tokens
                FROM model_catalog
                WHERE provider_product_id IN (:productIds) AND status = 'ACTIVE'
                ORDER BY provider_product_id, model_id
                """, new MapSqlParameterSource("productIds", productIds), rs -> {
            UUID productId = rs.getObject("provider_product_id", UUID.class);
            catalog.computeIfAbsent(productId, ignored -> new LinkedHashMap<>()).put(rs.getString("model_id"),
                    new CatalogRow(rs.getString("model_id"), rs.getString("display_name"),
                            (Integer) rs.getObject("context_window"), (Integer) rs.getObject("max_output_tokens")));
        });
        return catalog;
    }

    /**
     * Latest unit-price snapshot per (product, model, token type) as of now — the
     * same read the admin price list uses.
     */
    private Map<PriceKey, PriceSnapshot> latestPrices() {
        Map<PriceKey, PriceSnapshot> prices = new HashMap<>();
        for (PriceSnapshot snapshot : priceRepository.findAllLatestAt(Instant.now())) {
            prices.put(new PriceKey(snapshot.providerProductId(), snapshot.modelId(), snapshot.tokenType()), snapshot);
        }
        return prices;
    }

    private static MePlazaView.PlazaPrice priceOf(Map<PriceKey, PriceSnapshot> prices, UUID productId, String modelId) {
        PriceSnapshot input = prices.get(new PriceKey(productId, modelId, PriceTokenType.INPUT));
        PriceSnapshot output = prices.get(new PriceKey(productId, modelId, PriceTokenType.OUTPUT));
        PriceSnapshot cacheRead = prices.get(new PriceKey(productId, modelId, PriceTokenType.CACHE_READ));
        PriceSnapshot cacheCreation = prices.get(new PriceKey(productId, modelId, PriceTokenType.CACHE_CREATION));
        if (input == null && output == null && cacheRead == null && cacheCreation == null) {
            // No snapshot at all: undefined price, not a zero one (#878).
            return null;
        }
        return new MePlazaView.PlazaPrice(unitPrice(input), unitPrice(output), unitPrice(cacheRead),
                unitPrice(cacheCreation), currencyOf(input, output, cacheRead, cacheCreation));
    }

    private static BigDecimal unitPrice(PriceSnapshot snapshot) {
        return snapshot == null ? null : snapshot.unitPrice();
    }

    /**
     * First available currency; snapshots of one model are written in one currency.
     */
    private static String currencyOf(PriceSnapshot... snapshots) {
        for (PriceSnapshot snapshot : snapshots) {
            if (snapshot != null && snapshot.currency() != null) {
                return snapshot.currency();
            }
        }
        return null;
    }

    private record ModelKey(UUID providerProductId, String modelId) {
    }

    private record PriceKey(UUID providerProductId, String modelId, PriceTokenType tokenType) {
    }

    private record CatalogRow(String modelId, String displayName, Integer contextWindow, Integer maxOutputTokens) {
    }

    /**
     * Mutable accumulation cell for one (product, model) while keys are folded in.
     */
    private record ModelAccumulator(CatalogRow row, Set<MePlazaView.PlazaKeyRef> keys) {
        ModelAccumulator(CatalogRow row) {
            this(row, new LinkedHashSet<>());
        }
    }
}
