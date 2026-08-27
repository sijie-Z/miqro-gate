package com.miqroera.miqrokey.route;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;

/**
 * Source of the current routing snapshot for the gateway hot path.
 *
 * <p>
 * Implementations must be non-blocking from the caller's perspective where
 * possible; the JDBC implementation loads on the caller's thread and the holder
 * caches the last good snapshot, so the hot path only touches memory.
 * </p>
 */
public interface RouteSnapshotProvider {

    /**
     * Returns the current snapshot. Never throws: implementations must return the
     * last good snapshot when a refresh fails.
     */
    RouteSnapshot current();
}
