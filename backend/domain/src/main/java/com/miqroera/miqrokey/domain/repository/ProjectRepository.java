package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.Project;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Project} entities.
 */
public interface ProjectRepository {

    Optional<Project> findById(UUID id);

    /**
     * Batch form of {@link #findById}: one query for the whole ID set. Used by list
     * rendering so the query count stays constant as rows grow.
     */
    List<Project> findAllByIds(Collection<UUID> ids);

    Optional<Project> findByTenantIdAndCode(UUID tenantId, String code);

    List<Project> findAllByTenantId(UUID tenantId);

    Project insert(Project project);

    Project update(Project project);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}
