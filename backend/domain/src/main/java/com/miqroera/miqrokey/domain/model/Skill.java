package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A SkillHub catalog entry (P2.2, {@code skills} V16) following the Anthropic
 * Agent Skills format: a validated zip package whose {@code SKILL.md}
 * frontmatter supplies the catalog metadata. Every ACTIVE skill is visible to
 * all signed-in users; downloads are gated by {@link SkillAccess} rows.
 *
 * @param contentZip
 *            the validated skill package (zip with the skill directory at the
 *            root)
 * @param contentSha256
 *            SHA-256 hex of the package (dedupe / integrity display)
 * @param contentBytes
 *            package size in bytes (download display)
 */
public record Skill(UUID id, UUID tenantId, String name, String description, String version, String author,
        String license, List<String> tags, byte[] contentZip, String contentSha256, long contentBytes, String status,
        UUID createdBy, long rowVersion, Instant createdAt, Instant updatedAt) {

    public Skill {
        if (id == null || tenantId == null || name == null || name.isBlank() || description == null
                || description.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("id/tenantId/name/description/version are required");
        }
        if (contentZip == null || contentZip.length == 0) {
            throw new IllegalArgumentException("contentZip is required");
        }
        contentZip = contentZip.clone();
    }

    @Override
    public byte[] contentZip() {
        return contentZip.clone();
    }
}
