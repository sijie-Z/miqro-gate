package com.miqroera.miqrokey.testing;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;

/**
 * Mutable in-memory {@link RouteSnapshotProvider} for contract tests: install a
 * fixture snapshot, then atomically swap in updated snapshots to simulate
 * revocations, rotations, and grant changes between requests.
 */
public final class InMemoryRouteSnapshotProvider implements RouteSnapshotProvider {

    private volatile RouteSnapshot current;

    public InMemoryRouteSnapshotProvider(RouteSnapshot initial) {
        this.current = initial;
    }

    /** Atomically replaces the snapshot served to the hot path. */
    public void install(RouteSnapshot snapshot) {
        this.current = snapshot;
    }

    @Override
    public RouteSnapshot current() {
        return current;
    }
}
