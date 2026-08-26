package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.UsageDeletionService;
import com.miqroera.miqrokey.controlplane.service.UsageDeletionService.DeletionRequest;
import com.miqroera.miqrokey.domain.usage.UsageDeletion;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Double-confirmed usage deletion (G4.4, api-contract §5): preview counts the
 * window, create returns a one-time confirmation token (only its hash is
 * stored), confirm executes the permanent deletion with an audit trail. Access
 * is SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/usage-deletions")
public class AdminUsageDeletionController {

    private final UsageDeletionService deletionService;
    private final UserContext userContext;

    public AdminUsageDeletionController(UsageDeletionService deletionService, UserContext userContext) {
        this.deletionService = deletionService;
        this.userContext = userContext;
    }

    /** Dry-run row count for the window. */
    @GetMapping("/preview")
    public Preview preview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        long count = deletionService.preview(userContext.getUser().tenantId(), from, to);
        return new Preview(count);
    }

    /** Creates the request; the confirmation token is returned exactly once. */
    @PostMapping
    public DeletionRequest create(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var user = userContext.getUser();
        return deletionService.create(user.tenantId(), user.id(), from, to);
    }

    /** Confirms with the one-time token and executes the permanent deletion. */
    @PostMapping("/{deletionId}/confirm")
    public UsageDeletion confirm(@PathVariable UUID deletionId, @RequestBody ConfirmRequest body) {
        return deletionService.confirm(userContext.getUser().tenantId(), deletionId, body.confirmToken());
    }

    /** Recent deletion requests (metadata only; never the token). */
    @GetMapping
    public List<UsageDeletion> recent(@RequestParam(defaultValue = "20") int limit) {
        return deletionService.recent(userContext.getUser().tenantId(), limit);
    }

    public record Preview(long count) {
    }

    public record ConfirmRequest(String confirmToken) {
    }
}
