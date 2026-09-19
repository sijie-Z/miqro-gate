package com.miqroera.miqrokey.controlplane.security;

/**
 * Escaping for the hand-written {@code application/problem+json} envelopes in
 * this package (issue #445, extended by PH16).
 *
 * <p>
 * Every writer here splices values into a JSON string with
 * {@link String#format} rather than going through Jackson, so any
 * client-controlled value — notably the echoed {@code X-Request-Id} header —
 * must be escaped before it is concatenated. Quoting only {@code "} and
 * {@code \} is <em>not</em> enough: a literal HTAB (0x09) survives Tomcat's
 * header parser and reaches {@code getHeader} verbatim, so it is reflected raw
 * into the body and the whole envelope stops being parseable — Jackson rejects
 * it with {@code Illegal unquoted character ((CTRL-CHAR, code 9))}. Control
 * characters below 0x20 are therefore escaped too, which is what the
 * controller-side {@code escapeJson} copies already did.
 * </p>
 */
final class ProblemJson {

    private ProblemJson() {
    }

    /**
     * Escapes {@code value} for inclusion inside a JSON string literal. A
     * {@code null} input maps to the JSON literal {@code null} (no quotes).
     */
    static String escape(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
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
}
