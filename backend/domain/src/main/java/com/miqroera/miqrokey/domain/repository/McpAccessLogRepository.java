package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read access to {@code mcp_access_log} (V29, F15) for the admin audit query.
 * Rows are written by the gateway's async batch writer; this side only lists.
 */
public interface McpAccessLogRepository {

    /**
     * Newest-first rows of one tenant within the query bounds; every optional
     * filter narrows the result. The caller pre-validates the window and caps
     * {@code limit}.
     */
    List<McpAccessLogEntry> findRecent(UUID tenantId, String serviceName, String consumerName, Instant from, Instant to,
            int limit);
}
