package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;

/**
 * Fire-and-forget entry point of the F15 MCP access log. Implementations must
 * never block the caller (Reactor event loop) — see {@link McpAccessLogQueue}
 * (bounded queue, drop + count on saturation) and the no-op fallback used when
 * gateway persistence is disabled.
 */
public interface McpAccessLogSink {

    /** Best-effort record of one terminal outcome; never throws. */
    void record(McpAccessLogEntry entry);
}
