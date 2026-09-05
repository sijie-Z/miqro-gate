<script setup lang="ts">
/**
 * NextAdminMcpAccessLogsView — MCP 代理调用元数据日志（F15, V29）。
 * 只读审计尾：服务/消费者过滤 + 刷新；列=时间/消费者/服务/信封方法/
 * 工具/终态/HTTP 状态/网关请求 ID。正文永不出现（网关侧已保证）。
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiInput, UiStatusBadge, UiTable } from '@/ui';
import type { McpAccessLogEntry } from '@/types/generated-api';

const entries = ref<McpAccessLogEntry[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');
const serviceFilter = ref('');
const consumerFilter = ref('');

const columns = [
  { key: 'occurredAt', title: '时间', width: '150px' },
  { key: 'consumerName', title: '消费者', minWidth: '150px' },
  { key: 'serviceName', title: '服务', minWidth: '140px' },
  { key: 'rpcMethod', title: '方法', width: '130px' },
  { key: 'toolName', title: '工具', minWidth: '120px' },
  { key: 'status', title: '结果', width: '150px' },
  { key: 'httpStatus', title: 'HTTP', width: '80px', align: 'center' as const },
  { key: 'gatewayRequestId', title: '网关请求 ID', width: '300px' },
];

const STATUS_META: Record<string, { label: string; tone: 'success' | 'danger' | 'warning' | 'neutral' }> = {
  FORWARDED: { label: '已转发', tone: 'success' },
  SERVICE_DENIED: { label: '服务被拒', tone: 'danger' },
  TOOL_DENIED: { label: '工具被拒', tone: 'danger' },
  TOOL_UNAVAILABLE: { label: '工具不可用', tone: 'danger' },
  INVALID_ENVELOPE: { label: '信封非法', tone: 'warning' },
  UPSTREAM_FAILURE: { label: '上游失败', tone: 'danger' },
  CIRCUIT_OPEN: { label: '熔断打开', tone: 'danger' },
};

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    entries.value = await api.listMcpAccessLogs({
      service: serviceFilter.value.trim() || undefined,
      consumer: consumerFilter.value.trim() || undefined,
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

function resetFilters() {
  serviceFilter.value = '';
  consumerFilter.value = '';
  void load();
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
  <div class="ui-page next-mcp-logs">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">MCP 访问日志</h1>
        <p class="ui-page-desc">
          MCP 代理调用审计尾（最近 200 条，可过滤）；仅元数据，不含任何请求正文或响应内容。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="mcp-logs-refresh" @click="load">刷新</UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="mcp-logs-load-error">
      {{ loadError }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section class="ui-panel">
      <div class="ui-filter-bar next-mcp-logs__filters">
        <UiInput
          v-model="serviceFilter"
          label="服务名"
          placeholder="如 weather-mcp"
          data-testid="mcp-logs-service-filter"
          class="next-mcp-logs__filter"
        />
        <UiInput
          v-model="consumerFilter"
          label="消费者名"
          placeholder="如 drill-allowed"
          data-testid="mcp-logs-consumer-filter"
          class="next-mcp-logs__filter"
        />
        <div class="next-mcp-logs__filter-actions">
          <UiButton variant="primary" size="sm" data-testid="mcp-logs-query" @click="load">
            查询
          </UiButton>
          <UiButton variant="ghost" size="sm" data-testid="mcp-logs-reset" @click="resetFilters">
            重置
          </UiButton>
        </div>
      </div>
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ entries.length }} 条</span>
      </div>
      <UiTable
        :columns="columns"
        :data="entries"
        :loading="loading"
        row-key="gatewayRequestId"
        empty-title="暂无 MCP 调用日志"
        data-testid="mcp-logs-table"
      >
        <template #occurredAt="{ row }">{{
          formatTime((row as McpAccessLogEntry).occurredAt)
        }}</template>
        <template #consumerName="{ row }">
          <span class="ui-mono">{{ (row as McpAccessLogEntry).consumerName }}</span>
        </template>
        <template #serviceName="{ row }">
          {{ (row as McpAccessLogEntry).serviceName }}
        </template>
        <template #rpcMethod="{ row }">
          <span class="ui-mono">{{ (row as McpAccessLogEntry).rpcMethod ?? '—' }}</span>
        </template>
        <template #toolName="{ row }">
          <span class="ui-mono">{{ (row as McpAccessLogEntry).toolName ?? '—' }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="STATUS_META[(row as McpAccessLogEntry).status]?.tone ?? 'neutral'"
            :label="STATUS_META[(row as McpAccessLogEntry).status]?.label ?? (row as McpAccessLogEntry).status"
          />
        </template>
        <template #httpStatus="{ row }">
          <span class="ui-mono">{{ (row as McpAccessLogEntry).httpStatus ?? '—' }}</span>
        </template>
        <template #gatewayRequestId="{ row }">
          <span class="ui-mono ui-muted" :title="(row as McpAccessLogEntry).gatewayRequestId">{{
            (row as McpAccessLogEntry).gatewayRequestId.slice(0, 8)
          }}</span>
        </template>
      </UiTable>
    </section>
  </div>
</template>

<style scoped>
.next-mcp-logs__filters {
  display: flex;
  gap: var(--ui-space-3);
  align-items: flex-end;
  padding: var(--ui-space-3) var(--ui-space-4);
  border-bottom: 1px solid var(--ui-border-muted);
  flex-wrap: wrap;
}

.next-mcp-logs__filter {
  width: 220px;
}

.next-mcp-logs__filter-actions {
  display: flex;
  gap: var(--ui-space-2);
  padding-bottom: 4px;
}
</style>
