package com.miqroera.miqrokey.domain.usage;

/**
 * Terminal and in-flight states of a gateway request lifecycle record
 * ({@code request_usage_records.request_status}, usage-accounting §2).
 *
 * <p>
 * The gateway writes {@link #IN_FLIGHT} at request start and finalizes exactly
 * once with one of the observed terminal states. {@code AUTH_REJECTED},
 * {@code MODEL_NOT_ALLOWED} and {@code USAGE_PARSE_FAILED} are reserved for
 * future flows: auth/model failures are security events today (they never reach
 * the upstream, so no lifecycle record is created), and the usage observer
 * swallows parse errors by design (covered by {@code usage_missing}).
 * </p>
 */
public enum RequestStatus {

    /** Created at request start; the only state that can be finalized. */
    IN_FLIGHT,

    /** Upstream returned 2xx and the response was fully written. */
    SUCCEEDED,

    /** Upstream answered with a non-2xx status (body forwarded untouched). */
    UPSTREAM_REJECTED,

    /** Upstream was unreachable or the credential could not be routed. */
    UPSTREAM_UNAVAILABLE,

    /** The client disconnected before the response finished streaming. */
    CLIENT_CANCELLED,

    /** Upstream exceeded the response timeout before the first byte. */
    TIMEOUT_BEFORE_FIRST_BYTE,

    /** The stream failed after the first byte was delivered. */
    STREAM_INTERRUPTED,

    /** Reserved: the request was rejected during virtual-key authentication. */
    AUTH_REJECTED,

    /** Reserved: the requested model was not in the key's allowed set. */
    MODEL_NOT_ALLOWED,

    /** Reserved: the usage parser failed on a complete upstream response. */
    USAGE_PARSE_FAILED,
}
