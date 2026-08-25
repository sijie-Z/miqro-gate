package com.miqroera.miqrokey.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-process route refresh listener: subscribes to the control plane's
 * PostgreSQL {@code pg_notify} channel and triggers
 * {@link RouteSnapshotRefresher#refresh()} on every notification, so
 * revocation/rotation/grant changes propagate without waiting for the scheduled
 * refresh.
 *
 * <p>
 * Runs on a dedicated {@code DriverManager} connection (never the Hikari pool —
 * {@code LISTEN} pins the connection for the process lifetime) in a daemon
 * thread; it is a background consumer and never touches the Reactor event loop.
 * The scheduled refresh stays active as a fallback: missed notifications
 * self-heal within one refresh interval.
 * </p>
 *
 * <p>
 * Connection failures are logged and retried with capped backoff; the listener
 * only stops when {@link #close()} is called. {@link #close()} interrupts the
 * blocked {@code getNotifications()} call, closes the connection and joins the
 * thread.
 * </p>
 */
public final class RouteSnapshotRefreshListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RouteSnapshotRefreshListener.class);

    /** Blocking timeout of the JDBC notification poll. */
    private static final int POLL_TIMEOUT_MS = 2000;

    /** Initial reconnect delay after a lost connection. */
    private static final long INITIAL_BACKOFF_MS = 500;
    /** Ceiling on the exponential reconnect backoff. */
    private static final long MAX_BACKOFF_MS = 30_000;

    /**
     * Creates the dedicated LISTEN connection; production uses
     * {@code DriverManager}.
     */
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    private final String channel;
    private final ConnectionFactory connectionFactory;
    private final RouteSnapshotRefresher refresher;
    private final AtomicBoolean running = new AtomicBoolean(true);
    /**
     * Package-private for the same-package unit test asserting thread lifecycle.
     */
    final Thread listenerThread;

    public RouteSnapshotRefreshListener(String channel, ConnectionFactory connectionFactory,
            RouteSnapshotRefresher refresher) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.listenerThread = new Thread(this::run, "route-snapshot-notify-listener");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    private void run() {
        long backoff = INITIAL_BACKOFF_MS;
        while (running.get()) {
            try (Connection connection = connectionFactory.open()) {
                backoff = INITIAL_BACKOFF_MS;
                log.info("Route snapshot refresh listener connected on channel '{}'", channel);
                listenLoop(connection);
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.warn("Route snapshot refresh listener connection lost (retrying in {}ms): {}", backoff,
                        e.getMessage());
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
        log.info("Route snapshot refresh listener stopped");
    }

    /**
     * Issues {@code LISTEN} and blocks on {@code getNotifications()}. Every
     * notification triggers a refresh (version-bumped and installed into the
     * holder) on this thread.
     */
    private void listenLoop(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + channel);
        }
        while (running.get()) {
            org.postgresql.PGNotification[] notifications = connection.unwrap(org.postgresql.PGConnection.class)
                    .getNotifications(POLL_TIMEOUT_MS);
            if (notifications != null) {
                for (org.postgresql.PGNotification notification : notifications) {
                    if (channel.equals(notification.getName())) {
                        log.debug("Route refresh notification received on channel '{}'", channel);
                        refresher.refresh();
                    }
                }
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops the listener: interrupts the blocked JDBC poll, closes the connection
     * and joins the daemon thread. Idempotent.
     */
    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        listenerThread.interrupt();
        try {
            listenerThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
