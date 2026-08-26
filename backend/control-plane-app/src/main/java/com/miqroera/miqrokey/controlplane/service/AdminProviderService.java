package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.domain.model.PlanScope;
import com.miqroera.miqrokey.domain.model.Provider;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.model.SeatStatus;
import com.miqroera.miqrokey.domain.model.SubscriptionStatus;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.repository.ProviderRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin provider/Plan operations (G5.3, api-contract §5): provider product
 * instances (protocol, Plan shape, implementation and balance authority),
 * subscriptions (CRUD) and their seats (create / assign / release). All
 * SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor.
 */
@Service
public class AdminProviderService {

    private final ProviderRepository providerRepository;
    private final ProviderProductRepository productRepository;
    private final UpstreamSubscriptionRepository subscriptionRepository;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbc;

    public AdminProviderService(ProviderRepository providerRepository, ProviderProductRepository productRepository,
            UpstreamSubscriptionRepository subscriptionRepository, AuditService auditService,
            NamedParameterJdbcTemplate jdbc) {
        this.providerRepository = providerRepository;
        this.productRepository = productRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // provider products
    // ------------------------------------------------------------------

    public List<ProductView> listProducts() {
        return jdbc.query("""
                SELECT pp.*, p.slug AS provider_slug, p.display_name AS provider_name
                FROM provider_products pp
                JOIN providers p ON p.id = pp.provider_id
                ORDER BY p.display_name, pp.display_name
                """, new MapSqlParameterSource(), PRODUCT_ROW_MAPPER);
    }

    public ProviderProduct product(UUID productId) {
        return productRepository.findById(productId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Provider product not found"));
    }

    public List<Provider> listProviders() {
        return providerRepository.findAll();
    }

    // ------------------------------------------------------------------
    // subscriptions + seats
    // ------------------------------------------------------------------

    public List<SubscriptionView> listSubscriptions(UUID tenantId) {
        return jdbc.query("""
                SELECT s.*, pp.display_name AS product_name
                FROM upstream_subscriptions s
                JOIN provider_products pp ON pp.id = s.provider_product_id
                WHERE s.tenant_id = :tenantId
                ORDER BY s.created_at
                """, new MapSqlParameterSource("tenantId", tenantId), SUBSCRIPTION_ROW_MAPPER);
    }

    public SubscriptionView subscription(UUID tenantId, UUID subscriptionId) {
        List<SubscriptionView> found = jdbc.query("""
                SELECT s.*, pp.display_name AS product_name
                FROM upstream_subscriptions s
                JOIN provider_products pp ON pp.id = s.provider_product_id
                WHERE s.tenant_id = :tenantId AND s.id = :subscriptionId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("subscriptionId", subscriptionId),
                SUBSCRIPTION_ROW_MAPPER);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND", "Subscription not found");
        }
        return found.get(0);
    }

    @Transactional
    public UpstreamSubscription createSubscription(UUID tenantId, UUID adminId, UUID providerProductId, String name,
            BillingMode billingMode, PlanScope planScope, BigDecimal subscriptionPrice, String currency,
            Long quotaTotal, String quotaUnit) {
        productRepository.findById(providerProductId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Provider product not found"));
        UpstreamSubscription subscription = new UpstreamSubscription(UUID.randomUUID(), tenantId, providerProductId,
                name, null, billingMode != null ? billingMode : BillingMode.FIXED_SUBSCRIPTION,
                planScope != null ? planScope : PlanScope.NONE, subscriptionPrice, currency, null, null, null,
                quotaTotal, quotaUnit, SubscriptionStatus.ACTIVE, null,
                com.miqroera.miqrokey.domain.model.StatusSource.MANUAL_UNKNOWN, 0, Instant.now(), Instant.now());
        subscriptionRepository.insert(subscription);
        auditService.record(tenantId, adminId, "SUBSCRIPTION_CREATE", "SUBSCRIPTION", subscription.id(),
                "{\"name\":\"" + name + "\"}", null);
        return subscription;
    }

    public UpstreamSubscription updateSubscription(UUID tenantId, UUID adminId, UUID subscriptionId, String name,
            BigDecimal subscriptionPrice, String currency, Long quotaTotal, String quotaUnit,
            SubscriptionStatus status) {
        UpstreamSubscription subscription = requireSubscription(tenantId, subscriptionId);
        UpstreamSubscription updated = new UpstreamSubscription(subscription.id(), subscription.tenantId(),
                subscription.providerProductId(), name != null ? name : subscription.name(),
                subscription.externalAccountRef(), subscription.billingMode(), subscription.planScope(),
                subscriptionPrice != null ? subscriptionPrice : subscription.subscriptionPrice(),
                currency != null ? currency : subscription.currency(), subscription.periodStart(),
                subscription.periodEnd(), subscription.renewalAt(),
                quotaTotal != null ? quotaTotal : subscription.quotaTotal(),
                quotaUnit != null ? quotaUnit : subscription.quotaUnit(),
                status != null ? status : subscription.status(), subscription.lastStatusSyncAt(),
                subscription.statusSource(), subscription.version() + 1, subscription.createdAt(), Instant.now());
        subscriptionRepository.update(updated);
        auditService.record(tenantId, adminId, "SUBSCRIPTION_UPDATE", "SUBSCRIPTION", subscriptionId, "{}", null);
        return updated;
    }

    /** Seats of a subscription (available / assigned / disabled / released). */
    public List<SeatView> seats(UUID tenantId, UUID subscriptionId) {
        requireSubscription(tenantId, subscriptionId);
        return jdbc.query("""
                SELECT ps.*, u.username, u.display_name AS user_display
                FROM plan_seats ps
                LEFT JOIN users u ON u.id = ps.assigned_user_id AND u.tenant_id = ps.tenant_id
                WHERE ps.tenant_id = :tenantId AND ps.upstream_subscription_id = :subscriptionId
                ORDER BY ps.created_at
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("subscriptionId", subscriptionId),
                SEAT_ROW_MAPPER);
    }

    @Transactional
    public SeatView createSeat(UUID tenantId, UUID adminId, UUID subscriptionId, String externalSeatRef,
            String displayName, UUID assignedUserId) {
        requireSubscription(tenantId, subscriptionId);
        UUID seatId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plan_seats
                    (id, tenant_id, upstream_subscription_id, external_seat_ref, assigned_user_id, display_name,
                     seat_status, version)
                VALUES (:id, :tenantId, :subscriptionId, :externalRef, :assignedUserId, :displayName, 'ASSIGNED', 0)
                """,
                new MapSqlParameterSource("id", seatId).addValue("tenantId", tenantId)
                        .addValue("subscriptionId", subscriptionId).addValue("externalRef", externalSeatRef)
                        .addValue("assignedUserId", assignedUserId).addValue("displayName", displayName));
        auditService.record(tenantId, adminId, "SEAT_CREATE", "SEAT", seatId,
                displayName != null ? "{\"displayName\":\"" + displayName + "\"}" : "{}", null);
        return seats(tenantId, subscriptionId).stream().filter(s -> s.id().equals(seatId)).findFirst().orElseThrow();
    }

    /**
     * Assigns or releases a seat (member keys keep working via their credential).
     */
    @Transactional
    public SeatView updateSeat(UUID tenantId, UUID adminId, UUID subscriptionId, UUID seatId, UUID assignedUserId,
            SeatStatus status, String displayName) {
        requireSubscription(tenantId, subscriptionId);
        jdbc.update("""
                UPDATE plan_seats
                SET assigned_user_id = :assignedUserId, seat_status = :status, display_name = :displayName,
                    version = version + 1
                WHERE id = :seatId AND tenant_id = :tenantId
                """, new MapSqlParameterSource("assignedUserId", assignedUserId).addValue("status", status.name())
                .addValue("displayName", displayName).addValue("seatId", seatId).addValue("tenantId", tenantId));
        auditService.record(tenantId, adminId, "SEAT_UPDATE", "SEAT", seatId, "{}", null);
        return seats(tenantId, subscriptionId).stream().filter(s -> s.id().equals(seatId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND", "Seat not found"));
    }

    // ------------------------------------------------------------------

    private UpstreamSubscription requireSubscription(UUID tenantId, UUID subscriptionId) {
        UpstreamSubscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND", "Subscription not found"));
        if (!subscription.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND", "Subscription not found");
        }
        return subscription;
    }

    // ------------------------------------------------------------------
    // views
    // ------------------------------------------------------------------

    public record ProductView(UUID id, String providerSlug, String providerName, String productCode, String displayName,
            String billingMode, String protocols, String baseUrlHost, String implementationStatus,
            String balanceAuthority) {
    }

    public record SubscriptionView(UUID id, UUID providerProductId, String productName, String name, String billingMode,
            String planScope, BigDecimal subscriptionPrice, String currency, Long quotaTotal, String quotaUnit,
            String status, Instant createdAt) {
    }

    public record SeatView(UUID id, UUID subscriptionId, String externalSeatRef, UUID assignedUserId, String username,
            String userDisplay, String displayName, String seatStatus, Instant createdAt) {
    }

    private static final RowMapper<ProductView> PRODUCT_ROW_MAPPER = (rs, rowNum) -> {
        String baseUrlHost = "";
        try {
            String templates = rs.getString("base_url_templates");
            if (templates != null && templates.contains("\"url\"")) {
                String url = templates.replaceAll(".*\"url\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                baseUrlHost = java.net.URI.create(url).getHost();
            }
        } catch (Exception ignored) {
            // tolerate malformed templates in the read view
        }
        return new ProductView((UUID) rs.getObject("id"), rs.getString("provider_slug"), rs.getString("provider_name"),
                rs.getString("product_code"), rs.getString("display_name"), rs.getString("billing_mode"),
                rs.getString("supported_wire_protocols"), baseUrlHost, rs.getString("implementation_status"),
                rs.getString("balance_authority"));
    };

    private static final RowMapper<SubscriptionView> SUBSCRIPTION_ROW_MAPPER = (rs, rowNum) -> new SubscriptionView(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("provider_product_id"), rs.getString("product_name"),
            rs.getString("name"), rs.getString("billing_mode"), rs.getString("plan_scope"),
            rs.getObject("subscription_price", BigDecimal.class), rs.getString("currency"),
            rs.getObject("quota_total", Long.class), rs.getString("quota_unit"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SeatView> SEAT_ROW_MAPPER = (rs, rowNum) -> new SeatView((UUID) rs.getObject("id"),
            (UUID) rs.getObject("upstream_subscription_id"), rs.getString("external_seat_ref"),
            (UUID) rs.getObject("assigned_user_id"), rs.getString("username"), rs.getString("user_display"),
            rs.getString("display_name"), rs.getString("seat_status"), rs.getTimestamp("created_at").toInstant());
}
