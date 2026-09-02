package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.McpAclMode;
import jakarta.validation.constraints.NotNull;

/**
 * Server-level ACL mode of an MCP service. NONE = open to every caller (and the
 * only mode under which tool-level overrides may exist).
 */
public record SetMcpAccessModeRequest(@NotNull McpAclMode mode) {
}
