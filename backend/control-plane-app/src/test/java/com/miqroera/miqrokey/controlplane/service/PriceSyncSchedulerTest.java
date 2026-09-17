package com.miqroera.miqrokey.controlplane.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 24h price-sync cycle (issue #708): it runs against the seed tenant with a
 * system audit identity, and a failing cycle is logged at ERROR and contained
 * so the next one still fires.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Price sync scheduler (unit)")
class PriceSyncSchedulerTest {

    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private AdminPriceSyncService syncService;

    private PriceSyncScheduler scheduler;
    private ListAppender<ILoggingEvent> appender;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new PriceSyncScheduler(syncService, meterRegistry);
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(PriceSyncScheduler.class)).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(PriceSyncScheduler.class)).detachAppender(appender);
    }

    @Test
    @DisplayName("a cycle runs against the seed tenant and reports the counters")
    void cycleReportsCounters() {
        when(syncService.syncPreservingManual(eq(SEED_TENANT_ID), any(AuditContext.class)))
                .thenReturn(Map.<String, Object>of("written", 3, "unchanged", 1, "conflicts", List.of(), "unmatched",
                        List.of(), "syncedAt", "2026-09-17T00:00:00Z"));

        scheduler.runCycle();

        ArgumentCaptor<AuditContext> context = ArgumentCaptor.forClass(AuditContext.class);
        verify(syncService).syncPreservingManual(eq(SEED_TENANT_ID), context.capture());
        assertThat(context.getValue().actorId()).isNull();
        assertThat(context.getValue().requestId()).isEqualTo(PriceSyncScheduler.SCHEDULED_REQUEST_ID);
        assertThat(messages(Level.INFO)).anySatisfy(line -> assertThat(line).contains("written=3", "conflicts=0"));
        assertThat(count("success")).isEqualTo(1.0);
        assertThat(count("failure")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a failing cycle is logged at ERROR and never propagates")
    void failingCycleIsLoggedAndContained() {
        when(syncService.syncPreservingManual(eq(SEED_TENANT_ID), any(AuditContext.class))).thenThrow(
                new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "PRICE_SYNC_FAILED", "价格源返回非 200。"));

        assertThatCode(() -> scheduler.runCycle()).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR))
                .anySatisfy(line -> assertThat(line).contains("Scheduled price sync failed", "价格源返回非 200"));
        // Observable, not just logged (#708 acceptance): the failed run is counted.
        assertThat(count("failure")).isEqualTo(1.0);
        assertThat(count("success")).isEqualTo(0.0);
    }

    /**
     * {@code find} (not {@code get}): an un-incremented result has no meter yet.
     */
    private double count(String result) {
        var counter = meterRegistry.find(PriceSyncScheduler.RUNS_METRIC).tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private List<String> messages(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
