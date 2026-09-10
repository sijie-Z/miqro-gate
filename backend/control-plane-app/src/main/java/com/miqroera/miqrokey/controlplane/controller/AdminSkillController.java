package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.SkillView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.SkillService;
import com.miqroera.miqrokey.domain.model.SkillAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin SkillHub management (P2.3): upload (raw zip body), archive and
 * download-grant management. SYSTEM_ADMIN-only via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/skills")
public class AdminSkillController {

    private final SkillService skillService;
    private final UserContext userContext;

    public AdminSkillController(SkillService skillService, UserContext userContext) {
        this.skillService = skillService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<SkillView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> tags) {
        return skillService.list(userContext.getUser().tenantId(), q, tags);
    }

    /**
     * Uploads a skill package (raw zip body, {@code Content-Type:
     * application/zip}). The SKILL.md frontmatter supplies the catalog metadata;
     * the frontmatter name must match the zip's single root directory. Re-uploading
     * the same name replaces the entry.
     */
    @PostMapping
    public SkillView upload(@RequestParam("version") String version, @RequestBody byte[] zip,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return skillService.upload(user.tenantId(), user.id(), zip, version, requestId(httpReq));
    }

    /** Archives the skill: removed from the catalog; grants kept for restore. */
    @PostMapping("/{skillId}/archive")
    public SkillView archive(@PathVariable UUID skillId, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return skillService.archive(user.tenantId(), user.id(), skillId, requestId(httpReq));
    }

    /** Replaces the download grants; empty list = public skill. */
    @PutMapping("/{skillId}/access")
    public List<SkillAccess> setAccess(@PathVariable UUID skillId, @RequestBody List<SkillService.ScopeRequest> scopes,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return skillService.setAccess(user.tenantId(), user.id(), skillId, scopes, requestId(httpReq));
    }
    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
