package com.miqroera.miqrokey.spi;

/**
 * Provenance of a {@link PlanSnapshot} value
 * ({@code docs/provider-adapter-contract.md §6}). Admins never hand-enter a
 * remaining balance as fact; the UI must show the source and timestamp.
 */
public enum PlanDataSource {

    /** Provider's official balance/entitlement API. */
    OFFICIAL_API,

    /** Derived from official response headers or official billing detail. */
    PROVIDER_HEADER,

    /** Estimated from locally recorded usage and catalog rules. */
    LOCAL_ESTIMATE,

    /** Not reliably obtainable; shown as unknown with an admin alert. */
    UNAVAILABLE,
}
