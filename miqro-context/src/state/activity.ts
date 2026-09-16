/**
 * ActivitySegment switching (CAA Spec v1.1 §3.1/§5.4): a segment boundary
 * happens ONLY when the resolved project changes — HIGH evidence switches
 * immediately; a MEDIUM-only change must be confirmed by two consecutive
 * rounds (first round records pending and does NOT switch). Within the same
 * project, field-level evidence churn (files, paths, commands) never opens a
 * new segment. A→B→A with HIGH evidence at every change yields three real
 * segments.
 */
import { randomUUID } from "node:crypto";
import type { AttributionDecision } from "../resolver/evidence.js";
import type { SessionEntry } from "./session.js";

export type ActivityOutcome = {
  activityId: string;
  /** True when this request opened a new ActivitySegment. */
  switched: boolean;
};

/**
 * Apply the §5.4 hysteresis to a fresh decision and produce the activity
 * segment the request belongs to. Mutates (and returns) the session entry.
 */
export function applyActivity(
  entry: SessionEntry,
  decision: AttributionDecision,
  now: number,
): { entry: SessionEntry; activity: ActivityOutcome } {
  const current = entry.activity;
  const next: SessionEntry = { ...entry, lastDecision: decision };

  if (decision.status !== "RESOLVED" || !decision.projectId) {
    // No resolved project: no switching, no segment change. Keep the last
    // segment id for continuity if one exists.
    if (!current) {
      const activityId = randomUUID();
      next.activity = { activityId, projectId: "UNATTRIBUTED", since: now };
      return { entry: next, activity: { activityId, switched: true } };
    }
    return { entry: next, activity: { activityId: current.activityId, switched: false } };
  }

  const resolvedProject = decision.projectId;

  // Inherited decisions keep the current segment untouched.
  if (decision.inherited && current && current.projectId === resolvedProject) {
    next.pendingMedium = null;
    return { entry: next, activity: { activityId: current.activityId, switched: false } };
  }

  if (!current || current.projectId === "UNATTRIBUTED") {
    const activityId = randomUUID();
    next.activity = { activityId, projectId: resolvedProject, since: now };
    next.pendingMedium = null;
    next.inherited = { projectId: resolvedProject, projectTag: decision.projectTag };
    return { entry: next, activity: { activityId, switched: true } };
  }

  if (current.projectId === resolvedProject) {
    // Same project: nothing happens (spec: field-level changes never switch).
    next.pendingMedium = null;
    next.inherited = { projectId: resolvedProject, projectTag: decision.projectTag };
    return { entry: next, activity: { activityId: current.activityId, switched: false } };
  }

  // Project changed.
  if (decision.claimConfidence === "HIGH") {
    const activityId = randomUUID();
    next.activity = { activityId, projectId: resolvedProject, since: now };
    next.pendingMedium = null;
    next.inherited = { projectId: resolvedProject, projectTag: decision.projectTag };
    return { entry: next, activity: { activityId, switched: true } };
  }

  // MEDIUM-only change: needs two consecutive rounds (R4 hysteresis).
  const pending = entry.pendingMedium;
  if (pending && pending.projectId === resolvedProject && pending.count >= 1) {
    const activityId = randomUUID();
    next.activity = { activityId, projectId: resolvedProject, since: now };
    next.pendingMedium = null;
    next.inherited = { projectId: resolvedProject, projectTag: decision.projectTag };
    return { entry: next, activity: { activityId, switched: true } };
  }
  next.pendingMedium = { projectId: resolvedProject, projectTag: decision.projectTag, count: 1 };
  return { entry: next, activity: { activityId: current.activityId, switched: false } };
}
