package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.CostAllocationService;
import com.miqroera.miqrokey.domain.usage.CostAllocation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin cost allocation (G4.3, api-contract §5): per-period metered usage cost
 * per project plus the Plan fixed-cost share weighted by tokens. Access is
 * SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/subscriptions/{subscriptionId}/cost-allocation")
public class AdminCostAllocationController {

    private final CostAllocationService allocationService;
    private final UserContext userContext;

    public AdminCostAllocationController(CostAllocationService allocationService, UserContext userContext) {
        this.allocationService = allocationService;
        this.userContext = userContext;
    }

    /** Persisted allocation rows for the period (no recomputation). */
    @GetMapping
    public List<CostAllocation> byPeriod(@PathVariable UUID subscriptionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return allocationService.byPeriod(userContext.getUser().tenantId(), subscriptionId, from, to);
    }

    /** Computes and persists the allocation for the period, returning the rows. */
    @PostMapping("/allocate")
    public List<CostAllocation> allocate(@PathVariable UUID subscriptionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return allocationService.allocate(userContext.getUser().tenantId(), subscriptionId, from, to);
    }
}
