<script setup lang="ts">
/**
 * ModelScopePicker — product-catalog driven model scope editor (issue #571).
 *
 * Loads the product's model catalog (GET /admin/models?providerProductId=) and
 * renders it as a checkbox list with filter + select-all/clear + picked
 * counter. v-model is always the granted-model IDs as string[].
 *
 * Fallbacks: with no product, or when the catalog is empty, the server skips
 * catalog validation (#498) — the picker falls back to a free-text textarea
 * (one ID per line/comma) so scoping stays possible while the catalog is
 * being set up. IDs granted but missing from the catalog ("phantom" rows,
 * e.g. after a probe replaced the catalog) render checked with a warning
 * badge: keeping them checked makes the server reject the save
 * (MODEL_NOT_IN_CATALOG); unchecking removes them.
 */
import { computed, ref, watch } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiCheckbox } from '@/ui';
import type { ModelCatalogRow } from '@/types/generated-api';

const props = withDefaults(
  defineProps<{
    /** Provider product whose catalog drives the list; '' = unknown → textarea. */
    productId?: string;
    /** Granted model IDs (the checked set). */
    modelValue?: string[];
    /** Create mode: check the whole catalog once it first loads. */
    defaultAll?: boolean;
    disabled?: boolean;
  }>(),
  {
    productId: '',
    modelValue: () => [],
    defaultAll: false,
    disabled: false,
  },
);

const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>();

const rows = ref<ModelCatalogRow[]>([]);
const loading = ref(false);
const loadError = ref('');
const filter = ref('');
// #440-style guard: a slow catalog load for product A must never land after
// the picker re-targeted product B (the checked set derives from the rows).
let loadSeq = 0;
// defaultAll applies once per product; after the first load the operator
// owns the checked set.
let defaultPending = false;

const catalogIds = computed(() => new Set(rows.value.map((row) => row.modelId!)));

/** Granted IDs missing from the catalog — rows the server will reject. */
const phantomIds = computed(() => props.modelValue.filter((id) => !catalogIds.value.has(id)));

interface Option {
  modelId: string;
  displayName: string;
  source: string;
  phantom: boolean;
}

const visibleRows = computed<Option[]>(() => {
  const needle = filter.value.trim().toLowerCase();
  const match = (id: string, name: string) =>
    !needle || id.toLowerCase().includes(needle) || name.toLowerCase().includes(needle);
  const phantoms: Option[] = phantomIds.value
    .filter((id) => match(id, ''))
    .map((id) => ({ modelId: id, displayName: '', source: 'PHANTOM', phantom: true }));
  const catalog: Option[] = rows.value
    .filter((row) => match(row.modelId!, row.displayName ?? ''))
    .map((row) => ({
      modelId: row.modelId!,
      displayName: row.displayName ?? '',
      source: row.source ?? '',
      phantom: false,
    }));
  return [...phantoms, ...catalog];
});

const textareaMode = computed(
  () => props.productId === '' || (!loading.value && !loadError.value && rows.value.length === 0),
);

const scopeText = computed(() => props.modelValue.join('\n'));

function parseScopeText(text: string): string[] {
  return [
    ...new Set(
      text
        .split(/[,，\n]/)
        .map((id) => id.trim())
        .filter(Boolean),
    ),
  ];
}

function onTextInput(event: Event) {
  emit('update:modelValue', parseScopeText((event.target as HTMLTextAreaElement).value));
}

function onFilterInput(event: Event) {
  filter.value = (event.target as HTMLInputElement).value;
}

function onCheckbox(next: boolean | string[] | Set<string>) {
  if (Array.isArray(next)) {
    emit('update:modelValue', next);
  }
}

/** Adds every catalog row currently visible under the filter (not phantoms). */
function selectAllVisible() {
  const next = new Set(props.modelValue);
  for (const row of visibleRows.value) {
    if (!row.phantom) next.add(row.modelId);
  }
  emit('update:modelValue', [...next]);
}

function clearAll() {
  emit('update:modelValue', []);
}

async function load(productId: string) {
  const seq = ++loadSeq;
  rows.value = [];
  loadError.value = '';
  if (!productId) {
    return; // no product: textarea mode, nothing to fetch
  }
  loading.value = true;
  try {
    const list = await api.adminListModels(productId);
    if (seq !== loadSeq) {
      return; // the picker re-targeted another product; this response is stale
    }
    rows.value = list;
    if (defaultPending) {
      defaultPending = false;
      emit(
        'update:modelValue',
        list.map((row) => row.modelId!),
      );
    }
  } catch (error) {
    if (seq === loadSeq) {
      loadError.value = error instanceof ApiError ? error.message : '加载模型目录失败。';
    }
  } finally {
    if (seq === loadSeq) {
      loading.value = false;
    }
  }
}

watch(
  () => props.productId,
  (productId) => {
    filter.value = '';
    defaultPending = props.defaultAll;
    if (props.defaultAll) {
      // Create mode: a new product resets the scope to its whole catalog.
      emit('update:modelValue', []);
    }
    void load(productId);
  },
  { immediate: true },
);
</script>

<template>
  <div class="msp">
    <template v-if="textareaMode">
      <p class="msp__notice" data-testid="model-scope-fallback-notice">
        <template v-if="productId === ''">
          该授权未关联供应商产品：手动输入模型 ID，每行一个（此状态下服务端不校验目录）。
        </template>
        <template v-else>
          该产品暂无模型目录：可先到「供应商」页对产品执行「探测模型」或人工录入；也可直接手动输入（此状态下服务端不校验目录）。
        </template>
      </p>
      <textarea
        class="msp__textarea"
        :value="scopeText"
        :disabled="disabled"
        rows="6"
        placeholder="例如 deepseek-flash"
        data-testid="model-scope-textarea"
        @input="onTextInput"
      />
    </template>

    <div v-else-if="loading" class="msp__loading" data-testid="model-scope-loading">
      正在加载模型目录…
    </div>

    <div v-else-if="loadError" class="msp__error" data-testid="model-scope-error">
      <span>{{ loadError }}</span>
      <UiButton variant="ghost" size="sm" @click="load(productId)">重试</UiButton>
    </div>

    <div v-else class="msp__panel" data-testid="model-scope-list">
      <div class="msp__toolbar">
        <input
          class="msp__filter"
          :value="filter"
          type="text"
          placeholder="筛选模型…"
          data-testid="model-scope-filter"
          @input="onFilterInput"
        />
        <span class="msp__count" data-testid="model-scope-count">
          已选 {{ modelValue.length }} 个 · 目录 {{ rows.length }} 个
        </span>
        <UiButton variant="ghost" size="sm" @click="selectAllVisible">全选</UiButton>
        <UiButton variant="ghost" size="sm" @click="clearAll">清空</UiButton>
      </div>
      <div class="msp__rows">
        <div
          v-for="row in visibleRows"
          :key="row.modelId"
          class="msp__row"
          :class="{ 'msp__row--phantom': row.phantom }"
        >
          <div class="msp__check">
            <UiCheckbox
              :model-value="modelValue"
              :value="row.modelId"
              :disabled="disabled"
              :data-testid="`model-scope-option-${row.modelId}`"
              @update:model-value="onCheckbox"
            >
              <span class="msp__id">{{ row.modelId }}</span>
              <span v-if="row.displayName" class="msp__name">{{ row.displayName }}</span>
            </UiCheckbox>
          </div>
          <span v-if="row.phantom" class="msp__badge msp__badge--warn">不在目录</span>
          <span v-else-if="row.source === 'MANUAL'" class="msp__badge">人工</span>
        </div>
        <p v-if="!visibleRows.length" class="msp__empty">没有匹配的模型。</p>
      </div>
      <p v-if="phantomIds.length" class="msp__warn" data-testid="model-scope-phantom-warn">
        有
        {{ phantomIds.length }} 个已授权模型不在当前目录：保留勾选会被服务端拒绝，取消勾选即移除。
      </p>
    </div>
  </div>
</template>

<style scoped>
.msp {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.msp__notice {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  line-height: var(--ui-line-height-sm);
}

.msp__textarea {
  width: 100%;
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: var(--ui-font-mono);
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-base);
  resize: vertical;
}

.msp__textarea:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.msp__loading {
  padding: var(--ui-space-3);
  border: 1px dashed var(--ui-border-strong);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.msp__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-danger-fg);
  border-radius: var(--ui-radius-control);
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
  font-size: var(--ui-font-size-xs);
}

.msp__panel {
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  overflow: hidden;
}

.msp__toolbar {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2) var(--ui-space-3);
  border-bottom: 1px solid var(--ui-border);
  background: var(--ui-muted);
}

.msp__filter {
  flex: 1;
  min-width: 0;
  height: 26px;
  padding: 0 var(--ui-space-2);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-xs);
}

.msp__filter:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.msp__count {
  flex-shrink: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  font-variant-numeric: tabular-nums;
}

.msp__rows {
  display: flex;
  flex-direction: column;
  max-height: 264px;
  overflow-y: auto;
  padding: var(--ui-space-1);
}

.msp__row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-1) var(--ui-space-2);
  border-radius: var(--ui-radius-control);
}

.msp__row:hover {
  background: var(--ui-fill-hover);
}

.msp__check {
  display: flex;
  flex: 1;
  min-width: 0;
}

.msp__id {
  font-family: var(--ui-font-mono);
  font-size: var(--ui-font-size-sm);
}

.msp__name {
  margin-left: var(--ui-space-2);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.msp__badge {
  flex-shrink: 0;
  padding: 0 var(--ui-space-2);
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-xs);
  line-height: 18px;
}

.msp__badge--warn {
  background: var(--ui-warning-bg);
  color: var(--ui-warning-fg);
}

.msp__empty {
  margin: 0;
  padding: var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  text-align: center;
}

.msp__warn {
  margin: 0;
  padding: var(--ui-space-2) var(--ui-space-3);
  border-top: 1px solid var(--ui-border);
  background: var(--ui-warning-bg);
  color: var(--ui-warning-fg);
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-sm);
}
</style>
