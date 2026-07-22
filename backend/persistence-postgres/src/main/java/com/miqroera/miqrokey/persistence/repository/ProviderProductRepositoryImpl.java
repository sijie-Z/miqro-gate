package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
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
public class ProviderProductRepositoryImpl implements ProviderProductRepository {

    private static final RowMapper<ProviderProduct> ROW_MAPPER = (rs, rowNum) -> new ProviderProduct(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("provider_id"), rs.getString("product_code"),
            rs.getString("display_name"), BillingMode.valueOf(rs.getString("billing_mode")),
            rs.getString("plan_scope") != null ? PlanScope.valueOf(rs.getString("plan_scope")) : null,
            CredentialTopology.valueOf(rs.getString("credential_topology")),
            rs.getString("quota_topology") != null ? QuotaTopology.valueOf(rs.getString("quota_topology")) : null,
            rs.getString("supported_wire_protocols"), rs.getString("base_url_templates"), rs.getString("auth_scheme"),
            rs.getString("model_catalog_strategy"), rs.getString("plan_status_strategy"),
            rs.getString("balance_authority") != null
                    ? BalanceAuthority.valueOf(rs.getString("balance_authority"))
                    : null,
            ImplementationStatus.valueOf(rs.getString("implementation_status")), rs.getString("catalog_version"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;
    public ProviderProductRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProviderProduct> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM provider_products WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ProviderProduct> findByProviderIdAndProductCode(UUID providerId, String productCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM provider_products WHERE provider_id = :pid AND product_code = :pc",
                    new MapSqlParameterSource().addValue("pid", providerId).addValue("pc", productCode), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ProviderProduct> findAllByProviderId(UUID providerId) {
        return jdbc.query("SELECT * FROM provider_products WHERE provider_id = :pid ORDER BY product_code",
                new MapSqlParameterSource("pid", providerId), ROW_MAPPER);
    }

    @Override
    public List<ProviderProduct> findAll() {
        return jdbc.query("SELECT * FROM provider_products ORDER BY display_name", ROW_MAPPER);
    }

    @Override
    @Transactional
    public ProviderProduct insert(ProviderProduct product) {
        jdbc.update(
                "INSERT INTO provider_products (id, provider_id, product_code, display_name, billing_mode, plan_scope, credential_topology, quota_topology, supported_wire_protocols, base_url_templates, auth_scheme, model_catalog_strategy, plan_status_strategy, balance_authority, implementation_status, catalog_version, version, created_at, updated_at) VALUES (:id, :providerId, :productCode, :displayName, :billingMode, :planScope, :credentialTopology, :quotaTopology, :supportedWireProtocols::jsonb, :baseUrlTemplates::jsonb, :authScheme::jsonb, :modelCatalogStrategy, :planStatusStrategy, :balanceAuthority, :implementationStatus, :catalogVersion, :version, :createdAt, :updatedAt)",
                toParams(product));
        return product;
    }

    @Override
    @Transactional
    public ProviderProduct update(ProviderProduct product) {
        long expectedVersion = product.version() - 1;
        var params = toParams(product).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update(
                "UPDATE provider_products SET product_code = :productCode, display_name = :displayName, billing_mode = :billingMode, plan_scope = :planScope, credential_topology = :credentialTopology, quota_topology = :quotaTopology, implementation_status = :implementationStatus, version = version + 1, updated_at = :updatedAt WHERE id = :id AND version = :expectedVersion",
                params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: product " + product.id());
        return product;
    }

    private MapSqlParameterSource toParams(ProviderProduct p) {
        return new MapSqlParameterSource().addValue("id", p.id()).addValue("providerId", p.providerId())
                .addValue("productCode", p.productCode()).addValue("displayName", p.displayName())
                .addValue("billingMode", p.billingMode().name())
                .addValue("planScope", p.planScope() != null ? p.planScope().name() : null)
                .addValue("credentialTopology", p.credentialTopology().name())
                .addValue("quotaTopology", p.quotaTopology() != null ? p.quotaTopology().name() : null)
                .addValue("supportedWireProtocols", p.supportedWireProtocols())
                .addValue("baseUrlTemplates", p.baseUrlTemplates()).addValue("authScheme", p.authScheme())
                .addValue("modelCatalogStrategy", p.modelCatalogStrategy())
                .addValue("planStatusStrategy", p.planStatusStrategy())
                .addValue("balanceAuthority", p.balanceAuthority() != null ? p.balanceAuthority().name() : null)
                .addValue("implementationStatus", p.implementationStatus().name())
                .addValue("catalogVersion", p.catalogVersion()).addValue("version", p.version())
                .addValue("createdAt", Timestamp.from(p.createdAt()))
                .addValue("updatedAt", Timestamp.from(p.updatedAt()));
    }
}
