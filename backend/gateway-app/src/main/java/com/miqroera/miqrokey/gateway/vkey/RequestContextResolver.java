package com.miqroera.miqrokey.gateway.vkey;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.domain.vkey.VirtualKeyParseResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * CAA (Spec v1.1 §4/§6): resolves the per-request context claim to the binding
 * the request runs under. Identity/HMAC remains {@link VirtualKeyResolver}'s
 * job; this resolver only answers "which of the key's bindings?".
 *
 * <h2>Resolution ladder (deterministic, no guessing)</h2>
 * <ol>
 * <li>{@code X-Miqro-Project-Id} claim (Agent-injected, untrusted): must be a
 * UUID and the key must hold an ACTIVE binding for it — otherwise 400
 * {@code CONTEXT_INVALID} / 403 {@code CONTEXT_NOT_ALLOWED}. Selected binding →
 * {@code RESOLVED_HEADER}.</li>
 * <li>Legacy: the presented key suffix (tag) maps to a binding →
 * {@code RESOLVED_SUFFIX}. A non-matching suffix is cosmetic (ADR-0018 keys may
 * carry arbitrary labels) and falls through.</li>
 * <li>Exactly one ACTIVE binding → {@code SOLE_BINDING} (unambiguous
 * project).</li>
 * <li>Zero bindings → invalid key (uniform 404). Multiple bindings without any
 * context → 400 {@code CONTEXT_REQUIRED} (fail closed; never guess, never
 * borrow another project's grant).</li>
 * </ol>
 *
 * <p>
 * Every ruling carries the key's ACTIVE binding count as its candidate
 * cardinality (#1139): {@code RESOLVED_SUFFIX} with 1 candidate is the same "no
 * choice existed" fact as {@code SOLE_BINDING} (the suffix happened to match
 * the only binding — a key is minted with its project's tag as its suffix),
 * while &gt;1 means the ruling really selected among candidates. Recorded,
 * never judged: the ladder order and its outcomes are unchanged.
 * </p>
 *
 * <p>
 * Claim headers ({@code X-Miqro-Claim-*}, {@code X-Miqro-Activity},
 * {@code X-Claude-Code-Session-Id}) are recorded verbatim as audit metadata
 * after allowlist/format validation; they never affect authorization. All
 * {@code X-Miqro-*} headers are stripped before the upstream call by
 * {@code HeaderFilters}.
 * </p>
 */
@Component
public class RequestContextResolver {

    public static final String PROJECT_ID_HEADER = "X-Miqro-Project-Id";
    public static final String ACTIVITY_HEADER = "X-Miqro-Activity";
    public static final String CLAIM_SOURCE_HEADER = "X-Miqro-Claim-Source";
    public static final String CLAIM_CONFIDENCE_HEADER = "X-Miqro-Claim-Confidence";
    public static final String CLAIM_STATUS_HEADER = "X-Miqro-Claim-Status";
    public static final String SESSION_ID_HEADER = "X-Claude-Code-Session-Id";

    private static final Set<String> CLAIM_SOURCES = Set.of("prompt_url", "tool_path", "bash_cwd", "system_cwd",
            "git_remote", "suffix", "none");
    private static final Set<String> CLAIM_CONFIDENCES = Set.of("HIGH", "MEDIUM", "LOW", "NONE");
    private static final Set<String> CLAIM_STATUSES = Set.of("RESOLVED", "AMBIGUOUS", "UNATTRIBUTED");

    private static final int MAX_HEADER_CHARS = 64;

    /**
     * Runs the ladder for an already-authenticated key. Throws
     * {@link AuthFailureException} on rejection; never returns null.
     */
    public ResolvedContext resolve(RouteSnapshot snapshot, RouteSnapshot.KeyRecord key, VirtualKeyParseResult parsed,
            ServerHttpRequest request) {
        // Audit metadata (validated, never authoritative).
        String sessionId = bounded(request.getHeaders().getFirst(SESSION_ID_HEADER));
        UUID activityId = uuidOrNull(bounded(request.getHeaders().getFirst(ACTIVITY_HEADER)));
        String claimSource = allowlisted(bounded(request.getHeaders().getFirst(CLAIM_SOURCE_HEADER)), CLAIM_SOURCES);
        String claimConfidence = allowlisted(bounded(request.getHeaders().getFirst(CLAIM_CONFIDENCE_HEADER)),
                CLAIM_CONFIDENCES);
        String claimStatus = allowlisted(bounded(request.getHeaders().getFirst(CLAIM_STATUS_HEADER)), CLAIM_STATUSES);

        // #1139: the candidate cardinality — the key's ACTIVE binding count at
        // resolution time. Recorded with every ruling the ladder returns, so the
        // console can tell "the suffix picked among several bindings" (>1) from
        // "the suffix merely matched the only one" (1). A count only; the binding
        // details are never exposed.
        int resolutionCandidates = snapshot.bindingCount(key.keyId());

        // 1) Explicit project-id claim.
        String claimedRaw = bounded(request.getHeaders().getFirst(PROJECT_ID_HEADER));
        if (claimedRaw != null) {
            UUID claimed = uuidOrNull(claimedRaw);
            if (claimed == null) {
                throw new AuthFailureException(HttpStatus.BAD_REQUEST, "CONTEXT_INVALID",
                        "X-Miqro-Project-Id must be a project UUID");
            }
            RouteSnapshot.BindingRecord binding = snapshot.bindingByProject(key.keyId(), claimed);
            if (binding == null) {
                throw new AuthFailureException(HttpStatus.FORBIDDEN, "CONTEXT_NOT_ALLOWED",
                        "This key is not bound to the claimed project");
            }
            return new ResolvedContext(binding, claimed, "RESOLVED_HEADER", resolutionCandidates, claimSource,
                    claimConfidence, claimStatus, activityId, sessionId);
        }

        // 2) Legacy suffix selector.
        RouteSnapshot.BindingRecord byTag = snapshot.binding(key.keyId(), parsed.projectTag());
        if (byTag != null) {
            return new ResolvedContext(byTag, null, "RESOLVED_SUFFIX", resolutionCandidates, claimSource,
                    claimConfidence, claimStatus, activityId, sessionId);
        }

        // 3) Sole binding — the project is unambiguous.
        RouteSnapshot.BindingRecord sole = snapshot.soleBinding(key.keyId());
        if (sole != null) {
            return new ResolvedContext(sole, null, "SOLE_BINDING", resolutionCandidates, claimSource, claimConfidence,
                    claimStatus, activityId, sessionId);
        }

        // 4) No binding at all → invalid key; several bindings without context →
        // fail closed rather than borrow any project's grant (#647): when the
        // tenant configured an unattributed policy, route via its dedicated
        // credential/product/model scope and account to the UNATTRIBUTED
        // bucket; otherwise keep the hard CONTEXT_REQUIRED baseline.
        if (resolutionCandidates == 0) {
            throw new AuthFailureException(HttpStatus.NOT_FOUND, "virtual_key_invalid", "Unknown virtual key");
        }
        RouteSnapshot.UnattributedPolicyRecord policy = snapshot.unattributedPolicy(key.tenantId());
        if (policy != null) {
            RouteSnapshot.BindingRecord synthesized = new RouteSnapshot.BindingRecord(key.keyId(), policy.projectId(),
                    null, policy.credentialId(), policy.productId(), null);
            return new ResolvedContext(synthesized, null, "POLICY_ROUTED", resolutionCandidates, claimSource,
                    claimConfidence, claimStatus, activityId, sessionId);
        }
        throw new AuthFailureException(HttpStatus.BAD_REQUEST, "CONTEXT_REQUIRED",
                "This key is bound to several projects; provide X-Miqro-Project-Id");
    }

    private static String bounded(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_HEADER_CHARS) {
            return null;
        }
        return trimmed;
    }

    private static String allowlisted(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : null;
    }

    private static UUID uuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
