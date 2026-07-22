package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.*;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class UpstreamSubscriptionRepositoryImpl implements UpstreamSubscriptionRepository {

    private static final RowMapper<UpstreamSubscription> ROW_MAPPER = (rs, rowNum) -> {
        BigDecimal price = rs.getBigDecimal("subscription_price");
        return new UpstreamSubscription((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("provider_product_id"), rs.getString("name"), rs.getString("external_account_ref"),
                BillingMode.valueOf(rs.getString("billing_mode")),
                rs.getString("plan_scope") != null ? PlanScope.valueOf(rs.getString("plan_scope")) : null, price,
                rs.getString("currency"),
                rs.getTimestamp("period_start") != null ? rs.getTimestamp("period_start").toInstant() : null,
                rs.getTimestamp("period_end") != null ? rs.getTimestamp("period_end").toInstant() : null,
                rs.getTimestamp("renewal_at") != null ? rs.getTimestamp("renewal_at").toInstant() : null,
                (Long) rs.getObject("quota_total"), rs.getString("quota_unit"),
                SubscriptionStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("last_status_sync_at") != null
                        ? rs.getTimestamp("last_status_sync_at").toInstant()
                        : null,
                rs.getString("status_source") != null ? StatusSource.valueOf(rs.getString("status_source")) : null,
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    };

    private final NamedParameterJdbcTemplate jdbc;
    public UpstreamSubscriptionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UpstreamSubscription> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM upstream_subscriptions WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<UpstreamSubscription> findAllByProviderProductId(UUID providerProductId) {
        return jdbc.query("SELECT * FROM upstream_subscriptions WHERE provider_product_id = :pid",
                new MapSqlParameterSource("pid", providerProductId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public UpstreamSubscription insert(UpstreamSubscription sub) {
        jdbc.update(
                "INSERT INTO upstream_subscriptions (id, tenant_id, provider_product_id, name, external_account_ref, billing_mode, plan_scope, subscription_price, currency, period_start, period_end, renewal_at, quota_total, quota_unit, status, last_status_sync_at, status_source, version, created_at, updated_at) VALUES (:id, :tenantId, :providerProductId, :name, :externalAccountRef, :billingMode, :planScope, :subscriptionPrice, :currency, :periodStart, :periodEnd, :renewalAt, :quotaTotal, :quotaUnit, :status, :lastStatusSyncAt, :statusSource, :version, :createdAt, :updatedAt)",
                toParams(sub));
        return sub;
    }

    @Override
    @Transactional
    public UpstreamSubscription update(UpstreamSubscription sub) {
        long expectedVersion = sub.version() - 1;
        var params = toParams(sub).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update(
                "UPDATE upstream_subscriptions SET name = :name, billing_mode = :billingMode, plan_scope = :planScope, status = :status, last_status_sync_at = :lastStatusSyncAt, status_source = :statusSource, version = version + 1, updated_at = :updatedAt WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion",
                params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: subscription " + sub.id());
        return sub;
    }

    private MapSqlParameterSource toParams(UpstreamSubscription s) {
        return new MapSqlParameterSource().addValue("id", s.id()).addValue("tenantId", s.tenantId())
                .addValue("providerProductId", s.providerProductId()).addValue("name", s.name())
                .addValue("externalAccountRef", s.externalAccountRef()).addValue("billingMode", s.billingMode().name())
                .addValue("planScope", s.planScope() != null ? s.planScope().name() : null)
                .addValue("subscriptionPrice", s.subscriptionPrice()).addValue("currency", s.currency())
                .addValue("periodStart", s.periodStart() != null ? Timestamp.from(s.periodStart()) : null)
                .addValue("periodEnd", s.periodEnd() != null ? Timestamp.from(s.periodEnd()) : null)
                .addValue("renewalAt", s.renewalAt() != null ? Timestamp.from(s.renewalAt()) : null)
                .addValue("quotaTotal", s.quotaTotal()).addValue("quotaUnit", s.quotaUnit())
                .addValue("status", s.status().name())
                .addValue("lastStatusSyncAt",
                        s.lastStatusSyncAt() != null ? Timestamp.from(s.lastStatusSyncAt()) : null)
                .addValue("statusSource", s.statusSource() != null ? s.statusSource().name() : null)
                .addValue("version", s.version()).addValue("createdAt", Timestamp.from(s.createdAt()))
                .addValue("updatedAt", Timestamp.from(s.updatedAt()));
    }
}
