package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ProjectProviderGrant} entities.
 */
public interface ProjectProviderGrantRepository {

    Optional<ProjectProviderGrant> findById(UUID id);

    List<ProjectProviderGrant> findAllByProjectId(UUID projectId);

    List<ProjectProviderGrant> findAllByProjectIdAndStatus(UUID projectId, String status);

    List<ProjectProviderGrant> findAllByCredentialId(UUID credentialId);

    ProjectProviderGrant insert(ProjectProviderGrant grant);

    ProjectProviderGrant update(ProjectProviderGrant grant);

    boolean existsByProjectIdAndProductIdAndCredentialId(UUID projectId, UUID providerProductId, UUID credentialId);
}
