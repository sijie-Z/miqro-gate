package com.miqroera.miqrokey.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision matrix of the two-level MCP access control (Tencent doc 134890):
 * server layer first, tool layer only narrows.
 */
class McpAccessPolicyTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    @Test
    @DisplayName("open server allows everyone and tool overrides narrow")
    void openServerWithToolOverrides() {
        // Server NONE: everyone passes; tool ALLOW admits only A.
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.NONE, List.of(), McpAclMode.ALLOW, List.of(A), A)).isTrue();
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.NONE, List.of(), McpAclMode.ALLOW, List.of(A), B)).isFalse();
        // Tool DENY rejects only the listed caller.
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.NONE, List.of(), McpAclMode.DENY, List.of(A), A)).isFalse();
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.NONE, List.of(), McpAclMode.DENY, List.of(A), B)).isTrue();
        // No tool override: inherit the open server.
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.NONE, List.of(), null, List.of(), C)).isTrue();
    }

    @Test
    @DisplayName("ALLOW whitelists the server; tool overrides cannot widen")
    void allowServerCannotBeWidenedByTool() {
        // Server ALLOW admits only A.
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.ALLOW, List.of(A), null, List.of(), A)).isTrue();
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.ALLOW, List.of(A), null, List.of(), B)).isFalse();
        // B is not on the server list even if a tool DENY list would admit it.
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.ALLOW, List.of(A), McpAclMode.DENY, List.of(B), B)).isFalse();
        // A passes the server and is not denied by the tool list.
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.ALLOW, List.of(A), McpAclMode.DENY, List.of(B), A)).isTrue();
        // A passes the server and the tool ALLOW list admits it too.
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.ALLOW, List.of(A), McpAclMode.ALLOW, List.of(A), A)).isTrue();
    }

    @Test
    @DisplayName("DENY blacklists the server; everyone else inherits")
    void denyServer() {
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.DENY, List.of(A), null, List.of(), A)).isFalse();
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.DENY, List.of(A), null, List.of(), B)).isTrue();
        // Tool ALLOW cannot save a blacklisted caller.
        assertThat(McpAccessPolicy.isAllowed(McpAclMode.DENY, List.of(A), McpAclMode.ALLOW, List.of(A), A)).isFalse();
    }
}
