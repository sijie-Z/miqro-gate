package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.client.PriceSourceClient;
import com.miqroera.miqrokey.controlplane.client.PriceSourceException;
import com.miqroera.miqrokey.controlplane.client.SourceModelPrice;
import com.miqroera.miqrokey.controlplane.config.PriceSyncProperties;
import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Price-catalog sync (issue #585): pulls the current quotes from the public
 * price source and upserts price snapshots for the models already present in
 * our catalog. Success-only pipeline like the model probe: a fetch failure
 * writes nothing and answers 502.
 *
 * <p>
 * Scope rules: only PAYG products whose code maps to a source prefix are
 * synced; models are matched inside the product's ACTIVE {@code model_catalog}
 * rows (exact slug suffix, then the alias table). Unchanged values are skipped
 * so re-syncing stays cheap. Source quotes are USD per token and are converted
 * to CNY per 1M tokens with the configured rate.
 * </p>
 */
@Service
public class AdminPriceSyncService {

    private static final BigDecimal TOKENS_PER_UNIT = new BigDecimal("1000000");
    private static final String CURRENCY_CNY = "CNY";
    static final String SOURCE_LABEL = "OFFICIAL";

    private final PriceSourceClient priceSourceClient;
    private final PriceSyncProperties properties;
    private final ProviderProductRepository productRepository;
    private final PriceSnapshotRepository priceRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public AdminPriceSyncService(PriceSourceClient priceSourceClient, PriceSyncProperties properties,
            ProviderProductRepository productRepository, PriceSnapshotRepository priceRepository,
            NamedParameterJdbcTemplate jdbc, AuditService auditService, PlatformTransactionManager transactionManager) {
        this.priceSourceClient = priceSourceClient;
        this.properties = properties;
        this.productRepository = productRepository;
        this.priceRepository = priceRepository;
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Runs one sync and returns the report (written/unchanged/unmatched). */
    public Map<String, Object> sync(UUID tenantId, UUID adminId, AuditContext context) {
        List<SourceModelPrice> quotes = fetchQuotes(tenantId, adminId, context);
        Instant now = Instant.now();
        Map<String, PriceSnapshot> latest = new LinkedHashMap<>();
        for (PriceSnapshot snapshot : priceRepository.findAllLatestAt(now)) {
            latest.put(priceKey(snapshot.providerProductId(), snapshot.modelId(), snapshot.tokenType()), snapshot);
        }

        List<PriceSnapshot> toWrite = new ArrayList<>();
        List<Map<String, Object>> unmatched = new ArrayList<>();
        List<String> skippedProducts = new ArrayList<>();
        int unchanged = 0;
        int syncedProducts = 0;
        BigDecimal rate = properties.getUsdCnyRate();
        for (var product : productRepository.findAll()) {
            if (product.billingMode() != BillingMode.PAYG) {
                continue;
            }
            String prefix = PriceCatalogMapper.PRODUCT_CODE_PREFIX.get(product.productCode());
            if (prefix == null) {
                skippedProducts.add(product.productCode());
                continue;
            }
            syncedProducts++;
            Map<String, SourceModelPrice> bySuffix = PriceCatalogMapper.indexBySuffix(quotes, prefix);
            for (String modelId : activeCatalogModels(product.id())) {
                SourceModelPrice quote = PriceCatalogMapper.resolve(modelId, prefix, bySuffix);
                if (quote == null) {
                    unmatched.add(Map.of("productCode", product.productCode(), "modelId", modelId));
                    continue;
                }
                for (Map.Entry<PriceTokenType, BigDecimal> entry : perTokenPrices(quote).entrySet()) {
                    BigDecimal cnyPerMillion = entry.getValue().multiply(TOKENS_PER_UNIT).multiply(rate).setScale(6,
                            RoundingMode.HALF_UP);
                    String key = priceKey(product.id(), modelId, entry.getKey());
                    PriceSnapshot existing = latest.get(key);
                    if (existing != null && existing.unitPrice().compareTo(cnyPerMillion) == 0) {
                        unchanged++;
                        continue;
                    }
                    toWrite.add(new PriceSnapshot(UUID.randomUUID(), product.id(), modelId, entry.getKey(),
                            CURRENCY_CNY, cnyPerMillion, now, SOURCE_LABEL, adminId, now));
                }
            }
        }

        transactionTemplate.executeWithoutResult(status -> toWrite.forEach(priceRepository::insert));
        auditService.record(tenantId, adminId, "PRICE_SYNC", "PRICE_SNAPSHOT", null,
                AuditSummaries.summary(context, "written", toWrite.size(), "unchanged", unchanged, "unmatched",
                        unmatched.size(), "syncedProducts", syncedProducts),
                context.requestId());
        return report(rate, toWrite.size(), unchanged, unmatched, skippedProducts);
    }

    private List<SourceModelPrice> fetchQuotes(UUID tenantId, UUID adminId, AuditContext context) {
        try {
            return priceSourceClient.fetch();
        } catch (PriceSourceException e) {
            auditService.record(tenantId, adminId, "PRICE_SYNC_FAILED", "PRICE_SNAPSHOT", null,
                    AuditSummaries.summary(context, "error", e.code()), context.requestId());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PRICE_SYNC_FAILED", e.getMessage());
        }
    }

    private static Map<PriceTokenType, BigDecimal> perTokenPrices(SourceModelPrice quote) {
        Map<PriceTokenType, BigDecimal> prices = new LinkedHashMap<>();
        putIfPresent(prices, PriceTokenType.INPUT, quote.promptPerToken());
        putIfPresent(prices, PriceTokenType.OUTPUT, quote.completionPerToken());
        putIfPresent(prices, PriceTokenType.CACHE_READ, quote.cacheReadPerToken());
        putIfPresent(prices, PriceTokenType.CACHE_CREATION, quote.cacheWritePerToken());
        return prices;
    }

    private static void putIfPresent(Map<PriceTokenType, BigDecimal> prices, PriceTokenType type, BigDecimal value) {
        if (value != null && value.signum() > 0) {
            prices.put(type, value);
        }
    }

    private List<String> activeCatalogModels(UUID productId) {
        return jdbc.queryForList("""
                SELECT model_id FROM model_catalog
                WHERE provider_product_id = :productId AND status = 'ACTIVE'
                ORDER BY model_id
                """, new MapSqlParameterSource("productId", productId), String.class);
    }

    private static String priceKey(UUID productId, String modelId, PriceTokenType type) {
        return productId + "|" + modelId.toLowerCase(Locale.ROOT) + "|" + type;
    }

    private static Map<String, Object> report(BigDecimal rate, int written, int unchanged,
            List<Map<String, Object>> unmatched, List<String> skippedProducts) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", "openrouter");
        report.put("usdCnyRate", rate);
        report.put("written", written);
        report.put("unchanged", unchanged);
        report.put("unmatched", unmatched);
        report.put("skippedProducts", skippedProducts);
        report.put("syncedAt", Instant.now().toString());
        return report;
    }
}
