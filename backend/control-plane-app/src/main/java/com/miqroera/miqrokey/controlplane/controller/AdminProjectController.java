package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService.ProjectMemberView;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    public Project create(@Valid @RequestBody ProjectCreateRequest body) {
        var admin = userContext.getUser();
        return orgService.createProject(admin.tenantId(), admin.id(), body.code(), body.name(), body.projectTag());
    }

    @PatchMapping("/{projectId}")
    public Project update(@PathVariable UUID projectId, @Valid @RequestBody ProjectUpdateRequest body) {
        var admin = userContext.getUser();
        return orgService.updateProject(admin.tenantId(), admin.id(), projectId, body.name(), body.projectTag(),
                body.status());
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberView> members(@PathVariable UUID projectId) {
        return orgService.projectMembers(userContext.getUser().tenantId(), projectId);
    }

    @PostMapping("/{projectId}/members")
    public void addMember(@PathVariable UUID projectId, @RequestBody ProjectMemberRequest body) {
        var admin = userContext.getUser();
        orgService.addProjectMember(admin.tenantId(), admin.id(), projectId, body.userId());
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public void removeMember(@PathVariable UUID projectId, @PathVariable UUID userId) {
        var admin = userContext.getUser();
        orgService.removeProjectMember(admin.tenantId(), admin.id(), projectId, userId);
    }

    // -------------------------------------------------------------------
    // CAA Project Registry (V56, Spec v1.1 §7.4)
    // -------------------------------------------------------------------

    @GetMapping("/{projectId}/repositories")
    public List<AdminOrgService.ProjectRepoMappingView> repositories(@PathVariable UUID projectId) {
        return orgService.projectRepositories(userContext.getUser().tenantId(), projectId);
    }

    @PostMapping("/{projectId}/repositories")
    public AdminOrgService.ProjectRepoMappingView addRepository(@PathVariable UUID projectId,
            @RequestBody RepoKeyRequest body) {
        var admin = userContext.getUser();
        return orgService.addProjectRepository(admin.tenantId(), admin.id(), projectId, body.repoKey());
    }

    @DeleteMapping("/{projectId}/repositories/{mappingId}")
    public void removeRepository(@PathVariable UUID projectId, @PathVariable UUID mappingId) {
        var admin = userContext.getUser();
        orgService.removeProjectRepository(admin.tenantId(), admin.id(), projectId, mappingId);
    }

    /**
     * Widths mirror the columns (projects.code varchar(64), name varchar(200), and
     * the project_tag CHECK ^[A-Za-z0-9_-]{1,64}$ enforced in the service). Without
     * these the first signal an over-long value produced was a 409
     * RESOURCE_CONFLICT from the JDBC translation — a "duplicate or referenced"
     * message for what is really "too long". A missing code was likewise reported
     * as PROJECT_CODE_TAKEN, conflating "you sent nothing" with "someone else has
     * it".
     */
    public record ProjectCreateRequest(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 200) String name,
            String projectTag) {
    }

    public record ProjectUpdateRequest(@Size(max = 200) String name, String projectTag, ProjectStatus status) {
    }

    public record ProjectMemberRequest(UUID userId) {
    }

    public record RepoKeyRequest(String repoKey) {
    }
}
