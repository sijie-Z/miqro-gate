package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStartedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStatus;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostgresUsageEventWriter} guarded-upsert contract against a real
 * PostgreSQL (Testcontainers): a lifecycle record finalizes exactly once, a
 * retried flush never double-finalizes or rewrites a finalized record, and a
 * completion never depends on its start row having been persisted first.
 */
@Tag("integration")
@DisplayName("Postgres usage event writer (guarded upsert)")
class PostgresUsageEventWriterTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName
                .parse("postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName("miqrokey_test").withUsername("miqrokey_test")
                .withPassword("miqrokey_test");
        POSTGRES.start();
    }

    private static NamedParameterJdbcTemplate jdbc;
    private static PostgresUsageEventWriter writer;

    @BeforeAll
    static void setUpDatabase() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        writer = new PostgresUsageEventWriter(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        jdbc.update("""
                INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                VALUES (:id, 'writer-test', 'Writer Test', 'ACTIVE', 0, now(), now())
                """, new MapSqlParameterSource().addValue("id", TENANT_ID));
    }

    // -------------------------------------------------------------------
    // Lifecycle: start -> completion
    // -------------------------------------------------------------------

    @Test
    @DisplayName("start then completion produces one finalized SUCCEEDED record")
    void startThenCompletionFinalizes() {
        UUID requestId = UUID.randomUUID();
        String gatewayRequestId = "gw-" + requestId.toString().substring(0, 8);
        Instant startedAt = CLOCK.instant();

        writer.writeBatch(List.of(), List.of(), List.of(startEvent(startedAt, gatewayRequestId)), List.of());

        var startRow = fetchRow(startedAt, gatewayRequestId);
        assertThat(startRow).containsEntry("request_status", "IN_FLIGHT");
        assertThat(startRow).containsEntry("finalized_at", null);
        assertThat(startRow).containsEntry("streaming", false);

        writer.writeBatch(List.of(), List.of(), List.of(),
                List.of(completeEvent(startedAt, gatewayRequestId, RequestStatus.SUCCEEDED, 200, 12L, 30L)));

        var row = fetchRow(startedAt, gatewayRequestId);
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("http_status", 200);
        assertThat(row).containsEntry("input_tokens", 10L);
        assertThat(row).containsEntry("output_tokens", 5L);
        assertThat(row).containsEntry("total_tokens", 15L);
        assertThat(row).containsEntry("usage_missing", false);
        assertThat(row).containsEntry("wire_protocol", "ANTHROPIC_MESSAGES");
        assertThat(row).containsEntry("time_to_first_byte_ms", 30L);
        assertThat(row).containsEntry("duration_ms", 12L);
        assertThat(row.get("first_byte_at")).isNotNull();
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("finalized_at")).isNotNull();
        assertThat(row).containsEntry("tenant_id", TENANT_ID);
        assertThat(row.get("user_id")).isNotNull();
        assertThat(row.get("virtual_key_id")).isNotNull();
        assertThat(row.get("provider_id")).isNotNull();
        assertThat(row.get("provider_product_id")).isNotNull();
        assertThat(row.get("credential_id")).isNotNull();
    }

    @Test
    @DisplayName("a retried completion (failed-flush replay) never rewrites the finalized record")
    void retriedCompletionDoesNotRewriteFinalizedRow() {
        UUID requestId = UUID.randomUUID();
        String gatewayRequestId = "gw-retry-" + requestId.toString().substring(0, 6);
        Instant startedAt = CLOCK.instant();

        writer.writeBatch(List.of(), List.of(), List.of(startEvent(startedAt, gatewayRequestId)), List.of());
        writer.writeBatch(List.of(), List.of(), List.of(),
                List.of(completeEvent(startedAt, gatewayRequestId, RequestStatus.SUCCEEDED, 200, 12L, 30L)));
        // Retried flush: same completion, different (later) observations.
        writer.writeBatch(List.of(), List.of(), List.of(),
                List.of(completeEvent(startedAt, gatewayRequestId, RequestStatus.SUCCEEDED, 200, 99L, 30L)));

        var row = fetchRow(startedAt, gatewayRequestId);
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        // First finalize wins; the replay is a no-op.
        assertThat(row).containsEntry("duration_ms", 12L);
        assertThat(row).containsEntry("http_status", 200);
    }

    @Test
    @DisplayName("a completion without its start row inserts a standalone final record")
    void completionWithoutStartInsertsStandaloneRow() {
        UUID requestId = UUID.randomUUID();
        String gatewayRequestId = "gw-orphan-" + requestId.toString().substring(0, 6);
        Instant startedAt = CLOCK.instant();

        writer.writeBatch(List.of(), List.of(), List.of(),
                List.of(completeEvent(startedAt, gatewayRequestId, RequestStatus.UPSTREAM_REJECTED, 429, 5L, 1L)));

        var row = fetchRow(startedAt, gatewayRequestId);
        assertThat(row).containsEntry("request_status", "UPSTREAM_REJECTED");
        assertThat(row).containsEntry("http_status", 429);
        assertThat(row).containsEntry("client_cancelled", false);
        assertThat(row.get("finalized_at")).isNotNull();
    }

    @Test
    @DisplayName("a start arriving after the finalize (re-ordered re-enqueue) is a no-op")
    void startAfterFinalizeIsNoop() {
        UUID requestId = UUID.randomUUID();
        String gatewayRequestId = "gw-reorder-" + requestId.toString().substring(0, 6);
        Instant startedAt = CLOCK.instant();

        writer.writeBatch(List.of(), List.of(), List.of(),
                List.of(completeEvent(startedAt, gatewayRequestId, RequestStatus.SUCCEEDED, 200, 10L, 5L)));
        writer.writeBatch(List.of(), List.of(), List.of(startEvent(startedAt, gatewayRequestId)), List.of());

        var row = fetchRow(startedAt, gatewayRequestId);
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("http_status", 200);
    }

    @Test
    @DisplayName("a retried start is a no-op: exactly one IN_FLIGHT row")
    void retriedStartIsNoop() {
        UUID requestId = UUID.randomUUID();
        String gatewayRequestId = "gw-startidem-" + requestId.toString().substring(0, 5);
        Instant startedAt = CLOCK.instant();

        writer.writeBatch(List.of(), List.of(), List.of(startEvent(startedAt, gatewayRequestId)), List.of());
        writer.writeBatch(List.of(), List.of(), List.of(startEvent(startedAt, gatewayRequestId)), List.of());

        var row = fetchRow(startedAt, gatewayRequestId);
        assertThat(row).containsEntry("request_status", "IN_FLIGHT");
        assertThat(row.get("finalized_at")).isNull();
    }

    // -------------------------------------------------------------------
    // Usage + hit rows: idempotent writes
    // -------------------------------------------------------------------

    @Test
    @DisplayName("duplicate usage and hit events (retried flush) never double-count")
    void duplicateUsageAndHitsAreIdempotent() {
        UUID providerRequestId = UUID.randomUUID();
        UsageEvent usage = usageEvent(providerRequestId);
        CacheHitEvent hit = hitEvent("cache-key-a", CLOCK.instant());

        writer.writeBatch(List.of(usage, usage), List.of(hit, hit), List.of(), List.of());

        Integer usageRows = jdbc.queryForObject("SELECT count(*) FROM usage_event WHERE provider_request_id = :prid",
                new MapSqlParameterSource().addValue("prid", providerRequestId.toString()), Integer.class);
        assertThat(usageRows).isEqualTo(1);

        Integer hitRows = jdbc.queryForObject("""
                SELECT count(*) FROM cache_hit_event
                WHERE tenant_id = :tenantId AND cache_key = :cacheKey
                """, new MapSqlParameterSource().addValue("tenantId", TENANT_ID).addValue("cacheKey", hit.cacheKey()),
                Integer.class);
        assertThat(hitRows).isEqualTo(1);
    }

    // -------------------------------------------------------------------
    // Fixtures + helpers
    // -------------------------------------------------------------------

    private static RequestStartedEvent startEvent(Instant startedAt, String gatewayRequestId) {
        return new RequestStartedEvent(UUID.randomUUID(), startedAt, gatewayRequestId, TENANT_ID, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ANTHROPIC_MESSAGES", "claude-sonnet-5-20250915", false);
    }

    private static RequestCompletedEvent completeEvent(Instant startedAt, String gatewayRequestId, RequestStatus status,
            int httpStatus, long durationMs, long ttfbMs) {
        return new RequestCompletedEvent(UUID.randomUUID(), startedAt, gatewayRequestId, TENANT_ID, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ANTHROPIC_MESSAGES", "claude-sonnet-5-20250915", false, "upstream-req-1", startedAt.plusMillis(ttfbMs),
                startedAt.plusMillis(durationMs), durationMs, ttfbMs, httpStatus, status, false, false,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), status == RequestStatus.SUCCEEDED && false);
    }

    private static UsageEvent usageEvent(UUID providerRequestId) {
        return new UsageEvent(UUID.randomUUID(), TENANT_ID, providerRequestId.toString(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "model-x", CacheLevel.UPSTREAM,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), 42L, 200, null, true, false, "gw-usage",
                CLOCK.instant());
    }

    private static CacheHitEvent hitEvent(String cacheKey, Instant occurredAt) {
        return new CacheHitEvent(UUID.randomUUID(), TENANT_ID,
                cacheKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), CacheLevel.L1_HIT, "gw-hit", occurredAt);
    }

    private java.util.Map<String, Object> fetchRow(Instant startedAt, String gatewayRequestId) {
        var rows = jdbc.queryForList("""
                SELECT * FROM request_usage_records
                WHERE gateway_request_id = :gid AND started_at = :startedAt
                """, new MapSqlParameterSource().addValue("gid", gatewayRequestId).addValue("startedAt",
                java.sql.Timestamp.from(startedAt)));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
