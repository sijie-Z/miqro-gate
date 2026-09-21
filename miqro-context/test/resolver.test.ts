import { test } from "node:test";
import assert from "node:assert/strict";
import { decide } from "../src/resolver/decision.js";
import { Registry } from "../src/resolver/registry.js";
import type { Evidence } from "../src/resolver/evidence.js";

const A = "11111111-1111-1111-1111-111111111111";
const B = "22222222-2222-2222-2222-222222222222";

function registry(): Registry {
  const r = new Registry();
  r.load([
    { repoKey: "github.com/acme/repo-a", projectId: A, projectTag: "repo-a" },
    { repoKey: "github.com/acme/repo-b", projectId: B, projectTag: "repo-b" },
  ]);
  return r;
}

let seq = 0;
function ev(partial: Partial<Evidence>): Evidence {
  return {
    id: `e${seq++}`,
    source: "prompt_url",
    value: partial.repoKey ?? "x",
    confidence: "HIGH",
    scope: "turn",
    observedAt: 1,
    requestId: "r1",
    ...partial,
  };
}

test("C5: no system signal + a single URL group → RESOLVED B", () => {
  const d = decide([ev({ repoKey: "github.com/acme/repo-b", source: "prompt_url" })], registry(), undefined, 1);
  assert.equal(d.status, "RESOLVED");
  assert.equal(d.projectId, B);
  assert.equal(d.claimSource, "prompt_url");
  assert.equal(d.claimConfidence, "HIGH");
});

test("C6: system_cwd repo A (HIGH) + URL repo B (HIGH) → AMBIGUOUS with both groups", () => {
  const d = decide(
    [
      ev({ repoKey: "github.com/acme/repo-a", source: "system_cwd" }),
      ev({ repoKey: "github.com/acme/repo-b", source: "prompt_url" }),
    ],
    registry(),
    undefined,
    1,
  );
  assert.equal(d.status, "AMBIGUOUS");
  assert.equal(d.projectId, undefined);
  assert.equal(d.conflicts?.length, 2);
  assert.deepEqual(
    d.conflicts?.map((c) => c.projectId).sort(),
    [A, B].sort(),
  );
});

test("C7: no evidence at all → UNATTRIBUTED", () => {
  const d = decide([], registry(), undefined, 1);
  assert.equal(d.status, "UNATTRIBUTED");
  assert.equal(d.claimConfidence, "NONE");
});

test("no evidence but a session context exists → inherited RESOLVED", () => {
  const d = decide([], registry(), { projectId: A, projectTag: "repo-a" }, 1);
  assert.equal(d.status, "RESOLVED");
  assert.equal(d.projectId, A);
  assert.equal(d.inherited, true);
  assert.equal(d.claimConfidence, "NONE");
});

test("a single MEDIUM group resolves with MEDIUM confidence", () => {
  const d = decide(
    [ev({ repoKey: "github.com/acme/repo-b", source: "bash_cwd", confidence: "MEDIUM" })],
    registry(),
    undefined,
    1,
  );
  assert.equal(d.status, "RESOLVED");
  assert.equal(d.projectId, B);
  assert.equal(d.claimConfidence, "MEDIUM");
  assert.equal(d.claimSource, "bash_cwd");
});

test("two MEDIUM groups → AMBIGUOUS (no scalar scoring)", () => {
  const d = decide(
    [
      ev({ repoKey: "github.com/acme/repo-a", source: "bash_cwd", confidence: "MEDIUM" }),
      ev({ repoKey: "github.com/acme/repo-b", source: "bash_cwd", confidence: "MEDIUM" }),
    ],
    registry(),
    undefined,
    1,
  );
  assert.equal(d.status, "AMBIGUOUS");
});

test("HIGH beats MEDIUM: one HIGH group + one MEDIUM group → RESOLVED (HIGH)", () => {
  const d = decide(
    [
      ev({ repoKey: "github.com/acme/repo-a", source: "system_cwd", confidence: "HIGH" }),
      ev({ repoKey: "github.com/acme/repo-b", source: "bash_cwd", confidence: "MEDIUM" }),
    ],
    registry(),
    undefined,
    1,
  );
  assert.equal(d.status, "RESOLVED");
  assert.equal(d.projectId, A);
  assert.equal(d.claimConfidence, "HIGH");
});

test("evidence whose repo is not registered is never a project signal (no guessing)", () => {
  const d = decide([ev({ repoKey: "github.com/unknown/thing" })], registry(), undefined, 1);
  assert.equal(d.status, "UNATTRIBUTED");
});

test("evidence without a repo key (bare directory) cannot resolve a project", () => {
  const d = decide(
    [ev({ repoKey: undefined, value: "/home/u/notes", source: "system_cwd", confidence: "MEDIUM" })],
    registry(),
    undefined,
    1,
  );
  assert.equal(d.status, "UNATTRIBUTED");
});

test("two URLs to the same repo are one group, not a conflict", () => {
  const d = decide(
    [
      ev({ repoKey: "github.com/acme/repo-b", source: "prompt_url" }),
      ev({ repoKey: "github.com/acme/repo-b", source: "tool_path" }),
    ],
    registry(),
    undefined,
    1,
  );
  assert.equal(d.status, "RESOLVED");
  assert.equal(d.projectId, B);
});
