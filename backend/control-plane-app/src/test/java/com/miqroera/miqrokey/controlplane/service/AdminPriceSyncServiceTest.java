package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.client.PriceSourceClient;
import com.miqroera.miqrokey.controlplane.client.PriceSourceException;
import com.miqroera.miqrokey.controlplane.client.SourceModelPrice;
import com.miqroera.miqrokey.controlplane.config.PriceSyncProperties;
import com.miqroera.miqrokey.domain.model.BalanceAuthority;
import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.domain.model.ImplementationStatus;
import com.miqroera.miqrokey.domain.model.PlanScope;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Conflict/增量 semantics of the price-sync pipeline (issue #708). The scheduled
 * run must leave a MANUAL snapshot alone and report the conflict; unchanged
 * quotes are never rewritten; a source failure writes nothing but is never
 * silent (audit).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Admin price sync (unit)")
class AdminPriceSyncServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_ID = UUID.fromString("0190-0000-0000-0000-000000000020");
    private static final UUID ACTOR_ID = UUID.fromString("0190-0000-0000-0000-000000000001");
    private static final String REQUEST_ID = "scheduled-price-sync";
    /** 0.0000003 USD/token × 1e6 × 7 = 2.100000 CNY/1M. */
    private static final BigDecimal OFFICIAL_INPUT_CNY = new BigDecimal("2.100000");

    @Mock
    private PriceSourceClient priceSourceClient;
    @Mock
    private ProviderProductRepository productRepository;
    @Mock
    private PriceSnapshotRepository priceRepository;
    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private AuditService auditService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AdminPriceSyncService service;

    @BeforeEach
    void setUp() {
        var properties = new PriceSyncProperties();
        properties.setUsdCnyRate(new BigDecimal("7"));
        service = new AdminPriceSyncService(priceSourceClient, properties, productRepository, priceRepository, jdbc,
                auditService, transactionManager);
    }

    @Test
    @DisplayName("scheduled run keeps a MANUAL snapshot and reports the conflict")
    void scheduledRunKeepsManualSnapshot() {
        stubCatalog("deepseek-flash");
        stubQuote();
        stubLatest(snapshot("MANUAL", new BigDecimal("9.990000")));

        Map<String, Object> report = service.syncPreservingManual(TENANT_ID, AuditContext.human(null, REQUEST_ID));

        assertThat(report.get("written")).isEqualTo(0);
        assertThat(report.get("unchanged")).isEqualTo(0);
        assertThat(report.get("trigger")).isEqualTo("scheduled");
        List<Map<String, Object>> conflicts = conflictsOf(report);
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).get("modelId")).isEqualTo("deepseek-flash");
        assertThat(conflicts.get(0).get("tokenType")).isEqualTo("INPUT");
        assertThat((BigDecimal) conflicts.get(0).get("manualPrice")).isEqualByComparingTo("9.990000");
        assertThat((BigDecimal) conflicts.get(0).get("officialPrice")).isEqualByComparingTo(OFFICIAL_INPUT_CNY);
        // The human entry stays the effective price: nothing was appended.
        verify(priceRepository, never()).insert(any(PriceSnapshot.class));
        verify(auditService).record(eq(TENANT_ID), isNull(), eq("PRICE_SYNC"), eq("PRICE_SNAPSHOT"), isNull(),
                org.mockito.ArgumentMatchers.contains("\"conflicts\":\"1\""), eq(REQUEST_ID));
    }

    @Test
    @DisplayName("admin-triggered sync keeps its documented overwrite semantics")
    void adminRunOverwritesManualSnapshot() {
        stubCatalog("deepseek-flash");
        stubQuote();
        stubLatest(snapshot("MANUAL", new BigDecimal("9.990000")));

        Map<String, Object> report = service.sync(TENANT_ID, ACTOR_ID, AuditContext.human(ACTOR_ID, "req-1"));

        assertThat(report.get("trigger")).isEqualTo("manual");
        assertThat(report.get("written")).isEqualTo(1);
        assertThat(conflictsOf(report)).isEmpty();
        verify(priceRepository).insert(any(PriceSnapshot.class));
    }

    @Test
    @DisplayName("an unchanged quote is not rewritten")
    void unchangedQuoteIsSkipped() {
        stubCatalog("deepseek-flash");
        stubQuote();
        stubLatest(snapshot(AdminPriceSyncService.SOURCE_LABEL, OFFICIAL_INPUT_CNY));

        Map<String, Object> report = service.syncPreservingManual(TENANT_ID, AuditContext.human(null, REQUEST_ID));

        assertThat(report.get("written")).isEqualTo(0);
        assertThat(report.get("unchanged")).isEqualTo(1);
        assertThat(conflictsOf(report)).isEmpty();
        verify(priceRepository, never()).insert(any(PriceSnapshot.class));
    }

    @Test
    @DisplayName("a source failure drives nothing and records PRICE_SYNC_FAILED")
    void sourceFailureIsNotSilent() {
        when(priceSourceClient.fetch()).thenThrow(new PriceSourceException("SOURCE_HTTP_500", "价格源返回非 200。"));

        assertThatThrownBy(() -> service.syncPreservingManual(TENANT_ID, AuditContext.human(null, REQUEST_ID)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("PRICE_SYNC_FAILED");

        verify(priceRepository, never()).insert(any(PriceSnapshot.class));
        verify(auditService).record(eq(TENANT_ID), isNull(), eq("PRICE_SYNC_FAILED"), eq("PRICE_SNAPSHOT"), isNull(),
                anyString(), eq(REQUEST_ID));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> conflictsOf(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("conflicts");
    }

    private void stubCatalog(String... modelIds) {
        doReturn(List.of(modelIds)).when(jdbc).queryForList(anyString(), any(SqlParameterSource.class),
                eq(String.class));
        when(productRepository.findAll()).thenReturn(List.of(paygProduct()));
    }

    private void stubQuote() {
        // Input-only quote keeps the written/unchanged/conflict counters unambiguous.
        when(priceSourceClient.fetch()).thenReturn(List
                .of(new SourceModelPrice("deepseek/deepseek-flash", new BigDecimal("0.0000003"), null, null, null)));
    }

    private void stubLatest(PriceSnapshot snapshot) {
        when(priceRepository.findAllLatestAt(any(Instant.class))).thenReturn(List.of(snapshot));
    }

    private static PriceSnapshot snapshot(String source, BigDecimal unitPrice) {
        return new PriceSnapshot(UUID.randomUUID(), PRODUCT_ID, "deepseek-flash", PriceTokenType.INPUT, "CNY",
                unitPrice, Instant.now().minusSeconds(60), source, ACTOR_ID, Instant.now().minusSeconds(60));
    }

    private static ProviderProduct paygProduct() {
        return new ProviderProduct(PRODUCT_ID, UUID.randomUUID(), "deepseek-payg-api", "DeepSeek", BillingMode.PAYG,
                PlanScope.NONE, null, null, "[\"messages\"]", "[{\"url\":\"https://api.test.example\"}]", "bearer",
                "OFFICIAL_API", "OFFICIAL_API", BalanceAuthority.OFFICIAL_API, ImplementationStatus.IMPLEMENTED, "1", 0,
                Instant.now(), Instant.now());
    }
}
