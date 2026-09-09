<script setup lang="ts">
/**
 * NextProvidersView — /app/providers v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy providers page: catalogue of provider
 * product instances with protocol / base host / implementation / balance
 * source columns.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type { ProviderProductView } from '@/types/api';
import type { ModelCatalogRow } from '@/types/generated-api';

const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

// UiTable slot rows arrive as loose Records; the catalogue list always returns
// complete product rows, so cast back to the handwritten view type (which is
// deliberately kept in @/types/api, not migrated to the generated hub).
function productOf(row: unknown): ProviderProductView {
  return row as unknown as ProviderProductView;
}

const columns = [
  { key: 'provider', title: '供应商', width: '220px' },
  { key: 'product', title: '产品', minWidth: '220px' },
  { key: 'protocols', title: '协议', width: '190px' },
  { key: 'baseUrl', title: 'Base URL', minWidth: '220px' },
  { key: 'implementationStatus', title: '实现状态', width: '130px' },
  { key: 'balanceAuthority', title: '余额来源', width: '120px' },
  { key: 'actions', title: '操作', width: '150px' },
];

function chipLetter(name: string): string {
  return (name ?? '?').slice(0, 1).toUpperCase();
}

function chipClass(slug: string): string {
  switch (slug) {
    case 'tencent':
      return 'mk-chip-tencent';
    case 'deepseek':
      return 'mk-chip-deepseek';
    case 'zhipu':
      return 'mk-chip-zhipu';
    case 'minimax':
      return 'mk-chip-minimax';
    case 'moonshot':
      return 'mk-chip-moonshot';
    case 'baidu':
      return 'mk-chip-baidu';
    case 'volcengine':
      return 'mk-chip-volcengine';
    case 'aliyun':
      return 'mk-chip-aliyun';
    default:
      return 'mk-chip-tencent';
  }
}

function implTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'VERIFIED':
      return 'success';
    case 'IMPLEMENTED':
      return 'warning';
    case 'DEGRADED':
      return 'danger';
    default:
      return 'neutral';
  }
}

const implLabel: Record<string, string> = {
  VERIFIED: '已验证',
  IMPLEMENTED: '已实现',
  DEGRADED: '降级',
};

function balanceLabel(authority: string): string {
  switch (authority) {
    case 'OFFICIAL_API':
      return '官方 API';
    case 'LOCAL_ESTIMATE':
      return '本地估算';
    case 'UNAVAILABLE':
      return '不可用';
    default:
      return authority;
  }
}

// F18 model-catalog maintenance (manual entry fallback)
const modelsProduct = ref<ProviderProductView | null>(null);
const modelsVisible = ref(false);
const models = ref<ModelCatalogRow[]>([]);
const modelsLoading = ref(false);
const modelsError = ref('');
const modelForm = ref({ modelId: '', displayName: '' });
const modelSaving = ref(false);
const modelError = ref('');

async function openModels(product: ProviderProductView) {
  modelsProduct.value = product;
  models.value = [];
  modelsError.value = '';
  modelForm.value = { modelId: '', displayName: '' };
  modelError.value = '';
  modelsVisible.value = true;
  modelsLoading.value = true;
  try {
    models.value = await api.adminListModels(product.id);
  } catch (error) {
    modelsError.value = error instanceof ApiError ? error.message : '加载模型目录失败。';
  } finally {
    modelsLoading.value = false;
  }
}

async function addManualModel() {
  if (!modelsProduct.value) {
    return;
  }
  const modelId = modelForm.value.modelId.trim();
  if (!modelId) {
    modelError.value = '模型 ID 必填。';
    return;
  }
  modelSaving.value = true;
  modelError.value = '';
  try {
    await api.adminCreateModel(modelsProduct.value.id, {
      modelId,
      displayName: modelForm.value.displayName.trim() || undefined,
    });
    modelForm.value = { modelId: '', displayName: '' };
    toast.success('人工模型已录入');
    models.value = await api.adminListModels(modelsProduct.value.id);
  } catch (error) {
    modelError.value = error instanceof ApiError ? error.message : '录入失败，请稍后重试。';
  } finally {
    modelSaving.value = false;
  }
}

async function removeManualModel(row: ModelCatalogRow) {
  // Model rows come from adminListModels; the server always issues ids.
  try {
    await api.adminDeleteModel(row.id!);
    toast.success(`已删除 ${row.modelId}`);
    if (modelsProduct.value) {
      models.value = await api.adminListModels(modelsProduct.value.id);
    }
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  }
}

async function load() {
  loading.value = true;
  try {
    products.value = await api.listProviderProducts();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-providers">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">供应商</h1>
        <p class="ui-page-desc">供应商产品实例：协议、Plan 形态、验证状态与余额来源。</p>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ products.length }} 个产品实例</span>
      </div>
      <UiTable
        :columns="columns"
        :data="products"
        :loading="loading"
        row-key="id"
        empty-title="暂无产品实例"
        data-testid="products-table"
      >
        <template #provider="{ row }">
          <span class="next-providers__provider">
            <span
              class="mk-brand-chip mk-brand-chip--sm"
              :class="chipClass(productOf(row).providerSlug)"
              aria-hidden="true"
              >{{ chipLetter(productOf(row).providerName) }}</span
            >
            <span>{{ productOf(row).providerName }}</span>
          </span>
        </template>
        <template #product="{ row }">
          <div class="next-providers__name">{{ productOf(row).displayName }}</div>
          <div class="ui-mono next-providers__code">{{ productOf(row).productCode }}</div>
        </template>
        <template #protocols="{ row }">
          <span class="ui-mono">{{ productOf(row).protocols }}</span>
        </template>
        <template #baseUrl="{ row }">
          <span class="ui-mono">{{ productOf(row).baseUrlHost || '—' }}</span>
        </template>
        <template #implementationStatus="{ row }">
          <UiStatusBadge
            :tone="implTone(productOf(row).implementationStatus)"
            :label="
              implLabel[productOf(row).implementationStatus] ??
              productOf(row).implementationStatus
            "
          />
        </template>
        <template #balanceAuthority="{ row }">
          <span class="next-providers__balance">{{
            balanceLabel(productOf(row).balanceAuthority)
          }}</span>
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="ghost"
            size="sm"
            data-testid="product-models-open"
            @click="openModels(productOf(row))"
            >模型目录</UiButton
          >
        </template>
      </UiTable>
    </section>

    <!-- F18 model catalog (manual entry fallback) -->
    <UiDialog
      :open="modelsVisible"
      :title="modelsProduct ? `模型目录 · ${modelsProduct.displayName}` : '模型目录'"
      width="620px"
      data-testid="product-models-dialog"
      @update:open="modelsVisible = false"
    >
      <div v-if="modelsError" class="ui-alert ui-alert--error">{{ modelsError }}</div>
      <div v-if="modelsLoading" class="ui-panel-sub">加载中…</div>
      <div v-else class="next-providers__model-list" data-testid="product-models-list">
        <div v-for="m in models" :key="m.id" class="next-providers__model-row">
          <div class="next-providers__model-info">
            <span class="ui-mono">{{ m.modelId }}</span>
            <span class="next-providers__balance">{{ m.displayName || '—' }}</span>
          </div>
          <UiStatusBadge
            :tone="m.source === 'MANUAL' ? 'warning' : 'success'"
            :label="m.source === 'MANUAL' ? '人工' : '官方'"
          />
          <UiButton
            v-if="m.source === 'MANUAL'"
            variant="ghost"
            size="sm"
            class="next-providers__danger"
            :data-testid="`product-model-delete-${m.modelId}`"
            @click="removeManualModel(m)"
            >删除</UiButton
          >
        </div>
        <p v-if="!models.length" class="next-providers__empty">
          暂无目录模型。探测失败时可在此手工补录。
        </p>
      </div>
      <div class="next-providers__model-form" data-testid="product-models-form">
        <div v-if="modelError" class="ui-alert ui-alert--error">{{ modelError }}</div>
        <div class="next-providers__model-form-row">
          <UiInput
            v-model="modelForm.modelId"
            label="模型 ID"
            placeholder="manual-fallback-model"
            data-testid="product-models-id"
          />
          <UiInput
            v-model="modelForm.displayName"
            label="显示名（可选）"
            data-testid="product-models-name"
          />
          <UiButton
            variant="secondary"
            :loading="modelSaving"
            data-testid="product-models-add"
            @click="addManualModel"
            >录入人工模型</UiButton
          >
        </div>
      </div>
      <template #footer>
        <UiButton variant="secondary" @click="modelsVisible = false">关闭</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
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

.next-providers__provider {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.next-providers__name {
  font-weight: var(--ui-weight-medium);
}

.next-providers__code {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}

.next-providers__balance {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-providers__model-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.next-providers__model-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.next-providers__model-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}
.next-providers__model-form-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 14px;
  flex-wrap: wrap;
}
.next-providers__danger {
  color: var(--ui-danger-fg);
}
.next-providers__empty {
  color: var(--ui-foreground-faint);
  font-size: var(--ui-font-size-sm);
}
</style>
