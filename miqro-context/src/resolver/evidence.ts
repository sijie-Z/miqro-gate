/**
 * Evidence model (CAA Spec v1.1 §5.1) and the attribution decision object
 * (§5.1/§16). All local; nothing here is authoritative — the gateway
 * adjudicates (§4, R5).
 */

export type EvidenceSource = "prompt_url" | "tool_path" | "bash_cwd" | "system_cwd";
export type EvidenceConfidence = "HIGH" | "MEDIUM" | "LOW";
export type EvidenceScope = "turn" | "session";

export type Evidence = {
  id: string;
  source: EvidenceSource;
  /** Normalized: repo key when known, otherwise an absolute path. */
  value: string;
  repoKey?: string;
  confidence: EvidenceConfidence;
  scope: EvidenceScope;
  observedAt: number;
  requestId: string;
};

export type DecisionStatus = "RESOLVED" | "AMBIGUOUS" | "UNATTRIBUTED";

/** First-class attribution decision (spec §5.1, "Addendum"). */
export type AttributionDecision = {
  status: DecisionStatus;
  projectId?: string;
  projectTag?: string;
  activityId?: string;
  claimSource: EvidenceSource | "none";
  claimConfidence: EvidenceConfidence | "NONE";
  evidenceIds: string[];
  /** Conflict groups when AMBIGUOUS: each entry is one (project → evidenceIds). */
  conflicts?: Array<{ projectId: string; evidenceIds: string[] }>;
  decidedAt: number;
  /** True when RESOLVED by inheriting the session's stable context (§5.3-4). */
  inherited?: boolean;
};
