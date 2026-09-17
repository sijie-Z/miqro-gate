package com.miqroera.miqrokey.domain.usage;

/**
 * Lifecycle facts of one forwarded call, read from
 * {@code request_usage_records} (the gateway's per-request audit trail) and
 * joined onto the statistics fact rows by {@code (tenant, gateway_request_id)}.
 *
 * <p>
 * Only calls that actually reached upstream have a lifecycle row: coalesced
 * requests write {@code usage_event} but no lifecycle row. A row without one
 * carries {@code null} here — "not observed" is different from "unknown".
 * </p>
 *
 * @param wireProtocol
 *            inbound protocol family of the call (e.g.
 *            {@code ANTHROPIC_MESSAGES})
 * @param timeToFirstByteMs
 *            milliseconds from request start to the first upstream response
 *            byte; null when no first byte was ever seen (timeout before first
 *            byte, client cancel before upstream answered)
 * @param requestStatus
 *            terminal lifecycle status ({@code SUCCEEDED},
 *            {@code UPSTREAM_REJECTED}, {@code CLIENT_CANCELLED}, ...); null
 *            while still {@code IN_FLIGHT} or when no lifecycle row exists
 */
public record LifecycleInfo(String wireProtocol, Long timeToFirstByteMs, String requestStatus) {
}
