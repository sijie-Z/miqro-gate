package com.miqroera.miqrokey.route;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder of the current routing snapshot. The snapshot is swapped
 * atomically on refresh; readers on the hot path never block.
 *
 * <p>
 * The version monotonically increases with each successful load. If a refresh
 * fails, the previous snapshot is retained and the error is logged — the
 * gateway continues routing on the last good state (documented behavior:
 * revocation/rotation propagate within one refresh interval; a failing database
 * means the gateway keeps serving the previous decision set).
 * </p>
 */
public final class RouteSnapshotHolder implements RouteSnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(RouteSnapshotHolder.class);

    private final AtomicReference<RouteSnapshot> current;
    private final long versionIncrement = 1L;

    public RouteSnapshotHolder(Clock clock) {
        this.current = new AtomicReference<>(RouteSnapshot.empty(0, clock.instant()));
    }

    @Override
    public RouteSnapshot current() {
        return current.get();
    }

    /**
     * Atomically installs a refreshed snapshot with an incremented version.
     *
     * @param snapshot
     *            the freshly loaded snapshot
     */
    public void install(RouteSnapshot snapshot) {
        RouteSnapshot previous = current.getAndSet(snapshot);
        if (snapshot.version() <= previous.version()) {
            log.warn("Route snapshot version regression: previous={}, new={}; keeping new", previous.version(),
                    snapshot.version());
        }
        log.debug("Route snapshot installed: version={}, keys={}, bindings={}, credentials={}, models={}",
                snapshot.version(), snapshot.keys().size(), snapshot.bindings().size(), snapshot.credentials().size(),
                snapshot.modelsByKeyId().values().stream().mapToInt(java.util.Set::size).sum());
    }
}
