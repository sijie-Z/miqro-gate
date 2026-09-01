package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.SkillView;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.Skill;
import com.miqroera.miqrokey.domain.model.SkillAccess;
import com.miqroera.miqrokey.domain.model.Team;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.SkillRepository;
import com.miqroera.miqrokey.domain.repository.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * SkillHub catalog (P2.2/P2.3, {@code skills} / {@code skill_access} V16):
 * admin uploads validated skill packages (Anthropic Agent Skills format, parsed
 * by {@link SkillZipValidator}); every ACTIVE skill is visible to all signed-in
 * users; downloads are gated by TEAM/PROJECT grants (no grants = public).
 * Re-uploading the same name upserts the entry; archiving hides it.
 */
@Service
public class SkillService {

    private final SkillRepository skillRepository;
    private final TeamRepository teamRepository;
    private final ProjectRepository projectRepository;

    public SkillService(SkillRepository skillRepository, TeamRepository teamRepository,
            ProjectRepository projectRepository) {
        this.skillRepository = skillRepository;
        this.teamRepository = teamRepository;
        this.projectRepository = projectRepository;
    }

    /** Validates and stores a skill package; re-upload replaces the entry. */
    @Transactional
    public SkillView upload(UUID tenantId, UUID adminId, byte[] zipBytes, String version) {
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_INVALID", "版本必须是语义化版本号（如 1.0.0）。");
        }
        SkillZipValidator.SkillMetadata meta;
        try {
            meta = SkillZipValidator.validate(zipBytes);
        } catch (SkillZipValidator.SkillValidationException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.code(), e.getMessage());
        }
        Instant now = Instant.now();
        Skill skill = new Skill(UUID.randomUUID(), tenantId, meta.name(), meta.description(), version, meta.author(),
                meta.license(), meta.tags(), zipBytes, sha256Hex(zipBytes), zipBytes.length, "ACTIVE", adminId, 0, now,
                now);
        Skill stored = skillRepository.upsert(skill);
        return toView(stored);
    }

    /** Catalog for signed-in users: every ACTIVE skill. */
    public List<SkillView> list(UUID tenantId) {
        return skillRepository.findAllActive(tenantId).stream().map(this::toView).toList();
    }

    public SkillView get(UUID tenantId, UUID skillId) {
        return toView(findActive(tenantId, skillId));
    }

    /** Package bytes for download; 403 when the user holds no grant. */
    public DownloadResult download(UUID tenantId, UUID skillId, UUID userId, boolean admin) {
        Skill skill = findActive(tenantId, skillId);
        if (!skillRepository.canDownload(tenantId, skillId, userId, admin)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SKILL_DOWNLOAD_FORBIDDEN", "当前账号无该技能的下载授权。");
        }
        return new DownloadResult(skill.name(), skill.contentZip());
    }

    public record DownloadResult(String name, byte[] zip) {
    }

    /** Archives the skill: removed from the catalog, grants kept for restore. */
    @Transactional
    public SkillView archive(UUID tenantId, UUID skillId) {
        find(tenantId, skillId);
        return toView(skillRepository.archive(tenantId, skillId));
    }

    /**
     * Replaces the download grants (admin): each scope is validated against the
     * teams/projects tables. Empty list = public.
     */
    @Transactional
    public List<SkillAccess> setAccess(UUID tenantId, UUID skillId, List<ScopeRequest> scopes) {
        Skill skill = find(tenantId, skillId);
        List<SkillAccess> existing = skillRepository.findAccess(tenantId, skillId);
        for (SkillAccess access : existing) {
            skillRepository.deleteAccess(tenantId, skillId, access.scopeType(), access.scopeId());
        }
        for (ScopeRequest scope : scopes) {
            validateScope(tenantId, scope);
            skillRepository.insertAccess(new SkillAccess(UUID.randomUUID(), tenantId, skill.id(), scope.scopeType(),
                    scope.scopeId(), Instant.now()));
        }
        return skillRepository.findAccess(tenantId, skillId);
    }

    private void validateScope(UUID tenantId, ScopeRequest scope) {
        if (scope.scopeId() == null || scope.scopeType() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID", "授权范围格式无效。");
        }
        if (scope.scopeType().equals("TEAM")) {
            Team team = teamRepository.findById(scope.scopeId())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID", "授权团队不存在。"));
            if (!team.tenantId().equals(tenantId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID", "授权团队不存在。");
            }
        } else if (scope.scopeType().equals("PROJECT")) {
            Project project = projectRepository.findById(scope.scopeId())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID", "授权项目不存在。"));
            if (!project.tenantId().equals(tenantId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID", "授权项目不存在。");
            }
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_INVALID", "scopeType 必须是 TEAM 或 PROJECT。");
        }
    }

    private Skill find(UUID tenantId, UUID skillId) {
        return skillRepository.findByIdAndTenantId(skillId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "技能不存在。"));
    }

    /** Catalog access only ever exposes ACTIVE skills (archived = hidden). */
    private Skill findActive(UUID tenantId, UUID skillId) {
        Skill skill = find(tenantId, skillId);
        if (!"ACTIVE".equals(skill.status())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "技能不存在。");
        }
        return skill;
    }

    private SkillView toView(Skill skill) {
        return new SkillView(skill.id(), skill.name(), skill.description(), skill.version(), skill.author(),
                skill.license(), skill.tags(), skill.contentSha256(), skill.contentBytes(), skill.status(),
                skill.createdAt());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record ScopeRequest(@jakarta.validation.constraints.NotBlank String scopeType,
            @jakarta.validation.constraints.NotNull UUID scopeId) {
    }
}
