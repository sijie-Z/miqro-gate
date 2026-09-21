package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.QuotaDefaultTemplate;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;

import java.time.Instant;
import java.util.UUID;

/**
 * Default quota template state (api-contract §5.22, Tencent doc 135489). Before
 * the tenant ever configured a definition the template is simply
 * {@code enabled=false} with null definition fields.
 */
public record QuotaDefaultTemplateView(boolean enabled, QuotaMetric metric, QuotaPeriod period, Long limitValue,
        long version, UUID updatedBy, Instant updatedAt) {

    public static QuotaDefaultTemplateView empty() {
        return new QuotaDefaultTemplateView(false, null, null, null, 0, null, null);
    }

    public static QuotaDefaultTemplateView of(QuotaDefaultTemplate template) {
        return new QuotaDefaultTemplateView(template.enabled(), template.metric(), template.period(),
                template.limitValue(), template.version(), template.updatedBy(), template.updatedAt());
    }
}
