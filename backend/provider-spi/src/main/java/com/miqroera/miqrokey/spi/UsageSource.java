package com.miqroera.miqrokey.spi;

/**
 * Where a usage observation came from
 * ({@code docs/provider-adapter-contract.md §5}). Estimates must never be
 * presented as official values.
 */
public enum UsageSource {

    /** Explicitly given in the provider response / SSE events. */
    PROVIDER_RESPONSE,

    /** Pulled later from the provider's official usage/billing API. */
    PROVIDER_USAGE_API,

    /** Local estimate when no official value is obtainable. */
    LOCAL_ESTIMATE,

    /** Not reliably obtainable. */
    UNAVAILABLE,
}
