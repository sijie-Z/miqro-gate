package com.miqroera.miqrokey.domain.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One persisted quota/Plan status observation (G4.2, {@code quota_snapshots}
 * V9). Append-only history; readers take the latest row per scope (subscription
 * / seat / credential).
 *
 * <p>
 * {@code source} carries the authority level from
 * {@code docs/provider-adapter-contract.md} §6: {@code OFFICIAL_API} comes from
 * an adapter balance fetch, {@code LOCAL_ESTIMATE} from local usage against the
 * admin-recorded {@code quota_total}, and {@code UNAVAILABLE} means no official
 * API exists — the row exists so the UI can show "unknown" with a timestamp
 * instead of pretending. {@code providerStatusJson} is reserved for sanitized
 * provider payloads and never carries secrets.
 * </p>
 */
public record QuotaSnapshot(UUID id, UUID tenantId, UUID subscriptionId, UUID seatId, UUID credentialId,
        QuotaWindow windowType, BigDecimal total, BigDecimal used, BigDecimal remaining, QuotaUnit unit,
        boolean sharedPool, QuotaSource source, String providerStatusJson, Instant syncedAt, String errorMessage,
        Instant createdAt) {

    public QuotaSnapshot {
        if (id == null || tenantId == null || subscriptionId == null || windowType == null || unit == null
                || source == null || syncedAt == null || createdAt == null) {
            throw new IllegalArgumentException(
                    "id/tenantId/subscriptionId/windowType/unit/source/syncedAt/createdAt" + " are required");
        }
    }
}
