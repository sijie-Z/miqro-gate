package com.miqroera.miqrokey.domain.model;

/**
 * Access-control mode of an MCP server (Tencent AI gateway doc 134890). NONE =
 * open to every caller (allows tool-level refinement), ALLOW = only the listed
 * consumers may call, DENY = the listed consumers may not call. Grants rows
 * reuse the mode for ALLOW/DENY only.
 */
public enum McpAclMode {
    NONE, ALLOW, DENY
}
