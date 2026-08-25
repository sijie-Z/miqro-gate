package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.support.RouteSnapshotRefreshNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link RouteRefreshPublisher} backed by {@link RouteSnapshotRefreshNotifier}
 * (PostgreSQL {@code pg_notify}). The notification is sent AFTER the enclosing
 * transaction commits, so the gateway never refreshes against uncommitted rows.
 */
@Component
public class RouteRefreshPublisherAfterCommit implements RouteRefreshPublisher {

    private final ObjectProvider<RouteSnapshotRefreshNotifier> notifier;

    public RouteRefreshPublisherAfterCommit(ObjectProvider<RouteSnapshotRefreshNotifier> notifier) {
        this.notifier = notifier;
    }

    @Override
    public void publishChanged() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No active transaction (should not happen: callers are
            // @Transactional); nothing to commit, nothing to publish.
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                RouteSnapshotRefreshNotifier n = notifier.getIfAvailable();
                if (n != null) {
                    n.notifyChanged();
                }
            }
        });
    }
}
