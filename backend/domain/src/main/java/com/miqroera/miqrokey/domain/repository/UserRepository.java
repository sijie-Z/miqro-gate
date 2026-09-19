package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link User} entities.
 */
public interface UserRepository {

    Optional<User> findById(UUID id);

    /**
     * Batch form of {@link #findById}: one query for the whole ID set. Used by list
     * rendering so the query count stays constant as rows grow.
     */
    List<User> findAllByIds(Collection<UUID> ids);

    Optional<User> findByTenantIdAndUsername(UUID tenantId, String username);

    List<User> findAllByTenantId(UUID tenantId);

    List<User> findAllByTenantIdAndStatus(UUID tenantId, String status);

    User insert(User user);

    User update(User user);

    boolean existsByTenantIdAndUsername(UUID tenantId, String username);

    int countByTenantId(UUID tenantId);

    /**
     * Lock the tenant row for serializing bootstrap. Uses SELECT ... FOR UPDATE
     * inside a transaction to prevent concurrent bootstrap races.
     */
    void lockTenantForBootstrap(UUID tenantId);

    /**
     * Find a user by ID with a row-level lock (SELECT ... FOR UPDATE). Used for
     * atomic failed-login counter increments under concurrency.
     */
    Optional<User> findByIdForUpdate(UUID id);
}
