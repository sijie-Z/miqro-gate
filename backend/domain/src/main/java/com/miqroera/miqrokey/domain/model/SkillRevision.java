package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One immutable snapshot of a SkillHub package (I14, {@code skill_revisions}
 * V49, Tencent raw 20): a same-name upload publishes the next revision and
 * activates it; rollback activates an older revision without creating a new
 * number. The active revision's metadata + package are mirrored onto
 * {@link Skill} so catalog reads and downloads keep reading the parent row.
 *
 * @param contentZip
 *            the validated skill package bytes (cloned on the way in and out)
 */
public record SkillRevision(UUID id, UUID tenantId, UUID skillId, long revision, String version, String description,
        String author, String license, List<String> tags, List<String> examples, byte[] contentZip,
        String contentSha256, long contentBytes, UUID createdBy, Instant createdAt, Instant activatedAt) {

    public SkillRevision {
        tags = tags == null ? List.of() : List.copyOf(tags);
        examples = examples == null ? List.of() : List.copyOf(examples);
        if (id == null || tenantId == null || skillId == null || revision < 1 || version == null || version.isBlank()
                || description == null || description.isBlank() || contentZip == null || contentZip.length == 0
                || contentSha256 == null || contentSha256.isBlank() || createdBy == null || createdAt == null) {
            throw new IllegalArgumentException("id/tenantId/skillId/revision/version/description/sha are required");
        }
        contentZip = contentZip.clone();
    }

    @Override
    public byte[] contentZip() {
        return contentZip.clone();
    }

    /** Whether this revision is the one downloads currently serve. */
    public boolean active() {
        return activatedAt != null;
    }
}
