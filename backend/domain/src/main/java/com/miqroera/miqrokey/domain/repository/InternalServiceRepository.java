package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.InternalService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code services} (V18, P3.2 internal service registry).
 */
public interface InternalServiceRepository {

    InternalService insert(InternalService service);

    Optional<InternalService> findByIdAndTenantId(UUID id, UUID tenantId);

    List<InternalService> findAllByTenantId(UUID tenantId);

    /** Status update with optimistic version bump; returns the stored row. */
    InternalService updateStatus(UUID tenantId, UUID serviceId, String status, long expectedVersion);
}
