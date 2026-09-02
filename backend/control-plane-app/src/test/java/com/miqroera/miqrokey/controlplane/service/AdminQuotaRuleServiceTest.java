package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Window math of quota rules (UTC calendar slices): day, Mon-start week and
 * calendar month — the boundaries the watermark queries run against.
 */
class AdminQuotaRuleServiceTest {

    private static Instant utc(String date) {
        return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Test
    @DisplayName("DAILY window is the current UTC day")
    void dailyWindow() {
        AdminQuotaRuleService.Window w = AdminQuotaRuleService.window(QuotaPeriod.DAILY, LocalDate.parse("2026-09-02"));
        assertThat(w.from()).isEqualTo(utc("2026-09-02"));
        assertThat(w.to()).isEqualTo(utc("2026-09-03"));
    }

    @Test
    @DisplayName("WEEKLY window starts on Monday, also when today is Monday")
    void weeklyWindow() {
        // 2026-09-02 is a Wednesday; the week started Monday 2026-08-31.
        AdminQuotaRuleService.Window w = AdminQuotaRuleService.window(QuotaPeriod.WEEKLY,
                LocalDate.parse("2026-09-02"));
        assertThat(w.from()).isEqualTo(utc("2026-08-31"));
        assertThat(w.to()).isEqualTo(utc("2026-09-07"));

        // Monday itself starts the window.
        AdminQuotaRuleService.Window monday = AdminQuotaRuleService.window(QuotaPeriod.WEEKLY,
                LocalDate.parse("2026-08-31"));
        assertThat(monday.from()).isEqualTo(utc("2026-08-31"));
    }

    @Test
    @DisplayName("MONTHLY window is the UTC calendar month")
    void monthlyWindow() {
        AdminQuotaRuleService.Window w = AdminQuotaRuleService.window(QuotaPeriod.MONTHLY,
                LocalDate.parse("2026-09-02"));
        assertThat(w.from()).isEqualTo(utc("2026-09-01"));
        assertThat(w.to()).isEqualTo(utc("2026-10-01"));

        AdminQuotaRuleService.Window dec = AdminQuotaRuleService.window(QuotaPeriod.MONTHLY,
                LocalDate.parse("2026-12-15"));
        assertThat(dec.to()).isEqualTo(utc("2027-01-01"));
    }
}
