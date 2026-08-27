<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { ProviderProductView } from '@/types/api';

const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

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

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <el-table v-loading="loading" :data="products" data-testid="products-table">
      <el-table-column label="供应商" width="200">
        <template #default="{ row }">
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
      </el-table-column>
      <el-table-column label="产品" min-width="200">
        <template #default="{ row }">
          <div class="product-name">{{ row.displayName }}</div>
          <div class="mk-mono product-code">{{ row.productCode }}</div>
        </template>
      </el-table-column>
      <el-table-column label="协议" width="200">
        <template #default="{ row }">
          <span class="mk-mono">{{ row.protocols }}</span>
        </template>
      </el-table-column>
      <el-table-column label="Base URL" min-width="200">
        <template #default="{ row }">
          <span class="mk-mono">{{ row.baseUrlHost || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="实现状态" width="120">
        <template #default="{ row }">
          <span class="mk-status" :class="implClass(row.implementationStatus)">{{
            row.implementationStatus
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="余额来源" width="110">
        <template #default="{ row }">
          <span class="mk-status mk-status--neutral">{{ balanceLabel(row.balanceAuthority) }}</span>
        </template>
      </el-table-column>
    </el-table>
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
