<script setup lang="ts">
/**
 * NextAdminUsageView — /app/admin-usage v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy tenant-wide usage report: filter bar
 * (grouping + project/model id), summary strip, records table and pager.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ChartBarIcon, DownloadIcon, MoneyIcon, UploadIcon } from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import UsageCaliberTip from '@/components/UsageCaliberTip.vue';
import { UiButton, UiInput, UiSelect, UiStatusBadge, UiTable, UiTrendChart } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type { UsageGroupBy } from '@/types/api';
import type {
  UsageRecord,
  UsageRecordPage,
  UsageSummary,
  HourlyUsageReport,
  HourlyUsageRow,
} from '@/types/generated-api';

const groupBy = ref<UsageGroupBy>('project');
const modelId = ref('');
const projectId = ref('');
// #605: abuse forensics — filter the whole report down to one calling address.
const clientIp = ref('');
const summary = ref<UsageSummary | null>(null);
const summaryLoading = ref(true);
const summaryError = ref('');
const summaryRequestId = ref('');

const records = ref<UsageRecordPage | null>(null);
const recordsLoading = ref(true);

// ---- daily trend (aggregated from the loaded records page) ----
type TrendMetric = 'tokens' | 'requests' | 'latency';

const TREND_TABS: Array<{ value: TrendMetric; label: string }> = [
  { value: 'tokens', label: 'Token' },
  { value: 'requests', label: '请求' },
  { value: 'latency', label: '平均延迟' },
];

const trendMetric = ref<TrendMetric>('tokens');

const trendPoints = computed(() => {
  const items = records.value?.items ?? [];
  const byDay = new Map<string, { sum: number; count: number }>();
  for (const r of items) {
    const day = String(r.occurredAt ?? '').slice(0, 10);
    if (!day) continue;
    const entry = byDay.get(day) ?? { sum: 0, count: 0 };
    if (trendMetric.value === 'tokens') {
      entry.sum += r.totalTokens ?? (r.inputTokens ?? 0) + (r.outputTokens ?? 0);
    } else if (trendMetric.value === 'requests') {
      entry.sum += 1;
    } else {
      entry.sum += r.latencyMs ?? 0;
    }
    entry.count += 1;
    byDay.set(day, entry);
  }
  return [...byDay.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .slice(-14)
    .map(([day, { sum, count }]) => ({
      label: day.slice(5),
      value: trendMetric.value === 'latency' && count > 0 ? Math.round(sum / count) : sum,
    }));
});

const page = ref(1);
const pageSize = ref(20);

// ---- hourly token table (#634): per-hour buckets crossed with project x user/team ----
const hourlyDate = ref<string>(todayLocalIso());
const hourlyDays = ref<number>(1);
const hourlyDimension = ref<string>('USER');
const hourly = ref<HourlyUsageReport | null>(null);
const hourlyLoading = ref(true);
const hourlyError = ref('');

const hourlyDayOptions = [
  { value: 1, label: '当天' },
  { value: 3, label: '近 3 天' },
  { value: 7, label: '近 7 天' },
];

const hourlyDimensionOptions: UiSelectOption[] = [
  { value: 'NONE', label: '不分组（仅项目）' },
  { value: 'USER', label: '按用户' },
  { value: 'TEAM', label: '按团队' },
];

function todayLocalIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function formatHour(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:00`;
}

const hourlyRows = computed(() =>
  (hourly.value?.rows ?? []).map((r, i) => ({
    ...r,
    key: `${r.hourStart ?? ''}-${r.projectId ?? ''}-${r.dimensionId ?? ''}-${i}`,
  })),
);

const hourlyColumns = computed(() => {
  const cols: Array<{
    key: string;
    title: string;
    width?: string;
    minWidth?: string;
    align?: 'right';
  }> = [{ key: 'hourStart', title: '小时', width: '130px' }];
  if (hourlyDimension.value !== 'NONE') {
    cols.push({
      key: 'dimensionLabel',
      title: hourlyDimension.value === 'TEAM' ? '团队' : '用户',
      minWidth: '150px',
    });
  }
  cols.push(
    { key: 'projectLabel', title: '项目', minWidth: '150px' },
    { key: 'requests', title: '请求', width: '90px', align: 'right' },
    { key: 'inputTokens', title: '输入', width: '110px', align: 'right' },
    { key: 'outputTokens', title: '输出', width: '110px', align: 'right' },
    { key: 'cacheReadTokens', title: '缓存读', width: '110px', align: 'right' },
    { key: 'cacheCreationTokens', title: '缓存写', width: '110px', align: 'right' },
    { key: 'totalTokens', title: '合计', width: '120px', align: 'right' },
  );
  return cols;
});

async function loadHourly() {
  hourlyLoading.value = true;
  hourlyError.value = '';
  try {
    hourly.value = await api.adminUsageHourly({
      date: hourlyDate.value || undefined,
      days: hourlyDays.value,
      dimension: hourlyDimension.value,
      projectId: projectId.value || undefined,
      tzOffsetMinutes: -new Date().getTimezoneOffset(),
    });
  } catch (error) {
    if (error instanceof ApiError) {
      hourlyError.value = error.message;
    }
  } finally {
    hourlyLoading.value = false;
  }
}

function applyHourlyDays(value: number) {
  hourlyDays.value = value;
  void loadHourly();
}

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
  { value: 'virtual_key', label: '虚拟密钥' },
  { value: 'user', label: '用户' },
  { value: 'team', label: '团队' },
  { value: 'model', label: '模型' },
  { value: 'cache_level', label: '缓存层级' },
  { value: 'day', label: '日' },
  { value: 'month', label: '月' },
];

const columns = [
  { key: 'occurredAt', title: '时间', width: '180px' },
  { key: 'modelId', title: '模型', minWidth: '170px' },
  { key: 'inputTokens', title: '输入', width: '110px', align: 'right' as const },
  { key: 'outputTokens', title: '输出', width: '110px', align: 'right' as const },
  { key: 'cacheLevel', title: '缓存层级', width: '120px' },
  { key: 'upstreamStatusCode', title: '状态码', width: '90px', align: 'right' as const },
  { key: 'usageMissing', title: '用量上报', width: '90px' },
  { key: 'clientIp', title: '来源 IP', width: '140px' },
  { key: 'gatewayRequestId', title: '请求 ID', minWidth: '230px' },
];

// #440: request-sequence guard — rapid filter/window/page changes must not
// let an older summary+records pair land after a newer one.
let loadRequestSeq = 0;

async function load() {
  const seq = ++loadRequestSeq;
  summaryLoading.value = true;
  recordsLoading.value = true;
  summaryError.value = '';
  try {
    const summaryResult = await api.adminUsageSummary({
      groupBy: groupBy.value,
      modelId: modelId.value || undefined,
      projectId: projectId.value || undefined,
      ...rangeParams(),
    });
    if (seq !== loadRequestSeq) {
      return; // a newer request won — this response is stale
    }
    summary.value = summaryResult;
    const recordsResult = await api.adminUsageRecords({
      modelId: modelId.value || undefined,
      projectId: projectId.value || undefined,
      clientIp: clientIp.value.trim() || undefined,
      page: page.value,
      size: pageSize.value,
      ...rangeParams(),
    });
    if (seq !== loadRequestSeq) {
      return;
    }
    records.value = recordsResult;
  } catch (error) {
    if (seq === loadRequestSeq && error instanceof ApiError) {
      summaryError.value = error.message;
      summaryRequestId.value = error.requestId ?? '';
    }
  } finally {
    if (seq === loadRequestSeq) {
      summaryLoading.value = false;
      recordsLoading.value = false;
    }
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

function fmtMoney(value: number | string | undefined): string {
  return Number(value ?? 0).toFixed(4);
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const cacheLabel: Record<string, string> = {
  UPSTREAM: '上游',
  COALESCED: '合并',
  L1_HIT: 'L1 命中',
  L2_HIT: 'L2 命中',
};

onMounted(() => {
  void load();
  void loadHourly();
});
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
        <UiInput
          v-model="clientIp"
          placeholder="来源 IP（可选）"
          width="180px"
          data-testid="usage-client-ip"
        />
        <UiButton
          variant="primary"
          data-testid="usage-query"
          @click="
            page = 1;
            load();
            loadHourly();
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
            <span class="next-admin-usage__stat-label">输入 Token</span>
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
            <span class="next-admin-usage__stat-label">输出 Token</span>
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

    <section class="ui-panel next-usage__trend" data-testid="usage-trend">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">用量趋势</h2>
        <div class="next-usage__trend-tabs" role="tablist" aria-label="趋势指标">
          <button
            v-for="tab in TREND_TABS"
            :key="tab.value"
            type="button"
            role="tab"
            class="next-usage__trend-tab"
            :class="{ 'next-usage__trend-tab--on': trendMetric === tab.value }"
            :aria-selected="trendMetric === tab.value"
            :data-testid="`trend-tab-${tab.value}`"
            @click="trendMetric = tab.value"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
      <div class="ui-panel-body">
        <UiTrendChart
          :points="trendPoints"
          :value-formatter="fmtNum"
          data-testid="usage-trend-chart"
        />
      </div>
    </section>

    <section class="ui-panel next-admin-usage__hourly" data-testid="usage-hourly">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">每小时 Token</h2>
        <div class="next-admin-usage__hourly-controls">
          <UiInput
            v-model="hourlyDate"
            type="date"
            width="160px"
            data-testid="hourly-date"
            @change="loadHourly"
          />
          <div class="next-admin-usage__range" aria-label="天数">
            <button
              v-for="d in hourlyDayOptions"
              :key="d.value"
              type="button"
              class="next-admin-usage__seg"
              :class="{ 'next-admin-usage__seg--on': hourlyDays === d.value }"
              :data-testid="`hourly-days-${d.value}`"
              @click="applyHourlyDays(d.value)"
            >
              {{ d.label }}
            </button>
          </div>
          <UiSelect
            v-model="hourlyDimension"
            :options="hourlyDimensionOptions"
            data-testid="hourly-dimension"
            @change="loadHourly"
          />
        </div>
      </div>
      <div v-if="hourlyError" class="ui-alert ui-alert--error">{{ hourlyError }}</div>
      <UiTable
        :columns="hourlyColumns"
        :data="hourlyRows"
        :loading="hourlyLoading"
        row-key="key"
        empty-title="该时间窗没有用量"
        data-testid="usage-hourly-table"
      >
        <template #hourStart="{ row }">{{
          formatHour((row as HourlyUsageRow).hourStart)
        }}</template>
        <template #dimensionLabel="{ row }">
          <span class="ui-mono">{{ (row as HourlyUsageRow).dimensionLabel ?? '—' }}</span>
        </template>
        <template #projectLabel="{ row }">{{
          (row as HourlyUsageRow).projectLabel ?? '—'
        }}</template>
        <template #requests="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).requests) }}</span>
        </template>
        <template #inputTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).inputTokens) }}</span>
        </template>
        <template #outputTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).outputTokens) }}</span>
        </template>
        <template #cacheReadTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).cacheReadTokens) }}</span>
        </template>
        <template #cacheCreationTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).cacheCreationTokens) }}</span>
        </template>
        <template #totalTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).totalTokens) }}</span>
        </template>
      </UiTable>
    </section>

    <section class="ui-panel">
      <UiTable
        :columns="columns"
        :data="records?.items ?? []"
        :loading="recordsLoading"
        row-key="gatewayRequestId"
        empty-title="没有用量记录"
        data-testid="usage-records-table"
      >
        <template #occurredAt="{ row }">{{ formatTime((row as UsageRecord).occurredAt) }}</template>
        <template #modelId="{ row }">
          <span class="ui-mono">{{ (row as UsageRecord).modelId }}</span>
        </template>
        <template #inputTokens="{ row }">
          <span class="ui-num">{{ (row as UsageRecord).inputTokens ?? 0 }}</span>
        </template>
        <template #outputTokens="{ row }">
          <span class="ui-num">{{ (row as UsageRecord).outputTokens ?? 0 }}</span>
        </template>
        <template #cacheLevel="{ row }">
          <UiStatusBadge
            :label="
              cacheLabel[(row as UsageRecord).cacheLevel ?? ''] ?? (row as UsageRecord).cacheLevel
            "
          />
        </template>
        <template #upstreamStatusCode="{ row }">
          <span class="ui-num">{{ (row as UsageRecord).upstreamStatusCode ?? '—' }}</span>
        </template>
        <template #usageMissing="{ row }">
          <UiStatusBadge
            :tone="(row as UsageRecord).usageMissing ? 'warning' : 'success'"
            :label="(row as UsageRecord).usageMissing ? '缺失' : '正常'"
          />
        </template>
        <template #clientIp="{ row }">
          <span class="ui-mono">{{ (row as UsageRecord).clientIp || '—' }}</span>
        </template>
        <template #gatewayRequestId="{ row }">
          <span class="ui-mono next-admin-usage__reqid">{{
            (row as UsageRecord).gatewayRequestId
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
  color: var(--ui-primary-text);
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

.next-usage__trend {
  margin-bottom: var(--ui-space-5);
}

.next-admin-usage__hourly {
  margin-bottom: var(--ui-space-5);
}

.next-admin-usage__hourly-controls {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

.next-usage__trend-tabs {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--ui-radius-control);
  background: var(--ui-muted);
}

.next-usage__trend-tab {
  border: 0;
  padding: 0 12px;
  height: 24px;
  border-radius: 4px;
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-family: inherit;
  font-size: var(--ui-font-size-xs);
  cursor: pointer;
}

.next-usage__trend-tab--on {
  background: var(--ui-card);
  color: var(--ui-primary-text);
  box-shadow: var(--ui-shadow-card);
}
</style>
