import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  autostartPaths,
  buildAutostartEntry,
  enableAutostart,
  disableAutostart,
  autostartInstalled,
  fileNameFor,
} from "../src/install/autostart.js";

function sandboxEnv(): NodeJS.ProcessEnv {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "miqro-autostart-"));
  return { MIQRO_CONTEXT_AUTOSTART_DIR: dir };
}

test("default file names per platform", () => {
  assert.equal(fileNameFor("win32"), "miqro-context.cmd");
  assert.equal(fileNameFor("darwin"), "com.miqro.context.plist");
  assert.equal(fileNameFor("linux"), "miqro-context.service");
});

test("default directories follow the user-level, admin-free locations", () => {
  assert.ok(autostartPaths("win32", {}).filePath.includes("Startup"));
  assert.ok(autostartPaths("darwin", {}).filePath.includes(path.join("Library", "LaunchAgents")));
  assert.ok(autostartPaths("linux", {}).filePath.includes(path.join(".config", "systemd", "user")));
});

test("MIQRO_CONTEXT_AUTOSTART_DIR overrides the target directory", () => {
  const env = sandboxEnv();
  const paths = autostartPaths("linux", env);
  assert.equal(paths.dir, env["MIQRO_CONTEXT_AUTOSTART_DIR"]);
});

test("windows entry invokes node + cli and redirects to the log", () => {
  const entry = buildAutostartEntry({
    platform: "win32",
    env: sandboxEnv(),
    nodePath: "C:/node/node.exe",
    cliPath: "C:/miqro/index.js",
    logPath: "C:/Users/u/.miqro/agent.log",
  });
  assert.ok(entry.content.includes('"C:/node/node.exe" "C:/miqro/index.js" run'));
  assert.ok(entry.content.includes("agent.log"));
  assert.ok(entry.filePath.endsWith("miqro-context.cmd"));
});

test("macOS entry is a LaunchAgent plist with RunAtLoad", () => {
  const entry = buildAutostartEntry({
    platform: "darwin",
    env: sandboxEnv(),
    nodePath: "/usr/local/bin/node",
    cliPath: "/opt/miqro/index.js",
    logPath: "/Users/u/.miqro/agent.log",
  });
  assert.ok(entry.content.includes("<string>com.miqro.context</string>"));
  assert.ok(entry.content.includes("<key>RunAtLoad</key><true/>"));
  assert.ok(entry.content.includes("<string>/opt/miqro/index.js</string>"));
  assert.ok(entry.activateHint.includes("launchctl load"));
});

test("linux entry is a systemd user unit appended to the log", () => {
  const entry = buildAutostartEntry({
    platform: "linux",
    env: sandboxEnv(),
    nodePath: "/usr/bin/node",
    cliPath: "/opt/miqro/index.js",
    logPath: "/home/u/.miqro/agent.log",
  });
  assert.ok(entry.content.includes("ExecStart=/usr/bin/node /opt/miqro/index.js run"));
  assert.ok(entry.content.includes("WantedBy=default.target"));
  assert.ok(entry.activateHint.includes("systemctl --user enable"));
});

test("enable → installed → disable roundtrip is idempotent and sandboxed", () => {
  const env = sandboxEnv();
  const entry = buildAutostartEntry({
    platform: "linux",
    env,
    nodePath: "/usr/bin/node",
    cliPath: "/opt/miqro/index.js",
    logPath: "/home/u/.miqro/agent.log",
  });

  assert.equal(autostartInstalled("linux", env), false);
  enableAutostart(entry);
  assert.equal(autostartInstalled("linux", env), true);
  assert.equal(fs.readFileSync(entry.filePath, "utf8"), entry.content);

  // Idempotent re-enable overwrites identically.
  enableAutostart(entry);
  assert.equal(fs.readFileSync(entry.filePath, "utf8"), entry.content);

  assert.equal(disableAutostart("linux", env), true);
  assert.equal(autostartInstalled("linux", env), false);
  // Removing again is a silent no-op.
  assert.equal(disableAutostart("linux", env), false);
});
