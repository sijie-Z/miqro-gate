package com.miqroera.miqrokey.route;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RouteSnapshotRefreshListener} using Mockito fakes for
 * the JDBC objects: a notification on the channel must trigger
 * {@code RouteSnapshotRefresher#refresh()}, and
 * {@link RouteSnapshotRefreshListener#close()} must release the connection and
 * stop the daemon thread.
 */
@DisplayName("RouteSnapshotRefreshListener")
class SnapshotRefreshListenerTest {

    private static final String CHANNEL = "miqrokey_route_refresh";

    @Test
    @Timeout(10)
    @DisplayName("a notification on the channel triggers a snapshot refresh")
    void notificationTriggersRefresh() throws Exception {
        Harness h = new Harness();
        RouteSnapshotRefreshListener listener = new RouteSnapshotRefreshListener(CHANNEL, () -> h.connection,
                h.refresher());
        try {
            h.awaitListen();
            h.enqueueNotification(new FakeNotification(CHANNEL));
            assertThat(h.awaitRefresh()).isTrue();
            assertThat(h.refreshCount.get()).isEqualTo(1);
        } finally {
            listener.close();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("close stops the thread and closes the dedicated connection")
    void closeStopsListener() throws Exception {
        Harness h = new Harness();
        RouteSnapshotRefreshListener listener = new RouteSnapshotRefreshListener(CHANNEL, () -> h.connection,
                h.refresher());
        try {
            h.awaitListen();
        } finally {
            listener.close();
        }
        verify(h.connection, timeout(5_000)).close();
        verify(h.statement, timeout(5_000)).close();
        assertThat(listener.listenerThread.isAlive()).isFalse();
    }

    @Test
    @Timeout(10)
    @DisplayName("a failed connection is retried with backoff until it succeeds")
    void reconnectsAfterFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Harness h = new Harness();
        RouteSnapshotRefreshListener listener = new RouteSnapshotRefreshListener(CHANNEL, () -> {
            if (attempts.getAndIncrement() == 0) {
                throw new SQLException("simulated connection failure");
            }
            return h.connection;
        }, h.refresher());
        try {
            // The second attempt must LISTEN — proof the reconnect happened.
            h.awaitListen();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        } finally {
            listener.close();
        }
    }

    /**
     * Mockito-backed JDBC fake: a queue of notifications feeds
     * {@code getNotifications()}, the LISTEN statement is recorded, and refresh
     * counts are tracked on a mocked snapshot loader.
     */
    private static final class Harness {
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class);
        final PGConnection pg = mock(PGConnection.class);
        final LinkedBlockingQueue<PGNotification> notifications = new LinkedBlockingQueue<>();
        final AtomicInteger refreshCount = new AtomicInteger();
        private final CountDownLatch listened = new CountDownLatch(1);
        private final CountDownLatch refreshed = new CountDownLatch(1);
        private volatile String listenChannel;

        Harness() throws SQLException {
            when(connection.createStatement()).thenReturn(statement);
            doAnswer(invocation -> {
                listenChannel = invocation.getArgument(0);
                listened.countDown();
                return true;
            }).when(statement).execute(anyString());
            when(connection.unwrap(eq(PGConnection.class))).thenReturn(pg);
            doAnswer(invocation -> {
                int timeoutMillis = invocation.getArgument(0);
                PGNotification notification = notifications.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                return notification == null ? null : new PGNotification[]{notification};
            }).when(pg).getNotifications(anyInt());
        }

        RouteSnapshotRefresher refresher() {
            JdbcRouteSnapshotLoader loader = mock(JdbcRouteSnapshotLoader.class);
            doAnswer(invocation -> {
                refreshCount.incrementAndGet();
                refreshed.countDown();
                return RouteSnapshot.empty(1, Instant.EPOCH);
            }).when(loader).load(anyLong(), any());
            return new RouteSnapshotRefresher(loader, new RouteSnapshotHolder(Clock.systemUTC()), Clock.systemUTC());
        }

        void enqueueNotification(PGNotification notification) {
            notifications.offer(notification);
        }

        String awaitListen() throws InterruptedException {
            assertThat(listened.await(5, TimeUnit.SECONDS)).as("LISTEN must be issued").isTrue();
            return listenChannel;
        }

        boolean awaitRefresh() throws InterruptedException {
            return refreshed.await(5, TimeUnit.SECONDS);
        }
    }

    private record FakeNotification(String name) implements PGNotification {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getPID() {
            return 0;
        }

        @Override
        public String getParameter() {
            return "";
        }
    }
}
