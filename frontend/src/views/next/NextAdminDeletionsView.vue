<script setup lang="ts">
/**
 * NextAdminDeletionsView — /app/deletions v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy deletions page: window preview count,
 * create a deletion request returning a one-shot confirm token, then a
 * confirmation step executes the physical delete. Requests and audit chain
 * stay; raw usage rows are removed.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type { UsageDeletionRequest } from '@/types/api';

const deletions = ref<UsageDeletionRequest[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const from = ref('2026-08-01T00:00:00Z');
const to = ref('2026-08-31T00:00:00Z');
const previewCount = ref<number | null>(null);
const previewing = ref(false);
const previewError = ref('');

const confirmDialogOpen = ref(false);
const confirmToken = ref('');
const pendingDeletionId = ref('');
const confirmError = ref('');
const confirming = ref(false);

const columns = [
  { key: 'period', title: '窗口', minWidth: '220px' },
  { key: 'previewCount', title: '预览计数', width: '110px', align: 'right' as const },
  { key: 'status', title: '状态', width: '170px' },
  { key: 'deletedCount', title: '已删除', width: '110px', align: 'right' as const },
  { key: 'createdAt', title: '创建时间', width: '180px' },
];

const statusText: Record<string, string> = {
  PENDING_CONFIRMATION: '待确认',
  CONFIRMED: '已确认',
  EXECUTED: '已执行',
  EXPIRED: '已过期',
};

const statusTone: Record<string, 'warning' | 'success' | 'neutral' | 'danger'> = {
  PENDING_CONFIRMATION: 'warning',
  CONFIRMED: 'info',
  EXECUTED: 'success',
  EXPIRED: 'neutral',
};

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    deletions.value = await api.deletionRecent();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function preview() {
  previewing.value = true;
  previewError.value = '';
  try {
    const result = await api.deletionPreview(from.value, to.value);
    previewCount.value = result.count;
  } catch (error) {
    previewError.value = error instanceof ApiError ? error.message : '预览失败';
  } finally {
    previewing.value = false;
  }
}

async function createDeletion() {
  if (previewCount.value === null) {
    toast.info('请先预览确认窗口内的记录数');
    return;
  }
  try {
    const created = await api.createDeletion(from.value, to.value);
    confirmToken.value = created.confirmToken;
    pendingDeletionId.value = created.id;
    confirmError.value = '';
    confirmDialogOpen.value = true;
    await load();
  } catch (error) {
    previewError.value = error instanceof ApiError ? error.message : '创建失败';
  }
}

async function confirmDeletion() {
  if (!confirmToken.value.trim()) {
    confirmError.value = '请粘贴确认 Token';
    return;
  }
  confirming.value = true;
  confirmError.value = '';
  try {
    await api.confirmDeletion(pendingDeletionId.value, confirmToken.value.trim());
    confirmDialogOpen.value = false;
    toast.success('用量记录已删除（删除请求与审计链保留）');
    await load();
  } catch (error) {
    confirmError.value = error instanceof ApiError ? error.message : '确认失败';
  } finally {
    confirming.value = false;
  }
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
  <div class="ui-page next-deletions">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">用量删除</h1>
        <p class="ui-page-desc">物理删除时间窗口内的原始用量；删除请求本身与审计链永久保留。</p>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section class="ui-panel next-deletions__filter" data-testid="deletion-filter-bar">
      <div class="ui-panel-toolbar">
        <UiInput
          v-model="from"
          placeholder="起始时间（ISO）"
          width="240px"
          data-testid="deletion-from"
        />
        <UiInput
          v-model="to"
          placeholder="结束时间（ISO）"
          width="240px"
          data-testid="deletion-to"
        />
        <UiButton
          variant="secondary"
          :loading="previewing"
          data-testid="deletion-preview"
          @click="preview"
        >
          预览计数
        </UiButton>
        <UiButton variant="danger" data-testid="deletion-create" @click="createDeletion"
          >发起删除</UiButton
        >
      </div>
      <div class="ui-panel-body next-deletions__preview">
        <p
          v-if="previewCount !== null"
          class="next-deletions__note"
          data-testid="deletion-preview-count"
        >
          窗口内将删除
          <strong class="ui-num">{{ previewCount.toLocaleString() }}</strong>
          行用量记录；发起删除后需在 1 小时内用一次性 Token 确认。
        </p>
        <p v-if="previewError" class="ui-form-error">{{ previewError }}</p>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ deletions.length }} 个删除请求</span>
      </div>
      <UiTable
        :columns="columns"
        :data="deletions"
        :loading="loading"
        row-key="id"
        empty-title="还没有删除请求"
        data-testid="deletions-table"
      >
        <template #period="{ row }">
          <span class="ui-mono"
            >{{ (row as UsageDeletionRequest).periodFrom.slice(0, 10) }} →
            {{ (row as UsageDeletionRequest).periodTo.slice(0, 10) }}</span
          >
        </template>
        <template #previewCount="{ row }">
          <span class="ui-num">{{
            (row as UsageDeletionRequest).previewCount.toLocaleString()
          }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            variant="pill"
            :tone="statusTone[(row as UsageDeletionRequest).status] ?? 'neutral'"
            :label="
              statusText[(row as UsageDeletionRequest).status] ??
              (row as UsageDeletionRequest).status
            "
          />
        </template>
        <template #deletedCount="{ row }">
          <span class="ui-num">{{
            (row as UsageDeletionRequest).deletedCount?.toLocaleString() ?? '—'
          }}</span>
        </template>
        <template #createdAt="{ row }">{{
          formatTime((row as UsageDeletionRequest).createdAt)
        }}</template>
      </UiTable>
    </section>

    <!-- One-shot token confirmation -->
    <UiDialog
      :open="confirmDialogOpen"
      title="确认物理删除"
      description="此操作不可撤销。请粘贴创建删除请求时返回的一次性确认 Token；确认后窗口内原始用量将被物理清除（请求与审计链保留）。"
      width="480px"
      @update:open="confirmDialogOpen = false"
    >
      <div class="next-deletions__token">
        <div class="ui-mono next-deletions__token-once" data-testid="deletion-confirm-token-once">
          {{ confirmToken }}
        </div>
        <UiInput
          v-model="confirmToken"
          label="粘贴确认 Token"
          placeholder="一次有效，执行后不可恢复"
          data-testid="deletion-confirm-token"
        />
        <p v-if="confirmError" class="ui-form-error" data-testid="deletion-confirm-error">
          {{ confirmError }}
        </p>
      </div>
      <template #footer>
        <UiButton variant="ghost" @click="confirmDialogOpen = false">取消</UiButton>
        <UiButton
          variant="danger"
          :loading="confirming"
          data-testid="deletion-confirm-submit"
          @click="confirmDeletion"
        >
          确认并删除
        </UiButton>
      </template>
    </UiDialog>
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

.next-deletions__filter {
  margin-bottom: var(--ui-space-5);
}

.next-deletions__filter :deep(.ui-panel-toolbar) {
  flex-wrap: wrap;
}

.next-deletions__preview {
  padding-top: var(--ui-space-2);
}

.next-deletions__note {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-deletions__note strong {
  color: var(--ui-danger-fg);
}

.next-deletions__token {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-deletions__token-once {
  padding: var(--ui-space-2) var(--ui-space-3);
  background: var(--ui-muted);
  border: 1px dashed var(--ui-border-strong);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-xs);
  word-break: break-all;
}
</style>
