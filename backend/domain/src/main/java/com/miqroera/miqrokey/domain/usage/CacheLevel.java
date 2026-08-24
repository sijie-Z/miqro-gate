package com.miqroera.miqrokey.domain.usage;

/**
 * Tier of a request in the tiered usage statistics model.
 *
 * <ul>
 * <li>{@code UPSTREAM} — the request reached the provider and usage was
 * observed.</li>
 * <li>{@code COALESCED} — the request was merged into an in-flight identical
 * request; carries the leader's observed usage.</li>
 * <li>{@code L1_HIT} / {@code L2_HIT} — served from cache; no usage observed,
 * counted separately in {@code cache_hit_event}.</li>
 * </ul>
 */
public enum CacheLevel {
    UPSTREAM, COALESCED, L1_HIT, L2_HIT
}
