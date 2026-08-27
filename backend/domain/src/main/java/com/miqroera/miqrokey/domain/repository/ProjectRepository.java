package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Project} entities.
 */
public interface ProjectRepository {

    Optional<Project> findById(UUID id);

    Optional<Project> findByTenantIdAndCode(UUID tenantId, String code);

    List<Project> findAllByTenantId(UUID tenantId);

    Project insert(Project project);

    Project update(Project project);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}
