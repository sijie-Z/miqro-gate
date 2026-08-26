import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { globSync } from 'node:fs';

/**
 * Aesthetic audit (frontend-design.md §9): the compiled CSS must not contain
 * the explicitly forbidden "AI 味" patterns — gradients, oversized radii on
 * regular containers, or purple tokens. This scans the source CSS; the built
 * bundle inherits the same rules.
 */
describe('aesthetic audit', () => {
  const cssFiles = globSync('src/styles/*.css');
  const css = cssFiles.map((f) => readFileSync(f, 'utf-8')).join('\n');

  it('contains no gradients anywhere', () => {
    expect(css).not.toMatch(/linear-gradient|radial-gradient|conic-gradient/);
  });

  it('contains no purple brand tokens', () => {
    // Explicitly forbidden palette entries (any case).
    expect(css).not.toMatch(
      /#7c3aed|#8b5cf6|#a855f7|#6d28d9|#9333ea|#c026d3|#d946ef|#a21caf|purple/i,
    );
  });

  it('never exceeds 8px radius on regular containers (modal and the status pill are the exceptions)', () => {
    // Split into rule blocks so the sanctioned .mk-status pill radius (spec
    // §3: pill only for short status labels) is not treated as a container.
    const blocks = css.split('}');
    let checked = 0;
    for (const block of blocks) {
      const isStatusPill = /\.mk-status/.test(block);
      for (const m of block.matchAll(/--?[a-z-]*radius[a-z-]*:\s*([0-9.]+)px/g)) {
        if (!isStatusPill) {
          expect(Number(m[1])).toBeLessThanOrEqual(8);
          checked++;
        }
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  it('does not use marketing gradients or giant pill statuses', () => {
    // No pill-style status with big padding.
    expect(css).not.toMatch(/\.mk-status[^{]*\{[^}]*padding:\s*(?:1[2-9]|2\d)px/);
  });

  it('keeps shadows limited to dropdown/popover/modal', () => {
    // Cards must not cast shadows: every box-shadow declaration must be in a
    // popper/dropdown/dialog rule.
    const shadowBlocks = css.match(/[^{}]*\{[^}]*box-shadow:[^}]*\}/g) ?? [];
    for (const block of shadowBlocks) {
      expect(block).toMatch(/el-popper|el-dropdown|el-dialog/);
    }
  });
});
