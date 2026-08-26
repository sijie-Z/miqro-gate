package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService.UserCreated;
import com.miqroera.miqrokey.controlplane.service.AdminOrgService.UserPasswordReset;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin users (G5.2, api-contract §5): create (one-time temporary password),
 * disable/enable, password reset (sessions revoked, new temporary password
 * returned once) and session revocation. SYSTEM_ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminOrgService orgService;
    private final UserContext userContext;

    public AdminUserController(AdminOrgService orgService, UserContext userContext) {
        this.orgService = orgService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<User> list() {
        return orgService.listUsers(userContext.getUser().tenantId());
    }

    @PostMapping
    public UserCreated create(@RequestBody CreateRequest body) {
        var admin = userContext.getUser();
        return orgService.createUser(admin.tenantId(), admin.id(), body.username(), body.displayName(), body.role());
    }

    @PatchMapping("/{userId}")
    public User updateStatus(@PathVariable UUID userId, @RequestBody StatusRequest body) {
        var admin = userContext.getUser();
        return orgService.updateUserStatus(admin.tenantId(), admin.id(), userId, body.status());
    }

    @PostMapping("/{userId}/reset-password")
    public UserPasswordReset resetPassword(@PathVariable UUID userId) {
        var admin = userContext.getUser();
        return orgService.resetPassword(admin.tenantId(), admin.id(), userId);
    }

    @PostMapping("/{userId}/revoke-sessions")
    public void revokeSessions(@PathVariable UUID userId) {
        var admin = userContext.getUser();
        orgService.revokeSessions(admin.tenantId(), admin.id(), userId);
    }

    public record CreateRequest(String username, String displayName, UserRole role) {
    }

    public record StatusRequest(UserStatus status) {
    }
}
