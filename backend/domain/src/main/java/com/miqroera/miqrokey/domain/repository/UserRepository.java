package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link User} entities.
 */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByTenantIdAndUsername(UUID tenantId, String username);

    List<User> findAllByTenantId(UUID tenantId);

    List<User> findAllByTenantIdAndStatus(UUID tenantId, String status);

    User insert(User user);

    User update(User user);

    boolean existsByTenantIdAndUsername(UUID tenantId, String username);

    int countByTenantId(UUID tenantId);
}
