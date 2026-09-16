package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * On-demand MCP probe result (#685「调用验证」): the outcome of one immediate probe
 * against the service's upstream using its configured check mode (HEALTH_PATH /
 * JSONRPC_INITIALIZE). Read-only — it never mutates the stored health state.
 * {@code detail} is a sanitized, admin-visible note.
 */
public record McpServiceVerifyView(UUID serviceId, boolean reachable, String checkMode, long latencyMs, String detail,
        Instant checkedAt) {
}
