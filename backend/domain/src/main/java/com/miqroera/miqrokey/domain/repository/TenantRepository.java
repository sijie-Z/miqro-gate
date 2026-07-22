package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Tenant} entities.
 */
public interface TenantRepository {

    Optional<Tenant> findById(UUID id);

    Optional<Tenant> findByCode(String code);

    List<Tenant> findAll();

    Tenant insert(Tenant tenant);

    Tenant update(Tenant tenant);

    boolean existsByCode(String code);
}
