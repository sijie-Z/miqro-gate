package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One download grant for a skill (P2.2, {@code skill_access} V16): a TEAM or
 * PROJECT scope whose members may download the skill. Skills without access
 * rows are public (any signed-in user may download). Visibility of the catalog
 * itself is never gated — only downloads are.
 */
public record SkillAccess(UUID id, UUID tenantId, UUID skillId, String scopeType, UUID scopeId, Instant createdAt) {

    public SkillAccess {
        if (id == null || tenantId == null || skillId == null || scopeId == null) {
            throw new IllegalArgumentException("id/tenantId/skillId/scopeId are required");
        }
        if (scopeType == null || !(scopeType.equals("TEAM") || scopeType.equals("PROJECT"))) {
            throw new IllegalArgumentException("scopeType must be TEAM or PROJECT");
        }
    }
}
