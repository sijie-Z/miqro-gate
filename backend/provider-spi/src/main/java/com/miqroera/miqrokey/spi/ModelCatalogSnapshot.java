package com.miqroera.miqrokey.spi;

import java.time.Instant;
import java.util.List;

/**
 * Result of {@link ProviderProductAdapter#fetchModels}: the models a product
 * exposes plus provenance.
 *
 * @param providerProductId
 *            product the snapshot belongs to
 * @param models
 *            model list (empty when the product exposes none)
 * @param fetchedAt
 *            when the catalog was obtained
 */
public record ModelCatalogSnapshot(String providerProductId, List<ModelDefinition> models, Instant fetchedAt) {

    public ModelCatalogSnapshot {
        if (providerProductId == null || providerProductId.isBlank()) {
            throw new IllegalArgumentException("providerProductId must not be blank");
        }
        if (models == null) {
            throw new IllegalArgumentException("models must not be null");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt must not be null");
        }
        models = List.copyOf(models);
    }
}
