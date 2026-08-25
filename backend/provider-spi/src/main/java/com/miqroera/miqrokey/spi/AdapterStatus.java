package com.miqroera.miqrokey.spi;

/**
 * Verification lifecycle of an adapter/product definition
 * ({@code docs/provider-adapter-contract.md §7}).
 *
 * <p>
 * The portal must never display {@code IMPLEMENTED} as {@code VERIFIED}. The
 * production default catalog only enables {@code VERIFIED} products; admins may
 * explicitly enable {@code IMPLEMENTED} ones with a persistent warning.
 */
public enum AdapterStatus {

    /** Official documentation confirms the design; not implemented yet. */
    DOCUMENTED,

    /** Code and Mock contract tests complete. */
    IMPLEMENTED,

    /** Contract tests passed with a real provider credential. */
    VERIFIED,

    /** A previously verified capability regressed due to provider changes. */
    DEGRADED,

    /** Disabled by an admin or catalog release. */
    DISABLED,
}
