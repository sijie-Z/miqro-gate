package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyRequest;
import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyResponse;
import com.miqroera.miqrokey.controlplane.dto.VirtualKeyView;
import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import com.miqroera.miqrokey.controlplane.service.VirtualKeyService;
import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Open admin Virtual Key delegation (ADR-0016 增补 2026-09-09, 案 1): a machine
 * key authenticates and the operator (its issuing SYSTEM_ADMIN) creates a key
 * <em>for</em> the target user — ownership, membership and grant invariants are
 * evaluated against the target exactly as in self-service, so the 1:1 key
 * semantics never weaken. The full secret is returned once, same as
 * {@code POST /api/v1/me/virtual-keys}.
 */
@RestController
@RequestMapping("/api/v1/admin-api/virtual-keys")
public class OpenAdminVirtualKeysController {

    private final VirtualKeyService virtualKeyService;

    public OpenAdminVirtualKeysController(VirtualKeyService virtualKeyService) {
        this.virtualKeyService = virtualKeyService;
    }

    /**
     * Delegated creation: body = {@link CreateVirtualKeyRequest} fields plus the
     * target {@code userId}. Responds 201 with the one-time secret.
     */
    @PostMapping
    public ResponseEntity<CreateVirtualKeyResponse> createForUser(HttpServletRequest request,
            @Valid @RequestBody DelegatedCreateRequest body) {
        UUID tenantId = tenantId(request);
        UUID issuer = issuerId(request, tenantId);
        CreateVirtualKeyResponse resp = virtualKeyService.createForUser(tenantId, issuer, body.userId(),
                body.toCreateRequest(), requestId(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /** Keys owned by one tenant user (safe metadata; useful for flow queries). */
    @GetMapping
    public List<VirtualKeyView> listForUser(HttpServletRequest request, @RequestParam UUID userId) {
        return virtualKeyService.listForTenantUser(tenantId(request), userId);
    }

    public record DelegatedCreateRequest(@NotNull UUID userId, @Size(max = 200) String name, @NotNull UUID projectId,
            @NotNull UUID providerProductId, @NotNull UUID credentialGrantId, @NotNull VirtualKeyPurpose purpose,
            List<@Size(max = 128) String> allowedModels, @Pattern(regexp = "DISABLED|ENABLED") String cachePolicy) {

        CreateVirtualKeyRequest toCreateRequest() {
            return new CreateVirtualKeyRequest(name, projectId, providerProductId, credentialGrantId, purpose,
                    allowedModels, cachePolicy);
        }
    }

    // -------------------------------------------------------------------

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }

    private static UUID issuerId(HttpServletRequest request, UUID tenantId) {
        UUID issuer = (UUID) request.getAttribute(AdminApiKeyAuthFilter.ISSUER_ATTR);
        if (issuer == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EXECUTOR_UNKNOWN", "无法确定执行委托人：该机器密钥缺少发行管理员。");
        }
        return issuer;
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
