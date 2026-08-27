package com.miqroera.miqrokey.adapters.registry;

import com.miqroera.miqrokey.spi.AdapterRegistry;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Thread-safe compile-time adapter registry
 * ({@code docs/provider-adapter-contract.md §9}): adapters are wired from
 * classpath code during startup, never discovered or downloaded at runtime.
 * Registering two adapters with the same {@code adapterId} aborts startup.
 */
public final class BuiltInAdapterRegistry implements AdapterRegistry {

    private final Map<String, ProviderProductAdapter> adapters = new LinkedHashMap<>();

    @Override
    public synchronized void register(ProviderProductAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        String id = adapter.adapterId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        if (adapters.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate adapterId '" + id + "': two adapters registered under the "
                    + "same id — registration is compile-time only, duplicates abort startup");
        }
        adapters.put(id, adapter);
    }

    @Override
    public synchronized Optional<ProviderProductAdapter> findById(String adapterId) {
        return Optional.ofNullable(adapters.get(adapterId));
    }

    @Override
    public synchronized Set<String> adapterIds() {
        return new TreeSet<>(adapters.keySet());
    }
}
