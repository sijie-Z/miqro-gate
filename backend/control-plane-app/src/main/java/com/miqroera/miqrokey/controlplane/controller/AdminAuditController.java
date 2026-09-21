package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AuditEventView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AuditEventReadService;
import com.miqroera.miqrokey.controlplane.service.AuditEventReadService.AuditFilters;
import com.miqroera.miqrokey.controlplane.service.AuditEventReadService.ExportResult;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Admin audit events (G5.4, api-contract §5): immutable chain-links in reverse
 * causal order, with optional filters (action / resource type / actor / time
 * window) and cursor. The chain hashes are never serialized (integrity proof
 * lives in the table, not the API). Compliance CSV export slices the same
 * filtered view; truncated exports are declared via
 * {@code X-MiQroKey-Truncated} instead of silently dropping rows.
 * SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-events")
public class AdminAuditController {

    private final AuditEventReadService auditEvents;
    private final UserContext userContext;

    public AdminAuditController(AuditEventReadService auditEvents, UserContext userContext) {
        this.auditEvents = auditEvents;
        this.userContext = userContext;
    }

    @GetMapping
    public List<AuditEventView> list(@RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action, @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID actorId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Long beforePosition) {
        return auditEvents.list(userContext.getUser().tenantId(), size,
                new AuditFilters(action, targetType, actorId, parseInstant(from, "from"), parseInstant(to, "to")),
                beforePosition);
    }

    @GetMapping(path = "/export", produces = "text/csv")
    public void exportCsv(HttpServletResponse response, @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType, @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) throws IOException {
        ExportResult result = auditEvents.exportCsv(userContext.getUser().tenantId(),
                new AuditFilters(action, targetType, actorId, parseInstant(from, "from"), parseInstant(to, "to")));
        writeCsv(response, result);
    }

    /** Shared CSV response shaping (also used by the open admin read endpoint). */
    static void writeCsv(HttpServletResponse response, ExportResult result) throws IOException {
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String stamp = Instant.now().toString().replace(":", "-").replace(".", "-");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-events-" + stamp + ".csv\"");
        if (result.truncated()) {
            response.setHeader("X-MiQroKey-Truncated", "true");
        }
        response.getWriter().write(result.csv());
    }

    static Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", paramName + " must be an ISO-8601 instant");
        }
    }
}
