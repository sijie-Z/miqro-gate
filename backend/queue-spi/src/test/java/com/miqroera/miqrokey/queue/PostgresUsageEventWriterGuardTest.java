package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writer guard contract (no database): {@code usage_event.model_id} is NOT
 * NULL, and a transparent proxy can forward a body without a usable model
 * field. Such an event is unrepresentable — it must be dropped with a warning
 * instead of failing the whole batch, which the bus re-enqueues forever and
 * thereby stalls every later usage row behind it.
 */
@DisplayName("Postgres usage writer guard (model_id NOT NULL)")
class PostgresUsageEventWriterGuardTest {

    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    @DisplayName("a model-less usage event is dropped; the rest of the batch is written")
    void modelLessEventIsDroppedAndRestWritten() {
        RecordingJdbc jdbc = new RecordingJdbc();
        PostgresUsageEventWriter writer = new PostgresUsageEventWriter(jdbc, inlineTransactions());

        writer.writeBatch(List.of(usage("m-1", "model-a"), usage("m-2", null), usage("m-3", "model-b")), List.of(),
                List.of(), List.of());

        assertThat(jdbc.captured).hasSize(1);
        MapSqlParameterSource[] batch = jdbc.captured.get(0);
        assertThat(batch).hasSize(2);
        assertThat(batch[0].getValue("modelId")).isEqualTo("model-a");
        assertThat(batch[1].getValue("modelId")).isEqualTo("model-b");
    }

    @Test
    @DisplayName("an all-invalid batch performs no JDBC write at all")
    void allInvalidBatchWritesNothing() {
        RecordingJdbc jdbc = new RecordingJdbc();
        PostgresUsageEventWriter writer = new PostgresUsageEventWriter(jdbc, inlineTransactions());

        writer.writeBatch(List.of(usage("m-1", null)), List.of(), List.of(), List.of());

        assertThat(jdbc.captured).isEmpty();
    }

    // -------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------

    /** Runs the callback inline; no transaction manager, no database. */
    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) throws TransactionException {
                return action.doInTransaction(null);
            }
        };
    }

    private static UsageEvent usage(String gatewayRequestId, String modelId) {
        return new UsageEvent(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), modelId, CacheLevel.UPSTREAM,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), 42L, 200, null, true, false, gatewayRequestId,
                CLOCK.instant(), null, null);
    }

    /** Captures batchUpdate calls; the DataSource is never used. */
    private static final class RecordingJdbc extends NamedParameterJdbcTemplate {

        private final List<MapSqlParameterSource[]> captured = new ArrayList<>();

        RecordingJdbc() {
            super(new DriverManagerDataSource());
        }

        @Override
        public int[] batchUpdate(String sql, SqlParameterSource[] batchArgs) {
            captured.add(Arrays.copyOf(batchArgs, batchArgs.length, MapSqlParameterSource[].class));
            return new int[batchArgs.length];
        }
    }
}
