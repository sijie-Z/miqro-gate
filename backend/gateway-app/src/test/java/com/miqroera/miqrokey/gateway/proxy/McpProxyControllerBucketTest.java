package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the breaker-bucket derivation (#727): the bucket key is a
 * known tool name, a protocol method from the fixed MCP envelope set, or the
 * shared {@code envelope} bucket — never a client-invented string, which would
 * otherwise create a never-evicted registry entry per request.
 */
@DisplayName("McpProxyController breaker bucket (#727)")
class McpProxyControllerBucketTest {

    @Test
    @DisplayName("a resolved tool name is its own bucket")
    void toolNameWins() {
        assertThat(McpProxyController.bucketFor("search", "tools/call")).isEqualTo("search");
        assertThat(McpProxyController.bucketFor("search", "anything-else")).isEqualTo("search");
    }

    @Test
    @DisplayName("known envelope methods keep their own bucket")
    void knownMethodsKeepTheirBucket() {
        assertThat(McpProxyController.bucketFor(null, "initialize")).isEqualTo("initialize");
        assertThat(McpProxyController.bucketFor(null, "tools/list")).isEqualTo("tools/list");
        assertThat(McpProxyController.bucketFor(null, "resources/read")).isEqualTo("resources/read");
    }

    @Test
    @DisplayName("invented method strings collapse into the shared envelope bucket")
    void inventedMethodsCollapseToEnvelope() {
        assertThat(McpProxyController.bucketFor(null, "m1")).isEqualTo("envelope");
        assertThat(McpProxyController.bucketFor(null, "tools/call;DROP")).isEqualTo("envelope");
        assertThat(McpProxyController.bucketFor(null, "")).isEqualTo("envelope");
    }

    @Test
    @DisplayName("a missing method falls back to the shared envelope bucket")
    void nullMethodFallsBackToEnvelope() {
        assertThat(McpProxyController.bucketFor(null, null)).isEqualTo("envelope");
    }
}
