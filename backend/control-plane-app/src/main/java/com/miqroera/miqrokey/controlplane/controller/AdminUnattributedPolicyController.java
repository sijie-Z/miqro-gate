package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.UnattributedPolicyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant unattributed-request policy (Spec v1.1 §7.3, #647): configures how
 * requests that cannot be attributed are routed (dedicated credential + product
 * + model scope) and accounted (UNATTRIBUTED bucket project). SYSTEM_ADMIN-only
 * via the deny-by-default {@code /api/v1/admin/**} interceptor. Unconfigured
 * tenants keep the fail-closed {@code 400 CONTEXT_REQUIRED} baseline.
 */
@RestController
@RequestMapping("/api/v1/admin/unattributed-policy")
public class AdminUnattributedPolicyController {

    private final UnattributedPolicyService policyService;
    private final UserContext userContext;

    public AdminUnattributedPolicyController(UnattributedPolicyService policyService, UserContext userContext) {
        this.policyService = policyService;
        this.userContext = userContext;
    }

    @GetMapping
    public UnattributedPolicyService.PolicyView get() {
        return policyService.get(userContext.getUser().tenantId());
    }

    @PutMapping
    public UnattributedPolicyService.PolicyView put(@RequestBody UpsertPolicyRequest body) {
        var admin = userContext.getUser();
        return policyService.put(admin.tenantId(), admin.id(), body.credentialId(), body.providerProductId(),
                body.models());
    }

    @DeleteMapping
    public void delete() {
        var admin = userContext.getUser();
        policyService.delete(admin.tenantId(), admin.id());
    }

    public record UpsertPolicyRequest(UUID credentialId, UUID providerProductId, List<String> models) {
    }
}
