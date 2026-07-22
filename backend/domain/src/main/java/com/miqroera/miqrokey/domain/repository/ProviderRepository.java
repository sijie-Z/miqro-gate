package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.Provider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Provider} entities.
 */
public interface ProviderRepository {

    Optional<Provider> findById(UUID id);

    Optional<Provider> findBySlug(String slug);

    List<Provider> findAll();

    Provider insert(Provider provider);

    Provider update(Provider provider);
}
