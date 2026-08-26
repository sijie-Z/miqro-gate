<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { UsageGroupBy, UsageRecordPage, UsageSummary } from '@/types/api';

const groupBy = ref<UsageGroupBy>('project');
const modelId = ref('');
const projectId = ref('');
const summary = ref<UsageSummary | null>(null);
const summaryLoading = ref(true);
const summaryError = ref('');
const summaryRequestId = ref('');

const records = ref<UsageRecordPage | null>(null);
const recordsLoading = ref(true);
const page = ref(1);
const pageSize = ref(20);

async function load() {
  summaryLoading.value = true;
  recordsLoading.value = true;
  summaryError.value = '';
  try {
    summary.value = await api.adminUsageSummary({
      groupBy: groupBy.value,
      modelId: modelId.value || undefined,
      projectId: projectId.value || undefined,
    });
    records.value = await api.adminUsageRecords({
      modelId: modelId.value || undefined,
      projectId: projectId.value || undefined,
      page: page.value,
      size: pageSize.value,
    });
  } catch (error) {
    if (error instanceof ApiError) {
      summaryError.value = error.message;
      summaryRequestId.value = error.requestId ?? '';
    }
  } finally {
    summaryLoading.value = false;
    recordsLoading.value = false;
  }
}

function formatTime(iso?: string): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

onMounted(load);
</script>

<template>
  <div class="admin-usage-page">
    <PageHeader title="Usage" description="全租户用量：筛选条 → 汇总行 → 明细表。" />

    <div class="mk-filter-bar">
      <el-select v-model="groupBy" data-testid="usage-group-by" @change="load">
        <el-option label="项目" value="project" />
        <el-option label="Virtual Key" value="virtual_key" />
        <el-option label="缓存层级" value="cache_level" />
        <el-option label="日" value="day" />
      </el-select>
      <el-input v-model="projectId" placeholder="项目 ID（可选）" class="filter-input" />
      <el-input v-model="modelId" placeholder="模型 ID（可选）" class="filter-input" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>

    <el-alert v-if="summaryError" type="error" :closable="false" class="block-alert">
      {{ summaryError
      }}<span v-if="summaryRequestId" class="mk-mono">requestId: {{ summaryRequestId }}</span>
    </el-alert>

    <div v-if="summary && !summaryLoading" class="mk-summary-row" data-testid="usage-summary">
      <span
        >请求 <b class="mk-num">{{ summary.totals?.requests?.upstream ?? 0 }}</b></span
      >
      <span
        >输入 tokens <b class="mk-num">{{ summary.totals?.tokens?.input ?? 0 }}</b></span
      >
      <span
        >输出 tokens <b class="mk-num">{{ summary.totals?.tokens?.output ?? 0 }}</b></span
      >
      <span
        >上游成本 <b class="mk-num">{{ summary.totals?.cost?.upstreamPaid ?? 0 }}</b></span
      >
    </div>

    <el-table
      v-loading="recordsLoading"
      :data="records?.items ?? []"
      data-testid="usage-records-table"
    >
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
      </el-table-column>
      <el-table-column prop="modelId" label="模型" min-width="160" />
      <el-table-column label="输入" width="110" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.inputTokens ?? 0 }}</span></template
        >
      </el-table-column>
      <el-table-column label="输出" width="110" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.outputTokens ?? 0 }}</span></template
        >
      </el-table-column>
      <el-table-column prop="cacheLevel" label="缓存层级" width="110" />
      <el-table-column label="状态码" width="90" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.upstreamStatusCode ?? '—' }}</span></template
        >
      </el-table-column>
      <el-table-column label="Usage" width="90">
        <template #default="{ row }">
          <span v-if="row.usageMissing" class="mk-status mk-status--warning">missing</span>
          <span v-else class="mk-status mk-status--success">ok</span>
        </template>
      </el-table-column>
      <el-table-column label="Request ID" min-width="200">
        <template #default="{ row }"
          ><span class="mk-mono">{{ row.gatewayRequestId }}</span></template
        >
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-button
        :disabled="page <= 1"
        @click="
          page--;
          load();
        "
        >上一页</el-button
      >
      <span class="mk-num">第 {{ page }} 页 / 共 {{ records?.total ?? 0 }} 条</span>
      <el-button
        :disabled="(records?.items ?? []).length < pageSize"
        @click="
          page++;
          load();
        "
        >下一页</el-button
      >
    </div>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.filter-input {
  width: 220px;
}

.pager {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
}
</style>
