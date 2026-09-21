package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.McpAclMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Replaces one scope of an MCP access list. {@code toolId} null edits the
 * server-level list (allowed only while the server mode is ALLOW or DENY);
 * non-null edits that tool's override (allowed only while the server mode is
 * NONE — Tencent semantics: tool customization requires an open server).
 */
public record SetMcpAccessGrantsRequest(UUID toolId, @NotNull McpAclMode mode, @NotEmpty List<UUID> consumerIds) {
}
