package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.DeliveryAttempt;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.TestResult;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.WebhookEndpointView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Open admin webhook endpoints (ADR-0016 batch 2, option C): the session
 * endpoint's full lifecycle is tenant-scoped configuration without executor
 * columns, so it opens as-is to machine keys and SYSTEM_ADMIN sessions. The
 * signed test payload endpoint is included because delivery verification is a
 * first-class machine operation.
 */
@RestController
@RequestMapping("/api/v1/admin-api/webhooks")
public class OpenAdminWebhooksController {

    private final WebhookEndpointService endpointService;
    private final UserContext userContext;

    public OpenAdminWebhooksController(WebhookEndpointService endpointService, UserContext userContext) {
        this.endpointService = endpointService;
        this.userContext = userContext;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointView create(HttpServletRequest request, @RequestBody CreateRequest body) {
        return endpointService.create(tenantId(request), body.name(), body.url(), body.secret(),
                body.timeoutMs() != null ? body.timeoutMs() : 5000, auditContext(request));
    }

    @GetMapping
    public List<WebhookEndpointView> list(HttpServletRequest request) {
        return endpointService.list(tenantId(request));
    }

    @GetMapping("/{endpointId}")
    public WebhookEndpointView get(HttpServletRequest request, @PathVariable UUID endpointId) {
        return endpointService.view(endpointService.get(tenantId(request), endpointId));
    }

    @PatchMapping("/{endpointId}")
    public WebhookEndpointView update(HttpServletRequest request, @PathVariable UUID endpointId,
            @RequestBody UpdateRequest body) {
        return endpointService.updateView(tenantId(request), endpointId, body.name(), body.enabled(), body.timeoutMs(),
                auditContext(request));
    }

    @DeleteMapping("/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest request, @PathVariable UUID endpointId) {
        endpointService.delete(tenantId(request), endpointId, auditContext(request));
    }

    /** Sends a signed test payload to the endpoint. */
    @PostMapping("/{endpointId}/test")
    public TestResult test(HttpServletRequest request, @PathVariable UUID endpointId) {
        return endpointService.test(tenantId(request), endpointId);
    }

    /** Delivery history for the endpoint. */
    @GetMapping("/{endpointId}/deliveries")
    public List<DeliveryAttempt> deliveries(HttpServletRequest request, @PathVariable UUID endpointId,
            @RequestParam(defaultValue = "20") int limit) {
        return endpointService.deliveries(tenantId(request), endpointId, limit);
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }

    /**
     * Machine key -> issuing admin + via marker; SYSTEM_ADMIN session -> the user.
     */
    private AuditContext auditContext(HttpServletRequest request) {
        UUID issuer = (UUID) request.getAttribute(AdminApiKeyAuthFilter.ISSUER_ATTR);
        if (issuer != null) {
            return AuditContext.machine(issuer, (String) request.getAttribute(AdminApiKeyAuthFilter.NAME_ATTR),
                    requestId(request));
        }
        return AuditContext.human(userContext.getUser().id(), requestId(request));
    }

    public record CreateRequest(String name, String url, String secret, Integer timeoutMs) {
    }

    public record UpdateRequest(String name, Boolean enabled, Integer timeoutMs) {
    }
    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

}
