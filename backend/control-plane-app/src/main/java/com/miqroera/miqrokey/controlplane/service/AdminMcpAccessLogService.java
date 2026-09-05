package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.repository.McpAccessLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin listing of the gateway MCP access log (F15, api-contract §5.23):
 * newest-first audit tail with optional service / consumer / time-window
 * filters. The default window is the last 24 hours; the window is capped at 31
 * days (an audit tail, not a warehouse — full retention lives in the raw
 * table).
 */
@Service
public class AdminMcpAccessLogService {

    /** Hard cap on the filterable window. */
    public static final Duration MAX_WINDOW = Duration.ofDays(31);
    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 1000;

    private final McpAccessLogRepository repository;

    public AdminMcpAccessLogService(McpAccessLogRepository repository) {
        this.repository = repository;
    }

    public List<McpAccessLogEntry> view(UUID tenantId, String serviceName, String consumerName, Instant from,
            Instant to, Integer limit) {
        Instant fromInstant = from == null ? Instant.now().minus(Duration.ofDays(1)) : from;
        Instant toInstant = to == null ? Instant.now() : to;
        if (toInstant.isBefore(fromInstant)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_INVALID", "from must not be after to");
        }
        if (Duration.between(fromInstant, toInstant).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_TOO_WIDE",
                    "from/to window must not exceed 31 days");
        }
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIZE_INVALID", "limit must be between 1 and " + MAX_LIMIT);
        }
        return repository.findRecent(tenantId, serviceName, consumerName, fromInstant, toInstant, effectiveLimit);
    }
}
