package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.AdminMcpAccessLogService;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Open MCP access log query (ADR-0015, batch 1b read subset; F15):
 * metadata-only rows of MCP proxy calls, newest first, same filters and window
 * caps as the session endpoint. Authenticated by an admin machine key or a
 * SYSTEM_ADMIN session.
 */
@RestController
@RequestMapping("/api/v1/admin-api/mcp-access-logs")
public class OpenAdminMcpLogsReadController {

    private final AdminMcpAccessLogService logService;

    public OpenAdminMcpLogsReadController(AdminMcpAccessLogService logService) {
        this.logService = logService;
    }

    @GetMapping
    public List<McpAccessLogEntry> list(HttpServletRequest request, @RequestParam(required = false) String service,
            @RequestParam(required = false) String consumer, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer limit) {
        return logService.view(tenantId(request), blankToNull(service), blankToNull(consumer),
                parseInstant(from, "from"), parseInstant(to, "to"), limit);
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", paramName + " must be an ISO-8601 instant");
        }
    }
}
