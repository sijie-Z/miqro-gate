import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { globSync } from 'node:fs';

/**
 * Aesthetic audit (frontend-design.md §9, 2026-08-27 Tencent-console
 * direction): gradients exist ONLY on brand identity chips (.mk-brand-chip)
 * and the cost donut (.mk-donut); purple exists ONLY in the chip palette;
 * regular containers stay <=8px radius; cards may cast the hairline shadow
 * (0 1px 2px) — anything heavier belongs to popper/dropdown/dialog. This
 * scans the source CSS; the built bundle inherits the same rules.
 */
describe('aesthetic audit', () => {
  const cssFiles = globSync('src/styles/*.css');
  const css = cssFiles.map((f) => readFileSync(f, 'utf-8')).join('\n');

  // Rule blocks outside the sanctioned brand-identity rules.
  const stripped = css
    .split('}')
    .filter(
      (block) => !/\.mk-brand-chip|\.mk-donut|--miqrokey-chip-|--miqrokey-shadow-card/.test(block),
    )
    .join('}');

  it('contains gradients only on brand chips and the cost donut', () => {
    expect(stripped).not.toMatch(/linear-gradient|radial-gradient|conic-gradient/);
  });

  it('contains no purple tokens outside the brand chip palette', () => {
    // Explicitly forbidden palette entries (any case).
    expect(stripped).not.toMatch(
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
      const isModal = /\.t-dialog/.test(block) || /radius-modal/.test(block);
      const skip = isStatusPill || isModal;
      for (const m of block.matchAll(/--?[a-z-]*radius[a-z-]*:\s*([0-9.]+)px/g)) {
        if (!skip) {
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

  it('keeps shadows limited to dropdown/popover/modal (cards may cast the hairline shadow)', () => { // TDesign (t-) and legacy (el-) names both sanctioned
    // Hairline card shadow (0 1px 2px) is the sanctioned card depth; anything
    // else must stay on popper/dropdown/dialog.
    const shadowBlocks = css.match(/[^{}]*\{[^}]*box-shadow:[^}]*\}/g) ?? [];
    for (const block of shadowBlocks) {
      const hairlineCard =
        /\.mk-card|\.mk-stat-card/.test(block) &&
        (/0 1px 2px/.test(block) || /var\(--miqrokey-shadow-card\)/.test(block));
      if (!hairlineCard && !/box-shadow:\s*none/.test(block) && !/0 0 0 2px/.test(block)) {
        expect(block).toMatch(/(?:el|t)-(?:popper|dropdown|dialog|popup)/);
      }
    }
  });
});
