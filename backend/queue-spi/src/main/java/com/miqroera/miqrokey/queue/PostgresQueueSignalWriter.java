package com.miqroera.miqrokey.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * JDBC writer for {@code gateway_queue_signal} (V60). One row per signal, one
 * transaction per row: either the fact lands or it does not, so a retried write
 * can never double-count the same drop window. The inserted {@code id} is a
 * fresh UUID rather than a deterministic one for exactly that reason — the
 * retry is driven by the bus restoring its unwritten delta, and a rolled-back
 * transaction leaves nothing behind for it to collide with.
 *
 * <p>
 * Never runs on the Reactor event loop — the bus's scheduled report task owns
 * it, the same way the flush task owns {@link PostgresUsageEventWriter}.
 * </p>
 */
public final class PostgresQueueSignalWriter implements QueueSignalWriter {

    private static final Logger log = LoggerFactory.getLogger(PostgresQueueSignalWriter.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public PostgresQueueSignalWriter(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void writeSignal(QueueSignal signal) {
        try {
            transactionTemplate.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO gateway_queue_signal (id, tenant_id, occurred_at, dropped, queued_high_water,
                        capacity, saturation_mode)
                    VALUES (:id, :tenantId, :occurredAt, :dropped, :queuedHighWater, :capacity, :saturationMode)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("tenantId", signal.tenantId())
                    .addValue("occurredAt", Timestamp.from(signal.occurredAt())).addValue("dropped", signal.dropped())
                    .addValue("queuedHighWater", signal.queuedHighWater()).addValue("capacity", signal.capacity())
                    .addValue("saturationMode", signal.saturationMode().name())));
        } catch (Exception e) {
            log.warn("Queue saturation signal write failed (dropped={}, occurredAt={})", signal.dropped(),
                    signal.occurredAt(), e);
            throw e;
        }
    }
}
