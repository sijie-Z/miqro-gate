package com.miqroera.miqrokey.gateway.vkey;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;

import java.util.UUID;

/**
 * CAA (Context Attribution Architecture, Spec v1.1 §4/§6): the per-request
 * attribution decision accompanying an authenticated key — which binding the
 * request runs under, how it was selected ({@code resolutionStatus}), and the
 * client's <em>unverified</em> context claims kept for audit.
 *
 * <p>
 * Security invariant: every field except {@code binding} is a CLAIM, not an
 * authority. The binding itself was validated against the route snapshot; the
 * claims are recorded as-is and never influence authorization or routing.
 * </p>
 *
 * <p>
 * {@code resolutionStatus} ladder (Spec §4): {@code RESOLVED_HEADER} (validated
 * {@code X-Miqro-Project-Id} claim) → {@code RESOLVED_SUFFIX} (legacy
 * key-suffix tag maps to a binding) → {@code SOLE_BINDING} (key has exactly one
 * binding) → otherwise the request is rejected before this record exists
 * ({@code CONTEXT_REQUIRED} / {@code CONTEXT_NOT_ALLOWED}).
 * </p>
 */
public record ResolvedContext(RouteSnapshot.BindingRecord binding, UUID claimedProjectId, String resolutionStatus,
        String claimSource, String claimConfidence, String claimStatus, UUID activityId, String sessionId) {

    /** Legacy shape without CAA metadata (fixtures, suffix-only clients). */
    public static ResolvedContext of(RouteSnapshot.BindingRecord binding) {
        return new ResolvedContext(binding, null, "RESOLVED_SUFFIX", null, null, null, null, null);
    }
}
