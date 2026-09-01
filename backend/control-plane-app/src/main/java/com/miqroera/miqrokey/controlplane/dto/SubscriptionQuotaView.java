package com.miqroera.miqrokey.controlplane.dto;

import java.util.List;
import java.util.UUID;

/**
 * One subscription's quota status for the external billing API (ADR-0010).
 * Subscriptions without snapshots appear with an empty snapshot list so the
 * platform sees the full subscription footprint.
 */
public record SubscriptionQuotaView(UUID subscriptionId, String subscriptionName, List<QuotaEntryView> snapshots) {
}
