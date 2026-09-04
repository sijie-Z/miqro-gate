package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One route rule of an MCP service (F11, Tencent AI gateway doc 135482,
 * {@code mcp_route_rule} V28). Rules decide which inbound requests may reach
 * the service; every rule shares the same upstream (the service endpoint).
 *
 * <p>
 * The system-generated {@code default} rule (priority 0, no matchers) is the
 * catch-all that keeps the service reachable; it is immutable through the API.
 * Custom rules default to priority 1000 — higher priority wins first. Within a
 * rule, path/host/method/header conditions are AND-ed.
 *
 * <p>
 * Model-level validation mirrors the API contract; {@code null} matcher fields
 * mean "unrestricted" and {@code headerConditions} is never null.
 */
public record McpRouteRule(UUID id, UUID tenantId, UUID mcpServiceId, String name, String description, int priority,
        String pathMode, String pathValue, String hostMode, String hostValue, String methods,
        List<McpHeaderCondition> headerConditions, String status, long version, UUID createdBy, Instant createdAt,
        Instant updatedAt) {

    public static final String DEFAULT_ROUTE_NAME = "default";
    public static final String DEFAULT_ROUTE_PRIORITY = "0";
    /** Custom routes should stay at or above 1000 so they win over default. */
    public static final int DEFAULT_CUSTOM_PRIORITY = 1000;
    public static final int MAX_PRIORITY = 65535;
    public static final int MAX_HEADER_CONDITIONS = 8;

    public static final Set<String> MATCH_MODES = Set.of("EXACT", "PREFIX", "REGEX");
    public static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    public McpRouteRule {
        if (id == null || tenantId == null || mcpServiceId == null) {
            throw new IllegalArgumentException("id/tenantId/mcpServiceId are required");
        }
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("route name must be 1-64 chars");
        }
        if (description != null && description.length() > 200) {
            throw new IllegalArgumentException("description must not exceed 200 chars");
        }
        if (priority < 0 || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException("priority must be between 0 and " + MAX_PRIORITY);
        }
        validateMatcher("path", pathMode, pathValue, true);
        validateMatcher("host", hostMode, hostValue, false);
        if (methods != null && !methods.isBlank()) {
            String[] tokens = methods.split(",");
            Set<String> unique = new TreeSet<>();
            for (String token : tokens) {
                if (!HTTP_METHODS.contains(token)) {
                    throw new IllegalArgumentException("unknown HTTP method " + token);
                }
                unique.add(token);
            }
            if (unique.size() != tokens.length) {
                throw new IllegalArgumentException("methods must not repeat");
            }
            methods = String.join(",", unique);
        } else {
            methods = null;
        }
        if (headerConditions == null || headerConditions.size() > MAX_HEADER_CONDITIONS) {
            throw new IllegalArgumentException("header conditions must hold 0-" + MAX_HEADER_CONDITIONS + " entries");
        }
        if (status == null || !(status.equals("ENABLED") || status.equals("DISABLED"))) {
            throw new IllegalArgumentException("status must be ENABLED or DISABLED");
        }
        if (description != null && description.isBlank()) {
            description = null;
        }
    }

    private static void validateMatcher(String label, String mode, String value, boolean pathMustStartWithSlash) {
        if (mode == null && value == null) {
            return;
        }
        if (mode == null || value == null || !MATCH_MODES.contains(mode)) {
            throw new IllegalArgumentException(label + " matcher needs both mode and value");
        }
        if (value.length() > 256) {
            throw new IllegalArgumentException(label + " value must not exceed 256 chars");
        }
        if (pathMustStartWithSlash && !"REGEX".equals(mode) && !value.startsWith("/")) {
            throw new IllegalArgumentException(label + " value must start with /");
        }
    }

    /** The system-generated catch-all rule of a service. */
    public boolean isDefault() {
        return priority == 0 && DEFAULT_ROUTE_NAME.equalsIgnoreCase(name);
    }

    /** Unrestricted method list (null) means every HTTP method matches. */
    public boolean methodUnrestricted() {
        return methods == null || methods.isBlank();
    }

    public String normalizedName() {
        return name.toLowerCase(Locale.ROOT);
    }
}
