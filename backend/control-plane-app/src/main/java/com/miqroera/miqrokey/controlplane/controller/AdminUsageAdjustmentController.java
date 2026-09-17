package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.UsageAdjustmentRequest;
import com.miqroera.miqrokey.controlplane.dto.UsageAdjustmentView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import com.miqroera.miqrokey.controlplane.service.UsageAdjustmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usage adjustments (#709, backlog F20): append a correction to an observed
 * usage event, or a reversal that undoes an earlier one.
 *
 * <p>
 * There is no update and no delete endpoint by design — the ledger is
 * append-only, so correcting a correction is itself an append.
 * SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor.
 * </p>
 *
 * <p>
 * Read paths (detail net columns, export markers, and the reconcile-level
 * dimension) are wired up separately; at this stage an adjustment is recorded
 * and visible through this endpoint but does not yet move any reported figure.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/usage-adjustments")
public class AdminUsageAdjustmentController {

    private final UsageAdjustmentService usageAdjustmentService;
    private final UserContext userContext;

    public AdminUsageAdjustmentController(UsageAdjustmentService usageAdjustmentService, UserContext userContext) {
        this.usageAdjustmentService = usageAdjustmentService;
        this.userContext = userContext;
    }

    /**
     * Appends one adjustment; 201 + the recorded row.
     *
     * <p>
     * Supplying {@code idempotencyKey} makes the call safe to retry: a resubmission
     * returns the row already recorded rather than booking the correction twice.
     * </p>
     */
    @PostMapping
    public ResponseEntity<UsageAdjustmentView> append(@RequestBody UsageAdjustmentRequest request,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        UsageAdjustmentView view = usageAdjustmentService.append(user, request,
                AuditContext.human(user.id(), requestId(httpReq)));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * Adjustments booked against the usage event behind one request id, oldest
     * first.
     */
    @GetMapping
    public List<UsageAdjustmentView> list(@RequestParam String gatewayRequestId) {
        return usageAdjustmentService.listForRequest(userContext.getUser(), gatewayRequestId);
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
