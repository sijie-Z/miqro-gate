package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Definition of the global default quota strategy (api-contract §5.22). The
 * snapshot source only — toggling whether new users receive copies is the
 * enable/disable endpoints' job.
 */
public record ConfigureQuotaDefaultTemplateRequest(@NotNull QuotaMetric metric, @NotNull QuotaPeriod period,
        @NotNull @Positive Long limitValue) {
}
