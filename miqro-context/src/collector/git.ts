/**
 * Local-only git introspection (CAA Spec v1.1 §5.2): path → git root →
 * remote → canonical repo key. Read-only; every failure degrades gracefully
 * (the evidence simply carries no repo key).
 */
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { repoKeyOf } from "./url.js";

const run = promisify(execFile);

export type GitInfo = {
  root: string;
  remoteUrl: string | null;
  /** Canonical `github.com/{owner}/{repo}` when the remote is a github URL. */
  repoKey: string | null;
};

const TIMEOUT_MS = 2_000;
const cache = new Map<string, GitInfo | null>();

/** Normalize a git remote URL to a canonical repo key, or null. */
export function remoteToRepoKey(remoteUrl: string | null | undefined): string | null {
  if (!remoteUrl) return null;
  const url = remoteUrl.trim();
  const ssh = /^git@([^:]+):([^/]+)\/(.+?)(?:\.git)?$/.exec(url);
  if (ssh?.[1] && ssh[2] && ssh[3]) {
    return ssh[1].toLowerCase() === "github.com" ? repoKeyOf(ssh[2], ssh[3]) : null;
  }
  const https = /^https?:\/\/(?:[^@/]+@)?([^/]+)\/([^/]+)\/(.+?)(?:\.git)?\/?$/.exec(url);
  if (https?.[1] && https[2] && https[3]) {
    return https[1].toLowerCase() === "github.com" ? repoKeyOf(https[2], https[3]) : null;
  }
  return null;
}

async function git(args: string[], cwd: string): Promise<string | null> {
  try {
    const { stdout } = await run("git", args, { cwd, timeout: TIMEOUT_MS, windowsHide: true });
    const out = stdout.trim();
    return out.length > 0 ? out : null;
  } catch {
    return null;
  }
}

/**
 * Resolve a filesystem path to its git context. Returns null when the path
 * does not exist or is not inside a git work tree. Results (including
 * negative ones) are cached for the process lifetime.
 */
export async function resolveGit(path: string): Promise<GitInfo | null> {
  const hit = cache.get(path);
  if (hit !== undefined) return hit;
  let info: GitInfo | null = null;
  try {
    const root = await git(["rev-parse", "--show-toplevel"], path);
    if (root) {
      let remoteUrl = await git(["remote", "get-url", "origin"], root);
      if (!remoteUrl) {
        remoteUrl = await git(["config", "--get", "remote.origin.url"], root);
      }
      info = { root, remoteUrl, repoKey: remoteToRepoKey(remoteUrl) };
    }
  } catch {
    info = null;
  }
  cache.set(path, info);
  return info;
}

/** Test seam: drop the cache. */
export function clearGitCache(): void {
  cache.clear();
}
