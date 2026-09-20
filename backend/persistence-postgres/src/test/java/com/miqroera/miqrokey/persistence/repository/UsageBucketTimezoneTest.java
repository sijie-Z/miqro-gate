package com.miqroera.miqrokey.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository.GroupBy;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository.UsageFilter;
import com.miqroera.miqrokey.persistence.AbstractPostgresTest;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Day and month buckets follow the <em>requested</em> offset and nothing else
 * (#1050).
 *
 * <p>
 * Two defects lived here. The first: the unqualified {@code CAST(occurred_at AS
 * DATE)} resolved through the PostgreSQL session's {@code TimeZone}, so an
 * operator changing the server timezone moved every tenant's daily and monthly
 * series by one bucket at the day's edge — no code change, no warning, no audit
 * trail. The second: even pinned to UTC the buckets disagreed with the console,
 * where each row's timestamp is printed in the viewer's local time — traffic
 * between local 00:00 and 08:00 was drawn on the previous day's bar.
 * </p>
 *
 * <p>
 * The assertions below are shaped so they have teeth on any host, including one
 * whose sessions default to UTC: the bucket expressions are run on connections
 * whose session timezones differ (they must agree — the session may not move a
 * bucket) while the requested offset does move it (the local day must be the
 * local day). The repository-level checks pin the wiring; those alone would be
 * tautologies on a UTC host, which is why they are not the only assertions.
 * </p>
 */
@DisplayName("Usage day/month buckets follow the requested offset, never the session (#1050)")
class UsageBucketTimezoneTest extends AbstractPostgresTest {

    /** Seed tenant from V1. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 2026-09-03 in UTC, 2026-09-04 00:30 at +08 — the row that used to move. */
    private static final Instant EVENING_UTC = Instant.parse("2026-09-03T16:30:00Z");
    /** 2026-09-04 on both sides. */
    private static final Instant MORNING_UTC = Instant.parse("2026-09-04T02:00:00Z");
    /** 2026-08 in UTC, 2026-09-01 00:30 at +08 — the month edge. */
    private static final Instant MONTH_EDGE = Instant.parse("2026-08-31T16:30:00Z");

    private static final String UTC = "UTC";
    private static final String PLUS_EIGHT_SESSION = "Asia/Shanghai";

    private static final int UTC_OFFSET = 0;
    private static final int BEIJING_OFFSET_MINUTES = 480;

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
    @DisplayName("the day bucket follows the offset, not the session")
    void dayBucketFollowsTheOffsetOnly() {
        List<String> utcSession = dayBucketsOnPinnedSession(UTC, BEIJING_OFFSET_MINUTES);
        List<String> shanghaiSession = dayBucketsOnPinnedSession(PLUS_EIGHT_SESSION, BEIJING_OFFSET_MINUTES);

        // +08 puts the 16:30Z row on 09-04 (its local day) and the month-edge row on
        // 09-01.
        assertThat(utcSession).containsExactly("2026-09-01=1", "2026-09-04=2");
        assertThat(shanghaiSession).isEqualTo(utcSession);

        // The same rows at offset 0 keep the UTC reading.
        assertThat(dayBucketsOnPinnedSession(UTC, UTC_OFFSET)).containsExactly("2026-08-31=1", "2026-09-03=1",
                "2026-09-04=1");
    }

    @Test
    @DisplayName("the month bucket follows the offset, not the session")
    void monthBucketFollowsTheOffsetOnly() {
        List<String> utcSession = monthBucketsOnPinnedSession(UTC, BEIJING_OFFSET_MINUTES);
        List<String> shanghaiSession = monthBucketsOnPinnedSession(PLUS_EIGHT_SESSION, BEIJING_OFFSET_MINUTES);

        // 2026-08-31T16:30Z is 2026-09-01 00:30 at +08: the whole month moves.
        assertThat(utcSession).containsExactly("2026-09=3");
        assertThat(shanghaiSession).isEqualTo(utcSession);

        assertThat(monthBucketsOnPinnedSession(UTC, UTC_OFFSET)).containsExactly("2026-08=1", "2026-09=2");
    }

    @Test
    @DisplayName("the repository's own day aggregation returns the requested offset's buckets")
    void repositoryDayAggregationHonoursTheOffset() {
        assertThat(repositoryBuckets(GroupBy.DAY, BEIJING_OFFSET_MINUTES))
                .isEqualTo(dayBucketsOnPinnedSession(UTC, BEIJING_OFFSET_MINUTES));
        assertThat(repositoryBuckets(GroupBy.DAY, UTC_OFFSET)).isEqualTo(dayBucketsOnPinnedSession(UTC, UTC_OFFSET));
    }

    @Test
    @DisplayName("the repository's own month aggregation returns the requested offset's buckets")
    void repositoryMonthAggregationHonoursTheOffset() {
        assertThat(repositoryBuckets(GroupBy.MONTH, BEIJING_OFFSET_MINUTES))
                .isEqualTo(monthBucketsOnPinnedSession(UTC, BEIJING_OFFSET_MINUTES));
        assertThat(repositoryBuckets(GroupBy.MONTH, UTC_OFFSET))
                .isEqualTo(monthBucketsOnPinnedSession(UTC, UTC_OFFSET));
    }

    private List<String> repositoryBuckets(GroupBy groupBy, int tzOffsetMinutes) {
        UsageFilter filter = new UsageFilter(TENANT_ID, Set.of(keyId), WINDOW_FROM, WINDOW_TO);
        return usageStats.aggregateUsage(groupBy, filter, tzOffsetMinutes).stream()
                .map(row -> row.groupKey() + "=" + row.requests()).sorted().toList();
    }

    private List<String> dayBucketsOnPinnedSession(String sessionTimeZone, int tzOffsetMinutes) {
        return bucketsOnPinnedSession(sessionTimeZone,
                UsageStatsRepositoryImpl.dayBucket("occurred_at", tzOffsetMinutes), tzOffsetMinutes);
    }

    private List<String> monthBucketsOnPinnedSession(String sessionTimeZone, int tzOffsetMinutes) {
        return bucketsOnPinnedSession(sessionTimeZone,
                UsageStatsRepositoryImpl.monthBucket("occurred_at", tzOffsetMinutes), tzOffsetMinutes);
    }

    /**
     * Runs {@code bucketExpression} over this test's rows on a connection pinned to
     * {@code sessionTimeZone}. The pin is transactional ({@code SET LOCAL} plus
     * rollback) so the pooled connection is handed back untouched, and the probe
     * goes through a named-parameter template over that same connection — the
     * production expression binds {@code :tzOffsetMinutes} and the probe must
     * exercise the expression as written, not a rewritten copy.
     */
    private List<String> bucketsOnPinnedSession(String sessionTimeZone, String bucketExpression, int tzOffsetMinutes) {
        String sql = "SELECT " + bucketExpression + " AS group_key, COUNT(*) AS n FROM usage_event"
                + " WHERE tenant_id = :tenantId AND virtual_key_id = :keyId GROUP BY 1 ORDER BY 1";
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", TENANT_ID).addValue("keyId", keyId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement pin = connection.createStatement()) {
                pin.execute("SET LOCAL timezone = '" + sessionTimeZone + "'");
            }
            NamedParameterJdbcTemplate probe = new NamedParameterJdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            List<String> buckets = probe.query(sql, params, (rs, i) -> rs.getString(1) + "=" + rs.getLong(2));
            connection.rollback();
            return buckets;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "bucket probe failed: " + NamedParameterUtils.substituteNamedParameters(sql, params), e);
        }
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
