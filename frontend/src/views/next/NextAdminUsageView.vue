<script setup lang="ts">
/**
 * NextAdminUsageView — /app/admin-usage v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy tenant-wide usage report: filter bar
 * (grouping + project/model id), summary strip, records table and pager.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ChartBarIcon, DownloadIcon, MoneyIcon, UploadIcon } from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import UsageCaliberTip from '@/components/UsageCaliberTip.vue';
import { UiButton, UiInput, UiSelect, UiStatusBadge, UiTable } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type {UsageGroupBy} from '@/types/api';
import type { UsageRecordPage, UsageSummary } from '@/types/generated-api';

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

// ---- time range presets (server default when 0 — behaviour unchanged) ----
const rangeDays = ref<number>(0);
const windowOptions = [
  { value: 0, label: '默认' },
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 93, label: '近 93 天' },
];

function rangeParams(): { from?: string; to?: string } {
  if (!rangeDays.value) return {};
  const to = new Date();
  return {
    from: new Date(to.getTime() - rangeDays.value * 24 * 3600 * 1000).toISOString(),
    to: to.toISOString(),
  };
}

function applyRange(value: number) {
  rangeDays.value = value;
  page.value = 1;
  void load();
}

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
      ...rangeParams(),
    });
    records.value = await api.adminUsageRecords({
      modelId: modelId.value || undefined,
      projectId: projectId.value || undefined,
      page: page.value,
      size: pageSize.value,
      ...rangeParams(),
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

    <UsageCaliberTip />

    <section class="ui-panel next-admin-usage__filters" data-testid="usage-filter-bar">
      <div class="ui-panel-toolbar">
        <div class="next-admin-usage__range" aria-label="时间范围">
          <button
            v-for="w in windowOptions"
            :key="w.value"
            type="button"
            class="next-admin-usage__seg"
            :class="{ 'next-admin-usage__seg--on': rangeDays === w.value }"
            :data-testid="`admin-usage-range-${w.value}`"
            @click="applyRange(w.value)"
          >
            {{ w.label }}
          </button>
        </div>
        <UiSelect
          v-model="groupBy"
          :options="groupOptions"
          data-testid="usage-group-by"
          @change="load"
        />
        <UiInput
          v-model="projectId"
          placeholder="项目 ID（可选）"
          width="200px"
          data-testid="usage-project-id"
        />
        <UiInput
          v-model="modelId"
          placeholder="模型 ID（可选）"
          width="200px"
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
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--blue">
            <ChartBarIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">请求</span>
            <span class="next-admin-usage__stat-value ui-num">{{
              fmtNum(summary.totals?.requests?.upstream)
            }}</span>
          </span>
        </div>
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--green">
            <DownloadIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">输入 tokens</span>
            <span class="next-admin-usage__stat-value ui-num">{{
              fmtNum(summary.totals?.tokens?.input)
            }}</span>
          </span>
        </div>
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--cyan">
            <UploadIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">输出 tokens</span>
            <span class="next-admin-usage__stat-value ui-num">{{
              fmtNum(summary.totals?.tokens?.output)
            }}</span>
          </span>
        </div>
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--gold">
            <MoneyIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">上游成本</span>
            <span class="next-admin-usage__stat-value ui-num"
              >¥{{ fmtMoney(summary.totals?.cost?.upstreamPaid) }}</span
            >
          </span>
        </div>
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
          <span class="ui-mono next-admin-usage__reqid">{{
            (row as UsageRecordPage['items'][number]).gatewayRequestId
          }}</span>
        </template>
      </UiTable>
    </section>

    <div class="next-admin-usage__pager">
      <span class="next-admin-usage__pager-text">共 {{ records?.total ?? 0 }} 条</span>
      <UiButton
        variant="secondary"
        :disabled="page <= 1"
        data-testid="usage-prev"
        @click="gotoPage(page - 1)"
      >
        上一页
      </UiButton>
      <span class="next-admin-usage__pager-text next-admin-usage__pager-current"
        >第 {{ page }} 页</span
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

.next-admin-usage__range {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-admin-usage__seg {
  height: 28px;
  padding: 0 var(--ui-space-3);
  border: 0;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
  transition:
    color var(--ui-ease),
    background-color var(--ui-ease);
}

.next-admin-usage__seg:hover {
  color: var(--ui-foreground);
}

.next-admin-usage__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

/* keep the filter controls in one tight cluster (no space-between spread) */
.next-admin-usage__filters :deep(.ui-panel-toolbar) {
  flex-wrap: wrap;
  justify-content: flex-start;
}

.next-admin-usage__summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: var(--ui-space-4);
  padding: var(--ui-space-4) var(--ui-space-5);
  border-top: 1px solid var(--ui-border-muted);
}

.next-admin-usage__stat {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-admin-usage__stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  flex-shrink: 0;
}

.next-admin-usage__stat-icon svg {
  width: 18px;
  height: 18px;
}

.next-admin-usage__stat-icon--blue {
  background: var(--ui-info-bg);
  color: var(--ui-info-fg);
}

.next-admin-usage__stat-icon--green {
  background: var(--ui-success-bg);
  color: var(--ui-success-fg);
}

.next-admin-usage__stat-icon--cyan {
  background: #e0f4f6;
  color: #0e7490;
}

.next-admin-usage__stat-icon--gold {
  background: #fdf3e0;
  color: #a16207;
}

.next-admin-usage__stat-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.next-admin-usage__stat-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  white-space: nowrap;
}

.next-admin-usage__stat-value {
  font-size: 20px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
  letter-spacing: -0.01em;
  white-space: nowrap;
}

.next-admin-usage__reqid {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
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
  justify-content: flex-end;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-4);
}

.next-admin-usage__pager-text {
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__pager-current {
  color: var(--ui-foreground);
  font-weight: var(--ui-weight-medium);
}
</style>
