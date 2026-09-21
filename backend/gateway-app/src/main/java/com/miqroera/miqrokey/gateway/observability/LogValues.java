package com.miqroera.miqrokey.gateway.observability;

/**
 * Flattens caller-supplied values before they reach a gateway log line
 * (CWE-117, #1303).
 *
 * <p>
 * A JSON string may carry a real line break: {@code "\n"} in the wire body is a
 * legal escape that decodes to U+000A, so a value read out of a request body
 * can split one SLF4J event into several physical lines when the appender
 * renders it. The forged lines look exactly like the gateway's own output — an
 * attacker with an ordinary Virtual Key can fabricate an {@code ERROR} line, a
 * fake logger name and a fake {@code requestId} in the operator's log file and
 * in anything that tails it (Loki/Filebeat/syslog ship line-by-line).
 * </p>
 *
 * <p>
 * Same hygiene as {@code ApiKeyAuthFilter#forLog} on the control plane: control
 * characters and the {@code [ ] , =} delimiters of a {@code key=value}
 * structure become {@code ?}, and the result is bounded so an oversized value
 * cannot turn every call into a log-amplification vector. Only the log line is
 * affected — what is persisted (the MCP access log row) keeps the original
 * value.
 * </p>
 */
public final class LogValues {

    /**
     * Bound on a single flattened value. Larger than the control plane's 64
     * ({@code ApiKeyAuthFilter}) because JSON-RPC method and tool names are
     * legitimately longer than a requestId or a {@code sub} claim.
     */
    static final int MAX_LENGTH = 200;

    private LogValues() {
    }

    /**
     * Returns a single-line, bounded rendering of {@code value} for logging.
     *
     * @param value
     *            the caller-supplied value; may be {@code null}
     * @return the flattened value, or {@code null} when {@code value} is
     *         {@code null} (so callers keep their own placeholder handling)
     */
    public static String forLog(String value) {
        if (value == null) {
            return null;
        }
        String flat = value.replaceAll("[\\p{C}\\p{Zl}\\p{Zp}]", "?").replaceAll("[\\[\\],=]", "?");
        return flat.length() <= MAX_LENGTH ? flat : flat.substring(0, MAX_LENGTH) + "…";
    }
}
