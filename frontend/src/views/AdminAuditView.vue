<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { AuditEventView } from '@/types/api';

const events = ref<AuditEventView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');
const actionFilter = ref('');

async function load() {
  loading.value = true;
  try {
    events.value = await api.auditEvents({ size: 100, action: actionFilter.value || undefined });
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString();
}

onMounted(load);
</script>

<template>
  <div class="audit-page">
    <PageHeader title="Audit" description="不可修改的管理审计链（按因果提交序倒序）。" />

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <div class="mk-filter-bar">
      <t-input
        v-model="actionFilter"
        placeholder="按 action 过滤（例如 USER_CREATE）"
        class="filter-input"
      />
      <t-button theme="primary" data-testid="audit-refresh" @click="load">查询</t-button>
    </div>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        :data="events"
        data-testid="audit-table"
        row-key="id"
        size="small"
        :columns="[
          { colKey: 'chainPosition', title: '位置', width: 90, align: 'right' },
          { colKey: 'createdAt', title: '时间', width: 170 },
          { colKey: 'action', title: 'Action', width: 200 },
          { colKey: 'targetType', title: '目标类型', width: 120 },
          { colKey: 'changeSummary', title: '摘要', minWidth: 240 },
        ]"
      >
        <template #chainPosition="{ row }"
          ><span class="mk-num">{{ row.chainPosition }}</span></template
        >
        <template #createdAt="{ row }">{{ formatTime(row.createdAt) }}</template>
        <template #action="{ row }">
          <span class="mk-mono">{{ row.action }}</span>
        </template>
        <template #targetType="{ row }">{{ row.targetType ?? '—' }}</template>
        <template #changeSummary="{ row }"
          ><span class="mk-mono">{{ row.changeSummary ?? '—' }}</span></template
        >
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.filter-input {
  width: 280px;
}
</style>
