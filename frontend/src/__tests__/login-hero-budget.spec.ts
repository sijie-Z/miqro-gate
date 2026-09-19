/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { readFileSync, statSync } from 'node:fs';

/**
 * Login first-paint artwork budget.
 *
 * /login is the only route an unauthenticated visitor can reach, so the hero
 * artwork is downloaded by every first-time visitor before any session exists.
 * Vite emits any asset above the 4 KiB inline threshold verbatim, so the file
 * on disk IS the transfer size — and the 686 KB master PNG that shipped with
 * the auth-ui port was the single largest byte cost on that path.
 *
 * Budget, not a target: the artwork ships at 35,530 B after the WebP re-encode,
 * down from the 686,623 B PNG. 128 KiB leaves room to re-export a richer source
 * without silently re-opening the 686 KB regression this guards.
 */
const BUDGET_BYTES = 128 * 1024;
const VIEW = 'src/views/next/NextLoginView.vue';

/** Bitmaps the login view imports, resolved from its own import statements. */
function importedArtwork(): { path: string; bytes: number }[] {
  const source = readFileSync(VIEW, 'utf-8');
  const imports = [...source.matchAll(/^import\s+\w+\s+from\s+'(@\/assets\/[^']+)';$/gm)];
  expect(imports.length, `${VIEW} imports no @/assets bitmap`).toBeGreaterThan(0);
  return imports.map((match) => {
    const path = match[1]!.replace(/^@\//, 'src/');
    return { path, bytes: statSync(path).size };
  });
}

describe('login hero first-paint budget', () => {
  it('keeps the login artwork within the first-paint byte budget', () => {
    const assets = importedArtwork();
    const total = assets.reduce((sum, asset) => sum + asset.bytes, 0);
    const detail = assets.map((a) => `${a.path} ${a.bytes} B`).join(', ');
    expect(total, `${detail} (total ${total} B, budget ${BUDGET_BYTES} B)`).toBeLessThanOrEqual(
      BUDGET_BYTES,
    );
  });
});
