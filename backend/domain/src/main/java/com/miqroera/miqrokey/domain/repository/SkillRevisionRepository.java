package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.SkillRevision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code skill_revisions} (V49, I14 skill version management).
 * Revision rows are immutable except for the activation pointer; the caller
 * keeps {@code skills} mirrored to the active revision.
 */
public interface SkillRevisionRepository {

    SkillRevision insert(SkillRevision revision);

    /** Highest revision number currently stored for the skill (0 when none). */
    long maxRevision(UUID tenantId, UUID skillId);

    Optional<SkillRevision> findBySkillAndRevision(UUID tenantId, UUID skillId, long revision);

    Optional<SkillRevision> findActive(UUID tenantId, UUID skillId);

    /** Newest first, capped at {@code limit}. */
    List<SkillRevision> listBySkill(UUID tenantId, UUID skillId, int limit);

    /**
     * Clears the activation pointer of every revision except {@code keepRevision}.
     */
    int deactivateOthers(UUID tenantId, UUID skillId, long keepRevision);

    /**
     * Marks {@code revision} as active at {@code activatedAt}; returns rows
     * touched.
     */
    int activate(UUID tenantId, UUID skillId, long revision, Instant activatedAt);

    /**
     * Mirrors the revision metadata + package onto {@code skills} so catalog reads
     * and downloads stay unchanged (also restores ACTIVE status, mirroring the
     * pre-I14 re-upload semantics).
     */
    int mirrorToSkill(UUID tenantId, UUID skillId, String description, String author, String license, List<String> tags,
            List<String> examples, String version, byte[] contentZip, String contentSha256, long contentBytes);
}
