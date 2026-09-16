/**
 * Evidence extraction from the current turn (CAA Spec v1.1 §5.2).
 *
 * Inputs are the TurnDelta messages and the current request's own `system`
 * block — never full conversation history (R1). Four sources:
 *
 *   prompt_url  user-message github PR/issue URLs            → HIGH
 *   tool_path   tool_use file_path/notebook_path/path → git  → HIGH
 *   bash_cwd    Bash `cd <path>` / absolute paths → git      → MEDIUM
 *   system_cwd  `Working directory:` → git remote            → HIGH
 *               … non-git directory (name only)              → MEDIUM
 */
import { extractRepoKeys } from "./url.js";
import { resolveGit } from "./git.js";
import type { Evidence } from "../resolver/evidence.js";

type Message = { role?: string; content?: unknown };
type Block = Record<string, unknown>;

export type ExtractOptions = {
  requestId: string;
  now: number;
};

function textBlocksOf(content: unknown): { texts: string[]; toolUses: Block[]; toolResults: string[] } {
  const texts: string[] = [];
  const toolUses: Block[] = [];
  const toolResults: string[] = [];
  if (typeof content === "string") {
    texts.push(content);
  } else if (Array.isArray(content)) {
    for (const block of content) {
      if (!block || typeof block !== "object") continue;
      const b = block as Block;
      if (b["type"] === "text" && typeof b["text"] === "string") texts.push(b["text"]);
      else if (b["type"] === "tool_use") toolUses.push(b);
      else if (b["type"] === "tool_result") {
        const c = b["content"];
        if (typeof c === "string") toolResults.push(c);
        else if (Array.isArray(c)) {
          for (const inner of c) {
            if (inner && typeof inner === "object" && typeof (inner as Block)["text"] === "string") {
              toolResults.push((inner as Block)["text"] as string);
            }
          }
        }
      }
    }
  }
  return { texts, toolUses, toolResults };
}

/** Absolute filesystem path candidates found in a shell command. */
export function pathsFromCommand(command: string): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  // cd <path> (quoted or bare).
  for (const m of command.matchAll(/\bcd\s+(?:"([^"]+)"|'([^']+)'|([^\s;&|]+))/g)) {
    const p = m[1] ?? m[2] ?? m[3];
    if (p && !seen.has(p)) {
      seen.add(p);
      out.push(p);
    }
  }
  // Bare absolute paths (POSIX / or Windows drive).
  for (const m of command.matchAll(/(?:^|[\s"'=(])((?:\/|[A-Za-z]:[\\/])[^\s"'`;&|)]+)/g)) {
    const p = m[1];
    if (p && !seen.has(p)) {
      seen.add(p);
      out.push(p);
    }
  }
  return out;
}

function systemWorkingDirectory(systemText: string): string | null {
  const m = /Working directory:\s*(.+)/.exec(systemText);
  if (!m?.[1]) return null;
  return m[1].trim();
}

function systemTextOf(system: unknown): string {
  if (typeof system === "string") return system;
  if (Array.isArray(system)) {
    const parts: string[] = [];
    for (const block of system) {
      if (block && typeof block === "object") {
        const text = (block as Block)["text"];
        if (typeof text === "string") parts.push(text);
      }
    }
    return parts.join("\n");
  }
  return "";
}

export { systemTextOf };

/**
 * Extract current-turn evidence. Deterministic, sequential, and local:
 * network is never touched; git is read-only and cached.
 */
export async function extractTurnEvidence(
  systemText: string,
  deltaMessages: readonly unknown[],
  opts: ExtractOptions,
): Promise<Evidence[]> {
  const out: Evidence[] = [];
  let seq = 0;
  const push = (e: Omit<Evidence, "id">) => {
    out.push({ id: `${opts.requestId}:${seq++}`, ...e });
  };
  const seenRepoKeys = new Set<string>();
  const pushRepo = (repoKey: string, source: Evidence["source"], value: string, confidence: Evidence["confidence"]) => {
    const dedupe = `${source}:${repoKey}`;
    if (seenRepoKeys.has(dedupe)) return;
    seenRepoKeys.add(dedupe);
    push({ source, value, repoKey, confidence, scope: "turn", observedAt: opts.now, requestId: opts.requestId });
  };

  // system_cwd: the request's own system block always reflects the CURRENT
  // working directory (Claude Code re-renders it every request).
  const cwd = systemWorkingDirectory(systemText);
  if (cwd) {
    const gitInfo = await resolveGit(cwd);
    if (gitInfo?.repoKey) {
      pushRepo(gitInfo.repoKey, "system_cwd", gitInfo.repoKey, "HIGH");
    } else {
      push({
        source: "system_cwd",
        value: cwd,
        confidence: "MEDIUM",
        scope: "turn",
        observedAt: opts.now,
        requestId: opts.requestId,
      });
    }
  }

  // Current-turn messages only.
  for (const message of deltaMessages) {
    if (!message || typeof message !== "object") continue;
    const { texts, toolUses } = textBlocksOf((message as Message).content);
    if ((message as Message).role === "user") {
      for (const text of texts) {
        for (const repoKey of extractRepoKeys(text)) {
          pushRepo(repoKey, "prompt_url", repoKey, "HIGH");
        }
      }
    }
    for (const toolUse of toolUses) {
      const name = typeof toolUse["name"] === "string" ? (toolUse["name"] as string) : "";
      const input = (toolUse["input"] ?? {}) as Block;
      for (const key of ["file_path", "notebook_path", "path"]) {
        const p = input[key];
        if (typeof p === "string" && p.length > 0) {
          const gitInfo = await resolveGit(p);
          if (gitInfo?.repoKey) {
            pushRepo(gitInfo.repoKey, "tool_path", p, "HIGH");
          } else {
            push({
              source: "tool_path",
              value: p,
              confidence: "LOW",
              scope: "turn",
              observedAt: opts.now,
              requestId: opts.requestId,
            });
          }
        }
      }
      if (name === "Bash" && typeof input["command"] === "string") {
        for (const p of pathsFromCommand(input["command"] as string)) {
          const gitInfo = await resolveGit(p);
          if (gitInfo?.repoKey) {
            pushRepo(gitInfo.repoKey, "bash_cwd", p, "MEDIUM");
          }
        }
      }
    }
  }
  return out;
}
