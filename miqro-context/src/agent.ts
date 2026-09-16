/**
 * ContextAgent — the per-request pipeline (CAA Spec v1.1 §3.2):
 *
 *   body → TurnDelta → TurnEvidence → decision (scope+group+conflict)
 *        → ActivitySegment (hysteresis) → claim headers
 *
 * Pure in-process logic, no I/O except read-only git; callable directly from
 * tests (no HTTP needed).
 */
import { randomUUID } from "node:crypto";
import { computeTurnDelta, conversationKeyOf } from "./collector/delta.js";
import { extractTurnEvidence, systemTextOf } from "./collector/body.js";
import { decide } from "./resolver/decision.js";
import { Registry } from "./resolver/registry.js";
import type { AttributionDecision, Evidence } from "./resolver/evidence.js";
import { SessionStore, type SessionEntry } from "./state/session.js";
import { applyActivity } from "./state/activity.js";

export type AgentOutcome = {
  conversationKey: string;
  decision: AttributionDecision;
  evidence: Evidence[];
  switched: boolean;
  /** True when the conversation had no watermark before this request. */
  newConversation: boolean;
};

export type RecentDecision = {
  at: number;
  requestId: string;
  status: AttributionDecision["status"];
  projectTag?: string;
  claimSource: AttributionDecision["claimSource"];
  claimConfidence: AttributionDecision["claimConfidence"];
  inherited?: boolean;
  evidenceCount: number;
};

type Message = { role?: string; content?: unknown };

const MAX_RECENT = 20;

export class ContextAgent {
  readonly registry = new Registry();
  readonly sessions = new SessionStore();
  private recent: RecentDecision[] = [];
  private virtualKey: string | null = null;

  constructor(private readonly now: () => number = Date.now) {}

  /** Remember the bearer token the client uses (for registry sync only). */
  captureAuthorization(header: string | string[] | undefined): void {
    const value = Array.isArray(header) ? header[0] : header;
    if (!value) return;
    const m = /^Bearer\s+(mqk_live_\S+)$/i.exec(value.trim());
    if (m?.[1]) {
      this.virtualKey = m[1];
    }
  }

  /** The virtual key seen so far (config value wins when provided). */
  keyForRegistry(configKey?: string): string | null {
    return configKey ?? this.virtualKey;
  }

  recentDecisions(): RecentDecision[] {
    return [...this.recent];
  }

  /**
   * Process one parsed request body. Returns null when the body is not an
   * Anthropic/OpenAI style chat payload (no `messages` array) — such requests
   * are forwarded without claims.
   */
  async processJson(body: unknown): Promise<AgentOutcome | null> {
    if (!body || typeof body !== "object") return null;
    const record = body as Record<string, unknown>;
    const messages = record["messages"];
    if (!Array.isArray(messages)) return null;

    const now = this.now();
    const requestId = randomUUID();
    const systemText = systemTextOf(record["system"]);
    const existing = this.sessions.get(conversationKeyFor(systemText, messages as Message[]));

    const delta = computeTurnDelta(systemText, messages as Message[], existing?.watermark);
    const evidence = await extractTurnEvidence(systemText, delta.messages, { requestId, now });

    const base = existing ?? emptyEntry(delta.nextWatermark);
    const decisionBase = decide(evidence, this.registry, base.inherited, now);
    const withActivity: AttributionDecision = { ...decisionBase, activityId: undefined };
    const { entry, activity } = applyActivity(
      { ...base, watermark: delta.nextWatermark },
      withActivity,
      now,
    );
    const decision: AttributionDecision = { ...withActivity, activityId: activity.activityId };

    entry.lastDecision = decision;
    this.sessions.put(delta.conversationKey, entry);
    this.remember({
      at: now,
      requestId,
      status: decision.status,
      projectTag: decision.projectTag,
      claimSource: decision.claimSource,
      claimConfidence: decision.claimConfidence,
      inherited: decision.inherited,
      evidenceCount: evidence.length,
    });

    return {
      conversationKey: delta.conversationKey,
      decision,
      evidence,
      switched: activity.switched,
      newConversation: delta.isNewConversation || existing === undefined,
    };
  }

  private remember(entry: RecentDecision): void {
    this.recent.push(entry);
    while (this.recent.length > MAX_RECENT) this.recent.shift();
  }
}

function emptyEntry(watermark: SessionEntry["watermark"]): SessionEntry {
  return {
    watermark,
    inherited: undefined,
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

function conversationKeyFor(systemText: string, messages: Message[]): string {
  return conversationKeyOf(systemText, messages);
}
