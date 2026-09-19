package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.DeliveryAttempt;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.TestResult;
import com.miqroera.miqrokey.controlplane.service.WebhookEndpointService.WebhookEndpointView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    public WebhookEndpointView create(@Valid @RequestBody WebhookCreateRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return endpointService.create(user.tenantId(), body.name(), body.url(), body.secret(),
                body.timeoutMs() != null ? body.timeoutMs() : 5000, AuditContext.human(user.id(), requestId(httpReq)));
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
    public WebhookEndpointView update(@PathVariable UUID endpointId, @Valid @RequestBody WebhookUpdateRequest body,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return endpointService.updateView(user.tenantId(), endpointId, body.name(), body.enabled(), body.timeoutMs(),
                AuditContext.human(user.id(), requestId(httpReq)));
    }

    @DeleteMapping("/{endpointId}")
    public void delete(@PathVariable UUID endpointId, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        endpointService.delete(user.tenantId(), endpointId, AuditContext.human(user.id(), requestId(httpReq)));
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

    /**
     * Bounds mirror the storage columns ({@code varchar(200)} / {@code varchar(500)})
     * and the timeout domain already used for MCP upstream budgets
     * (1000..600000 ms, api-contract §5.11): a value outside them is a client error
     * the admin UI never sends, so it must be a 400 rather than a NOT NULL or
     * length breach surfacing as a generic 409.
     */
    public record WebhookCreateRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 500) String url,
            @NotBlank String secret,
            @Min(1000) @Max(600000) Integer timeoutMs) {
    }

    /**
     * PATCH is partial: an absent field keeps its stored value, so only present
     * values are constrained. {@code name} rejects blank-but-present
     * ({@code "   "}) without forbidding newlines or other content.
     */
    public record WebhookUpdateRequest(
            @Pattern(regexp = "\\s*\\S[\\s\\S]*") @Size(max = 200) String name,
            Boolean enabled,
            @Min(1000) @Max(600000) Integer timeoutMs) {
    }
    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

}
