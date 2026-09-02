<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import { confirmDialog } from '@/utils/confirm';
import type { McpServiceView, McpToolView } from '@/types/api';

import type { McpServiceView } from '@/types/api';

const services = ref<McpServiceView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns: PrimaryTableCol[] = [
  { colKey: 'name', title: '名称', minWidth: 160 },
  { colKey: 'endpoint', title: '接入地址', minWidth: 200 },
  { colKey: 'transport', title: '传输', width: 120 },
  { colKey: 'status', title: '状态', width: 90 },
  { colKey: 'health', title: '健康', width: 100 },
  { colKey: 'healthCheckedAt', title: '最近检查', width: 170 },
  { colKey: 'actions', title: '操作', width: 200, fixed: 'right' },
];

const creating = ref(false);
const form = ref({ name: '', description: '', endpoint: '', transport: 'STREAMABLE_HTTP' });
const formError = ref('');
const submitting = ref(false);

const configService = ref<McpServiceView | null>(null);
const configVisible = ref(false);
const configForm = ref({
  checkIntervalSeconds: '30',
  checkTimeoutSeconds: '5',
  failThreshold: '3',
  recoverThreshold: '1',
  checkPath: '/health',
});
const configSaving = ref(false);
const configError = ref('');

// Tools dialog
const toolsService = ref<McpServiceView | null>(null);
const toolsVisible = ref(false);
const tools = ref<McpToolView[]>([]);
const toolsLoading = ref(false);
const toolsError = ref('');
const toolForm = ref({ toolName: '', description: '', method: 'GET', path: '' });
const toolSaving = ref(false);
const toolFormError = ref('');
const toolCreating = ref(false);

const canCreateTool = computed(
  () => toolForm.value.toolName.trim().length > 0 && toolForm.value.path.trim().length > 0,
);

const canCreate = computed(
  () => form.value.name.trim().length > 0 && form.value.endpoint.trim().length > 0,
);

function healthLabel(status: string): string {
  return status === 'HEALTHY' ? '健康' : status === 'UNHEALTHY' ? '不健康' : '未知';
}

function healthClass(status: string): string {
  return status === 'HEALTHY' ? 'success' : status === 'UNHEALTHY' ? 'danger' : 'neutral';
}

function formatTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    services.value = await api.adminListMcpServices();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载 MCP 服务失败。';
    }
  } finally {
    loading.value = false;
  }
}

async function createService() {
  if (!canCreate.value) {
    formError.value = '请填写名称与接入地址。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    await api.adminCreateMcpService({
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      endpoint: form.value.endpoint.trim(),
      transport: form.value.transport,
    });
    creating.value = false;
    form.value = { name: '', description: '', endpoint: '', transport: 'STREAMABLE_HTTP' };
    MessagePlugin.success('MCP 服务已注册');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function setStatus(service: McpServiceView, status: string) {
  const action = status === 'ONLINE' ? '上线' : '下线';
  try {
    await confirmDialog({
      header: `${action} MCP 服务「${service.name}」`,
      body:
        status === 'OFFLINE'
          ? '下线后该服务暂停对外提供，健康检查不会自动恢复，需手动上线。'
          : '上线后服务恢复对外提供。',
      confirmBtn: action,
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.adminSetMcpStatus(service.id, status);
    MessagePlugin.success(`服务已${action}`);
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  }
}

async function openConfig(service: McpServiceView) {
  configService.value = service;
  configForm.value = {
    checkIntervalSeconds: String(service.checkIntervalSeconds),
    checkTimeoutSeconds: String(service.checkTimeoutSeconds),
    failThreshold: String(service.failThreshold),
    recoverThreshold: String(service.recoverThreshold),
    checkPath: service.checkPath,
  };
  configError.value = '';
  configVisible.value = true;
}

async function saveConfig() {
  if (!configService.value) {
    return;
  }
  configSaving.value = true;
  configError.value = '';
  try {
    await api.adminUpdateMcpHealthConfig(configService.value.id, {
      checkIntervalSeconds: Number(configForm.value.checkIntervalSeconds),
      checkTimeoutSeconds: Number(configForm.value.checkTimeoutSeconds),
      failThreshold: Number(configForm.value.failThreshold),
      recoverThreshold: Number(configForm.value.recoverThreshold),
      checkPath: configForm.value.checkPath.trim(),
    });
    configVisible.value = false;
    MessagePlugin.success('健康检查配置已更新');
    await load();
  } catch (error) {
    configError.value = error instanceof ApiError ? error.message : '保存失败，请稍后重试。';
  } finally {
    configSaving.value = false;
  }
}

async function openTools(service: McpServiceView) {
  toolsService.value = service;
  tools.value = [];
  toolsError.value = '';
  toolForm.value = { toolName: '', description: '', method: 'GET', path: '' };
  toolCreating.value = false;
  toolsVisible.value = true;
  toolsLoading.value = true;
  try {
    tools.value = await api.adminListMcpTools(service.id);
  } catch (error) {
    toolsError.value = error instanceof ApiError ? error.message : '加载工具失败。';
  } finally {
    toolsLoading.value = false;
  }
}

async function createTool() {
  if (!toolsService.value || !canCreateTool.value) {
    toolFormError.value = '请填写工具名与路径。';
    return;
  }
  toolSaving.value = true;
  toolFormError.value = '';
  try {
    await api.adminCreateMcpTool(toolsService.value.id, {
      toolName: toolForm.value.toolName.trim(),
      description: toolForm.value.description.trim() || undefined,
      method: toolForm.value.method,
      path: toolForm.value.path.trim(),
    });
    toolCreating.value = false;
    toolForm.value = { toolName: '', description: '', method: 'GET', path: '' };
    MessagePlugin.success('工具已创建');
    tools.value = await api.adminListMcpTools(toolsService.value.id);
  } catch (error) {
    toolFormError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    toolSaving.value = false;
  }
}

async function setToolStatus(tool: McpToolView, status: string) {
  if (!toolsService.value) {
    return;
  }
  const action = status === 'ENABLED' ? '启用' : '禁用';
  try {
    await api.adminSetMcpToolStatus(toolsService.value.id, tool.id, status);
    MessagePlugin.success(`工具已${action}`);
    tools.value = await api.adminListMcpTools(toolsService.value.id);
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  }
}

onMounted(load);
</script>

<template>
  <div class="mcp-page">
    <PageHeader
      title="MCP 服务"
      description="MCP Server 管理：注册、手动上下线（下线后健康检查不会自动恢复）、健康检查配置与状态。"
    >
      <template #actions>
        <t-button theme="primary" data-testid="mcp-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '注册 MCP 服务' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="creating" class="create-panel" data-testid="mcp-create-form">
      <h3 class="panel-title">注册 MCP 服务</h3>
      <p class="hint">
        接入地址必须为 https；健康检查默认每 30 秒探测 /health，连续失败 3 次标记不健康。
      </p>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required-mark>
          <t-input v-model="form.name" placeholder="例如 erp-mcp" data-testid="mcp-create-name" />
        </t-form-item>
        <t-form-item label="描述">
          <t-input v-model="form.description" placeholder="用途说明（可选）" />
        </t-form-item>
        <t-form-item label="传输类型">
          <t-select v-model="form.transport">
            <t-option label="Streamable HTTP" value="STREAMABLE_HTTP" />
            <t-option label="SSE" value="SSE" />
          </t-select>
        </t-form-item>
        <t-form-item label="接入地址" required-mark>
          <t-input
            v-model="form.endpoint"
            placeholder="https://erp.internal.example"
            data-testid="mcp-create-endpoint"
          />
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <t-button
          theme="primary"
          :disabled="!canCreate"
          :loading="submitting"
          data-testid="mcp-create-submit"
          @click="createService"
        >
          注册
        </t-button>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="columns"
        :data="services"
        class="mcp-table"
        data-testid="mcp-table"
      >
        <template #name="{ row }">
          <span class="mcp-name">{{ row.name }}</span>
        </template>
        <template #endpoint="{ row }">
          <span class="mk-mono mcp-endpoint">{{ row.endpoint }}</span>
        </template>
        <template #transport="{ row }">
          <span class="mk-mono">{{ row.transport }}</span>
        </template>
        <template #status="{ row }">
          <span
            class="mk-status"
            :class="row.status === 'ONLINE' ? 'mk-status--success' : 'mk-status--neutral'"
          >
            {{ row.status === 'ONLINE' ? 'Online' : 'Offline' }}
          </span>
        </template>
        <template #health="{ row }">
          <span class="mk-status" :class="`mk-status--${healthClass(row.healthStatus)}`">
            {{ healthLabel(row.healthStatus) }}
          </span>
        </template>
        <template #healthCheckedAt="{ row }">{{ formatTime(row.healthCheckedAt) }}</template>
        <template #actions="{ row }">
          <t-button variant="text" data-testid="mcp-tools" @click="openTools(row)">Tools</t-button>
          <t-button variant="text" data-testid="mcp-health-config" @click="openConfig(row)"
            >健康检查</t-button
          >
          <t-button
            v-if="row.status === 'ONLINE'"
            variant="text"
            theme="danger"
            data-testid="mcp-offline"
            @click="setStatus(row, 'OFFLINE')"
          >
            下线
          </t-button>
          <t-button v-else variant="text" data-testid="mcp-online" @click="setStatus(row, 'ONLINE')"
            >上线</t-button
          >
        </template>
        <template #empty>
          <div class="table-empty">
            <p>还没有注册的 MCP 服务。</p>
            <p class="hint">注册后网关定期探测健康状态，Agent 可经网关调用其 Tools。</p>
          </div>
        </template>
      </t-table>
    </t-loading>

    <t-dialog
      v-model:visible="configVisible"
      :header="configService ? `健康检查 · ${configService.name}` : '健康检查'"
      width="520px"
      :close-on-overlay-click="false"
    >
      <t-form label-align="top">
        <div class="form-row">
          <t-form-item label="检查间隔（秒）">
            <t-input v-model="configForm.checkIntervalSeconds" type="number" />
          </t-form-item>
          <t-form-item label="超时（秒）">
            <t-input v-model="configForm.checkTimeoutSeconds" type="number" />
          </t-form-item>
        </div>
        <div class="form-row">
          <t-form-item label="失败阈值">
            <t-input v-model="configForm.failThreshold" type="number" />
          </t-form-item>
          <t-form-item label="恢复阈值">
            <t-input v-model="configForm.recoverThreshold" type="number" />
          </t-form-item>
        </div>
        <t-form-item label="检查路径">
          <t-input
            v-model="configForm.checkPath"
            placeholder="/health"
            data-testid="mcp-check-path"
          />
        </t-form-item>
        <p v-if="configError" class="form-error">{{ configError }}</p>
      </t-form>
      <template #footer>
        <t-button
          theme="primary"
          :loading="configSaving"
          data-testid="mcp-config-save"
          @click="saveConfig"
        >
          保存
        </t-button>
        <t-button @click="configVisible = false">取消</t-button>
      </template>
    </t-dialog>

    <t-dialog
      v-model:visible="toolsVisible"
      :header="toolsService ? `Tools · ${toolsService.name}` : 'Tools'"
      width="640px"
      :close-on-overlay-click="false"
    >
      <t-loading :loading="toolsLoading" size="small">
        <div v-if="toolsError" class="form-error">{{ toolsError }}</div>
        <div v-if="tools.length" class="tool-rows" data-testid="mcp-tool-list">
          <div v-for="tool in tools" :key="tool.id" class="tool-row">
            <div class="tool-info">
              <span class="mk-mono tool-name">{{ tool.toolName }}</span>
              <span class="tool-desc">{{ tool.description || '—' }}</span>
              <span class="mk-mono tool-path">{{ tool.method }} {{ tool.path }}</span>
            </div>
            <span
              class="mk-status"
              :class="tool.status === 'ENABLED' ? 'mk-status--success' : 'mk-status--neutral'"
            >
              {{ tool.status === 'ENABLED' ? 'Enabled' : 'Disabled' }}
            </span>
            <t-button
              v-if="tool.status === 'ENABLED'"
              variant="text"
              theme="danger"
              data-testid="mcp-tool-disable"
              @click="setToolStatus(tool, 'DISABLED')"
            >
              禁用
            </t-button>
            <t-button
              v-else
              variant="text"
              data-testid="mcp-tool-enable"
              @click="setToolStatus(tool, 'ENABLED')"
            >
              启用
            </t-button>
          </div>
        </div>
        <div v-else-if="!toolsLoading && !toolsError" class="tool-empty">
          <p>该服务还没有工具。</p>
          <p class="hint">手动创建工具后，AI Agent 即可按工具名调用。</p>
        </div>
      </t-loading>
      <div class="tool-create">
        <t-button
          variant="outline"
          size="small"
          data-testid="mcp-tool-create-open"
          @click="toolCreating = !toolCreating"
        >
          {{ toolCreating ? '收起表单' : '新建工具' }}
        </t-button>
        <div v-if="toolCreating" class="tool-create-form" data-testid="mcp-tool-create-form">
          <t-form label-align="top">
            <div class="form-row">
              <t-form-item label="工具名" required-mark>
                <t-input
                  v-model="toolForm.toolName"
                  placeholder="query_order"
                  data-testid="mcp-tool-name"
                />
              </t-form-item>
              <t-form-item label="方法">
                <t-select v-model="toolForm.method">
                  <t-option label="GET" value="GET" />
                  <t-option label="POST" value="POST" />
                  <t-option label="PUT" value="PUT" />
                  <t-option label="DELETE" value="DELETE" />
                  <t-option label="PATCH" value="PATCH" />
                </t-select>
              </t-form-item>
            </div>
            <t-form-item label="路径" required-mark>
              <t-input
                v-model="toolForm.path"
                placeholder="/orders/{id}"
                data-testid="mcp-tool-path"
              />
            </t-form-item>
            <t-form-item label="描述">
              <t-input v-model="toolForm.description" placeholder="工具用途（可选）" />
            </t-form-item>
            <p v-if="toolFormError" class="form-error">{{ toolFormError }}</p>
            <t-button
              theme="primary"
              size="small"
              :disabled="!canCreateTool"
              :loading="toolSaving"
              data-testid="mcp-tool-create-submit"
              @click="createTool"
            >
              创建
            </t-button>
          </t-form>
        </div>
      </div>
      <template #footer>
        <t-button @click="toolsVisible = false">关闭</t-button>
      </template>
    </t-dialog>
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
  max-width: 760px;
}

.panel-title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.create-form {
  max-width: 520px;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.mcp-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.mcp-name {
  font-weight: 500;
}

.mcp-endpoint {
  font-size: 12px;
  overflow-wrap: anywhere;
}

.table-empty {
  padding: 24px 0;
  color: var(--miqrokey-text-secondary);
}

.table-empty .hint {
  font-size: 13px;
  color: var(--miqrokey-text-disabled);
  margin: 4px 0 0;
}

.form-row {
  display: flex;
  gap: 12px;
}

.form-row .t-form__item {
  flex: 1;
}

.tool-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.tool-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--miqrokey-border-muted);
  border-radius: var(--miqrokey-radius-panel);
}

.tool-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.tool-name {
  font-weight: 500;
}

.tool-desc {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
}

.tool-path {
  font-size: 12px;
  color: var(--miqrokey-text-disabled);
}

.tool-empty {
  padding: 16px 0;
  color: var(--miqrokey-text-secondary);
}

.tool-empty .hint {
  font-size: 12px;
  color: var(--miqrokey-text-disabled);
  margin: 4px 0 0;
}

.tool-create {
  border-top: 1px solid var(--miqrokey-border-muted);
  padding-top: 16px;
}

.tool-create-form {
  margin-top: 12px;
}
</style>
