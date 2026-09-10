<script setup lang="ts">
/**
 * NextAdminReconciliationsView — /app/reconciliations admin page (F19, I2).
 * Canonical JSONL bill upload → async four-state report; the list is
 * newest-first, and a report's summary plus its four-state detail rows
 * (verdict filter + cursor paging) are read-only.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type { ReconciliationReport, ReconciliationRow, ReconciliationVerdict } from '@/api';

const MAX_UPLOAD_BYTES = 16 * 1024 * 1024;
const ROWS_PAGE = 100;

const reports = ref<ReconciliationReport[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

// ---- upload form
const creating = ref(false);
const providerCode = ref('');
const currency = ref('USD');
const windowFrom = ref('');
const windowTo = ref('');
const file = ref<File | null>(null);
const fileHint = ref('');
const formError = ref('');
const submitting = ref(false);

// ---- report detail
const selected = ref<ReconciliationReport | null>(null);
const rows = ref<ReconciliationRow[]>([]);
const rowsState = ref<'' | ReconciliationVerdict>('');
const nextCursor = ref<string | number>('');
const rowsLoading = ref(false);
const rowsError = ref('');

const listColumns = [
  { key: 'createdAt', title: '上传时间', width: '150px' },
  { key: 'providerCode', title: '供应商', width: '160px' },
  { key: 'currency', title: '币种', width: '70px' },
  { key: 'window', title: '窗口', minWidth: '230px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'totalRows', title: '行数', width: '90px', align: 'right' as const },
  { key: 'amountDiff', title: '金额差', width: '120px', align: 'right' as const },
  { key: 'actions', title: '操作', width: '110px', align: 'center' as const },
];

const rowColumns = [
  { key: 'rowNo', title: '行号', width: '70px', align: 'right' as const },
  { key: 'verdict', title: '结论', width: '140px' },
  { key: 'matchedBy', title: '匹配级别', width: '120px' },
  { key: 'refs', title: '账单行 / 本地记录', minWidth: '260px' },
  { key: 'detail', title: '明细', minWidth: '220px' },
];

const statusTone: Record<
  api.ReconciliationStatus,
  'success' | 'warning' | 'danger' | 'neutral' | 'info'
> = {
  PENDING: 'info',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
};

const statusText: Record<api.ReconciliationStatus, string> = {
  PENDING: '排队中',
  RUNNING: '匹配中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
};

const verdictTone: Record<
  ReconciliationVerdict,
  'success' | 'warning' | 'danger' | 'neutral' | 'info'
> = {
  MATCHED: 'success',
  PARTIAL: 'warning',
  UNMATCHED_PROVIDER: 'danger',
  UNMATCHED_LOCAL: 'danger',
};

const verdictText: Record<ReconciliationVerdict, string> = {
  MATCHED: '一致',
  PARTIAL: '部分',
  UNMATCHED_PROVIDER: '供应商未匹配',
  UNMATCHED_LOCAL: '本地未匹配',
};

const filters: Array<{ value: '' | ReconciliationVerdict; label: string }> = [
  { value: '', label: '全部' },
  { value: 'MATCHED', label: '一致' },
  { value: 'PARTIAL', label: '部分' },
  { value: 'UNMATCHED_PROVIDER', label: '供应商未匹配' },
  { value: 'UNMATCHED_LOCAL', label: '本地未匹配' },
];

function statusToneFor(status: api.ReconciliationStatus) {
  return statusTone[status] ?? 'neutral';
}

/** UiTable slots hand rows over as Record<string, unknown>; this page knows the shape. */
const asReport = (row: unknown) => row as ReconciliationReport;
const asDetailRow = (row: unknown) => row as ReconciliationRow;

function statusLabelFor(status: api.ReconciliationStatus) {
  return statusText[status] ?? status;
}

function verdictLabel(verdict: ReconciliationVerdict) {
  return verdictText[verdict] ?? verdict;
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const page = await api.listReconciliations();
    reports.value = page.reports;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement;
  const picked = input.files?.[0] ?? null;
  file.value = picked;
  fileHint.value = '';
  formError.value = '';
  if (!picked) {
    return;
  }
  if (picked.size > MAX_UPLOAD_BYTES) {
    formError.value = '文件超过 16MB 上限。';
    file.value = null;
    return;
  }
  // gzip magic sniff for the inline hint only — the server detects the
  // transport by the same magic bytes on its side.
  const reader = new FileReader();
  reader.onload = () => {
    const bytes = new Uint8Array(reader.result as ArrayBuffer);
    const gzip = bytes.length === 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
    fileHint.value = gzip ? '已识别 gzip 压缩账单' : 'UTF-8 JSONL 账单';
  };
  reader.readAsArrayBuffer(picked.slice(0, 2));
}

async function submitUpload() {
  formError.value = '';
  if (!providerCode.value.trim()) {
    formError.value = 'providerCode 必填（供应商目录中的 product_code）。';
    return;
  }
  if (!currency.value.trim()) {
    formError.value = 'currency 必填（ISO-4217）。';
    return;
  }
  if (
    !windowFrom.value ||
    !windowTo.value ||
    new Date(windowFrom.value) >= new Date(windowTo.value)
  ) {
    formError.value = '时间窗口无效：from 必须早于 to。';
    return;
  }
  if (!file.value) {
    formError.value = '请选择 canonical JSONL 账单文件。';
    return;
  }
  submitting.value = true;
  try {
    const created = await api.createReconciliation(
      {
        providerCode: providerCode.value.trim(),
        currency: currency.value.trim().toUpperCase(),
        windowFrom: windowFrom.value,
        windowTo: windowTo.value,
      },
      file.value,
    );
    creating.value = false;
    file.value = null;
    toast.success('账单已上传，匹配进行中');
    await load();
    await selectReport(created);
    if (created.status === 'PENDING' || created.status === 'RUNNING') {
      poll(created.id);
    }
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '上传失败';
  } finally {
    submitting.value = false;
  }
}

async function selectReport(report: ReconciliationReport) {
  selected.value = report;
  rowsState.value = '';
  nextCursor.value = '';
  rowsError.value = '';
  rows.value = [];
  await refreshDetail();
}

async function refreshDetail() {
  if (!selected.value) {
    return;
  }
  try {
    selected.value = await api.reconciliationReport(selected.value.id);
  } catch (error) {
    if (error instanceof ApiError) {
      rowsError.value = error.message;
    }
  }
  await loadRows(true);
}

async function loadRows(reset: boolean) {
  if (!selected.value) {
    return;
  }
  rowsLoading.value = true;
  rowsError.value = '';
  try {
    const page = await api.reconciliationRows(selected.value.id, {
      state: rowsState.value || undefined,
      cursor: reset ? undefined : nextCursor.value || undefined,
      limit: ROWS_PAGE,
    });
    rows.value = reset ? page.rows : [...rows.value, ...page.rows];
    nextCursor.value = page.nextCursor;
  } catch (error) {
    if (error instanceof ApiError) {
      rowsError.value = error.message;
    }
  } finally {
    rowsLoading.value = false;
  }
}

function setFilter(value: '' | ReconciliationVerdict) {
  rowsState.value = value;
  void loadRows(true);
}

function closeDetail() {
  selected.value = null;
  rows.value = [];
}

function poll(id: string) {
  const timer = setInterval(async () => {
    try {
      const report = await api.reconciliationReport(id);
      const index = reports.value.findIndex((r) => r.id === id);
      if (index >= 0) {
        reports.value[index] = report;
      }
      if (selected.value?.id === id) {
        selected.value = report;
      }
      if (report.status === 'SUCCEEDED' || report.status === 'FAILED') {
        clearInterval(timer);
        if (selected.value?.id === id) {
          await loadRows(true);
        }
        if (report.status === 'FAILED') {
          toast.error(report.errorMessage ?? '对账失败');
        }
      }
    } catch {
      clearInterval(timer);
    }
  }, 2000);
}

function formatTime(iso?: string | null): string {
  if (!iso) {
    return '—';
  }
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function formatBytes(bytes?: number | null): string {
  if (bytes === undefined || bytes === null) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Compact per-verdict context: model/amount for bill rows, bucket counts, local model. */
function detailText(row: ReconciliationRow): string {
  const detail = row.detail ?? {};
  const parts: string[] = [];
  if (typeof detail.modelId === 'string') {
    parts.push(detail.modelId);
  }
  if (typeof detail.amount === 'string') {
    const suffix = typeof detail.currency === 'string' ? ` ${detail.currency}` : '';
    parts.push(`${detail.amount}${suffix}`);
  }
  if (typeof detail.bucketKey === 'string') {
    parts.push(`桶 ${detail.bucketKey}`);
  }
  if (typeof detail.providerCount === 'number' && typeof detail.localCount === 'number') {
    parts.push(`账单 ${detail.providerCount} / 本地 ${detail.localCount}`);
  }
  if (typeof detail.occurredAt === 'string') {
    parts.push(formatTime(detail.occurredAt));
  }
  return parts.join(' · ') || '—';
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-recon">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">账单对账</h1>
        <p class="ui-page-desc">
          上传供应商 canonical JSONL 账单（.gz 可选），异步生成四态报告；结果只读，不写入用量。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="recon-upload-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '上传账单' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section v-if="creating" class="ui-panel next-recon__upload" data-testid="recon-upload-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">上传账单</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-recon__grid">
          <UiInput
            v-model="providerCode"
            label="供应商 product_code"
            required
            data-testid="recon-provider"
          />
          <UiInput
            v-model="currency"
            label="币种（ISO-4217）"
            required
            data-testid="recon-currency"
          />
          <UiInput
            v-model="windowFrom"
            label="窗口开始"
            placeholder="2026-09-09T00:00:00Z"
            required
            data-testid="recon-window-from"
          />
          <UiInput
            v-model="windowTo"
            label="窗口结束"
            placeholder="2026-09-10T00:00:00Z"
            required
            data-testid="recon-window-to"
          />
          <label class="next-recon__file">
            <span class="next-recon__file-label">账单文件（.jsonl / .jsonl.gz，≤16MB）</span>
            <input
              type="file"
              accept=".jsonl,.gz,application/gzip,application/json"
              data-testid="recon-file"
              @change="onFilePicked"
            />
            <span v-if="fileHint" class="next-recon__file-hint" data-testid="recon-file-hint">{{
              fileHint
            }}</span>
          </label>
          <p v-if="formError" class="ui-form-error" data-testid="recon-form-error">
            {{ formError }}
          </p>
          <div class="next-recon__actions">
            <UiButton
              variant="primary"
              :disabled="submitting"
              data-testid="recon-submit"
              @click="submitUpload"
            >
              {{ submitting ? '上传中…' : '上传并匹配' }}
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section v-if="selected" class="ui-panel next-recon__detail" data-testid="recon-detail">
      <div class="ui-panel-head next-recon__detail-head">
        <div>
          <h2 class="ui-panel-title">
            报告 <span class="ui-mono">{{ selected.id.slice(0, 8) }}</span>
            <UiStatusBadge
              variant="pill"
              :tone="statusToneFor(selected.status)"
              :label="statusLabelFor(selected.status)"
            />
          </h2>
          <p class="ui-panel-sub">
            {{ selected.providerCode }} · {{ selected.currency }} ·
            {{ formatTime(selected.windowFrom) }} → {{ formatTime(selected.windowTo) }} · 上传于
            {{ formatTime(selected.createdAt) }}
          </p>
        </div>
        <UiButton variant="ghost" data-testid="recon-detail-close" @click="closeDetail"
          >关闭</UiButton
        >
      </div>
      <div class="ui-panel-body">
        <div v-if="selected.errorMessage" class="ui-alert ui-alert--error">
          {{ selected.errorMessage }}
        </div>
        <div class="next-recon__stats">
          <div class="next-recon__stat">
            <span class="next-recon__stat-label">总行数</span>
            <span class="next-recon__stat-value">{{ selected.totalRows ?? '—' }}</span>
          </div>
          <div class="next-recon__stat">
            <span class="next-recon__stat-label">一致</span>
            <span class="next-recon__stat-value">{{ selected.matched ?? '—' }}</span>
          </div>
          <div class="next-recon__stat">
            <span class="next-recon__stat-label">部分桶</span>
            <span class="next-recon__stat-value">{{ selected.partialBuckets ?? '—' }}</span>
          </div>
          <div class="next-recon__stat">
            <span class="next-recon__stat-label">供应商未匹配</span>
            <span class="next-recon__stat-value">{{ selected.unmatchedProvider ?? '—' }}</span>
          </div>
          <div class="next-recon__stat">
            <span class="next-recon__stat-label">本地未匹配</span>
            <span class="next-recon__stat-value">{{ selected.unmatchedLocal ?? '—' }}</span>
          </div>
          <div class="next-recon__stat">
            <span class="next-recon__stat-label">行错误</span>
            <span class="next-recon__stat-value">{{ selected.lineErrorCount ?? '—' }}</span>
          </div>
          <div class="next-recon__stat">
            <span class="next-recon__stat-label">金额差</span>
            <span class="next-recon__stat-value">{{ selected.amountDiff ?? '—' }}</span>
          </div>
          <div class="next-recon__stat">
            <span class="next-recon__stat-label">上传大小</span>
            <span class="next-recon__stat-value">{{ formatBytes(selected.uploadBytes) }}</span>
          </div>
        </div>

        <div class="next-recon__filters">
          <button
            v-for="f in filters"
            :key="f.value || 'all'"
            type="button"
            class="next-recon__filter"
            :class="{ 'next-recon__filter--on': rowsState === f.value }"
            :data-testid="`recon-filter-${f.value || 'all'}`"
            @click="setFilter(f.value)"
          >
            {{ f.label }}
          </button>
        </div>

        <div v-if="rowsError" class="ui-alert ui-alert--error">{{ rowsError }}</div>
        <UiTable
          :columns="rowColumns"
          :data="rows"
          :loading="rowsLoading"
          row-key="rowNo"
          empty-title="没有符合条件的明细行"
          data-testid="recon-rows-table"
        >
          <template #verdict="{ row }">
            <UiStatusBadge
              variant="pill"
              :tone="verdictTone[asDetailRow(row).verdict] ?? 'neutral'"
              :label="verdictLabel(asDetailRow(row).verdict)"
            />
          </template>
          <template #matchedBy="{ row }">{{ asDetailRow(row).matchedBy ?? '—' }}</template>
          <template #refs="{ row }">
            <span class="ui-mono">{{ asDetailRow(row).providerRowRef ?? '—' }}</span>
            <span class="next-recon__sep">/</span>
            <span class="ui-mono">{{ asDetailRow(row).localRef ?? '—' }}</span>
          </template>
          <template #detail="{ row }">{{ detailText(asDetailRow(row)) }}</template>
        </UiTable>
        <div v-if="nextCursor !== '' && nextCursor !== null" class="next-recon__more">
          <UiButton variant="ghost" data-testid="recon-rows-more" @click="loadRows(false)"
            >加载更多</UiButton
          >
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ reports.length }} 份报告</span>
        <UiButton variant="ghost" size="sm" data-testid="recon-refresh" @click="load"
          >刷新</UiButton
        >
      </div>
      <UiTable
        :columns="listColumns"
        :data="reports"
        :loading="loading"
        row-key="id"
        empty-title="还没有对账报告"
        data-testid="recon-table"
      >
        <template #createdAt="{ row }">{{ formatTime(asReport(row).createdAt) }}</template>
        <template #providerCode="{ row }">
          <span class="ui-mono">{{ asReport(row).providerCode }}</span>
        </template>
        <template #window="{ row }">
          <span class="ui-mono"
            >{{ formatTime(asReport(row).windowFrom) }} →
            {{ formatTime(asReport(row).windowTo) }}</span
          >
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            variant="pill"
            :tone="statusToneFor(asReport(row).status)"
            :label="statusLabelFor(asReport(row).status)"
          />
        </template>
        <template #totalRows="{ row }">
          <span class="ui-num">{{ asReport(row).totalRows?.toLocaleString() ?? '—' }}</span>
        </template>
        <template #amountDiff="{ row }">
          <span class="ui-num">{{ asReport(row).amountDiff ?? '—' }}</span>
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="ghost"
            size="sm"
            data-testid="recon-open"
            @click="selectReport(asReport(row))"
            >查看明细</UiButton
          >
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

.next-recon__upload,
.next-recon__detail {
  margin-bottom: var(--ui-space-5);
}

.next-recon__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4) var(--ui-space-6);
  max-width: 680px;
}

.next-recon__file {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  grid-column: 1 / -1;
}

.next-recon__file-label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  line-height: var(--ui-line-height-sm);
}

.next-recon__file-hint {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-recon__actions {
  display: flex;
  gap: var(--ui-space-2);
  grid-column: 1 / -1;
}

.next-recon__detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-4);
}

.next-recon__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-4);
}

.next-recon__stat {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  padding: var(--ui-space-3);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-recon__stat-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-recon__stat-value {
  font-size: var(--ui-font-size-md);
  font-weight: var(--ui-weight-semibold);
}

.next-recon__filters {
  display: inline-flex;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1);
  margin-bottom: var(--ui-space-3);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-recon__filter {
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

.next-recon__filter--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-recon__sep {
  margin: 0 var(--ui-space-1);
  color: var(--ui-foreground-secondary);
}

.next-recon__more {
  display: flex;
  justify-content: center;
  padding-top: var(--ui-space-3);
}
</style>
