<script setup lang="ts">
/**
 * NextAdminUsageView — /app/admin-usage v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy tenant-wide usage report: filter bar
 * (grouping + project/model id), summary strip, records table and pager.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiInput, UiSelect, UiStatusBadge, UiTable } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type { UsageGroupBy, UsageRecordPage, UsageSummary } from '@/types/api';

const groupBy = ref<UsageGroupBy>('project');
const modelId = ref('');
const projectId = ref('');
const summary = ref<UsageSummary | null>(null);
const summaryLoading = ref(true);
const summaryError = ref('');
const summaryRequestId = ref('');

const records = ref<UsageRecordPage | null>(null);
const recordsLoading = ref(true);
const page = ref(1);
const pageSize = ref(20);

const groupOptions: UiSelectOption[] = [
  { value: 'project', label: '项目' },
  { value: 'virtual_key', label: 'Virtual Key' },
  { value: 'cache_level', label: '缓存层级' },
  { value: 'day', label: '日' },
];

const columns = [
  { key: 'occurredAt', title: '时间', width: '180px' },
  { key: 'modelId', title: '模型', minWidth: '170px' },
  { key: 'inputTokens', title: '输入', width: '110px', align: 'right' as const },
  { key: 'outputTokens', title: '输出', width: '110px', align: 'right' as const },
  { key: 'cacheLevel', title: '缓存层级', width: '120px' },
  { key: 'upstreamStatusCode', title: '状态码', width: '90px', align: 'right' as const },
  { key: 'usageMissing', title: 'Usage', width: '90px' },
  { key: 'gatewayRequestId', title: 'Request ID', minWidth: '230px' },
];

async function load() {
  summaryLoading.value = true;
  recordsLoading.value = true;
  summaryError.value = '';
  try {
    summary.value = await api.adminUsageSummary({
      groupBy: groupBy.value,
      modelId: modelId.value || undefined,
      projectId: projectId.value || undefined,
    });
    records.value = await api.adminUsageRecords({
      modelId: modelId.value || undefined,
      projectId: projectId.value || undefined,
      page: page.value,
      size: pageSize.value,
    });
  } catch (error) {
    if (error instanceof ApiError) {
      summaryError.value = error.message;
      summaryRequestId.value = error.requestId ?? '';
    }
  } finally {
    summaryLoading.value = false;
    recordsLoading.value = false;
  }
}

function gotoPage(next: number) {
  if (next < 1) return;
  page.value = next;
  void load();
}

function fmtNum(value: number | undefined): string {
  return (value ?? 0).toLocaleString();
}

function fmtMoney(value: string | undefined): string {
  return Number(value ?? 0).toFixed(4);
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const cacheLabel: Record<string, string> = {
  UPSTREAM: 'upstream',
  COALESCED: 'coalesced',
  L1_HIT: 'L1 hit',
  L2_HIT: 'L2 hit',
};

onMounted(load);
</script>

<template>
  <div class="ui-page next-admin-usage">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">用量报表</h1>
        <p class="ui-page-desc">全租户用量：筛选条件 → 汇总 → 明细表。</p>
      </div>
    </header>

    <section class="ui-panel next-admin-usage__filters" data-testid="usage-filter-bar">
      <div class="ui-panel-toolbar">
        <UiSelect
          v-model="groupBy"
          :options="groupOptions"
          data-testid="usage-group-by"
          @change="load"
        />
        <UiInput
          v-model="projectId"
          placeholder="项目 ID（可选）"
          width="220px"
          data-testid="usage-project-id"
        />
        <UiInput
          v-model="modelId"
          placeholder="模型 ID（可选）"
          width="220px"
          data-testid="usage-model-id"
        />
        <UiButton
          variant="primary"
          data-testid="usage-query"
          @click="
            page = 1;
            load();
          "
          >查询</UiButton
        >
      </div>
      <div
        v-if="summary && !summaryLoading"
        class="next-admin-usage__summary"
        data-testid="usage-summary"
      >
        <span
          >请求 <b class="ui-num">{{ fmtNum(summary.totals?.requests?.upstream) }}</b></span
        >
        <span
          >输入 tokens <b class="ui-num">{{ fmtNum(summary.totals?.tokens?.input) }}</b></span
        >
        <span
          >输出 tokens <b class="ui-num">{{ fmtNum(summary.totals?.tokens?.output) }}</b></span
        >
        <span
          >上游成本 <b class="ui-num">¥{{ fmtMoney(summary.totals?.cost?.upstreamPaid) }}</b></span
        >
      </div>
    </section>

    <div v-if="summaryError" class="ui-alert ui-alert--error">
      {{ summaryError
      }}<span v-if="summaryRequestId" class="ui-request-id">
        requestId: {{ summaryRequestId }}</span
      >
    </div>

    <section class="ui-panel">
      <UiTable
        :columns="columns"
        :data="records?.items ?? []"
        :loading="recordsLoading"
        row-key="gatewayRequestId"
        empty-title="没有用量记录"
        data-testid="usage-records-table"
      >
        <template #occurredAt="{ row }">{{
          formatTime((row as UsageRecordPage['items'][number]).occurredAt)
        }}</template>
        <template #modelId="{ row }">
          <span class="ui-mono">{{ (row as UsageRecordPage['items'][number]).modelId }}</span>
        </template>
        <template #inputTokens="{ row }">
          <span class="ui-num">{{
            (row as UsageRecordPage['items'][number]).inputTokens ?? 0
          }}</span>
        </template>
        <template #outputTokens="{ row }">
          <span class="ui-num">{{
            (row as UsageRecordPage['items'][number]).outputTokens ?? 0
          }}</span>
        </template>
        <template #cacheLevel="{ row }">
          <UiStatusBadge
            :label="
              cacheLabel[(row as UsageRecordPage['items'][number]).cacheLevel] ??
              (row as UsageRecordPage['items'][number]).cacheLevel
            "
          />
        </template>
        <template #upstreamStatusCode="{ row }">
          <span class="ui-num">{{
            (row as UsageRecordPage['items'][number]).upstreamStatusCode ?? '—'
          }}</span>
        </template>
        <template #usageMissing="{ row }">
          <UiStatusBadge
            :tone="(row as UsageRecordPage['items'][number]).usageMissing ? 'warning' : 'success'"
            :label="(row as UsageRecordPage['items'][number]).usageMissing ? 'missing' : 'ok'"
          />
        </template>
        <template #gatewayRequestId="{ row }">
          <span class="ui-mono">{{
            (row as UsageRecordPage['items'][number]).gatewayRequestId
          }}</span>
        </template>
      </UiTable>
    </section>

    <div class="next-admin-usage__pager">
      <UiButton
        variant="secondary"
        :disabled="page <= 1"
        data-testid="usage-prev"
        @click="gotoPage(page - 1)"
      >
        上一页
      </UiButton>
      <span class="ui-num next-admin-usage__pager-text"
        >第 {{ page }} 页 / 共 {{ records?.total ?? 0 }} 条</span
      >
      <UiButton
        variant="secondary"
        :disabled="(records?.items ?? []).length < pageSize"
        data-testid="usage-next"
        @click="gotoPage(page + 1)"
      >
        下一页
      </UiButton>
    </div>
  </div>
</template>

<style scoped>
.next-admin-usage__filters {
  margin-bottom: var(--ui-space-4);
}

.next-admin-usage__filters :deep(.ui-panel-toolbar) {
  flex-wrap: wrap;
}

.next-admin-usage__summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-5);
  padding: var(--ui-space-3) var(--ui-space-5);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__summary b {
  color: var(--ui-foreground);
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

.next-admin-usage__pager {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  margin-top: var(--ui-space-4);
}

.next-admin-usage__pager-text {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}
</style>
