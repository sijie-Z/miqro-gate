/**
 * The local agent endpoint (CAA Spec v1.1 §3.1/§3.4): 127.0.0.1 only,
 * default port 8788. Two routes:
 *
 *   GET /__miqro/status   local control plane for `status`/`doctor` (never
 *                         forwarded upstream)
 *   *                     everything else: evidence → decision → claims →
 *                         byte-exact forward (SSE streams chunk by chunk)
 *
 * Debug logging never prints message content (spec §3.4 privacy).
 */
import http from "node:http";
import type { IncomingMessage, ServerResponse } from "node:http";
import { ContextAgent } from "../agent.js";
import type { ContextConfig } from "../config.js";
import { claimHeaders, stripMiqroHeaders } from "./inject.js";
import { forward } from "./forward.js";

const MAX_BODY_BYTES = 32 * 1024 * 1024;

export type ServerDeps = {
  config: ContextConfig;
  agent: ContextAgent;
  log?: (line: string) => void;
  startedAt?: number;
  /** Fired once when the client's virtual key is first learned from traffic. */
  onKeyLearned?: () => Promise<void> | void;
};

export function createAgentServer(deps: ServerDeps): http.Server {
  const { config, agent } = deps;
  const log = deps.log ?? (() => {});
  const startedAt = deps.startedAt ?? Date.now();

  return http.createServer((req: IncomingMessage, res: ServerResponse) => {
    void handle(req, res).catch((err) => {
      log(`agent error: ${err instanceof Error ? err.message : String(err)}`);
      if (!res.headersSent) res.writeHead(500, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "agent_internal_error" }));
    });
  });

  async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    if (req.method === "GET" && req.url === "/__miqro/status") {
      const sync = agent.registry.lastSync();
      res.writeHead(200, { "content-type": "application/json" });
      res.end(
        JSON.stringify({
          version: 1,
          uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
          gatewayBaseUrl: config.gatewayBaseUrl,
          sessions: agent.sessions.size(),
          registry: { entries: agent.registry.size(), fetchedAt: sync.fetchedAt, error: sync.error },
          recent: agent.recentDecisions(),
        }),
      );
      return;
    }

    const body = await readBody(req, res);
    if (body === null) return; // 413 already written

    // The key for registry sync rides the client's own Authorization; sync as
    // soon as it is first learned (the periodic timer covers the rest).
    if (agent.captureAuthorization(req.headers["authorization"]) && deps.onKeyLearned) {
      void deps.onKeyLearned();
    }

    const headers = stripMiqroHeaders(req.headers);
    let claims: Record<string, string> = {};
    const contentType = String(req.headers["content-type"] ?? "");
    if (body.length > 0 && contentType.includes("application/json") && (req.method === "POST" || req.method === "PUT")) {
      let parsed: unknown = null;
      try {
        parsed = JSON.parse(body.toString("utf8"));
      } catch {
        parsed = null;
      }
      if (parsed !== null) {
        const outcome = await agent.processJson(parsed);
        if (outcome) {
          claims = claimHeaders(outcome.decision);
          log(
            `[${new Date().toISOString()}] ${outcome.decision.status}` +
              `${outcome.decision.projectTag ? ` → ${outcome.decision.projectTag}` : ""}` +
              ` (${outcome.decision.claimSource}/${outcome.decision.claimConfidence}` +
              `${outcome.decision.inherited ? ", inherited" : ""}, ${outcome.evidence.length} evidence` +
              `${outcome.newConversation ? ", new conversation" : ""})`,
          );
        }
      }
    }

    await forward(
      {
        targetBaseUrl: config.gatewayBaseUrl,
        method: req.method ?? "GET",
        url: req.url ?? "/",
        headers: { ...headers, ...claims },
        body,
      },
      res,
      req,
    );
  }
}

function readBody(req: IncomingMessage, res: ServerResponse): Promise<Buffer | null> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        res.writeHead(413, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: { type: "payload_too_large", message: "request body exceeds the agent buffer" } }));
        req.destroy();
        resolve(null);
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", () => resolve(Buffer.alloc(0)));
  });
}
