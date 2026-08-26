<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { AuditEventView } from '@/types/api';

const events = ref<AuditEventView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');
const actionFilter = ref('');

async function load() {
  loading.value = true;
  try {
    events.value = await api.auditEvents({ size: 100, action: actionFilter.value || undefined });
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString();
}

onMounted(load);
</script>

<template>
  <div class="audit-page">
    <PageHeader title="Audit" description="不可修改的管理审计链（按因果提交序倒序）。" />

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <div class="mk-filter-bar">
      <el-input
        v-model="actionFilter"
        placeholder="按 action 过滤（例如 USER_CREATE）"
        class="filter-input"
      />
      <el-button type="primary" data-testid="audit-refresh" @click="load">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="events" data-testid="audit-table">
      <el-table-column label="位置" width="90" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.chainPosition }}</span></template
        >
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="action" label="Action" width="200">
        <template #default="{ row }">
          <span class="mk-mono">{{ row.action }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="targetType" label="目标类型" width="120">
        <template #default="{ row }">{{ row.targetType ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="changeSummary" label="摘要" min-width="240">
        <template #default="{ row }"
          ><span class="mk-mono">{{ row.changeSummary ?? '—' }}</span></template
        >
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.filter-input {
  width: 280px;
}
</style>
