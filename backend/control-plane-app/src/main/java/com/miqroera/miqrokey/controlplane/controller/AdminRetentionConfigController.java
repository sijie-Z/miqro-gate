package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminRetentionConfigService;
import com.miqroera.miqrokey.domain.model.RetentionConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance-retention switch (ADR-0014 v3 Accepted, api-contract §5.26):
 * SYSTEM_ADMIN-only, default fully off. v1 exposes only the enable/disable
 * toggle; content scope (USER_TEXT_ONLY) and envelope key version are fixed
 * deployment defaults until P5 lands.
 */
@RestController
@RequestMapping("/api/v1/admin/retention-config")
public class AdminRetentionConfigController {

    private final AdminRetentionConfigService service;
    private final UserContext userContext;

    public AdminRetentionConfigController(AdminRetentionConfigService service, UserContext userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    public RetentionConfig get() {
        return service.view(userContext.getUser().tenantId());
    }

    public record UpdateRequest(Boolean enabled) {
    }

    @PutMapping
    public RetentionConfig put(@RequestBody UpdateRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return service.configure(user.tenantId(), user.id(), body.enabled(), requestId(httpReq));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header == null ? "" : header;
    }
}
