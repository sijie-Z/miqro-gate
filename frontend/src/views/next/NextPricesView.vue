<script setup lang="ts">
/**
 * NextPricesView — /app/prices v2 admin page (U2 platform batch).
 * Behaviour parity with legacy prices page: append-only unit-price snapshots
 * driving cost accounting. Rendering on the v2 system; APIs untouched.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDrawer, UiInput, UiSelect, UiTable, toast } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type { PriceSnapshotView, ProviderProductView } from '@/types/api';

const prices = ref<PriceSnapshotView[]>([]);
const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const productById = computed(
  () => new Map(products.value.map((p) => [p.id, p]) as [string, ProviderProductView][]),
);

function productName(productId: string): string {
  return productById.value.get(productId)?.displayName ?? productId;
}

const tokenTypeLabel: Record<string, string> = {
  INPUT: '输入',
  OUTPUT: '输出',
  CACHE_READ: '缓存读',
  CACHE_CREATION: '缓存写',
};

const sourceLabel: Record<string, string> = {
  MANUAL: '人工录入',
  OFFICIAL: '官方同步',
};

const productOptions = computed<UiSelectOption[]>(() =>
  products.value.map((p) => ({
    value: p.id,
    label: `${p.providerName} · ${p.displayName}（${p.baseUrlHost}）`,
  })),
);

const columns = [
  { key: 'product', title: '供应商产品', minWidth: '220px' },
  { key: 'modelId', title: '模型', minWidth: '190px' },
  { key: 'tokenType', title: '类型', width: '90px' },
  { key: 'unitPrice', title: '单价', width: '150px', align: 'right' as const },
  { key: 'effectiveFrom', title: '生效时间', width: '170px' },
  { key: 'source', title: '来源', width: '100px' },
];

// Create form (append-only snapshot: edits never touch history)
const creating = ref(false);
const form = ref({
  providerProductId: '',
  modelId: '',
  tokenType: 'INPUT',
  currency: 'CNY',
  unitPrice: '',
  source: 'MANUAL',
});
const submitting = ref(false);
const formError = ref('');
const formRequestId = ref('');

const canCreate = computed(
  () =>
    form.value.providerProductId !== '' &&
    form.value.modelId.trim().length > 0 &&
    Number(form.value.unitPrice) > 0,
);

const tokenTypeOptions: UiSelectOption[] = [
  { value: 'INPUT', label: '输入' },
  { value: 'OUTPUT', label: '输出' },
  { value: 'CACHE_READ', label: '缓存读' },
  { value: 'CACHE_CREATION', label: '缓存写' },
];

const currencyOptions: UiSelectOption[] = [
  { value: 'CNY', label: 'CNY（¥）' },
  { value: 'USD', label: 'USD（$）' },
];

const sourceOptions: UiSelectOption[] = [
  { value: 'MANUAL', label: '人工录入' },
  { value: 'OFFICIAL', label: '官方同步' },
];

function resetForm() {
  form.value = {
    providerProductId: '',
    modelId: '',
    tokenType: 'INPUT',
    currency: 'CNY',
    unitPrice: '',
    source: 'MANUAL',
  };
  formError.value = '';
  formRequestId.value = '';
}

async function createPrice() {
  if (!canCreate.value) {
    formError.value = '产品、模型与大于 0 的单价必填。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  formRequestId.value = '';
  try {
    await api.createPrice({
      providerProductId: form.value.providerProductId,
      modelId: form.value.modelId.trim(),
      tokenType: form.value.tokenType,
      currency: form.value.currency,
      unitPrice: String(form.value.unitPrice),
      source: form.value.source,
    });
    toast.success('单价快照已生效（仅影响此后的成本计算）');
    creating.value = false;
    resetForm();
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      formError.value = error.message;
      formRequestId.value = error.requestId ?? '';
    } else {
      formError.value = '创建失败，请稍后重试。';
    }
  } finally {
    submitting.value = false;
  }
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [priceList, productList] = await Promise.all([
      api.listPrices(),
      api.listProviderProducts(),
    ]);
    prices.value = priceList;
    products.value = productList;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载模型单价失败。';
    }
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-prices">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">模型单价</h1>
        <p class="ui-page-desc">
          按（产品、模型、Token 类型）配置每百万 Token
          单价，驱动成本计算。单价是不可变快照：修改即追加，历史成本不重算。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="price-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '新增单价' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="prices-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section v-if="creating" class="ui-panel next-prices__create" data-testid="price-create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">新增单价快照</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-prices__grid">
          <UiSelect
            v-model="form.providerProductId"
            label="供应商产品"
            required
            placeholder="选择产品"
            :options="productOptions"
            width="100%"
            data-testid="price-create-product"
          />
          <UiInput
            v-model="form.modelId"
            label="模型 ID"
            required
            placeholder="例如 claude-3-7-sonnet"
            data-testid="price-create-model"
          />
          <UiSelect
            v-model="form.tokenType"
            label="Token 类型"
            :options="tokenTypeOptions"
            data-testid="price-create-type"
          />
          <UiSelect
            v-model="form.currency"
            label="货币"
            :options="currencyOptions"
            data-testid="price-create-currency"
          />
          <UiInput
            v-model="form.unitPrice"
            label="单价（每 1M Tokens）"
            required
            type="number"
            step="0.0001"
            placeholder="例如 3.0000"
            data-testid="price-create-unit"
          />
          <UiSelect
            v-model="form.source"
            label="来源"
            :options="sourceOptions"
            data-testid="price-create-source"
          />
          <p v-if="formError" class="ui-form-error" data-testid="price-create-error">
            {{ formError
            }}<span v-if="formRequestId" class="ui-request-id">
              requestId: {{ formRequestId }}</span
            >
          </p>
          <div class="next-prices__actions">
            <UiButton
              variant="primary"
              :disabled="!canCreate"
              :loading="submitting"
              data-testid="price-create-submit"
              @click="createPrice"
            >
              创建单价
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ prices.length }} 条生效单价</span>
      </div>
      <UiTable
        :columns="columns"
        :data="prices"
        :loading="loading"
        row-key="id"
        empty-title="还没有单价快照"
        data-testid="prices-table"
      >
        <template #product="{ row }">{{
          productName((row as PriceSnapshotView).providerProductId)
        }}</template>
        <template #modelId="{ row }">
          <span class="ui-mono">{{ (row as PriceSnapshotView).modelId }}</span>
        </template>
        <template #tokenType="{ row }">{{
          tokenTypeLabel[(row as PriceSnapshotView).tokenType] ??
          (row as PriceSnapshotView).tokenType
        }}</template>
        <template #unitPrice="{ row }">
          <span class="next-prices__price ui-num"
            >{{ (row as PriceSnapshotView).currency === 'USD' ? '$' : '¥'
            }}{{ Number((row as PriceSnapshotView).unitPrice).toFixed(4) }} / 1M</span
          >
        </template>
        <template #effectiveFrom="{ row }">{{
          formatTime((row as PriceSnapshotView).effectiveFrom)
        }}</template>
        <template #source="{ row }">
          {{ sourceLabel[(row as PriceSnapshotView).source] ?? (row as PriceSnapshotView).source }}
        </template>
      </UiTable>
    </section>
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

.next-prices__create {
  margin-bottom: var(--ui-space-5);
  max-width: 820px;
}

.next-prices__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4) var(--ui-space-6);
  max-width: 720px;
}

.next-prices__actions {
  display: flex;
  gap: var(--ui-space-2);
  grid-column: 1 / -1;
}

.next-prices__price {
  font-variant-numeric: tabular-nums;
}
</style>
