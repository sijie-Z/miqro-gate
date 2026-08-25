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
            rs.getString("review_note"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ModelApprovalRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ModelApproval insert(ModelApproval approval) {
        jdbc.update("""
                INSERT INTO model_approval (id, tenant_id, virtual_key_id, model_id, requested_by,
                    status, reviewed_by, review_note, version, created_at, updated_at)
                VALUES (:id, :tenantId, :virtualKeyId, :modelId, :requestedBy,
                    :status, :reviewedBy, :reviewNote, :version, :createdAt, :updatedAt)
                """, toParams(approval));
        return approval;
    }

    @Override
    public Optional<ModelApproval> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM model_approval WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ModelApproval> findAllByVirtualKeyId(UUID virtualKeyId) {
        return jdbc.query("SELECT * FROM model_approval WHERE virtual_key_id = :virtualKeyId ORDER BY created_at DESC",
                new MapSqlParameterSource("virtualKeyId", virtualKeyId), ROW_MAPPER);
    }

    @Override
    public List<ModelApproval> findAllByStatus(ModelApprovalStatus status) {
        return jdbc.query("SELECT * FROM model_approval WHERE status = :status ORDER BY created_at ASC",
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

    private MapSqlParameterSource toParams(ModelApproval a) {
        return new MapSqlParameterSource().addValue("id", a.id()).addValue("tenantId", a.tenantId())
                .addValue("virtualKeyId", a.virtualKeyId()).addValue("modelId", a.modelId())
                .addValue("requestedBy", a.requestedBy()).addValue("status", a.status().name())
                .addValue("reviewedBy", a.reviewedBy()).addValue("reviewNote", a.reviewNote())
                .addValue("version", a.version()).addValue("createdAt", Timestamp.from(a.createdAt()))
                .addValue("updatedAt", Timestamp.from(a.updatedAt()));
    }
}
