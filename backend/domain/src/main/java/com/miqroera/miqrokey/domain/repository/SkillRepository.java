package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.Skill;
import com.miqroera.miqrokey.domain.model.SkillAccess;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code skills} / {@code skill_access} (V16, P2.2 SkillHub catalog).
 */
public interface SkillRepository {

    Skill insert(Skill skill);

    /** Upsert by (tenant, name) — re-upload replaces the package and metadata. */
    Skill upsert(Skill skill);

    Optional<Skill> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Skill> findByName(UUID tenantId, String name);

    List<Skill> findAllActive(UUID tenantId);

    /** Sets the skill to ARCHIVED (removed from the catalog, data kept). */
    Skill archive(UUID tenantId, UUID skillId);

    /** True when a row was removed (admin archive; a re-upload re-creates it). */
    boolean delete(UUID tenantId, UUID skillId);

    // ---- download grants ----

    void insertAccess(SkillAccess access);

    void deleteAccess(UUID tenantId, UUID skillId, String scopeType, UUID scopeId);

    List<SkillAccess> findAccess(UUID tenantId, UUID skillId);

    /**
     * True when the user may download the skill: no access rows (public), an ADMIN
     * user, or membership in any granted TEAM/PROJECT scope.
     */
    boolean canDownload(UUID tenantId, UUID skillId, UUID userId, boolean admin);
}
