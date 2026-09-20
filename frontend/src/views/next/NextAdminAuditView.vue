<script setup lang="ts">
/**
 * NextAdminAuditView — /app/audit v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy audit page: reverse chain list with an
 * action filter; chain hashes are never serialized.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiInput, UiSelect, UiTable } from '@/ui';
import type { AuditEventView } from '@/types/generated-api';
import {
  TARGET_TYPE_LABELS,
  actionLabel,
  summaryText,
  targetTypeLabel,
} from '@/utils/audit-labels';

const events = ref<AuditEventView[]>([]);

/** Top actions within the currently loaded events (labelled as such). */
const topActions = computed(() => {
  const counts = new Map<string, number>();
  for (const e of events.value) {
    const key = e.action || 'UNKNOWN';
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const ranked = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6);
  const max = Math.max(...ranked.map(([, n]) => n), 1);
  return ranked.map(([action, count]) => ({
    action,
    count,
    width: `${Math.max(6, (count / max) * 100)}%`,
  }));
});
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');
const actionFilter = ref('');
const targetTypeFilter = ref('');
const actorFilter = ref('');
const actorError = ref('');
const fromFilter = ref('');
const toFilter = ref('');
const exporting = ref(false);
const exportNotice = ref('');
const exportIsError = ref(false);

/** Resource types actually recorded by the control plane (free text stays possible via the API). */
const TARGET_TYPES = [
  'ADMIN_API_KEY',
  'AGENT',
  'ALERT_RULE',
  'BUDGET',
  'CONFIG',
  'CONSUMER',
  'DELETION',
  'GRANT',
  'MCP_SERVICE',
  'MCP_TOOL',
  'MODEL_APPROVAL',
  'MODEL_CATALOG',
  'PRICE_SNAPSHOT',
  'PROJECT',
  'PROVIDER_PRODUCT',
  'QUOTA_RULE',
  'RECONCILIATION',
  'SEAT',
  'SERVICE',
  'SESSION',
  'SKILL',
  'SUBSCRIPTION',
  'TEAM',
  'TENANT',
  'UPSTREAM_CREDENTIAL',
  'USER',
  'VIRTUAL_KEY',
  'WEBHOOK',
];

const targetTypeOptions = [
  { value: '', label: '全部类型' },
  ...TARGET_TYPES.map((type) => ({ value: type, label: TARGET_TYPE_LABELS[type] ?? type })),
];

const columns = [
  { key: 'chainPosition', title: '位置', width: '90px', align: 'right' as const },
  { key: 'createdAt', title: '时间', width: '180px' },
  { key: 'actor', title: '操作者', width: '160px' },
  { key: 'action', title: '动作', width: '200px' },
  { key: 'targetType', title: '目标类型', width: '120px' },
  { key: 'target', title: '目标', minWidth: '180px' },
  { key: 'changeSummary', title: '摘要', minWidth: '260px' },
];

/** #389: unresolved references fall back to a short id so rows stay readable. */
function shortId(id: string): string {
  return id.length > 9 ? `${id.slice(0, 8)}…` : id;
}

/** actorId must be UUID-shaped; an invalid value blocks the request with an inline hint. */
function validActor(): boolean {
  actorError.value = '';
  const actor = actorFilter.value.trim();
  if (
    actor &&
    !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(actor)
  ) {
    actorError.value = '操作者必须是 UUID。';
    return false;
  }
  return true;
}

async function load() {
  if (!validActor()) {
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    events.value = await api.auditEvents({
      action: actionFilter.value.trim() || undefined,
      targetType: targetTypeFilter.value.trim() || undefined,
      actorId: actorFilter.value.trim() || undefined,
      from: toIso(fromFilter.value),
      to: toIso(toFilter.value),
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

/** datetime-local value (browser-local) → UTC ISO instant, matching the API contract. */
function toIso(local: string): string | undefined {
  if (!local) return undefined;
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

/** Quick windows: fill the datetime-local inputs with the trailing N days and query. */
function applyRange(days: number) {
  const now = new Date();
  fromFilter.value = toLocalInput(new Date(now.getTime() - days * 24 * 3600 * 1000));
  toFilter.value = toLocalInput(now);
  void load();
}

function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

async function exportCsv() {
  if (!validActor()) {
    return;
  }
  exporting.value = true;
  exportNotice.value = '';
  exportIsError.value = false;
  try {
    const { csv, truncated } = await api.exportAuditCsv({
      action: actionFilter.value.trim() || undefined,
      targetType: targetTypeFilter.value.trim() || undefined,
      actorId: actorFilter.value.trim() || undefined,
      from: toIso(fromFilter.value),
      to: toIso(toFilter.value),
    });
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `audit-events-${new Date().toISOString().replace(/[:.]/g, '-')}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
    const rows = csv.trim().split('\n').length - 1;
    exportNotice.value = truncated
      ? `已导出前 ${rows} 行并截断（单次上限 5 万行）——请缩小时间范围或补充筛选后重试。`
      : `已导出 ${rows} 行 CSV。`;
  } catch (error) {
    exportIsError.value = true;
    exportNotice.value = error instanceof ApiError ? error.message : '导出失败，请稍后重试。';
  } finally {
    exporting.value = false;
  }
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
  <div class="ui-page next-audit">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">审计日志</h1>
        <p class="ui-page-desc">哈希链完整性校验的审计事件；链哈希不会出现在任何响应中。</p>
      </div>
    </header>

    <section class="ui-panel next-audit__filter">
      <div class="next-audit__filters">
        <UiInput
          v-model="actionFilter"
          placeholder="动作，如 LOGIN_SUCCESS"
          width="200px"
          data-testid="audit-action-filter"
        />
        <UiSelect
          v-model="targetTypeFilter"
          :options="targetTypeOptions"
          width="200px"
          data-testid="audit-targettype-filter"
        />
        <UiInput
          v-model="actorFilter"
          placeholder="操作者 UUID"
          width="260px"
          data-testid="audit-actor-filter"
        />
        <UiInput
          v-model="fromFilter"
          type="datetime-local"
          width="200px"
          data-testid="audit-from"
        />
        <UiInput v-model="toFilter" type="datetime-local" width="200px" data-testid="audit-to" />
        <UiButton variant="ghost" data-testid="audit-range-7" @click="applyRange(7)"
          >近 7 天</UiButton
        >
        <UiButton variant="ghost" data-testid="audit-range-30" @click="applyRange(30)"
          >近 30 天</UiButton
        >
        <UiButton variant="primary" data-testid="audit-refresh" @click="load">查询</UiButton>
        <UiButton
          variant="secondary"
          :loading="exporting"
          data-testid="audit-export"
          @click="exportCsv"
        >
          导出 CSV
        </UiButton>
      </div>
      <p v-if="actorError" class="ui-form-error" data-testid="audit-actor-error">
        {{ actorError }}
      </p>
      <div
        v-if="exportNotice"
        class="next-audit__notice"
        :class="{ 'next-audit__notice--error': exportIsError }"
        data-testid="audit-export-notice"
      >
        {{ exportNotice }}
      </div>
    </section>

    <section
      v-if="events.length"
      class="ui-panel next-audit__actions"
      data-testid="audit-action-dist"
    >
      <div class="ui-panel-head">
        <div>
          <h2 class="ui-panel-title">动作分布</h2>
          <span class="ui-panel-sub">基于当前 {{ events.length }} 条记录</span>
        </div>
      </div>
      <div class="ui-panel-body next-audit__action-body">
        <div v-for="row in topActions" :key="row.action" class="next-audit__action-row">
          <span class="next-audit__action-label" :title="row.action">{{
            actionLabel(row.action)
          }}</span>
          <div class="next-audit__action-track">
            <div class="next-audit__action-fill" :style="{ width: row.width }" />
          </div>
          <span class="next-audit__action-count ui-num">{{ row.count }}</span>
        </div>
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
        :error="loadError"
        @retry="load"
      >
        <template #chainPosition="{ row }">
          <span class="ui-num">{{ (row as AuditEventView).chainPosition }}</span>
        </template>
        <template #createdAt="{ row }">{{
          formatTime((row as AuditEventView).createdAt)
        }}</template>
        <template #actor="{ row }">
          <span v-if="(row as AuditEventView).actorName">{{
            (row as AuditEventView).actorName
          }}</span>
          <span
            v-else-if="(row as AuditEventView).actorId"
            class="ui-mono"
            data-testid="audit-actor-fallback"
            >{{ shortId((row as AuditEventView).actorId!) }}</span
          >
          <span v-else>—</span>
        </template>
        <template #action="{ row }">
          <span :title="(row as AuditEventView).action">{{
            actionLabel((row as AuditEventView).action)
          }}</span>
        </template>
        <template #targetType="{ row }">{{
          targetTypeLabel((row as AuditEventView).targetType)
        }}</template>
        <template #target="{ row }">
          <span v-if="(row as AuditEventView).targetName">{{
            (row as AuditEventView).targetName
          }}</span>
          <span v-else-if="(row as AuditEventView).targetId" class="ui-mono">{{
            shortId((row as AuditEventView).targetId!)
          }}</span>
          <span v-else>—</span>
        </template>
        <template #changeSummary="{ row }">
          <span class="next-audit__summary" :title="(row as AuditEventView).changeSummary">{{
            summaryText((row as AuditEventView).changeSummary)
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

.next-audit__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
  align-items: center;
}

.next-audit__actions {
  margin-bottom: var(--ui-space-5);
}

.next-audit__action-body {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-audit__action-row {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr) 56px;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
}

.next-audit__action-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ui-foreground-secondary);
}

.next-audit__action-track {
  height: 8px;
  border-radius: 2px;
  background: var(--ui-muted);
  overflow: hidden;
}

.next-audit__action-fill {
  height: 100%;
  border-radius: 2px;
  background: var(--ui-primary);
}

.next-audit__action-count {
  text-align: right;
  color: var(--ui-foreground);
}

.next-audit__notice {
  margin-top: var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-audit__notice--error {
  color: var(--ui-danger-fg);
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
