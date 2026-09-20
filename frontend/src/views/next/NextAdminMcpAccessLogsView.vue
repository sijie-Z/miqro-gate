<script setup lang="ts">
/**
 * NextAdminMcpAccessLogsView — MCP 代理调用元数据日志（F15, V29）。
 * 只读审计尾：服务/消费者/时间范围/条数过滤 + 刷新；列=时间/消费者/服务/信封方法/
 * 工具/终态/HTTP 状态/网关请求 ID。正文永不出现（网关侧已保证）。
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { countWhenLoaded } from '@/utils/load-state';
import { ApiError } from '@/api/http';
import { UiButton, UiDrawer, UiInput, UiSelect, UiStatusBadge, UiTable } from '@/ui';
import type { McpAccessLogEntry } from '@/types/generated-api';

const entries = ref<McpAccessLogEntry[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');
const serviceFilter = ref('');
const consumerFilter = ref('');
const fromFilter = ref('');
const toFilter = ref('');
/** 条数：默认 200，与后端 AdminMcpAccessLogService.DEFAULT_LIMIT 对齐（上限 1000）。 */
const limitFilter = ref('200');

const LIMIT_OPTIONS = [
  { label: '200 条', value: '200' },
  { label: '500 条', value: '500' },
  { label: '1000 条', value: '1000' },
];

const columns = [
  { key: 'occurredAt', title: '时间', width: '150px' },
  { key: 'consumerName', title: '消费者', minWidth: '150px' },
  { key: 'serviceName', title: '服务', minWidth: '140px' },
  { key: 'rpcMethod', title: '方法', width: '130px' },
  { key: 'toolName', title: '工具', minWidth: '120px' },
  { key: 'status', title: '结果', width: '150px' },
  { key: 'httpStatus', title: 'HTTP 状态', width: '90px', align: 'center' as const },
  { key: 'sessionId', title: '会话', width: '150px' },
  { key: 'ttfbMs', title: '首字节', width: '90px', align: 'right' as const },
  { key: 'gatewayRequestId', title: '网关请求 ID', width: '300px' },
];

const STATUS_META: Record<
  string,
  { label: string; tone: 'success' | 'danger' | 'warning' | 'neutral' }
> = {
  FORWARDED: { label: '已转发', tone: 'success' },
  SERVICE_DENIED: { label: '服务被拒', tone: 'danger' },
  TOOL_DENIED: { label: '工具被拒', tone: 'danger' },
  TOOL_UNAVAILABLE: { label: '工具不可用', tone: 'danger' },
  INVALID_ENVELOPE: { label: '信封非法', tone: 'warning' },
  UPSTREAM_FAILURE: { label: '上游失败', tone: 'danger' },
  CIRCUIT_OPEN: { label: '熔断打开', tone: 'danger' },
};

/** Window KPI band (#554): aggregates over the currently loaded rows (窗口内口径). */
const summary = computed(() => {
  const total = entries.value.length;
  let forwarded = 0;
  let denied = 0;
  let failed = 0;
  let invalid = 0;
  for (const row of entries.value) {
    switch (row.status) {
      case 'FORWARDED':
        forwarded += 1;
        break;
      case 'SERVICE_DENIED':
      case 'TOOL_DENIED':
      case 'TOOL_UNAVAILABLE':
        denied += 1;
        break;
      case 'UPSTREAM_FAILURE':
      case 'CIRCUIT_OPEN':
        failed += 1;
        break;
      case 'INVALID_ENVELOPE':
        invalid += 1;
        break;
      default:
        break;
    }
  }
  const denom = forwarded + failed;
  return {
    total,
    forwarded,
    denied,
    failed,
    invalid,
    failureRate: denom > 0 ? ((failed / denom) * 100).toFixed(1) + '%' : '—',
  };
});

// Per-call detail drawer (#554): metadata timeline, never any payload.
const detail = ref<McpAccessLogEntry | null>(null);
const detailVisible = ref(false);

function openDetail(row: unknown) {
  detail.value = row as McpAccessLogEntry;
  detailVisible.value = true;
}

const detailStatus = computed(() => {
  const status = detail.value?.status ?? '';
  return STATUS_META[status]?.label ?? status;
});

const detailFirstByteAt = computed(() => {
  const base = detail.value?.occurredAt;
  const ttfb = detail.value?.ttfbMs;
  if (!base || ttfb == null) return null;
  return new Date(new Date(base).getTime() + ttfb);
});

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    entries.value = await api.listMcpAccessLogs({
      service: serviceFilter.value.trim() || undefined,
      consumer: consumerFilter.value.trim() || undefined,
      from: toIsoInstant(fromFilter.value),
      to: toIsoInstant(toFilter.value),
      limit: Number(limitFilter.value),
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

function resetFilters() {
  serviceFilter.value = '';
  consumerFilter.value = '';
  fromFilter.value = '';
  toFilter.value = '';
  limitFilter.value = '200';
  void load();
}

/** Quick windows: fill the datetime-local inputs with the trailing N days and query. */
function applyRange(days: number) {
  const now = new Date();
  fromFilter.value = toLocalInput(new Date(now.getTime() - days * 24 * 3600 * 1000));
  toFilter.value = toLocalInput(now);
  void load();
}

/** datetime-local value (browser-local) → UTC ISO instant, matching the API contract. */
function toIsoInstant(local: string): string | undefined {
  if (!local) return undefined;
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-mcp-logs">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">MCP 访问日志</h1>
        <p class="ui-page-desc">
          MCP 代理调用审计尾（默认近 24 小时、最新 200
          条；可过滤时间范围与条数）——仅元数据，不含任何请求正文或响应内容。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="mcp-logs-refresh" @click="load">刷新</UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="mcp-logs-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section class="ui-panel next-mcp-logs__summary" data-testid="mcp-logs-summary">
      <div class="next-mcp-logs__stat">
        <span class="next-mcp-logs__stat-label">总请求</span>
        <span class="next-mcp-logs__stat-value ui-num" data-testid="mcp-logs-total">{{
          summary.total
        }}</span>
      </div>
      <div class="next-mcp-logs__stat">
        <span class="next-mcp-logs__stat-label">已转发</span>
        <span class="next-mcp-logs__stat-value ui-num">{{ summary.forwarded }}</span>
      </div>
      <div class="next-mcp-logs__stat">
        <span class="next-mcp-logs__stat-label">被拒</span>
        <span class="next-mcp-logs__stat-value ui-num">{{ summary.denied }}</span>
      </div>
      <div class="next-mcp-logs__stat">
        <span class="next-mcp-logs__stat-label">上游失败 / 熔断</span>
        <span class="next-mcp-logs__stat-value ui-num">{{ summary.failed }}</span>
      </div>
      <div class="next-mcp-logs__stat">
        <span class="next-mcp-logs__stat-label">信封非法</span>
        <span class="next-mcp-logs__stat-value ui-num">{{ summary.invalid }}</span>
      </div>
      <div class="next-mcp-logs__stat">
        <span class="next-mcp-logs__stat-label">失败率</span>
        <span class="next-mcp-logs__stat-value ui-num" data-testid="mcp-logs-failure-rate">{{
          summary.failureRate
        }}</span>
      </div>
      <span class="next-mcp-logs__summary-hint">窗口内口径 · 失败率 = 失败/(转发+失败)</span>
    </section>

    <section class="ui-panel">
      <div class="ui-filter-bar next-mcp-logs__filters">
        <UiInput
          v-model="serviceFilter"
          label="服务名"
          placeholder="如 weather-mcp"
          data-testid="mcp-logs-service-filter"
          class="next-mcp-logs__filter"
        />
        <UiInput
          v-model="consumerFilter"
          label="消费者名"
          placeholder="如 drill-allowed"
          data-testid="mcp-logs-consumer-filter"
          class="next-mcp-logs__filter"
        />
        <UiInput
          v-model="fromFilter"
          type="datetime-local"
          label="起始时间"
          data-testid="mcp-logs-from"
          class="next-mcp-logs__filter"
        />
        <UiInput
          v-model="toFilter"
          type="datetime-local"
          label="结束时间"
          data-testid="mcp-logs-to"
          class="next-mcp-logs__filter"
        />
        <div class="next-mcp-logs__filter-actions">
          <UiButton variant="ghost" size="sm" data-testid="mcp-logs-range-7" @click="applyRange(7)">
            近 7 天
          </UiButton>
          <UiButton
            variant="ghost"
            size="sm"
            data-testid="mcp-logs-range-30"
            @click="applyRange(30)"
          >
            近 30 天
          </UiButton>
        </div>
        <UiSelect
          v-model="limitFilter"
          :options="LIMIT_OPTIONS"
          width="130px"
          data-testid="mcp-logs-limit"
        />
        <div class="next-mcp-logs__filter-actions">
          <UiButton variant="primary" size="sm" data-testid="mcp-logs-query" @click="load">
            查询
          </UiButton>
          <UiButton variant="ghost" size="sm" data-testid="mcp-logs-reset" @click="resetFilters">
            重置
          </UiButton>
        </div>
      </div>
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ countWhenLoaded(loadError, entries.length) }} 条</span>
      </div>
      <UiTable
        :columns="columns"
        :data="entries"
        :loading="loading"
        row-key="gatewayRequestId"
        empty-title="暂无 MCP 调用日志"
        data-testid="mcp-logs-table"
        :error="loadError"
        @row-click="openDetail"
        @retry="load"
      >
        <template #occurredAt="{ row }">{{
          formatTime((row as McpAccessLogEntry).occurredAt)
        }}</template>
        <template #consumerName="{ row }">
          <span class="ui-mono">{{ (row as McpAccessLogEntry).consumerName }}</span>
        </template>
        <template #serviceName="{ row }">
          {{ (row as McpAccessLogEntry).serviceName }}
        </template>
        <template #rpcMethod="{ row }">
          <span class="ui-mono">{{ (row as McpAccessLogEntry).rpcMethod ?? '—' }}</span>
        </template>
        <template #toolName="{ row }">
          <span class="ui-mono">{{ (row as McpAccessLogEntry).toolName ?? '—' }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="STATUS_META[(row as McpAccessLogEntry).status ?? '']?.tone ?? 'neutral'"
            :label="
              STATUS_META[(row as McpAccessLogEntry).status ?? '']?.label ??
              (row as McpAccessLogEntry).status
            "
          />
        </template>
        <template #httpStatus="{ row }">
          <span class="ui-mono">{{ (row as McpAccessLogEntry).httpStatus ?? '—' }}</span>
        </template>
        <template #sessionId="{ row }">
          <span class="ui-mono ui-muted" :title="(row as McpAccessLogEntry).sessionId">{{
            (row as McpAccessLogEntry).sessionId?.slice(0, 12) ?? '—'
          }}</span>
        </template>
        <template #ttfbMs="{ row }">
          <span class="ui-num">{{
            (row as McpAccessLogEntry).ttfbMs != null
              ? `${(row as McpAccessLogEntry).ttfbMs} ms`
              : '—'
          }}</span>
        </template>
        <template #gatewayRequestId="{ row }">
          <span class="ui-mono ui-muted" :title="(row as McpAccessLogEntry).gatewayRequestId">{{
            (row as McpAccessLogEntry).gatewayRequestId?.slice(0, 8) ?? '—'
          }}</span>
        </template>
      </UiTable>
    </section>

    <!-- #554: per-call metadata timeline -->
    <UiDrawer
      :open="detailVisible"
      :title="detail ? `调用详情 · ${detail.serviceName}` : '调用详情'"
      width="560px"
      data-testid="mcp-logs-detail-drawer"
      @update:open="detailVisible = false"
    >
      <div v-if="detail" class="next-mcp-logs__detail">
        <ol class="next-mcp-logs__timeline" data-testid="mcp-logs-timeline">
          <li class="next-mcp-logs__timeline-item">
            <span class="next-mcp-logs__timeline-dot" />
            <span class="next-mcp-logs__timeline-label">受理</span>
            <span class="ui-mono">{{ formatTime(detail.occurredAt) }}</span>
          </li>
          <li v-if="detailFirstByteAt" class="next-mcp-logs__timeline-item">
            <span class="next-mcp-logs__timeline-dot" />
            <span class="next-mcp-logs__timeline-label">上游首包</span>
            <span class="ui-mono">{{ formatTime(detailFirstByteAt.toISOString()) }}</span>
            <span class="next-mcp-logs__timeline-delta ui-num">+{{ detail.ttfbMs }} ms</span>
          </li>
          <li class="next-mcp-logs__timeline-item">
            <span class="next-mcp-logs__timeline-dot next-mcp-logs__timeline-dot--end" />
            <span class="next-mcp-logs__timeline-label">结论</span>
            <UiStatusBadge
              :tone="STATUS_META[detail.status ?? '']?.tone ?? 'neutral'"
              :label="detailStatus"
            />
            <span v-if="detail.httpStatus != null" class="ui-mono"
              >HTTP {{ detail.httpStatus }}</span
            >
          </li>
        </ol>
        <dl class="next-mcp-logs__meta">
          <div class="next-mcp-logs__meta-row">
            <dt>服务</dt>
            <dd>{{ detail.serviceName }}</dd>
          </div>
          <div class="next-mcp-logs__meta-row">
            <dt>消费者</dt>
            <dd class="ui-mono">{{ detail.consumerName }}</dd>
          </div>
          <div class="next-mcp-logs__meta-row">
            <dt>方法</dt>
            <dd class="ui-mono">{{ detail.rpcMethod ?? '—' }}</dd>
          </div>
          <div class="next-mcp-logs__meta-row">
            <dt>工具</dt>
            <dd class="ui-mono">{{ detail.toolName ?? '—' }}</dd>
          </div>
          <div class="next-mcp-logs__meta-row">
            <dt>会话</dt>
            <dd class="ui-mono">{{ detail.sessionId ?? '—' }}</dd>
          </div>
          <div class="next-mcp-logs__meta-row">
            <dt>网关请求 ID</dt>
            <dd class="ui-mono">{{ detail.gatewayRequestId }}</dd>
          </div>
        </dl>
        <p class="next-mcp-logs__detail-hint">仅元数据——工具参数与响应正文永不落库。</p>
      </div>
    </UiDrawer>
  </div>
</template>

<style scoped>
.next-mcp-logs__filters {
  display: flex;
  gap: var(--ui-space-3);
  align-items: flex-end;
  padding: var(--ui-space-3) var(--ui-space-4);
  border-bottom: 1px solid var(--ui-border-muted);
  flex-wrap: wrap;
}

.next-mcp-logs__filter {
  width: 220px;
}

.next-mcp-logs__filter-actions {
  display: flex;
  gap: var(--ui-space-2);
  padding-bottom: 4px;
}

.next-mcp-logs__summary {
  display: flex;
  align-items: baseline;
  gap: var(--ui-space-5);
  flex-wrap: wrap;
  padding: var(--ui-space-4) var(--ui-space-5);
  margin-bottom: var(--ui-space-4);
}

.next-mcp-logs__stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.next-mcp-logs__stat-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  white-space: nowrap;
}

.next-mcp-logs__stat-value {
  font-size: 20px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-mcp-logs__summary-hint {
  margin-left: auto;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-mcp-logs__detail {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-mcp-logs__timeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.next-mcp-logs__timeline-item {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
}

.next-mcp-logs__timeline-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ui-primary);
  flex-shrink: 0;
}

.next-mcp-logs__timeline-dot--end {
  background: var(--ui-foreground-faint);
}

.next-mcp-logs__timeline-label {
  width: 72px;
  color: var(--ui-foreground-secondary);
}

.next-mcp-logs__timeline-delta {
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-xs);
}

.next-mcp-logs__meta {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp-logs__meta-row {
  display: flex;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
}

.next-mcp-logs__meta-row dt {
  width: 96px;
  color: var(--ui-foreground-secondary);
}

.next-mcp-logs__meta-row dd {
  margin: 0;
  word-break: break-all;
}

.next-mcp-logs__detail-hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}
</style>
