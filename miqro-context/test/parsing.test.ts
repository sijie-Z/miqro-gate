import { test } from "node:test";
import assert from "node:assert/strict";
import { extractRepoKeys } from "../src/collector/url.js";
import { remoteToRepoKey } from "../src/collector/git.js";
import { pathsFromCommand } from "../src/collector/body.js";
import { claimHeaders, stripMiqroHeaders } from "../src/proxy/inject.js";

test("github PR / issue / repo URLs extract the canonical repo key", () => {
  assert.deepEqual(extractRepoKeys("https://github.com/acme/repo-a/pull/42"), ["github.com/acme/repo-a"]);
  assert.deepEqual(extractRepoKeys("see https://github.com/acme/repo-b/issues/7 please"), ["github.com/acme/repo-b"]);
  assert.deepEqual(extractRepoKeys("https://github.com/acme/repo-c"), ["github.com/acme/repo-c"]);
  assert.deepEqual(extractRepoKeys("git clone https://github.com/acme/repo-d.git"), ["github.com/acme/repo-d"]);
});

test("non-repository and non-github URLs are ignored", () => {
  assert.deepEqual(extractRepoKeys("https://github.com/orgs/acme/repositories"), []);
  assert.deepEqual(extractRepoKeys("https://gitlab.com/acme/repo/pull/1"), []);
  assert.deepEqual(extractRepoKeys("no urls here"), []);
});

test("duplicate URLs collapse to one repo key", () => {
  assert.deepEqual(
    extractRepoKeys("https://github.com/acme/x/pull/1 and https://github.com/acme/x/pull/2"),
    ["github.com/acme/x"],
  );
});

test("git remotes normalize (ssh, https, .git)", () => {
  assert.equal(remoteToRepoKey("git@github.com:acme/rocket.git"), "github.com/acme/rocket");
  assert.equal(remoteToRepoKey("https://github.com/acme/rocket.git"), "github.com/acme/rocket");
  assert.equal(remoteToRepoKey("https://github.com/acme/rocket"), "github.com/acme/rocket");
  assert.equal(remoteToRepoKey("git@gitlab.com:acme/rocket.git"), null);
  assert.equal(remoteToRepoKey(""), null);
  assert.equal(remoteToRepoKey(null), null);
});

test("bash command path extraction (cd and absolute paths)", () => {
  const paths = pathsFromCommand('cd "/home/u/work/proj" && ls && cd ../other');
  assert.ok(paths.includes("/home/u/work/proj"));
  assert.ok(paths.includes("../other"));
  const win = pathsFromCommand("cd D:\\repos\\demo && git status");
  assert.ok(win.includes("D:\\repos\\demo"));
});

test("claim headers: RESOLVED injects the project; AMBIGUOUS never does", () => {
  const resolved = claimHeaders({
    status: "RESOLVED",
    projectId: "11111111-1111-1111-1111-111111111111",
    activityId: "22222222-2222-2222-2222-222222222222",
    claimSource: "system_cwd",
    claimConfidence: "HIGH",
    evidenceIds: [],
    decidedAt: 1,
  });
  assert.equal(resolved["X-Miqro-Project-Id"], "11111111-1111-1111-1111-111111111111");
  assert.equal(resolved["X-Miqro-Activity"], "22222222-2222-2222-2222-222222222222");
  assert.equal(resolved["X-Miqro-Claim-Status"], "RESOLVED");

  const ambiguous = claimHeaders({
    status: "AMBIGUOUS",
    claimSource: "none",
    claimConfidence: "NONE",
    evidenceIds: [],
    decidedAt: 1,
  });
  assert.equal(ambiguous["X-Miqro-Project-Id"], undefined);
  assert.equal(ambiguous["X-Miqro-Claim-Status"], "AMBIGUOUS");
});

test("stripMiqroHeaders removes client-forged x-miqro-* and x-miqrokey-*", () => {
  const headers = stripMiqroHeaders({
    authorization: "Bearer mqk_live_x",
    "X-Miqro-Project-Id": "forged",
    "x-miqrokey-thing": "forged",
    "x-claude-code-session-id": "sess-1",
    "content-type": "application/json",
  });
  assert.equal(headers["X-Miqro-Project-Id"], undefined);
  assert.equal(headers["x-miqrokey-thing"], undefined);
  assert.equal(headers["x-claude-code-session-id"], "sess-1");
  assert.equal(headers["authorization"], "Bearer mqk_live_x");
});
