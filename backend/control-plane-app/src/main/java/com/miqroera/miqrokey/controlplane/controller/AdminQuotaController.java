package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.QuotaSnapshotService;
import com.miqroera.miqrokey.domain.usage.QuotaSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin quota/Plan status (G4.2, api-contract §5): latest quota snapshots per
 * scope for a subscription (shared pool, seats, member keys) and an explicit
 * admin-triggered refresh that asks the product's adapter for the official
 * status (or records UNAVAILABLE / LOCAL_ESTIMATE honestly). Access is
 * SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor. Snapshots never contain secrets or upstream URLs.
 */
@RestController
@RequestMapping("/api/v1/admin/subscriptions/{subscriptionId}/quota")
public class AdminQuotaController {

    private final QuotaSnapshotService quotaSnapshotService;
    private final UserContext userContext;

    public AdminQuotaController(QuotaSnapshotService quotaSnapshotService, UserContext userContext) {
        this.quotaSnapshotService = quotaSnapshotService;
        this.userContext = userContext;
    }

    /** Latest snapshot per scope (subscription / seat / credential). */
    @GetMapping
    public List<QuotaSnapshot> latest(@PathVariable UUID subscriptionId) {
        return quotaSnapshotService.latest(userContext.getUser().tenantId(), subscriptionId);
    }

    /**
     * Refreshes quota snapshots from the adapter (official API) and local usage,
     * then returns the fresh latest-per-scope view.
     */
    @PostMapping("/refresh")
    public List<QuotaSnapshot> refresh(@PathVariable UUID subscriptionId) {
        UUID tenantId = userContext.getUser().tenantId();
        quotaSnapshotService.refresh(tenantId, subscriptionId);
        return quotaSnapshotService.latest(tenantId, subscriptionId);
    }
}
