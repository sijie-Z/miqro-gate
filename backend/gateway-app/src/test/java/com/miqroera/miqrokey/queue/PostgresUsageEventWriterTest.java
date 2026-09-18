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
    private static final String CLIENT_IP = "203.0.113.7";

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

        // #605: the calling-party address round-trips onto the usage fact.
        String clientIp = jdbc.queryForObject("SELECT client_ip FROM usage_event WHERE provider_request_id = :prid",
                new MapSqlParameterSource().addValue("prid", providerRequestId.toString()), String.class);
        assertThat(clientIp).isEqualTo(CLIENT_IP);

        Integer hitRows = jdbc.queryForObject("""
                SELECT count(*) FROM cache_hit_event
                WHERE tenant_id = :tenantId AND cache_key = :cacheKey
                """, new MapSqlParameterSource().addValue("tenantId", TENANT_ID).addValue("cacheKey", hit.cacheKey()),
                Integer.class);
        assertThat(hitRows).isEqualTo(1);
    }

    // -------------------------------------------------------------------
    // CAA evidence rows (Spec v1.1 §7.2, #629)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("evidence rows record the observed selector — and exist only where one was used (#629)")
    void contextEvidenceRecordsObservedSelector() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID claimedProjectId = UUID.randomUUID();
        // 1) Agent-declared project id: the header claim is the evidence.
        UsageEvent claimed = caaEvent("gw-evi-header-" + suffix, new UsageEvent.ContextAttribution("sess-1",
                UUID.randomUUID(), claimedProjectId, "RESOLVED_HEADER", "git_remote", "HIGH", "miqro-web"));
        // 2) legacy suffix selector: the tag in the key is the evidence, no declared
        // confidence.
        UsageEvent bySuffix = caaEvent("gw-evi-suffix-" + suffix,
                new UsageEvent.ContextAttribution(null, null, null, "RESOLVED_SUFFIX", null, null, "miqro-web"));
        // 3) sole binding / 4) unattributed policy: resolved without any external
        // signal.
        UsageEvent sole = caaEvent("gw-evi-sole-" + suffix,
                new UsageEvent.ContextAttribution(null, null, null, "SOLE_BINDING", null, "MEDIUM", "miqro-web"));
        UsageEvent policy = caaEvent("gw-evi-policy-" + suffix,
                new UsageEvent.ContextAttribution(null, null, null, "POLICY_ROUTED", null, "NONE", null));

        writer.writeBatch(List.of(claimed, bySuffix, sole, policy), List.of(), List.of(), List.of());

        // All four requests are recorded as usage — evidence absence below is a
        // decision, not a dropped batch.
        for (UsageEvent e : List.of(claimed, bySuffix, sole, policy)) {
            assertThat(usageRows(e.gatewayRequestId())).isEqualTo(1);
        }

        var claimedRows = evidenceRows(claimed.id());
        assertThat(claimedRows).hasSize(1);
        assertThat(claimedRows.get(0)).containsEntry("source", "header")
                .containsEntry("value", claimedProjectId.toString()).containsEntry("confidence", "HIGH")
                .containsEntry("scope", "turn").containsEntry("request_id", claimed.gatewayRequestId())
                .containsEntry("tenant_id", TENANT_ID);

        var suffixRows = evidenceRows(bySuffix.id());
        assertThat(suffixRows).hasSize(1);
        assertThat(suffixRows.get(0)).containsEntry("source", "suffix").containsEntry("value", "miqro-web")
                .containsEntry("confidence", "NONE").containsEntry("scope", "turn");

        assertThat(evidenceRows(sole.id())).isEmpty();
        assertThat(evidenceRows(policy.id())).isEmpty();

        // A retried flush (bus replay) neither duplicates nor rewrites evidence.
        writer.writeBatch(List.of(claimed, bySuffix, sole, policy), List.of(), List.of(), List.of());
        assertThat(evidenceRows(claimed.id())).hasSize(1);
        assertThat(evidenceRows(bySuffix.id())).hasSize(1);
    }

    @Test
    @DisplayName("an unrepresentable usage event leaves no orphan evidence row (#629)")
    void droppedUsageEventLeavesNoEvidenceRow() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UsageEvent dropped = new UsageEvent(UUID.randomUUID(), TENANT_ID, null, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, CacheLevel.UPSTREAM,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), 42L, 200, null, true, false,
                "gw-evi-drop-" + suffix, CLOCK.instant(), CLIENT_IP, new UsageEvent.ContextAttribution(null, null,
                        UUID.randomUUID(), "RESOLVED_HEADER", null, "LOW", "miqro-web"));
        UsageEvent healthy = caaEvent("gw-evi-mate-" + suffix,
                new UsageEvent.ContextAttribution(null, null, null, "RESOLVED_SUFFIX", null, null, "miqro-web"));

        writer.writeBatch(List.of(dropped, healthy), List.of(), List.of(), List.of());

        // usage_event.model_id is NOT NULL: the unrepresentable event is dropped,
        // and its evidence must not survive it.
        assertThat(usageRows(dropped.gatewayRequestId())).isZero();
        assertThat(evidenceRows(dropped.id())).isEmpty();
        // ... while the same batch still lands the healthy event and its evidence.
        assertThat(usageRows(healthy.gatewayRequestId())).isEqualTo(1);
        assertThat(evidenceRows(healthy.id())).hasSize(1);
    }

    // -------------------------------------------------------------------
    // Fixtures + helpers
    // -------------------------------------------------------------------

    /**
     * CAA event with an upstream request id: the gateway only writes attribution
     * for requests it actually sent, and it makes a replayed flush a real no-op on
     * both tables (usage dedupes on {@code provider_request_id}, evidence on
     * {@code id}).
     */
    private static UsageEvent caaEvent(String gatewayRequestId, UsageEvent.ContextAttribution attribution) {
        return new UsageEvent(UUID.randomUUID(), TENANT_ID, UUID.randomUUID().toString(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "model-x", CacheLevel.UPSTREAM,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), 42L, 200, null, true, false, gatewayRequestId,
                CLOCK.instant(), CLIENT_IP, attribution);
    }

    private static Integer usageRows(String gatewayRequestId) {
        return jdbc.queryForObject("SELECT count(*) FROM usage_event WHERE gateway_request_id = :gid",
                new MapSqlParameterSource().addValue("gid", gatewayRequestId), Integer.class);
    }

    private static List<java.util.Map<String, Object>> evidenceRows(UUID usageId) {
        return jdbc.queryForList("""
                SELECT source, value, confidence, scope, request_id, tenant_id, observed_at
                FROM request_context_evidence WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", usageId));
    }

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
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), status == RequestStatus.SUCCEEDED && false, 0);
    }

    @Test
    @DisplayName("CAA context columns persist verbatim with the usage row (#633)")
    void contextColumnsPersist() {
        Instant occurredAt = CLOCK.instant();
        String gatewayRequestId = "gw-caa-" + UUID.randomUUID().toString().substring(0, 8);
        UUID activityId = UUID.randomUUID();
        UUID claimedProjectId = UUID.randomUUID();
        UsageEvent event = new UsageEvent(UUID.randomUUID(), TENANT_ID, null, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "model-x", CacheLevel.UPSTREAM,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), 42L, 200, null, true, false, gatewayRequestId,
                occurredAt, CLIENT_IP, new UsageEvent.ContextAttribution("sess-1", activityId, claimedProjectId,
                        "RESOLVED_HEADER", "tool_path", "HIGH", "miqro-web"));

        writer.writeBatch(List.of(event), List.of(), List.of(), List.of());

        var rows = jdbc.queryForList("""
                SELECT session_id, activity_id, claimed_project_id, resolution_status, claim_source, claim_confidence
                FROM usage_event WHERE gateway_request_id = :gid
                """, new MapSqlParameterSource().addValue("gid", gatewayRequestId));
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row).containsEntry("session_id", "sess-1");
        assertThat(row).containsEntry("activity_id", activityId);
        assertThat(row).containsEntry("claimed_project_id", claimedProjectId);
        assertThat(row).containsEntry("resolution_status", "RESOLVED_HEADER");
        assertThat(row).containsEntry("claim_source", "tool_path");
        assertThat(row).containsEntry("claim_confidence", "HIGH");
    }

    private static UsageEvent usageEvent(UUID providerRequestId) {
        return new UsageEvent(UUID.randomUUID(), TENANT_ID, providerRequestId.toString(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "model-x", CacheLevel.UPSTREAM,
                new TokenBucket(10L, 5L, 0L, 0L, 10L, 5L, 15L, 0L), 42L, 200, null, true, false, "gw-usage",
                CLOCK.instant(), CLIENT_IP, null);
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
