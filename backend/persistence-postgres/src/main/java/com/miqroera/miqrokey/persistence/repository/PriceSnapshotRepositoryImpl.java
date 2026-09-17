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
                    -- id breaks ties: with effective_from alone, two rows sharing a timestamp
                    -- left the winner to the database, so the same historical replay could
                    -- resolve to different prices (#710).
                    ORDER BY effective_from DESC, id DESC LIMIT 1
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
        // Latest effective price per (product, model, token type). "Latest" is ordered
        // by
        // (effective_from, id) — the id tie-break is load-bearing: the previous
        // MAX(effective_from)-join returned *every* row sharing the max timestamp, and
        // the
        // caller folds them into a map, so the winner was whatever the database
        // happened to
        // return last. That made the same window price differently between runs (#710).
        //
        // Expressed as "no strictly greater candidate exists" rather than DISTINCT ON,
        // so the
        // statement stays portable (the previous author kept it H2-friendly for the
        // same reason).
        return jdbc.query("""
                SELECT p.*
                FROM price_snapshot p
                WHERE p.effective_from <= :at
                  AND NOT EXISTS (SELECT 1 FROM price_snapshot p2
                                  WHERE p2.provider_product_id = p.provider_product_id
                                    AND p2.model_id = p.model_id
                                    AND p2.token_type = p.token_type
                                    AND p2.effective_from <= :at
                                    AND (p2.effective_from > p.effective_from
                                         OR (p2.effective_from = p.effective_from AND p2.id > p.id)))
                ORDER BY p.provider_product_id, p.model_id, p.token_type
                """, new MapSqlParameterSource("at", Timestamp.from(at)), ROW_MAPPER);
    }
}
