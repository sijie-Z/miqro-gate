package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.Skill;
import com.miqroera.miqrokey.domain.model.SkillRevision;
import com.miqroera.miqrokey.domain.repository.SkillRepository;
import com.miqroera.miqrokey.domain.repository.SkillRevisionRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Skill package versioning (I14, Tencent raw 20 版本管理): a same-name upload
 * publishes an immutable snapshot ({@code skill_revisions} V49) and moves the
 * activation pointer; the active revision's metadata + package are mirrored
 * onto {@code skills} so catalog reads and downloads stay unchanged. History is
 * never pruned and rollback is an idempotent pointer move that does not mint a
 * new revision number. Publish/activate record audit events
 * (SKILL_REVISION_PUBLISH / SKILL_REVISION_ACTIVATE).
 */
@Service
public class SkillRevisionService {

    private final SkillRepository skillRepository;
    private final SkillRevisionRepository revisionRepository;
    private final AuditService auditService;

    public SkillRevisionService(SkillRepository skillRepository, SkillRevisionRepository revisionRepository,
            AuditService auditService) {
        this.skillRepository = skillRepository;
        this.revisionRepository = revisionRepository;
        this.auditService = auditService;
    }

    /** First upload of a skill: baseline revision 1, active. */
    @Transactional
    public SkillRevision recordBaseline(UUID tenantId, UUID adminId, Skill skill, Instant now) {
        return revisionRepository.insert(new SkillRevision(UUID.randomUUID(), tenantId, skill.id(), 1, skill.version(),
                skill.description(), skill.author(), skill.license(), skill.tags(), skill.examples(),
                skill.contentZip(), skill.contentSha256(), skill.contentBytes(), adminId, now, now));
    }

    /**
     * Same-name re-upload (raw 20 版本管理): the validated package becomes the next
     * revision, becomes active and is mirrored onto the skills row. The caller has
     * already validated the zip and the semantic version.
     */
    @Transactional
    public SkillRevision publishValidated(UUID tenantId, UUID adminId, SkillZipValidator.SkillMetadata meta,
            String version, byte[] zipBytes, String requestId) {
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_INVALID", "版本必须是语义化版本号（如 1.0.0）。");
        }
        Skill skill = skillRepository.findByName(tenantId, meta.name())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "技能不存在。"));
        long revision = revisionRepository.maxRevision(tenantId, skill.id()) + 1;
        Instant now = Instant.now();
        SkillRevision created = new SkillRevision(UUID.randomUUID(), tenantId, skill.id(), revision, version,
                meta.description(), meta.author(), meta.license(), meta.tags(), meta.examples(), zipBytes,
                SkillService.sha256Hex(zipBytes), zipBytes.length, adminId, now, now);
        try {
            // Clear the current activation pointer BEFORE inserting the active
            // revision: the partial unique index allows one active row per skill.
            revisionRepository.deactivateOthers(tenantId, skill.id(), revision);
            revisionRepository.insert(created);
            revisionRepository.mirrorToSkill(tenantId, skill.id(), meta.description(), meta.author(), meta.license(),
                    meta.tags(), meta.examples(), version, zipBytes, created.contentSha256(), created.contentBytes());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SKILL_REVISION_CONFLICT", "并发发布冲突，请刷新后重试。");
        }
        auditService.record(tenantId, adminId, "SKILL_REVISION_PUBLISH", "SKILL", skill.id(), AuditSummaries.summary(
                "name", AuditSummaries.sanitize(skill.name()), "revision", revision, "version", version), requestId);
        return created;
    }

    /** Newest-first history; metadata view only, never the package bytes. */
    public List<SkillRevisionView> list(UUID tenantId, UUID skillId, int limit) {
        requireSkill(tenantId, skillId);
        return revisionRepository.listBySkill(tenantId, skillId, limit).stream().map(SkillRevisionView::of).toList();
    }

    /**
     * Rollback / activation: moving the pointer to an older revision never mints a
     * new number and repeats are idempotent (activating the already-active revision
     * is a no-op success). Mirrors metadata + package onto the skill row.
     */
    @Transactional
    public SkillRevisionView activate(UUID tenantId, UUID adminId, UUID skillId, long revision, String requestId) {
        requireSkill(tenantId, skillId);
        SkillRevision target = revisionRepository.findBySkillAndRevision(tenantId, skillId, revision)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SKILL_REVISION_NOT_FOUND", "技能修订不存在。"));
        if (target.activatedAt() == null) {
            revisionRepository.deactivateOthers(tenantId, skillId, revision);
            revisionRepository.activate(tenantId, skillId, revision, Instant.now());
            revisionRepository.mirrorToSkill(tenantId, skillId, target.description(), target.author(), target.license(),
                    target.tags(), target.examples(), target.version(), target.contentZip(), target.contentSha256(),
                    target.contentBytes());
            auditService.record(tenantId, adminId, "SKILL_REVISION_ACTIVATE", "SKILL", skillId,
                    AuditSummaries.summary("revision", revision), requestId);
        }
        return SkillRevisionView
                .of(revisionRepository.findBySkillAndRevision(tenantId, skillId, revision).orElseThrow());
    }

    private void requireSkill(UUID tenantId, UUID skillId) {
        skillRepository.findByIdAndTenantId(skillId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "技能不存在。"));
    }

    /** History view: metadata only (the package stays server-side). */
    public record SkillRevisionView(UUID id, UUID skillId, long revision, String version, String description,
            String author, String license, List<String> tags, List<String> examples, String contentSha256,
            long contentBytes, UUID createdBy, Instant createdAt, Instant activatedAt) {

        static SkillRevisionView of(SkillRevision revision) {
            return new SkillRevisionView(revision.id(), revision.skillId(), revision.revision(), revision.version(),
                    revision.description(), revision.author(), revision.license(), revision.tags(), revision.examples(),
                    revision.contentSha256(), revision.contentBytes(), revision.createdBy(), revision.createdAt(),
                    revision.activatedAt());
        }
    }
}
