package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.ProviderProduct;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ProviderProduct} entities.
 */
public interface ProviderProductRepository {

    Optional<ProviderProduct> findById(UUID id);

    Optional<ProviderProduct> findByProviderIdAndProductCode(UUID providerId, String productCode);

    List<ProviderProduct> findAllByProviderId(UUID providerId);

    List<ProviderProduct> findAll();

    ProviderProduct insert(ProviderProduct product);

    ProviderProduct update(ProviderProduct product);
}
