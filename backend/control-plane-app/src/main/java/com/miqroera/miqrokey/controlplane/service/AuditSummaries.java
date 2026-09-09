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
            String value = String.valueOf(kv[i + 1]).replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append(value).append('"');
        }
        return sb.append('}').toString();
    }
}
