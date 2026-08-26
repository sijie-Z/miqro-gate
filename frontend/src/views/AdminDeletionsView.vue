<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
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
    ElMessage.error(error instanceof ApiError ? error.message : '创建失败，请稍后重试。');
  }
}

async function confirmDeletion() {
  try {
    const result = await api.confirmDeletion(pendingDeletionId.value, confirmToken.value);
    confirmDialog.value = false;
    ElMessage.success(`已删除 ${result.deletedCount ?? 0} 条用量记录（永久）。`);
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
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

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <div class="mk-filter-bar" data-testid="deletion-filter-bar">
      <el-input v-model="from" class="filter-input" data-testid="deletion-from" />
      <el-input v-model="to" class="filter-input" data-testid="deletion-to" />
      <el-button :loading="previewing" data-testid="deletion-preview" @click="preview"
        >预览计数</el-button
      >
      <el-button type="danger" data-testid="deletion-create" @click="createDeletion"
        >创建删除请求</el-button
      >
    </div>
    <p v-if="previewCount !== null" class="preview-note" data-testid="deletion-preview-count">
      将删除 <b class="mk-num">{{ previewCount }}</b> 条记录。
    </p>
    <p v-if="previewError" class="preview-note preview-error">{{ previewError }}</p>

    <el-table v-loading="loading" :data="deletions" data-testid="deletions-table">
      <el-table-column label="窗口" min-width="200">
        <template #default="{ row }">
          <span class="mk-mono"
            >{{ row.periodFrom.slice(0, 10) }} → {{ row.periodTo.slice(0, 10) }}</span
          >
        </template>
      </el-table-column>
      <el-table-column prop="previewCount" label="预览计数" width="100" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.previewCount }}</span></template
        >
      </el-table-column>
      <el-table-column label="状态" width="170">
        <template #default="{ row }">
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
      </el-table-column>
      <el-table-column label="已删除" width="100" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.deletedCount ?? '—' }}</span></template
        >
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="confirmDialog"
      title="确认删除（二次确认）"
      width="460px"
      data-testid="deletion-confirm-dialog"
    >
      <p>
        删除请求已创建。请粘贴创建时返回的<strong>一次性确认 token</strong>
        完成删除。删除是永久且不可撤销的。
      </p>
      <el-input
        v-model="confirmToken"
        type="textarea"
        :rows="2"
        data-testid="deletion-confirm-token"
      />
      <template #footer>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button type="danger" data-testid="deletion-confirm-submit" @click="confirmDeletion"
          >确认删除</el-button
        >
      </template>
    </el-dialog>
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
