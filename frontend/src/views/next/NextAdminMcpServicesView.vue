<script setup lang="ts">
/**
 * NextAdminMcpServicesView — /app/mcp-services v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy MCP console: register Streamable HTTP/SSE
 * servers, manual online/offline (gated), health-check config, tool registry
 * with enable/disable and the two-level access control (Tencent doc 134890):
 * server mode (open/allowlist/denylist) + per-tool overrides while the server
 * stays fully open. Multi-selects render as checkbox groups (the v2 select is
 * single-value; consumer lists stay short at console scale).
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type {
  ApiConsumerView,
  McpAclMode,
  McpAccessView,
  McpServiceView,
  McpToolView,
} from '@/types/api';

const services = ref<McpServiceView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'name', title: '名称', minWidth: '170px' },
  { key: 'endpoint', title: '接入地址', minWidth: '220px' },
  { key: 'transport', title: '传输', width: '130px' },
  { key: 'status', title: '状态', width: '100px' },
  { key: 'healthStatus', title: '健康', width: '100px' },
  { key: 'healthCheckedAt', title: '最近检查', width: '170px' },
  { key: 'actions', title: '操作', width: '280px' },
];

const transportOptions = [
  { value: 'STREAMABLE_HTTP', label: 'Streamable HTTP' },
  { value: 'SSE', label: 'SSE' },
];

const methodOptions = [
  { value: 'GET', label: 'GET' },
  { value: 'POST', label: 'POST' },
  { value: 'PUT', label: 'PUT' },
  { value: 'DELETE', label: 'DELETE' },
  { value: 'PATCH', label: 'PATCH' },
];

const modeOptions: { value: string; label: string }[] = [
  { value: '', label: '继承服务规则' },
  { value: 'ALLOW', label: '仅名单内可调用' },
  { value: 'DENY', label: '名单内禁止' },
];

const registering = ref(false);
const form = ref({
  name: '',
  description: '',
  endpoint: '',
  transport: 'STREAMABLE_HTTP',
});
const formError = ref('');
const submitting = ref(false);

// Health config dialog
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

// Access control dialog
const accessService = ref<McpServiceView | null>(null);
const accessVisible = ref(false);
const accessLoading = ref(false);
const accessError = ref('');
const access = ref<McpAccessView | null>(null);
const consumers = ref<ApiConsumerView[]>([]);
const serverMode = ref<McpAclMode>('NONE');
const serverIds = ref<string[]>([]);
const serverSaving = ref(false);
const serverResetSaving = ref(false);
const accessNotice = ref('');
/** Draft overrides per tool: null = inherit, otherwise ALLOW/DENY + ids. */
const toolDrafts = ref<Record<string, { mode: McpAclMode | null; ids: string[] }>>({});

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const consumerOptions = computed(() =>
  consumers.value.map((c) => ({
    id: c.id,
    label: `${c.name}（${c.keyPrefix}…）`,
  })),
);

const canCreate = computed(
  () => form.value.name.trim().length > 0 && form.value.endpoint.trim().length > 0,
);

const canCreateTool = computed(
  () => toolForm.value.toolName.trim().length > 0 && toolForm.value.path.trim().length > 0,
);

function healthLabel(status: string): string {
  return status === 'HEALTHY' ? '健康' : status === 'UNHEALTHY' ? '不健康' : '未知';
}

function healthTone(status: string): 'success' | 'danger' | 'neutral' {
  return status === 'HEALTHY' ? 'success' : status === 'UNHEALTHY' ? 'danger' : 'neutral';
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    services.value = await api.adminListMcpServices();
  } catch (error) {
    loadError.value = errorText(error, '加载 MCP 服务失败。');
    if (error instanceof ApiError) {
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function registerService() {
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
    registering.value = false;
    form.value = { name: '', description: '', endpoint: '', transport: 'STREAMABLE_HTTP' };
    toast.success('MCP 服务已注册');
    await load();
  } catch (error) {
    formError.value = errorText(error, '创建失败，请稍后重试。');
  } finally {
    submitting.value = false;
  }
}

function requestStatusChange(service: McpServiceView, status: string) {
  const action = status === 'ONLINE' ? '上线' : '下线';
  confirmState.value = {
    title: `${action} MCP 服务「${service.name}」`,
    body:
      status === 'OFFLINE'
        ? '下线后该服务暂停对外提供，健康检查不会自动恢复，需手动上线。'
        : '上线后服务恢复对外提供。',
    confirmLabel: action,
    tone: status === 'OFFLINE' ? 'danger' : 'primary',
    run: async () => {
      try {
        await api.adminSetMcpStatus(service.id, status);
        toast.success(`服务已${action}`);
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(error.message);
        }
      }
    },
  };
}

async function confirmAndRun() {
  const state = confirmState.value;
  if (!state) return;
  confirmState.value = null;
  await state.run();
}

// ---- health config ----

function openConfig(service: McpServiceView) {
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
    toast.success('健康检查配置已更新');
    await load();
  } catch (error) {
    configError.value = errorText(error, '保存失败，请稍后重试。');
  } finally {
    configSaving.value = false;
  }
}

// ---- tools ----

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
    toolsError.value = errorText(error, '加载工具失败。');
  } finally {
    toolsLoading.value = false;
  }
}

async function refreshTools() {
  if (!toolsService.value) {
    return;
  }
  tools.value = await api.adminListMcpTools(toolsService.value.id);
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
    toast.success('工具已创建');
    await refreshTools();
  } catch (error) {
    toolFormError.value = errorText(error, '创建失败，请稍后重试。');
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
    toast.success(`工具已${action}`);
    await refreshTools();
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  }
}

// ---- access control ----

function toolDraft(toolId: string, mode: McpAclMode | null, ids: string[]) {
  toolDrafts.value[toolId] = { mode, ids: [...ids] };
}

function toggleToolConsumer(toolId: string, consumerId: string, checked: boolean) {
  const draft = toolDrafts.value[toolId];
  if (!draft) return;
  toolDraft(
    toolId,
    draft.mode,
    checked ? [...draft.ids, consumerId] : draft.ids.filter((id) => id !== consumerId),
  );
}

async function openAccess(service: McpServiceView) {
  accessService.value = service;
  accessVisible.value = true;
  accessError.value = '';
  toolDrafts.value = {};
  await loadAccess();
}

async function loadAccess() {
  if (!accessService.value) return;
  accessLoading.value = true;
  accessError.value = '';
  try {
    const [view, consumerList] = await Promise.all([
      api.getMcpServiceAccess(accessService.value.id),
      api.listApiConsumers(),
    ]);
    access.value = view;
    consumers.value = consumerList;
    serverMode.value = view.mode;
    serverIds.value = view.serverConsumers.map((c) => c.id);
    for (const tool of view.tools) {
      toolDraft(
        tool.toolId,
        tool.mode,
        tool.consumers.map((c) => c.id),
      );
    }
    accessNotice.value =
      view.mode === 'NONE'
        ? '全部开放：任何调用方均可访问。可在下方为单个工具配置更细的名单（工具级规则只会进一步收窄）。'
        : view.mode === 'ALLOW'
          ? '白名单：仅名单内的 API 消费者可调用该服务。'
          : '黑名单：名单内的 API 消费者被禁止调用，其余放行。';
  } catch (err) {
    accessError.value = errorText(err, '加载失败');
  } finally {
    accessLoading.value = false;
  }
}

async function saveServerMode() {
  if (!accessService.value) return;
  serverSaving.value = true;
  accessError.value = '';
  try {
    await api.setMcpAccessMode(accessService.value.id, serverMode.value);
    toast.success('服务访问模式已保存');
    await loadAccess();
  } catch (err) {
    accessError.value = errorText(err, '保存失败');
  } finally {
    serverSaving.value = false;
  }
}

async function saveServerList() {
  if (!accessService.value || serverMode.value === 'NONE') return;
  serverSaving.value = true;
  accessError.value = '';
  try {
    await api.setMcpAccessGrants(accessService.value.id, {
      mode: serverMode.value,
      consumerIds: serverIds.value,
    });
    toast.success('服务名单已更新');
    await loadAccess();
  } catch (err) {
    accessError.value = errorText(err, '保存失败');
  } finally {
    serverSaving.value = false;
  }
}

async function resetServerAccess() {
  if (!accessService.value) return;
  serverResetSaving.value = true;
  accessError.value = '';
  try {
    await api.setMcpAccessMode(accessService.value.id, 'NONE');
    toast.success('已重置为全部开放');
    await loadAccess();
  } catch (err) {
    accessError.value = errorText(err, '重置失败');
  } finally {
    serverResetSaving.value = false;
  }
}

async function saveToolDraft(toolId: string, toolName: string) {
  if (!accessService.value) return;
  const draft = toolDrafts.value[toolId];
  if (!draft) return;
  toolSaving.value = true;
  accessError.value = '';
  try {
    if (draft.mode === null) {
      await api.clearMcpAccessGrants(accessService.value.id, toolId);
      toast.success(`${toolName} 已恢复为继承服务规则`);
    } else {
      await api.setMcpAccessGrants(accessService.value.id, {
        toolId,
        mode: draft.mode,
        consumerIds: draft.ids,
      });
      toast.success(`${toolName} 的访问名单已更新`);
    }
    await loadAccess();
  } catch (err) {
    accessError.value = errorText(err, '保存失败');
  } finally {
    toolSaving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-mcp">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">MCP 服务</h1>
        <p class="ui-page-desc">
          MCP Server 管理：注册、手动上下线（下线后健康检查不会自动恢复）、健康检查配置与状态。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="primary"
          data-testid="mcp-create-open"
          @click="registering = !registering"
        >
          {{ registering ? '收起表单' : '注册 MCP 服务' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section v-if="registering" class="ui-panel next-mcp__register" data-testid="mcp-create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">注册 MCP 服务</h2>
      </div>
      <div class="ui-panel-body">
        <p class="next-mcp__hint">
          接入地址必须为 https；健康检查默认每 30 秒探测 /health，连续失败 3 次标记不健康。
        </p>
        <div class="next-mcp__register-form">
          <UiInput
            v-model="form.name"
            label="名称"
            required
            placeholder="例如 erp-mcp"
            data-testid="mcp-create-name"
          />
          <div class="ui-field">
            <span class="ui-field__label">描述</span>
            <textarea
              v-model="form.description"
              class="ui-textarea"
              rows="2"
              placeholder="用途说明（可选）"
              data-testid="mcp-create-desc"
            />
          </div>
          <div class="next-mcp__row">
            <UiSelect v-model="form.transport" label="传输类型" :options="transportOptions" />
            <UiInput
              v-model="form.endpoint"
              label="接入地址"
              required
              placeholder="https://erp.internal.example"
              data-testid="mcp-create-endpoint"
            />
          </div>
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-mcp__actions">
            <UiButton
              variant="primary"
              :disabled="!canCreate"
              :loading="submitting"
              data-testid="mcp-create-submit"
              @click="registerService"
              >注册</UiButton
            >
            <UiButton variant="ghost" @click="registering = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ services.length }} 个服务</span>
      </div>
      <UiTable
        :columns="columns"
        :data="services"
        :loading="loading"
        row-key="id"
        empty-title="还没有注册的 MCP 服务"
        empty-description="注册后网关定期探测健康状态，Agent 可经网关调用其 Tools。"
        data-testid="mcp-table"
      >
        <template #name="{ row }">
          <span class="next-mcp__name">{{ (row as McpServiceView).name }}</span>
        </template>
        <template #endpoint="{ row }">
          <span class="ui-mono next-mcp__endpoint">{{ (row as McpServiceView).endpoint }}</span>
        </template>
        <template #transport="{ row }">
          <span class="ui-mono">{{ (row as McpServiceView).transport }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as McpServiceView).status === 'ONLINE' ? 'success' : 'neutral'"
            :label="(row as McpServiceView).status === 'ONLINE' ? '在线' : '已下线'"
          />
        </template>
        <template #healthStatus="{ row }">
          <UiStatusBadge
            :tone="healthTone((row as McpServiceView).healthStatus)"
            :label="healthLabel((row as McpServiceView).healthStatus)"
          />
        </template>
        <template #healthCheckedAt="{ row }">{{
          formatTime((row as McpServiceView).healthCheckedAt)
        }}</template>
        <template #actions="{ row }">
          <div class="next-mcp__row-actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="mcp-tools"
              @click="openTools(row as McpServiceView)"
              >Tools</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="mcp-access"
              @click="openAccess(row as McpServiceView)"
              >访问控制</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="mcp-health-config"
              @click="openConfig(row as McpServiceView)"
              >健康检查</UiButton
            >
            <UiButton
              v-if="(row as McpServiceView).status === 'ONLINE'"
              variant="ghost"
              size="sm"
              class="next-mcp__danger"
              data-testid="mcp-offline"
              @click="requestStatusChange(row as McpServiceView, 'OFFLINE')"
              >下线</UiButton
            >
            <UiButton
              v-else
              variant="ghost"
              size="sm"
              data-testid="mcp-online"
              @click="requestStatusChange(row as McpServiceView, 'ONLINE')"
              >上线</UiButton
            >
          </div>
        </template>
      </UiTable>
    </section>

    <!-- Health check configuration -->
    <UiDialog
      :open="configVisible"
      :title="configService ? `健康检查 · ${configService.name}` : '健康检查'"
      width="520px"
      @update:open="configVisible = false"
    >
      <div class="next-mcp__dialog-form">
        <div class="next-mcp__row">
          <UiInput
            v-model="configForm.checkIntervalSeconds"
            label="检查间隔（秒）"
            data-testid="mcp-check-interval"
          />
          <UiInput
            v-model="configForm.checkTimeoutSeconds"
            label="超时（秒）"
            data-testid="mcp-check-timeout"
          />
        </div>
        <div class="next-mcp__row">
          <UiInput
            v-model="configForm.failThreshold"
            label="失败阈值"
            data-testid="mcp-check-fail"
          />
          <UiInput
            v-model="configForm.recoverThreshold"
            label="恢复阈值"
            data-testid="mcp-check-recover"
          />
        </div>
        <UiInput
          v-model="configForm.checkPath"
          label="检查路径"
          placeholder="/health"
          data-testid="mcp-check-path"
        />
        <p v-if="configError" class="ui-form-error">{{ configError }}</p>
      </div>
      <template #footer>
        <UiButton variant="ghost" @click="configVisible = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="configSaving"
          data-testid="mcp-config-save"
          @click="saveConfig"
          >保存</UiButton
        >
      </template>
    </UiDialog>

    <!-- Tool registry -->
    <UiDialog
      :open="toolsVisible"
      :title="toolsService ? `Tools · ${toolsService.name}` : 'Tools'"
      width="640px"
      @update:open="toolsVisible = false"
    >
      <div v-if="toolsError" class="ui-alert ui-alert--error">{{ toolsError }}</div>
      <div v-if="toolsLoading" class="next-mcp__tools-loading">
        <div v-for="n in 3" :key="n" class="ui-skeleton next-mcp__tool-skeleton">&nbsp;</div>
      </div>
      <template v-else>
        <div v-if="tools.length" class="next-mcp__tool-list" data-testid="mcp-tool-list">
          <div v-for="tool in tools" :key="tool.id" class="next-mcp__tool-row">
            <div class="next-mcp__tool-info">
              <span class="ui-mono next-mcp__tool-name">{{ tool.toolName }}</span>
              <span class="next-mcp__tool-desc">{{ tool.description || '—' }}</span>
              <span class="ui-mono next-mcp__tool-path">{{ tool.method }} {{ tool.path }}</span>
            </div>
            <UiStatusBadge
              :tone="tool.status === 'ENABLED' ? 'success' : 'neutral'"
              :label="tool.status === 'ENABLED' ? '已启用' : '已禁用'"
            />
            <UiButton
              v-if="tool.status === 'ENABLED'"
              variant="ghost"
              size="sm"
              class="next-mcp__danger"
              data-testid="mcp-tool-disable"
              @click="setToolStatus(tool, 'DISABLED')"
              >禁用</UiButton
            >
            <UiButton
              v-else
              variant="ghost"
              size="sm"
              data-testid="mcp-tool-enable"
              @click="setToolStatus(tool, 'ENABLED')"
              >启用</UiButton
            >
          </div>
        </div>
        <p v-else class="next-mcp__tool-empty">
          该服务还没有工具。<br /><span class="next-mcp__hint"
            >手动创建工具后，AI Agent 即可按工具名调用。</span
          >
        </p>
        <div class="next-mcp__tool-create">
          <UiButton
            variant="secondary"
            size="sm"
            data-testid="mcp-tool-create-open"
            @click="toolCreating = !toolCreating"
          >
            {{ toolCreating ? '收起表单' : '新建工具' }}
          </UiButton>
          <div
            v-if="toolCreating"
            class="next-mcp__tool-create-form"
            data-testid="mcp-tool-create-form"
          >
            <div class="next-mcp__row">
              <UiInput
                v-model="toolForm.toolName"
                label="工具名"
                required
                placeholder="query_order"
                data-testid="mcp-tool-name"
              />
              <UiSelect v-model="toolForm.method" label="方法" :options="methodOptions" />
            </div>
            <UiInput
              v-model="toolForm.path"
              label="路径"
              required
              placeholder="/orders/{id}"
              data-testid="mcp-tool-path"
            />
            <UiInput
              v-model="toolForm.description"
              label="描述"
              placeholder="工具用途（可选）"
              data-testid="mcp-tool-desc"
            />
            <p v-if="toolFormError" class="ui-form-error">{{ toolFormError }}</p>
            <div class="next-mcp__actions">
              <UiButton
                variant="primary"
                size="sm"
                :disabled="!canCreateTool"
                :loading="toolSaving"
                data-testid="mcp-tool-create-submit"
                @click="createTool"
                >创建</UiButton
              >
            </div>
          </div>
        </div>
      </template>
      <template #footer>
        <UiButton variant="secondary" @click="toolsVisible = false">关闭</UiButton>
      </template>
    </UiDialog>

    <!-- Two-level access control -->
    <UiDialog
      :open="accessVisible"
      :title="'访问控制 · ' + (accessService?.name ?? '')"
      width="780px"
      data-testid="mcp-access-dialog"
      @update:open="accessVisible = false"
    >
      <div v-if="accessError" class="ui-alert ui-alert--error">{{ accessError }}</div>
      <div v-if="accessLoading" class="next-mcp__access-loading">
        <div v-for="n in 3" :key="n" class="ui-skeleton next-mcp__tool-skeleton">&nbsp;</div>
      </div>
      <template v-else-if="access">
        <p class="next-mcp__access-notice">{{ accessNotice }}</p>

        <section class="next-mcp__access-section">
          <h3 class="next-mcp__access-title">服务级访问（谁能调用整个服务）</h3>
          <div class="next-mcp__access-row">
            <div class="next-mcp__segmented" data-testid="mcp-access-mode">
              <button
                type="button"
                class="next-mcp__seg"
                :class="{ 'next-mcp__seg--on': serverMode === 'NONE' }"
                @click="serverMode = 'NONE'"
              >
                全部开放
              </button>
              <button
                type="button"
                class="next-mcp__seg"
                :class="{ 'next-mcp__seg--on': serverMode === 'ALLOW' }"
                @click="serverMode = 'ALLOW'"
              >
                白名单
              </button>
              <button
                type="button"
                class="next-mcp__seg"
                :class="{ 'next-mcp__seg--on': serverMode === 'DENY' }"
                @click="serverMode = 'DENY'"
              >
                黑名单
              </button>
            </div>
            <UiButton
              variant="secondary"
              size="sm"
              :loading="serverSaving"
              data-testid="mcp-access-mode-save"
              @click="saveServerMode"
              >保存模式</UiButton
            >
          </div>
          <div v-if="serverMode !== 'NONE'" class="next-mcp__access-list-block">
            <span class="ui-field__label"
              >名单（{{ serverMode === 'ALLOW' ? '白名单' : '黑名单' }}）</span
            >
            <div class="next-mcp__check-list" data-testid="mcp-access-server-list">
              <label v-for="c in consumerOptions" :key="c.id" class="next-mcp__check">
                <input
                  v-model="serverIds"
                  type="checkbox"
                  :value="c.id"
                  data-testid="mcp-access-consumer"
                />
                <span>{{ c.label }}</span>
              </label>
              <p v-if="!consumerOptions.length" class="ui-field__hint">暂无 API 消费者</p>
            </div>
            <div class="next-mcp__actions">
              <UiButton
                variant="primary"
                size="sm"
                :loading="serverSaving"
                data-testid="mcp-access-server-save"
                @click="saveServerList"
                >保存{{ serverMode === 'ALLOW' ? '白' : '黑' }}名单</UiButton
              >
              <UiButton
                variant="secondary"
                size="sm"
                :loading="serverResetSaving"
                data-testid="mcp-access-server-reset"
                @click="resetServerAccess"
                >重置为全部开放</UiButton
              >
            </div>
          </div>
        </section>

        <section v-if="serverMode === 'NONE'" class="next-mcp__access-section">
          <h3 class="next-mcp__access-title">
            工具级访问（谁可调用某个工具；仅服务全开放时可配置）
          </h3>
          <div class="next-mcp__tool-access-list" data-testid="mcp-access-tools">
            <div
              v-for="tool in access.tools"
              :key="tool.toolId"
              class="next-mcp__tool-access"
              :data-tool-id="tool.toolId"
            >
              <div class="next-mcp__tool-access-head">
                <span class="ui-mono next-mcp__tool-name">{{ tool.toolName }}</span>
                <div
                  class="next-mcp__segmented next-mcp__segmented--sm"
                  data-testid="mcp-access-tool-mode"
                >
                  <button
                    v-for="opt in modeOptions"
                    :key="opt.value"
                    type="button"
                    class="next-mcp__seg"
                    :class="{
                      'next-mcp__seg--on':
                        (toolDrafts[tool.toolId]?.mode ?? null) ===
                        (opt.value === '' ? null : opt.value),
                    }"
                    @click="
                      toolDraft(
                        tool.toolId,
                        opt.value === '' ? null : (opt.value as 'ALLOW' | 'DENY'),
                        toolDrafts[tool.toolId]?.ids ?? [],
                      )
                    "
                  >
                    {{ opt.label }}
                  </button>
                </div>
              </div>
              <div
                v-if="toolDrafts[tool.toolId]?.mode"
                class="next-mcp__check-list next-mcp__check-list--nested"
              >
                <label v-for="c in consumerOptions" :key="c.id" class="next-mcp__check">
                  <input
                    :value="c.id"
                    :checked="toolDrafts[tool.toolId]?.ids.includes(c.id) ?? false"
                    type="checkbox"
                    data-testid="mcp-tool-consumer"
                    @change="
                      toggleToolConsumer(
                        tool.toolId,
                        c.id,
                        ($event.target as HTMLInputElement).checked,
                      )
                    "
                  />
                  <span>{{ c.label }}</span>
                </label>
                <p v-if="!consumerOptions.length" class="ui-field__hint">暂无 API 消费者</p>
              </div>
              <div class="next-mcp__actions">
                <UiButton
                  variant="primary"
                  size="sm"
                  :loading="toolSaving"
                  data-testid="mcp-access-tool-save"
                  @click="saveToolDraft(tool.toolId, tool.toolName)"
                  >保存</UiButton
                >
              </div>
            </div>
            <p v-if="!access.tools.length" class="next-mcp__hint">该服务暂无工具。</p>
          </div>
        </section>
      </template>
      <template #footer>
        <UiButton variant="secondary" @click="accessVisible = false">关闭</UiButton>
      </template>
    </UiDialog>

    <UiDialog
      v-if="confirmState"
      :open="true"
      :title="confirmState.title"
      :description="confirmState.body"
      width="440px"
      @update:open="confirmState = null"
    >
      <template #footer>
        <UiButton variant="ghost" @click="confirmState = null">取消</UiButton>
        <UiButton
          :variant="confirmState.tone === 'danger' ? 'danger' : 'primary'"
          @click="confirmAndRun"
        >
          {{ confirmState.confirmLabel }}
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

.ui-field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.ui-field__label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
  line-height: var(--ui-line-height-sm);
}

.ui-field__hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  line-height: var(--ui-line-height-sm);
}

.ui-textarea {
  width: 100%;
  min-height: 56px;
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  resize: vertical;
}

.ui-textarea:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.next-mcp__register {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
}

.next-mcp__hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground-faint);
}

.next-mcp__register-form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 560px;
}

.next-mcp__dialog-form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-mcp__row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4);
}

.next-mcp__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
}

.next-mcp__row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-1);
}

.next-mcp__name {
  font-weight: var(--ui-weight-medium);
}

.next-mcp__endpoint {
  font-size: var(--ui-font-size-xs);
  overflow-wrap: anywhere;
}

.next-mcp__danger {
  color: var(--ui-danger-fg);
}

/* tools */
.next-mcp__tools-loading,
.next-mcp__access-loading {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp__tool-skeleton {
  height: 52px;
}

.next-mcp__tool-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp__tool-row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-mcp__tool-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.next-mcp__tool-name {
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
}

.next-mcp__tool-desc {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-mcp__tool-path {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-mcp__tool-empty {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}

.next-mcp__tool-create {
  border-top: 1px solid var(--ui-border);
  padding-top: var(--ui-space-4);
  margin-top: var(--ui-space-4);
}

.next-mcp__tool-create-form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-3);
  max-width: 560px;
}

/* access control */
.next-mcp__access-notice {
  margin: 0 0 var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  background: var(--ui-info-bg);
  color: var(--ui-info-fg);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.next-mcp__access-section {
  margin-bottom: var(--ui-space-4);
  padding-bottom: var(--ui-space-4);
  border-bottom: 1px solid var(--ui-border-muted);
}

.next-mcp__access-section:last-of-type {
  border-bottom: none;
  margin-bottom: 0;
  padding-bottom: 0;
}

.next-mcp__access-title {
  margin: 0 0 var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-mcp__access-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
}

.next-mcp__access-list-block {
  margin-top: var(--ui-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp__segmented {
  display: inline-flex;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-mcp__segmented--sm .next-mcp__seg {
  height: 24px;
  padding: 0 var(--ui-space-2);
  font-size: var(--ui-font-size-xs);
}

.next-mcp__seg {
  height: 30px;
  padding: 0 var(--ui-space-3);
  border: 0;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
  white-space: nowrap;
}

.next-mcp__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-mcp__check-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  padding: var(--ui-space-1);
}

.next-mcp__check-list--nested {
  margin-top: var(--ui-space-2);
}

.next-mcp__check {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-1) var(--ui-space-2);
  border-radius: calc(var(--ui-radius-control) - 2px);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
}

.next-mcp__check:hover {
  background: var(--ui-fill-hover);
}

.next-mcp__check input {
  accent-color: var(--ui-primary);
  margin: 0;
}

.next-mcp__tool-access-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.next-mcp__tool-access {
  padding: var(--ui-space-3);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-mcp__tool-access-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
}
</style>
