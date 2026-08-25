package com.miqroera.miqrokey.controlplane.service;

/**
 * Publishes a route-refresh signal after a mutating control-plane transaction
 * commits, so the gateway reloads its routing snapshot promptly.
 *
 * <p>
 * Implementations must fire AFTER_COMMIT (via
 * {@code TransactionSynchronizationManager}): a notification published inside
 * an uncommitted transaction would be delivered before the data is visible and
 * the gateway would refresh against stale rows. A no-op implementation is used
 * when no notifier is configured (tests, in-memory runs).
 * </p>
 */
public interface RouteRefreshPublisher {

    /** Signals a routing-relevant change; must never throw. */
    void publishChanged();

    /** No-op for contexts without a notifier. */
    RouteRefreshPublisher NONE = () -> {
    };
}
