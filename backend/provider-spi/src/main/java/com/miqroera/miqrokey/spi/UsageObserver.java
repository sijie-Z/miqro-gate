package com.miqroera.miqrokey.spi;

/**
 * Consumes usage observations extracted from a request's upstream exchange.
 * Observers must be side-effect free on the data path: they read the response
 * byte-stream in parallel, never alter bytes, event boundaries or arrival order
 * ({@code docs/provider-adapter-contract.md §4/§5}). Parse failures are
 * reported as {@link UsageObservation} with
 * {@code source = UsageSource.UNAVAILABLE} and a sanitized error, never a body.
 */
public interface UsageObserver {

    /**
     * Called at most once per request when usage is known or definitively missing.
     * Implementations must be fast and non-blocking.
     */
    void onUsage(UsageObservation observation);
}
