package com.miqroera.miqrokey.controlplane.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.miqroera.miqrokey.domain.usage.QuotaSource;
import com.miqroera.miqrokey.domain.usage.QuotaUnit;
import com.miqroera.miqrokey.domain.usage.QuotaWindow;

/**
 * One latest quota snapshot entry for the external billing API (ADR-0010,
 * {@code GET /api/v1/billing/quota}). Carries only quota numbers and their
 * authority level — never the internal error message or provider status
 * payload, which stay on the admin surface.
 */
public record QuotaEntryView(UUID seatId, UUID credentialId, QuotaWindow windowType, BigDecimal total, BigDecimal used,
        BigDecimal remaining, QuotaUnit unit, boolean sharedPool, QuotaSource source, Instant syncedAt) {
}
