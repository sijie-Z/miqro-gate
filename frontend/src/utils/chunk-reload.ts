/**
 * #663: a portal redeploy replaces every hashed asset, so a tab still running
 * the previous bundle 404s on its next lazy route chunk and the SPA whites
 * out. Recover with a single hard reload that picks up the new index.html;
 * the timestamp guard keeps a genuinely broken server from causing a reload
 * loop — outside the cooldown the original error path still applies.
 */

const LAST_RELOAD_KEY = 'miqro-chunk-reload-at';
const RELOAD_COOLDOWN_MS = 10_000;

/** Browser wordings for a failed dynamic import (Chromium / Firefox / Safari). */
const CHUNK_ERROR_PATTERNS = [
  /failed to fetch dynamically imported module/i,
  /error loading dynamically imported module/i,
  /importing a module script failed/i,
];

export function isChunkLoadError(error: unknown): boolean {
  return (
    error instanceof Error && CHUNK_ERROR_PATTERNS.some((pattern) => pattern.test(error.message))
  );
}

/**
 * Hard-reload once to pick up the post-deploy bundle. Returns false — without
 * touching the location — while inside the cooldown window, and when
 * sessionStorage is unavailable (there the loop guard cannot be persisted, so
 * a reload storm is the worse failure).
 */
export function reloadForChunkError(
  now: number = Date.now(),
  reload: () => void = () => window.location.reload(),
): boolean {
  try {
    const last = Number(sessionStorage.getItem(LAST_RELOAD_KEY) ?? 0);
    if (now - last < RELOAD_COOLDOWN_MS) return false;
    sessionStorage.setItem(LAST_RELOAD_KEY, String(now));
  } catch {
    return false;
  }
  reload();
  return true;
}

/**
 * Wire the recovery into Vite's preload-failure event, fired when a dynamic
 * import's modulepreload fails. Returns an uninstall for tests.
 */
export function installChunkReload(
  reload: () => void = () => window.location.reload(),
): () => void {
  const handler = (event: Event) => {
    if (reloadForChunkError(Date.now(), reload)) event.preventDefault();
  };
  window.addEventListener('vite:preloadError', handler);
  return () => window.removeEventListener('vite:preloadError', handler);
}
