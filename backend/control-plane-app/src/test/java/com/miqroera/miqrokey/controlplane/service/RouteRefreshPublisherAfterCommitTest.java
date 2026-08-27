package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.support.RouteSnapshotRefreshNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * AFTER_COMMIT semantics of {@link RouteRefreshPublisherAfterCommit}: the
 * notification is published only when the surrounding transaction commits —
 * never on rollback.
 */
@DisplayName("RouteRefreshPublisherAfterCommit")
class RouteRefreshPublisherAfterCommitTest {

    @Test
    @DisplayName("publishes after a committed transaction")
    void publishesAfterCommit() {
        RouteSnapshotRefreshNotifier notifier = mock(RouteSnapshotRefreshNotifier.class);
        RouteRefreshPublisher publisher = new RouteRefreshPublisherAfterCommit(provider(notifier));

        inTransaction(tx -> {
            publisher.publishChanged();
            return null;
        });

        verify(notifier, times(1)).notifyChanged();
    }

    @Test
    @DisplayName("does not publish when the transaction rolls back")
    void doesNotPublishOnRollback() {
        RouteSnapshotRefreshNotifier notifier = mock(RouteSnapshotRefreshNotifier.class);
        RouteRefreshPublisher publisher = new RouteRefreshPublisherAfterCommit(provider(notifier));

        try {
            inTransaction(tx -> {
                publisher.publishChanged();
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // transaction rolled back
        }

        verify(notifier, never()).notifyChanged();
    }

    @Test
    @DisplayName("publishes without a notifier bean (no-op)")
    void noNotifierBeanIsSafe() {
        RouteRefreshPublisher publisher = new RouteRefreshPublisherAfterCommit(provider(null));

        inTransaction(tx -> {
            publisher.publishChanged();
            return null;
        });
        // no exception, nothing published
    }

    private static void inTransaction(java.util.function.Function<TransactionTemplate, Void> body) {
        DataSource ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.execute(status -> {
            body.apply(tx);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RouteSnapshotRefreshNotifier> provider(RouteSnapshotRefreshNotifier notifier) {
        ObjectProvider<RouteSnapshotRefreshNotifier> provider = mock(ObjectProvider.class);
        if (notifier == null) {
            org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
        } else {
            org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(notifier);
        }
        return provider;
    }
}
