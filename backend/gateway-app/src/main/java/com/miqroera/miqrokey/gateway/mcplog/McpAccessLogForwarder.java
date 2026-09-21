package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;

import java.util.List;

/**
 * Secondary delivery of MCP access-log metadata (I19, Tencent raw 16 log
 * shipping): after a batch is durably written, the queue may fan it out to
 * external sinks (webhook / syslog). Implementations run on the flush scheduler
 * thread — never on the Reactor event loop — and must never throw: a broken
 * sink degrades to a throttled WARN, it never blocks or fails the audit rows.
 * Payloads are metadata only (the entry shape carries no content).
 */
public interface McpAccessLogForwarder {

    /** Short sink name for logs (e.g. {@code webhook}, {@code syslog}). */
    String name();

    /** Best-effort delivery of one flushed batch; never throws. */
    void forward(List<McpAccessLogEntry> batch);
}
