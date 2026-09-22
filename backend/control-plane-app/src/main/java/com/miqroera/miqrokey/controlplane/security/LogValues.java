package com.miqroera.miqrokey.controlplane.security;

/**
 * Flattens caller-supplied values before they reach a control plane log line
 * (CWE-117, #1313).
 *
 * <p>
 * A JSON string may carry a real line break: {@code "\n"} in the wire body is a
 * legal escape that decodes to U+000A, so a value read out of a request body
 * can split one SLF4J event into several physical lines when the appender
 * renders it. A username is caller-supplied free text — unauthenticated at
 * {@code /api/v1/auth/register}, which is CSRF-exempt and open by default — so
 * the forged lines can imitate the control plane's own format (a fake
 * {@code ERROR} level, a fake logger name, a fake {@code requestId}).
 * Line-oriented readers and shippers (Loki/Filebeat/syslog) cannot tell them
 * apart.
 * </p>
 *
 * <p>
 * Single implementation for the whole control plane: the same rule already
 * guarded the billing channel only (formerly a private
 * {@code ApiKeyAuthFilter#forLog}), which is how the audit and auth log lines
 * stayed exposed. Control characters and the {@code [ ] , =} delimiters of a
 * {@code key=value} structure become {@code ?}, and the result is bounded so an
 * oversized value cannot turn every request into a log-amplification vector.
 * This applies to the log line only — what is persisted (and what the API
 * echoes back) keeps the original value.
 * </p>
 */
public final class LogValues {

    /**
     * Default bound on a single flattened value. Matches the {@code username}
     * column ({@code varchar(128)}), so a legitimate identifier is never truncated
     * while an attacker-supplied value still cannot amplify the log.
     */
    public static final int DEFAULT_MAX_LENGTH = 128;

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
        return forLog(value, DEFAULT_MAX_LENGTH);
    }

    /**
     * Returns a single-line rendering of {@code value}, bounded to
     * {@code maxLength} characters plus the truncation marker.
     *
     * @param value
     *            the caller-supplied value; may be {@code null}
     * @param maxLength
     *            maximum number of characters kept from {@code value}
     * @return the flattened value, or {@code null} when {@code value} is
     *         {@code null}
     */
    public static String forLog(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String flat = value.replaceAll("[\\p{C}\\p{Zl}\\p{Zp}]", "?").replaceAll("[\\[\\],=]", "?");
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength) + "…";
    }
}
