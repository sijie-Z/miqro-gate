/**
 * Per-conversation state (CAA Spec v1.1 §3.2/§5.4): watermark, the stable
 * session context that new turns inherit, and the ActivitySegment switch with
 * hysteresis.
 *
 * Segment rules (§5.4): boundaries happen ONLY when the resolved project
 * changes; HIGH evidence switches immediately; a MEDIUM-only change must be
 * seen twice in a row (first round records pending and does NOT switch).
 * A→B→A with HIGH evidence produces three real segments — work is recorded as
 * it happened, no artificial merging.
 */
import { randomUUID } from "node:crypto";
import type { ConversationWatermark } from "../collector/delta.js";
import type { AttributionDecision } from "../resolver/evidence.js";

export type SessionEntry = {
  watermark: ConversationWatermark;
  /** Stable context new turns inherit when they carry no signal of their own. */
  inherited?: { projectId: string; projectTag?: string };
  lastDecision: AttributionDecision;
  activity: { activityId: string; projectId: string; since: number } | null;
  /** MEDIUM-only change waiting for its second consecutive confirmation. */
  pendingMedium: { projectId: string; projectTag?: string; count: number } | null;
};

const MAX_SESSIONS = 500;

export class SessionStore {
  private sessions = new Map<string, SessionEntry>();

  get(conversationKey: string): SessionEntry | undefined {
    return this.sessions.get(conversationKey);
  }

  put(conversationKey: string, entry: SessionEntry): void {
    // Refresh insertion order for LRU-style eviction.
    this.sessions.delete(conversationKey);
    this.sessions.set(conversationKey, entry);
    while (this.sessions.size > MAX_SESSIONS) {
      const oldest = this.sessions.keys().next().value;
      if (oldest === undefined) break;
      this.sessions.delete(oldest);
    }
  }

  clear(): void {
    this.sessions.clear();
  }

  size(): number {
    return this.sessions.size;
  }
}
