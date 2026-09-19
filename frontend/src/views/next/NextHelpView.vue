<script setup lang="ts">
/**
 * NextHelpView — /app/help: the in-console handbook (#869).
 *
 * The markdown is imported as raw text at build time from
 * src/content/handbook/ — a VENDORED copy of docs/user-guide/ kept
 * byte-identical by `npm run sync:handbook` (drift guard:
 * handbook-sync.spec.ts). Vendoring matters: the deploy build context only
 * carries backend/ + frontend/ + deploy/, so an earlier revision that
 * glob-imported ../../../../docs/*.md built fine locally/CI and shipped an
 * EMPTY handbook in production (#899). The GitHub link remains a convenience,
 * not the source of truth. Rendering: marked (MIT). Relative links inside the
 * docs are rewritten to GitHub blob URLs and headings get stable ids so the
 * side TOC can scroll to them.
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { marked } from 'marked';
import { UiButton } from '@/ui';
import { resolveDocLink } from '@/lib/handbook-links';

const RAW = import.meta.glob('../../content/handbook/*.md', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

function rawOf(file: string): string {
  const key = Object.keys(RAW).find((k) => k.endsWith(`/${file}`));
  return key ? RAW[key]! : '';
}

const DOC_ORDER: Array<[string, string]> = [
  ['README.md', '手册总览'],
  ['quickstart.md', '快速上手'],
  ['user-guide.md', '用户手册'],
  ['admin-guide.md', '管理员手册'],
  ['developer-guide.md', '开发接入'],
  ['faq.md', '常见问题'],
];

const docs = DOC_ORDER.map(([file, title]) => ({ file, title, raw: rawOf(file) })).filter(
  (d) => d.raw.length > 0,
);

const current = ref(docs[0]?.file ?? '');
const currentDoc = computed(() => docs.find((d) => d.file === current.value));

const contentEl = ref<HTMLElement | null>(null);
const toc = ref<Array<{ id: string; text: string; level: number }>>([]);

const html = computed(() =>
  currentDoc.value ? (marked.parse(currentDoc.value.raw, { gfm: true }) as string) : '',
);

watch(html, async () => {
  await nextTick();
  decorate();
});
onMounted(decorate);

/** Stable heading ids + TOC collection + link rewriting (see module comment). */
function decorate() {
  const root = contentEl.value;
  if (!root) return;
  const list: Array<{ id: string; text: string; level: number }> = [];
  let n = 0;
  root.querySelectorAll('h1, h2, h3').forEach((el) => {
    const level = Number(el.tagName.slice(1));
    const id = `hb-h-${n++}`;
    el.id = id;
    if (level >= 2) list.push({ id, text: el.textContent ?? '', level });
  });
  toc.value = list;

  root.querySelectorAll('a').forEach((a) => {
    const href = a.getAttribute('href') ?? '';
    const resolved = resolveDocLink(href, currentDoc.value?.file ?? '');
    a.setAttribute('href', resolved.href);
    if (resolved.external) {
      a.setAttribute('target', '_blank');
      a.setAttribute('rel', 'noopener');
    }
  });
}

function scrollTo(id: string) {
  contentEl.value?.querySelector(`#${CSS.escape(id)}`)?.scrollIntoView({
    behavior: 'smooth',
    block: 'start',
  });
}

function openOnGithub() {
  window.open(
    `https://github.com/sijie-Z/miqro-gate/blob/develop/docs/user-guide/${current.value}`,
    '_blank',
    'noopener',
  );
}
</script>

<template>
  <div class="ui-page next-help">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">帮助</h1>
        <p class="ui-page-desc">
          随控制台内置的使用手册（离线可用）——快速上手、用户/管理员手册、开发接入与 FAQ。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="secondary" data-testid="help-github" @click="openOnGithub"
          >在 GitHub 打开</UiButton
        >
      </div>
    </header>

    <div v-if="!docs.length" class="ui-panel next-help__empty" data-testid="help-empty">
      <p class="next-help__empty-text">
        手册内容未打包进本次构建（构建树缺少内容副本）。可直接在 GitHub 查看：
      </p>
      <a
        class="ui-link-action"
        href="https://github.com/sijie-Z/miqro-gate/tree/develop/docs/user-guide"
        target="_blank"
        rel="noopener"
        >docs/user-guide</a
      >
    </div>

    <div v-else class="next-help__layout">
      <aside class="next-help__side">
        <nav class="next-help__docs" aria-label="手册目录">
          <button
            v-for="d in docs"
            :key="d.file"
            type="button"
            class="next-help__doc"
            :class="{ 'next-help__doc--on': current === d.file }"
            :data-testid="`help-doc-${d.file.replace('.md', '')}`"
            @click="current = d.file"
          >
            {{ d.title }}
          </button>
        </nav>
        <nav v-if="toc.length" class="next-help__toc" aria-label="本页目录">
          <button
            v-for="t in toc"
            :key="t.id"
            type="button"
            class="next-help__toc-item"
            :class="{ 'next-help__toc-item--sub': t.level === 3 }"
            @click="scrollTo(t.id)"
          >
            {{ t.text }}
          </button>
        </nav>
      </aside>

      <!-- v-html renders the repository's own docs/user-guide markdown,
           inlined at build time (trusted input; see the eslint override). -->
      <article
        ref="contentEl"
        class="ui-panel next-help__body markdown"
        data-testid="help-content"
        v-html="html"
      />
    </div>
  </div>
</template>

<style scoped>
.next-help__empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--ui-space-2);
  padding: var(--ui-space-6);
}

.next-help__empty-text {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-help__layout {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: var(--ui-space-4);
  align-items: start;
}

.next-help__side {
  position: sticky;
  top: var(--ui-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-height: calc(100vh - 120px);
  overflow-y: auto;
}

.next-help__docs,
.next-help__toc {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.next-help__doc,
.next-help__toc-item {
  text-align: left;
  border: none;
  background: none;
  font: inherit;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  padding: 6px 10px;
  border-radius: var(--ui-radius-control);
  cursor: pointer;
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.next-help__doc:hover,
.next-help__toc-item:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.next-help__doc--on {
  background: var(--ui-primary-soft);
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-medium);
}

.next-help__toc-item {
  font-size: var(--ui-font-size-xs);
  padding: 3px 10px;
}

.next-help__toc-item--sub {
  padding-left: 22px;
  color: var(--ui-foreground-faint);
}

.next-help__body {
  padding: var(--ui-space-6) var(--ui-space-8);
  overflow-x: auto;
}

/* ---- handbook typography (scoped onto the v-html subtree) ---- */
.next-help__body :deep(h1) {
  margin: 0 0 var(--ui-space-4);
  font-size: 22px;
  font-weight: var(--ui-weight-semibold);
  line-height: 30px;
  color: var(--ui-foreground);
}

.next-help__body :deep(h2) {
  margin: var(--ui-space-6) 0 var(--ui-space-3);
  padding-top: var(--ui-space-4);
  border-top: 1px solid var(--ui-border-muted);
  font-size: 17px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-help__body :deep(h3) {
  margin: var(--ui-space-4) 0 var(--ui-space-2);
  font-size: 15px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-help__body :deep(p),
.next-help__body :deep(li) {
  font-size: var(--ui-font-size-base);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground);
}

.next-help__body :deep(p) {
  margin: var(--ui-space-2) 0;
}

.next-help__body :deep(ul),
.next-help__body :deep(ol) {
  margin: var(--ui-space-2) 0;
  padding-left: var(--ui-space-6);
}

.next-help__body :deep(li + li) {
  margin-top: 4px;
}

.next-help__body :deep(a) {
  color: var(--ui-primary-text);
  text-decoration: none;
}

.next-help__body :deep(a:hover) {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.next-help__body :deep(code) {
  font-family: var(--ui-font-mono);
  font-size: 12px;
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--ui-muted);
}

.next-help__body :deep(pre) {
  margin: var(--ui-space-3) 0;
  padding: var(--ui-space-3) var(--ui-space-4);
  border-radius: var(--ui-radius-panel);
  background: var(--ui-muted);
  overflow-x: auto;
}

.next-help__body :deep(pre code) {
  padding: 0;
  background: none;
  font-size: 12px;
  line-height: 20px;
}

.next-help__body :deep(table) {
  width: 100%;
  margin: var(--ui-space-3) 0;
  border-collapse: collapse;
  font-size: var(--ui-font-size-sm);
}

.next-help__body :deep(th) {
  text-align: left;
  padding: 8px 10px;
  border-bottom: 1px solid var(--ui-border);
  background: var(--ui-muted);
  font-weight: var(--ui-weight-semibold);
  white-space: nowrap;
}

.next-help__body :deep(td) {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ui-border-muted);
  vertical-align: top;
}

.next-help__body :deep(blockquote) {
  margin: var(--ui-space-3) 0;
  padding: var(--ui-space-2) var(--ui-space-4);
  border-left: 3px solid var(--ui-primary);
  background: var(--ui-primary-soft);
  border-radius: 0 var(--ui-radius-control) var(--ui-radius-control) 0;
}

.next-help__body :deep(blockquote p) {
  margin: 0;
  color: var(--ui-foreground-secondary);
}

.next-help__body :deep(hr) {
  margin: var(--ui-space-5) 0;
  border: none;
  border-top: 1px solid var(--ui-border-muted);
}

@media (max-width: 900px) {
  .next-help__layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .next-help__side {
    position: static;
    max-height: none;
  }
}
</style>
