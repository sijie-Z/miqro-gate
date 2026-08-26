package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService.TeamMemberView;
import com.miqroera.miqrokey.domain.model.Team;
import com.miqroera.miqrokey.domain.model.TeamStatus;
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

/** Admin teams + members (G5.2, api-contract §5). SYSTEM_ADMIN-only. */
@RestController
@RequestMapping("/api/v1/admin/teams")
public class AdminTeamController {

    private final AdminOrgService orgService;
    private final UserContext userContext;

    public AdminTeamController(AdminOrgService orgService, UserContext userContext) {
        this.orgService = orgService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<Team> list() {
        return orgService.listTeams(userContext.getUser().tenantId());
    }

    @PostMapping
    public Team create(@RequestBody CreateRequest body) {
        var admin = userContext.getUser();
        return orgService.createTeam(admin.tenantId(), admin.id(), body.name(), body.description());
    }

    @PatchMapping("/{teamId}")
    public Team update(@PathVariable UUID teamId, @RequestBody UpdateRequest body) {
        var admin = userContext.getUser();
        return orgService.updateTeam(admin.tenantId(), admin.id(), teamId, body.name(), body.description(),
                body.status());
    }

    @GetMapping("/{teamId}/members")
    public List<TeamMemberView> members(@PathVariable UUID teamId) {
        return orgService.teamMembers(userContext.getUser().tenantId(), teamId);
    }

    @PostMapping("/{teamId}/members")
    public void addMember(@PathVariable UUID teamId, @RequestBody MemberRequest body) {
        var admin = userContext.getUser();
        orgService.addTeamMember(admin.tenantId(), admin.id(), teamId, body.userId());
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    public void removeMember(@PathVariable UUID teamId, @PathVariable UUID userId) {
        var admin = userContext.getUser();
        orgService.removeTeamMember(admin.tenantId(), admin.id(), teamId, userId);
    }

    public record CreateRequest(String name, String description) {
    }

    public record UpdateRequest(String name, String description, TeamStatus status) {
    }

    public record MemberRequest(UUID userId) {
    }
}
