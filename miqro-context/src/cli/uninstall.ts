/**
 * `miqro-context uninstall` — remove the login autostart entry (#648).
 * Idempotent: silent success when nothing is installed. Never touches Claude
 * Code settings or the agent config (those are the user's).
 */
import { autostartPaths, disableAutostart, autostartInstalled } from "../install/autostart.js";

export async function uninstallCommand(): Promise<void> {
  if (!process.argv.includes("--autostart")) {
    process.stdout.write(
      "nothing to do: pass --autostart to remove the login autostart entry\n" +
        "(the agent config and Claude Code settings are never touched by uninstall)\n",
    );
    return;
  }
  const paths = autostartPaths(process.platform);
  if (!autostartInstalled(process.platform)) {
    process.stdout.write(`autostart: not installed (${paths.filePath})\n`);
    return;
  }
  disableAutostart(process.platform);
  const hint =
    process.platform === "darwin"
      ? `run: launchctl unload ${paths.filePath} (if it was loaded)`
      : process.platform === "linux"
        ? "run: systemctl --user daemon-reload (if it was enabled)"
        : "the entry is gone; it will not run at next login";
  process.stdout.write(`autostart removed: ${paths.filePath}\n  ${hint}\n`);
}
