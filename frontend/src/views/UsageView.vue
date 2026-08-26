<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import PageHeader from '@/components/PageHeader.vue';
import { ApiError } from '@/api/http';
import type { UsageGroupBy, UsageSummary, UsageRecordPage } from '@/types/api';

const groupBy = ref<UsageGroupBy>('project');
const summary = ref<UsageSummary | null>(null);
const summaryLoading = ref(true);
const summaryError = ref('');

const records = ref<UsageRecordPage | null>(null);
const recordsLoading = ref(true);
const recordsError = ref('');
const page = ref(1);
const pageSize = ref(20);

const cacheLevelLabel: Record<string, string> = {
  UPSTREAM: 'upstream',
  COALESCED: 'coalesced',
  L1_HIT: 'L1 hit',
  L2_HIT: 'L2 hit',
};

onMounted(() => {
  void loadSummary();
  void loadRecords();
});

async function loadSummary() {
  summaryLoading.value = true;
  summaryError.value = '';
  try {
    summary.value = await api.usageSummary(groupBy.value);
  } catch (error) {
    if (error instanceof ApiError) {
      summaryError.value = `${error.message}（requestId: ${error.requestId ?? '-'}）`;
    } else {
      summaryError.value = '加载用量汇总失败。';
    }
  } finally {
    summaryLoading.value = false;
  }
}

async function loadRecords() {
  recordsLoading.value = true;
  recordsError.value = '';
  try {
    records.value = await api.usageRecords({ page: page.value, size: pageSize.value });
  } catch (error) {
    if (error instanceof ApiError) {
      recordsError.value = `${error.message}（requestId: ${error.requestId ?? '-'}）`;
    } else {
      recordsError.value = '加载用量明细失败。';
    }
  } finally {
    recordsLoading.value = false;
  }
}

function changeGroupBy(value: UsageGroupBy) {
  groupBy.value = value;
  void loadSummary();
}

function changePage(value: number) {
  page.value = value;
  void loadRecords();
}

function formatCost(value?: string): string {
  if (value === undefined || value === null) {
    return '—';
  }
  const num = Number(value);
  if (Number.isNaN(num)) {
    return value;
  }
  return `$${num.toFixed(4)}`;
}

function formatNumber(value?: number): string {
  return value === undefined || value === null ? '—' : value.toLocaleString();
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString();
}
</script>

<template>
  <div class="usage-page">
    <PageHeader title="Usage" description="仅统计你名下 Virtual Key 产生的用量。" />

    <!-- Summary -->
    <section class="summary-panel">
      <div class="summary-toolbar">
        <span class="toolbar-label">按维度分组</span>
        <el-select
          :model-value="groupBy"
          class="group-select"
          data-testid="summary-groupby"
          @change="changeGroupBy"
        >
          <el-option value="project" label="项目" />
          <el-option value="virtual_key" label="Virtual Key" />
          <el-option value="cache_level" label="缓存级别" />
          <el-option value="day" label="日期" />
        </el-select>
      </div>

      <el-alert v-if="summaryError" type="error" :closable="false" class="block-alert" />

      <el-table
        v-loading="summaryLoading"
        :data="summary?.groups ?? []"
        class="summary-table"
        data-testid="summary-table"
      >
        <el-table-column label="分组" min-width="160">
          <template #default="{ row }">{{ row.label || row.groupKey }}</template>
        </el-table-column>
        <el-table-column label="请求" width="100" align="right">
          <template #default="{ row }">{{
            row.requests.upstream + row.requests.coalesced + row.requests.l1Hit + row.requests.l2Hit
          }}</template>
        </el-table-column>
        <el-table-column label="输入 tokens" width="130" align="right">
          <template #default="{ row }">
            <span class="mk-num">{{ formatNumber(row.tokens.input) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="输出 tokens" width="130" align="right">
          <template #default="{ row }">
            <span class="mk-num">{{ formatNumber(row.tokens.output) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Cache 读" width="120" align="right">
          <template #default="{ row }">
            <span class="mk-num">{{ formatNumber(row.tokens.cacheRead) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上游成本" width="130" align="right">
          <template #default="{ row }">
            <span class="mk-num">{{ formatCost(row.cost.upstreamPaid) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="网关观测成本" width="140" align="right">
          <template #default="{ row }">
            <span class="mk-num">{{ formatCost(row.cost.gatewayObserved) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="table-empty">当前时间范围内没有用量记录。</div>
        </template>
      </el-table>

      <div v-if="summary" class="totals-row" data-testid="summary-totals">
        <span class="totals-label">合计</span>
        <span class="mk-num"
          >{{
            formatNumber(summary.totals.requests.upstream + summary.totals.requests.coalesced)
          }}
          请求</span
        >
        <span class="mk-num"
          >{{ formatNumber(summary.totals.tokens.input) }} 输入 /
          {{ formatNumber(summary.totals.tokens.output) }} 输出 tokens</span
        >
        <span class="mk-num">{{ formatCost(summary.totals.cost.upstreamPaid) }} 上游成本</span>
      </div>
    </section>

    <!-- Records -->
    <section class="records-panel">
      <h3 class="panel-title">最近记录</h3>
      <el-alert v-if="recordsError" type="error" :closable="false" class="block-alert" />

      <el-table
        v-loading="recordsLoading"
        :data="records?.items ?? []"
        class="records-table"
        data-testid="records-table"
      >
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="模型" min-width="180">
          <template #default="{ row }">
            <span class="mk-mono">{{ row.modelId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="110">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{
              cacheLevelLabel[row.cacheLevel] ?? row.cacheLevel
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="输入" width="100" align="right">
          <template #default="{ row }">
            <span class="mk-num">{{ formatNumber(row.inputTokens) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="输出" width="100" align="right">
          <template #default="{ row }">
            <span class="mk-num">{{ formatNumber(row.outputTokens) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="90" align="right">
          <template #default="{ row }">
            <span class="mk-num">{{
              row.latencyMs === null || row.latencyMs === undefined ? '—' : `${row.latencyMs}ms`
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上游状态" width="100" align="right">
          <template #default="{ row }">{{ row.upstreamStatusCode ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="供应商请求 ID" min-width="200">
          <template #default="{ row }">
            <span class="mk-mono">{{ row.providerRequestId || '—' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="table-empty">没有用量记录。</div>
        </template>
      </el-table>

      <div v-if="records && records.total > 0" class="pagination-row">
        <span class="mk-num total-text">共 {{ records.total }} 条</span>
        <el-pagination
          layout="prev, pager, next"
          :total="records.total"
          :page-size="pageSize"
          :current-page="page"
          @current-change="changePage"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: var(--miqrokey-font-size-title);
  font-weight: 600;
  color: var(--miqrokey-text-primary);
}

.page-desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.block-alert {
  margin-bottom: 16px;
}

.summary-panel,
.records-panel {
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
}

.summary-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.toolbar-label {
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.group-select {
  width: 180px;
}

.panel-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.totals-row {
  display: flex;
  gap: 24px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--miqrokey-border-muted);
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.totals-label {
  font-weight: 600;
  color: var(--miqrokey-text-primary);
}

.pagination-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.total-text {
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.table-empty {
  padding: 16px 0;
  color: var(--miqrokey-text-secondary);
}
</style>
