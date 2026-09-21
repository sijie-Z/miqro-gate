package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The global default quota strategy (V26, {@code quota_default_template},
 * Tencent doc 135489): one row per tenant. While {@code enabled}, every newly
 * created user receives a snapshot copy of this plan as an ordinary
 * {@link QuotaRule} (USER scope). Copy semantics — later template edits never
 * touch already-assigned rules and disabling keeps them — are enforced by the
 * applying service, not by this record.
 */
public record QuotaDefaultTemplate(UUID tenantId, boolean enabled, QuotaMetric metric, QuotaPeriod period,
        long limitValue, UUID updatedBy, long version, Instant createdAt, Instant updatedAt) {

    public QuotaDefaultTemplate {
        if (tenantId == null || metric == null || period == null || updatedBy == null) {
            throw new IllegalArgumentException("tenantId/metric/period/updatedBy are required");
        }
        if (limitValue <= 0) {
            throw new IllegalArgumentException("limitValue must be positive");
        }
    }
}
