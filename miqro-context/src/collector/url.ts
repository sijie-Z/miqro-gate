/**
 * GitHub URL → repository key extraction (CAA Spec v1.1 §5.2, prompt_url).
 *
 * Only explicit repository-scoped URLs count: a PR / issue / repo link pins
 * the turn to one repository. Free-form text ("看了下 github 上的代码") is
 * deliberately NOT guessed at — the resolver must never guess (spec §5.3).
 */

const URL_RE =
  /https?:\/\/(?:www\.)?github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+?)(?:\.git)?(?:\/(?:pull|issues|pulls|tree|blob|commit|releases|actions|compare)\/\S*)?(?=[\s)>\]"'`,;]|$)/g;

/** Normalize `owner/repo` into the canonical repo key. */
export function repoKeyOf(owner: string, repo: string): string {
  return `github.com/${owner}/${repo}`.toLowerCase();
}

/**
 * Extract github repository keys from a text block. Returns canonical
 * `github.com/{owner}/{repo}` keys, deduplicated, in first-seen order.
 */
export function extractRepoKeys(text: string): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const m of text.matchAll(URL_RE)) {
    const owner = m[1];
    const repo = m[2];
    if (!owner || !repo) continue;
    // Skip platform paths that are not repositories.
    if (["orgs", "settings", "marketplace", "sponsors", "features"].includes(owner.toLowerCase())) continue;
    const key = repoKeyOf(owner, repo);
    if (!seen.has(key)) {
      seen.add(key);
      out.push(key);
    }
  }
  return out;
}
