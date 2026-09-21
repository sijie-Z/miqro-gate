package com.miqroera.miqrokey.controlplane.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One project's monthly budget with its live spend watermark (G8.2): the budget
 * plan plus the current month's allocated cost, the spend percentage and the
 * alert level (NORMAL / WARNING / EXCEEDED) derived from the configured
 * threshold.
 */
public record BudgetView(UUID projectId, String projectCode, String projectName, String month, BigDecimal amount,
        String currency, BigDecimal alertThresholdPct, String status, BigDecimal spent, BigDecimal spentPct,
        String level) {
}
