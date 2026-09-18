package com.miqroera.miqrokey.route;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #846: a fresh deployment let the gateway boot before the control plane's
 * Flyway run, so the first refresh failed with PostgreSQL {@code undefined_table}
 * — logged as ERROR and retried only on the next 30s tick. These tests pin the
 * replacement behavior: that failure is classified as "schema not ready",
 * deferred with a WARN, retried on a backed-off short ticker, and every other
 * failure keeps the historical ERROR path.
 */
class RouteSnapshotRefresherTest {

    /** Clock the tests move by hand; the refresher only reads {@code instant()}. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-18T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private final JdbcRouteSnapshotLoader loader = mock(JdbcRouteSnapshotLoader.class);
    private final RouteSnapshotHolder holder = mock(RouteSnapshotHolder.class);
    private final MutableClock clock = new MutableClock();
    private final RouteSnapshotRefresher refresher = new RouteSnapshotRefresher(loader, holder, clock);

    private static BadSqlGrammarException undefinedTable() {
        return new BadSqlGrammarException("load", "select 1 from virtual_keys",
                new SQLException("ERROR: relation \"virtual_keys\" does not exist", "42P01"));
    }

    @Test
    @DisplayName("undefined_table is 'schema not ready', everything else is not")
    void classifiesUndefinedTable() {
        assertThat(RouteSnapshotRefresher.isSchemaNotReady(undefinedTable())).isTrue();
        // Wrapped one extra layer (JdbcTemplate -> DAO -> refresher) still counts.
        assertThat(RouteSnapshotRefresher
                .isSchemaNotReady(new IllegalStateException("load failed", undefinedTable()))).isTrue();
        assertThat(RouteSnapshotRefresher.isSchemaNotReady(new SQLException("down", "08006"))).isFalse();
        assertThat(RouteSnapshotRefresher.isSchemaNotReady(new RuntimeException("boom"))).isFalse();
        assertThat(RouteSnapshotRefresher.isSchemaNotReady(undefinedTable().getCause().getCause())).isFalse();
    }

    @Test
    @DisplayName("retry backoff doubles then caps at the refresh interval")
    void backoffSchedule() {
        assertThat(RouteSnapshotRefresher.retryBackoffSeconds(1)).isEqualTo(2);
        assertThat(RouteSnapshotRefresher.retryBackoffSeconds(2)).isEqualTo(4);
        assertThat(RouteSnapshotRefresher.retryBackoffSeconds(3)).isEqualTo(8);
        assertThat(RouteSnapshotRefresher.retryBackoffSeconds(4)).isEqualTo(16);
        assertThat(RouteSnapshotRefresher.retryBackoffSeconds(5)).isEqualTo(30);
        assertThat(RouteSnapshotRefresher.retryBackoffSeconds(50)).isEqualTo(30);
    }

    @Test
    @DisplayName("not-ready failure defers without installing, then the ticker recovers")
    void deferredThenRecovers() {
        RouteSnapshot snapshot = mock(RouteSnapshot.class);
        when(loader.load(anyLong(), any())).thenThrow(undefinedTable()).thenReturn(snapshot);

        refresher.refresh();
        verify(holder, never()).install(any());

        // The ticker must respect the 2s backoff gate...
        clock.advanceSeconds(1);
        refresher.retryWhenSchemaNotReady();
        verify(loader, times(1)).load(anyLong(), any());

        // ...and recover once it fires.
        clock.advanceSeconds(1);
        refresher.retryWhenSchemaNotReady();
        verify(loader, times(2)).load(anyLong(), any());
        verify(holder, times(1)).install(snapshot);

        // Cured: further ticker beats are a no-op until the next failure.
        clock.advanceSeconds(60);
        refresher.retryWhenSchemaNotReady();
        verify(loader, times(2)).load(anyLong(), any());
    }

    @Test
    @DisplayName("consecutive not-ready failures back off further each time")
    void backoffGrows() {
        when(loader.load(anyLong(), any())).thenThrow(undefinedTable());

        refresher.refresh(); // attempt 1 -> next in 2s
        clock.advanceSeconds(2);
        refresher.retryWhenSchemaNotReady(); // attempt 2 -> next in 4s
        verify(loader, times(2)).load(anyLong(), any());

        clock.advanceSeconds(3); // 3s after the 4s gate: too early
        refresher.retryWhenSchemaNotReady();
        verify(loader, times(2)).load(anyLong(), any());

        clock.advanceSeconds(1);
        refresher.retryWhenSchemaNotReady();
        verify(loader, times(3)).load(anyLong(), any());
    }

    @Test
    @DisplayName("a non-not-ready failure stays an error and schedules no fast retry")
    void realErrorsDoNotScheduleRetry() {
        when(loader.load(anyLong(), any())).thenThrow(new IllegalStateException("connection refused"));

        refresher.refresh();
        verify(holder, never()).install(any());

        clock.advanceSeconds(120);
        refresher.retryWhenSchemaNotReady();
        verify(loader, times(1)).load(anyLong(), any());
    }
}
