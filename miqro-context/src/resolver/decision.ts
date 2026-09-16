/**
 * Attribution decision (CAA Spec v1.1 §5.3): scope + grouping + conflicts —
 * deliberately NO scalar scoring and no LLM guessing.
 *
 *   1. group current-turn evidence by the project its repo key resolves to;
 *   2. ≥2 independent HIGH groups → AMBIGUOUS (conflicting evidenceIds kept);
 *   3. exactly one HIGH group → RESOLVED (HIGH);
 *      no HIGH but exactly one MEDIUM group → RESOLVED (MEDIUM);
 *      ≥2 MEDIUM groups → AMBIGUOUS;
 *   4. no project signal this turn → inherit the session's stable context;
 *   5. nothing at all → UNATTRIBUTED.
 */
import type { AttributionDecision, Evidence } from "./evidence.js";
import type { Registry } from "./registry.js";

export type InheritedContext = { projectId: string; projectTag?: string } | undefined;

type Group = { projectId: string; projectTag?: string; high: Evidence[]; medium: Evidence[]; all: Evidence[] };

export function decide(
  evidence: Evidence[],
  registry: Registry,
  inherited: InheritedContext,
  now: number,
): Omit<AttributionDecision, "activityId"> {
  const groups = new Map<string, Group>();
  let unmapped = 0;
  for (const e of evidence) {
    if (!e.repoKey) {
      unmapped += 1; // recorded for the audit but never a project signal (§5.3)
      continue;
    }
    const entry = registry.lookup(e.repoKey);
    if (!entry) {
      unmapped += 1;
      continue;
    }
    let group = groups.get(entry.projectId);
    if (!group) {
      group = { projectId: entry.projectId, projectTag: entry.projectTag, high: [], medium: [], all: [] };
      groups.set(entry.projectId, group);
    }
    group.all.push(e);
    if (e.confidence === "HIGH") group.high.push(e);
    else if (e.confidence === "MEDIUM") group.medium.push(e);
  }

  const highGroups = [...groups.values()].filter((g) => g.high.length > 0);
  const mediumGroups = [...groups.values()].filter((g) => g.high.length === 0 && g.medium.length > 0);

  if (highGroups.length >= 2 || (highGroups.length === 0 && mediumGroups.length >= 2)) {
    const conflicting = highGroups.length >= 2 ? highGroups : mediumGroups;
    return {
      status: "AMBIGUOUS",
      claimSource: "none",
      claimConfidence: "NONE",
      evidenceIds: evidence.map((e) => e.id),
      conflicts: conflicting.map((g) => ({ projectId: g.projectId, evidenceIds: g.all.map((e) => e.id) })),
      decidedAt: now,
    };
  }

  if (highGroups.length === 1 || mediumGroups.length === 1) {
    const group = highGroups[0] ?? mediumGroups[0];
    if (!group) throw new Error("unreachable");
    const strongest = group.high[0] ?? group.medium[0];
    if (!strongest) throw new Error("unreachable");
    return {
      status: "RESOLVED",
      projectId: group.projectId,
      projectTag: group.projectTag,
      claimSource: strongest.source,
      claimConfidence: group.high.length > 0 ? "HIGH" : "MEDIUM",
      evidenceIds: evidence.map((e) => e.id),
      decidedAt: now,
    };
  }

  if (inherited) {
    return {
      status: "RESOLVED",
      projectId: inherited.projectId,
      projectTag: inherited.projectTag,
      claimSource: "none",
      claimConfidence: "NONE",
      evidenceIds: evidence.map((e) => e.id),
      inherited: true,
      decidedAt: now,
    };
  }

  return {
    status: "UNATTRIBUTED",
    claimSource: "none",
    claimConfidence: "NONE",
    evidenceIds: evidence.map((e) => e.id),
    decidedAt: now,
  };
}
