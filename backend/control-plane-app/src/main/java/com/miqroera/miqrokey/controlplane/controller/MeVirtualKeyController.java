package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyRequest;
import com.miqroera.miqrokey.controlplane.dto.CreateVirtualKeyResponse;
import com.miqroera.miqrokey.controlplane.dto.VirtualKeyView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.VirtualKeyService;
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
 * Self-service Virtual Key endpoints (api-contract §4): list, create, detail,
 * rotate, revoke. The full secret appears only in the create/rotate response
 * ({@code shownOnce}); everything else is safe metadata.
 *
 * <p>
 * Errors are RFC 9457 problem+json via {@link GlobalExceptionHandler}; an
 * {@code ApiException} with a status carries the business rule violation.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/me/virtual-keys")
public class MeVirtualKeyController {

    private final VirtualKeyService virtualKeyService;
    private final UserContext userContext;

    public MeVirtualKeyController(VirtualKeyService virtualKeyService, UserContext userContext) {
        this.virtualKeyService = virtualKeyService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<VirtualKeyView> list() {
        return virtualKeyService.list(user());
    }

    @PostMapping
    public ResponseEntity<CreateVirtualKeyResponse> create(@Valid @RequestBody CreateVirtualKeyRequest request,
            HttpServletRequest httpReq) {
        CreateVirtualKeyResponse resp = virtualKeyService.create(user(), request, requestId(httpReq));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/{id}")
    public VirtualKeyView get(@PathVariable UUID id) {
        return virtualKeyService.get(user(), id);
    }

    @PostMapping("/{id}/rotate")
    public CreateVirtualKeyResponse rotate(@PathVariable UUID id, HttpServletRequest httpReq) {
        return virtualKeyService.rotate(user(), id, requestId(httpReq));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable UUID id, HttpServletRequest httpReq) {
        virtualKeyService.revoke(user(), id, requestId(httpReq));
        return ResponseEntity.ok(Map.of("message", "Virtual key revoked"));
    }

    // -------------------------------------------------------------------

    private User user() {
        return userContext.getUser();
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
