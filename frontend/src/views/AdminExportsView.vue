<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { ExportTask } from '@/types/api';

const tasks = ref<ExportTask[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const format = ref<'CSV' | 'JSONL'>('CSV');
const from = ref('2026-08-01T00:00:00Z');
const to = ref('2026-08-31T00:00:00Z');
const creating = ref(false);
const formError = ref('');

async function load() {
  loading.value = true;
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
  creating.value = true;
  formError.value = '';
  try {
    const task = await api.createExport(format.value, from.value, to.value);
    ElMessage.success(`导出任务已创建（${task.id.slice(0, 8)}…）`);
    await load();
    poll(task.id);
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    creating.value = false;
  }
}

/** Polls until the task finishes so the download link appears. */
function poll(id: string) {
  const timer = window.setInterval(async () => {
    try {
      const task = await api.exportStatus(id);
      if (task.status === 'SUCCEEDED' || task.status === 'FAILED') {
        window.clearInterval(timer);
        await load();
      }
    } catch {
      window.clearInterval(timer);
    }
  }, 2000);
}

async function download(task: ExportTask) {
  window.location.href = `/api/v1/admin/exports/${task.id}/download`;
}

function statusClass(status: string): string {
  switch (status) {
    case 'SUCCEEDED':
      return 'mk-status--success';
    case 'FAILED':
    case 'EXPIRED':
      return 'mk-status--danger';
    default:
      return 'mk-status--neutral';
  }
}

function formatTime(iso?: string): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

onMounted(load);
</script>

<template>
  <div class="exports-page">
    <PageHeader title="Exports" description="原始用量记录导出（CSV/JSONL gzip，仅计数与元数据）。">
      <template #actions>
        <el-button type="primary" data-testid="export-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建导出' }}
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <section v-if="creating" class="create-panel" data-testid="export-create-form">
      <h3 class="panel-title">创建导出</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="格式">
          <el-select v-model="format">
            <el-option label="CSV" value="CSV" />
            <el-option label="JSONL" value="JSONL" />
          </el-select>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="从">
            <el-input v-model="from" data-testid="export-from" />
          </el-form-item>
          <el-form-item label="到">
            <el-input v-model="to" data-testid="export-to" />
          </el-form-item>
        </div>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <el-button
          type="primary"
          :loading="creating"
          data-testid="export-create-submit"
          @click="createExport"
        >
          创建导出
        </el-button>
      </el-form>
    </section>

    <el-table v-loading="loading" :data="tasks" data-testid="exports-table">
      <el-table-column prop="format" label="格式" width="90" />
      <el-table-column label="窗口" min-width="220">
        <template #default="{ row }">
          <span class="mk-mono"
            >{{ row.periodFrom.slice(0, 10) }} → {{ row.periodTo.slice(0, 10) }}</span
          >
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <span class="mk-status" :class="statusClass(row.status)">{{ row.status }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="rowCount" label="行数" width="90" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.rowCount ?? '—' }}</span></template
        >
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'SUCCEEDED'"
            link
            type="primary"
            data-testid="export-download"
            @click="download(row)"
          >
            下载
          </el-button>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: var(--miqrokey-content-form-max);
}

.panel-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}

.create-form {
  max-width: 520px;
}

.form-row {
  display: flex;
  gap: 12px;
}

.form-row .el-form-item {
  flex: 1;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}
</style>
