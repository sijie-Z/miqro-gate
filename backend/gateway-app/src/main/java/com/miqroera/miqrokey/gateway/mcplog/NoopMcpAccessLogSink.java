package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;

/**
 * No-op sink used when gateway persistence is disabled (default, in-memory
 * gateway): there is no table to write, so F15 rows are intentionally not
 * produced — the same configuration that turns off usage persistence.
 */
public final class NoopMcpAccessLogSink implements McpAccessLogSink {

    @Override
    public void record(McpAccessLogEntry entry) {
        // No persistence: nothing to do.
    }
}
