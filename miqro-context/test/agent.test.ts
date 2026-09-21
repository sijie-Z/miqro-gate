import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { ContextAgent } from "../src/agent.js";

const A = "aaaaaaaa-0000-0000-0000-000000000001";
const B = "bbbbbbbb-0000-0000-0000-000000000002";

function agentWithRegistry(): ContextAgent {
  const agent = new ContextAgent(() => 1_000);
  agent.registry.load([
    { repoKey: "github.com/acme/repo-a", projectId: A, projectTag: "repo-a" },
    { repoKey: "github.com/acme/repo-b", projectId: B, projectTag: "repo-b" },
  ]);
  return agent;
}

function userText(text: string) {
  return { role: "user", content: [{ type: "text", text }] };
}
function toolUse(pathValue: string) {
  return { role: "assistant", content: [{ type: "tool_use", name: "Read", input: { file_path: pathValue } }] };
}
function toolResult(text: string) {
  return { role: "user", content: [{ type: "tool_result", tool_use_id: "t1", content: text }] };
}

/** A git work tree with an origin remote (used for system_cwd evidence). */
function makeRepo(dirName: string, remote: string): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `miqro-ctx-${dirName}-`));
  try {
    execFileSync("git", ["init", "-q"], { cwd: dir });
    execFileSync("git", ["remote", "add", "origin", remote], { cwd: dir });
  } catch {
    // git unavailable — the test degrades to the MEDIUM directory-name path
  }
  return dir;
}

test("C15 (R1): stale tool history about repo A never pollutes a turn about repo B", async () => {
  const agent = agentWithRegistry();
  const fileA = makeRepo("repo-a", "git@github.com:acme/repo-a.git");
  if (!fs.existsSync(path.join(fileA, ".git"))) return; // git unavailable
  const first = await agent.processJson({
    model: "x",
    messages: [userText("start work"), toolUse(fileA), toolResult("repo A file contents")],
  });
  assert.equal(first?.decision.projectId, A, "first turn attributes to repo A");

  // Second turn: SAME conversation, tool history about repo A still in the
  // body, but the new user message is about repo B's PR.
  const second = await agent.processJson({
    model: "x",
    messages: [
      userText("start work"),
      toolUse(fileA),
      toolResult("repo A file contents"),
      { role: "assistant", content: [{ type: "text", text: "done" }] },
      userText("now review https://github.com/acme/repo-b/pull/9"),
    ],
  });
  assert.equal(second?.decision.status, "RESOLVED", "no AMBIGUOUS from stale history");
  assert.equal(second?.decision.projectId, B);
  assert.equal(second?.decision.claimSource, "prompt_url");
  assert.equal(second?.decision.claimConfidence, "HIGH");
});

test("system_cwd from the request's own system block resolves via a real git remote", async () => {
  const agent = agentWithRegistry();
  const repoB = makeRepo("repo-b", "git@github.com:acme/repo-b.git");
  // Only meaningful when git actually initialized the repo.
  if (!fs.existsSync(path.join(repoB, ".git"))) return;

  const out = await agent.processJson({
    system: [{ type: "text", text: `You are Claude Code.\nWorking directory: ${repoB}\n` }],
    model: "x",
    messages: [userText("fix the failing test")],
  });
  assert.equal(out?.decision.status, "RESOLVED");
  assert.equal(out?.decision.projectId, B);
  assert.equal(out?.decision.claimSource, "system_cwd");
  assert.equal(out?.decision.claimConfidence, "HIGH");
});

test("conflicting HIGH signals yield AMBIGUOUS and no project claim", async () => {
  const agent = agentWithRegistry();
  const repoA = makeRepo("conflict-a", "git@github.com:acme/repo-a.git");
  if (!fs.existsSync(path.join(repoA, ".git"))) return;

  const out = await agent.processJson({
    system: [{ type: "text", text: `Working directory: ${repoA}\n` }],
    model: "x",
    messages: [userText("also check https://github.com/acme/repo-b/pull/1")],
  });
  assert.equal(out?.decision.status, "AMBIGUOUS");
  assert.equal(out?.decision.projectId, undefined);
  assert.equal(out?.decision.conflicts?.length, 2);
});

test("session inheritance: a signal-free follow-up keeps the stable project", async () => {
  const agent = agentWithRegistry();
  const repoB = makeRepo("inherit-b", "git@github.com:acme/repo-b.git");
  const base = [
    userText("look at https://github.com/acme/repo-b/pull/3"),
    { role: "assistant", content: [{ type: "text", text: "ok" }] },
  ];
  const first = await agent.processJson({ model: "x", messages: base });
  assert.equal(first?.decision.projectId, B);

  const followUp = await agent.processJson({
    model: "x",
    messages: [...base, userText("thanks, continue")],
  });
  assert.equal(followUp?.decision.status, "RESOLVED");
  assert.equal(followUp?.decision.projectId, B);
  assert.equal(followUp?.decision.inherited, true);

  // The inherited round keeps the same activity segment.
  assert.equal(followUp?.switched, false);
  assert.equal(followUp?.decision.activityId, first?.decision.activityId);
});

test("no registry match and no history: UNATTRIBUTED, no guessing", async () => {
  const agent = agentWithRegistry();
  const out = await agent.processJson({
    model: "x",
    messages: [userText("https://github.com/acme/unknown-repo/pull/1")],
  });
  assert.equal(out?.decision.status, "UNATTRIBUTED");
  assert.equal(out?.decision.projectId, undefined);
});

test("non-chat bodies (no messages array) are not processed", async () => {
  const agent = agentWithRegistry();
  const out = await agent.processJson({ hello: "world" });
  assert.equal(out, null);
});
