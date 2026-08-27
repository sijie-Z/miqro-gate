package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminProviderService;
import com.miqroera.miqrokey.controlplane.service.AdminProviderService.SeatView;
import com.miqroera.miqrokey.controlplane.service.AdminProviderService.SubscriptionView;
import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.domain.model.PlanScope;
import com.miqroera.miqrokey.domain.model.SeatStatus;
import com.miqroera.miqrokey.domain.model.SubscriptionStatus;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Admin subscriptions + seats (G5.3, api-contract §5): PAYG / personal / team /
 * enterprise plans and their seat assignments (member keys are credentials,
 * managed under {@code /api/v1/admin/credentials}). SYSTEM_ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/admin/subscriptions")
public class AdminSubscriptionController {

    private final AdminProviderService providerService;
    private final UserContext userContext;

    public AdminSubscriptionController(AdminProviderService providerService, UserContext userContext) {
        this.providerService = providerService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<SubscriptionView> list() {
        return providerService.listSubscriptions(userContext.getUser().tenantId());
    }

    @GetMapping("/{subscriptionId}")
    public SubscriptionView get(@PathVariable UUID subscriptionId) {
        return providerService.subscription(userContext.getUser().tenantId(), subscriptionId);
    }

    @PostMapping
    public UpstreamSubscription create(@RequestBody CreateRequest body) {
        var admin = userContext.getUser();
        return providerService.createSubscription(admin.tenantId(), admin.id(), body.providerProductId(), body.name(),
                body.billingMode(), body.planScope(), body.subscriptionPrice(), body.currency(), body.quotaTotal(),
                body.quotaUnit());
    }

    @PatchMapping("/{subscriptionId}")
    public UpstreamSubscription update(@PathVariable UUID subscriptionId, @RequestBody UpdateRequest body) {
        var admin = userContext.getUser();
        return providerService.updateSubscription(admin.tenantId(), admin.id(), subscriptionId, body.name(),
                body.subscriptionPrice(), body.currency(), body.quotaTotal(), body.quotaUnit(), body.status());
    }

    @GetMapping("/{subscriptionId}/seats")
    public List<SeatView> seats(@PathVariable UUID subscriptionId) {
        return providerService.seats(userContext.getUser().tenantId(), subscriptionId);
    }

    @PostMapping("/{subscriptionId}/seats")
    public SeatView createSeat(@PathVariable UUID subscriptionId, @RequestBody SeatRequest body) {
        var admin = userContext.getUser();
        return providerService.createSeat(admin.tenantId(), admin.id(), subscriptionId, body.externalSeatRef(),
                body.displayName(), body.assignedUserId());
    }

    @PatchMapping("/{subscriptionId}/seats/{seatId}")
    public SeatView updateSeat(@PathVariable UUID subscriptionId, @PathVariable UUID seatId,
            @RequestBody SeatUpdateRequest body) {
        var admin = userContext.getUser();
        return providerService.updateSeat(admin.tenantId(), admin.id(), subscriptionId, seatId, body.assignedUserId(),
                body.status(), body.displayName());
    }

    public record CreateRequest(UUID providerProductId, String name, BillingMode billingMode, PlanScope planScope,
            BigDecimal subscriptionPrice, String currency, Long quotaTotal, String quotaUnit) {
    }

    public record UpdateRequest(String name, BigDecimal subscriptionPrice, String currency, Long quotaTotal,
            String quotaUnit, SubscriptionStatus status) {
    }

    public record SeatRequest(String externalSeatRef, String displayName, UUID assignedUserId) {
    }

    public record SeatUpdateRequest(UUID assignedUserId, SeatStatus status, String displayName) {
    }
}
