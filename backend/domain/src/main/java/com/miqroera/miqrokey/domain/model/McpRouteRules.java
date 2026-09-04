package com.miqroera.miqrokey.domain.model;

import com.google.re2j.Pattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pure route-rule logic for F11 (Tencent AI gateway doc 135482) — no Spring, no
 * database. Two concerns:
 *
 * <ul>
 * <li>{@link #matches}: does an inbound request hit a rule? Path/host/method
 * and every header condition are AND-ed; REGEX modes use RE2 (full match —
 * examples in the upstream doc anchor with {@code ^…$}).</li>
 * <li>{@link #sameMatchSurface} / {@link #conflicting}: config-surface
 * comparison used by the real-time conflict check (two ENABLED rules of the
 * same service must not describe the identical request surface; regex
 * containment is undecidable in general, so equivalence of the canonical
 * condition set is the enforced bound — documented in api-contract).</li>
 * </ul>
 *
 * <p>
 * The matching code never inspects a request body (不读正文).
 */
public final class McpRouteRules {

    private McpRouteRules() {
    }

    /** Inbound request surface a matcher evaluates. Header names lower-cased. */
    public record RouteRequest(String method, String path, String host, Map<String, List<String>> headers) {

        public RouteRequest {
            if (method == null || path == null) {
                throw new IllegalArgumentException("method and path are required");
            }
            headers = headers == null ? Map.of() : headers;
        }

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    /** Full-match semantics for REGEX (examples in the upstream doc anchor). */
    public static boolean matches(McpRouteRule rule, RouteRequest request) {
        if (!matchesPath(rule, request.path())) {
            return false;
        }
        if (!matchesHost(rule, request.host())) {
            return false;
        }
        if (!rule.methodUnrestricted() && !List.of(rule.methods().split(",")).contains(request.method())) {
            return false;
        }
        for (McpHeaderCondition condition : rule.headerConditions()) {
            String actual = request.header(condition.name());
            if (actual == null || !matchesValue(condition.mode(), condition.value(), actual)) {
                return false;
            }
        }
        return true;
    }

    /** Compiles a RE2 pattern; throws IllegalArgumentException when invalid. */
    public static void requireRe2(String mode, String value) {
        if ("REGEX".equals(mode)) {
            Pattern.compile(value);
        }
    }

    /** Canonical, comparison-safe description of a rule's match surface. */
    public static String matchSurface(McpRouteRule rule) {
        List<String> parts = new ArrayList<>();
        parts.add(rule.pathMode() + "|" + rule.pathValue());
        parts.add(rule.hostMode() + "|" + rule.hostValue());
        parts.add(rule.methodUnrestricted() ? "*" : rule.methods());
        List<String> headers = rule.headerConditions().stream().map(McpHeaderCondition::canonical).sorted().toList();
        parts.add(String.join("&", headers));
        return String.join(";", parts);
    }

    /**
     * Two rules describe the same inbound surface when their canonical condition
     * sets are equal (path/host/methods/headers all equal). Name, description,
     * priority and status are not part of the surface.
     */
    public static boolean sameMatchSurface(McpRouteRule left, McpRouteRule right) {
        return matchSurface(left).equals(matchSurface(right));
    }

    /**
     * Rules that would collide with {@code candidate} among {@code existing}: an
     * ENABLED rule of the same service whose match surface equals the candidate's.
     * The candidate itself (by id) is excluded so updates stay valid. Returned
     * rules are sorted by priority desc, name asc.
     */
    public static List<McpRouteRule> conflicting(McpRouteRule candidate, List<McpRouteRule> existing) {
        return existing.stream().filter(rule -> rule.status().equals("ENABLED"))
                .filter(rule -> !rule.id().equals(candidate.id())).filter(rule -> sameMatchSurface(candidate, rule))
                .sorted(Comparator.comparingInt(McpRouteRule::priority).reversed()
                        .thenComparing(McpRouteRule::normalizedName))
                .toList();
    }

    private static boolean matchesPath(McpRouteRule rule, String path) {
        if (rule.pathMode() == null) {
            return true;
        }
        return matchesValue(rule.pathMode(), rule.pathValue(), path);
    }

    private static boolean matchesHost(McpRouteRule rule, String host) {
        if (rule.hostMode() == null) {
            return true;
        }
        if (host == null) {
            return false;
        }
        // Hosts are matched case-insensitively (domain names are case-blind):
        // EXACT/PREFIX compare against the lower-cased pattern; REGEX stays as
        // authored since the operator controls the expression.
        String actual = host.toLowerCase(java.util.Locale.ROOT);
        if (!"REGEX".equals(rule.hostMode())) {
            return matchesValue(rule.hostMode(), rule.hostValue().toLowerCase(java.util.Locale.ROOT), actual);
        }
        return matchesValue(rule.hostMode(), rule.hostValue(), actual);
    }

    private static boolean matchesValue(String mode, String pattern, String actual) {
        return switch (mode) {
            case "EXACT" -> pattern.equals(actual);
            case "PREFIX" -> actual.startsWith(pattern);
            case "REGEX" -> Pattern.matches(pattern, actual);
            default -> throw new IllegalArgumentException("unknown match mode " + mode);
        };
    }
}
