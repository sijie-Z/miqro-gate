<script setup lang="ts">
/**
 * NextAdminRetentionLogsView — /app/retention-logs (ADR-0014 §8).
 * Compliance viewer for the retention ledger: filtered, decrypted page over
 * retention_log with a CSV export shaped like the audit export. Every read on
 * this page is itself audited by the backend (RETENTION_LOG_VIEW/EXPORT).
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiSelect, UiSwitch, UiTable } from '@/ui';
import type { AdminRetentionLogView, RetentionConfigView } from '@/types/generated-api';

const rows = ref<AdminRetentionLogView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const userIdFilter = ref('');
const userIdError = ref('');
const directionFilter = ref('');
const protocolFilter = ref('');
const fromFilter = ref('');
const toFilter = ref('');

const exporting = ref(false);
const exportNotice = ref('');
const exportIsError = ref(false);

const viewing = ref<AdminRetentionLogView | null>(null);

// ---- #688 采集配置（ADR-0014：租户级单一开关 + 内容上限）----
const config = ref<RetentionConfigView | null>(null);
const configLoading = ref(true);
const configSaving = ref(false);
const configError = ref('');
const configNotice = ref('');
const configEnabled = ref(false);
const configMaxBytes = ref('262144');

const MIN_MAX_BYTES = 1024;
const MAX_MAX_BYTES = 4194304;

async function loadConfig() {
  configLoading.value = true;
  configError.value = '';
  try {
    const view = await api.getRetentionConfig();
    config.value = view;
    configEnabled.value = view.enabled ?? false;
    configMaxBytes.value = String(view.maxContentBytes ?? 262144);
  } catch (error) {
    configError.value = configErrorText(error);
  } finally {
    configLoading.value = false;
  }
}

function configErrorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return '加载采集配置失败，请稍后重试。';
}

async function saveConfig() {
  const bytes = Number(configMaxBytes.value);
  if (!Number.isInteger(bytes) || bytes < MIN_MAX_BYTES || bytes > MAX_MAX_BYTES) {
    configError.value = `内容上限必须是 ${MIN_MAX_BYTES}–${MAX_MAX_BYTES} 之间的整数（字节）。`;
    configNotice.value = '';
    return;
  }
  configSaving.value = true;
  configError.value = '';
  configNotice.value = '';
  try {
    const view = await api.putRetentionConfig({
      enabled: configEnabled.value,
      maxContentBytes: bytes,
    });
    config.value = view;
    configNotice.value = '已保存——保存后数秒内生效，无需同步。';
  } catch (error) {
    configError.value = error instanceof ApiError ? error.message : '保存失败，请稍后重试。';
  } finally {
    configSaving.value = false;
  }
}

const directionOptions = [
  { value: '', label: '全部方向' },
  { value: 'INPUT', label: '输入' },
  { value: 'OUTPUT', label: '输出' },
];

const protocolOptions = [
  { value: '', label: '全部协议' },
  { value: 'ANTHROPIC_MESSAGES', label: 'ANTHROPIC_MESSAGES' },
  { value: 'OPENAI_CHAT', label: 'OPENAI_CHAT' },
  { value: 'OPENAI_RESPONSES', label: 'OPENAI_RESPONSES' },
];

const columns = [
  { key: 'occurredAt', title: '时间', width: '180px' },
  { key: 'direction', title: '方向', width: '90px' },
  { key: 'userName', title: '用户', width: '150px' },
  { key: 'wireProtocol', title: '协议', width: '190px' },
  { key: 'textCharCount', title: '字符数', width: '90px', align: 'right' as const },
  { key: 'dataMd5', title: 'MD5', width: '130px' },
  { key: 'text', title: '内容', minWidth: '320px' },
];

function validUserId(): boolean {
  userIdError.value = '';
  const value = userIdFilter.value.trim();
  if (
    value &&
    !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value)
  ) {
    userIdError.value = '用户必须是 UUID。';
    return false;
  }
  return true;
}

function queryFilters() {
  return {
    userId: userIdFilter.value.trim() || undefined,
    direction: directionFilter.value || undefined,
    protocol: protocolFilter.value || undefined,
    from: toIso(fromFilter.value),
    to: toIso(toFilter.value),
  };
}

// #1245: request-sequence guard — 改方向/协议筛选或连点「查询」时，让慢的旧响应
// 落在新响应之后会把表格重新画成上一次筛选的行（行必须与上方控件一致）。
let loadRequestSeq = 0;

async function load() {
  if (!validUserId()) {
    return;
  }
  const seq = ++loadRequestSeq;
  loading.value = true;
  loadError.value = '';
  try {
    const list = await api.retentionLogs({ ...queryFilters(), size: 50 });
    if (seq !== loadRequestSeq) {
      return; // a newer filter set won — this response is stale
    }
    rows.value = list;
  } catch (error) {
    if (seq === loadRequestSeq && error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    if (seq === loadRequestSeq) {
      loading.value = false;
    }
  }
}

/** datetime-local value (browser-local) → UTC ISO instant, matching the API contract. */
function toIso(local: string): string | undefined {
  if (!local) return undefined;
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

async function exportCsv() {
  if (!validUserId()) {
    return;
  }
  exporting.value = true;
  exportNotice.value = '';
  exportIsError.value = false;
  try {
    const { csv, truncated } = await api.exportRetentionLogsCsv(queryFilters());
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `retention-logs-${new Date().toISOString().replace(/[:.]/g, '-')}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
    const count = csv.trim().split('\n').length - 1;
    exportNotice.value = truncated
      ? `已导出前 ${count} 行并截断（单次上限 5 万行）——请缩小时间范围后重试。`
      : `已导出 ${count} 行 CSV。`;
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
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}:${pad(d.getSeconds())}`;
}

function directionLabel(direction?: string): string {
  return direction === 'OUTPUT' ? '输出' : direction === 'INPUT' ? '输入' : '—';
}

/** One-line preview; the dialog shows the full decrypted text. */
function preview(text?: string | null): string {
  if (text == null) return '（无法解密）';
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > 60 ? `${flat.slice(0, 60)}…` : flat;
}

onMounted(() => {
  void load();
  void loadConfig();
});
</script>

<template>
  <div class="ui-page next-retention">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">内容留痕</h1>
        <p class="ui-page-desc">
          开启留痕的租户其输入与模型输出在此可查（AES-GCM
          密文落库，本页按管理员权限解密展示）；查看与导出动作本身进入审计。
        </p>
      </div>
    </header>

    <!-- #688 采集配置：开关 + 内容上限（对齐腾讯「包体采集」高级设置） -->
    <section class="ui-panel next-retention__config" data-testid="retention-config-card">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">采集配置</h2>
        <span class="next-retention__config-scope">租户级 · 覆盖请求与响应双面</span>
      </div>
      <div v-if="configLoading" class="next-retention__config-body">正在加载配置…</div>
      <div v-else class="next-retention__config-body">
        <UiSwitch v-model="configEnabled" data-testid="retention-config-enabled">
          开启内容留痕
        </UiSwitch>
        <p class="next-retention__config-hint">
          开启后，请求的用户文本与模型输出将经网关旁路加密落库（AES-GCM），供合规查看；工具调用载荷、系统提示词与推理链仍不采集（ADR-0014）。
        </p>
        <UiInput
          v-model="configMaxBytes"
          label="内容上限（字节）"
          type="number"
          width="220px"
          hint="超限截断并标记 truncated；范围 1024–4194304（1 KiB–4 MiB）。与腾讯不同：我们无「不截断」档，也没有请求/响应分别开关。"
          data-testid="retention-config-max-bytes"
        />
        <div class="next-retention__config-actions">
          <UiButton
            variant="primary"
            :loading="configSaving"
            data-testid="retention-config-save"
            @click="saveConfig"
          >
            保存
          </UiButton>
          <span
            v-if="configNotice"
            class="next-retention__config-notice"
            data-testid="retention-config-notice"
            >{{ configNotice }}</span
          >
        </div>
        <p v-if="configError" class="ui-form-error" data-testid="retention-config-error">
          {{ configError }}
        </p>
      </div>
    </section>

    <section class="ui-panel next-retention__filter">
      <div class="next-retention__filters">
        <UiInput
          v-model="userIdFilter"
          placeholder="用户 UUID"
          width="280px"
          data-testid="retention-user-filter"
        />
        <UiSelect
          v-model="directionFilter"
          :options="directionOptions"
          width="140px"
          data-testid="retention-direction-filter"
        />
        <UiSelect
          v-model="protocolFilter"
          :options="protocolOptions"
          width="210px"
          data-testid="retention-protocol-filter"
        />
        <UiInput
          v-model="fromFilter"
          type="datetime-local"
          width="200px"
          data-testid="retention-from"
        />
        <UiInput
          v-model="toFilter"
          type="datetime-local"
          width="200px"
          data-testid="retention-to"
        />
        <UiButton variant="primary" data-testid="retention-refresh" @click="load">查询</UiButton>
        <UiButton
          variant="secondary"
          :loading="exporting"
          data-testid="retention-export"
          @click="exportCsv"
        >
          导出 CSV
        </UiButton>
      </div>
      <p v-if="userIdError" class="ui-form-error" data-testid="retention-user-error">
        {{ userIdError }}
      </p>
      <div
        v-if="exportNotice"
        class="next-retention__notice"
        :class="{ 'next-retention__notice--error': exportIsError }"
        data-testid="retention-export-notice"
      >
        {{ exportNotice }}
      </div>
    </section>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section class="ui-panel">
      <UiTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        row-key="eventId"
        empty-title="没有匹配的留痕记录"
        data-testid="retention-table"
        :error="loadError"
        @retry="load"
      >
        <template #occurredAt="{ row }">{{
          formatTime((row as AdminRetentionLogView).occurredAt)
        }}</template>
        <template #direction="{ row }">{{
          directionLabel((row as AdminRetentionLogView).direction)
        }}</template>
        <template #userName="{ row }">{{
          (row as AdminRetentionLogView).userName || '—'
        }}</template>
        <template #wireProtocol="{ row }">
          <span class="ui-mono">{{ (row as AdminRetentionLogView).wireProtocol }}</span>
        </template>
        <template #textCharCount="{ row }">
          <span class="ui-num">{{ (row as AdminRetentionLogView).textCharCount }}</span>
        </template>
        <template #dataMd5="{ row }">
          <span class="ui-mono next-retention__md5">{{
            (row as AdminRetentionLogView).dataMd5 || '—'
          }}</span>
        </template>
        <template #text="{ row }">
          <button
            type="button"
            class="next-retention__text-link"
            :data-testid="`retention-view-${(row as AdminRetentionLogView).eventId}`"
            @click="viewing = row as AdminRetentionLogView"
          >
            {{ preview((row as AdminRetentionLogView).text) }}
          </button>
        </template>
      </UiTable>
    </section>

    <UiDialog
      v-if="viewing"
      :open="!!viewing"
      title="留痕详情"
      :description="`${directionLabel(viewing.direction)} · ${viewing.wireProtocol} · ${formatTime(viewing.occurredAt)}`"
      width="640px"
      data-testid="retention-detail"
      @update:open="viewing = null"
    >
      <p class="next-retention__meta">
        用户：{{ viewing.userName || viewing.userId }} · 字符数：{{ viewing.textCharCount }} ·
        MD5：{{ viewing.dataMd5 || '—'
        }}<span v-if="viewing.truncated"> · <strong>已被截断</strong></span>
      </p>
      <pre class="next-retention__full" data-testid="retention-full-text">{{
        viewing.text ?? '（无法解密：密钥版本不可用）'
      }}</pre>
      <template #footer>
        <UiButton variant="secondary" data-testid="retention-detail-close" @click="viewing = null">
          关闭
        </UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.next-retention__filter {
  margin-bottom: var(--ui-space-5);
}

.next-retention__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
  align-items: center;
}

.next-retention__notice {
  margin-top: var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-retention__notice--error {
  color: var(--ui-danger-fg);
}

.next-retention__md5 {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-retention__text-link {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  color: var(--ui-foreground);
  cursor: pointer;
  text-align: left;
}

.next-retention__text-link:hover {
  color: var(--ui-primary);
}

.next-retention__meta {
  margin: 0 0 var(--ui-space-2);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-retention__full {
  margin: 0;
  padding: var(--ui-space-3);
  border-radius: var(--ui-radius-control);
  background: var(--ui-muted);
  font-size: var(--ui-font-size-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 420px;
  overflow: auto;
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
.next-retention__config {
  margin-bottom: var(--ui-space-4);
}

.next-retention__config-scope {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-retention__config-body {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  padding: var(--ui-space-4) var(--ui-space-5);
}

.next-retention__config-hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  max-width: 720px;
}

.next-retention__config-actions {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-retention__config-notice {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-success-fg, var(--ui-foreground-secondary));
}
</style>
