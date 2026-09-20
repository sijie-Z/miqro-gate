package com.miqroera.miqrokey.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository.GroupBy;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository.UsageFilter;
import com.miqroera.miqrokey.persistence.AbstractPostgresTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Day and month buckets are a property of the data, not of the connection that
 * reads them (#1050).
 *
 * <p>
 * {@code CAST(occurred_at AS DATE)} and
 * {@code date_trunc('month', occurred_at)} resolve through the PostgreSQL
 * session's {@code TimeZone}. Changing that setting — a server reconfiguration,
 * a different image, {@code PGTZ} — moved every tenant's daily and monthly
 * series by one bucket at the day's edge: no code change, no warning, no audit
 * trail. The hourly path already states the rule ("epoch arithmetic is
 * session-timezone independent"); these tests pin it for the day and month
 * buckets.
 * </p>
 *
 * <p>
 * The first pair of assertions is the one with teeth on every host: it runs the
 * <em>production</em> bucket expression (via {@link UsageStatsRepositoryImpl})
 * on two connections whose session timezones differ, and demands the same
 * buckets. The last assertion pins the wiring — the repository's own
 * aggregation must return those same UTC buckets. That last one is a tautology
 * on a host whose sessions default to UTC, which is exactly why it is not the
 * only assertion here.
 * </p>
 */
@DisplayName("Usage day/month buckets ignore the session timezone (#1050)")
class UsageBucketTimezoneTest extends AbstractPostgresTest {

    /** Seed tenant from V1. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 2026-09-03 in UTC, 2026-09-04 00:30 at +08 — the row that used to move. */
    private static final Instant EVENING_UTC = Instant.parse("2026-09-03T16:30:00Z");
    /** 2026-09-04 on both sides. */
    private static final Instant MORNING_UTC = Instant.parse("2026-09-04T02:00:00Z");
    /** 2026-08 in UTC, 2026-09-01 00:30 at +08 — the month edge. */
    private static final Instant MONTH_EDGE = Instant.parse("2026-08-31T16:30:00Z");

    /** Two sessions that disagree about both the day and the month of a row. */
    private static final String UTC = "UTC";
    private static final String PLUS_EIGHT = "Asia/Shanghai";

    private static final Instant WINDOW_FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2026-09-30T00:00:00Z");

    @Autowired
    private UsageStatsRepository usageStats;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    /** Own key per test: the shared container holds every other class' rows too. */
    private UUID keyId;

    @BeforeEach
    void seed() {
        keyId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                VALUES (:id, :tenantId, :code, 'Timezone probe', 'ACTIVE', NULL, 0)
                """, new MapSqlParameterSource("id", projectId).addValue("tenantId", TENANT_ID).addValue("code",
                "TZ-" + keyId.toString().substring(0, 8)));

        insertEvent(projectId, productId, credentialId, EVENING_UTC);
        insertEvent(projectId, productId, credentialId, MORNING_UTC);
        insertEvent(projectId, productId, credentialId, MONTH_EDGE);
    }

    @Test
    @DisplayName("the day bucket expression yields the same buckets under UTC and +08")
    void dayBucketExpressionIsSessionIndependent() {
        List<String> utc = bucketsOnPinnedSession(UTC, UsageStatsRepositoryImpl.utcDayBucket("occurred_at"));

        assertThat(utc).containsExactly("2026-08-31=1", "2026-09-03=1", "2026-09-04=1");
        assertThat(bucketsOnPinnedSession(PLUS_EIGHT, UsageStatsRepositoryImpl.utcDayBucket("occurred_at")))
                .isEqualTo(utc);
    }

    @Test
    @DisplayName("the month bucket expression yields the same buckets under UTC and +08")
    void monthBucketExpressionIsSessionIndependent() {
        List<String> utc = bucketsOnPinnedSession(UTC, UsageStatsRepositoryImpl.utcMonthBucket("occurred_at"));

        assertThat(utc).containsExactly("2026-08=1", "2026-09=2");
        assertThat(bucketsOnPinnedSession(PLUS_EIGHT, UsageStatsRepositoryImpl.utcMonthBucket("occurred_at")))
                .isEqualTo(utc);
    }

    @Test
    @DisplayName("the repository's own day aggregation returns those UTC buckets")
    void repositoryDayAggregationIsUtcBucketed() {
        assertThat(repositoryBuckets(GroupBy.DAY))
                .isEqualTo(bucketsOnPinnedSession(UTC, UsageStatsRepositoryImpl.utcDayBucket("occurred_at")));
    }

    @Test
    @DisplayName("the repository's own month aggregation returns those UTC buckets")
    void repositoryMonthAggregationIsUtcBucketed() {
        assertThat(repositoryBuckets(GroupBy.MONTH))
                .isEqualTo(bucketsOnPinnedSession(UTC, UsageStatsRepositoryImpl.utcMonthBucket("occurred_at")));
    }

    private List<String> repositoryBuckets(GroupBy groupBy) {
        UsageFilter filter = new UsageFilter(TENANT_ID, Set.of(keyId), WINDOW_FROM, WINDOW_TO);
        return usageStats.aggregateUsage(groupBy, filter).stream().map(row -> row.groupKey() + "=" + row.requests())
                .sorted().toList();
    }

    /**
     * Runs {@code bucketExpression} over this test's rows on a connection pinned to
     * {@code sessionTimeZone}. The pin is transactional ({@code SET LOCAL} plus
     * rollback) so the pooled connection is handed back untouched.
     */
    private List<String> bucketsOnPinnedSession(String sessionTimeZone, String bucketExpression) {
        String sql = "SELECT " + bucketExpression + " AS group_key, COUNT(*) AS n FROM usage_event"
                + " WHERE tenant_id = ? AND virtual_key_id = ? GROUP BY 1 ORDER BY 1";
        List<String> buckets = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement pin = connection.createStatement()) {
                pin.execute("SET LOCAL timezone = '" + sessionTimeZone + "'");
            }
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, TENANT_ID);
                ps.setObject(2, keyId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        buckets.add(rs.getString(1) + "=" + rs.getLong(2));
                    }
                }
            }
            connection.rollback();
        } catch (Exception e) {
            throw new IllegalStateException("bucket probe failed", e);
        }
        return buckets;
    }

    private void insertEvent(UUID projectId, UUID productId, UUID credentialId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO usage_event
                    (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                     credential_id, model_id, cache_level, input_tokens, output_tokens, total_tokens,
                     gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :providerRequestId, :keyId, :projectId, :productId, :credentialId,
                        'tz-probe-model', 'UPSTREAM', 1, 1, 2, :gatewayRequestId, :occurredAt)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT_ID)
                .addValue("providerRequestId", "tz-probe-" + UUID.randomUUID()).addValue("keyId", keyId)
                .addValue("projectId", projectId).addValue("productId", productId)
                .addValue("credentialId", credentialId).addValue("gatewayRequestId", "greq-" + UUID.randomUUID())
                .addValue("occurredAt", Timestamp.from(occurredAt)));
    }
}
