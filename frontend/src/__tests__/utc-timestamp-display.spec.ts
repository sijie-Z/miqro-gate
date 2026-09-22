/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * #PH89 source guard — backend instants must not be printed by slicing the ISO
 * string.
 *
 * Why a source guard and not just a behavioural test: `iso.slice(0, 16)` and a
 * local-getter conversion produce *byte-identical* output when the process runs
 * at UTC+0, which is the default on GitHub runners — the frontend CI job does
 * not pin TZ (#1301). So the regression test in `NextProvidersView.spec.ts`
 * only bites on a developer box in a non-UTC zone; it would stay green in CI
 * while the console silently printed UTC wall clocks again. This guard fails in
 * every zone.
 *
 * The rule is deliberately narrow — it flags *slicing a data timestamp*, i.e.
 * one that arrived from the API. A filename built from `new Date()` is
 * client-generated and self-consistent, so it needs no conversion and is
 * exempted by rule, not by a blanket allowlist.
 */

/** `.slice(0, 10|16)` on an ISO string, and the `split('T')[0]` variant. */
const RAW_UTC_SLICE = /\.slice\(\s*0\s*,\s*(?:10|16)\s*\)/;
const RAW_UTC_SPLIT = /\.split\(\s*['"]T['"]\s*\)\s*\[\s*0\s*\]/;

/** A `new Date()` built right there in the expression: client-side, not an API value. */
const CLIENT_GENERATED = /new Date\(\)\.toISOString\(\)/;

/**
 * Comment lines are prose, not code. Without this the guard flags `datetime.ts`
 * for *documenting* the anti-pattern — and would flag the next developer who
 * writes "never do `iso.slice(0, 16)` here" in a comment.
 */
function isComment(line: string): boolean {
  const t = line.trim();
  return t.startsWith('*') || t.startsWith('//') || t.startsWith('/*');
}

/**
 * Sites that slice an API value on purpose, with the reason. Kept as
 * `[file, snippet]` so the entry dies if the line moves or is rewritten.
 */
const SLICES_AN_API_VALUE_ON_PURPOSE: Array<[string, string]> = [
  ['NextAdminDeletionsView.vue', "(asDeletion(row).periodFrom ?? '').slice(0, 10)"],
  ['NextAdminDeletionsView.vue', "(asDeletion(row).periodTo ?? '').slice(0, 10)"],
  // These two views take the period as free-text ISO (`起始时间（ISO）`, default
  // `2026-08-01T00:00:00Z`) and send the typed string verbatim as the API
  // parameter. The row therefore echoes the operator's *own* text, in the
  // operator's own notation, matching the input directly above it. Converting
  // only the echo would make the row disagree with the field that produced it.
  ['NextAdminExportsView.vue', '(row as ExportTask).periodFrom?.slice(0, 10)'],
  ['NextAdminExportsView.vue', '(row as ExportTask).periodTo?.slice(0, 10)'],
  // A download filename, not a rendered timestamp: `cache-roi-2026-09-01_….csv`.
  // The window inside it is the report's own UTC range, so the name is stable
  // for a given report regardless of who downloads it.
  ['NextRoiView.vue', "(report.value.from ?? '').slice(0, 10)"],
  ['NextRoiView.vue', "(report.value.to ?? '').slice(0, 10)"],
];

function sourceFiles(dir: string): Array<{ name: string; path: string }> {
  const out: Array<{ name: string; path: string }> = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === '__tests__' || entry.name === 'node_modules') continue;
      out.push(...sourceFiles(path));
    } else if (entry.name.endsWith('.vue') || entry.name.endsWith('.ts')) {
      out.push({ name: entry.name, path });
    }
  }
  return out;
}

describe('#PH89 后端时间戳不得切片后直接显示', () => {
  const files = sourceFiles(join(process.cwd(), 'src'));

  it('没有对 API 时间戳做 16 字符切片（分钟精度墙钟）', () => {
    // After the #PH89 fix nothing in `src/` does this, so the check is absolute:
    // no allowlist, no exceptions. `.slice(0, 16)` on an ISO string is always
    // the UTC wall clock — there is no correct use for it in this codebase.
    const offenders: string[] = [];
    for (const file of files) {
      for (const [index, line] of readFileSync(file.path, 'utf8').split('\n').entries()) {
        if (isComment(line)) continue;
        if (line.includes('slice(0, 16)') && line.includes("replace('T'")) {
          offenders.push(`${file.name}:${index + 1} ${line.trim().slice(0, 100)}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('对 API 时间戳做 10 字符切片（日期）的每一处都有书面理由', () => {
    const offenders: string[] = [];
    for (const file of files) {
      for (const [index, line] of readFileSync(file.path, 'utf8').split('\n').entries()) {
        if (isComment(line)) continue;
        if (!RAW_UTC_SLICE.test(line) && !RAW_UTC_SPLIT.test(line)) continue;
        if (CLIENT_GENERATED.test(line)) continue;
        const justified = SLICES_AN_API_VALUE_ON_PURPOSE.some(
          ([name, snippet]) => name === file.name && line.includes(snippet),
        );
        if (!justified) {
          offenders.push(`${file.name}:${index + 1} ${line.trim().slice(0, 100)}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('理由表没有失效条目（防止理由随代码漂移后继续放行）', () => {
    const stale = SLICES_AN_API_VALUE_ON_PURPOSE.filter(([name, snippet]) => {
      const file = files.find((f) => f.name === name);
      if (!file) return true;
      return !readFileSync(file.path, 'utf8').includes(snippet);
    });
    expect(stale).toEqual([]);
  });
});
