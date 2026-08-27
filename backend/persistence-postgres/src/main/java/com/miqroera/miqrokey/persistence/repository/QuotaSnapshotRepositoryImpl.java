package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.repository.QuotaSnapshotRepository;
import com.miqroera.miqrokey.domain.usage.QuotaSnapshot;
import com.miqroera.miqrokey.domain.usage.QuotaSource;
import com.miqroera.miqrokey.domain.usage.QuotaUnit;
import com.miqroera.miqrokey.domain.usage.QuotaWindow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation for {@code quota_snapshots} (V9, G4.2). The latest-per-
 * scope view uses {@code DISTINCT ON} so each (subscription, seat, credential)
 * combination contributes exactly its newest row.
 */
@Repository
@Transactional(readOnly = true)
public class QuotaSnapshotRepositoryImpl implements QuotaSnapshotRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaSnapshotRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public QuotaSnapshot insert(QuotaSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO quota_snapshots
                    (id, tenant_id, subscription_id, seat_id, credential_id, window_type, total, used, remaining,
                     unit, shared_pool, source, provider_status_json, synced_at, error_message, created_at)
                VALUES (:id, :tenantId, :subscriptionId, :seatId, :credentialId, :windowType, :total, :used,
                        :remaining, :unit, :sharedPool, :source, :providerStatusJson, :syncedAt, :errorMessage,
                        :createdAt)
                """, params(snapshot));
        return snapshot;
    }

    @Override
    public List<QuotaSnapshot> findLatestPerScope(UUID tenantId, UUID subscriptionId) {
        return jdbc.query("""
                SELECT DISTINCT ON (seat_id, credential_id) *
                FROM quota_snapshots
                WHERE tenant_id = :tenantId AND subscription_id = :subscriptionId
                ORDER BY seat_id, credential_id, synced_at DESC
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("subscriptionId", subscriptionId),
                ROW_MAPPER);
    }

    @Override
    public List<QuotaSnapshot> findLatestBySubscription(UUID tenantId, UUID subscriptionId, int limit) {
        return jdbc.query("""
                SELECT * FROM quota_snapshots
                WHERE tenant_id = :tenantId AND subscription_id = :subscriptionId
                ORDER BY synced_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("subscriptionId", subscriptionId)
                .addValue("limit", limit), ROW_MAPPER);
    }

    private static MapSqlParameterSource params(QuotaSnapshot s) {
        return new MapSqlParameterSource("id", s.id()).addValue("tenantId", s.tenantId())
                .addValue("subscriptionId", s.subscriptionId()).addValue("seatId", s.seatId())
                .addValue("credentialId", s.credentialId()).addValue("windowType", s.windowType().name())
                .addValue("total", s.total()).addValue("used", s.used()).addValue("remaining", s.remaining())
                .addValue("unit", s.unit().name()).addValue("sharedPool", s.sharedPool())
                .addValue("source", s.source().name()).addValue("providerStatusJson", s.providerStatusJson())
                .addValue("syncedAt", Timestamp.from(s.syncedAt())).addValue("errorMessage", s.errorMessage())
                .addValue("createdAt", Timestamp.from(s.createdAt()));
    }

    private static final RowMapper<QuotaSnapshot> ROW_MAPPER = (rs, rowNum) -> new QuotaSnapshot(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("subscription_id"),
            (UUID) rs.getObject("seat_id"), (UUID) rs.getObject("credential_id"),
            QuotaWindow.valueOf(rs.getString("window_type")), rs.getObject("total", BigDecimal.class),
            rs.getObject("used", BigDecimal.class), rs.getObject("remaining", BigDecimal.class),
            QuotaUnit.valueOf(rs.getString("unit")), rs.getBoolean("shared_pool"),
            QuotaSource.valueOf(rs.getString("source")), rs.getString("provider_status_json"),
            rs.getTimestamp("synced_at").toInstant(), rs.getString("error_message"),
            rs.getTimestamp("created_at").toInstant());
}
