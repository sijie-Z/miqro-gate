#!/usr/bin/env node
/**
 * miqro-context CLI (CAA Spec v1.1 §3.4):
 *   run      start the local agent (foreground)
 *   status   query the running agent's local status endpoint
 *   doctor   environment / connectivity / evidence-chain self-check
 *   install  print the Claude Code two-step setup (never edits your config)
 */
import { runCommand } from "./run.js";
import { statusCommand } from "./status.js";
import { doctorCommand } from "./doctor.js";
import { installCommand } from "./install.js";

const USAGE = `miqro-context <command>

Commands:
  run       Start the local context agent (127.0.0.1:8788 by default)
  status    Show the running agent's recent attribution decisions
  doctor    Self-check config, gateway reachability, key, registry, git
  install   Print the Claude Code setup steps (does not modify your files)

Config: ~/.miqro/context.json (or MIQRO_CONTEXT_CONFIG)
Env:    MIQRO_CONTEXT_GATEWAY_URL, MIQRO_CONTEXT_PORT, MIQRO_CONTEXT_VIRTUAL_KEY
`;

async function main(): Promise<void> {
  const command = process.argv[2];
  switch (command) {
    case "run":
      await runCommand();
      return;
    case "status":
      await statusCommand();
      return;
    case "doctor":
      await doctorCommand();
      return;
    case "install":
      await installCommand();
      return;
    case "--help":
    case "-h":
    case undefined:
      process.stdout.write(USAGE);
      return;
    default:
      process.stderr.write(`unknown command: ${command}\n\n${USAGE}`);
      process.exitCode = 2;
  }
}

void main();
