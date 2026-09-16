<script setup lang="ts">
/**
 * PageGuide — per-page 「使用指引」card (#656, Tencent-console 产品指南
 * pattern): a dismissible 3–4 step chain that tells a first-time admin what
 * this page is part of and where the next step lives. It is a signpost, not
 * a wizard — forms stay single-page (frontend-design §6).
 *
 * Per-page state persists in localStorage: expanded (first visit) →
 * collapsed (slim bar) → hidden (不再显示).
 */
import { computed, ref } from 'vue';
import type { PageGuideContent } from '@/content/pageGuides';

const props = defineProps<{
  guide: PageGuideContent;
  /** Stable per-page id used for the persisted state key. */
  storageKey: string;
}>();

type GuideState = 'expanded' | 'collapsed' | 'hidden';

const stateKey = computed(() => `miqrokey.page-guide.${props.storageKey}`);

// Read synchronously in setup (not onMounted) so a collapsed/hidden page
// never flashes the expanded card on load.
function initialState(): GuideState {
  try {
    const stored = localStorage.getItem(`miqrokey.page-guide.${props.storageKey}`);
    if (stored === 'collapsed' || stored === 'hidden') return stored;
  } catch {
    // storage unavailable — keep the first-visit expanded state
  }
  return 'expanded';
}

const state = ref<GuideState>(initialState());

function persist(next: GuideState) {
  state.value = next;
  try {
    localStorage.setItem(stateKey.value, next);
  } catch {
    // non-fatal
  }
}
</script>

<template>
  <section
    v-if="state === 'expanded'"
    class="ui-page-guide"
    data-testid="page-guide"
    aria-label="使用指引"
  >
    <header class="ui-page-guide__head">
      <span class="ui-page-guide__eyebrow">使用指引</span>
      <h2 class="ui-page-guide__title">{{ guide.title }}</h2>
      <div class="ui-page-guide__head-actions">
        <button
          type="button"
          class="ui-page-guide__ghost"
          data-testid="page-guide-collapse"
          @click="persist('collapsed')"
        >
          收起
        </button>
        <button
          type="button"
          class="ui-page-guide__ghost"
          data-testid="page-guide-hide"
          @click="persist('hidden')"
        >
          不再显示
        </button>
      </div>
    </header>
    <ol class="ui-page-guide__steps">
      <li v-for="(step, index) in guide.steps" :key="step.title" class="ui-page-guide__step">
        <span class="ui-page-guide__num" aria-hidden="true">{{ index + 1 }}</span>
        <div class="ui-page-guide__body">
          <p class="ui-page-guide__step-title">{{ step.title }}</p>
          <p class="ui-page-guide__step-desc">{{ step.desc }}</p>
          <div v-if="step.to || step.docHref" class="ui-page-guide__links">
            <router-link v-if="step.to" :to="step.to" class="ui-link-action ui-page-guide__link">
              {{ step.toText }}
            </router-link>
            <a
              v-if="step.docHref"
              :href="step.docHref"
              target="_blank"
              rel="noopener noreferrer"
              class="ui-link-action ui-page-guide__link"
            >
              {{ step.docText }}
            </a>
          </div>
        </div>
      </li>
    </ol>
  </section>

  <div
    v-else-if="state === 'collapsed'"
    class="ui-page-guide ui-page-guide--collapsed"
    data-testid="page-guide"
  >
    <span class="ui-page-guide__eyebrow">使用指引</span>
    <span class="ui-page-guide__collapsed-title">{{ guide.title }}</span>
    <span class="ui-page-guide__spacer" />
    <button
      type="button"
      class="ui-page-guide__ghost"
      data-testid="page-guide-expand"
      @click="persist('expanded')"
    >
      展开
    </button>
    <button
      type="button"
      class="ui-page-guide__ghost"
      data-testid="page-guide-hide"
      @click="persist('hidden')"
    >
      不再显示
    </button>
  </div>
</template>

<style scoped>
.ui-page-guide {
  margin-bottom: var(--ui-space-4);
  padding: var(--ui-space-4) var(--ui-space-5);
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-panel);
}

.ui-page-guide__head {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.ui-page-guide__eyebrow {
  padding: 1px 8px;
  border-radius: 4px;
  background: var(--ui-primary-soft);
  color: var(--ui-primary-text);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  line-height: 18px;
  white-space: nowrap;
}

.ui-page-guide__title {
  flex: 1;
  margin: 0;
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.ui-page-guide__head-actions {
  display: flex;
  align-items: center;
  gap: var(--ui-space-1);
}

.ui-page-guide__ghost {
  padding: 0 6px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: none;
  font: inherit;
  font-size: var(--ui-font-size-xs);
  line-height: 22px;
  color: var(--ui-foreground-faint);
  cursor: pointer;
  transition: color var(--ui-ease);
}

.ui-page-guide__ghost:hover {
  color: var(--ui-foreground);
}

.ui-page-guide__ghost:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.ui-page-guide__steps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--ui-space-3) var(--ui-space-5);
  margin: var(--ui-space-4) 0 0;
  padding: var(--ui-space-4) 0 0;
  border-top: 1px solid var(--ui-border-muted);
  list-style: none;
}

.ui-page-guide__step {
  display: flex;
  align-items: flex-start;
  gap: var(--ui-space-2);
  min-width: 0;
}

.ui-page-guide__num {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-primary-soft);
  color: var(--ui-primary-text);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-semibold);
  line-height: 20px;
  text-align: center;
}

.ui-page-guide__body {
  min-width: 0;
}

.ui-page-guide__step-title {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  line-height: 20px;
  color: var(--ui-foreground);
}

.ui-page-guide__step-desc {
  margin: 2px 0 0;
  font-size: var(--ui-font-size-xs);
  line-height: 18px;
  color: var(--ui-foreground-secondary);
}

.ui-page-guide__links {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-1);
  margin-top: 2px;
}

.ui-page-guide__link {
  padding: 0;
  font-size: var(--ui-font-size-xs);
}

.ui-page-guide--collapsed {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-1) var(--ui-space-4) var(--ui-space-1) var(--ui-space-5);
}

.ui-page-guide__collapsed-title {
  overflow: hidden;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ui-page-guide__spacer {
  flex: 1;
}
</style>
