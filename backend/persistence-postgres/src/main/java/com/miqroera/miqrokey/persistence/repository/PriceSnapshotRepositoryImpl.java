package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
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
public class PriceSnapshotRepositoryImpl implements PriceSnapshotRepository {

    private static final RowMapper<PriceSnapshot> ROW_MAPPER = (rs, rowNum) -> new PriceSnapshot(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("provider_product_id"), rs.getString("model_id"),
            PriceTokenType.valueOf(rs.getString("token_type")), rs.getString("currency"),
            rs.getBigDecimal("unit_price"), rs.getTimestamp("effective_from").toInstant(), rs.getString("source"),
            (UUID) rs.getObject("created_by"), rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public PriceSnapshotRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public PriceSnapshot insert(PriceSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type,
                    currency, unit_price, effective_from, source, created_by, created_at)
                VALUES (:id, :providerProductId, :modelId, :tokenType,
                    :currency, :unitPrice, :effectiveFrom, :source, :createdBy, :createdAt)
                """,
                new MapSqlParameterSource().addValue("id", snapshot.id())
                        .addValue("providerProductId", snapshot.providerProductId())
                        .addValue("modelId", snapshot.modelId()).addValue("tokenType", snapshot.tokenType().name())
                        .addValue("currency", snapshot.currency()).addValue("unitPrice", snapshot.unitPrice())
                        .addValue("effectiveFrom", Timestamp.from(snapshot.effectiveFrom()))
                        .addValue("source", snapshot.source()).addValue("createdBy", snapshot.createdBy())
                        .addValue("createdAt", Timestamp.from(snapshot.createdAt())));
        return snapshot;
    }

    @Override
    public Optional<PriceSnapshot> findLatestAt(UUID providerProductId, String modelId, PriceTokenType tokenType,
            Instant at) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM price_snapshot
                    WHERE provider_product_id = :productId AND model_id = :modelId AND token_type = :tokenType
                      AND effective_from <= :at
                    ORDER BY effective_from DESC LIMIT 1
                    """,
                    new MapSqlParameterSource().addValue("productId", providerProductId).addValue("modelId", modelId)
                            .addValue("tokenType", tokenType.name()).addValue("at", Timestamp.from(at)),
                    ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<PriceSnapshot> findAllLatestAt(Instant at) {
        // Latest effective price per (product, model, token type): join against
        // the max-effective_from per triple. Written without DISTINCT ON so the
        // statement also runs on H2-compatible databases.
        return jdbc.query("""
                SELECT DISTINCT p.*
                FROM price_snapshot p
                JOIN (SELECT provider_product_id, model_id, token_type, MAX(effective_from) AS eff
                      FROM price_snapshot
                      WHERE effective_from <= :at
                      GROUP BY provider_product_id, model_id, token_type) m
                  ON m.provider_product_id = p.provider_product_id
                 AND m.model_id = p.model_id
                 AND m.token_type = p.token_type
                 AND m.eff = p.effective_from
                ORDER BY p.provider_product_id, p.model_id, p.token_type
                """, new MapSqlParameterSource("at", Timestamp.from(at)), ROW_MAPPER);
    }
}
