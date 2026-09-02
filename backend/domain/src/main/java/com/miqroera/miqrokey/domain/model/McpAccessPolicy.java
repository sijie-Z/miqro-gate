package com.miqroera.miqrokey.domain.model;

import java.util.Collection;
import java.util.UUID;

/**
 * Pure decision logic for the two-level MCP access control (Tencent AI gateway
 * doc 134890): the server list decides who may call the whole service, the tool
 * list — when present — refines ("further narrows") who may call that single
 * tool.
 *
 * <ul>
 * <li>Server NONE → server layer allows everyone.</li>
 * <li>Server ALLOW → only listed consumers pass the server layer.</li>
 * <li>Server DENY → listed consumers are rejected at the server layer.</li>
 * <li>No tool override → the tool inherits the server decision.</li>
 * <li>Tool ALLOW/DENY lists apply only to callers that already passed the
 * server layer (they can only narrow, never widen).</li>
 * </ul>
 */
public final class McpAccessPolicy {

    private McpAccessPolicy() {
    }

    /** Allowed when every applicable layer admits the consumer. */
    public static boolean isAllowed(McpAclMode serverMode, Collection<UUID> serverList, McpAclMode toolMode,
            Collection<UUID> toolList, UUID consumerId) {
        boolean serverAllowed = switch (serverMode) {
            case NONE -> true;
            case ALLOW -> serverList.contains(consumerId);
            case DENY -> !serverList.contains(consumerId);
        };
        if (!serverAllowed) {
            return false;
        }
        if (toolMode == null) {
            return true; // no tool override — inherit the server decision
        }
        return switch (toolMode) {
            case ALLOW -> toolList.contains(consumerId);
            case DENY -> !toolList.contains(consumerId);
            case NONE -> true; // defensive: NONE is not a valid grant mode
        };
    }
}
