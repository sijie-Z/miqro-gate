<script setup lang="ts">
/**
 * NextAdminExportsView — /app/exports v2 admin page (U2 ops batch).
 * Behaviour parity with legacy exports page: create async CSV/JSONL export
 * for a window, poll to completion, download product, list recent tasks.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type {} from '@/types/api';
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
  { key: 'status', title: '状态', width: '130px' },
  { key: 'rowCount', title: '行数', width: '110px', align: 'right' as const },
  { key: 'createdAt', title: '创建时间', width: '180px' },
  { key: 'actions', title: '操作', width: '100px', align: 'center' as const },
];

const statusTone: Record<
  ExportTask['status'],
  'success' | 'warning' | 'danger' | 'neutral' | 'info'
> = {
  PENDING: 'info',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  EXPIRED: 'neutral',
};

const statusText: Record<ExportTask['status'], string> = {
  PENDING: '排队中',
  RUNNING: '生成中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  EXPIRED: '已过期',
};

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
    formError.value = '时间窗口无效：from 必须早于 to。';
    return;
  }
  formError.value = '';
  try {
    const created = await api.createExport(format.value, from.value, to.value);
    creating.value = false;
    toast.success('导出任务已创建，完成后可下载');
    await load();
    poll(created.id);
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败';
  }
}

function poll(id: string) {
  const timer = setInterval(async () => {
    try {
      const task = await api.exportStatus(id);
      const index = tasks.value.findIndex((t) => t.id === id);
      if (index >= 0) {
        tasks.value[index] = task;
      }
      if (task.status === 'SUCCEEDED' || task.status === 'FAILED' || task.status === 'EXPIRED') {
        clearInterval(timer);
        if (task.status === 'FAILED') {
          toast.error(task.errorMessage ?? '导出失败');
        }
      }
    } catch {
      clearInterval(timer);
    }
  }, 2000);
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
            >{{ (row as ExportTask).periodFrom.slice(0, 10) }} →
            {{ (row as ExportTask).periodTo.slice(0, 10) }}</span
          >
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            variant="pill"
            :tone="statusTone[(row as ExportTask).status]"
            :label="statusText[(row as ExportTask).status]"
          />
        </template>
        <template #rowCount="{ row }">
          <span class="ui-num">{{ (row as ExportTask).rowCount?.toLocaleString() ?? '—' }}</span>
        </template>
        <template #createdAt="{ row }">{{ formatTime((row as ExportTask).createdAt) }}</template>
        <template #actions="{ row }">
          <UiButton
            v-if="(row as ExportTask).status === 'SUCCEEDED'"
            variant="ghost"
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
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-exports__actions {
  display: flex;
  gap: var(--ui-space-2);
  grid-column: 1 / -1;
}
</style>
