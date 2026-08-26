package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService;
import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin grants (G5.2, api-contract §5): project × provider product × credential
 * grants with a model scope. SYSTEM_ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/admin/grants")
public class AdminGrantController {

    private final AdminOrgService orgService;
    private final UserContext userContext;

    public AdminGrantController(AdminOrgService orgService, UserContext userContext) {
        this.orgService = orgService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<ProjectProviderGrant> list() {
        return orgService.listGrants(userContext.getUser().tenantId());
    }

    @PostMapping
    public ProjectProviderGrant create(@RequestBody CreateRequest body) {
        var admin = userContext.getUser();
        return orgService.createGrant(admin.tenantId(), admin.id(), body.projectId(), body.providerProductId(),
                body.credentialId(), body.models());
    }

    @GetMapping("/{grantId}/models")
    public List<String> models(@PathVariable UUID grantId) {
        return orgService.grantModels(userContext.getUser().tenantId(), grantId);
    }

    @PostMapping("/{grantId}/models")
    public ProjectProviderGrant updateModels(@PathVariable UUID grantId, @RequestBody ModelsRequest body) {
        var admin = userContext.getUser();
        return orgService.updateGrantModels(admin.tenantId(), admin.id(), grantId, body.models());
    }

    @DeleteMapping("/{grantId}")
    public void disable(@PathVariable UUID grantId) {
        var admin = userContext.getUser();
        orgService.disableGrant(admin.tenantId(), admin.id(), grantId);
    }

    public record CreateRequest(UUID projectId, UUID providerProductId, UUID credentialId, List<String> models) {
    }

    public record ModelsRequest(List<String> models) {
    }
}
