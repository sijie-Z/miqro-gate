package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.McpAclMode;

import java.util.List;
import java.util.UUID;

/**
 * Full view of one MCP service's two-level access control (api-contract §5.21,
 * Tencent AI gateway doc 134890): the server mode with its consumer list plus,
 * per tool, the override mode (null = inherit the server rule) and its own
 * list.
 */
public record McpAccessView(UUID serviceId, String serviceName, McpAclMode mode, List<ConsumerRef> serverConsumers,
        List<ToolAccess> tools) {

    public record ConsumerRef(UUID id, String name) {
    }

    public record ToolAccess(UUID toolId, String toolName, McpAclMode mode, List<ConsumerRef> consumers) {
    }
}
