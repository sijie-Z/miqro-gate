package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Provider;
import com.miqroera.miqrokey.domain.model.ProviderStatus;
import com.miqroera.miqrokey.domain.repository.ProviderRepository;
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
public class ProviderRepositoryImpl implements ProviderRepository {

    private static final RowMapper<Provider> ROW_MAPPER = (rs, rowNum) -> new Provider((UUID) rs.getObject("id"),
            rs.getString("slug"), rs.getString("display_name"), rs.getString("official_site_url"),
            rs.getString("documentation_url"), rs.getString("catalog_version"),
            ProviderStatus.valueOf(rs.getString("status")), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;
    public ProviderRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Provider> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM providers WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Provider> findBySlug(String slug) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM providers WHERE slug = :slug",
                    new MapSqlParameterSource("slug", slug), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Provider> findAll() {
        return jdbc.query("SELECT * FROM providers ORDER BY display_name", ROW_MAPPER);
    }

    @Override
    @Transactional
    public Provider insert(Provider provider) {
        jdbc.update(
                "INSERT INTO providers (id, slug, display_name, official_site_url, documentation_url, catalog_version, status, version, created_at, updated_at) VALUES (:id, :slug, :displayName, :officialSiteUrl, :documentationUrl, :catalogVersion, :status, :version, :createdAt, :updatedAt)",
                toParams(provider));
        return provider;
    }

    @Override
    @Transactional
    public Provider update(Provider provider) {
        long expectedVersion = provider.version() - 1;
        var params = toParams(provider).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update(
                "UPDATE providers SET slug = :slug, display_name = :displayName, official_site_url = :officialSiteUrl, documentation_url = :documentationUrl, catalog_version = :catalogVersion, status = :status, version = version + 1, updated_at = :updatedAt WHERE id = :id AND version = :expectedVersion",
                params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: provider " + provider.id());
        return provider;
    }

    private MapSqlParameterSource toParams(Provider p) {
        return new MapSqlParameterSource().addValue("id", p.id()).addValue("slug", p.slug())
                .addValue("displayName", p.displayName()).addValue("officialSiteUrl", p.officialSiteUrl())
                .addValue("documentationUrl", p.documentationUrl()).addValue("catalogVersion", p.catalogVersion())
                .addValue("status", p.status().name()).addValue("version", p.version())
                .addValue("createdAt", Timestamp.from(p.createdAt()))
                .addValue("updatedAt", Timestamp.from(p.updatedAt()));
    }
}
