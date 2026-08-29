<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
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

const columns: PrimaryTableCol[] = [
  { colKey: 'product', title: '供应商产品', minWidth: 180 },
  { colKey: 'modelId', title: '模型', minWidth: 180 },
  { colKey: 'tokenType', title: '类型', width: 100 },
  { colKey: 'unitPrice', title: '单价', width: 160, align: 'right' },
  { colKey: 'effectiveFrom', title: '生效时间', width: 170 },
  { colKey: 'source', title: '来源', width: 110 },
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
    MessagePlugin.success('单价快照已生效（仅影响此后的成本计算）');
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
  return new Date(iso).toLocaleString();
}

onMounted(load);

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
</script>

<template>
  <div class="prices-page">
    <PageHeader
      title="模型单价"
      description="按（产品、模型、Token 类型）配置每百万 Token 单价，驱动成本计算。单价是不可变快照：修改即追加，历史成本不重算。"
    >
      <template #actions>
        <t-button theme="primary" data-testid="price-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '新增单价' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" class="block-alert" data-testid="prices-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="creating" class="create-panel" data-testid="price-create-form">
      <h3 class="panel-title">新增单价快照</h3>
      <t-form label-align="top" class="create-form">
        <t-form-item label="供应商产品" required-mark>
          <t-select
            v-model="form.providerProductId"
            placeholder="选择产品"
            class="full-width"
            data-testid="price-create-product"
          >
            <t-option
              v-for="p in products"
              :key="p.id"
              :value="p.id"
              :label="`${p.displayName}（${p.baseUrlHost}）`"
            />
          </t-select>
        </t-form-item>
        <t-form-item label="模型" required-mark>
          <t-input
            v-model="form.modelId"
            placeholder="例如 claude-3-7-sonnet"
            class="mk-mono"
            data-testid="price-create-model"
          />
        </t-form-item>
        <div class="form-row">
          <t-form-item label="Token 类型" required-mark>
            <t-select v-model="form.tokenType" data-testid="price-create-type">
              <t-option label="输入" value="INPUT" />
              <t-option label="输出" value="OUTPUT" />
              <t-option label="缓存读" value="CACHE_READ" />
              <t-option label="缓存写" value="CACHE_CREATION" />
            </t-select>
          </t-form-item>
          <t-form-item label="货币" required-mark>
            <t-select v-model="form.currency" data-testid="price-create-currency">
              <t-option label="CNY（¥）" value="CNY" />
              <t-option label="USD（$）" value="USD" />
            </t-select>
          </t-form-item>
        </div>
        <t-form-item label="单价（每 1M Tokens）" required-mark>
          <t-input
            v-model="form.unitPrice"
            type="number"
            step="0.0001"
            placeholder="例如 3.0000"
            class="mk-num"
            data-testid="price-create-unit"
          />
        </t-form-item>
        <t-form-item label="来源">
          <t-select v-model="form.source" data-testid="price-create-source">
            <t-option label="人工录入" value="MANUAL" />
            <t-option label="官方同步" value="OFFICIAL" />
          </t-select>
        </t-form-item>
        <t-alert v-if="formError" theme="error" class="form-error" data-testid="price-create-error">
          {{ formError
          }}<span v-if="formRequestId" class="mk-mono">requestId: {{ formRequestId }}</span>
        </t-alert>
        <div class="form-actions">
          <t-button
            theme="primary"
            :disabled="!canCreate"
            :loading="submitting"
            data-testid="price-create-submit"
            @click="createPrice"
          >
            创建单价
          </t-button>
          <t-button @click="creating = false">取消</t-button>
        </div>
      </t-form>
    </section>

    <div class="mk-filter-bar" data-testid="prices-filter-bar">
      <span class="mk-stat-hint">共 {{ prices.length }} 条生效单价</span>
    </div>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="columns"
        :data="prices"
        class="prices-table"
        data-testid="prices-table"
      >
        <template #product="{ row }">{{ productName(row.providerProductId) }}</template>
        <template #modelId="{ row }">
          <span class="mk-mono">{{ row.modelId }}</span>
        </template>
        <template #tokenType="{ row }">
          {{ tokenTypeLabel[row.tokenType] ?? row.tokenType }}
        </template>
        <template #unitPrice="{ row }">
          <span class="mk-num"
            >{{ row.currency === 'USD' ? '$' : '¥' }}{{ Number(row.unitPrice).toFixed(4) }} /
            1M</span
          >
        </template>
        <template #effectiveFrom="{ row }">{{ formatTime(row.effectiveFrom) }}</template>
        <template #source="{ row }">
          <span
            class="mk-status"
            :class="row.source === 'OFFICIAL' ? 'mk-status--success' : 'mk-status--neutral'"
          >
            {{ sourceLabel[row.source] ?? row.source }}
          </span>
        </template>
        <template #empty>
          <div class="table-empty">
            <p>还没有模型单价。</p>
            <p class="hint">
              录入单价后，用量成本与首页成本分布才会显示金额；未配置单价的模型成本显示为「—」。
            </p>
          </div>
        </template>
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: 760px;
}

.panel-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}

.create-form {
  max-width: 520px;
}

.form-row {
  display: flex;
  gap: 16px;
}

.form-row .t-form__item {
  flex: 1;
}

.full-width {
  width: 100%;
}

.form-error {
  margin-bottom: 12px;
}

.form-actions {
  display: flex;
  gap: 8px;
}

.prices-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.table-empty {
  padding: 24px 0;
  color: var(--miqrokey-text-secondary);
}

.table-empty .hint {
  font-size: 13px;
  color: var(--miqrokey-text-disabled);
  margin: 4px 0 0;
}
</style>
