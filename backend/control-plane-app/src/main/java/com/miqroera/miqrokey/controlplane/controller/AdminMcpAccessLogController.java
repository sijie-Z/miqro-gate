package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminMcpAccessLogService;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * MCP access log audit query (F15, api-contract §5.23): metadata-only rows of
 * MCP proxy calls, newest first. SYSTEM_ADMIN-only via the RoleInterceptor
 * deny-by-default on {@code /api/v1/admin/**}.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-access-logs")
public class AdminMcpAccessLogController {

    private final AdminMcpAccessLogService logService;
    private final UserContext userContext;

    public AdminMcpAccessLogController(AdminMcpAccessLogService logService, UserContext userContext) {
        this.logService = logService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<McpAccessLogEntry> list(@RequestParam(required = false) String service,
            @RequestParam(required = false) String consumer, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer limit) {
        return logService.view(userContext.getUser().tenantId(), blankToNull(service), blankToNull(consumer),
                parseInstant(from, "from"), parseInstant(to, "to"), limit);
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
