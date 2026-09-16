import { test } from "node:test";
import assert from "node:assert/strict";
import { applyActivity } from "../src/state/activity.js";
import type { SessionEntry } from "../src/state/session.js";
import type { AttributionDecision } from "../src/resolver/evidence.js";

const A = "aaaaaaaa-0000-0000-0000-000000000001";
const B = "bbbbbbbb-0000-0000-0000-000000000002";

function entry(): SessionEntry {
  return {
    watermark: { conversationKey: "k", messageCount: 0 },
    lastDecision: {
      status: "UNATTRIBUTED",
      claimSource: "none",
      claimConfidence: "NONE",
      evidenceIds: [],
      decidedAt: 0,
    },
    activity: null,
    pendingMedium: null,
  };
}

function resolved(projectId: string, confidence: "HIGH" | "MEDIUM"): AttributionDecision {
  return {
    status: "RESOLVED",
    projectId,
    claimSource: "prompt_url",
    claimConfidence: confidence,
    evidenceIds: [],
    decidedAt: 1,
  };
}

test("first resolved request opens the first segment", () => {
  const { activity } = applyActivity(entry(), resolved(A, "HIGH"), 1);
  assert.equal(activity.switched, true);
});

test("same project across rounds: no segment change (field churn does not switch)", () => {
  const e1 = applyActivity(entry(), resolved(A, "HIGH"), 1);
  const e2 = applyActivity(e1.entry, resolved(A, "HIGH"), 2);
  const e3 = applyActivity(e2.entry, resolved(A, "MEDIUM"), 3);
  assert.equal(e2.activity.switched, false);
  assert.equal(e3.activity.switched, false);
  assert.equal(e2.activity.activityId, e1.activity.activityId);
});

test("HIGH change switches immediately: A → B → A yields three real segments", () => {
  const e1 = applyActivity(entry(), resolved(A, "HIGH"), 1);
  const e2 = applyActivity(e1.entry, resolved(B, "HIGH"), 2);
  const e3 = applyActivity(e2.entry, resolved(A, "HIGH"), 3);
  assert.equal(e2.activity.switched, true);
  assert.equal(e3.activity.switched, true);
  const ids = new Set([e1.activity.activityId, e2.activity.activityId, e3.activity.activityId]);
  assert.equal(ids.size, 3);
});

test("C16: MEDIUM-only change needs two consecutive rounds", () => {
  const e1 = applyActivity(entry(), resolved(A, "HIGH"), 1);
  const first = applyActivity(e1.entry, resolved(B, "MEDIUM"), 2);
  assert.equal(first.activity.switched, false, "first MEDIUM round does not switch");
  assert.equal(first.entry.pendingMedium?.projectId, B);
  const second = applyActivity(first.entry, resolved(B, "MEDIUM"), 3);
  assert.equal(second.activity.switched, true, "second consecutive round switches");
  assert.equal(second.entry.pendingMedium, null);
});

test("C16: a pending MEDIUM is discarded when the evidence goes elsewhere", () => {
  const e1 = applyActivity(entry(), resolved(A, "HIGH"), 1);
  const first = applyActivity(e1.entry, resolved(B, "MEDIUM"), 2);
  const other = applyActivity(first.entry, resolved(A, "HIGH"), 3);
  assert.equal(other.activity.switched, false);
  assert.equal(other.entry.pendingMedium, null);
});

test("UNATTRIBUTED rounds keep the current segment for continuity", () => {
  const e1 = applyActivity(entry(), resolved(A, "HIGH"), 1);
  const e2 = applyActivity(e1.entry, { status: "UNATTRIBUTED", claimSource: "none", claimConfidence: "NONE", evidenceIds: [], decidedAt: 2 }, 2);
  assert.equal(e2.activity.switched, false);
  assert.equal(e2.activity.activityId, e1.activity.activityId);
});
