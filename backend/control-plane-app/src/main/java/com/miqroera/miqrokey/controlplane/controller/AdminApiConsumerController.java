package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ApiConsumerView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.ApiConsumerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API-consumer management (ADR-0010/0011): create (one-time key), list,
 * disable, and the optional RS256 JWT verification key. SYSTEM_ADMIN-only via
 * RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/api-consumers")
public class AdminApiConsumerController {

    private final ApiConsumerService consumerService;
    private final UserContext userContext;

    public AdminApiConsumerController(ApiConsumerService consumerService, UserContext userContext) {
        this.consumerService = consumerService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<ApiConsumerView> list() {
        return consumerService.list(userContext.getUser().tenantId());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest body) {
        ApiConsumerService.CreatedConsumer created = consumerService.create(userContext.getUser().tenantId(),
                body.name().trim());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("consumer", created.consumer(), "apiKey", created.apiKey(), "shownOnce", true));
    }

    @PostMapping("/{consumerId}/disable")
    public ApiConsumerView disable(@PathVariable UUID consumerId) {
        return consumerService.disable(userContext.getUser().tenantId(), consumerId);
    }

    /**
     * Sets/rotates the RS256 JWT verification key (PEM); returns the fingerprint.
     */
    @PutMapping("/{consumerId}/jwt-key")
    public ApiConsumerView setJwtKey(@PathVariable UUID consumerId, @Valid @RequestBody SetJwtKeyRequest body) {
        return consumerService.setJwtKey(userContext.getUser().tenantId(), consumerId, body.publicKeyPem());
    }

    /** Removes the JWT verification key; JWT auth stops immediately. */
    @DeleteMapping("/{consumerId}/jwt-key")
    public ApiConsumerView removeJwtKey(@PathVariable UUID consumerId) {
        return consumerService.removeJwtKey(userContext.getUser().tenantId(), consumerId);
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record SetJwtKeyRequest(@NotBlank @Size(max = 8192) String publicKeyPem) {
    }
}
