package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AdminApiKeyView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin lifecycle for open admin API keys (ADR-0015). SYSTEM_ADMIN-only via the
 * deny-by-default {@code /api/v1/admin/**} interceptor. The plaintext secret is
 * returned exactly once at issue.
 */
@RestController
@RequestMapping("/api/v1/admin/api-keys")
public class AdminApiKeyController {

    private final AdminApiKeyService service;
    private final UserContext userContext;

    public AdminApiKeyController(AdminApiKeyService service, UserContext userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    public List<AdminApiKeyView> list() {
        return service.list(userContext.getUser().tenantId());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> issue(@Valid @RequestBody IssueRequest body,
            @RequestParam(required = false) Instant expiresAt) {
        var user = userContext.getUser();
        AdminApiKeyService.Issued issued = service.issue(user.tenantId(), user.id(), body.name().trim(), expiresAt);
        AdminApiKeyView view = service.list(user.tenantId()).stream().filter(v -> v.id().equals(issued.id()))
                .findFirst().orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("key", view, "secret", issued.secret(), "shownOnce", true));
    }

    @PostMapping("/{keyId}/revoke")
    public AdminApiKeyView revoke(@PathVariable UUID keyId) {
        var user = userContext.getUser();
        return service.revoke(user.tenantId(), user.id(), keyId);
    }

    public record IssueRequest(@NotBlank @Size(max = 200) String name) {
    }
}
