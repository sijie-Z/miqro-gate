package com.miqroera.miqrokey.controlplane.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a PostgreSQL {@code pg_notify} on the shared route-refresh channel
 * ({@code miqrokey_route_refresh}) so the gateway's
 * {@code RouteSnapshotRefreshListener} reloads its snapshot without waiting for
 * the scheduled refresh.
 *
 * <p>
 * Called AFTER_COMMIT by the mutating services (see
 * {@link com.miqroera.miqrokey.controlplane.service.RouteRefreshPublisher}); a
 * failure only logs — the committed data change is never rolled back, and the
 * gateway's 30s scheduled refresh remains the safety net.
 * </p>
 */
@Component
public class RouteSnapshotRefreshNotifier {

    /** Contractual channel name shared with the gateway listener. */
    public static final String CHANNEL = "miqrokey_route_refresh";

    private static final Logger log = LoggerFactory.getLogger(RouteSnapshotRefreshNotifier.class);

    private final NamedParameterJdbcTemplate jdbc;

    public RouteSnapshotRefreshNotifier(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Sends the notification; failures are logged and swallowed. */
    public void notifyChanged() {
        try {
            // Plain Statement.execute (simple query protocol): pg_notify is a
            // void-returning SELECT, and the pgjdbc executeUpdate path throws
            // "Unexpected result returned" after the notification fires.
            // The channel is a compile-time constant — no injection surface.
            jdbc.getJdbcTemplate().execute("SELECT pg_notify('" + CHANNEL + "', '')");
            log.debug("Published route refresh notification on channel '{}'", CHANNEL);
        } catch (Exception e) {
            // Log-and-continue: the scheduled snapshot refresh is the safety net.
            log.warn("Failed to publish route refresh notification on channel '{}'", CHANNEL, e);
        }
    }
}
