/**
 * App-wide language layer (zh-Hans default, en opt-in).
 *
 * The console's source copy is Simplified Chinese. Instead of rewriting every
 * view into key-based i18n, the rendered DOM is translated at the edges:
 * `installDomI18n()` watches the document and, while English is selected,
 * swaps text nodes and user-visible attributes through a zh→en phrase table
 * (text nodes are restored from their originals when Chinese is selected
 * again). Views with their own copy dictionaries (the login page) opt out via
 * `data-i18n-ignore`. Brand tokens (MiQroGate, HTTP terms, provider names,
 * requestId, model IDs) are intentionally absent from the table and pass
 * through unchanged.
 */
import { ref, watch } from 'vue';
import { DICT, PATTERNS } from './dict';

export type Lang = 'zh-Hans' | 'en';

const STORAGE_KEY = 'miqrogate.lang';

export const language = ref<Lang>(localStorage.getItem(STORAGE_KEY) === 'en' ? 'en' : 'zh-Hans');

watch(language, (value) => {
  localStorage.setItem(STORAGE_KEY, value);
});

export function setLanguage(value: Lang): void {
  language.value = value;
}

const CJK = /[一-鿿]/;
const collapse = (text: string) => text.replace(/\s+/g, ' ').trim();

const lookupCache = new Map<string, string | null>();

/** Translate one collapsed Chinese string; null when the table has no entry. */
export function translateText(collapsed: string): string | null {
  if (!CJK.test(collapsed)) return null;
  const cached = lookupCache.get(collapsed);
  if (cached !== undefined) return cached;
  const result = translateOne(collapsed);
  lookupCache.set(collapsed, result);
  return result;
}

function translateOne(collapsed: string): string | null {
  const direct = DICT[collapsed];
  if (direct !== undefined) return direct;
  // Error toasts append the request id to the message ("…不能停用；请先停用该 Agent。（requestId: 123）",
  // NextCredentialsView and friends). The suffix is not copy: strip it, translate
  // the head, and put it back verbatim so the toast stays one text node.
  const suffixed = /^([\s\S]+)（requestId: ([^）]*)）$/.exec(collapsed);
  const head = suffixed?.[1];
  const requestId = suffixed?.[2];
  if (head !== undefined && requestId !== undefined) {
    const translated = translateOne(head);
    if (translated !== null) return `${translated}（requestId: ${requestId}）`;
  }
  for (const [re, replacement] of PATTERNS) {
    if (re.test(collapsed)) {
      return collapsed.replace(re, replacement);
    }
  }
  // Composite labels ("组织 / 用户", "Token 用量 · 每月"): translate the
  // segments and recompose when at least one segment is covered and no CJK
  // segment is left untranslated.
  for (const sep of [' / ', ' · ', ' — ', '：']) {
    if (!collapsed.includes(sep)) continue;
    const segments = collapsed.split(sep);
    let changed = false;
    let unresolved = false;
    const translated = segments.map((segment) => {
      if (!CJK.test(segment)) return segment;
      const hit =
        DICT[segment] ??
        PATTERNS.reduce<string | null>(
          (acc, [re, replacement]) =>
            acc ?? (re.test(segment) ? segment.replace(re, replacement) : null),
          null,
        );
      if (hit === null) {
        unresolved = true;
        return segment;
      }
      changed = true;
      return hit;
    });
    if (changed && !unresolved) return translated.join(sep);
  }
  return null;
}

// --------------------------------------------------------------------- engine
const ATTRS = ['placeholder', 'title', 'aria-label'] as const;
const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'CODE', 'PRE']);

const originals = new WeakMap<Text, string>();
const attrOriginals = new WeakMap<Element, Map<string, string>>();
let applying = false;

function skipSubtree(el: Element): boolean {
  return SKIP_TAGS.has(el.tagName) || el.hasAttribute('data-i18n-ignore');
}

function applyTextNode(node: Text): void {
  const parent = node.parentElement;
  if (!parent || skipSubtree(parent)) return;
  const raw = node.data;
  const collapsed = collapse(raw);
  if (!collapsed || !CJK.test(collapsed)) return;
  const translated = translateText(collapsed);
  if (translated === null || translated === collapsed) return;
  const match = raw.match(/^(\s*)([\s\S]*?)(\s*)$/);
  if (!match) return;
  const rebuilt = match[1] + translated + match[3];
  if (node.data === rebuilt) return;
  // Refresh the restore target when the source value itself changed
  // (interpolated counts) — translations never contain CJK copy of the key.
  if (CJK.test(raw) && originals.get(node) !== raw) originals.set(node, raw);
  applying = true;
  node.data = rebuilt;
  applying = false;
}

function restoreTextNode(node: Text): void {
  const original = originals.get(node);
  if (original === undefined || node.data === original) return;
  applying = true;
  node.data = original;
  applying = false;
}

function applyAttr(el: Element): void {
  if (skipSubtree(el)) return;
  for (const attr of ATTRS) {
    const value = el.getAttribute(attr);
    if (!value) continue;
    const collapsed = collapse(value);
    const translated = translateText(collapsed);
    if (translated === null || translated === collapsed) continue;
    let saved = attrOriginals.get(el);
    if (!saved) {
      saved = new Map();
      attrOriginals.set(el, saved);
    }
    if (!saved.has(attr)) saved.set(attr, value);
    if (value !== translated) {
      applying = true;
      el.setAttribute(attr, translated);
      applying = false;
    }
  }
}

function restoreAttr(el: Element): void {
  const saved = attrOriginals.get(el);
  if (!saved) return;
  for (const [attr, value] of saved) {
    if (el.getAttribute(attr) !== value) {
      applying = true;
      el.setAttribute(attr, value);
      applying = false;
    }
  }
}

function walk(root: Node, mode: 'en' | 'zh'): void {
  if (root.nodeType === Node.TEXT_NODE) {
    if (mode === 'en') applyTextNode(root as Text);
    else restoreTextNode(root as Text);
    return;
  }
  if (root.nodeType !== Node.ELEMENT_NODE) {
    root.childNodes.forEach((child) => walk(child, mode));
    return;
  }
  const el = root as Element;
  if (skipSubtree(el)) return;
  if (mode === 'en') applyAttr(el);
  else restoreAttr(el);
  el.childNodes.forEach((child) => walk(child, mode));
}

export function applyLanguageTo(root: Node = document.body): void {
  walk(root, language.value === 'en' ? 'en' : 'zh');
}

let installed = false;

/** Install the observer + language effect. Call once from App.vue. */
export function installDomI18n(): void {
  if (installed) return;
  installed = true;

  watch(
    language,
    () => {
      // Full pass: translate everything (en) or restore everything (zh).
      applyLanguageTo(document.body);
    },
    { flush: 'post' },
  );

  const observer = new MutationObserver((mutations) => {
    if (applying) return;
    const mode: 'en' | 'zh' = language.value === 'en' ? 'en' : 'zh';
    for (const mutation of mutations) {
      if (mutation.type === 'characterData') {
        const text = mutation.target as Text;
        if (mode === 'en') applyTextNode(text);
        else restoreTextNode(text);
        continue;
      }
      if (mutation.type === 'attributes') {
        const el = mutation.target as Element;
        if (mode === 'en') applyAttr(el);
        else restoreAttr(el);
        continue;
      }
      for (const node of mutation.addedNodes) {
        walk(node, mode);
      }
    }
  });
  observer.observe(document.body, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: [...ATTRS],
  });
}
