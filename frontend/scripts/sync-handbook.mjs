#!/usr/bin/env node
/**
 * Vendor the user-guide handbook into the frontend (#899).
 *
 * docs/user-guide/ is the single source of truth, but the DEPLOY build context
 * only carries backend/ + frontend/ + deploy/ — a view that glob-imports
 * ../../../../docs/*.md built fine locally and in CI (full checkout) and shipped
 * an EMPTY handbook in production. The console therefore carries its own copy
 * under src/content/handbook/, kept byte-identical by this script; the drift
 * guard (src/__tests__/handbook-sync.spec.ts) fails the suite when the two
 * copies diverge.
 *
 * Usage: npm run sync:handbook   (run after editing docs/user-guide/*.md)
 */
import { copyFileSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(here, '../../docs/user-guide');
const DEST = resolve(here, '../src/content/handbook');

const names = readdirSync(SRC).filter((n) => n.endsWith('.md'));
if (names.length === 0) {
  console.error(`no markdown files found in ${SRC}`);
  process.exit(1);
}

rmSync(DEST, { recursive: true, force: true });
mkdirSync(DEST, { recursive: true });
for (const name of names) {
  copyFileSync(join(SRC, name), join(DEST, name));
}
console.log(`synced ${names.length} handbook files -> src/content/handbook/`);
