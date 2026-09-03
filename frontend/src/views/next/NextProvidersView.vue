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
import { UiStatusBadge, UiTable } from '@/ui';
import type { ProviderProductView } from '@/types/api';

const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'provider', title: '供应商', width: '220px' },
  { key: 'product', title: '产品', minWidth: '220px' },
  { key: 'protocols', title: '协议', width: '190px' },
  { key: 'baseUrl', title: 'Base URL', minWidth: '220px' },
  { key: 'implementationStatus', title: '实现状态', width: '130px' },
  { key: 'balanceAuthority', title: '余额来源', width: '120px' },
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
              :class="chipClass((row as ProviderProductView).providerSlug)"
              aria-hidden="true"
              >{{ chipLetter((row as ProviderProductView).providerName) }}</span
            >
            <span>{{ (row as ProviderProductView).providerName }}</span>
          </span>
        </template>
        <template #product="{ row }">
          <div class="next-providers__name">{{ (row as ProviderProductView).displayName }}</div>
          <div class="ui-mono next-providers__code">
            {{ (row as ProviderProductView).productCode }}
          </div>
        </template>
        <template #protocols="{ row }">
          <span class="ui-mono">{{ (row as ProviderProductView).protocols }}</span>
        </template>
        <template #baseUrl="{ row }">
          <span class="ui-mono">{{ (row as ProviderProductView).baseUrlHost || '—' }}</span>
        </template>
        <template #implementationStatus="{ row }">
          <UiStatusBadge
            :tone="implTone((row as ProviderProductView).implementationStatus)"
            :label="
              implLabel[(row as ProviderProductView).implementationStatus] ??
              (row as ProviderProductView).implementationStatus
            "
          />
        </template>
        <template #balanceAuthority="{ row }">
          <span class="next-providers__balance">{{
            balanceLabel((row as ProviderProductView).balanceAuthority)
          }}</span>
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
</style>
