package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Quota-rule plan upsert (api-contract §5.19). The tuple (scopeType, scopeId,
 * metric, period) is the natural key — re-PUTting the same tuple edits the plan
 * in place. Missing optional fields fall back to defaults (warn 80%, ACTIVE).
 */
public record UpsertQuotaRuleRequest(@NotNull QuotaScopeType scopeType, @NotNull UUID scopeId,
        @NotNull QuotaMetric metric, @NotNull QuotaPeriod period, @NotNull @Positive Long limitValue,
        @Min(1) @Max(99) Integer warnPercent, QuotaRuleStatus status) {
}
