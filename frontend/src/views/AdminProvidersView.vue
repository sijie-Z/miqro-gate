<script setup lang="ts">
import { onMounted, ref } from 'vue';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { ProviderProductView } from '@/types/api';

const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const tableColumns: PrimaryTableCol[] = [
  { colKey: 'provider', title: '供应商', width: 200 },
  { colKey: 'product', title: '产品', minWidth: 200 },
  { colKey: 'protocols', title: '协议', width: 200 },
  { colKey: 'baseUrl', title: 'Base URL', minWidth: 200 },
  { colKey: 'implementationStatus', title: '实现状态', width: 120 },
  { colKey: 'balanceAuthority', title: '余额来源', width: 110 },
];

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

function implClass(status: string): string {
  switch (status) {
    case 'VERIFIED':
      return 'mk-status--success';
    case 'IMPLEMENTED':
      return 'mk-status--warning';
    case 'DEGRADED':
      return 'mk-status--danger';
    default:
      return 'mk-status--neutral';
  }
}

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

onMounted(load);
</script>

<template>
  <div class="providers-page">
    <PageHeader
      title="Providers"
      description="供应商产品实例：协议、Plan 形态、验证状态与余额来源。"
    />

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        :data="products"
        :columns="tableColumns"
        row-key="id"
        size="small"
        data-testid="products-table"
      >
        <template #provider="{ row }">
          <div class="mk-provider-cell">
            <span
              class="mk-brand-chip mk-brand-chip--sm"
              :class="chipClass(row.providerSlug)"
              aria-hidden="true"
            >
              {{ chipLetter(row.providerName) }}
            </span>
            <span>{{ row.providerName }}</span>
          </div>
        </template>
        <template #product="{ row }">
          <div class="product-name">{{ row.displayName }}</div>
          <div class="mk-mono product-code">{{ row.productCode }}</div>
        </template>
        <template #protocols="{ row }">
          <span class="mk-mono">{{ row.protocols }}</span>
        </template>
        <template #baseUrl="{ row }">
          <span class="mk-mono">{{ row.baseUrlHost || '—' }}</span>
        </template>
        <template #implementationStatus="{ row }">
          <span class="mk-status" :class="implClass(row.implementationStatus)">{{
            row.implementationStatus
          }}</span>
        </template>
        <template #balanceAuthority="{ row }">
          <span class="mk-status mk-status--neutral">{{ balanceLabel(row.balanceAuthority) }}</span>
        </template>
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.product-name {
  font-weight: 500;
}

.product-code {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
}
</style>
