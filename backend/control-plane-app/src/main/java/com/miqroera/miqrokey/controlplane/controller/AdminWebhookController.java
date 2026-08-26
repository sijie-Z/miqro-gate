package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.DeliveryAttempt;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.TestResult;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.WebhookEndpointView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin webhook endpoints (G4.5, api-contract §5): CRUD plus a signed test
 * delivery and delivery history. The signing secret is never returned. Access
 * is SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/webhooks")
public class AdminWebhookController {

    private final WebhookEndpointService endpointService;
    private final UserContext userContext;

    public AdminWebhookController(WebhookEndpointService endpointService, UserContext userContext) {
        this.endpointService = endpointService;
        this.userContext = userContext;
    }

    @PostMapping
    public WebhookEndpointView create(@RequestBody CreateRequest body) {
        return endpointService.create(userContext.getUser().tenantId(), body.name(), body.url(), body.secret(),
                body.timeoutMs() != null ? body.timeoutMs() : 5000);
    }

    @GetMapping
    public List<WebhookEndpointView> list() {
        return endpointService.list(userContext.getUser().tenantId());
    }

    @GetMapping("/{endpointId}")
    public WebhookEndpointView get(@PathVariable UUID endpointId) {
        return endpointService.view(endpointService.get(userContext.getUser().tenantId(), endpointId));
    }

    @PatchMapping("/{endpointId}")
    public WebhookEndpointView update(@PathVariable UUID endpointId, @RequestBody UpdateRequest body) {
        return endpointService.updateView(userContext.getUser().tenantId(), endpointId, body.name(), body.enabled(),
                body.timeoutMs());
    }

    @DeleteMapping("/{endpointId}")
    public void delete(@PathVariable UUID endpointId) {
        endpointService.delete(userContext.getUser().tenantId(), endpointId);
    }

    /** Sends a signed test payload to the endpoint. */
    @PostMapping("/{endpointId}/test")
    public TestResult test(@PathVariable UUID endpointId) {
        return endpointService.test(userContext.getUser().tenantId(), endpointId);
    }

    /** Delivery history for the endpoint. */
    @GetMapping("/{endpointId}/deliveries")
    public List<DeliveryAttempt> deliveries(@PathVariable UUID endpointId,
            @RequestParam(defaultValue = "20") int limit) {
        return endpointService.deliveries(userContext.getUser().tenantId(), endpointId, limit);
    }

    public record CreateRequest(String name, String url, String secret, Integer timeoutMs) {
    }

    public record UpdateRequest(String name, Boolean enabled, Integer timeoutMs) {
    }
}
