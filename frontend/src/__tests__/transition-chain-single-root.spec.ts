/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { parse } from '@vue/compiler-sfc';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The template AST node type, derived from `parse`'s own signature. The named
 * type lives in @vue/compiler-core, which this package does not declare as a
 * direct dependency — importing it by name would couple the guard to a
 * transitive package's export surface.
 */
type TemplateAst = NonNullable<
  NonNullable<ReturnType<typeof parse>['descriptor']['template']>['ast']
>;
type TemplateNode = TemplateAst['children'][number];

/**
 * #1144 guard: every page the shell outlet renders must keep exactly ONE
 * element root.
 *
 * The shell wraps its `<RouterView>` in `<Transition mode="out-in">`
 * (NewShell.vue:907). A page component with a fragment root (multi-root
 * template) cannot be animated there, and the renderer's fragment unmount path
 * never fires the transition's afterLeave — `state.isLeaving` stays true, so
 * the outlet is wedged to `<!---->` for THIS and every following navigation
 * until a full reload: no error, no error-boundary takeover, nothing to click
 * (verified on vue-router 4.6.4 and 5.0.7 alike). Reverting the #1144 fix
 * (NextAdminMcpServicesView.vue back to three roots) reds this guard — that
 * teeth test is recorded in the delivery report.
 *
 * Scope is deliberately the ROUTER TABLE's page components (`component: () =>
 * import('@/…')` in src/router/index.ts), not the whole src tree: off-chain
 * components may be multi-root for good reasons (App.vue is
 * [ErrorBoundary, UiToastHost]), and a rule that reds harmless files gets
 * ignored — the #807 lesson.
 */

/** All routed page components, extracted statically from the router table. */
const ROUTE_COMPONENT = /component:\s*\(\)\s*=>\s*import\(\s*['"]@\/([^'"]+\.vue)['"]\s*\)/g;

/**
 * Exemptions must carry a written reason, and the second test fails on a stale
 * entry (one that no longer needs exempting). Currently EMPTY: every routed
 * component is single-root. Do not add entries lightly — the whole route tree
 * is one `router.ts` edit away from regressing.
 */
const EXEMPT: Record<string, string> = {};

/** Does this top-level node attach to the previous root as a v-else branch? */
function isElseBranch(node: TemplateNode): boolean {
  if (node.type !== 1 /* ELEMENT */) return false;
  return node.props.some((p) => p.name === 'else' || p.name === 'else-if');
}

function isIgnorable(node: TemplateNode): boolean {
  // NodeTypes.TEXT = 2, NodeTypes.COMMENT = 3 (compiler-core enum order).
  return node.type === 2 || node.type === 3;
}

function where(node: TemplateNode): string {
  const line = node.loc?.start?.line ?? '?';
  const tag = node.type === 1 ? node.tag : `node:${node.type}`;
  return `line ${line} <${tag}>`;
}

/**
 * The same shape `renderComponentRoot`/`filterSingleRoot` sees: comments and
 * v-else branches don't add roots, and a `<template>` root only counts as a
 * single element root when it unwraps to exactly one node (otherwise the
 * rendered root is a fragment again).
 */
function checkSingleRoot(
  source: string,
  filename: string,
): { ok: true } | { ok: false; detail: string } {
  const { descriptor, errors } = parse(source, { filename });
  const ast = descriptor.template?.ast;
  if (errors.length || !ast) {
    return { ok: false, detail: '模板无法解析（SFC parse 报错）' };
  }
  const roots: TemplateNode[] = [];
  for (const child of ast.children) {
    if (isIgnorable(child)) continue;
    if (isElseBranch(child) && roots.length > 0) continue;
    roots.push(child);
  }
  if (roots.length !== 1) {
    const detail =
      roots.length === 0
        ? '模板没有任何根节点'
        : `顶层有 ${roots.length} 个根：${roots.map(where).join(' / ')}`;
    return { ok: false, detail };
  }
  const only = roots[0];
  if (!only) {
    return { ok: false, detail: '模板没有任何根节点' };
  }
  if (only.type !== 1) {
    return { ok: false, detail: `顶层唯一节点不是元素（${where(only)}）` };
  }
  if (only.tag === 'template') {
    const inner = only.children.filter((c) => !isIgnorable(c));
    if (inner.length !== 1) {
      return {
        ok: false,
        detail: `<template> 根包着 ${inner.length} 个节点，渲染为 fragment（${where(only)}）`,
      };
    }
  }
  return { ok: true };
}

describe('#1144 routed pages are single-root (outlet Transition contract)', () => {
  const routerSource = readFileSync(join(process.cwd(), 'src', 'router', 'index.ts'), 'utf8');
  const routed = [...routerSource.matchAll(ROUTE_COMPONENT)]
    .map((m) => m[1])
    .filter((rel): rel is string => typeof rel === 'string');

  it('every routed page component renders exactly one element root', () => {
    // Anti-vacuous: an extraction that finds nothing would make this guard
    // silently green forever. The table currently holds 38 page components.
    expect(
      routed.length,
      'router-table extraction found almost no pages — the guard would be silently green',
    ).toBeGreaterThanOrEqual(30);

    const offenders: string[] = [];
    for (const rel of routed) {
      const file = join(process.cwd(), 'src', rel);
      if (!existsSync(file)) {
        offenders.push(`${rel}: 路由引用的组件在磁盘上不存在`);
        continue;
      }
      if (EXEMPT[rel]) continue;
      const verdict = checkSingleRoot(readFileSync(file, 'utf8'), rel);
      if (!verdict.ok) offenders.push(`${rel}: ${verdict.detail}`);
    }
    expect(offenders).toEqual([]);
  });

  it('exemptions carry a reason and are still required', () => {
    for (const [rel, reason] of Object.entries(EXEMPT)) {
      expect(reason.trim().length, `${rel} 的豁免必须写明理由`).toBeGreaterThan(10);
      const file = join(process.cwd(), 'src', rel);
      expect(existsSync(file), `豁免条目指向不存在的文件: ${rel}`).toBe(true);
      const verdict = checkSingleRoot(readFileSync(file, 'utf8'), rel);
      expect(verdict.ok, `${rel} 已是单根，豁免不再需要——请删除该条目（防止白名单腐烂）`).toBe(
        false,
      );
    }
  });
});
