package com.miqroera.miqrokey.controlplane.service;

/**
 * Shared change-summary builders for audit events (jsonb-safe, secret-free).
 * Free-form names are user input: control characters are dropped and JSON
 * specials escaped so a crafted name cannot corrupt the summary document.
 */
final class AuditSummaries {

    private AuditSummaries() {
    }

    static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
    }

    /** Renders alternating key/value pairs as one flat JSON object. */
    static String summary(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":\"");
            sb.append(escapeJson(String.valueOf(kv[i + 1]))).append('"');
        }
        return sb.append('}').toString();
    }

    /**
     * Full JSON-string escaping for user-supplied values (#447): backslash, quote
     * and every control character (short escapes where they exist, {@code \\uXXXX}
     * otherwise). A crafted value must round-trip as a literal string and can never
     * forge sibling members of the summary document.
     */
    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Summary with the context's machine marker appended when present (issue #324):
     * human calls render exactly like {@link #summary(Object...)}, open admin API
     * calls additionally carry {@code via: admin-api:<key name>}.
     */
    static String summary(AuditContext context, Object... kv) {
        if (context.via() == null) {
            return summary(kv);
        }
        Object[] extended = java.util.Arrays.copyOf(kv, kv.length + 2);
        extended[kv.length] = "via";
        extended[kv.length + 1] = context.via();
        return summary(extended);
    }
}
