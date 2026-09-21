<script setup lang="ts">
/**
 * NextPlazaView — 模型广场 (#1201, 腾讯「AI 能力市场」对位).
 *
 * Lists every model the caller can actually call (the same key ∩ grant ∩
 * ACTIVE-catalog intersection the gateway gates on), with the unit prices the
 * cost report will charge, plus the catalog models still approvable on one of
 * their keys. Read-only; every row links into the playground or the approval
 * flow.
 */
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import * as api from '@/api';
import { UiButton, UiInput, UiPageGuide, UiSelect, UiTable } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type {
  MePlazaView,
  PlazaKeyRef,
  PlazaModel,
  PlazaPrice,
  RequestableModel,
} from '@/types/generated-api';
import { PLAZA_GUIDE } from '@/content/pageGuides';

const router = useRouter();

const data = ref<MePlazaView | null>(null);
const loading = ref(true);
const loadError = ref('');
const search = ref('');
const productFilter = ref('');

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    data.value = await api.getPlazaModels();
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

const models = computed<PlazaModel[]>(() => data.value?.models ?? []);
const requestable = computed<RequestableModel[]>(() => data.value?.requestable ?? []);

const productOptions = computed<UiSelectOption[]>(() => {
  const seen = new Map<string, string>();
  for (const model of models.value) {
    const id = model.providerProductId ?? '';
    if (id && !seen.has(id)) seen.set(id, model.providerProductName ?? id);
  }
  return [...seen.entries()].map(([value, label]) => ({ value, label }));
});

const filteredModels = computed(() => {
  const q = search.value.trim().toLowerCase();
  return models.value.filter((model) => {
    if (productFilter.value && model.providerProductId !== productFilter.value) return false;
    if (!q) return true;
    return (
      (model.modelId ?? '').toLowerCase().includes(q) ||
      (model.displayName ?? '').toLowerCase().includes(q) ||
      (model.providerProductName ?? '').toLowerCase().includes(q)
    );
  });
});

const columns = [
  { key: 'modelId', title: '模型', minWidth: '220px' },
  { key: 'providerProductName', title: '供应商产品', minWidth: '160px' },
  { key: 'priceInput', title: '输入价（每百万 tokens）', width: '170px' },
  { key: 'priceOutput', title: '输出价（每百万 tokens）', width: '170px' },
  { key: 'contextWindow', title: '上下文', width: '100px' },
  { key: 'keys', title: '可用密钥', minWidth: '180px' },
  { key: 'actions', title: '操作', width: '90px' },
];

const requestableColumns = [
  { key: 'modelId', title: '模型', minWidth: '220px' },
  { key: 'providerProductName', title: '供应商产品', minWidth: '160px' },
  { key: 'contextWindow', title: '上下文', width: '100px' },
  { key: 'keyName', title: '申请于密钥', minWidth: '180px' },
  { key: 'actions', title: '操作', width: '100px' },
];

function priceText(price: PlazaPrice | null | undefined, pick: 'input' | 'output'): string {
  if (!price) return '—';
  const value = pick === 'input' ? price.inputPerMillion : price.outputPerMillion;
  if (value === null || value === undefined) return '—';
  const numeric = Number(value);
  const text = Number.isFinite(numeric) ? String(numeric) : String(value);
  return price.currency ? `${text} ${price.currency}` : text;
}

function contextText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value >= 1000) {
    const k = value / 1000;
    return `${Number.isInteger(k) ? k : k.toFixed(1)}K`;
  }
  return String(value);
}

function m(row: unknown): PlazaModel {
  return row as PlazaModel;
}

function req(row: unknown): RequestableModel {
  return row as RequestableModel;
}

function keyChips(keys: PlazaKeyRef[] | undefined): PlazaKeyRef[] {
  return (keys ?? []).slice(0, 2);
}

function tryModel(modelId: string) {
  void router.push({ path: '/app/playground', query: { model: modelId } });
}

function requestModel(row: RequestableModel) {
  void router.push({
    path: '/app/model-approvals',
    query: { keyId: row.keyId, model: row.modelId },
  });
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-plaza">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">模型广场</h1>
        <p class="ui-page-desc">
          你名下「可用」密钥能调用的全部模型：单价、上下文与可调用范围一目了然；选中模型可直接去试调。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="primary"
          data-testid="plaza-go-playground"
          @click="router.push('/app/playground')"
        >
          去试调台
        </UiButton>
      </div>
    </header>

    <UiPageGuide :guide="PLAZA_GUIDE" storage-key="plaza" />

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="plaza-load-error">
      {{ loadError }}
    </div>

    <template v-if="!loading && !loadError">
      <section
        v-if="models.length === 0 && requestable.length === 0"
        class="ui-panel"
        data-testid="plaza-empty"
      >
        <div class="ui-panel-head">
          <h2 class="ui-panel-title">暂无可用模型</h2>
        </div>
        <div class="ui-panel-body next-plaza__empty">
          <p>两种常见原因：</p>
          <ul>
            <li>你还没有「可用」状态的虚拟密钥 —— 先到「我的密钥」创建一把。</li>
            <li>管理员还没有为你的项目圈定模型范围 —— 可在「模型申请」提交申请，或联系管理员。</li>
          </ul>
          <div class="next-plaza__empty-actions">
            <UiButton variant="primary" @click="router.push('/app/keys')"
              >前往「我的密钥」</UiButton
            >
            <UiButton variant="ghost" @click="router.push('/app/model-approvals')"
              >前往「模型申请」</UiButton
            >
          </div>
        </div>
      </section>
    </template>

    <section v-if="loading || models.length > 0" class="ui-panel">
      <div class="ui-panel-head next-plaza__head">
        <h2 class="ui-panel-title">可用模型</h2>
        <div class="next-plaza__filters">
          <UiInput
            v-model="search"
            placeholder="搜索模型 ID / 名称"
            width="220px"
            data-testid="plaza-search"
          />
          <UiSelect
            v-if="productOptions.length > 1"
            v-model="productFilter"
            :options="[{ value: '', label: '全部产品' }, ...productOptions]"
            placeholder="全部产品"
            width="200px"
            data-testid="plaza-product-filter"
          />
        </div>
      </div>
      <UiTable
        :columns="columns"
        :data="filteredModels"
        :loading="loading"
        row-key="modelId"
        empty-title="没有匹配的模型"
        empty-description="调整搜索词或产品筛选试试。"
        data-testid="plaza-table"
      >
        <template #modelId="{ row }">
          <div class="next-plaza__model">
            <span class="ui-mono">{{ m(row).modelId }}</span>
            <span v-if="m(row).displayName" class="next-plaza__sub">
              {{ m(row).displayName }}
            </span>
          </div>
        </template>
        <template #providerProductName="{ row }">
          {{ m(row).providerProductName || '—' }}
        </template>
        <template #priceInput="{ row }">
          <span class="ui-mono">{{ priceText(m(row).price, 'input') }}</span>
        </template>
        <template #priceOutput="{ row }">
          <span class="ui-mono">{{ priceText(m(row).price, 'output') }}</span>
        </template>
        <template #contextWindow="{ row }">
          {{ contextText(m(row).contextWindow) }}
        </template>
        <template #keys="{ row }">
          <span
            v-for="key in keyChips(m(row).keys)"
            :key="key.id"
            class="next-plaza__chip"
            :title="key.name || key.display || ''"
          >
            {{ key.name || key.display }}
          </span>
          <span
            v-if="(m(row).keys?.length ?? 0) > 2"
            class="next-plaza__chip next-plaza__chip--more"
          >
            +{{ (m(row).keys?.length ?? 0) - 2 }}
          </span>
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="link"
            :data-testid="`plaza-try-${m(row).modelId}`"
            @click="tryModel(m(row).modelId ?? '')"
          >
            试调
          </UiButton>
        </template>
      </UiTable>
    </section>

    <section v-if="!loading && requestable.length > 0" class="ui-panel">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">可申请模型（{{ requestable.length }}）</h2>
        <p class="next-plaza__section-desc">
          这些模型已在供应商目录中、但还不在你的密钥上；提交申请，管理员审批通过后数秒内生效。
        </p>
      </div>
      <UiTable
        :columns="requestableColumns"
        :data="requestable"
        row-key="modelId"
        data-testid="plaza-requestable-table"
      >
        <template #modelId="{ row }">
          <div class="next-plaza__model">
            <span class="ui-mono">{{ req(row).modelId }}</span>
            <span v-if="req(row).displayName" class="next-plaza__sub">
              {{ req(row).displayName }}
            </span>
          </div>
        </template>
        <template #providerProductName="{ row }">
          {{ req(row).providerProductName || '—' }}
        </template>
        <template #contextWindow="{ row }">
          {{ contextText(req(row).contextWindow) }}
        </template>
        <template #keyName="{ row }">
          <span class="next-plaza__chip">{{ req(row).keyName || '—' }}</span>
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="link"
            :data-testid="`plaza-request-${req(row).keyId}-${req(row).modelId}`"
            @click="requestModel(req(row))"
          >
            去申请
          </UiButton>
        </template>
      </UiTable>
    </section>
  </div>
</template>

<style scoped>
.next-plaza__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

.next-plaza__filters {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.next-plaza__section-desc {
  margin: var(--ui-space-1) 0 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-plaza__model {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.next-plaza__sub {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-plaza__chip {
  display: inline-block;
  margin-right: var(--ui-space-1);
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--ui-muted);
  color: var(--ui-foreground);
  font-size: var(--ui-font-size-xs);
  white-space: nowrap;
}

.next-plaza__chip--more {
  color: var(--ui-foreground-faint);
}

.next-plaza__empty ul {
  margin: var(--ui-space-2) 0;
  padding-left: 1.2em;
  color: var(--ui-foreground-muted);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.next-plaza__empty-actions {
  display: flex;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-3);
}

.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}
</style>
