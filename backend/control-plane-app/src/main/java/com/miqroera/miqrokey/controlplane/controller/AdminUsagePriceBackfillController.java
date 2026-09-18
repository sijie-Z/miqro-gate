package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.UsagePriceBackfillResult;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.UsagePriceBackfillService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Freezes the price basis onto historical usage rows (#710 / F21-A).
 *
 * <p>
 * SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor. The operation is idempotent — it only touches rows whose price
 * basis has never been evaluated — so an operator can safely re-run it over a
 * window, and a wide backfill can be done in successive narrower windows.
 * </p>
 *
 * <p>
 * A pass also completes the {@code base_cost_amount} of rows that were stamped
 * before that column existed (#771), deriving it from the prices they already
 * froze. So re-running is the repair path for a deployment upgraded past V66,
 * not just a no-op.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/usage-price-backfill")
public class AdminUsagePriceBackfillController {

    private final UsagePriceBackfillService backfillService;
    private final UserContext userContext;

    public AdminUsagePriceBackfillController(UsagePriceBackfillService backfillService, UserContext userContext) {
        this.backfillService = backfillService;
        this.userContext = userContext;
    }

    /**
     * Stamps prices as-of each row's own {@code occurred_at}; returns per-outcome
     * counts.
     *
     * <p>
     * {@code unavailable} counts history that could not be priced at all — it is
     * surfaced rather than folded into success, because a silent zero would read as
     * "free" instead of "unknown".
     * </p>
     */
    @PostMapping
    public UsagePriceBackfillResult backfill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return backfillService.backfill(user.tenantId(), user.id(), from, to, requestId(httpReq));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
