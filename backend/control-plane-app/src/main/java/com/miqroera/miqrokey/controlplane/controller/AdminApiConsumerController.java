package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ApiConsumerView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.ApiConsumerActivityService;
import com.miqroera.miqrokey.controlplane.service.ApiConsumerService;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
    private final ApiConsumerActivityService activityService;
    private final UserContext userContext;

    public AdminApiConsumerController(ApiConsumerService consumerService, ApiConsumerActivityService activityService,
            UserContext userContext) {
        this.consumerService = consumerService;
        this.activityService = activityService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<ApiConsumerView> list() {
        return consumerService.list(userContext.getUser().tenantId());
    }

    @PostMapping
    public ResponseEntity<CreateApiConsumerResponse> create(@Valid @RequestBody CreateRequest body,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        ApiConsumerService.CreatedConsumer created = consumerService.create(user.tenantId(), user.id(),
                body.name().trim(), parseExpiresAt(body.expiresAt()), requestId(httpReq));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateApiConsumerResponse(created.consumer(), created.apiKey(), true));
    }

    /** #322: optional ISO-8601 expiry; malformed values are rejected up front. */
    private static java.time.Instant parseExpiresAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONSUMER_EXPIRES_INVALID",
                    "expiresAt 必须是 ISO-8601 时刻（UTC）。");
        }
    }

    @PostMapping("/{consumerId}/disable")
    public ApiConsumerView disable(@PathVariable UUID consumerId, HttpServletRequest httpReq) {
        return consumerService.disable(userContext.getUser().tenantId(), userContext.getUser().id(), consumerId,
                requestId(httpReq));
    }

    /**
     * Sets/rotates the RS256 JWT verification key (PEM); returns the fingerprint.
     */
    @PutMapping("/{consumerId}/jwt-key")
    public ApiConsumerView setJwtKey(@PathVariable UUID consumerId, @Valid @RequestBody SetJwtKeyRequest body,
            HttpServletRequest httpReq) {
        return consumerService.setJwtKey(userContext.getUser().tenantId(), userContext.getUser().id(), consumerId,
                body.publicKeyPem(), requestId(httpReq));
    }

    /** Removes the JWT verification key; JWT auth stops immediately. */
    @DeleteMapping("/{consumerId}/jwt-key")
    public ApiConsumerView removeJwtKey(@PathVariable UUID consumerId, HttpServletRequest httpReq) {
        return consumerService.removeJwtKey(userContext.getUser().tenantId(), userContext.getUser().id(), consumerId,
                requestId(httpReq));
    }

    /**
     * Per-consumer MCP call overview (#338): mcp_access_log window aggregates —
     * totals by outcome class, top tools/services, last call. Read-only.
     */
    @GetMapping("/{consumerId}/activity")
    public java.util.Map<String, Object> activity(@PathVariable UUID consumerId,
            @RequestParam(defaultValue = "24") int hours) {
        return activityService.activity(userContext.getUser().tenantId(), consumerId, hours);
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    /**
     * Replaces the capability scope (issue #316): null = full access, empty list =
     * no channels; unknown/duplicate codes → 400 CONSUMER_SCOPE_INVALID. Audited as
     * CONSUMER_SCOPE_UPDATE with the previous and next scope.
     */
    @PatchMapping("/{consumerId}/scope")
    public ApiConsumerView updateScope(@PathVariable UUID consumerId, @Valid @RequestBody ScopeRequest body,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return consumerService.updateScope(user.tenantId(), user.id(), consumerId, body.capabilities(),
                requestId(httpReq));
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String name, String expiresAt) {
    }

    public record ScopeRequest(List<String> capabilities) {
    }

    /** Creation response (201): consumer view + one-time api key. */
    public record CreateApiConsumerResponse(ApiConsumerView consumer, String apiKey, boolean shownOnce) {
    }

    public record SetJwtKeyRequest(@NotBlank @Size(max = 8192) String publicKeyPem) {
    }
}
