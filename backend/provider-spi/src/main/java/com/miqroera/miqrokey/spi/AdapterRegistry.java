package com.miqroera.miqrokey.spi;

import java.util.Optional;
import java.util.Set;

/**
 * Compile-time registry of {@link ProviderProductAdapter}s
 * ({@code docs/provider-adapter-contract.md §9}): adapters are wired at startup
 * from classpath code, never discovered or downloaded at runtime. Registration
 * of a duplicate {@code adapterId} must fail startup.
 */
public interface AdapterRegistry {

    /** Registers an adapter; throws on duplicate adapterId or null. */
    void register(ProviderProductAdapter adapter);

    /** Resolves an adapter by id; empty when not registered. */
    Optional<ProviderProductAdapter> findById(String adapterId);

    /** All registered adapter ids, sorted. */
    Set<String> adapterIds();
}
