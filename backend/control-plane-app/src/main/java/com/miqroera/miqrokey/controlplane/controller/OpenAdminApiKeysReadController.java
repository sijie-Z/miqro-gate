package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AdminApiKeyView;
import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.AdminApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Open admin key listing (ADR-0015, batch 1b read subset): the tenant's machine
 * keys as views — digest and plaintext never leave the store, so a key can
 * audit its own fleet without seeing other tenants' rows.
 */
@RestController
@RequestMapping("/api/v1/admin-api/api-keys")
public class OpenAdminApiKeysReadController {

    private final AdminApiKeyService adminApiKeyService;

    public OpenAdminApiKeysReadController(AdminApiKeyService adminApiKeyService) {
        this.adminApiKeyService = adminApiKeyService;
    }

    @GetMapping
    public List<AdminApiKeyView> list(HttpServletRequest request) {
        return adminApiKeyService.list(tenantId(request));
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }
}
