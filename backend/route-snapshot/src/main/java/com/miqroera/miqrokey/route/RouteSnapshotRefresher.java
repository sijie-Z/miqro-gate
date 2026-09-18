package com.miqroera.miqrokey.route;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>
 * <b>Schema-not-ready (#846).</b> On a brand-new deployment the gateway can
 * start before the control plane's Flyway migrations finish (postgres reports
 * healthy on an empty database). The first refresh then fails with PostgreSQL
 * {@code undefined_table} — not a defect to alarm over, but "not built yet".
 * That case is classified separately: it logs a WARN naming the real cause and
 * retries on a short, backed-off ticker
 * ({@code miqrokey.gateway.route-snapshot.retry-check-interval}, default 2s,
 * backoff 2→4→8→16→30s) instead of waiting out the full refresh interval.
 * Every other failure keeps the historical ERROR log. Compose also orders the
 * gateway after control-plane health; this retry is the belt for paths where
 * ordering cannot help (gateway pointed at a database nobody migrated yet).
 * </p>
 */
public final class RouteSnapshotRefresher {

    private static final Logger log = LoggerFactory.getLogger(RouteSnapshotRefresher.class);

    /** PostgreSQL SQLState for {@code undefined_table} ("relation … does not exist"). */
    private static final String UNDEFINED_TABLE = "42P01";

    /** Backoff cap for the not-ready retry ticker — never slower than the normal refresh. */
    private static final long MAX_RETRY_BACKOFF_SECONDS = 30;

    private final JdbcRouteSnapshotLoader loader;
    private final RouteSnapshotHolder holder;
    private final Clock clock;
    private final AtomicLong versionCounter = new AtomicLong(0);

    /** Last failure was "schema not built yet" — the short ticker keeps retrying. */
    private final AtomicBoolean schemaNotReady = new AtomicBoolean(false);
    private final AtomicInteger notReadyAttempts = new AtomicInteger(0);
    private volatile Instant nextAttemptAt = Instant.EPOCH;

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
            clearNotReady();
        } catch (Exception e) {
            if (isSchemaNotReady(e)) {
                deferUntilSchemaReady(e);
                return;
            }
            log.error("Route snapshot refresh failed (version={}); keeping previous snapshot", version, e);
        }
    }

    /**
     * The short ticker behind {@link #refresh()}: fires only while a
     * schema-not-ready failure is pending, and honors the backoff gate.
     */
    @Scheduled(fixedDelayString = "${miqrokey.gateway.route-snapshot.retry-check-interval:2s}")
    public void retryWhenSchemaNotReady() {
        if (schemaNotReady.get() && !clock.instant().isBefore(nextAttemptAt)) {
            refresh();
        }
    }

    private void deferUntilSchemaReady(Exception e) {
        int attempt = notReadyAttempts.incrementAndGet();
        long backoff = retryBackoffSeconds(attempt);
        nextAttemptAt = clock.instant().plusSeconds(backoff);
        schemaNotReady.set(true);
        log.warn(
                "Route snapshot refresh deferred: database schema not initialised yet "
                        + "(control-plane migrations still running; attempt {}); keeping previous snapshot; "
                        + "retrying in ~{}s",
                attempt, backoff);
        if (log.isDebugEnabled()) {
            log.debug("schema-not-ready cause", e);
        }
    }

    private void clearNotReady() {
        if (schemaNotReady.compareAndSet(true, false)) {
            log.info("Route snapshot refresh recovered after {} deferred attempt(s)", notReadyAttempts.get());
        }
        notReadyAttempts.set(0);
    }

    /** 2, 4, 8, 16, then capped at 30 — callers grow attempt from 1. */
    static long retryBackoffSeconds(int attempt) {
        long shift = Math.min(Math.max(attempt, 1), 5);
        return Math.min(1L << shift, MAX_RETRY_BACKOFF_SECONDS);
    }

    /**
     * True when the failure chain bottoms out in PostgreSQL
     * {@code undefined_table} — "migrations have not run here yet", as opposed
     * to a real SQL defect. Walked over the cause chain because the JDBC layer
     * wraps {@link SQLException} in Spring's DataAccessException.
     */
    static boolean isSchemaNotReady(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNDEFINED_TABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
