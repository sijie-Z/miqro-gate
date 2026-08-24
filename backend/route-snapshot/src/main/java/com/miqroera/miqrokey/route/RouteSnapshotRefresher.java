package com.miqroera.miqrokey.route;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodically reloads the routing snapshot from the database and atomically
 * installs it into the holder. A failed refresh keeps the last good snapshot
 * (documented degradation: control-plane changes propagate within one refresh
 * interval; a DB outage does not tear down routing).
 *
 * <p>
 * The {@link #refresh()} method runs on the gateway's scheduler
 * ({@code @EnableScheduling}, wired in {@code GatewayFeatureConfig}); interval
 * comes from {@code miqrokey.gateway.route-snapshot.refresh-interval} (default
 * 30s). Tests call {@link #refresh()} directly after control-plane changes.
 * </p>
 */
public final class RouteSnapshotRefresher {

    private static final Logger log = LoggerFactory.getLogger(RouteSnapshotRefresher.class);

    private final JdbcRouteSnapshotLoader loader;
    private final RouteSnapshotHolder holder;
    private final Clock clock;
    private final AtomicLong versionCounter = new AtomicLong(0);

    public RouteSnapshotRefresher(JdbcRouteSnapshotLoader loader, RouteSnapshotHolder holder, Clock clock) {
        this.loader = loader;
        this.holder = holder;
        this.clock = clock;
    }

    /**
     * Loads and installs a fresh snapshot. Called by the scheduled task and
     * explicitly after control-plane changes in tests.
     */
    @Scheduled(fixedDelayString = "${miqrokey.gateway.route-snapshot.refresh-interval:30s}")
    public void refresh() {
        long version = versionCounter.incrementAndGet();
        Instant loadedAt = clock.instant();
        try {
            RouteSnapshot snapshot = loader.load(version, loadedAt);
            holder.install(snapshot);
        } catch (Exception e) {
            log.error("Route snapshot refresh failed (version={}); keeping previous snapshot", version, e);
        }
    }
}
