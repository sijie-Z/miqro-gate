package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService.ProjectMemberView;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin projects + members (G5.2, api-contract §5). SYSTEM_ADMIN-only. */
@RestController
@RequestMapping("/api/v1/admin/projects")
public class AdminProjectController {

    private final AdminOrgService orgService;
    private final UserContext userContext;

    public AdminProjectController(AdminOrgService orgService, UserContext userContext) {
        this.orgService = orgService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<Project> list() {
        return orgService.listProjects(userContext.getUser().tenantId());
    }

    @PostMapping
    public Project create(@RequestBody CreateRequest body) {
        var admin = userContext.getUser();
        return orgService.createProject(admin.tenantId(), admin.id(), body.code(), body.name(), body.projectTag());
    }

    @PatchMapping("/{projectId}")
    public Project update(@PathVariable UUID projectId, @RequestBody UpdateRequest body) {
        var admin = userContext.getUser();
        return orgService.updateProject(admin.tenantId(), admin.id(), projectId, body.name(), body.projectTag(),
                body.status());
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberView> members(@PathVariable UUID projectId) {
        return orgService.projectMembers(userContext.getUser().tenantId(), projectId);
    }

    @PostMapping("/{projectId}/members")
    public void addMember(@PathVariable UUID projectId, @RequestBody MemberRequest body) {
        var admin = userContext.getUser();
        orgService.addProjectMember(admin.tenantId(), admin.id(), projectId, body.userId());
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public void removeMember(@PathVariable UUID projectId, @PathVariable UUID userId) {
        var admin = userContext.getUser();
        orgService.removeProjectMember(admin.tenantId(), admin.id(), projectId, userId);
    }

    public record CreateRequest(String code, String name, String projectTag) {
    }

    public record UpdateRequest(String name, String projectTag, ProjectStatus status) {
    }

    public record MemberRequest(UUID userId) {
    }
}
