/**
 * Header hygiene at the local agent (CAA Spec v1.1 §3.3):
 * - ALWAYS strip any client-supplied `X-Miqro-*` header (a request may not
 *   forge context — the claim is the agent's to make, and even then only a
 *   hint);
 * - inject the agent's own claims for the current request.
 *
 * Every injected value is a CLAIM, never authority (R5): the gateway
 * re-validates the project against the key's bindings.
 */
import type { AttributionDecision } from "../resolver/evidence.js";

export function stripMiqroHeaders(headers: NodeJS.Dict<string | string[]>): NodeJS.Dict<string | string[]> {
  const out: NodeJS.Dict<string | string[]> = {};
  for (const [name, value] of Object.entries(headers)) {
    if (name.toLowerCase().startsWith("x-miqro-") || name.toLowerCase().startsWith("x-miqrokey-")) continue;
    out[name] = value;
  }
  return out;
}

/**
 * Claim headers for a decision. `X-Miqro-Project-Id` is injected ONLY when
 * the decision is RESOLVED (spec §3.3); AMBIGUOUS / UNATTRIBUTED requests
 * carry the claim status but no project — the gateway then fails closed
 * (CONTEXT_REQUIRED for multi-bound keys) or routes by suffix/sole binding.
 */
export function claimHeaders(decision: AttributionDecision): Record<string, string> {
  const headers: Record<string, string> = {
    "X-Miqro-Claim-Source": decision.claimSource,
    "X-Miqro-Claim-Confidence": decision.claimConfidence,
    "X-Miqro-Claim-Status": decision.status,
  };
  if (decision.activityId) {
    headers["X-Miqro-Activity"] = decision.activityId;
  }
  if (decision.status === "RESOLVED" && decision.projectId) {
    headers["X-Miqro-Project-Id"] = decision.projectId;
  }
  return headers;
}
