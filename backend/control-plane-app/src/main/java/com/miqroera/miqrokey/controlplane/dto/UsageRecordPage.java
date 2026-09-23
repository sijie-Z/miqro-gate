package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.usage.CacheLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Paged usage records for one user ({@code GET /api/v1/me/usage/records}). Only
 * counts and metadata — never prompt, code, or model content.
 *
 * <p>
 * Two paging flavours share this shape (#1368). {@code page}/{@code size} is
 * jump-to-page: the window is an offset, so it is only approximate while the
 * gateway keeps writing — a reader who needs every row exactly once must not
 * walk it that way. {@code nextCursor} is the stable alternative: pass it back
 * as {@code before} to get the next slice, and stop when it is null. That walk
 * is keyset paging — the slice boundary is a row, not a position — so usage
 * written or deleted in between cannot make a row come out twice or be skipped.
 * </p>
 *
 * <p>
 * {@code total} is an exact {@code COUNT(*)} over the same filter, but it is a
 * separate read from {@code items}: it is the count at some instant during the
 * request, not the size of a frozen result set, so it can legitimately differ
 * from what a walk ends up handing out on a live table. A cursor walk does not
 * need it — {@code nextCursor} is what says whether there is more.
 * </p>
 *
 * @param nextCursor
 *            opaque cursor for the slice after {@code items}, null when this is
 *            the last one. Never build or parse it on the client.
 */
public record UsageRecordPage(List<UsageRecordView> items, long page, long size, long total, String nextCursor) {

    /**
     * One usage row as shown to a caller.
     *
     * <p>
     * The {@code *Tokens} fields are the <b>observed</b> counts — what the gateway
     * actually recorded. The {@code net*} fields are those counts plus every
     * adjustment booked against the row (#709), i.e. the financial/reporting
     * reading; {@code adjusted} says whether any correction exists at all, so a
     * correction that has since been reversed keeps it true while the net counts
     * return to the observed ones (#774). Both are carried rather than the observed
     * fields being overwritten, so the change is additive and a reader never has to
     * guess which one they hold.
     * </p>
     *
     * <p>
     * Enrichment columns (#758): {@code providerProductName} is the 供应商 column;
     * {@code ttfbMs} / {@code wireProtocol} / {@code requestStatus} come from the
     * lifecycle trail and are null for rows without one (coalesced requests);
     * {@code cost} is the per-row figure priced from that row's own price basis
     * (#710) — the same expression the aggregates use — and {@code priced=false}
     * means the row, not the whole number, is 未定价: at least one non-zero token type
     * has no price, so {@code cost} holds only the priced dimensions and is a lower
     * bound rather than a total. It is the amount the report books for this row;
     * the flag is what says not to present it as the full amount.
     * </p>
     *
     * <p>
     * Attribution (#1128, CAA V54): {@code resolutionStatus} is the server's
     * verdict on which project the request belonged to — four values are produced
     * today ({@code RESOLVED_HEADER} / {@code RESOLVED_SUFFIX} /
     * {@code SOLE_BINDING} / {@code POLICY_ROUTED}); {@code UNATTRIBUTED} /
     * {@code AMBIGUOUS} are reserved by the shared vocabulary — while
     * {@code claimSource} / {@code claimConfidence} are what the client
     * <em>claimed</em> ({@code prompt_url}, {@code tool_path}, … at
     * HIGH/MEDIUM/LOW). The two are kept apart on purpose: the claim is unverified
     * input, the status is the ruling. Every authenticated proxy request walks the
     * ladder, so {@code null} means a row written before V54 (or outside the proxy
     * path). A single-binding key's rows are never null: an ordinary one records
     * {@code RESOLVED_SUFFIX}, because a key is minted with its project's tag as
     * its suffix and the suffix step matching leaves the sole-binding fallback
     * untried. {@code SOLE_BINDING} is what such a key records when the presented
     * suffix does not match its binding — a non-matching suffix is cosmetic
     * (ADR-0018 keys may carry arbitrary labels) and the ladder falls through. The
     * claim fields are null both when the client sent nothing and when it sent
     * something the resolver dropped, so they do not mean "no claim was made".
     * </p>
     *
     * <p>
     * {@code resolutionCandidates} (#1139, CAA V72) is the candidate cardinality:
     * the number of ACTIVE bindings the key held when the ladder ran. It is the
     * discriminator that separates the two facts {@code RESOLVED_SUFFIX} used to
     * blur — 1 = the suffix merely matched the key's only binding (nothing to
     * choose from; the same fact {@code SOLE_BINDING} records), &gt;1 = the ruling
     * really selected among candidates. {@code null} = a row written before V72:
     * unknown, and consumers must not guess. A count, deliberately: the binding
     * details are never exposed.
     * </p>
     */
    public record UsageRecordView(Instant occurredAt, String modelId, CacheLevel cacheLevel, Long inputTokens,
            Long outputTokens, Long cacheReadInputTokens, Long cacheCreationInputTokens, Long totalTokens,
            Long latencyMs, Integer upstreamStatusCode, String providerRequestId, String gatewayRequestId,
            boolean isComplete, boolean usageMissing, UUID virtualKeyId, String clientIp, Long netInputTokens,
            Long netOutputTokens, Long netCacheReadInputTokens, Long netCacheCreationInputTokens, boolean adjusted,
            String providerProductName, Long ttfbMs, String wireProtocol, String requestStatus, BigDecimal cost,
            boolean priced, String resolutionStatus, Integer resolutionCandidates, String claimSource,
            String claimConfidence) {
    }
}
