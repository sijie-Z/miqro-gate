package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.ProjectMembership;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link ProjectMembership} join entities.
 */
public interface ProjectMembershipRepository {

    List<ProjectMembership> findAllByProjectId(UUID projectId);

    List<ProjectMembership> findAllByUserId(UUID userId);

    ProjectMembership insert(ProjectMembership membership);

    void delete(UUID projectId, UUID userId);

    boolean exists(UUID projectId, UUID userId);
}
