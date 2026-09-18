<script setup lang="ts">
/**
 * NextAdminExportsView — /app/exports v2 admin page (U2 ops batch).
 * Behaviour parity with legacy exports page: create async CSV/JSONL export
 * for a window, poll to completion, download product, list recent tasks.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDonut, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type { ExportTask } from '@/types/generated-api';

const tasks = ref<ExportTask[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const format = ref<'CSV' | 'JSONL'>('CSV');
const from = ref('2026-08-01T00:00:00Z');
const to = ref('2026-08-31T00:00:00Z');
const creating = ref(false);
const formError = ref('');

const columns = [
  { key: 'format', title: '格式', width: '100px' },
  { key: 'period', title: '窗口', minWidth: '260px' },
  { key: 'status', title: '状态', width: '120px' },
  { key: 'reconcileLevel', title: '可对账等级', width: '130px' },
  { key: 'adjustmentLevel', title: '含调整', width: '110px' },
  { key: 'rowCount', title: '行数', width: '110px', align: 'right' as const },
  { key: 'createdAt', title: '创建时间', width: '180px' },
  { key: 'actions', title: '操作', width: '100px', align: 'center' as const },
];

const statusTone: Record<
  NonNullable<ExportTask['status']>,
  'success' | 'warning' | 'danger' | 'neutral' | 'info'
> = {
  PENDING: 'info',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  EXPIRED: 'neutral',
};

const statusText: Record<NonNullable<ExportTask['status']>, string> = {
  PENDING: '排队中',
  RUNNING: '生成中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  EXPIRED: '已过期',
};

const TONE_COLORS: Record<string, string> = {
  success: '#389e0d',
  warning: '#d48806',
  danger: '#cf1322',
  info: '#0960bd',
  neutral: '#8c8c8c',
};

/** Task status distribution over the loaded list. */
const statusSegments = computed(() => {
  const byLabel = new Map<string, { value: number; color: string }>();
  for (const t of tasks.value) {
    const label = statusLabelFor(t.status) || '—';
    const color = TONE_COLORS[statusToneFor(t.status)] ?? '#8c8c8c';
    const cur = byLabel.get(label);
    if (cur) cur.value += 1;
    else byLabel.set(label, { value: 1, color });
  }
  return [...byLabel.entries()].map(([label, v]) => ({ label, value: v.value, color: v.color }));
});

function statusToneFor(
  status: ExportTask['status'],
): 'success' | 'warning' | 'danger' | 'neutral' | 'info' {
  // export rows always carry a status; fold unknown/absent into the neutral default
  return statusTone[status as NonNullable<ExportTask['status']>] ?? 'neutral';
}

function reconcileBadge(
  level?: string | null,
): { tone: 'success' | 'warning' | 'neutral'; label: string } | null {
  switch (level) {
    case 'PROVIDER_ID_BACKED':
      return { tone: 'success', label: '可对账' };
    case 'PARTIAL':
      return { tone: 'warning', label: '部分' };
    case 'LOCAL_ONLY':
      return { tone: 'neutral', label: '本地口径' };
    default:
      return null;
  }
}

/**
 * #716: the other axis — whether the file's numbers include corrections.
 *
 * Only PRESENT gets a chip. An untouched export says NONE, and marking that with
 * a chip too would put a badge on nearly every row and drown the one that
 * matters; the column reads "—" instead, which is how this table marks "nothing
 * to say" everywhere else.
 */
function adjustmentBadge(level?: string | null): { tone: 'info'; label: string } | null {
  return level === 'PRESENT' ? { tone: 'info', label: '含调整' } : null;
}

function statusLabelFor(status: ExportTask['status']): string {
  // export rows always carry a status; unknown values render blank like before
  return statusText[status as NonNullable<ExportTask['status']>] ?? '';
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    tasks.value = await api.exportRecent();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function createExport() {
  if (!from.value || !to.value || new Date(from.value) >= new Date(to.value)) {
    formError.value = '时间窗口无效：开始时间必须早于结束时间。';
    return;
  }
  formError.value = '';
  try {
    const created = await api.createExport(format.value, from.value, to.value);
    creating.value = false;
    toast.success('导出任务已创建，完成后可下载');
    await load();
    // server contract: createExport responses always carry the task id
    poll(created.id!);
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败';
  }
}

// #440: poll intervals must die with the component — previously they kept
// hitting the API for a dead component until the task finished (or forever,
// if the task was stranded).
const pollTimers = new Set<ReturnType<typeof setInterval>>();
onUnmounted(() => {
  pollTimers.forEach((timer) => clearInterval(timer));
  pollTimers.clear();
});

function poll(id: string) {
  const timer = setInterval(async () => {
    try {
      const task = await api.exportStatus(id);
      if (!pollTimers.has(timer)) {
        return; // cleared on unmount — the response is irrelevant
      }
      const index = tasks.value.findIndex((t) => t.id === id);
      if (index >= 0) {
        tasks.value[index] = task;
      }
      if (task.status === 'SUCCEEDED' || task.status === 'FAILED' || task.status === 'EXPIRED') {
        clearInterval(timer);
        pollTimers.delete(timer);
        if (task.status === 'FAILED') {
          toast.error(task.errorMessage ?? '导出失败');
        }
      }
    } catch {
      clearInterval(timer);
      pollTimers.delete(timer);
    }
  }, 2000);
  pollTimers.add(timer);
}

function download(task: ExportTask) {
  // Direct GET download (session cookie + same-origin); CSRF not required.
  window.location.href = `/api/v1/admin/exports/${task.id}/download`;
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-exports">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">导出任务</h1>
        <p class="ui-page-desc">
          按时间窗口导出原始用量（仅计数与元数据列，无请求正文）。产物 24 小时后过期。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="export-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '新建导出' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section v-if="creating" class="ui-panel next-exports__create" data-testid="export-create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">新建导出</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-exports__grid">
          <div class="next-exports__format">
            <span class="next-exports__format-label">格式</span>
            <div class="next-exports__segmented">
              <button
                type="button"
                class="next-exports__seg"
                :class="{ 'next-exports__seg--on': format === 'CSV' }"
                @click="format = 'CSV'"
              >
                CSV
              </button>
              <button
                type="button"
                class="next-exports__seg"
                :class="{ 'next-exports__seg--on': format === 'JSONL' }"
                @click="format = 'JSONL'"
              >
                JSONL
              </button>
            </div>
          </div>
          <UiInput v-model="from" label="起始时间" required data-testid="export-from" />
          <UiInput v-model="to" label="结束时间" required data-testid="export-to" />
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-exports__actions">
            <UiButton variant="primary" data-testid="export-create-submit" @click="createExport"
              >创建任务</UiButton
            >
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section
      v-if="statusSegments.length"
      class="ui-panel next-exports__summary"
      data-testid="exports-status-dist"
    >
      <div class="ui-panel-head">
        <div>
          <h2 class="ui-panel-title">任务状态分布</h2>
          <span class="ui-panel-sub">基于当前 {{ tasks.length }} 个任务</span>
        </div>
      </div>
      <div class="ui-panel-body next-exports__summary-body">
        <UiDonut
          :segments="statusSegments"
          :center-text="`${tasks.length}`"
          data-testid="exports-status-donut"
        />
        <div class="ui-legend">
          <div v-for="seg in statusSegments" :key="seg.label" class="ui-legend-row">
            <span class="ui-legend-dot" :style="{ background: seg.color }" />
            <span class="ui-legend-label">{{ seg.label }}</span>
            <span class="ui-legend-pct ui-num"
              >{{ ((seg.value / Math.max(1, tasks.length)) * 100).toFixed(0) }}%</span
            >
            <span class="ui-legend-value ui-num">{{ seg.value }}</span>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ tasks.length }} 个任务</span>
      </div>
      <UiTable
        :columns="columns"
        :data="tasks"
        :loading="loading"
        row-key="id"
        empty-title="还没有导出任务"
        data-testid="exports-table"
      >
        <template #period="{ row }">
          <span class="ui-mono"
            >{{ (row as ExportTask).periodFrom?.slice(0, 10) ?? '' }} →
            {{ (row as ExportTask).periodTo?.slice(0, 10) ?? '' }}</span
          >
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            variant="pill"
            :tone="statusToneFor((row as ExportTask).status)"
            :label="statusLabelFor((row as ExportTask).status)"
          />
        </template>
        <template #reconcileLevel="{ row }">
          <UiStatusBadge
            v-if="reconcileBadge((row as ExportTask).reconcileLevel)"
            variant="pill"
            :tone="reconcileBadge((row as ExportTask).reconcileLevel)!.tone"
            :label="reconcileBadge((row as ExportTask).reconcileLevel)!.label"
            data-testid="export-reconcile-level"
          />
          <span v-else>—</span>
        </template>
        <template #adjustmentLevel="{ row }">
          <UiStatusBadge
            v-if="adjustmentBadge((row as ExportTask).adjustmentLevel)"
            variant="pill"
            :tone="adjustmentBadge((row as ExportTask).adjustmentLevel)!.tone"
            :label="adjustmentBadge((row as ExportTask).adjustmentLevel)!.label"
            data-testid="export-adjustment-level"
          />
          <span v-else>—</span>
        </template>
        <template #rowCount="{ row }">
          <span class="ui-num">{{ (row as ExportTask).rowCount?.toLocaleString() ?? '—' }}</span>
        </template>
        <template #createdAt="{ row }">{{ formatTime((row as ExportTask).createdAt) }}</template>
        <template #actions="{ row }">
          <UiButton
            v-if="(row as ExportTask).status === 'SUCCEEDED'"
            variant="link"
            size="sm"
            data-testid="export-download"
            @click="download(row as ExportTask)"
          >
            下载
          </UiButton>
          <span v-else>—</span>
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

.next-exports__summary {
  margin-bottom: var(--ui-space-5);
}

.next-exports__summary-body {
  display: flex;
  align-items: center;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
}

.next-exports__create {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
}

.next-exports__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4) var(--ui-space-6);
  max-width: 680px;
}

.next-exports__format {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.next-exports__format-label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  line-height: var(--ui-line-height-sm);
}

.next-exports__segmented {
  display: inline-flex;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
  width: fit-content;
}

.next-exports__seg {
  height: 30px;
  padding: 0 var(--ui-space-3);
  border: 0;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
}

.next-exports__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-semibold);
}

.next-exports__actions {
  display: flex;
  gap: var(--ui-space-2);
  grid-column: 1 / -1;
}
</style>
