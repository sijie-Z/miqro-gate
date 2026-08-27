<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
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

const confirmDialog = ref(false);
const confirmToken = ref('');
const pendingDeletionId = ref('');

async function load() {
  loading.value = true;
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
    previewError.value = error instanceof ApiError ? error.message : '预览失败，请稍后重试。';
  } finally {
    previewing.value = false;
  }
}

async function createDeletion() {
  try {
    const request = await api.createDeletion(from.value, to.value);
    pendingDeletionId.value = request.id;
    confirmToken.value = request.confirmToken;
    confirmDialog.value = true;
  } catch (error) {
    MessagePlugin.error(error instanceof ApiError ? error.message : '创建失败，请稍后重试。');
  }
}

async function confirmDeletion() {
  try {
    const result = await api.confirmDeletion(pendingDeletionId.value, confirmToken.value);
    confirmDialog.value = false;
    MessagePlugin.success(`已删除 ${result.deletedCount ?? 0} 条用量记录（永久）。`);
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

function formatTime(iso?: string): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

onMounted(load);
</script>

<template>
  <div class="deletions-page">
    <PageHeader
      title="Usage Deletions"
      description="按时间窗永久删除用量记录；需一次性确认 token，删除不可撤销。"
    />

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <div class="mk-filter-bar" data-testid="deletion-filter-bar">
      <t-input v-model="from" class="filter-input" data-testid="deletion-from" />
      <t-input v-model="to" class="filter-input" data-testid="deletion-to" />
      <t-button :loading="previewing" data-testid="deletion-preview" @click="preview"
        >预览计数</t-button
      >
      <t-button theme="danger" data-testid="deletion-create" @click="createDeletion"
        >创建删除请求</t-button
      >
    </div>
    <p v-if="previewCount !== null" class="preview-note" data-testid="deletion-preview-count">
      将删除 <b class="mk-num">{{ previewCount }}</b> 条记录。
    </p>
    <p v-if="previewError" class="preview-note preview-error">{{ previewError }}</p>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        :data="deletions"
        data-testid="deletions-table"
        row-key="id"
        size="small"
        :columns="[
          { colKey: 'period', title: '窗口', minWidth: 200 },
          { colKey: 'previewCount', title: '预览计数', width: 100, align: 'right' },
          { colKey: 'status', title: '状态', width: 170 },
          { colKey: 'deletedCount', title: '已删除', width: 100, align: 'right' },
          { colKey: 'createdAt', title: '创建时间', width: 170 },
        ]"
      >
        <template #period="{ row }">
          <span class="mk-mono"
            >{{ row.periodFrom.slice(0, 10) }} → {{ row.periodTo.slice(0, 10) }}</span
          >
        </template>
        <template #previewCount="{ row }"
          ><span class="mk-num">{{ row.previewCount }}</span></template
        >
        <template #status="{ row }">
          <span
            class="mk-status"
            :class="
              row.status === 'EXECUTED'
                ? 'mk-status--danger'
                : row.status === 'PENDING_CONFIRMATION'
                  ? 'mk-status--warning'
                  : 'mk-status--neutral'
            "
          >
            {{ row.status }}
          </span>
        </template>
        <template #deletedCount="{ row }"
          ><span class="mk-num">{{ row.deletedCount ?? '—' }}</span></template
        >
        <template #createdAt="{ row }">{{ formatTime(row.createdAt) }}</template>
      </t-table>
    </t-loading>

    <t-dialog
      v-model:visible="confirmDialog"
      header="确认删除（二次确认）"
      width="460px"
      data-testid="deletion-confirm-dialog"
    >
      <p>
        删除请求已创建。请粘贴创建时返回的<strong>一次性确认 token</strong>
        完成删除。删除是永久且不可撤销的。
      </p>
      <t-textarea v-model="confirmToken" data-testid="deletion-confirm-token" />
      <template #footer>
        <t-button @click="confirmDialog = false">取消</t-button>
        <t-button theme="danger" data-testid="deletion-confirm-submit" @click="confirmDeletion"
          >确认删除</t-button
        >
      </template>
    </t-dialog>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.filter-input {
  width: 220px;
}

.preview-note {
  margin: 8px 0;
  color: var(--miqrokey-text-secondary);
}

.preview-error {
  color: var(--miqrokey-danger);
}
</style>
