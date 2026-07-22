package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.Team;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Team} entities.
 */
public interface TeamRepository {

    Optional<Team> findById(UUID id);

    List<Team> findAllByTenantId(UUID tenantId);

    Team insert(Team team);

    Team update(Team team);
}
