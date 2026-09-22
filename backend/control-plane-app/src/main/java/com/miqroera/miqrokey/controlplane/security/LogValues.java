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
 * The control plane's shared implementation of this rule: before #1313 it
 * guarded the billing channel only (a private {@code ApiKeyAuthFilter#forLog}),
 * which is how the audit and auth log lines stayed exposed. Control characters
 * and the {@code [ ] , =} delimiters of a {@code key=value} structure become
 * {@code ?}, and the result is bounded so an oversized value cannot turn every
 * request into a log-amplification vector. This applies to the log line only —
 * what is persisted (and what the API echoes back) keeps the original value.
 * </p>
 *
 * <p>
 * Shared, but not yet the only copy: {@code OriginInterceptor} still carries
 * its own private flattener that strips control characters only — no delimiter
 * flattening, no bound — and the gateway has a separate twin with its own
 * bound. Folding the remaining copies onto this class is a follow-up, not part
 * of #1313.
 * </p>
 *
 * <p>
 * The rule is lossy by design, which matters when reading a log line: values
 * that differ only in a delimiter or in an invisible format character
 * ({@code a=b}, {@code a,b}, {@code a]b}, a name carrying a zero-width joiner)
 * all render as {@code a?b}, so a legitimate identifier containing one of them
 * is visibly altered and two accounts can become indistinguishable. The bound
 * is applied after flattening, so it can also fall between the halves of a
 * surrogate pair.
 * </p>
 */
public final class LogValues {

    /**
     * Default bound on a single flattened value. Matches the {@code username}
     * column ({@code varchar(128)}), so a legitimate identifier is never truncated
     * while an attacker-supplied value still cannot amplify the log. A truncated
     * value keeps {@code maxLength} characters plus the {@code …} marker, so the
     * returned string is at most {@code maxLength + 1} characters long — the
     * constant bounds the input kept, not the output length.
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
