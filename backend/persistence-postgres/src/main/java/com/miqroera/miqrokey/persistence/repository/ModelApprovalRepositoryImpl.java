package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.ModelApproval;
import com.miqroera.miqrokey.domain.model.ModelApprovalStatus;
import com.miqroera.miqrokey.domain.repository.ModelApprovalRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class ModelApprovalRepositoryImpl implements ModelApprovalRepository {

    private static final RowMapper<ModelApproval> ROW_MAPPER = (rs, rowNum) -> new ModelApproval(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("virtual_key_id"),
            rs.getString("model_id"), (UUID) rs.getObject("requested_by"),
            ModelApprovalStatus.valueOf(rs.getString("status")), (UUID) rs.getObject("reviewed_by"),
            rs.getString("reason"), rs.getString("review_note"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final String COLS = "id, tenant_id, virtual_key_id, model_id, requested_by, status, reviewed_by,"
            + " reason, review_note, version, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public ModelApprovalRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ModelApproval insert(ModelApproval approval) {
        jdbc.update("""
                INSERT INTO model_approval (id, tenant_id, virtual_key_id, model_id, requested_by,
                    status, reviewed_by, reason, review_note, version, created_at, updated_at)
                VALUES (:id, :tenantId, :virtualKeyId, :modelId, :requestedBy,
                    :status, :reviewedBy, :reason, :reviewNote, :version, :createdAt, :updatedAt)
                """, toParams(approval));
        return approval;
    }

    @Override
    public Optional<ModelApproval> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT " + COLS + " FROM model_approval WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ModelApproval> findAllByVirtualKeyId(UUID virtualKeyId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM model_approval WHERE virtual_key_id = :virtualKeyId"
                        + " ORDER BY created_at DESC",
                new MapSqlParameterSource("virtualKeyId", virtualKeyId), ROW_MAPPER);
    }

    @Override
    public List<ModelApproval> findAllByRequestedBy(UUID requestedBy) {
        return jdbc.query(
                "SELECT " + COLS + " FROM model_approval WHERE requested_by = :requestedBy"
                        + " ORDER BY created_at DESC",
                new MapSqlParameterSource("requestedBy", requestedBy), ROW_MAPPER);
    }

    @Override
    public List<ModelApproval> findPage(ModelApprovalStatus status, int limit, Instant beforeCreatedAt, UUID beforeId) {
        var params = new MapSqlParameterSource("status", status == null ? null : status.name()).addValue("limit", limit)
                .addValue("beforeCreatedAt", toTs(beforeCreatedAt)).addValue("beforeId", beforeId);
        // Explicit casts: PostgreSQL cannot infer a type for a null parameter that
        // appears in both "? IS NULL" and the row-wise keyset comparison.
        String sql = "SELECT " + COLS + " FROM model_approval"
                + " WHERE (:status::varchar IS NULL OR status = :status::varchar)"
                + " AND (:beforeCreatedAt::timestamptz IS NULL"
                + "     OR (created_at, id) < (:beforeCreatedAt::timestamptz, :beforeId::uuid))"
                + " ORDER BY created_at DESC, id DESC LIMIT :limit";
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    @Override
    public List<ModelApproval> findAllByStatus(ModelApprovalStatus status) {
        return jdbc.query("SELECT " + COLS + " FROM model_approval WHERE status = :status ORDER BY created_at ASC",
                new MapSqlParameterSource("status", status.name()), ROW_MAPPER);
    }

    @Override
    @Transactional
    public ModelApproval update(ModelApproval approval) {
        long expectedVersion = approval.version() - 1;
        var params = toParams(approval).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update("""
                UPDATE model_approval SET status = :status, reviewed_by = :reviewedBy,
                    review_note = :reviewNote, version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: model approval " + approval.id());
        return approval;
    }

    private static Timestamp toTs(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private MapSqlParameterSource toParams(ModelApproval a) {
        return new MapSqlParameterSource().addValue("id", a.id()).addValue("tenantId", a.tenantId())
                .addValue("virtualKeyId", a.virtualKeyId()).addValue("modelId", a.modelId())
                .addValue("requestedBy", a.requestedBy()).addValue("status", a.status().name())
                .addValue("reviewedBy", a.reviewedBy()).addValue("reason", a.reason())
                .addValue("reviewNote", a.reviewNote()).addValue("version", a.version())
                .addValue("createdAt", toTs(a.createdAt())).addValue("updatedAt", toTs(a.updatedAt()));
    }
}
