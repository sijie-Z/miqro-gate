package com.miqroera.miqrokey.domain.model;

import java.util.Objects;

/**
 * One header match condition of an MCP route rule (F11, {@code mcp_route_rule}
 * V28). Conditions inside a rule are AND-ed: every condition must be satisfied
 * for the rule to match. Header names are matched case-insensitively; values
 * follow the configured mode ({@code EXACT|PREFIX|REGEX}, RE2 for REGEX).
 *
 * @param name
 *            header name, case-insensitive, max 64 chars
 * @param mode
 *            {@code EXACT|PREFIX|REGEX}
 * @param value
 *            match value, max 256 chars
 */
public record McpHeaderCondition(String name, String mode, String value) {

    public McpHeaderCondition {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("header condition name must be 1-64 chars");
        }
        if (mode == null || !(mode.equals("EXACT") || mode.equals("PREFIX") || mode.equals("REGEX"))) {
            throw new IllegalArgumentException("mode must be EXACT, PREFIX or REGEX");
        }
        if (value == null || value.length() > 256) {
            throw new IllegalArgumentException("header condition value must not exceed 256 chars");
        }
        name = name.trim();
        value = value.trim();
    }

    public String key() {
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    /** Canonical form used for match-surface comparison (order-insensitive). */
    public String canonical() {
        return key() + "|" + mode + "|" + value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof McpHeaderCondition that && key().equals(that.key()) && mode.equals(that.mode())
                && value.equals(that.value());
    }

    @Override
    public int hashCode() {
        return Objects.hash(key(), mode, value);
    }
}
