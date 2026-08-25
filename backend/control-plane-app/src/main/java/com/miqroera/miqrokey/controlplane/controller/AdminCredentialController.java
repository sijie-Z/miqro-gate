package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AdminCredentialCreateRequest;
import com.miqroera.miqrokey.controlplane.dto.CredentialDetailView;
import com.miqroera.miqrokey.controlplane.dto.CredentialView;
import com.miqroera.miqrokey.controlplane.dto.RotateCredentialRequest;
import com.miqroera.miqrokey.controlplane.dto.ValidateCredentialRequest;
import com.miqroera.miqrokey.controlplane.dto.ValidateCredentialResponse;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminCredentialService;
import com.miqroera.miqrokey.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin upstream-credential endpoints (api-contract §5): create, validate,
 * rotate, disable, list, detail. Only SYSTEM_ADMIN role reaches this mapping
 * (RoleInterceptor). Responses carry masked metadata only; plaintext secrets
 * never leave the request body.
 */
@RestController
@RequestMapping("/api/v1/admin/credentials")
public class AdminCredentialController {

    private final AdminCredentialService credentialService;
    private final UserContext userContext;

    public AdminCredentialController(AdminCredentialService credentialService, UserContext userContext) {
        this.credentialService = credentialService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<CredentialView> list() {
        return credentialService.list(user());
    }

    @GetMapping("/{id}")
    public CredentialDetailView get(@PathVariable UUID id) {
        return credentialService.detail(user(), id);
    }

    @PostMapping
    public ResponseEntity<CredentialView> create(@Valid @RequestBody AdminCredentialCreateRequest request,
            HttpServletRequest httpReq) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(credentialService.create(user(), request, requestId(httpReq)));
    }

    /**
     * Tests a candidate secret without persisting anything.
     */
    @PostMapping("/{id}/validate")
    public ValidateCredentialResponse validate(@PathVariable UUID id,
            @Valid @RequestBody ValidateCredentialRequest request, HttpServletRequest httpReq) {
        return credentialService.validate(user(), id, request, requestId(httpReq));
    }

    @PostMapping("/{id}/rotate")
    public CredentialView rotate(@PathVariable UUID id, @Valid @RequestBody RotateCredentialRequest request,
            HttpServletRequest httpReq) {
        return credentialService.rotate(user(), id, request, requestId(httpReq));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable UUID id, HttpServletRequest httpReq) {
        credentialService.disable(user(), id, requestId(httpReq));
        return ResponseEntity.ok(Map.of("message", "Credential disabled"));
    }

    // -------------------------------------------------------------------

    private User user() {
        return userContext.getUser();
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header == null || header.isBlank() ? null : header;
    }
}
