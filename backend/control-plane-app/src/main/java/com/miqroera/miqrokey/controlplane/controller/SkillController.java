package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.SkillView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.SkillService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * SkillHub catalog for signed-in users (P2.3): every ACTIVE skill is visible;
 * downloads are gated by the skill's TEAM/PROJECT grants (403 without one).
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillService skillService;
    private final UserContext userContext;

    public SkillController(SkillService skillService, UserContext userContext) {
        this.skillService = skillService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<SkillView> list() {
        return skillService.list(userContext.getUser().tenantId());
    }

    @GetMapping("/{skillId}")
    public SkillView get(@PathVariable UUID skillId) {
        return skillService.get(userContext.getUser().tenantId(), skillId);
    }

    @GetMapping("/{skillId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID skillId) {
        var user = userContext.getUser();
        SkillService.DownloadResult result = skillService.download(user.tenantId(), skillId, user.id(),
                user.role().name().equals("SYSTEM_ADMIN"));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(result.name() + ".zip").build().toString())
                .body(result.zip());
    }
}
