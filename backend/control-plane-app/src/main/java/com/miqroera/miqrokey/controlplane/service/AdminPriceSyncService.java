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
 *
 * <p>
 * Conflict policy against human input (issue #708). Two trigger paths share
 * this pipeline and differ only in what happens when a differing quote meets an
 * existing {@code MANUAL} snapshot for the same (product, model, token type):
 * </p>
 * <ul>
 * <li>{@link #sync} — admin-triggered. The official quote replaces the manual
 * snapshot, as documented in api-contract §5.9: an explicit click is
 * authoritative.</li>
 * <li>{@link #syncPreservingManual} — scheduled (#708). The manual snapshot is
 * <em>kept</em> and the quote is reported as a conflict, so an unattended job
 * can never silently overwrite a price a human curated.</li>
 * </ul>
 */
@Service
public class AdminPriceSyncService {

    private static final BigDecimal TOKENS_PER_UNIT = new BigDecimal("1000000");
    private static final String CURRENCY_CNY = "CNY";
    static final String SOURCE_LABEL = "OFFICIAL";
    static final String MANUAL_LABEL = "MANUAL";

    /**
     * What a differing quote does to an existing {@code MANUAL} snapshot (see the
     * class javadoc).
     */
    enum ConflictPolicy {
        /** Admin-triggered: the official quote wins. */
        OVERWRITE,
        /** Scheduled (#708): the manual entry wins, the quote is reported instead. */
        KEEP_MANUAL
    }

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

    /**
     * Runs one admin-triggered sync and returns the report
     * (written/unchanged/conflicts/unmatched). A differing quote replaces an
     * existing MANUAL snapshot (api-contract §5.9).
     */
    public Map<String, Object> sync(UUID tenantId, UUID adminId, AuditContext context) {
        return run(tenantId, adminId, context, ConflictPolicy.OVERWRITE, "manual");
    }

    /**
     * Runs one scheduled sync (issue #708): identical to {@link #sync} except that
     * a differing quote never replaces a MANUAL snapshot — it is reported under
     * {@code conflicts} and the human entry stays the effective price. The run is
     * attributed to no human actor ({@code created_by} stays null on the written
     * snapshots).
     */
    public Map<String, Object> syncPreservingManual(UUID tenantId, AuditContext context) {
        return run(tenantId, null, context, ConflictPolicy.KEEP_MANUAL, "scheduled");
    }

    private Map<String, Object> run(UUID tenantId, UUID adminId, AuditContext context, ConflictPolicy policy,
            String trigger) {
        List<SourceModelPrice> quotes = fetchQuotes(tenantId, adminId, context);
        Instant now = Instant.now();
        Map<String, PriceSnapshot> latest = new LinkedHashMap<>();
        for (PriceSnapshot snapshot : priceRepository.findAllLatestAt(now)) {
            latest.put(priceKey(snapshot.providerProductId(), snapshot.modelId(), snapshot.tokenType()), snapshot);
        }

        List<PriceSnapshot> toWrite = new ArrayList<>();
        List<Map<String, Object>> unmatched = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
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
                    if (existing != null && policy == ConflictPolicy.KEEP_MANUAL
                            && MANUAL_LABEL.equals(existing.source())) {
                        // Never let the unattended job overwrite a human price (#708):
                        // keep the manual snapshot and surface the conflict instead.
                        conflicts.add(Map.of("productCode", product.productCode(), "modelId", modelId, "tokenType",
                                entry.getKey().name(), "manualPrice", existing.unitPrice(), "officialPrice",
                                cnyPerMillion));
                        continue;
                    }
                    toWrite.add(new PriceSnapshot(UUID.randomUUID(), product.id(), modelId, entry.getKey(),
                            CURRENCY_CNY, cnyPerMillion, now, SOURCE_LABEL, adminId, now));
                }
            }
        }

        transactionTemplate.executeWithoutResult(status -> toWrite.forEach(priceRepository::insert));
        auditService.record(tenantId, adminId, "PRICE_SYNC", "PRICE_SNAPSHOT", null,
                AuditSummaries.summary(context, "trigger", trigger, "written", toWrite.size(), "unchanged", unchanged,
                        "conflicts", conflicts.size(), "unmatched", unmatched.size(), "syncedProducts", syncedProducts),
                context.requestId());
        return report(rate, trigger, toWrite.size(), unchanged, conflicts, unmatched, skippedProducts);
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

    private static Map<String, Object> report(BigDecimal rate, String trigger, int written, int unchanged,
            List<Map<String, Object>> conflicts, List<Map<String, Object>> unmatched, List<String> skippedProducts) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", "openrouter");
        report.put("trigger", trigger);
        report.put("usdCnyRate", rate);
        report.put("written", written);
        report.put("unchanged", unchanged);
        // Quotes skipped because a MANUAL snapshot owns the key (scheduled runs only).
        report.put("conflicts", conflicts);
        report.put("unmatched", unmatched);
        report.put("skippedProducts", skippedProducts);
        report.put("syncedAt", Instant.now().toString());
        return report;
    }
}
