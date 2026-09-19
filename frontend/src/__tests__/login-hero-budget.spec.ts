/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync, readdirSync } from 'node:fs';

/**
 * Shipped bitmap byte budget.
 *
 * /login is the only route an unauthenticated visitor can reach, so its hero
 * artwork is downloaded by every first-time visitor before any session exists.
 * Vite emits any asset above the 4 KiB inline threshold verbatim, so the file
 * on disk IS the transfer size — and the 686,623 B master PNG that shipped with
 * the auth-ui port made that page's hero the single largest byte cost on the
 * login path (larger than the 148.82 kB entry chunk). It now ships as a
 * 35,530 B WebP.
 *
 * The guard is deliberately path- and syntax-independent: it does not parse
 * import statements, because a guard that only understands one import spelling
 * is bypassed by spelling the import differently (relative path, double quotes,
 * `url()` in a <style> block, `new URL(..., import.meta.url)`). It instead takes
 * every bitmap file that exists in the shipped tree and asks one question: is
 * this file's *name* mentioned anywhere in the source? If it is, the asset is
 * reachable by the bundler and its bytes are on the wire. A bitmap that no
 * source file mentions (the PNG master kept for re-exports) never reaches the
 * bundle and is therefore exempt by construction — no allow-list to maintain.
 *
 * Budget, not a target: 128 KiB leaves room to re-export a richer source
 * without silently re-opening the 686 KB regression this guards.
 */
const BUDGET_BYTES = 128 * 1024;
const BITMAP = /\.(png|jpe?g|gif|webp|avif|bmp)$/i;
const SOURCE = /\.(vue|ts|tsx|js|jsx|mjs|cjs|css|scss|sass|less|html)$/i;
/** This file names bitmaps only in prose; it must not count as a reference. */
const SELF = 'login-hero-budget.spec.ts';

/** Every file below `root`, recursively, as POSIX-style relative paths. */
function walk(root: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const child = `${root}/${entry.name}`;
    if (entry.isDirectory()) found.push(...walk(child));
    else found.push(child);
  }
  return found;
}

const baseName = (path: string): string => path.slice(path.lastIndexOf('/') + 1);

/** Text the bundler reads: everything under src/, plus the HTML entry. */
function sources(): string[] {
  const paths = walk('src').filter((path) => SOURCE.test(path) && !path.endsWith(SELF));
  if (existsSync('index.html')) paths.push('index.html');
  return paths;
}

describe('shipped bitmap byte budget', () => {
  const sourceText = sources().map((path) => readFileSync(path, 'utf-8'));

  /** Bitmaps under `dir` that some source file names, i.e. that ship. */
  function referenced(dir: string): { path: string; bytes: number }[] {
    return walk(dir)
      .filter((path) => BITMAP.test(path))
      .map((path) => ({ path, bytes: readFileSync(path).byteLength }))
      .filter((asset) => sourceText.some((text) => text.includes(baseName(asset.path))));
  }

  const oversized = (assets: { path: string; bytes: number }[]): string =>
    assets
      .map((asset) => `${asset.path} ${asset.bytes} B`)
      .join(', ')
      .concat(` (budget ${BUDGET_BYTES} B)`);

  it('keeps every referenced bitmap under src/ within the byte budget', () => {
    const offenders = referenced('src').filter((asset) => asset.bytes > BUDGET_BYTES);
    expect(offenders.length, oversized(offenders)).toBe(0);
  });

  it('keeps every bitmap under public/ within the byte budget', () => {
    // public/ is copied verbatim and needs no import to reach the browser.
    const offenders = walk('public')
      .filter((path) => BITMAP.test(path))
      .map((path) => ({ path, bytes: readFileSync(path).byteLength }))
      .filter((asset) => asset.bytes > BUDGET_BYTES);
    expect(offenders.length, oversized(offenders)).toBe(0);
  });

  it('resolves the login artwork, so the guard cannot pass vacuously', () => {
    const assets = referenced('src');
    expect(
      assets.length,
      'no bitmap under src/ is referenced by any source file — the reference ' +
        'scan is broken, not the artwork',
    ).toBeGreaterThan(0);
  });
});
