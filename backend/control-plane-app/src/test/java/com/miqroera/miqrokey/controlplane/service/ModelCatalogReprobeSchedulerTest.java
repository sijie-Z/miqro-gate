package com.miqroera.miqrokey.controlplane.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduled model re-probe (#350, I8): one probe per (tenant, OFFICIAL_API
 * product) pair, system-actor audits, and failure isolation between products.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogReprobeScheduler")
class ModelCatalogReprobeSchedulerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    NamedParameterJdbcTemplate jdbc;
    @Mock
    ModelCatalogProbeService probeService;

    private ModelCatalogReprobeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ModelCatalogReprobeScheduler(jdbc, probeService);
    }

    @Test
    @DisplayName("probes every due product with a system actor and survives failures")
    void probesEachDueProduct() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), ArgumentMatchers.<SqlParameterSource>any())).thenReturn(List.of(pair(first), pair(second)));
        when(probeService.probe(eq(TENANT), isNull(), eq(first), any(AuditContext.class)))
                .thenReturn(Map.of("modelCount", 1));
        when(probeService.probe(eq(TENANT), isNull(), eq(second), any(AuditContext.class))).thenThrow(new ApiException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "MODEL_PROBE_FAILED", "upstream down"));

        assertThatCode(() -> scheduler.runCycle()).doesNotThrowAnyException();

        verify(probeService).probe(eq(TENANT), isNull(), eq(first), any(AuditContext.class));
        verify(probeService).probe(eq(TENANT), isNull(), eq(second), any(AuditContext.class));
    }

    @Test
    @DisplayName("no ACTIVE subscriptions means no upstream calls")
    void noSubscriptionsNoCalls() {
        when(jdbc.queryForList(anyString(), ArgumentMatchers.<SqlParameterSource>any())).thenReturn(List.of());

        scheduler.runCycle();

        verify(probeService, never()).probe(any(), any(), any(), any());
    }

    private static Map<String, Object> pair(UUID productId) {
        return Map.of("tenant_id", TENANT, "provider_product_id", productId);
    }
}
