<script setup lang="ts">
/**
 * NextAdminAuditView — /app/audit v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy audit page: reverse chain list with an
 * action filter; chain hashes are never serialized.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiInput, UiTable } from '@/ui';
import type { AuditEventView } from '@/types/generated-api';

const events = ref<AuditEventView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');
const actionFilter = ref('');

const columns = [
  { key: 'chainPosition', title: '位置', width: '90px', align: 'right' as const },
  { key: 'createdAt', title: '时间', width: '180px' },
  { key: 'action', title: '动作', width: '200px' },
  { key: 'targetType', title: '目标类型', width: '120px' },
  { key: 'changeSummary', title: '摘要', minWidth: '260px' },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    events.value = await api.auditEvents({
      action: actionFilter.value.trim() || undefined,
    });
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
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-audit">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">审计日志</h1>
        <p class="ui-page-desc">哈希链完整性校验的审计事件；链哈希不会出现在任何响应中。</p>
      </div>
    </header>

    <section class="ui-panel next-audit__filter">
      <div class="ui-panel-toolbar">
        <UiInput
          v-model="actionFilter"
          placeholder="按动作过滤，如 LOGIN_SUCCESS"
          width="280px"
          data-testid="audit-action-filter"
        />
        <UiButton variant="primary" data-testid="audit-refresh" @click="load">查询</UiButton>
      </div>
    </section>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section class="ui-panel">
      <UiTable
        :columns="columns"
        :data="events"
        :loading="loading"
        row-key="id"
        empty-title="没有匹配的审计事件"
        data-testid="audit-table"
      >
        <template #chainPosition="{ row }">
          <span class="ui-num">{{ (row as AuditEventView).chainPosition }}</span>
        </template>
        <template #createdAt="{ row }">{{
          formatTime((row as AuditEventView).createdAt)
        }}</template>
        <template #action="{ row }">
          <span class="ui-mono">{{ (row as AuditEventView).action }}</span>
        </template>
        <template #targetType="{ row }">{{ (row as AuditEventView).targetType || '—' }}</template>
        <template #changeSummary="{ row }">
          <span class="next-audit__summary">{{
            (row as AuditEventView).changeSummary || '—'
          }}</span>
        </template>
      </UiTable>
    </section>
  </div>
</template>

<style scoped>
.next-audit__filter {
  margin-bottom: var(--ui-space-5);
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

.next-audit__summary {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}
</style>
