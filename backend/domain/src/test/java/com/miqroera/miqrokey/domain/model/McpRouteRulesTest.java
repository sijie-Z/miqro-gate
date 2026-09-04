package com.miqroera.miqrokey.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure matching/conflict logic for F11 route rules (Tencent doc 135482): AND
 * semantics inside a rule, RE2 full-match, host case-insensitivity and the
 * canonical match-surface conflict bound.
 */
class McpRouteRulesTest {

    private static final UUID SERVICE = UUID.randomUUID();

    private McpRouteRule rule(String pathMode, String pathValue, String hostMode, String hostValue, String methods,
            List<McpHeaderCondition> headers) {
        return new McpRouteRule(UUID.randomUUID(), UUID.randomUUID(), SERVICE, "custom", null, 1000, pathMode,
                pathValue, hostMode, hostValue, methods, headers, "ENABLED", 0, UUID.randomUUID(), Instant.now(),
                Instant.now());
    }

    private McpRouteRules.RouteRequest request(String method, String path, String host, Map<String, List<String>> h) {
        return new McpRouteRules.RouteRequest(method, path, host, h);
    }

    @Test
    @DisplayName("unrestricted rule matches anything")
    void unrestrictedMatches() {
        McpRouteRule rule = rule(null, null, null, null, null, List.of());
        assertThat(McpRouteRules.matches(rule, request("GET", "/anything", "api.example.com", Map.of()))).isTrue();
    }

    @Test
    @DisplayName("path EXACT/PREFIX/REGEX match; conditions AND-ed")
    void pathMatching() {
        assertThat(McpRouteRules.matches(rule("EXACT", "/api", null, null, null, List.of()),
                request("GET", "/api", null, Map.of()))).isTrue();
        assertThat(McpRouteRules.matches(rule("EXACT", "/api", null, null, null, List.of()),
                request("GET", "/api/v1", null, Map.of()))).isFalse();
        assertThat(McpRouteRules.matches(rule("PREFIX", "/api", null, null, null, List.of()),
                request("GET", "/api/v1/orders", null, Map.of()))).isTrue();
        // RE2 full match (anchored pattern in the upstream example).
        assertThat(McpRouteRules.matches(rule("REGEX", "^/api/v[0-9]+$", null, null, null, List.of()),
                request("GET", "/api/v2", null, Map.of()))).isTrue();
        assertThat(McpRouteRules.matches(rule("REGEX", "^/api/v[0-9]+$", null, null, null, List.of()),
                request("GET", "/api/v2/extra", null, Map.of()))).isFalse();
    }

    @Test
    @DisplayName("methods act as a whitelist; null means unrestricted")
    void methodWhitelist() {
        McpRouteRule rule = rule(null, null, null, null, "POST,GET", List.of());
        assertThat(McpRouteRules.matches(rule, request("GET", "/x", null, Map.of()))).isTrue();
        assertThat(McpRouteRules.matches(rule, request("DELETE", "/x", null, Map.of()))).isFalse();
        assertThat(McpRouteRules.matches(rule(null, null, null, null, null, List.of()),
                request("DELETE", "/x", null, Map.of()))).isTrue();
    }

    @Test
    @DisplayName("host matching is case-insensitive and honors its mode")
    void hostMatching() {
        assertThat(McpRouteRules.matches(rule(null, null, "EXACT", "mcp-prod.example.com", null, List.of()),
                request("GET", "/x", "MCP-PROD.EXAMPLE.COM", Map.of()))).isTrue();
        assertThat(McpRouteRules.matches(rule(null, null, "PREFIX", "mcp-", null, List.of()),
                request("GET", "/x", "mcp-test.example.com", Map.of()))).isTrue();
        assertThat(McpRouteRules.matches(rule(null, null, "REGEX", "^v[0-9].*\\.example\\.com$", null, List.of()),
                request("GET", "/x", "v2.api.example.com", Map.of()))).isTrue();
        // Missing Host header never matches a host-constrained rule.
        assertThat(McpRouteRules.matches(rule(null, null, "EXACT", "mcp-prod.example.com", null, List.of()),
                request("GET", "/x", null, Map.of()))).isFalse();
    }

    @Test
    @DisplayName("header conditions AND; names case-insensitive; missing header fails")
    void headerConditions() {
        List<McpHeaderCondition> headers = List.of(new McpHeaderCondition("X-Tenant-Id", "EXACT", "acme"),
                new McpHeaderCondition("X-Canary", "EXACT", "true"));
        Map<String, List<String>> h = Map.of("x-tenant-id", List.of("acme"), "x-canary", List.of("true"));
        assertThat(McpRouteRules.matches(rule(null, null, null, null, null, headers), request("GET", "/x", null, h)))
                .isTrue();
        assertThat(McpRouteRules.matches(rule(null, null, null, null, null, headers),
                request("GET", "/x", null, Map.of("x-tenant-id", List.of("acme"))))).isFalse();
        assertThat(McpRouteRules.matches(
                rule(null, null, null, null, null, List.of(new McpHeaderCondition("X-Version", "PREFIX", "v2"))),
                request("GET", "/x", null, Map.of("x-version", List.of("v2.4.0"))))).isTrue();
    }

    @Test
    @DisplayName("invalid RE2 patterns are rejected at validation time")
    void invalidRe2() {
        assertThatThrownBy(() -> McpRouteRules.requireRe2("REGEX", "^(unclosed"))
                .isInstanceOf(com.google.re2j.PatternSyntaxException.class);
        // Backreferences are legal in java.util but not RE2.
        assertThatThrownBy(() -> McpRouteRules.requireRe2("REGEX", "(a)\\1"))
                .isInstanceOf(com.google.re2j.PatternSyntaxException.class);
        assertThatCode(() -> McpRouteRules.requireRe2("REGEX", "^/api/v[0-9]+$")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("conflict detection flags identical match surfaces only")
    void conflicts() {
        List<McpHeaderCondition> baseHeaders = List.of(new McpHeaderCondition("X-A", "EXACT", "1"),
                new McpHeaderCondition("X-B", "EXACT", "2"));
        McpRouteRule a = rule("EXACT", "/api", "EXACT", "mcp.example.com", "GET", baseHeaders);
        // Same surface with headers re-ordered and name case flipped still collides.
        McpRouteRule twin = rule("EXACT", "/api", "EXACT", "mcp.example.com", "GET",
                List.of(new McpHeaderCondition("x-b", "EXACT", "2"), new McpHeaderCondition("X-A", "EXACT", "1")));
        McpRouteRule other = rule("PREFIX", "/api", null, null, null, List.of());

        assertThat(McpRouteRules.conflicting(a, List.of(a))).isEmpty();
        assertThat(McpRouteRules.conflicting(a, List.of(twin, other, a))).containsExactly(twin);
        // A different path surface or different headers do not conflict.
        assertThat(McpRouteRules.conflicting(a, List.of(other))).isEmpty();
        assertThat(McpRouteRules.conflicting(a, List.of(rule("EXACT", "/api", "EXACT", "mcp.example.com", "GET",
                List.of(new McpHeaderCondition("X-A", "EXACT", "1")))))).isEmpty();
        // DISABLED duplicates do not conflict (re-armed ones are re-checked).
        McpRouteRule disabled = new McpRouteRule(twin.id(), twin.tenantId(), SERVICE, "twin", null, 1000, "EXACT",
                "/api", "EXACT", "mcp.example.com", "GET",
                List.of(new McpHeaderCondition("x-b", "EXACT", "2"), new McpHeaderCondition("X-A", "EXACT", "1")),
                "DISABLED", 0, UUID.randomUUID(), Instant.now(), Instant.now());
        assertThat(McpRouteRules.conflicting(a, List.of(disabled))).isEmpty();
    }

    @Test
    @DisplayName("custom catch-all collides with the system default route")
    void catchAllVsDefault() {
        McpRouteRule catchAll = rule(null, null, null, null, null, List.of());
        McpRouteRule defaultRule = rule(null, null, null, null, null, List.of());
        assertThat(McpRouteRules.sameMatchSurface(catchAll, defaultRule)).isTrue();
    }

    @Test
    @DisplayName("rule model enforces matcher and method constraints")
    void modelGuards() {
        assertThatThrownBy(() -> rule("EXACT", "no-slash", null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule("EXACT", null, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule(null, null, null, null, "GET,GET", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule(null, null, null, null, "TRACE", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
